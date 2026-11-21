package cattivole.spectra.assistant.ui

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
        card = assistant.cards.card(id) ?: return@LaunchedEffect nav.back()
    }
    val current = card ?: return
    val scope = rememberCoroutineScope()
    val notify = cattivole.spectra.ui.LocalNotify.current
    val facts = current.facts
    SpectraScreen(
        title = facts.title,
        subtitle = if (current.live) tr("Summary") else tr("Saved %s", dateTime(current.createdAt)),
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
                }
            }
        }
        if (current.live) {
            if (facts.steps.isNotEmpty()) {
                freeCard(key = "steps", header = tr("Next steps")) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (step in facts.steps) {
                            FilledTonalButton(onClick = { assistant.cards.runStep(current, step) }, enabled = !current.busy, modifier = Modifier.fillMaxWidth()) {
                                Text(step.label)
                            }
                        }
                    }
                }
            }
            val ran = current.entries.mapNotNull { it.action }.toSet()
            freeCard(key = "more", header = tr("Ask the model"), footer = tr("Deep analysis reads everything around this subject and writes at length: about 3–4 minutes on this phone.")) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { assistant.cards.run(current, CardAction.DETAILS) }, enabled = !current.busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Subject, null, Modifier.size(18.dp))
                        Text(if (CardAction.DETAILS in ran) tr("More detail again") else tr("More detail"), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = { assistant.cards.run(current, CardAction.DEEP) }, enabled = !current.busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.TravelExplore, null, Modifier.size(18.dp))
                        Text(tr("Deep analysis"), Modifier.padding(start = 8.dp))
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
