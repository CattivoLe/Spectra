package cattivole.spectra.assistant

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.EngineController
import alexcattivo.localchat.engine.GenerationEvent
import alexcattivo.localchat.engine.GenerationParams
import alexcattivo.localchat.kit.ChatStore
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
    private var job: Job? = null
    private var running: CardEntry? = null

    /** A new card about [subject], its summary started at once. Null when there is nothing to summarize. */
    suspend fun open(subject: CardSubject): UUID? {
        val facts = sources.cardFacts(subject) ?: return null
        assistant.ensureStarted()
        val chat = store.createChat(systemPrompt = CardSubjects.encode(subject), folderID = assistant.summariesFolder())
        store.rename(chat.id, facts.title)
        val head = store.append(ChatMessage.Role.User, AssistantCards.render(facts.title, facts.findings), chat.id)
        val card = AssistantCard(chat.id, subject, facts, chat.createdAt, live = true).also { it.headMessage = head.id }
        cards[chat.id] = card
        Log.i(TAG, "card ${subject.kind}: ${facts.findings.size} findings shown at once")
        run(card, CardAction.SUMMARY)
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
        for (message in messages) {
            if (message.role != ChatMessage.Role.Assistant) continue
            val (action, text) = savedText(message.content)
            card.entries.add(
                CardEntry(action, action?.heading ?: text).also {
                    it.text = if (action != null) text else ""
                    it.state = if (message.interrupted) CardEntry.State.STOPPED else CardEntry.State.DONE
                },
            )
        }
        return card
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
            store.append(ChatMessage.Role.Assistant, "✓ ${step.label}", card.id)
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
            ChatMessage(ChatMessage.Role.System, CardPrompts.system(action, card.facts.subject.kind, tool = card.lastCheck?.tool)),
            ChatMessage(ChatMessage.Role.User, CardPrompts.user(card.facts, action, card.lastCheck, earlierSummary(card, entry))),
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
        Log.i(TAG, "card ${card.facts.subject.kind} ${action.name}: first words ${entry.firstWordsMs} ms, done ${entry.totalMs} ms, $outcome")
        if (entry.text.isNotEmpty() && outcome != CardEntry.State.FAILED) {
            scope.launch {
                val saved = store.append(ChatMessage.Role.Assistant, "**${action.heading}**\n\n${entry.text}", card.id, assistant.currentModelName)
                if (outcome == CardEntry.State.STOPPED) store.update(saved.id, saved.content, interrupted = true)
            }
        }
    }

    /** What the card already said in short (its summary and check notes), for a check's text not to repeat it. */
    private fun earlierSummary(card: AssistantCard, current: CardEntry): String =
        card.entries.filter { it !== current && (it.action == CardAction.SUMMARY || it.action == CardAction.CHECK) && it.text.isNotBlank() }
            .joinToString("\n") { it.text }

    /** Forgets a deleted card. */
    fun forget(id: UUID) {
        cards.remove(id)
    }

    fun forgetAll() {
        stop()
        cards.clear()
    }

    companion object {
        const val TAG = "SpectraCard"

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
