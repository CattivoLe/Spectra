package cattivole.spectra.assistant.ui

import androidx.compose.material.icons.rounded.Public
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subject
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.assistant.TrafficRef
import cattivole.spectra.assistant.AssistantActions
import cattivole.spectra.assistant.AssistantCard
import cattivole.spectra.assistant.CardAction
import cattivole.spectra.assistant.CardEntry
import cattivole.spectra.assistant.CardFinding
import cattivole.spectra.assistant.CardRisk
import cattivole.spectra.assistant.AssistantModel
import cattivole.spectra.ui.SectionFooter
import cattivole.spectra.ui.Spectra
import cattivole.spectra.ui.SpectraScreen
import cattivole.spectra.ui.card
import cattivole.spectra.ui.dateTime
import cattivole.spectra.ui.freeCard
import cattivole.spectra.ui.tr
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * A summary card: the app's own findings at the top, at once; under them what the model wrote, one block per action;
 * at the bottom only buttons — next steps chosen from the findings, "More detail" and "Deep analysis". No text field.
 */
@Composable
fun AssistantCardScreen(model: SpectraModel, nav: AssistantNav, id: UUID) {
    val assistant = model.assistant
    var card by remember { mutableStateOf<AssistantCard?>(null) }
    LaunchedEffect(id) {
        val loaded = assistant.cards.card(id) ?: return@LaunchedEffect nav.back()
        // "Continue" in the Assistant tab's list: the stopped text is picked up as the card opens.
        card = if (assistant.cards.takeContinue(id)) assistant.cards.resume(loaded) else loaded
    }
    val current = card ?: return
    val onContinue: () -> Unit = { card = assistant.cards.resume(current) }
    val scope = rememberCoroutineScope()
    val notify = cattivole.spectra.ui.LocalNotify.current
    val facts = current.facts
    var folderName by remember { mutableStateOf<String?>(null) }
    // A web search of "More detail" asks here before anything goes out.
    assistant.pendingSearch?.let { SearchApprovalDialog(it, assistant.web.provider.title) }
    LaunchedEffect(id) { folderName = assistant.folderNameOf(id) }
    SpectraScreen(
        title = facts.title,
        subtitle = listOfNotNull(
            if (current.live) tr("Summary") else tr("Saved %s", dateTime(current.createdAt)),
            folderName,
        ).joinToString(" · "),
        onBack = nav::back,
        actions = {
            if (current.busy) {
                IconButton(onClick = { assistant.cards.stop() }) { Icon(Icons.Rounded.Stop, contentDescription = tr("Stop")) }
            }
        },
    ) {
        card(header = tr("Findings"), key = "findings", rows = facts.findings.map { finding -> { FindingRow(finding) } })
        current.entries.forEachIndexed { index, entry ->
            if (entry.isCheck) {
                card(key = "check-$index", rows = listOf { CheckRow(entry) })
            } else {
                freeCard(key = "text-$index", header = entry.label, footer = timing(entry)) {
                    EntryText(entry, assistant, nav)
                    // Stopped by leaving or by another card: the same text can be picked up again.
                    if (entry === current.stopped && (current.live || current.subject != null)) {
                        TextButton(onClick = onContinue, modifier = Modifier.padding(top = 4.dp)) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                            Text(tr("Continue"), Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
        if (current.live) {
            // A step already run is not offered again: its result is above.
            val steps = facts.steps.filter { it.call !in current.ranSteps }
            if (steps.isNotEmpty()) {
                freeCard(key = "steps", header = tr("Next steps")) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (step in steps) {
                            // A step that opens a screen decides nothing by itself and does not disturb the text being written.
                            val opens = AssistantActions.Name.of(step.tool)?.takeIf { it.opensScreen }
                            FilledTonalButton(
                                onClick = {
                                    if (opens == null) {
                                        assistant.cards.runStep(current, step)
                                    } else {
                                        scope.launch {
                                            if (!openTrafficStep(model, nav, opens, step.text)) notify(tr("This connection is no longer in the list."))
                                        }
                                    }
                                },
                                enabled = opens != null || !current.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(step.label)
                            }
                        }
                    }
                }
            }
            // An action that finished is not offered again until a check brings new data: it would say the same.
            val done = current.doneSinceCheck
            val offers = if (facts.awaitsCheck) emptyList() else listOf(CardAction.DETAILS, CardAction.DEEP).filter { it !in done }
            if (offers.isNotEmpty()) {
                val webHint = when {
                    current.subject?.let { cattivole.spectra.assistant.CardWeb.lookup(model, it, CardAction.DETAILS) } == null -> null
                    !assistant.web.isEnabled -> tr("With the internet on (Assistant tab → ⋮ → Internet), both also search the web for this device's model and known problems.")
                    !assistant.web.isReady -> tr("The web search is not set up: Assistant tab → ⋮ → Internet.")
                    assistant.web.confirmEachSearch -> tr("Both also search the web through %s; you see the query first.", assistant.web.provider.title)
                    else -> tr("Both also search the web through %s.", assistant.web.provider.title)
                }
                freeCard(
                    key = "more",
                    header = tr("Ask the model"),
                    footer = listOfNotNull(
                        if (CardAction.DEEP in offers) tr("Deep analysis reads everything around this subject and writes at length: about 3–4 minutes on this phone.") else null,
                        webHint,
                    ).joinToString(" ").ifEmpty { null },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (action in offers) {
                            OutlinedButton(onClick = { assistant.cards.run(current, action) }, enabled = !current.busy, modifier = Modifier.fillMaxWidth()) {
                                Icon(if (action == CardAction.DEEP) Icons.Rounded.TravelExplore else Icons.Rounded.Subject, null, Modifier.size(18.dp))
                                Text(action.heading, Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        } else {
            val subject = current.subject
            if (subject != null) {
                freeCard(key = "refresh", footer = tr("A saved summary shows what was found then. A new one reads the current data.")) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                val fresh = assistant.cards.open(subject)
                                if (fresh != null) nav.card(fresh) else notify(tr("There is nothing to summarize for it now."))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Text(tr("New summary"), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/**
 * Opens a sniffer screen filled in with the recorded request the step points to; what happens there is the user's
 * doing. False when the request is gone.
 */
private suspend fun openTrafficStep(model: SpectraModel, nav: AssistantNav, step: AssistantActions.Name, text: String?): Boolean {
    if (step == AssistantActions.Name.OPEN_TLS_HOSTS) {
        nav.trafficHosts()
        return true
    }
    val ref = text?.let(TrafficRef::of) ?: return false
    val index = ref.index ?: return false
    val (session, flow) = model.captures.library.find(ref) ?: return false
    if (index !in flow.exchanges.indices) return false
    if (step == AssistantActions.Name.OPEN_MAP_LOCAL) {
        model.traffic.draftRule(flow, index, session)
        nav.trafficMapRule(null)
    } else {
        model.traffic.draftRepeat(flow, index, session)
        nav.trafficRepeat()
    }
    return true
}

@Composable
private fun FindingRow(finding: CardFinding) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 6.dp).size(10.dp).background(finding.risk.color(), CircleShape))
        Text(finding.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CheckRow(entry: CardEntry) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (entry.state) {
            CardEntry.State.WAITING, CardEntry.State.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            CardEntry.State.DONE -> Icon(Icons.Rounded.CheckCircle, null, tint = Spectra.colors.good, modifier = Modifier.size(18.dp))
            else -> Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(entry.label, style = MaterialTheme.typography.bodyMedium)
            val note = when (entry.state) {
                CardEntry.State.STOPPED -> tr("Stopped")
                else -> entry.text
            }
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EntryText(entry: CardEntry, assistant: AssistantModel, nav: AssistantNav) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WebLine(entry)
        entry.web?.images?.takeIf { it.isNotEmpty() }?.let { ImageStrip(it) }
        when {
            entry.state == CardEntry.State.FAILED -> {
                Text(entry.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (assistant.assistantModelFile == null) {
                    OutlinedButton(onClick = nav::models) {
                        Icon(Icons.Rounded.Memory, null, Modifier.size(18.dp))
                        Text(tr("Model…"), Modifier.padding(start = 8.dp))
                    }
                }
            }
            entry.webBusy -> Unit
            entry.text.isEmpty() && entry.active -> {
                val loading = assistant.controller.state.let {
                    it == alexcattivo.localchat.engine.EngineController.State.Loading || it == alexcattivo.localchat.engine.EngineController.State.Empty
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        when {
                            loading -> tr("Loading the model…")
                            entry.state == CardEntry.State.WAITING -> tr("Waiting for the model…")
                            else -> tr("Reading the findings…")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                MarkdownText(entry.text)
                if (entry.state == CardEntry.State.STOPPED) {
                    Text(tr("Stopped"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        entry.web?.takeIf { it.results.isNotEmpty() && !entry.active }?.let { WebSources(it) }
    }
}

/** The web search's state above the text: running, declined, failed, or where the text's facts came from. */
@Composable
private fun WebLine(entry: CardEntry) {
    val status = entry.webStatus
    val web = entry.web
    val text = when {
        status != null -> status
        web != null && web.results.isNotEmpty() -> tr("Read on the web: %d sources via %s", web.usedSourceIndices.size, web.provider)
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entry.webBusy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Rounded.Public, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The pages the text drew on, by number as the text cites them; a tap opens one in the browser. */
@Composable
private fun WebSources(web: alexcattivo.localchat.kit.web.SearchPayload) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(tr("Sources"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (index in web.usedSourceIndices) {
            val result = web.results.getOrNull(index) ?: continue
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(result.url))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("[${index + 1}]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(result.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(result.host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** How long the text took, from the tap: shown small under it. */
private fun timing(entry: CardEntry): String? {
    val total = entry.totalMs ?: return null
    val first = entry.firstWordsMs
    fun seconds(ms: Long) = String.format(Locale.getDefault(), "%.0f", ms / 1000.0)
    return if (first != null) tr("First words in %s s, done in %s s", seconds(first), seconds(total)) else tr("Done in %s s", seconds(total))
}

@Composable
fun CardRisk.color(): Color = when (this) {
    CardRisk.CRITICAL -> Spectra.colors.danger
    CardRisk.WARNING -> Spectra.colors.warning
    CardRisk.INFO -> Spectra.colors.info
    CardRisk.OK -> Spectra.colors.good
}

fun CardRisk.label(): String = when (this) {
    CardRisk.CRITICAL -> tr("Critical")
    CardRisk.WARNING -> tr("Attention")
    CardRisk.INFO -> tr("Note")
    CardRisk.OK -> tr("Fine")
}
