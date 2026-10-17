package cattivole.spectra.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WaterfallChart
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.Band
import cattivole.spectra.core.Capabilities
import cattivole.spectra.core.ChannelAdvisor
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.DeauthEvent
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.ProbingDevice
import cattivole.spectra.core.RadioSource
import cattivole.spectra.core.SecondaryChannel
import cattivole.spectra.core.SignalQuality
import cattivole.spectra.core.TrackedNetwork
import cattivole.spectra.core.WifiClient
import cattivole.spectra.platform.PhoneWifiScanner
import java.util.Locale

private enum class WifiSort { SIGNAL, NAME, CHANNEL }

@Composable
fun NetworksScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val store = model.store
    val notify = LocalNotify.current
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(WifiSort.SIGNAL) }
    var showOut by rememberSaveable { mutableStateOf(true) }
    var band by rememberSaveable { mutableStateOf<Band?>(null) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val all = store.networks().values
    val needle = query.trim()
    val networks = all.filter { network ->
        (showOut || network.isInRange) &&
            (band == null || network.latest.band == band) &&
            (
                needle.isEmpty() ||
                    network.title.contains(needle, ignoreCase = true) ||
                    network.id.text.contains(needle, ignoreCase = true) ||
                    network.id.vendor()?.contains(needle, ignoreCase = true) == true
                )
    }.sortedWith { a, b ->
        if (a.isInRange != b.isInRange) return@sortedWith if (a.isInRange) -1 else 1
        when (sort) {
            WifiSort.NAME -> a.title.compareTo(b.title, ignoreCase = true)
            WifiSort.CHANNEL -> compareValuesBy(a, b, { it.latest.band }, { it.latest.channel }, { -it.latest.rssi })
            WifiSort.SIGNAL -> b.latest.rssi.compareTo(a.latest.rssi)
        }
    }
    val bands = all.map { it.latest.band }.toSet()
    val refreshing = store.isScanning || model.phone.status == PhoneWifiScanner.Status.SCANNING
    SpectraScreen(
        title = "Wi‑Fi",
        actions = {
            IconButton(
                onClick = {
                    model.history.saveNetworks(store, tr("This phone"))
                    notify(tr("Saved to History"))
                },
                enabled = all.isNotEmpty() || store.probingDevices.isNotEmpty() || store.deauthEvents.isNotEmpty(),
            ) { Icon(Icons.Rounded.SaveAlt, contentDescription = tr("Save")) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = tr("Options")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Scan now")) },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                        onClick = {
                            model.link.scanNow()
                            model.phone.refresh()
                            menu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Channels")) },
                        leadingIcon = { Icon(Icons.Rounded.WaterfallChart, null) },
                        onClick = {
                            menu = false
                            nav.channels()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(tr("Clear list")) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                        onClick = {
                            store.clear()
                            menu = false
                        },
                    )
                }
            }
        },
        refreshing = refreshing,
        onRefresh = {
            model.link.scanNow()
            model.phone.refresh()
        },
        overlay = {
            when {
                all.isEmpty() && !model.link.isConnected && model.phone.lastResults == 0 -> EmptyState(
                    icon = Icons.Rounded.WifiFind,
                    title = tr("No networks yet"),
                    body = phoneRadioNote(model)
                        ?: tr("The phone lists what its own radio hears, including 5 GHz. A Cardputer adds clients, probe requests, deauth frames and hidden names."),
                    action = tr("Find sensor"),
                    onAction = nav::sensorTab,
                )
                all.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.Wifi,
                    title = tr("Listening"),
                    body = tr("Networks appear here after the first scan."),
                    busy = true,
                )
                networks.isEmpty() && needle.isNotEmpty() -> EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    title = tr("No results"),
                    body = tr("Nothing matches “%s”.", needle),
                )
            }
        },
    ) {
        item(key = "status") { WifiStatusChips(model, nav) }
        item(key = "search") { SearchField(query, { query = it }, tr("Name, BSSID or maker")) }
        item(key = "filters") {
            ChipRow {
                ToggleChip(tr("Signal"), sort == WifiSort.SIGNAL, { sort = WifiSort.SIGNAL })
                ToggleChip(tr("Name"), sort == WifiSort.NAME, { sort = WifiSort.NAME })
                ToggleChip(tr("Channel"), sort == WifiSort.CHANNEL, { sort = WifiSort.CHANNEL })
                if (bands.size > 1) {
                    for (shown in Band.entries.filter { it in bands }) {
                        ToggleChip(bandLabel(shown), band == shown, { band = if (band == shown) null else shown })
                    }
                }
                ToggleChip(tr("Out of range"), showOut, { showOut = !showOut })
            }
        }
        phoneRadioNote(model)?.takeIf { all.isNotEmpty() }?.let { note ->
            card(key = "phone-note", rows = listOf { NoteRow(note, warning = model.phone.status != PhoneWifiScanner.Status.SCANNING) })
        }
        cardSection(
            store.deauthEvents,
            header = tr("Deauth detected"),
            footer = tr("Deauthentication frames force devices off the network. A burst or a broadcast target suggests an active attack."),
            key = { it.id },
            sectionKey = "deauth",
            headerTint = { Spectra.colors.danger },
            headerIcon = Icons.Rounded.GppBad,
            dividerInset = 70.dp,
        ) { DeauthRow(it) }
        cardSection(
            networks,
            header = tr("In the latest pass: %d", all.count { it.wasInLastScan }),
            key = { it.id.text },
            sectionKey = "networks",
            dividerInset = 70.dp,
        ) { network -> NetworkRow(network, onClick = { nav.network(network.id) }) }
        cardSection(
            store.probingDevices,
            header = tr("Probing: %d", store.probingDevices.size),
            footer = tr("Devices sending probe requests nearby, revealing networks they have connected to before."),
            key = { "probe-" + it.mac.text },
            sectionKey = "probes",
            dividerInset = 70.dp,
        ) { device -> ProbeRow(device, lanHost(model, device.mac), onClick = { nav.client(device.mac) }) }
    }
}

@Composable
private fun WifiStatusChips(model: SpectraModel, nav: Navigator) {
    val store = model.store
    val link = model.link
    val now = rememberNow()
    val scanStart = rememberStart(store.isScanning)
    ChipRow(Modifier.padding(bottom = 4.dp)) {
        StatusChip(
            icon = Icons.Rounded.SettingsInputAntenna,
            title = sensorHeadline(model),
            detail = sensorDetail(model),
            tint = if (link.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            busy = store.isScanning || link.state is cattivole.spectra.platform.SensorLink.State.Searching ||
                link.state is cattivole.spectra.platform.SensorLink.State.Connecting,
            onClick = nav::sensorTab,
        )
        val anchor = scanStart ?: store.lastScanFinished
        if (link.isConnected && anchor != null) {
            StatusChip(
                icon = Icons.Rounded.Timer,
                title = stopwatch(now - anchor),
                detail = if (store.isScanning) tr("Scanning") else tr("Since the last scan"),
                tint = Spectra.colors.info,
                busy = store.isScanning,
                onClick = link::scanNow,
            )
        }
        val phoneNetworks = store.networks().values.filter { it.source == RadioSource.PHONE && it.isInRange }
        StatusChip(
            icon = Icons.Rounded.PhoneAndroid,
            title = tr("This phone"),
            detail = when (model.phone.status) {
                PhoneWifiScanner.Status.WIFI_OFF -> tr("Wi-Fi is off")
                PhoneWifiScanner.Status.PERMISSION -> tr("No permission")
                PhoneWifiScanner.Status.LOCATION_OFF -> tr("Location is off")
                else -> phoneNetworks.groupBy { it.latest.band }.entries.sortedBy { it.key }
                    .joinToString(" · ") { "${it.value.size} × ${bandLabel(it.key)}" }
                    .ifEmpty { tr("Nothing heard") }
            },
            tint = Spectra.colors.revealed,
            busy = model.phone.status == PhoneWifiScanner.Status.SCANNING,
            onClick = model.phone::refresh,
        )
        val twoFour = store.networks().values.filter { it.isInRange && it.latest.band == Band.TWO_FOUR }.map { it.latest }
        if (twoFour.isNotEmpty()) {
            StatusChip(
                icon = Icons.Rounded.WaterfallChart,
                title = tr("Channels"),
                detail = tr("Quietest: %d", ChannelAdvisor.recommendation(twoFour)),
                tint = Spectra.colors.excellent,
                onClick = nav::channels,
            )
        }
    }
}

internal fun phoneRadioNote(model: SpectraModel): String? = when (model.phone.status) {
    PhoneWifiScanner.Status.THROTTLED -> tr("Android is limiting Wi-Fi scans. Showing the last results the system kept.")
    PhoneWifiScanner.Status.WIFI_OFF -> tr("Wi-Fi is off, so the phone radio is quiet.")
    PhoneWifiScanner.Status.PERMISSION -> tr("Nearby Wi-Fi permission is off, so network names stay hidden.")
    PhoneWifiScanner.Status.LOCATION_OFF -> tr("Location services must be on or this Android version returns an empty scan.")
    PhoneWifiScanner.Status.SCANNING, PhoneWifiScanner.Status.IDLE -> null
}

internal fun bandLabel(band: Band): String = when (band) {
    Band.TWO_FOUR -> tr("2.4 GHz")
    Band.FIVE -> tr("5 GHz")
    Band.SIX -> tr("6 GHz")
}

internal fun lanHost(model: SpectraModel, mac: MacAddress): LanDevice? =
    model.lan.devices.firstOrNull { it.macAddress == mac }

// Rows

@Composable
private fun securityIcon(reading: AccessPointReading): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> {
    val colors = Spectra.colors
    return when (reading.auth.strength) {
        AuthMode.Strength.NONE -> Icons.Rounded.LockOpen to colors.weak
        AuthMode.Strength.WEAK -> Icons.Rounded.GppBad to colors.danger
        AuthMode.Strength.FAIR -> Icons.Rounded.Lock to MaterialTheme.colorScheme.onSurfaceVariant
        AuthMode.Strength.STRONG -> Icons.Rounded.Lock to colors.success
    }
}

internal fun networkName(network: TrackedNetwork): String = network.revealedSsid ?: when (network.latest.displayName) {
    "Hidden network", "Name withheld" -> tr(network.latest.displayName)
    else -> network.latest.displayName
}

@Composable
fun NetworkRow(network: TrackedNetwork, onClick: () -> Unit, now: Long = System.currentTimeMillis()) {
    val reading = network.latest
    val hidden = reading.ssid == null && network.revealedSsid == null
    val (lock, lockTint) = securityIcon(reading)
    val subtitle = when {
        network.wasInLastScan -> buildList {
            add(tr("Ch %d", reading.channel))
            add(reading.auth.name)
            Capabilities.generation(reading.capabilities, reading.phyNote).takeIf { it != "—" }?.let(::add)
            if (network.clients.isNotEmpty()) add(tr("Clients: %d", network.clients.size))
            network.traffic?.let { add("↕ " + byteSize(it.bytes)) }
        }.joinToString(" · ")
        network.isInRange -> tr("Missed scans: %d", network.missedScans)
        else -> tr("Out of range · %s", ago(network.lastSeen, now))
    }
    WithCopyMenu(
        listOfNotNull(
            (network.revealedSsid ?: reading.ssid)?.let { CopyItem(tr("Copy name"), tr("Name"), it) },
            CopyItem(tr("Copy BSSID"), "BSSID", reading.bssid.text),
        ),
    ) { open ->
        ListRow(
            title = networkName(network),
            subtitle = subtitle,
            italicTitle = hidden,
            mutedTitle = hidden,
            dimmed = !network.wasInLastScan,
            leading = {
                GlyphTile(if (network.isInRange) signalColor(reading.rssi) else MaterialTheme.colorScheme.outline) {
                    WifiGlyph(reading.rssi, network.isInRange, size = 24.dp)
                }
            },
            badges = {
                Icon(lock, contentDescription = reading.auth.name, tint = lockTint, modifier = Modifier.size(15.dp))
                if (network.revealedSsid != null) {
                    Icon(Icons.Rounded.Visibility, contentDescription = tr("Revealed"), tint = Spectra.colors.revealed, modifier = Modifier.size(15.dp))
                }
                if (reading.band != Band.TWO_FOUR) Pill(bandLabel(reading.band), MaterialTheme.colorScheme.secondary)
            },
            trailing = { SignalTrailing(reading.rssi, network.isInRange, network.samples.map { it.rssi }) },
            onClick = onClick,
            onLongClick = open,
        )
    }
}

@Composable
fun DeauthRow(event: DeauthEvent) {
    val colors = Spectra.colors
    val (icon, tint, badge) = when (event.severity) {
        DeauthEvent.Severity.CRITICAL -> Triple(Icons.Rounded.GppBad, colors.danger, tr("ATTACK"))
        DeauthEvent.Severity.WARNING -> Triple(Icons.Rounded.GppMaybe, colors.warning, tr("SUSPICIOUS"))
        DeauthEvent.Severity.INFO -> Triple(Icons.Rounded.Shield, colors.fair, tr("INFO"))
    }
    WithCopyMenu(
        listOf(
            CopyItem(tr("Copy source MAC"), tr("Source MAC"), event.source.text),
            CopyItem(tr("Copy target MAC"), tr("Target MAC"), event.target.text),
            CopyItem(tr("Copy BSSID"), "BSSID", event.bssid.text),
        ),
    ) { open ->
        ListRow(
            title = if (event.isBroadcast) tr("Broadcast deauth") else tr("Targeted deauth"),
            subtitle = "${tr(event.reasonDescription)} → ${if (event.isBroadcast) tr("all devices") else event.target.text}",
            leading = { IconTile(icon, tint) },
            badges = { Pill(badge, tint, filled = true) },
            trailing = {
                Column(horizontalAlignment = Alignment.End) {
                    Text("×${event.count}", style = MaterialTheme.typography.titleMedium.merge(Tabular), color = tint)
                    Text(tr("Ch %d", event.channel), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            onLongClick = open,
        )
    }
}

@Composable
fun ProbeRow(device: ProbingDevice, host: LanDevice?, onClick: (() -> Unit)?) {
    val subtitle = buildList {
        add(tr("Ch %d", device.channel))
        add(device.ssids.ifEmpty { listOf(tr("broadcast")) }.joinToString(", "))
        if (host != null && host.displayName != device.displayName) add(tr("on LAN %s", host.displayName))
    }.joinToString(" · ")
    WithCopyMenu(
        listOfNotNull(
            CopyItem(tr("Copy MAC"), "MAC", device.mac.text),
            device.ssids.takeIf { it.isNotEmpty() }?.let { CopyItem(tr("Copy SSIDs"), "SSID", it.joinToString(", ")) },
        ),
    ) { open ->
        ListRow(
            title = device.displayName,
            subtitle = subtitle,
            leading = { IconTile(stationIcon(device.displayName, device.mac, false), Spectra.colors.weak) },
            badges = { if (device.mac.isLocallyAdministered) Pill(tr("Random"), Spectra.colors.random) },
            trailing = { SignalTrailing(device.rssi, true, emptyList()) },
            onClick = onClick,
            onLongClick = open,
        )
    }
}

@Composable
fun ClientRow(client: WifiClient, host: LanDevice?, onClick: (() -> Unit)?) {
    val subtitle = buildList {
        add(client.mac.text)
        if (host != null && host.displayName != client.displayName) add(tr("on LAN %s", host.displayName))
    }.joinToString(" · ")
    WithCopyMenu(listOf(CopyItem(tr("Copy MAC"), "MAC", client.mac.text))) { open ->
        ListRow(
            title = client.displayName,
            subtitle = subtitle,
            subtitleLines = 1,
            leading = {
                IconTile(
                    stationIcon(client.displayName, client.mac, client.isAssociated),
                    if (client.isAssociated) Spectra.colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            badges = { if (client.mac.isLocallyAdministered) Pill(tr("Random"), Spectra.colors.random) },
            trailing = { SignalTrailing(client.rssi, true, emptyList()) },
            onClick = onClick,
            onLongClick = open,
        )
    }
}

// Network card

@Composable
fun NetworkDetail(model: SpectraModel, nav: Navigator, id: MacAddress?, entryId: String?) {
    model.generation
    val entry = entryId?.let { model.history.entry(it) }
    val network = id?.let { if (entryId != null) entry?.network(it) else model.store.network(it) }
    if (network == null) {
        SpectraScreen(tr("Network"), onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.Wifi, tr("Network cleared"), tr("This network is no longer in the list."))
        }) {}
        return
    }
    val reading = network.latest
    val snapshot = entryId != null
    val quality = SignalQuality.of(reading.rssi)
    val qualityColor = Spectra.colors.of(quality)
    val now = if (snapshot) network.lastSeen else rememberNow()
    SpectraScreen(
        title = networkName(network),
        subtitle = if (snapshot) entry?.let { tr("Saved %s", dateTime(it.savedAt)) } else reading.bssid.text,
        onBack = nav::back,
    ) {
        freeCard("hero") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GlyphTile(if (network.isInRange) qualityColor else MaterialTheme.colorScheme.outline, size = 52.dp) {
                    WifiGlyph(reading.rssi, network.isInRange, size = 32.dp)
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            networkName(network),
                            style = MaterialTheme.typography.titleLarge,
                            fontStyle = if (reading.ssid == null && network.revealedSsid == null) FontStyle.Italic else FontStyle.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (network.revealedSsid != null) {
                            Icon(Icons.Rounded.Visibility, null, tint = Spectra.colors.revealed, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(bandLabel(reading.band), MaterialTheme.colorScheme.secondary)
                        Pill(if (network.source == RadioSource.SENSOR) tr("Sensor") else tr("Phone"), MaterialTheme.colorScheme.primary)
                        val (_, lockTint) = securityIcon(reading)
                        Pill(reading.auth.name, lockTint)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${reading.rssi}",
                    style = MaterialTheme.typography.displayMedium.merge(Tabular),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    tr("dBm"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (network.isInRange) tr(quality.label) else tr("Out of range"),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (network.isInRange) qualityColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Text(
                tr("avg %d · min %d · max %d", network.averageRssi, network.weakestRssi, network.strongestRssi),
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            SignalHistoryChart(network.samples.map { it.date to it.rssi }, qualityColor)
        }
        val notes = networkNotes(network)
        if (notes.isNotEmpty()) {
            card(header = tr("Notes"), key = "notes", rows = notes.map { (text, warning) -> { NoteRow(text, warning) } })
        }
        card(
            header = tr("Identity"),
            key = "identity",
            rows = buildList {
                add { InfoRow(tr("Name"), networkName(network), copyable = true) }
                if (network.revealedSsid != null) add { InfoRow(tr("Broadcast name"), tr("Hidden")) }
                add { InfoRow("BSSID", reading.bssid.text, mono = true, copyable = true) }
                add { InfoRow(tr("Address type"), macKind(reading.bssid)) }
                reading.bssid.vendor()?.let { add { InfoRow(tr("Maker"), it) } }
                reading.countryCode?.let { code ->
                    val country = Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }
                    add { InfoRow(tr("Country"), country) }
                }
                network.vendorInfo?.deviceName?.takeIf { it.isNotEmpty() }?.let { add { InfoRow(tr("Device name"), it) } }
                network.vendorInfo?.manufacturer?.takeIf { it.isNotEmpty() }?.let { add { InfoRow(tr("Manufacturer"), it) } }
            },
        )
        card(
            header = tr("Radio"),
            key = "radio",
            footer = if (network.source == RadioSource.PHONE) {
                tr("This sighting is from the phone. Android does not report associated clients or frames the network is not addressed to.")
            } else {
                null
            },
            rows = buildList {
                add { InfoRow(tr("Channel"), "${reading.channel}") }
                add { InfoRow(tr("Frequency"), tr("%d MHz", reading.frequencyMHz)) }
                add { InfoRow(tr("Band"), bandLabel(reading.band)) }
                add { InfoRow(tr("Width"), widthText(reading)) }
                add { InfoRow(tr("Standards"), Capabilities.standards(reading.capabilities)) }
                add { InfoRow(tr("Generation"), Capabilities.generation(reading.capabilities, reading.phyNote)) }
                if (reading.capabilities and Capabilities.FTM != 0) add { InfoRow(tr("Fine Timing Measurement"), tr("Supported")) }
                if (reading.capabilities and Capabilities.LONG_RANGE != 0) add { InfoRow(tr("Long range"), "802.11 LR") }
            },
        )
        card(
            header = tr("Security"),
            key = "security",
            rows = listOf(
                { InfoRow(tr("Authentication"), reading.auth.name) },
                { InfoRow(tr("Pairwise cipher"), tr(reading.pairwiseCipher.name)) },
                { InfoRow(tr("Group cipher"), tr(reading.groupCipher.name)) },
                { InfoRow("WPS", if (reading.capabilities and Capabilities.WPS != 0) tr("On") else tr("Off")) },
            ),
        )
        cardSection(
            network.clients,
            header = tr("Clients: %d", network.clients.size),
            footer = tr("Devices connected to this network, found through promiscuous-mode frame capture on the sensor."),
            key = { "client-" + it.mac.text },
            sectionKey = "clients",
            dividerInset = 70.dp,
        ) { client -> ClientRow(client, lanHost(model, client.mac), onClick = { nav.client(client.mac, entryId) }) }
        network.traffic?.let { traffic ->
            card(
                header = tr("Traffic"),
                key = "traffic",
                footer = tr("Data frames seen during the promiscuous-mode capture window. A high bitrate may indicate streaming (cameras, microphones)."),
                rows = buildList {
                    add { InfoRow(tr("Volume"), byteSize(traffic.bytes)) }
                    add { InfoRow(tr("Packets"), "${traffic.packets}") }
                    if (!snapshot) model.store.lastScanDurationMs?.let { bitrate(traffic.bytes, it) }?.let { add { InfoRow(tr("Est. bitrate"), it) } }
                },
            )
        }
        card(
            header = tr("Observed"),
            key = "observed",
            rows = buildList {
                add { InfoRow(tr("First seen"), clockTime(network.firstSeen)) }
                add { InfoRow(tr("Last seen"), ago(network.lastSeen, if (snapshot) entry?.savedAt ?: now else now)) }
                add { InfoRow(tr("Readings"), "${network.samples.size}") }
                if (network.missedScans > 0) add { InfoRow(tr("Missed scans"), "${network.missedScans}") }
            },
        )
    }
}

private fun networkNotes(network: TrackedNetwork): List<Pair<String, Boolean>> {
    val reading = network.latest
    val notes = mutableListOf<Pair<String, Boolean>>()
    when (reading.auth.strength) {
        AuthMode.Strength.NONE -> notes += tr("Open network: traffic is not encrypted, anyone nearby can read it.") to true
        AuthMode.Strength.WEAK -> notes += tr("%s is obsolete and can be broken in minutes.", reading.auth.name) to true
        else -> Unit
    }
    if (reading.capabilities and Capabilities.WPS != 0) {
        notes += tr("WPS is on. Its PIN method is a known weak point; turn it off if this is your router.") to true
    }
    if (reading.groupCipher == Cipher.TKIP || reading.pairwiseCipher == Cipher.TKIP) {
        notes += tr("TKIP is enabled for old devices and weakens the whole network.") to true
    }
    if (reading.bssid.isLocallyAdministered) {
        notes += tr("Randomised address: usually a phone hotspot, a guest network or a mesh node.") to false
    }
    if (network.revealedSsid != null) {
        notes += tr("Hidden SSID revealed from a probe response or association frame — the AP hides its name from beacons.") to false
    }
    return notes
}

private fun widthText(reading: AccessPointReading): String = when (reading.secondaryChannel) {
    SecondaryChannel.ABOVE -> tr("%d MHz (secondary above)", reading.channelWidthMHz)
    SecondaryChannel.BELOW -> tr("%d MHz (secondary below)", reading.channelWidthMHz)
    else -> tr("%d MHz", reading.channelWidthMHz)
}

// Client card

@Composable
fun ClientDetail(model: SpectraModel, nav: Navigator, mac: MacAddress?, entryId: String?) {
    model.generation
    val entry = entryId?.let { model.history.entry(it) }
    val networks = if (entryId != null) entry?.networks.orEmpty() else model.store.networks().values.toList()
    val probe = mac?.let { if (entryId != null) entry?.probe(it) else model.store.probingDevices.firstOrNull { p -> p.mac == it } }
    val client = mac?.let { target ->
        networks.firstNotNullOfOrNull { network -> network.clients.firstOrNull { it.mac == target } }
            ?: (if (entryId == null) model.store.clients.firstOrNull { it.mac == target } else null)
            ?: probe?.let { WifiClient(it.mac, null, it.rssi, false) }
    }
    if (client == null) {
        SpectraScreen(tr("Device"), onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.CellTower, tr("Device cleared"), tr("It has not been heard since this list was cleared."))
        }) {}
        return
    }
    val host = lanHost(model, client.mac)
    val ap = client.apBssid?.let { bssid -> networks.firstOrNull { it.id == bssid } }
    SpectraScreen(client.displayName, subtitle = client.mac.text, onBack = nav::back) {
        freeCard("hero") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconTile(
                    stationIcon(client.displayName, client.mac, client.isAssociated),
                    if (client.isAssociated) Spectra.colors.success else Spectra.colors.weak,
                    size = 52.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(client.displayName, style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Pill(if (client.isAssociated) tr("Connected") else tr("Probing"), if (client.isAssociated) Spectra.colors.success else Spectra.colors.weak)
                        if (client.mac.isLocallyAdministered) Pill(tr("Random"), Spectra.colors.random)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${client.rssi}", style = MaterialTheme.typography.headlineMedium.merge(Tabular), color = signalColor(client.rssi))
                    Text(tr("dBm"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        card(
            key = "client",
            rows = buildList {
                add { InfoRow(tr("MAC address"), client.mac.text, mono = true, copyable = true) }
                add { InfoRow(tr("Signal"), tr("%d dBm", client.rssi)) }
                if (client.mac.isLocallyAdministered) {
                    add { InfoRow(tr("Address type"), tr("Randomised")) }
                } else {
                    client.manufacturer?.let { add { InfoRow(tr("Maker"), it) } }
                }
                add { InfoRow(tr("Associated"), if (client.isAssociated) tr("Yes") else tr("Probe only")) }
                if (ap != null) {
                    add { InfoRow(tr("Network"), networkName(ap), valueColor = MaterialTheme.colorScheme.primary, onClick = { nav.network(ap.id, entryId) }) }
                }
            },
        )
        if (probe != null) {
            card(
                header = tr("Probes"),
                key = "probes",
                rows = listOf(
                    { InfoRow(tr("Channel"), "${probe.channel}") },
                    { InfoRow(tr("Looking for"), probe.ssids.ifEmpty { listOf(tr("Broadcast")) }.joinToString(", ")) },
                ),
            )
        }
        if (host != null) {
            card(header = tr("On the LAN"), key = "lan", rows = listOf {
                ListRow(
                    title = host.displayName,
                    subtitle = host.ipAddress,
                    leading = { IconTile(host.icon(), MaterialTheme.colorScheme.primary) },
                    onClick = { nav.host(host.ipAddress) },
                )
            })
        }
    }
}

// Channels

@Composable
fun ChannelsScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val inRange = model.store.networks().values.filter { it.isInRange }
    val twoFour = inRange.filter { it.latest.band == Band.TWO_FOUR }.map { it.latest }.sortedByDescending { it.rssi }
    val five = inRange.filter { it.latest.band == Band.FIVE }.map { it.latest }.sortedByDescending { it.rssi }
    val six = inRange.filter { it.latest.band == Band.SIX }.map { it.latest }.sortedByDescending { it.rssi }
    SpectraScreen(
        tr("Channels"),
        onBack = nav::back,
        overlay = {
            if (inRange.isEmpty()) {
                EmptyState(Icons.Rounded.WaterfallChart, tr("Nothing on the air yet"), tr("Channel use is drawn from the networks in range."))
            }
        },
    ) {
        if (twoFour.isNotEmpty()) {
            freeCard(
                "spectrum-24",
                header = tr("2.4 GHz"),
                footer = tr("Each hump is one network: its width is the channels it covers, its height how loud it is here."),
                padding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
            ) { ChannelSpectrum(twoFour) }
            val best = ChannelAdvisor.recommendation(twoFour)
            freeCard("advice") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Spectra.colors.spectrum, alpha = 0.22f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$best", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(tr("Quietest channel here"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            tr("Of the non-overlapping channels 1, 6 and 11, this one meets the least interference. Set it on your router if you control one."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            card(
                header = tr("Networks per channel"),
                key = "per-channel",
                rows = (1..13).map { channel ->
                    {
                        val level = ChannelAdvisor.level(ChannelAdvisor.congestion(channel, twoFour))
                        val count = twoFour.count { it.channel == channel }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "$channel",
                                style = MaterialTheme.typography.titleSmall.merge(Tabular),
                                modifier = Modifier.width(24.dp),
                                color = if (channel == best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            LevelBar(level, Modifier.weight(1f))
                            Text(
                                if (count == 0) "—" else "$count",
                                style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp),
                            )
                        }
                    }
                },
            )
        }
        if (five.isNotEmpty()) {
            freeCard(
                "spectrum-5",
                header = tr("5 GHz"),
                footer = tr("Heard by the phone; the Cardputer is 2.4 GHz only."),
                padding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
            ) {
                val span = wideDomain(five)
                ChannelSpectrum(five, ticks = listOf(36, 52, 100, 116, 132, 149, 165).filter { it.toDouble() in span }, domain = span)
            }
        }
        if (six.isNotEmpty()) {
            freeCard(
                "spectrum-6",
                header = tr("6 GHz"),
                padding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
            ) {
                val span = wideDomain(six)
                ChannelSpectrum(six, ticks = (1..233 step 32).filter { it.toDouble() in span }, domain = span)
            }
        }
        cardSection(
            five + six,
            header = tr("5 and 6 GHz from the phone"),
            key = { "wide-" + it.bssid.text },
            sectionKey = "wide",
            dividerInset = 70.dp,
        ) { reading ->
            val network = model.store.network(reading.bssid)
            ListRow(
                title = network?.let(::networkName) ?: reading.displayName,
                subtitle = "${bandLabel(reading.band)} · ${tr("Ch %d", reading.channel)} · ${tr("%d MHz", reading.channelWidthMHz)}",
                leading = { GlyphTile(signalColor(reading.rssi)) { WifiGlyph(reading.rssi, size = 24.dp) } },
                trailing = { SignalTrailing(reading.rssi, true, network?.samples?.map { it.rssi }.orEmpty()) },
                onClick = { nav.network(reading.bssid) },
            )
        }
    }
}

private fun wideDomain(readings: List<AccessPointReading>): ClosedFloatingPointRange<Double> {
    val low = readings.minOf { it.occupiedChannels.start }
    val high = readings.maxOf { it.occupiedChannels.endInclusive }
    return (low - 2)..(high + 2)
}
