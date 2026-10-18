package cattivole.spectra.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal fun kindIcon(kind: String): ImageVector = when (kind) {
    "lan" -> Icons.Rounded.Lan
    "bluetooth" -> Icons.Rounded.Bluetooth
    "lora" -> Icons.Rounded.CellTower
    else -> Icons.Rounded.Wifi
}

@Composable
internal fun kindTint(kind: String): Color = when (kind) {
    "lan" -> Spectra.colors.good
    "bluetooth" -> Spectra.colors.info
    "lora" -> Spectra.colors.revealed
    else -> MaterialTheme.colorScheme.primary
}

private fun kindTitle(kind: String): String = when (kind) {
    "lan" -> "LAN"
    "bluetooth" -> "Bluetooth"
    "lora" -> "LoRa"
    else -> "Wi‑Fi"
}

/** The phone saves under its own name; translate it, and leave a Cardputer's name alone. */
internal fun sourceName(source: String): String = when (source) {
    "This phone", "This iPhone" -> tr("This phone")
    else -> source
}

/** How the saved sessions are grouped on screen. */
private enum class Grouping { DATE, PLACE }

/** One collapsible group of sessions: a day, or a place. */
private class HistoryGroup(
    val id: String,
    val label: String,
    val entries: List<HistoryEntry>,
) {
    /** Which scan types this group holds, for the icon row on the header. */
    val kinds: List<String> get() = entries.map { it.kind }.distinct()
    val itemCount: Int get() = entries.sumOf { it.itemCount }
}

@Composable
fun HistoryScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val history = model.history
    var showLive by remember { mutableStateOf(false) }
    if (showLive) {
        LiveMapScreen(model) { showLive = false }
        return
    }
    var confirmClear by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf(Grouping.DATE) }
    // Empty means every group starts collapsed.
    var expanded by remember { mutableStateOf(setOf<String>()) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, null) },
            title = { Text(tr("Clear history?")) },
            text = { Text(tr("Saved scans from this phone and the Cardputer are removed.")) },
            confirmButton = {
                TextButton(onClick = {
                    history.clear()
                    confirmClear = false
                }) { Text(tr("Clear history"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Cancel")) } },
        )
    }
    if (showSettings) {
        HistorySettingsDialog(
            autosave = model.autosaveHistory,
            onAutosave = { model.autosaveHistory = it },
            onDismiss = { showSettings = false },
        )
    }
    val groups = when (grouping) {
        Grouping.DATE -> byDate(history.entries)
        Grouping.PLACE -> byPlace(history.entries)
    }
    val pins = mapPins(history.entries)
    val showMap = grouping == Grouping.PLACE && pins.isNotEmpty()
    SpectraScreen(
        title = tr("History"),
        actions = {
            IconButton(onClick = { showLive = true }) { Icon(Icons.Rounded.MyLocation, contentDescription = tr("Live map")) }
            if (history.entries.isNotEmpty()) {
                ExportMenu(history.entries)
            }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, contentDescription = tr("History settings")) }
            if (history.entries.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, contentDescription = tr("Clear")) }
            }
        },
        overlay = {
            if (history.entries.isEmpty()) {
                EmptyState(
                    Icons.Rounded.History,
                    tr("Nothing saved"),
                    tr("Scans are saved here automatically. The Cardputer's side button saves its own scan, and it arrives here when the phone connects."),
                )
            }
        },
        fill = if (showMap) {
            {
                HistoryPlaceMap(history.entries, onOpen = { nav.history(it) })
                GroupingBar(grouping, Modifier.align(Alignment.TopCenter)) { grouping = it }
            }
        } else {
            null
        },
    ) {
        if (!showMap && history.entries.isNotEmpty()) {
            item(key = "grouping") {
                GroupingBar(grouping) { grouping = it }
            }
        }
        for (group in groups) {
            val open = group.id in expanded
            item(key = "head-${group.id}", contentType = "section-header") {
                GroupHeader(
                    group.label,
                    group.kinds,
                    group.itemCount,
                    open,
                    modifier = Modifier.animateItem(),
                ) { expanded = if (open) expanded - group.id else expanded + group.id }
            }
            if (open) {
                cardSection(
                    group.entries,
                    header = null,
                    key = { it.id },
                    sectionKey = group.id,
                    dividerInset = 70.dp,
                ) { entry ->
                    HistoryRow(
                        entry,
                        showPlace = grouping == Grouping.DATE,
                        onOpen = { nav.history(entry.id) },
                        onDelete = { history.delete(entry.id) },
                    )
                }
            }
        }
        if (history.entries.isNotEmpty()) {
            item(key = "hint") { SectionFooter(tr("Swipe a saved scan to the left to delete it.")) }
        }
    }
}

@Composable
private fun GroupingBar(grouping: Grouping, modifier: Modifier = Modifier, onSelect: (Grouping) -> Unit) {
    ChipRow(modifier) {
        ToggleChip(tr("By date"), grouping == Grouping.DATE, { onSelect(Grouping.DATE) }, Icons.Rounded.CalendarMonth)
        ToggleChip(tr("By place"), grouping == Grouping.PLACE, { onSelect(Grouping.PLACE) }, Icons.Rounded.Place)
    }
}

private fun byDate(entries: List<HistoryEntry>): List<HistoryGroup> {
    val buckets = entries.groupBy { startOfDay(it.savedAt) }
    return buckets.entries.sortedByDescending { it.key }.map { (day, rows) ->
        HistoryGroup("date:$day", dayLabel(day), rows.sortedByDescending { it.savedAt })
    }
}

private fun byPlace(entries: List<HistoryEntry>): List<HistoryGroup> {
    val buckets = entries.groupBy { it.placeLabel ?: it.placeKey ?: "" }
    return buckets.entries
        .map { (label, rows) ->
            HistoryGroup(
                "place:$label",
                label.ifEmpty { tr("No location") },
                rows.sortedByDescending { it.savedAt },
            )
        }
        .sortedByDescending { it.entries.firstOrNull()?.savedAt ?: 0 }
}

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(day: Long): String {
    val today = startOfDay(System.currentTimeMillis())
    return when (day) {
        today -> tr("Today")
        today - 86_400_000L -> tr("Yesterday")
        else -> SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date(day))
    }
}

/** A collapsible section header with the group's name and the icons of the scan types inside it. */
@Composable
private fun GroupHeader(
    label: String,
    kinds: List<String>,
    count: Int,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    SectionHeader(
        label,
        modifier = modifier.clickable(onClick = onToggle),
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (kind in kinds) {
                    Icon(kindIcon(kind), contentDescription = null, tint = kindTint(kind), modifier = Modifier.size(16.dp))
                }
            }
            Text(
                "$count",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

@Composable
private fun HistorySettingsDialog(autosave: Boolean, onAutosave: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Settings, null) },
        title = { Text(tr("History settings")) },
        text = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = autosave, role = Role.Switch, onValueChange = onAutosave)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(tr("Save scans automatically"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(autosave, null)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Done")) } },
    )
}

@Composable
private fun HistoryRow(entry: HistoryEntry, showPlace: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
        value == SwipeToDismissBoxValue.EndToStart
    })
    val fromSensor = entry.captureId != null
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = tr("Delete"), tint = MaterialTheme.colorScheme.onError)
            }
        },
    ) {
        val place = entry.placeLabel?.takeIf { showPlace }
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
            ListRow(
                title = entry.kindTitle,
                subtitle = "${ago(entry.savedAt)} · ${place ?: sourceName(entry.source)}",
                subtitleLines = 1,
                leading = { IconTile(if (fromSensor) Icons.Rounded.Memory else kindIcon(entry.kind), kindTint(entry.kind)) },
                badges = {
                    if (entry.deauths.isNotEmpty()) {
                        Icon(Icons.Rounded.GppBad, contentDescription = tr("Deauth detected"), tint = Spectra.colors.danger, modifier = Modifier.padding(start = 2.dp))
                    }
                },
                trailing = { Pill("${entry.itemCount}", kindTint(entry.kind)) },
                onClick = onOpen,
            )
        }
    }
}

@Composable
fun HistoryDetail(model: SpectraModel, nav: Navigator, id: String) {
    model.generation
    val entry = model.history.entry(id)
    if (entry == null) {
        SpectraScreen(tr("History"), onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.History, tr("Gone"), tr("This saved scan is no longer here."))
        }) {}
        return
    }
    val footer = "${sourceName(entry.source)} · ${dateTime(entry.savedAt)}"
    val context = LocalContext.current
    SpectraScreen(
        title = kindTitle(entry.kind),
        subtitle = footer,
        onBack = nav::back,
        actions = {
            ExportMenu(listOf(entry))
            IconButton(onClick = {
                model.history.delete(id)
                nav.back()
            }) { Icon(Icons.Rounded.Delete, contentDescription = tr("Delete")) }
        },
    ) {
        val lat = entry.latitude
        val lon = entry.longitude
        if (lat != null && lon != null) {
            card(header = tr("Where"), key = "where", rows = listOf {
                ActionRow(
                    String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon),
                    Icons.Rounded.Place,
                    onClick = {
                        val label = Uri.encode(sourceName(entry.source))
                        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    },
                )
            })
        }
        if (!entry.hasRecords) {
            cardSection(entry.lines.withIndex().toList(), key = { "line-${it.index}" }, sectionKey = "lines", footer = footer) { (_, line) ->
                ListRow(
                    title = line.title,
                    subtitle = line.detail.ifEmpty { null },
                    trailing = if (line.trailing.isNotEmpty()) {
                        { Text(line.trailing, style = MaterialTheme.typography.titleMedium.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        null
                    },
                )
            }
            return@SpectraScreen
        }
        cardSection(
            entry.deauths,
            header = tr("Deauth detected"),
            key = { "d-" + it.id },
            sectionKey = "deauth",
            headerTint = { Spectra.colors.danger },
            headerIcon = Icons.Rounded.GppBad,
            dividerInset = 70.dp,
        ) { DeauthRow(it) }
        cardSection(
            entry.networks,
            header = tr("Networks: %d", entry.networks.size),
            key = { "n-" + it.id.text },
            sectionKey = "networks",
            dividerInset = 70.dp,
        ) { network -> NetworkRow(network, onClick = { nav.network(network.id, entry.id) }, now = entry.savedAt) }
        cardSection(
            entry.probes,
            header = tr("Probing: %d", entry.probes.size),
            key = { "p-" + it.mac.text },
            sectionKey = "probes",
            dividerInset = 70.dp,
        ) { probe -> ProbeRow(probe, null, onClick = { nav.client(probe.mac, entry.id) }) }
        cardSection(
            entry.connections,
            header = tr("Connection"),
            key = { "c-${it.checkedAt}" },
            sectionKey = "connection",
            dividerInset = 70.dp,
        ) { saved ->
            val report = saved.toReport()
            ListRow(
                title = statusLabel(saved.status),
                subtitle = connectionSummary(report, saved.viaVpn),
                leading = { IconTile(Icons.Rounded.Public, statusColor(saved.status)) },
                trailing = {
                    Text(ago(saved.checkedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                onClick = { nav.savedConnection(entry.id, saved.checkedAt) },
            )
        }
        cardSection(
            entry.lan,
            header = tr("Devices: %d", entry.lan.size),
            key = { "h-" + it.ipAddress },
            sectionKey = "lan",
            dividerInset = 70.dp,
        ) { device -> DeviceRow(device, onClick = { nav.host(device.ipAddress, entry.id) }) }
        cardSection(
            entry.bluetooth,
            header = tr("Devices: %d", entry.bluetooth.size),
            key = { "b-" + it.address },
            sectionKey = "bluetooth",
            dividerInset = 70.dp,
        ) { device -> BleRow(device, device.lastSeen, onClick = { nav.ble(device.address, entry.id) }) }
        item(key = "saved-footer") { SectionFooter(footer) }
    }
}
