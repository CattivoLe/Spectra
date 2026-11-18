package cattivole.spectra.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cattivole.spectra.core.CaptureAnalysis
import cattivole.spectra.core.CaptureSummary
import cattivole.spectra.core.PacketFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import cattivole.spectra.core.PacketRow
import cattivole.spectra.core.PcapAnalyzer
import cattivole.spectra.core.PcapError
import cattivole.spectra.core.PcapException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface PcapPhase {
    data object Idle : PcapPhase
    data class Loading(val name: String) : PcapPhase
    data class Loaded(val name: String, val analysis: CaptureAnalysis) : PcapPhase
    data class Failed(val message: String) : PcapPhase
}

/**
 * Imports a `.pcap`/`.pcapng` and shows an approximate decode: a summary, a
 * protocol and application breakdown, the busiest endpoints and conversations,
 * and the first packets. Parsing runs off the main thread. Mirrors iOS `PcapView`.
 * [capture] opens a capture already in memory (name and bytes) instead of a file;
 * [onSave] then takes the place of opening another file.
 */
@Composable
fun PcapScreen(
    onBack: () -> Unit,
    capture: Pair<String, ByteArray>? = null,
    onSave: (() -> Unit)? = null,
    /** Keeps the decoded capture for the assistant and opens a chat about it. Null hides the ✨ button. */
    onAsk: ((name: String, analysis: CaptureAnalysis, fromSensor: Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<PcapPhase>(capture?.let { PcapPhase.Loading(it.first) } ?: PcapPhase.Idle) }
    var filter by remember { mutableStateOf(PacketFilter()) }
    LaunchedEffect(capture) {
        val (name, bytes) = capture ?: return@LaunchedEffect
        filter = PacketFilter()
        phase = withContext(Dispatchers.Default) { analyze(bytes, name) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryName(context, uri) ?: "capture.pcap"
        phase = PcapPhase.Loading(name)
        filter = PacketFilter()
        scope.launch {
            phase = withContext(Dispatchers.Default) { readAndAnalyze(context, uri, name) }
        }
    }
    fun pick() = picker.launch(arrayOf("*/*"))

    SpectraScreen(
        tr("Decode pcap"),
        onBack = onBack,
        actions = {
            val loaded = phase as? PcapPhase.Loaded
            if (onAsk != null && loaded != null) {
                IconButton(onClick = { onAsk(loaded.name, loaded.analysis, capture != null) }) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = tr("Ask the assistant"), tint = Spectra.colors.revealed)
                }
            }
            if (onSave != null) {
                IconButton(onClick = onSave) {
                    Icon(Icons.Rounded.Download, contentDescription = tr("Save .pcap"))
                }
            } else if (phase is PcapPhase.Loaded) {
                IconButton(onClick = { pick() }) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = tr("Open another"))
                }
            }
        },
    ) {
        when (val current = phase) {
            is PcapPhase.Idle -> freeCard(
                "choose",
                footer = tr("Open a .pcap or .pcapng saved from Wireshark, tcpdump or the Cardputer. Spectra reads it on the phone and shows a rough decode: protocols, the busiest hosts and the first packets. Nothing leaves the device."),
            ) {
                Button(onClick = { pick() }) { Text(tr("Choose a capture…")) }
            }

            is PcapPhase.Loading -> freeCard("loading") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(tr("Decoding %s…", current.name), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            is PcapPhase.Failed -> freeCard("failed") {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { pick() }) { Text(tr("Choose a file…")) }
            }

            is PcapPhase.Loaded -> results(current.name, current.analysis, filter) { filter = it }
        }
    }
}

private fun LazyListScope.results(
    name: String,
    analysis: CaptureAnalysis,
    filter: PacketFilter,
    onFilter: (PacketFilter) -> Unit,
) {
    val summary = analysis.summary

    val captureRows = buildList<@Composable () -> Unit> {
        add { InfoRow(tr("File"), name) }
        add { InfoRow(tr("Format"), analysis.format.uppercase(Locale.US)) }
        add { InfoRow(tr("Link layer"), summary.linkTypes.ifEmpty { listOf("—") }.joinToString(", ")) }
        add { InfoRow(tr("Packets"), summary.packetCount.toString()) }
        add { InfoRow(tr("Captured"), byteSize(summary.capturedBytes)) }
        if (summary.wireBytes != summary.capturedBytes) {
            add { InfoRow(tr("On the wire"), byteSize(summary.wireBytes)) }
        }
        summary.duration?.let { duration -> add { InfoRow(tr("Duration"), durationText(duration)) } }
        summary.start?.let { start -> add { InfoRow(tr("Started"), startText(start)) } }
    }
    card(header = tr("Capture"), key = "capture", rows = captureRows)

    if (summary.packetCount == 0) {
        freeCard("empty") {
            Text(tr("The file parsed, but it holds no packets."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    cardSection(items = summary.protocols, header = tr("Protocols"), key = { it.name }) {
        CountBar(it.name, it.count, summary.packetCount)
    }

    cardSection(
        items = summary.apps,
        header = tr("Applications"),
        footer = tr("Guessed from well-known ports, so it is a hint, not proof."),
        key = { it.name },
    ) {
        CountBar(it.name, it.count, summary.packetCount)
    }

    cardSection(
        items = summary.talkers,
        header = tr("Top hosts"),
        footer = tr("Tap a host or a conversation to show only its packets."),
        key = { it.endpoint },
    ) { talker ->
        val picked = filter.endpoints == listOf(talker.endpoint)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onFilter(filter.copy(endpoints = if (picked) emptyList() else listOf(talker.endpoint))) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                talker.endpoint,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${talker.packets} · ${byteSize(talker.bytes.toLong())}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (picked) FilteredMark()
        }
    }

    cardSection(items = summary.conversations, header = tr("Conversations"), key = { it.a + "|" + it.b }) { conversation ->
        val pair = listOf(conversation.a, conversation.b)
        val picked = filter.endpoints == pair
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onFilter(filter.copy(endpoints = if (picked) emptyList() else pair)) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${conversation.a} ↔ ${conversation.b}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr("%d packets · %s", conversation.packets, byteSize(conversation.bytes.toLong())),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (picked) FilteredMark()
        }
    }

    if (analysis.rows.isEmpty()) return
    packetFilters(summary, filter, onFilter)
    val matching = if (filter.isActive) analysis.rows.filter(filter::matches) else analysis.rows
    val shown = matching.take(PcapAnalyzer.SHOWN_ROWS)
    if (matching.isEmpty()) {
        card(key = "no-match", rows = listOf { NoteRow(tr("No packet matches these filters."), warning = false) })
    }
    cardSection(
        items = shown,
        header = if (filter.isActive) tr("Packets: %d of %d", matching.size, analysis.rows.size) else tr("Packets"),
        footer = when {
            shown.size < matching.size -> tr("Showing the first %d of %d. Narrow the filters to see the rest.", shown.size, matching.size)
            analysis.rowsTruncated -> tr("The list holds the first %d packets. The summary above counts all %d.", analysis.rows.size, summary.packetCount)
            else -> null
        },
        key = { it.index },
        sectionKey = "packets",
    ) { row ->
        PacketRowView(row, summary.start)
    }
}

@Composable
private fun FilteredMark() {
    Icon(
        Icons.Rounded.FilterAlt,
        contentDescription = tr("Filtered"),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp).size(18.dp),
    )
}

/** Search, protocol and application chips, and the host picked above. */
private fun LazyListScope.packetFilters(summary: CaptureSummary, filter: PacketFilter, onFilter: (PacketFilter) -> Unit) {
    freeCard("packet-filter", header = tr("Filter")) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = { onFilter(filter.copy(query = it)) },
            placeholder = { Text(tr("Address, port or text")) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = if (filter.query.isEmpty()) {
                null
            } else {
                { IconButton(onClick = { onFilter(filter.copy(query = "")) }) { Icon(Icons.Rounded.Close, contentDescription = tr("Clear")) } }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (summary.protocols.size > 1) {
            ChipRow(contentPadding = PaddingValues(top = 10.dp)) {
                summary.protocols.forEach { item ->
                    ToggleChip(tr(item.name), item.name in filter.transports, { onFilter(filter.toggle(item.name)) })
                }
            }
        }
        if (summary.apps.isNotEmpty()) {
            ChipRow(contentPadding = PaddingValues(top = 4.dp)) {
                summary.apps.forEach { item ->
                    ToggleChip(item.name, filter.app == item.name, { onFilter(filter.copy(app = if (filter.app == item.name) null else item.name)) })
                }
            }
        }
        if (filter.endpoints.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lan, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    filter.endpoints.joinToString(" ↔ "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                IconButton(onClick = { onFilter(filter.copy(endpoints = emptyList())) }) {
                    Icon(Icons.Rounded.Close, contentDescription = tr("Remove"))
                }
            }
        }
        if (filter.isActive) {
            TextButton(onClick = { onFilter(PacketFilter()) }) { Text(tr("Reset filters")) }
        }
    }
}

@Composable
private fun CountBar(name: String, count: Int, total: Int) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text("$count", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val fraction = if (total > 0) (count.toFloat() / total).coerceIn(0.02f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            Box(
                Modifier.fillMaxWidth(fraction).height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun PacketRowView(row: PacketRow, start: Double?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${offsetText(row.timestamp, start)}  ${row.transport}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${row.length} B", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(row.info, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun offsetText(timestamp: Double?, start: Double?): String =
    if (timestamp != null && start != null) "+%.3fs".format(timestamp - start) else "—"

private fun durationText(seconds: Double): String {
    val total = seconds.toLong()
    return if (total >= 60) "%d:%02d".format(total / 60, total % 60) else "%.1f s".format(seconds)
}

private fun startText(seconds: Double): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date((seconds * 1000).toLong()))

private fun queryName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

private fun readAndAnalyze(context: Context, uri: Uri, name: String): PcapPhase {
    val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        ?: return PcapPhase.Failed(tr("Could not read the file."))
    return analyze(bytes, name)
}

private fun analyze(bytes: ByteArray, name: String): PcapPhase {
    if (bytes.size > 80_000_000) {
        return PcapPhase.Failed(tr("That capture is too large to open on the phone (over 80 MB)."))
    }
    return try {
        PcapPhase.Loaded(name, PcapAnalyzer.analyze(bytes))
    } catch (e: PcapException) {
        PcapPhase.Failed(
            when (e.kind) {
                PcapError.NOT_A_CAPTURE -> tr("This is not a pcap or pcapng file.")
                PcapError.TOO_SHORT -> tr("This file is too short to be a capture.")
                else -> tr("Could not decode this capture.")
            },
        )
    } catch (e: Exception) {
        PcapPhase.Failed(tr("Could not decode this capture."))
    }
}
