package cattivole.spectra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.platform.BleGattClient
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Warning
import cattivole.spectra.core.TrackerAssessment
import cattivole.spectra.core.TrackerDetector
import cattivole.spectra.core.BleAdvertisement
import cattivole.spectra.core.BleDevice
import cattivole.spectra.core.BleServiceRef
import cattivole.spectra.core.BeaconDecode
import cattivole.spectra.core.GattValue
import cattivole.spectra.core.SignalQuality
import cattivole.spectra.core.WatchList
import cattivole.spectra.core.hex
import cattivole.spectra.platform.DeviceBleScanner

internal enum class BleSort { SIGNAL, NAME, RECENT }
internal enum class BleScope { ALL, NAMED, CONNECTABLE }

@Composable
internal fun BleSortRow(sort: BleSort, onSort: (BleSort) -> Unit) {
    ChipRow(contentPadding = SortChipPadding) {
        ToggleChip(tr("Signal"), sort == BleSort.SIGNAL, { onSort(BleSort.SIGNAL) })
        ToggleChip(tr("Name"), sort == BleSort.NAME, { onSort(BleSort.NAME) })
        ToggleChip(tr("Recently seen"), sort == BleSort.RECENT, { onSort(BleSort.RECENT) })
    }
}

@Composable
internal fun BleFilterRow(scope: BleScope, onScope: (BleScope) -> Unit, showOut: Boolean, onShowOut: () -> Unit) {
    ChipRow(contentPadding = FilterChipPadding) {
        ToggleChip(tr("With a name"), scope == BleScope.NAMED, { onScope(if (scope == BleScope.NAMED) BleScope.ALL else BleScope.NAMED) })
        ToggleChip(tr("Connectable"), scope == BleScope.CONNECTABLE, { onScope(if (scope == BleScope.CONNECTABLE) BleScope.ALL else BleScope.CONNECTABLE) })
        ToggleChip(tr("Out of range"), showOut, onShowOut)
    }
}

internal fun Iterable<BleDevice>.arranged(
    now: Long,
    sort: BleSort,
    scope: BleScope,
    showOut: Boolean,
    needle: String,
): List<BleDevice> = filter { device ->
    (showOut || device.isNearby(now)) &&
        when (scope) {
            BleScope.ALL -> true
            BleScope.NAMED -> device.hasOwnName
            BleScope.CONNECTABLE -> device.isConnectable == true
        } &&
        (
            needle.isEmpty() ||
                device.shownName().contains(needle, true) ||
                device.shownSubtitle().contains(needle, true) ||
                device.address.contains(needle, true) ||
                device.services.any { it.name?.contains(needle, true) == true }
            )
}.sortedWith { a, b ->
    val near = b.isNearby(now).compareTo(a.isNearby(now))
    if (near != 0) return@sortedWith near
    val order = when (sort) {
        BleSort.SIGNAL -> b.orderRssi.compareTo(a.orderRssi)
        BleSort.NAME -> a.shownName().compareTo(b.shownName(), ignoreCase = true)
        BleSort.RECENT -> (b.lastSeen / 10_000).compareTo(a.lastSeen / 10_000)
    }
    if (order != 0) order else a.address.compareTo(b.address)
}

private fun unavailableTitle(why: DeviceBleScanner.Availability): String = when (why) {
    DeviceBleScanner.Availability.STARTING -> tr("Starting Bluetooth…")
    DeviceBleScanner.Availability.POWERED_OFF -> tr("Bluetooth is off")
    DeviceBleScanner.Availability.UNAUTHORIZED -> tr("Bluetooth permission is off")
    DeviceBleScanner.Availability.UNSUPPORTED -> tr("No Bluetooth radio")
}

@Composable
fun BluetoothScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val ble = model.ble
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(BleSort.SIGNAL) }
    var scope by rememberSaveable { mutableStateOf(BleScope.ALL) }
    var showOut by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    /** The Trackers section, folded to the tags that need a look; open, it lists every tag heard. */
    var trackersOpen by rememberSaveable { mutableStateOf(false) }
    val now = ble.now
    val needle = query.trim()
    val heard = ble.devices.values
    val rows = heard.arranged(now, sort, scope, showOut, needle)
    val watching = WatchList.presentDevices(model.watchedIds, heard, now)
    val trackers = if (needle.isEmpty()) TrackerDetector.trackers(heard.filter { it.isNearby(now) }, now) else emptyList()
    val alarming = trackers.any { it.second.level == TrackerAssessment.Level.ALERT }
    val state = ble.state
    SpectraScreen(
        title = "Bluetooth",
        actions = {
            HistoryButton(nav, cattivole.spectra.core.HistoryArea.BLUETOOTH)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = tr("Options")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuLabel(tr("Sort by"))
                    BleSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label()) },
                            leadingIcon = { MenuCheck(sort == option) },
                            onClick = { sort = option },
                        )
                    }
                    HorizontalDivider()
                    MenuLabel(tr("Show"))
                    BleScope.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label()) },
                            leadingIcon = { MenuCheck(scope == option) },
                            onClick = { scope = option },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(tr("Show out of range")) },
                        leadingIcon = { Icon(if (showOut) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null) },
                        onClick = { showOut = !showOut },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (ble.isScanning) tr("Stop") else tr("Scan now")) },
                        leadingIcon = { Icon(if (ble.isScanning) Icons.Rounded.Stop else Icons.Rounded.Refresh, null) },
                        enabled = state !is DeviceBleScanner.State.Unavailable,
                        onClick = {
                            if (ble.isScanning) ble.stop() else ble.start()
                            menu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Classic inquiry")) },
                        leadingIcon = { Icon(if (ble.classicInquiry) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null) },
                        onClick = {
                            ble.setClassic(!ble.classicInquiry)
                            menu = false
                        },
                    )
                    if (heard.isNotEmpty()) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(tr("Clear list")) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                            onClick = {
                                ble.clear()
                                menu = false
                            },
                        )
                    }
                }
            }
        },
        refreshing = false,
        onRefresh = { if (state !is DeviceBleScanner.State.Unavailable) ble.start() },
        searchQuery = query.takeIf { heard.isNotEmpty() },
        onSearchChange = if (heard.isNotEmpty()) {
            { query = it }
        } else {
            null
        },
        searchPlaceholder = tr("Name, company or address"),
        overlay = {
            when {
                heard.isEmpty() && state is DeviceBleScanner.State.Unavailable -> EmptyState(
                    Icons.Rounded.BluetoothDisabled,
                    unavailableTitle(state.why),
                    when (state.why) {
                        DeviceBleScanner.Availability.POWERED_OFF -> tr("Turn Bluetooth on in quick settings.")
                        DeviceBleScanner.Availability.UNAUTHORIZED -> tr("Spectra is not allowed to scan for nearby devices.")
                        DeviceBleScanner.Availability.UNSUPPORTED -> tr("This phone has no Bluetooth Low Energy.")
                        DeviceBleScanner.Availability.STARTING -> tr("Starting Bluetooth…")
                    },
                )
                heard.isEmpty() && state is DeviceBleScanner.State.Scanning -> EmptyState(
                    Icons.AutoMirrored.Rounded.BluetoothSearching,
                    tr("Listening"),
                    tr("Devices appear as their advertisements arrive."),
                    busy = true,
                )
                heard.isEmpty() -> EmptyState(
                    Icons.Rounded.Bluetooth,
                    "Bluetooth",
                    tr("Phones, headphones, sensors and other devices that are advertising nearby."),
                    action = tr("Scan"),
                    onAction = ble::start,
                )
                rows.isEmpty() && needle.isNotEmpty() -> EmptyState(Icons.Rounded.SearchOff, tr("No results"), tr("Nothing matches “%s”.", needle))
                rows.isEmpty() -> EmptyState(Icons.Rounded.Bluetooth, tr("No devices"), tr("Nothing in this filter is nearby."))
            }
        },
    ) {
        item(key = "status") {
            ChipRow(Modifier.padding(bottom = 4.dp)) {
                val nearby = ble.nearbyCount
                StatusChip(
                    icon = Icons.Rounded.Bluetooth,
                    title = when (state) {
                        DeviceBleScanner.State.Scanning -> tr("Scanning…")
                        DeviceBleScanner.State.Idle -> tr("Paused")
                        is DeviceBleScanner.State.Unavailable -> unavailableTitle(state.why)
                    },
                    detail = if (nearby == heard.size) tr("Nearby: %d", nearby) else tr("Nearby: %d · seen: %d", nearby, heard.size),
                    tint = if (state is DeviceBleScanner.State.Unavailable) Spectra.colors.warning else MaterialTheme.colorScheme.primary,
                    busy = ble.isScanning,
                    onClick = {
                        if (ble.isScanning) ble.stop() else if (state !is DeviceBleScanner.State.Unavailable) ble.start()
                    },
                )
            }
        }
        if (heard.isNotEmpty()) {
            item(key = "sort") { BleSortRow(sort) { sort = it } }
            item(key = "filters") {
                BleFilterRow(scope, { scope = it }, showOut) { showOut = !showOut }
            }
        }
        if (ble.classicInquiry) {
            card(key = "classic", rows = listOf {
                NoteRow(tr("Classic inquiry pauses low-energy scanning on many phones until it finishes."), warning = false)
            })
        }
        // Tags near their owners are everyday keys and bags: folded away unless asked for, the alarming ones stay.
        val rankedTrackers = trackers.sortedByDescending { it.second.level }
        val shownTrackers = if (trackersOpen) rankedTrackers else rankedTrackers.filter { it.second.level != TrackerAssessment.Level.INFO }
        val toggleTrackers = { trackersOpen = !trackersOpen }
        val trackersTint: @Composable () -> Color = { if (alarming) Spectra.colors.danger else Spectra.colors.warning }
        val trackersTrailing: @Composable RowScope.() -> Unit = {
            Text(
                if (trackersOpen) tr("Fold") else tr("All %d", trackers.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                if (trackersOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        if (trackers.isNotEmpty() && shownTrackers.isEmpty()) {
            item(key = "header-trackers") {
                SectionHeader(
                    tr("Trackers: %d, all near their owners", trackers.size),
                    Modifier.animateItem().clickable(onClick = toggleTrackers),
                    tint = trackersTint(),
                    icon = Icons.Rounded.Sell,
                    trailing = trackersTrailing,
                )
            }
        }
        cardSection(
            shownTrackers,
            header = tr("Trackers"),
            footer = if (alarming) {
                tr("A tag away from its owner has stayed near you for a while. Move somewhere else and check whether it is still here.")
            } else if (trackersOpen) {
                tr("AirTags, Tile, Samsung SmartTag, Chipolo and Find My Device tags report their position through other people's phones. One near its owner is just someone's keys.")
            } else {
                null
            },
            key = { "tracker-" + it.first.address },
            sectionKey = "trackers",
            headerTint = trackersTint,
            headerIcon = Icons.Rounded.Sell,
            headerTrailing = if (trackers.size > shownTrackers.size || trackersOpen) trackersTrailing else null,
            onHeaderClick = toggleTrackers,
            dividerInset = 70.dp,
        ) { (device, assessment) -> TrackerRow(device, assessment, onClick = { nav.ble(device.address) }) }
        cardSection(
            watching,
            header = tr("Watching"),
            key = { "watch-" + it.address },
            sectionKey = "watching",
            headerTint = { Spectra.colors.warning },
            headerIcon = Icons.Rounded.Star,
            dividerInset = 70.dp,
        ) { device -> BleRow(device, now, onClick = { nav.ble(device.address) }, watched = true) }
        cardSection(
            rows,
            header = tr("Nearby: %d", ble.nearbyCount),
            footer = tr("Android reports the hardware address. A resolvable private address still changes, so the same device can reappear under a new one."),
            key = { it.address },
            sectionKey = "devices",
            dividerInset = 70.dp,
        ) { device -> BleRow(device, now, onClick = { nav.ble(device.address) }, watched = model.isWatched(device.address)) }
    }
}

private fun BleSort.label(): String = when (this) {
    BleSort.SIGNAL -> tr("Signal")
    BleSort.NAME -> tr("Name")
    BleSort.RECENT -> tr("Recently seen")
}

private fun BleScope.label(): String = when (this) {
    BleScope.ALL -> tr("All devices")
    BleScope.NAMED -> tr("With a name")
    BleScope.CONNECTABLE -> tr("Connectable")
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun MenuCheck(selected: Boolean) {
    if (selected) Icon(Icons.Rounded.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
}

/** A tag in the Trackers section: what it is, whether its owner is nearby, and how long it has stayed. */
@Composable
private fun TrackerRow(device: BleDevice, assessment: TrackerAssessment, onClick: () -> Unit) {
    val color = when (assessment.level) {
        TrackerAssessment.Level.ALERT -> Spectra.colors.danger
        TrackerAssessment.Level.WATCH -> Spectra.colors.warning
        TrackerAssessment.Level.INFO -> MaterialTheme.colorScheme.outline
    }
    val minutes = (assessment.presentFor / 60_000).toInt()
    val detail = listOfNotNull(
        assessment.ownerLabel?.let { tr(it) },
        if (minutes >= 1) tr("near you for %d min", minutes) else tr("just appeared"),
        assessment.batteryLabel?.let { tr(it) },
    ).joinToString(" · ")
    ListRow(
        title = tr(assessment.kind.label),
        subtitle = detail,
        detail = device.address,
        leading = { IconTile(if (assessment.level == TrackerAssessment.Level.ALERT) Icons.Rounded.Warning else Icons.Rounded.Sell, color) },
        trailing = { SignalTrailing(device.rssi, true, device.samples.map { it.rssi }) },
        onClick = onClick,
    )
}

@Composable
fun BleRow(device: BleDevice, now: Long, onClick: () -> Unit, watched: Boolean = false) {
    val nearby = device.isNearby(now)
    WithCopyMenu(
        listOf(
            CopyItem(tr("Copy name"), tr("Name"), device.shownName()),
            CopyItem(tr("Copy address"), tr("Address"), device.address),
        ),
    ) { open ->
        ListRow(
            title = device.shownName(),
            subtitle = if (nearby) device.shownSubtitle() else tr("Out of range · %s", ago(device.lastSeen, now)),
            dimmed = !nearby,
            leading = { IconTile(device.icon(), if (nearby) signalColor(device.rssi) else MaterialTheme.colorScheme.outline) },
            badges = {
                if (device.isConnectable == true) {
                    Icon(
                        Icons.Rounded.Link,
                        contentDescription = tr("Connectable"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (device.services.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.Dns,
                        contentDescription = tr("Services listed"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (device.isSpectraSensor) Pill(tr("Sensor"), MaterialTheme.colorScheme.primary)
                if (device.transport == BleDevice.Transport.CLASSIC) Pill(tr("Classic"), MaterialTheme.colorScheme.secondary)
                if (watched) {
                    Icon(Icons.Rounded.Star, contentDescription = tr("Watching"), tint = Spectra.colors.warning, modifier = Modifier.size(14.dp))
                }
            },
            trailing = { SignalTrailing(device.rssi, nearby, device.samples.map { it.rssi }) },
            onClick = onClick,
            onLongClick = open,
        )
    }
}

@Composable
fun BleDetail(model: SpectraModel, nav: Navigator, address: String, entryId: String?) {
    model.generation
    val entry = entryId?.let { model.history.entry(it) }
    val device = if (entryId != null) entry?.ble(address) else model.ble.devices[address]
    if (device == null) {
        SpectraScreen(address, onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.Bluetooth, tr("Device cleared"), tr("It has not advertised since this list was cleared."))
        }) {}
        return
    }
    val snapshot = entryId != null
    var showRadar by rememberSaveable { mutableStateOf(false) }
    if (showRadar && !snapshot) {
        RadarScreen(model, device) { showRadar = false }
        return
    }
    DisposableEffect(address, snapshot) {
        onDispose { if (!snapshot) model.gatt.disconnect() }
    }
    val now = if (snapshot) device.lastSeen else model.ble.now
    val nearby = device.isNearby(now)
    val quality = SignalQuality.of(device.rssi)
    val color = if (nearby) Spectra.colors.of(quality) else MaterialTheme.colorScheme.outline
    SpectraScreen(
        title = device.shownName(),
        subtitle = if (snapshot) entry?.let { tr("Saved %s", dateTime(it.savedAt)) } else device.address,
        onBack = nav::back,
        actions = {
            // The card reads what the device advertises, and its GATT services once they were read here.
            if (!snapshot) {
                AskAssistantButton(model, nav, subject = { cattivole.spectra.assistant.CardSubject.Bluetooth(device.address) })
            }
            if (!snapshot) {
                val watched = model.isWatched(device.address)
                IconButton(onClick = { model.toggleWatch(device.address) }) {
                    Icon(
                        if (watched) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = if (watched) tr("Stop watching") else tr("Watch device"),
                        tint = if (watched) Spectra.colors.warning else LocalContentColor.current,
                    )
                }
                IconButton(onClick = { showRadar = true }) {
                    Icon(Icons.Rounded.TrackChanges, contentDescription = tr("Find"))
                }
            }
        },
    ) {
        freeCard("hero") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconTile(device.icon(), color, size = 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(device.shownName(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        if (device.isSpectraSensor) Pill(tr("Sensor"), MaterialTheme.colorScheme.primary)
                        device.companyName?.takeIf { it != device.displayName }?.let { Pill(it, MaterialTheme.colorScheme.secondary) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${device.rssi}", style = MaterialTheme.typography.displayMedium.merge(Tabular), fontWeight = FontWeight.SemiBold)
                Text(
                    tr("dBm"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (nearby) tr(quality.label) else tr("Out of range"),
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Text(
                tr("avg %d · min %d · max %d", device.averageRssi, device.weakestRssi, device.strongestRssi),
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            SignalHistoryChart(
                device.samples.map { it.date to it.rssi },
                color,
                ceiling = -15,
                placeholder = tr("The graph fills in as advertisements arrive."),
            )
        }
        card(
            header = tr("Identity"),
            key = "identity",
            footer = tr("A resolvable private address changes every few minutes; a public or static one stays."),
            rows = buildList {
                add { InfoRow(tr("Address"), device.address, mono = true, copyable = true) }
                add { InfoRow(tr("Address type"), tr(device.addressKind)) }
                add { InfoRow(tr("Transport"), if (device.transport == BleDevice.Transport.CLASSIC) tr("Classic") else tr("Low energy")) }
                device.isConnectable?.let { add { InfoRow(tr("Connection"), if (it) tr("Connectable") else tr("Not connectable")) } }
                device.txPower?.let { add { InfoRow(tr("TX power"), tr("%d dBm", it)) } }
                device.companyName?.let { add { InfoRow(tr("Company"), it) } }
                device.deviceClass?.let { add { InfoRow(tr("Class"), tr(it)) } }
            },
        )
        if (!snapshot && device.isConnectable != false) {
            val gatt = model.gatt
            val bound = gatt.deviceAddress == device.address
            val gstate: BleGattClient.State = if (bound) gatt.state else BleGattClient.State.Idle
            card(
                header = tr("Services (GATT)"),
                key = "gatt",
                footer = tr("Connects to the device to list its services and read the values it exposes. Many devices allow only a brief connection."),
                rows = buildList {
                    add {
                        when (gstate) {
                            is BleGattClient.State.Idle ->
                                ActionRow(tr("Connect and read services"), Icons.AutoMirrored.Rounded.BluetoothSearching, onClick = { gatt.connect(device.address) })
                            is BleGattClient.State.Connecting -> NoteRow(tr("Connecting…"), warning = false)
                            is BleGattClient.State.Discovering -> NoteRow(tr("Reading services…"), warning = false)
                            is BleGattClient.State.Connected ->
                                ActionRow(tr("Disconnect"), Icons.Rounded.Close, onClick = { gatt.disconnect() }, tint = Spectra.colors.danger)
                            is BleGattClient.State.Failed -> NoteRow(tr(gstate.message), warning = true)
                        }
                    }
                    if (bound) {
                        gatt.services.forEach { svc -> add { GattServiceRow(svc) } }
                    }
                },
            )
        }
        cardSection(device.apple, header = "Apple", key = null, sectionKey = "apple") { message ->
            ListRow(title = appleLabel(message), subtitle = appleDetail(message), subtitleLines = 3)
        }
        cardSection(device.services, header = tr("Services"), key = { "s-" + it.uuid }, sectionKey = "services") { ServiceRow(it) }
        cardSection(device.solicitedServices, header = tr("Looking for"), key = { "l-" + it.uuid }, sectionKey = "solicited") { ServiceRow(it) }
        cardSection(device.serviceData, header = tr("Service data"), key = { "d-" + it.uuid }, sectionKey = "service-data") { item ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(item.name ?: item.uuid, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (item.name != null) {
                    Text(item.uuid, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val beacon = BeaconDecode.decode(item.uuid, item.data)
                if (beacon != null) {
                    Text(
                        beacon.detail?.let { "${tr(beacon.title)} · $it" } ?: tr(beacon.title),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(hex(item.data), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (device.manufacturerPayload.isNotEmpty()) {
            card(header = tr("Manufacturer data"), key = "manufacturer", rows = listOf {
                val copy = rememberCopier()
                ListRow(
                    title = device.companyId?.let { "0x%04X".format(it) } ?: "—",
                    subtitle = hex(device.manufacturerPayload),
                    monoSubtitle = true,
                    subtitleLines = 6,
                    onLongClick = { copy(tr("Manufacturer data"), hex(device.manufacturerPayload)) },
                )
            })
        }
        card(
            header = tr("Observed"),
            key = "observed",
            rows = listOf(
                { InfoRow(tr("First seen"), clockTime(device.firstSeen)) },
                { InfoRow(tr("Last seen"), ago(device.lastSeen, if (snapshot) entry?.savedAt ?: now else now)) },
                { InfoRow(tr("Packets"), "${device.packetCount}") },
            ),
        )
    }
}

@Composable
private fun ServiceRow(service: BleServiceRef) {
    ListRow(
        title = service.name ?: service.uuid,
        subtitle = if (service.name != null) service.uuid else null,
        monoSubtitle = true,
    )
}

@Composable
private fun GattServiceRow(service: BleGattClient.Service) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(service.name ?: service.uuid, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        if (service.name != null) {
            Text(service.uuid, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        service.characteristics.forEach { ch ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    ch.name ?: ch.uuid,
                    style = if (ch.name != null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    fontFamily = if (ch.name != null) null else FontFamily.Monospace,
                    fontWeight = if (ch.name != null) FontWeight.Medium else null,
                    modifier = Modifier.weight(1f),
                )
                if (ch.properties.isNotEmpty()) {
                    Text(ch.properties, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (ch.name != null) {
                Text(ch.uuid, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val value = ch.value
            if (value != null && value.isNotEmpty()) {
                val decoded = GattValue.decode(ch.uuid, value)?.let { tr(it) }
                if (decoded != null) {
                    Text(decoded, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
                }
                Text(
                    rawGatt(value),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Hex, and the letters when every byte is plain text. */
private fun rawGatt(bytes: ByteArray): String {
    val hex = hex(bytes)
    val printable = bytes.all { it.toInt() in 32..126 }
    return if (printable) "$hex  “${String(bytes, Charsets.US_ASCII)}”" else hex
}
