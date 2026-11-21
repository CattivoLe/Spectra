package cattivole.spectra.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.Band
import cattivole.spectra.core.HistoryArea
import cattivole.spectra.core.HistoryEntry
import cattivole.spectra.core.forArea
import cattivole.spectra.core.only
import cattivole.spectra.core.hasStoredPassword
import cattivole.spectra.core.passesWifiFilter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal fun kindIcon(kind: String): ImageVector = when (kind) {
    "lan" -> Icons.Rounded.Lan
    "internet" -> Icons.Rounded.Public
    "bluetooth" -> Icons.Rounded.Bluetooth
    "lora" -> Icons.Rounded.CellTower
    else -> Icons.Rounded.Wifi
}

@Composable
internal fun kindTint(kind: String): Color = when (kind) {
    "lan" -> Spectra.colors.good
    "internet" -> Spectra.colors.excellent
    "bluetooth" -> Spectra.colors.info
    "lora" -> Spectra.colors.revealed
    else -> MaterialTheme.colorScheme.primary
}

/** The phone saves under its own name; translate it, and leave a Cardputer's name alone. */
internal fun sourceName(source: String): String = when (source) {
    "This phone", "This iPhone" -> tr("This phone")
    else -> source
}

/** The tab's name, under the History title. */
internal fun HistoryArea.title(): String = when (this) {
    HistoryArea.WIFI -> "Wi‑Fi"
    HistoryArea.LAN -> "LAN"
    HistoryArea.INTERNET -> tr("Internet")
    HistoryArea.BLUETOOTH -> "Bluetooth"
}

/** What the empty history of a tab says will appear in it. */
private fun HistoryArea.emptyHint(): String = when (this) {
    HistoryArea.WIFI -> tr("Scans are saved here automatically. The Cardputer's side button saves its own scan, and it arrives here when the phone connects.")
    HistoryArea.LAN -> tr("Device scans of each network are saved here automatically.")
    HistoryArea.INTERNET -> tr("Connection checks are saved here automatically, by network.")
    HistoryArea.BLUETOOTH -> tr("Bluetooth scans are saved here automatically.")
}

/** The clock in a tab's top bar: that tab's saved sessions. */
@Composable
fun HistoryButton(nav: Navigator, area: HistoryArea) {
    IconButton(onClick = { nav.history(area) }) { Icon(Icons.Rounded.History, contentDescription = tr("History")) }
}

/** How the saved sessions are grouped on screen. */
private enum class Grouping { DATE, PLACE, LIST }

/** Order of sessions inside a group, and of the flat list. */
private enum class HistorySort { NEWEST, OLDEST, NAME, COUNT }

/** One collapsible group of sessions: a day, or a place. */
private class HistoryGroup(
    val id: String,
    val label: String,
    val entries: List<HistoryEntry>,
) {
    /** Which scan types this group holds, for the icon row on the header. */
    val kinds: List<String> get() = entries.flatMap { it.shownKinds() }.distinct()
    val itemCount: Int get() = entries.sumOf { it.itemCount }
}

/** One tab's saved sessions: only the records of [area], each session cut down to them. */
@Composable
fun HistoryScreen(model: SpectraModel, nav: Navigator, area: HistoryArea) {
    model.generation
    val history = model.history
    val all = history.entries.forArea(area)
    // Live map is hidden for now.
    // var showLive by remember { mutableStateOf(false) }
    // if (showLive) {
    //     LiveMapScreen(model) { showLive = false }
    //     return
    // }
    var confirmClear by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf(Grouping.DATE) }
    var sort by rememberSaveable { mutableStateOf(HistorySort.NEWEST) }
    var query by rememberSaveable { mutableStateOf("") }
    var importedShelf by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // Today starts open. Every other day stays folded until it is tapped.
    var expanded by remember { mutableStateOf(setOf(todayGroupId())) }
    val context = LocalContext.current
    val notify = LocalNotify.current
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
        }.getOrNull()
        if (text == null) {
            notify(tr("Could not read that file."))
            return@rememberLauncherForActivityResult
        }
        when (val added = history.importDocument(text)) {
            -1 -> notify(tr("Could not read that file."))
            0 -> notify(tr("Nothing new in that file."))
            else -> {
                importedShelf = true
                notify(tr("Imported %d scans.", added))
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, null) },
            title = { Text(tr("Clear history?")) },
            text = {
                Text(
                    if (importedShelf) tr("Imported %s records are removed. Scans on this phone and other tabs stay.", area.title())
                    else tr("Saved %s records are removed. Other tabs keep theirs.", area.title()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    history.clearShelf(importedShelf, area)
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
    val shown = all.filter { (it.importedFrom != null) == importedShelf }
    val needle = query.trim()
    val matched = shown.filter { it.matchesQuery(needle) }.ordered(sort)
    val groups = when (grouping) {
        Grouping.DATE -> byDate(matched)
        Grouping.PLACE -> byPlace(matched)
        Grouping.LIST -> emptyList()
    }
    val pins = mapPins(matched)
    val showMap = grouping == Grouping.PLACE && pins.isNotEmpty()
    SpectraScreen(
        title = tr("History"),
        subtitle = area.title(),
        onBack = nav::back,
        actions = {
            // Live map is hidden for now.
            // IconButton(onClick = { showLive = true }) { Icon(Icons.Rounded.MyLocation, contentDescription = tr("Live map")) }
            if (shown.isNotEmpty()) {
                ExportMenu(shown)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = tr("More")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Import…")) },
                        leadingIcon = { Icon(Icons.Rounded.FileOpen, null) },
                        onClick = {
                            menu = false
                            importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Settings…")) },
                        leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                        onClick = {
                            menu = false
                            showSettings = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Licenses…")) },
                        leadingIcon = { Icon(Icons.Rounded.Gavel, null) },
                        onClick = {
                            menu = false
                            nav.licenses()
                        },
                    )
                    if (shown.isNotEmpty()) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(tr("Clear history"), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                confirmClear = true
                            },
                        )
                    }
                }
            }
        },
        overlay = {
            if (all.isEmpty()) {
                EmptyState(Icons.Rounded.History, tr("Nothing saved"), area.emptyHint())
            }
        },
        fill = if (showMap) {
            {
                HistoryPlaceMap(matched, onOpen = { nav.history(area, it) })
                Column(Modifier.align(Alignment.TopCenter)) {
                    HistoryFilters(importedShelf, { importedShelf = it }, grouping, { grouping = it }, sort, { sort = it })
                }
            }
        } else {
            null
        },
        searchQuery = query.takeIf { all.isNotEmpty() },
        onSearchChange = if (all.isNotEmpty()) {
            { query = it }
        } else {
            null
        },
        searchPlaceholder = tr("Name, place or address"),
    ) {
        if (!showMap && all.isNotEmpty()) {
            item(key = "filters") {
                HistoryFilters(importedShelf, { importedShelf = it }, grouping, { grouping = it }, sort, { sort = it })
            }
        }
        if (!showMap && all.isNotEmpty() && shown.isEmpty()) {
            item(key = "shelf-empty") {
                SectionFooter(
                    if (importedShelf) tr("Nothing imported yet.")
                    else tr("Nothing saved on this phone."),
                )
            }
        }
        if (!showMap && shown.isNotEmpty() && matched.isEmpty()) {
            item(key = "search-empty") {
                Box(Modifier.fillMaxWidth().padding(top = 36.dp)) {
                    EmptyState(
                        Icons.Rounded.SearchOff,
                        tr("No results"),
                        tr("Nothing matches “%s”.", needle),
                    )
                }
            }
        }
        if (!showMap && grouping == Grouping.LIST && matched.isNotEmpty()) {
            cardSection(
                matched,
                header = tr("Saved: %d", matched.size),
                headerIcon = Icons.AutoMirrored.Rounded.ViewList,
                key = { it.id },
                sectionKey = "list",
                dividerInset = 70.dp,
            ) { entry ->
                HistoryRow(
                    entry,
                    showPlace = true,
                    onOpen = { nav.history(area, entry.id) },
                    onDelete = { history.delete(entry.id, area) },
                )
            }
        }
        for (group in groups) {
            val open = group.id in expanded
            val toggle = { expanded = if (open) expanded - group.id else expanded + group.id }
            val icon = if (grouping == Grouping.PLACE) Icons.Rounded.Place else Icons.Rounded.CalendarMonth
            if (open) {
                cardSection(
                    group.entries,
                    header = group.label,
                    headerIcon = icon,
                    headerTrailing = { GroupTrailing(group.kinds, group.itemCount, expanded = true) },
                    onHeaderClick = toggle,
                    key = { it.id },
                    sectionKey = group.id,
                    dividerInset = 70.dp,
                ) { entry ->
                    HistoryRow(
                        entry,
                        showPlace = grouping == Grouping.DATE,
                        onOpen = { nav.history(area, entry.id) },
                        onDelete = { history.delete(entry.id, area) },
                    )
                }
            } else {
                item(key = "head-${group.id}", contentType = "section-header") {
                    GroupHeader(group.label, icon, group.kinds, group.itemCount, expanded = false, Modifier.animateItem(), toggle)
                }
            }
        }
        if (shown.isNotEmpty()) {
            item(key = "hint") { SectionFooter(tr("Swipe a saved scan to the left to delete it.")) }
        }
    }
}

/** Sort on one sideways row, shelf and grouping on the row under it. */
@Composable
private fun HistoryFilters(
    imported: Boolean,
    onImported: (Boolean) -> Unit,
    grouping: Grouping,
    onGrouping: (Grouping) -> Unit,
    sort: HistorySort,
    onSort: (HistorySort) -> Unit,
) {
    Column {
        ChipRow(contentPadding = SortChipPadding) {
            ToggleChip(tr("Newest"), sort == HistorySort.NEWEST, { onSort(HistorySort.NEWEST) })
            ToggleChip(tr("Oldest"), sort == HistorySort.OLDEST, { onSort(HistorySort.OLDEST) })
            ToggleChip(tr("Name"), sort == HistorySort.NAME, { onSort(HistorySort.NAME) })
            ToggleChip(tr("Most found"), sort == HistorySort.COUNT, { onSort(HistorySort.COUNT) })
        }
        ChipRow(contentPadding = FilterChipPadding) {
            ToggleChip(tr("On this phone"), !imported, { onImported(false) })
            ToggleChip(tr("Imported"), imported, { onImported(true) })
            ToggleChip(tr("By date"), grouping == Grouping.DATE, { onGrouping(Grouping.DATE) }, Icons.Rounded.CalendarMonth)
            ToggleChip(tr("By place"), grouping == Grouping.PLACE, { onGrouping(Grouping.PLACE) }, Icons.Rounded.Place)
            ToggleChip(tr("All"), grouping == Grouping.LIST, { onGrouping(Grouping.LIST) }, Icons.AutoMirrored.Rounded.ViewList)
        }
    }
}

private fun List<HistoryEntry>.ordered(sort: HistorySort): List<HistoryEntry> = when (sort) {
    HistorySort.NEWEST -> sortedByDescending { it.savedAt }
    HistorySort.OLDEST -> sortedBy { it.savedAt }
    HistorySort.NAME -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.placeLabel ?: it.rowTitle })
    HistorySort.COUNT -> sortedByDescending { it.itemCount }
}

private fun byDate(entries: List<HistoryEntry>): List<HistoryGroup> {
    val buckets = entries.groupBy { startOfDay(it.savedAt) }
    return buckets.entries.sortedByDescending { it.key }.map { (day, rows) ->
        HistoryGroup("date:$day", dayLabel(day), rows)
    }
}

private fun byPlace(entries: List<HistoryEntry>): List<HistoryGroup> {
    val buckets = entries.groupBy { it.placeLabel ?: it.placeKey ?: "" }
    return buckets.entries
        .map { (label, rows) ->
            HistoryGroup(
                "place:$label",
                label.ifEmpty { tr("No location") },
                rows,
            )
        }
        .sortedByDescending { it.entries.firstOrNull()?.savedAt ?: 0 }
}

private fun todayGroupId(): String = "date:${startOfDay(System.currentTimeMillis())}"

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

/** A collapsible section header, the same heading a Wi-Fi card uses, with the group's scan types. */
@Composable
private fun GroupHeader(
    label: String,
    icon: ImageVector,
    kinds: List<String>,
    count: Int,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    SectionHeader(
        label,
        modifier = modifier.clickable(onClick = onToggle),
        icon = icon,
        trailing = { GroupTrailing(kinds, count, expanded) },
    )
}

@Composable
private fun RowScope.GroupTrailing(kinds: List<String>, count: Int, expanded: Boolean) {
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
    val iconKind = entry.shownKinds().first()
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
        val who = entry.importedFrom ?: sourceName(entry.source)
        val line = when {
            place != null && entry.importedFrom != null -> "${ago(entry.savedAt)} · $place · ${entry.importedFrom}"
            else -> "${ago(entry.savedAt)} · ${place ?: who}"
        }
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
            ListRow(
                title = tr(entry.rowTitle),
                subtitle = line,
                subtitleLines = 1,
                leading = { IconTile(if (fromSensor) Icons.Rounded.Memory else kindIcon(iconKind), kindTint(iconKind)) },
                badges = {
                    if (entry.deauths.isNotEmpty()) {
                        Icon(Icons.Rounded.GppBad, contentDescription = tr("Deauth detected"), tint = Spectra.colors.danger, modifier = Modifier.padding(start = 2.dp))
                    }
                },
                trailing = { Pill("${entry.itemCount}", kindTint(iconKind)) },
                onClick = onOpen,
            )
        }
    }
}

@Composable
fun HistoryDetail(model: SpectraModel, nav: Navigator, id: String, area: HistoryArea) {
    model.generation
    var query by rememberSaveable { mutableStateOf("") }
    var wifiSort by rememberSaveable { mutableStateOf(WifiSort.SIGNAL) }
    var openOnly by rememberSaveable { mutableStateOf(false) }
    var knownOnly by rememberSaveable { mutableStateOf(false) }
    var band by rememberSaveable { mutableStateOf<Band?>(null) }
    var showWifiOut by rememberSaveable { mutableStateOf(false) }
    var bleSort by rememberSaveable { mutableStateOf(BleSort.SIGNAL) }
    var bleScope by rememberSaveable { mutableStateOf(BleScope.ALL) }
    var showBleOut by rememberSaveable { mutableStateOf(false) }
    // The session as this tab shows it: another tab's records in it stay out of sight.
    val entry = model.history.entry(id)?.only(area)
    if (entry == null) {
        SpectraScreen(tr("History"), onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.History, tr("Gone"), tr("This saved scan is no longer here."))
        }) {}
        return
    }
    val needle = query.trim()
    val knownSsids = model.link.knownSsids()
    val showWifi = entry.networks.isNotEmpty() || entry.probes.isNotEmpty()
    val showBle = entry.bluetooth.isNotEmpty()
    val showKnown = entry.networks.any { it.hasStoredPassword(knownSsids) }
    val filterKnown = knownOnly && showKnown
    val networks = entry.networks.filter {
        it.passesWifiFilter(showWifiOut, openOnly, band, needle, filterKnown, knownSsids)
    }.sortedForWifi(wifiSort)
    val probes = entry.probes.filter { it.passesWifiFilter(openOnly, band, needle, filterKnown) }
    val bands = entry.networks.map { it.latest.band }.toSet()
    val bleNow = entry.bluetooth.maxOfOrNull { it.lastSeen } ?: entry.savedAt
    val bluetooth = entry.bluetooth.arranged(bleNow, bleSort, bleScope, showBleOut, needle)
    val lan = entry.lan.filter { device ->
        needle.isEmpty() ||
            device.ipAddress.contains(needle, true) ||
            device.displayName.contains(needle, true) ||
            device.macAddress?.text?.contains(needle, true) == true ||
            device.manufacturer?.contains(needle, true) == true ||
            device.identity.label.contains(needle, true)
    }
    val deauths = entry.deauths.filter { event ->
        needle.isEmpty() ||
            event.bssid.text.contains(needle, true) ||
            event.bssid.vendor()?.contains(needle, true) == true ||
            event.source.text.contains(needle, true) ||
            event.target.text.contains(needle, true)
    }
    val connections = entry.connections.filter { saved ->
        if (needle.isEmpty()) return@filter true
        val report = saved.toReport()
        statusLabel(saved.status).contains(needle, true) || connectionSummary(report, saved.viaVpn).contains(needle, true)
    }
    val searchable = showWifi || showBle || entry.lan.isNotEmpty()
    val placeholder = when {
        showWifi && !showBle && entry.lan.isEmpty() -> tr("Name, BSSID or maker")
        showBle && !showWifi && entry.lan.isEmpty() -> tr("Name, company or address")
        !showWifi && !showBle -> tr("IP, name or MAC")
        else -> tr("Name, place or address")
    }
    val who = entry.importedFrom ?: sourceName(entry.source)
    val footer = "$who · ${dateTime(entry.savedAt)}"
    val context = LocalContext.current
    SpectraScreen(
        title = tr(entry.rowTitle),
        subtitle = footer,
        onBack = nav::back,
        actions = {
            ExportMenu(listOf(entry))
            IconButton(onClick = {
                model.history.delete(id, area)
                nav.back()
            }) { Icon(Icons.Rounded.Delete, contentDescription = tr("Delete")) }
        },
        searchQuery = query.takeIf { searchable },
        onSearchChange = if (searchable) {
            { query = it }
        } else {
            null
        },
        searchPlaceholder = placeholder,
    ) {
        if (showWifi) {
            if (showBle) {
                item(key = "wifi-label") { SectionHeader("Wi‑Fi", icon = Icons.Rounded.Wifi) }
            }
            item(key = "wifi-sort") { WifiSortRow(wifiSort) { wifiSort = it } }
            item(key = "wifi-filters") {
                WifiFilterRow(
                    openOnly = openOnly,
                    onOpenOnly = { openOnly = !openOnly },
                    showKnown = showKnown,
                    knownOnly = knownOnly,
                    onKnownOnly = { knownOnly = !knownOnly },
                    bands = bands,
                    band = band,
                    onBand = { band = it },
                    showOut = showWifiOut,
                    onShowOut = { showWifiOut = !showWifiOut },
                )
            }
        }
        if (showBle) {
            if (showWifi) {
                item(key = "ble-label") { SectionHeader("Bluetooth", icon = Icons.Rounded.Bluetooth) }
            }
            item(key = "ble-sort") { BleSortRow(bleSort) { bleSort = it } }
            item(key = "ble-filters") {
                BleFilterRow(bleScope, { bleScope = it }, showBleOut) { showBleOut = !showBleOut }
            }
        }
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
            deauths,
            header = tr("Deauth detected"),
            key = { "d-" + it.id },
            sectionKey = "deauth",
            headerTint = { Spectra.colors.danger },
            headerIcon = Icons.Rounded.GppBad,
            dividerInset = 70.dp,
        ) { DeauthRow(it) }
        cardSection(
            networks,
            header = tr("Networks: %d", networks.size),
            key = { "n-" + it.id.text },
            sectionKey = "networks",
            dividerInset = 70.dp,
        ) { network ->
            NetworkRow(
                network,
                onClick = { nav.network(network.id, entry.id) },
                now = entry.savedAt,
                known = network.hasStoredPassword(knownSsids),
            )
        }
        cardSection(
            probes,
            header = tr("Probing: %d", probes.size),
            key = { "p-" + it.mac.text },
            sectionKey = "probes",
            dividerInset = 70.dp,
        ) { probe -> ProbeRow(probe, null, onClick = { nav.client(probe.mac, entry.id) }) }
        cardSection(
            connections,
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
            lan,
            header = tr("Devices: %d", lan.size),
            key = { "h-" + it.ipAddress },
            sectionKey = "lan",
            dividerInset = 70.dp,
        ) { device -> DeviceRow(device, onClick = { nav.host(device.ipAddress, entry.id) }) }
        cardSection(
            bluetooth,
            header = tr("Devices: %d", bluetooth.size),
            key = { "b-" + it.address },
            sectionKey = "bluetooth",
            dividerInset = 70.dp,
        ) { device -> BleRow(device, device.lastSeen, onClick = { nav.ble(device.address, entry.id) }) }
        if (
            entry.hasRecords &&
            deauths.isEmpty() && networks.isEmpty() && probes.isEmpty() &&
            connections.isEmpty() && lan.isEmpty() && bluetooth.isEmpty()
        ) {
            item(key = "filtered-empty") {
                Box(Modifier.fillMaxWidth().padding(top = 36.dp)) {
                    EmptyState(
                        Icons.Rounded.SearchOff,
                        tr("No results"),
                        if (needle.isNotEmpty()) tr("Nothing matches “%s”.", needle) else tr("Nothing in this filter is nearby."),
                    )
                }
            }
        }
        item(key = "saved-footer") { SectionFooter(footer) }
    }
}
