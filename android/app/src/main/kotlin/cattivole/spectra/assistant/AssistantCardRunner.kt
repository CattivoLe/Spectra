package cattivole.spectra.assistant

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.EngineController
import alexcattivo.localchat.engine.GenerationEvent
import alexcattivo.localchat.engine.GenerationParams
import alexcattivo.localchat.kit.ChatStore
import alexcattivo.localchat.kit.web.ImageResult
import alexcattivo.localchat.kit.web.SearchPayload
import alexcattivo.localchat.kit.web.WebSearchException
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import cattivole.spectra.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/** One block under a card's findings: a model text (summary, detail, deep analysis) or a check a button ran. */
class CardEntry(val action: CardAction?, val label: String) {
    enum class State { WAITING, RUNNING, DONE, STOPPED, FAILED }

    var text by mutableStateOf("")
    var state by mutableStateOf(State.WAITING)
    /** From the tap to the first words, and to the end: what the card is measured by. */
    var firstWordsMs by mutableStateOf<Long?>(null)
    var totalMs by mutableStateOf<Long?>(null)
    /** The chat message this text is saved as: continuing it rewrites that message instead of adding one. */
    var savedMessage: UUID? = null
    /** What a web search brought for this text: sources (and pictures of the device) shown with it. */
    var web by mutableStateOf<SearchPayload?>(null)
    /** The web search's line while it runs, or why it did not: «Searching: «…»», «Search failed». */
    var webStatus by mutableStateOf<String?>(null)
    var webBusy by mutableStateOf(false)

    val isCheck: Boolean get() = action == null
    val active: Boolean get() = state == State.WAITING || state == State.RUNNING
}

/** A summary card while the app runs: its facts, what ran on it, and whether it can still act (a saved one cannot). */
class AssistantCard(val id: UUID, val subject: CardSubject?, facts: CardFacts, val createdAt: Long, val live: Boolean) {
    var facts by mutableStateOf(facts)
    val entries = mutableStateListOf<CardEntry>()
    /** The saved head message, rewritten when a check changes the findings. */
    var headMessage: UUID? = null
    /** The last check a step button ran: every later model action reads its result. */
    var lastCheck: CardCheck? = null
    /** Step calls already run on this card: their buttons are not offered again. */
    val ranSteps = mutableStateListOf<String>()

    /** The model actions that finished since the last check: offering them again would only repeat them. */
    val doneSinceCheck: Set<CardAction>
        get() {
            val lastCheck = entries.indexOfLast { it.isCheck }
            return entries.drop(lastCheck + 1).filter { it.state == CardEntry.State.DONE }.mapNotNull { it.action }.toSet()
        }

    val busy: Boolean get() = entries.any { it.active }

    /** The model's last text, when it was cut short: what "Continue" picks up. */
    val stopped: CardEntry?
        get() = entries.lastOrNull { !it.isCheck }?.takeIf { it.state == CardEntry.State.STOPPED && !busy }
}

/**
 * Runs summary cards: opens one for a ✨ button, puts each model action through the engine as a fresh short
 * generation with its own prompt, runs the step buttons' checks, and keeps every card as a chat in the Summaries
 * folder so the Assistant tab lists them.
 */
class AssistantCardRunner(
    private val assistant: AssistantModel,
    private val store: ChatStore,
    private val controller: EngineController,
    private val sources: AssistantSources,
    private val actions: AssistantActions,
    private val scope: CoroutineScope,
) {
    private val cards = mutableMapOf<UUID, AssistantCard>()
    /** Cards the list asked to continue: their screen picks the text up as soon as it opens. */
    private val resumeOnOpen = mutableSetOf<UUID>()

    fun continueOnOpen(id: UUID) {
        resumeOnOpen.add(id)
    }

    fun takeContinue(id: UUID): Boolean = resumeOnOpen.remove(id)

    /** A card in memory whose last text was stopped: the Assistant tab offers to continue it. */
    fun isStopped(id: UUID): Boolean = cards[id]?.stopped != null
    private var job: Job? = null
    private var running: CardEntry? = null

    /** A new card about [subject], its summary started at once. Null when there is nothing to summarize. */
    suspend fun open(subject: CardSubject): UUID? {
        val facts = sources.cardFacts(subject) ?: return null
        assistant.ensureStarted()
        val chat = store.createChat(systemPrompt = CardSubjects.encode(subject), folderID = assistant.recordingFolder())
        store.rename(chat.id, facts.title)
        val head = store.append(ChatMessage.Role.User, AssistantCards.render(facts.title, facts.findings), chat.id)
        val card = AssistantCard(chat.id, subject, facts, chat.createdAt, live = true).also { it.headMessage = head.id }
        cards[chat.id] = card
        Log.i(TAG, "card ${subject.kind}: ${facts.findings.size} findings shown at once")
        // Nothing measured yet: the buttons are the whole card, there is nothing for the model to explain.
        if (!facts.awaitsCheck) run(card, CardAction.SUMMARY)
        return chat.id
    }

    /** The card in memory, or a saved one read back from the store (then it only shows what was written). */
    suspend fun card(id: UUID): AssistantCard? {
        cards[id]?.let { return it }
        val chat = store.chat(id) ?: return null
        val messages = store.messages(id)
        val head = messages.firstOrNull { it.role == ChatMessage.Role.User } ?: return null
        val (title, findings) = AssistantCards.parse(head.content) ?: return null
        val subject = CardSubjects.decode(chat.systemPrompt)
        val facts = CardFacts(subject ?: CardSubject.Lan, title, findings, emptyList(), "", "")
        val card = AssistantCard(id, subject, facts, chat.createdAt, live = false)
        card.headMessage = head.id
        for (message in messages) {
            if (message.role != ChatMessage.Role.Assistant) continue
            val (action, text) = savedText(message.content)
            card.entries.add(
                CardEntry(action, action?.heading ?: text).also {
                    it.text = if (action != null) text else ""
                    it.state = if (message.interrupted) CardEntry.State.STOPPED else CardEntry.State.DONE
                    it.savedMessage = message.id
                    it.web = message.searchJson?.let(SearchPayload::fromJson)
                },
            )
        }
        return card
    }

    /**
     * Picks up the card's last text that was stopped — by leaving, by another card, or by the app closing: the same
     * action runs again in its place. A saved card comes back to life first, reading its subject's current data.
     * Returns the card now on screen.
     */
    fun resume(card: AssistantCard): AssistantCard {
        val stopped = card.stopped ?: return card
        val action = stopped.action ?: return card
        val live = if (card.live) card else revive(card) ?: return card
        val index = live.entries.indexOfLast { !it.isCheck }.takeIf { it >= 0 } ?: return live
        stop()
        val entry = CardEntry(action, action.heading).also {
            it.savedMessage = stopped.savedMessage
            // What the web gave the stopped text is used again: nothing goes out a second time.
            it.web = stopped.web
        }
        live.entries[index] = entry
        job = scope.launch { generate(live, entry, action) }
        return live
    }

    private fun revive(saved: AssistantCard): AssistantCard? {
        val subject = saved.subject ?: return null
        val facts = sources.cardFacts(subject) ?: saved.facts
        val live = AssistantCard(saved.id, subject, facts, saved.createdAt, live = true)
        live.headMessage = saved.headMessage
        live.entries.addAll(saved.entries)
        cards[saved.id] = live
        return live
    }

    /** Starts a model action on a card, stopping whatever card text is being written now. */
    fun run(card: AssistantCard, action: CardAction) {
        if (!card.live) return
        stop()
        val entry = CardEntry(action, action.heading)
        card.entries.add(entry)
        job = scope.launch { generate(card, entry, action) }
    }

    /** Runs a step button's check, refreshes the findings, then writes a new short summary. */
    fun runStep(card: AssistantCard, step: CardStep) {
        if (!card.live) return
        stop()
        val entry = CardEntry(null, step.label).apply {
            state = CardEntry.State.RUNNING
            text = AssistantActions.runningLabel(step.tool)
        }
        card.entries.add(entry)
        card.ranSteps.add(step.call)
        job = scope.launch {
            val result = try {
                actions.perform(step.call)
            } catch (cancelled: CancellationException) {
                entry.state = CardEntry.State.STOPPED
                entry.text = ""
                throw cancelled
            } catch (error: Throwable) {
                entry.state = CardEntry.State.FAILED
                entry.text = error.localizedMessage ?: error.toString()
                return@launch
            }
            entry.state = CardEntry.State.DONE
            entry.text = ""
            if (runCatching { store.append(ChatMessage.Role.Assistant, "✓ ${step.label}", card.id) }.isFailure) return@launch
            val subject = card.subject ?: return@launch
            val fresh = sources.cardFacts(subject) ?: card.facts
            // The check's own words go to the model as their own block: a capture or a GATT read says more than
            // the refreshed findings, and the model is asked about the check, not for the summary again.
            card.lastCheck = CardCheck(AssistantActions.title(step.tool), result, step.tool)
            card.facts = fresh
            card.headMessage?.let { store.update(it, AssistantCards.render(fresh.title, fresh.findings)) }
            val explained = CardEntry(CardAction.CHECK, CardAction.CHECK.heading)
            card.entries.add(explained)
            generate(card, explained, CardAction.CHECK)
        }
    }

    /** Stops the card text or check under way; what was written so far stays. */
    fun stop() {
        val current = job ?: return
        job = null
        if (running != null) controller.stop()
        current.cancel()
    }

    private suspend fun generate(card: AssistantCard, entry: CardEntry, action: CardAction) {
        val tapped = SystemClock.elapsedRealtime()
        entry.state = CardEntry.State.WAITING
        if (assistant.assistantModelFile == null) {
            entry.state = CardEntry.State.FAILED
            entry.text = tr("The assistant's model is not downloaded yet: Assistant tab → ⋮ → Model.")
            return
        }
        // "More detail" and "Deep analysis" read the web first, when it is on: what the device is, known problems.
        if (entry.web == null) {
            try {
                entry.web = lookUp(card, entry, action)
            } catch (cancelled: CancellationException) {
                entry.state = CardEntry.State.STOPPED
                entry.webBusy = false
                throw cancelled
            }
        }
        // The model may still be loading, or an old chat may be finishing its answer: wait for the engine.
        val ready = try {
            snapshotFlow { controller.state to assistant.loadError }.first { (state, error) ->
                state == EngineController.State.Ready || state == EngineController.State.UnloadedForMemory || error != null
            }
        } catch (cancelled: CancellationException) {
            // Stopped before the model was free: the block must not keep waiting (it would hold the buttons).
            entry.state = CardEntry.State.STOPPED
            throw cancelled
        }
        if (ready.second != null && ready.first != EngineController.State.Ready) {
            entry.state = CardEntry.State.FAILED
            entry.text = tr("The model did not load: %s", ready.second ?: "")
            return
        }
        val messages = listOf(
            ChatMessage(ChatMessage.Role.System, CardPrompts.system(action, card.facts.subject.kind, tool = card.lastCheck?.tool, web = entry.web != null)),
            ChatMessage(ChatMessage.Role.User, CardPrompts.user(card.facts, action, card.lastCheck, earlierSummary(card, entry), web = entry.web)),
        )
        Log.i(TAG, "card ${card.facts.subject.kind} ${action.name}: system ${messages[0].content.length} chars, user ${messages[1].content.length} chars")
        val params = GenerationParams(maxTokens = action.maxTokens, temperature = 0.3f)
        entry.state = CardEntry.State.RUNNING
        running = entry
        val text = StringBuilder()
        var outcome = CardEntry.State.DONE
        var cut = false
        try {
            controller.send(messages, params).collect { event ->
                when (event) {
                    is GenerationEvent.Text -> {
                        text.append(event.piece)
                        if (entry.firstWordsMs == null && text.isNotBlank()) entry.firstWordsMs = SystemClock.elapsedRealtime() - tapped
                        entry.text = AssistantText.polish(text.toString()).trim()
                    }
                    is GenerationEvent.Finished -> {
                        val stats = event.stats
                        cut = stats.stopReason == alexcattivo.localchat.engine.StopReason.MaxTokens
                        Log.i(
                            TAG,
                            "card ${card.facts.subject.kind} ${action.name}: prompt ${stats.promptTokens} tok (${stats.reusedPromptTokens} reused), " +
                                "first token ${"%.1f".format(stats.timeToFirstTokenSeconds)} s, ${stats.generatedTokens} tok in " +
                                "${"%.1f".format(stats.generationSeconds)} s, ${stats.stopReason}",
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            outcome = CardEntry.State.STOPPED
        } catch (error: Throwable) {
            outcome = CardEntry.State.FAILED
            if (text.isBlank()) text.append(error.localizedMessage ?: error.toString())
        } finally {
            running = null
        }
        entry.totalMs = SystemClock.elapsedRealtime() - tapped
        // Out of length: say so with an ellipsis rather than end mid-sentence as if that were all.
        entry.text = AssistantText.polish(text.toString()).trim().let { if (cut && it.isNotEmpty()) "$it …" else it }
        entry.state = outcome
        if (outcome == CardEntry.State.DONE && entry.text.isNotEmpty()) assistant.haptics.done()
        Log.i(TAG, "card ${card.facts.subject.kind} ${action.name}: first words ${entry.firstWordsMs} ms, done ${entry.totalMs} ms, $outcome")
        if (entry.text.isNotEmpty() && outcome != CardEntry.State.FAILED) {
            val content = "**${action.heading}**\n\n${entry.text}"
            val stopped = outcome == CardEntry.State.STOPPED
            val earlier = entry.savedMessage
            scope.launch {
                // The card may have been deleted while the model wrote: then there is nothing to save it in.
                if (card.id !in cards) return@launch
                runCatching {
                    if (earlier != null) {
                        store.update(earlier, content, interrupted = stopped)
                    } else {
                        val saved = store.append(ChatMessage.Role.Assistant, content, card.id, assistant.currentModelName)
                        entry.savedMessage = saved.id
                        if (stopped) store.update(saved.id, saved.content, interrupted = true)
                    }
                    // The sources and pictures stay with the text, so a saved card shows them too.
                    val web = entry.web
                    val id = entry.savedMessage
                    if (web != null && id != null) store.setSearch(web.toJson(), id)
                }.onFailure { Log.w(TAG, "card text not saved: ${it.message}") }
            }
        }
    }

    /**
     * The web search for a "More detail" or "Deep analysis" text: one query about what the subject is (its maker
     * and model, never an address or a name), confirmed like any other search, written to the log and kept in the
     * search cache; for a device also a few pictures. Null when the web is off, not set up, declined or useless.
     */
    private suspend fun lookUp(card: AssistantCard, entry: CardEntry, action: CardAction): SearchPayload? {
        val web = assistant.web
        val subject = card.subject ?: return null
        val lookup = CardWeb.lookup(sources.model, subject, action) ?: return null
        if (!web.isEnabled) return null
        val research = try {
            web.makeResearch(light = action == CardAction.DETAILS)
        } catch (error: WebSearchException) {
            entry.webStatus = tr("The web search is not set up: Assistant tab → ⋮ → Internet.")
            return null
        }
        val provider = research.provider
        val providerName = provider.name
        entry.webBusy = true
        try {
            val reused = runCatching { store.cachedSearch(lookup.query, providerName) }.getOrNull()
            var query = reused?.query ?: lookup.query
            if (reused == null && web.confirmEachSearch) {
                entry.webStatus = tr("Waiting for your OK to search the web…")
                query = web.approve?.invoke(query)?.trim()?.takeIf { it.isNotEmpty() } ?: run {
                    entry.webStatus = tr("Web search declined: the text uses only what the app measured.")
                    return null
                }
            }
            entry.webStatus = if (reused != null) tr("Using an earlier search: «%s»", query) else tr("Searching the web: «%s»…", query)
            val payload = try {
                research.runWithRetry(
                    query, "${card.facts.title}. $query", check = null, approve = null,
                    sent = { store.logSearch(it, providerName, true) },
                    initial = reused?.gathered,
                    fetched = { sent, gathered -> runCatching { store.cacheSearch(sent, providerName, gathered) } },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                store.logSearch(query, providerName, false)
                entry.webStatus = tr("The web search failed (%s): the text uses only what the app measured.", searchError(error))
                return null
            }
            // A picture of the device, when the service can find pictures and the model is known.
            var images: List<ImageResult> = emptyList()
            val pictureQuery = lookup.imageQuery
            if (pictureQuery != null && web.provider.supportsImages) {
                // Approved with the text query when it is part of it; otherwise it is asked about on its own.
                val approved = when {
                    !web.confirmEachSearch || query.contains(pictureQuery, ignoreCase = true) -> pictureQuery
                    else -> web.approve?.invoke(pictureQuery)?.trim()?.takeIf { it.isNotEmpty() }
                }
                if (approved != null) {
                    entry.webStatus = tr("Looking for pictures: «%s»…", approved)
                    images = try {
                        provider.searchImages(approved, IMAGE_COUNT).also { store.logSearch(approved, providerName, true) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        store.logSearch(approved, providerName, false)
                        emptyList()
                    }
                }
            }
            if (payload.results.isEmpty() && images.isEmpty()) {
                entry.webStatus = tr("Nothing found on the web for «%s».", query)
                return null
            }
            entry.webStatus = null
            Log.i(TAG, "card web: ${payload.results.size} results, ${payload.evidence?.size ?: 0} passages, ${images.size} pictures from $providerName")
            return payload.copy(images = images.ifEmpty { null })
        } finally {
            entry.webBusy = false
        }
    }

    private fun searchError(error: Throwable): String = when (error) {
        is WebSearchException.Offline -> tr("no connection")
        is WebSearchException.Http -> when (error.code) {
            401, 403 -> tr("the service refused the key")
            429 -> tr("too many requests")
            402 -> tr("the service's credits ran out")
            else -> "HTTP ${error.code}"
        }
        is WebSearchException.NotConfigured -> tr("not set up")
        else -> error.message ?: error.javaClass.simpleName
    }

    /** What the card already said in short (its summary and check notes), for a check's text not to repeat it. */
    private fun earlierSummary(card: AssistantCard, current: CardEntry): String =
        card.entries.filter { it !== current && (it.action == CardAction.SUMMARY || it.action == CardAction.CHECK) && it.text.isNotBlank() }
            .joinToString("\n") { it.text }

    /** Forgets a deleted card. */
    fun forget(id: UUID) {
        // A card deleted while its text is being written: stop the model, its words have nowhere to go.
        if (cards[id]?.busy == true) stop()
        cards.remove(id)
    }

    fun forgetAll() {
        stop()
        cards.clear()
    }

    companion object {
        const val TAG = "SpectraCard"
        private const val IMAGE_COUNT = 6

        /** A saved card text: its heading names the action. A check is saved as "✓ label". */
        fun savedText(content: String): Pair<CardAction?, String> {
            // Either wording: the app language may have changed since the card was saved.
            for (action in CardAction.entries) {
                for (head in listOf("**${action.heading}**", "**${action.englishHeading}**")) {
                    if (content.startsWith(head)) return action to content.removePrefix(head).trim()
                }
            }
            return null to content.removePrefix("✓").trim()
        }
    }
}
