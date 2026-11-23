package cattivole.spectra.ui

import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Password
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WaterfallChart
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import cattivole.spectra.platform.WifiJoiner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
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
import cattivole.spectra.core.Threats
import cattivole.spectra.core.WatchList
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.ProbeInsight
import cattivole.spectra.core.ProbingDevice
import cattivole.spectra.core.hasStoredPassword
import cattivole.spectra.core.passesWifiFilter
import cattivole.spectra.core.RadioSource
import cattivole.spectra.core.SecondaryChannel
import cattivole.spectra.core.SignalQuality
import cattivole.spectra.core.TrackedNetwork
import cattivole.spectra.core.WifiClient
import cattivole.spectra.platform.Permissions
import cattivole.spectra.platform.PhoneWifiScanner
import cattivole.spectra.platform.SensorLink
import java.util.Locale

internal enum class WifiSort { SIGNAL, NAME, CHANNEL }

@Composable
internal fun WifiSortRow(sort: WifiSort, onSort: (WifiSort) -> Unit) {
    ChipRow(contentPadding = SortChipPadding) {
        ToggleChip(tr("Signal"), sort == WifiSort.SIGNAL, { onSort(WifiSort.SIGNAL) })
        ToggleChip(tr("Name"), sort == WifiSort.NAME, { onSort(WifiSort.NAME) })
        ToggleChip(tr("Channel"), sort == WifiSort.CHANNEL, { onSort(WifiSort.CHANNEL) })
    }
}

@Composable
internal fun WifiFilterRow(
    openOnly: Boolean,
    onOpenOnly: () -> Unit,
    showKnown: Boolean,
    knownOnly: Boolean,
    onKnownOnly: () -> Unit,
    bands: Set<Band>,
    band: Band?,
    onBand: (Band?) -> Unit,
    showOut: Boolean,
    onShowOut: () -> Unit,
) {
    ChipRow(contentPadding = FilterChipPadding) {
        ToggleChip(tr("Open"), openOnly, onOpenOnly)
        if (showKnown) {
            ToggleChip(tr("Known"), knownOnly, onKnownOnly)
        }
        if (bands.size > 1) {
            for (shown in Band.entries.filter { it in bands }) {
                ToggleChip(bandLabel(shown), band == shown, { onBand(if (band == shown) null else shown) })
            }
        }
        ToggleChip(tr("Out of range"), showOut, onShowOut)
    }
}

internal fun List<TrackedNetwork>.sortedForWifi(sort: WifiSort): List<TrackedNetwork> =
    sortedWith { a, b ->
        if (a.isInRange != b.isInRange) return@sortedWith if (a.isInRange) -1 else 1
        when (sort) {
            WifiSort.NAME -> a.title.compareTo(b.title, ignoreCase = true)
            WifiSort.CHANNEL -> compareValuesBy(a, b, { it.latest.band }, { it.latest.channel }, { -it.latest.rssi })
            WifiSort.SIGNAL -> b.latest.rssi.compareTo(a.latest.rssi)
        }
    }

@Composable
fun NetworksScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val store = model.store
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(WifiSort.SIGNAL) }
    var showOut by rememberSaveable { mutableStateOf(false) }
    var openOnly by rememberSaveable { mutableStateOf(false) }
    var knownOnly by rememberSaveable { mutableStateOf(false) }
    var band by rememberSaveable { mutableStateOf<Band?>(null) }
    var expandedThreats by remember { mutableStateOf(emptySet<String>()) }
    val knownSsids = model.link.knownSsids()
    val all = store.networks().values
    val showKnown = all.any { it.hasStoredPassword(knownSsids) }
    val filterKnown = knownOnly && showKnown
    val needle = query.trim()
    val networks = all.filter { network ->
        network.passesWifiFilter(showOut, openOnly, band, needle, filterKnown, knownSsids)
    }.sortedForWifi(sort)
    val probes = store.probingDevices.filter { it.passesWifiFilter(openOnly, band, needle, filterKnown) }
    val bands = all.map { it.latest.band }.toSet()
    val threats = Threats.assess(all.toList(), store.deauthEvents)
    val watching = WatchList.presentNetworks(model.watchedIds, all)
    val refreshing = store.isScanning || model.phone.status == PhoneWifiScanner.Status.SCANNING
    SpectraScreen(
        title = "Wi‑Fi",
        actions = {
            var menu by remember { mutableStateOf(false) }
            HistoryButton(nav, cattivole.spectra.core.HistoryArea.WIFI)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = tr("More")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Decode pcap…")) },
                        leadingIcon = { Icon(Icons.Rounded.Description, null) },
                        onClick = {
                            menu = false
                            nav.pcap()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Saved networks…")) },
                        leadingIcon = { Icon(Icons.Rounded.Password, null) },
                        onClick = {
                            menu = false
                            nav.savedNetworks()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Sensor…")) },
                        leadingIcon = { Icon(Icons.Rounded.SettingsInputAntenna, null) },
                        onClick = {
                            menu = false
                            nav.sensorTab()
                        },
                    )
                    if (all.isNotEmpty()) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(tr("Clear list")) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                            onClick = {
                                menu = false
                                store.clear()
                            },
                        )
                    }
                }
            }
        },
        refreshing = refreshing,
        onRefresh = {
            model.link.scanNow()
            model.phone.refresh()
        },
        searchQuery = query,
        onSearchChange = { query = it },
        searchPlaceholder = tr("Name, BSSID or maker"),
        overlay = {
            when {
                all.isEmpty() && model.phone.status == PhoneWifiScanner.Status.SCANNING -> EmptyState(
                    icon = Icons.Rounded.Wifi,
                    title = tr("Listening"),
                    body = tr("Networks appear here after the first scan."),
                    busy = true,
                )
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
                networks.isEmpty() && probes.isEmpty() && needle.isNotEmpty() -> EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    title = tr("No results"),
                    body = tr("Nothing matches “%s”.", needle),
                )
                networks.isEmpty() && probes.isEmpty() && all.isNotEmpty() -> EmptyState(
                    icon = Icons.Rounded.WifiFind,
                    title = tr("No results"),
                    body = tr("Nothing in this filter is nearby."),
                )
            }
        },
    ) {
        item(key = "status") { WifiStatusChips(model, nav) }
        item(key = "sort") { WifiSortRow(sort) { sort = it } }
        item(key = "filters") {
            WifiFilterRow(
                openOnly = openOnly,
                onOpenOnly = { openOnly = !openOnly },
                showKnown = showKnown,
                knownOnly = knownOnly,
                onKnownOnly = { knownOnly = !knownOnly },
                bands = bands,
                band = band,
                onBand = { band = it },
                showOut = showOut,
                onShowOut = { showOut = !showOut },
            )
        }
        phoneRadioNote(model)?.takeIf { all.isNotEmpty() }?.let { note ->
            card(key = "phone-note", rows = listOf { NoteRow(note, warning = model.phone.status != PhoneWifiScanner.Status.SCANNING) })
        }
        cardSection(
            threats,
            header = tr("Threats"),
            key = { it.id },
            sectionKey = "threats",
            headerTint = { Spectra.colors.warning },
            headerIcon = Icons.Rounded.Warning,
            dividerInset = 70.dp,
        ) { finding ->
            val open = finding.id in expandedThreats
            Column {
                ThreatRow(finding, expanded = open, onClick = {
                    expandedThreats = if (open) expandedThreats - finding.id else expandedThreats + finding.id
                })
                if (open) {
                    Text(
                        threatExplanation(finding.kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 70.dp, end = 16.dp, bottom = 8.dp),
                    )
                    finding.deauth?.let { DeauthRow(it) }
                    for (id in finding.networks) {
                        val network = store.network(id) ?: continue
                        NetworkRow(
                            network,
                            onClick = { nav.network(network.id) },
                            watched = model.isWatched(network.id.text),
                            known = network.hasStoredPassword(knownSsids),
                            current = model.wifiJoiner.isCurrent(network),
                        )
                    }
                }
            }
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
            watching,
            header = tr("Watching"),
            key = { "watch-" + it.id.text },
            sectionKey = "watching",
            headerTint = { Spectra.colors.warning },
            headerIcon = Icons.Rounded.Star,
            dividerInset = 70.dp,
        ) { network ->
            NetworkRow(
                network,
                onClick = { nav.network(network.id) },
                watched = true,
                known = network.hasStoredPassword(knownSsids),
                current = model.wifiJoiner.isCurrent(network),
            )
        }
        cardSection(
            networks,
            header = tr("In the latest pass: %d", all.count { it.wasInLastScan }),
            key = { it.id.text },
            sectionKey = "networks",
            dividerInset = 70.dp,
        ) { network ->
            NetworkRow(
                network,
                onClick = { nav.network(network.id) },
                watched = model.isWatched(network.id.text),
                known = network.hasStoredPassword(knownSsids),
                current = model.wifiJoiner.isCurrent(network),
            )
        }
        cardSection(
            probes,
            header = tr("Probing: %d", probes.size),
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
            detail = sensorDetail(model, showCharging = false),
            tint = if (link.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            busy = store.isScanning || link.state is cattivole.spectra.platform.SensorLink.State.Searching ||
                link.state is cattivole.spectra.platform.SensorLink.State.Connecting,
            indicator = if (link.isCharging == true && link.batteryLevel != null) Spectra.colors.success else null,
            onClick = nav::sensorTab,
        )
        val phoneNetworks = store.phoneSightings().filter { it.isInRange }
        val context = LocalContext.current
        val requestPermissions = LocalRequestPermissions.current
        // With a sensor connected its chip says what is heard; the phone's own radio chip is only noise then.
        if (!link.isConnected) StatusChip(
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
            onClick = {
                when {
                    !Permissions.canScanWifi(context) -> requestPermissions()
                    model.phone.status == PhoneWifiScanner.Status.LOCATION_OFF -> context.startActivity(
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                    )
                    else -> model.phone.refresh()
                }
            },
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
    PhoneWifiScanner.Status.PERMISSION -> tr("Location and nearby Wi-Fi must be allowed, or the phone radio stays quiet.")
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

/** Third line of a network cell: a person for clients and an up-down mark for traffic. */
@Composable
private fun ClientTrafficLine(clients: Int, traffic: String?) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val text = buildAnnotatedString {
        if (clients > 0) {
            appendInlineContent("clients", tr("Clients: %d", clients))
            append(" $clients")
        }
        if (traffic != null) {
            if (clients > 0) append("  ")
            appendInlineContent("traffic", tr("Traffic"))
            append(" $traffic")
        }
    }
    val mark = Placeholder(12.sp, 12.sp, PlaceholderVerticalAlign.TextCenter)
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.merge(Tabular),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        inlineContent = mapOf(
            "clients" to InlineTextContent(mark) {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = Spectra.colors.info, modifier = Modifier.fillMaxSize())
            },
            "traffic" to InlineTextContent(mark) {
                Icon(Icons.Rounded.SwapVert, contentDescription = null, tint = Spectra.colors.revealed, modifier = Modifier.fillMaxSize())
            },
        ),
    )
}

internal fun networkName(network: TrackedNetwork): String = network.revealedSsid ?: when (network.latest.displayName) {
    "Hidden network", "Name withheld" -> tr(network.latest.displayName)
    else -> network.latest.displayName
}

@Composable
fun NetworkRow(
    network: TrackedNetwork,
    onClick: () -> Unit,
    now: Long = System.currentTimeMillis(),
    watched: Boolean = false,
    known: Boolean = false,
    current: Boolean = false,
) {
    val reading = network.latest
    val hidden = reading.ssid == null && network.revealedSsid == null
    val (lock, lockTint) = securityIcon(reading)
    val facts = when {
        network.wasInLastScan -> buildList {
            add(tr("Ch %d", reading.channel))
            add(reading.auth.name)
            Capabilities.generation(reading.capabilities, reading.phyNote).takeIf { it != "—" }?.let(::add)
        }.joinToString(" · ")
        network.isInRange -> tr("Missed scans: %d", network.missedScans)
        else -> tr("Out of range · %s", ago(network.lastSeen, now))
    }
    val clientCount = network.clients.size
    val traffic = network.traffic?.let { byteSize(it.bytes) }
    WithCopyMenu(
        listOfNotNull(
            (network.revealedSsid ?: reading.ssid)?.let { CopyItem(tr("Copy name"), tr("Name"), it) },
            CopyItem(tr("Copy BSSID"), "BSSID", reading.bssid.text),
        ),
    ) { open ->
        ListRow(
            title = networkName(network),
            subtitle = facts,
            subtitleLines = 1,
            detailContent = if (clientCount > 0 || traffic != null) {
                { ClientTrafficLine(clientCount, traffic) }
            } else {
                null
            },
            italicTitle = hidden,
            mutedTitle = hidden,
            dimmed = !network.wasInLastScan,
            leading = {
                GlyphTile(if (network.isInRange) signalColor(reading.rssi) else MaterialTheme.colorScheme.outline) {
                    WifiGlyph(reading.rssi, network.isInRange, size = 24.dp)
                }
            },
            badges = {
                if (current) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = tr("Connected"), tint = Spectra.colors.success, modifier = Modifier.size(16.dp))
                }
                Icon(lock, contentDescription = reading.auth.name, tint = lockTint, modifier = Modifier.size(15.dp))
                if (known) {
                    Icon(Icons.Rounded.Key, contentDescription = tr("Password saved"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                }
                if (network.revealedSsid != null) {
                    Icon(Icons.Rounded.Visibility, contentDescription = tr("Revealed"), tint = Spectra.colors.revealed, modifier = Modifier.size(15.dp))
                }
                if (watched) {
                    Icon(Icons.Rounded.Star, contentDescription = tr("Watching"), tint = Spectra.colors.warning, modifier = Modifier.size(15.dp))
                }
                if (reading.band != Band.TWO_FOUR) Pill(bandLabel(reading.band), MaterialTheme.colorScheme.secondary)
            },
            trailing = { SignalTrailing(reading.rssi, network.isInRange, network.samples.map { it.rssi }) },
            onClick = onClick,
            onLongClick = open,
        )
    }
}

internal fun threatExplanation(kind: Threats.Kind): String = when (kind) {
    Threats.Kind.DEAUTH_ATTACK ->
        tr("Someone is sending frames that throw devices off the network. It is how handshakes are captured for password cracking and how devices are pushed toward a fake access point. The frames are listed below.")
    Threats.Kind.EVIL_TWIN ->
        tr("Copies of one network that differ in security or maker can be an impostor waiting for devices that join by name. Compare the access points below and avoid the one you do not recognise.")
    Threats.Kind.STREAMING ->
        tr("A network that moves data the whole time may be carrying a camera or microphone feed. Open it below to see which devices are connected.")
}

@Composable
fun ThreatRow(finding: Threats.Finding, expanded: Boolean = false, onClick: (() -> Unit)? = null) {
    val tint = if (finding.severity == Threats.Severity.CRITICAL) Spectra.colors.danger else Spectra.colors.warning
    val icon = when (finding.kind) {
        Threats.Kind.DEAUTH_ATTACK -> Icons.Rounded.GppBad
        Threats.Kind.EVIL_TWIN -> Icons.Rounded.Groups
        Threats.Kind.STREAMING -> Icons.Rounded.Videocam
    }
    val title = when (finding.kind) {
        Threats.Kind.DEAUTH_ATTACK -> if (finding.broadcast) tr("Deauth attack") else tr("Deauth frames")
        Threats.Kind.EVIL_TWIN -> tr("Possible evil twin")
        Threats.Kind.STREAMING -> tr("Steady traffic")
    }
    val detail = when (finding.kind) {
        Threats.Kind.DEAUTH_ATTACK ->
            tr("%d frames on channel %d — devices are being forced off.", finding.frames, finding.channel)
        Threats.Kind.EVIL_TWIN ->
            if (finding.differByAuth) {
                tr("“%s” is on %d access points with different security.", finding.ssid ?: "", finding.apCount)
            } else {
                tr("“%s” is on %d access points with different makers.", finding.ssid ?: "", finding.apCount)
            }
        Threats.Kind.STREAMING ->
            tr("%s is moving data continuously — a camera or microphone may be streaming.", finding.name ?: "")
    }
    ListRow(
        title = title,
        subtitle = detail,
        subtitleLines = if (expanded) 4 else 2,
        leading = { IconTile(icon, tint) },
        trailing = if (onClick != null) {
            {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) tr("Hides the details") else tr("Shows the details"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
        onClick = onClick,
    )
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
    // A capture belongs to this screen: stop it when the user leaves.
    DisposableEffect(Unit) { onDispose { if (!snapshot) model.link.stopRawCapture() } }
    val quality = SignalQuality.of(reading.rssi)
    val qualityColor = Spectra.colors.of(quality)
    val now = if (snapshot) network.lastSeen else rememberNow()
    val joinSsid = network.revealedSsid ?: reading.ssid
    var pendingJoin by remember { mutableStateOf<String?>(null) } // ssid awaiting a password
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    val join = rememberWifiJoin(model)
    val context = LocalContext.current
    pendingJoin?.let { ssid ->
        AlertDialog(
            onDismissRequest = { pendingJoin = null },
            title = { Text(tr("Join “%s”", ssid)) },
            text = {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(tr("Password")) },
                    singleLine = true,
                    visualTransformation = if (showPassphrase) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassphrase = !showPassphrase }) {
                            Icon(if (showPassphrase) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null)
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    join(ssid, passphrase, reading.auth.raw)
                    pendingJoin = null
                }, enabled = passphrase.isNotEmpty()) { Text(tr("Connect")) }
            },
            dismissButton = { TextButton(onClick = { pendingJoin = null }) { Text(tr("Cancel")) } },
        )
    }
    SpectraScreen(
        title = networkName(network),
        subtitle = if (snapshot) entry?.let { tr("Saved %s", dateTime(it.savedAt)) } else reading.bssid.text,
        onBack = nav::back,
        actions = {
            if (!snapshot) {
                AskAssistantButton(model, nav, subject = { cattivole.spectra.assistant.CardSubject.Wifi(reading.bssid.text) })
                val watched = model.isWatched(reading.bssid.text)
                IconButton(onClick = { model.toggleWatch(reading.bssid.text) }) {
                    Icon(
                        if (watched) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = if (watched) tr("Stop watching") else tr("Watch network"),
                        tint = if (watched) Spectra.colors.warning else LocalContentColor.current,
                    )
                }
            }
        },
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
                (network.revealedSsid ?: reading.ssid)?.let { model.link.knownPassword(it) }?.let { password ->
                    add { InfoRow(tr("Password"), password, mono = true, copyable = true) }
                }
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
        if (!snapshot && model.link.isConnected) {
            captureCard(model.link, reading.channel, network, onDecode = { nav.decodeCapture(captureLabel(network)) })
        }
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
        joinSsid?.let { ssid ->
            val savedPassword = model.link.knownPassword(ssid)
            connectionCard(
                ssid = ssid,
                method = reading.auth.joinMethod,
                joiner = model.wifiJoiner,
                savedPassword = savedPassword,
                onConnectOpen = { join(ssid, null, reading.auth.raw) },
                onConnectSaved = { join(ssid, savedPassword, reading.auth.raw) },
                onNeedPassword = {
                    passphrase = savedPassword.orEmpty()
                    pendingJoin = ssid
                },
                onOpenPanel = { openWifiPanel(context) },
            )
        }
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
                    bitrate(traffic.bytes, cattivole.spectra.core.TrafficSnapshot.WINDOW_MS)?.let { add { InfoRow(tr("Est. bitrate"), it) } }
                },
            )
        }
        card(
            header = tr("Observed"),
            key = "observed",
            rows = buildList {
                val recorded = model.history.earliestNetworkSeen(network.id)
                val first = if (recorded != null && recorded < network.firstSeen) recorded else network.firstSeen
                add { InfoRow(tr("First seen"), dateTime(first)) }
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

// Joining the phone itself to this network (counterpart of the iOS WiFiJoiner UI).

private fun LazyListScope.connectionCard(
    ssid: String,
    method: AuthMode.JoinMethod,
    joiner: WifiJoiner,
    savedPassword: String?,
    onConnectOpen: () -> Unit,
    onConnectSaved: () -> Unit,
    onNeedPassword: () -> Unit,
    onOpenPanel: () -> Unit,
) {
    val status = joiner.status
    val connectRows: List<@Composable () -> Unit> = when (method) {
        AuthMode.JoinMethod.OPEN -> listOf({ ActionRow(tr("Connect to this network"), Icons.Rounded.Wifi, onClick = onConnectOpen) })
        AuthMode.JoinMethod.PSK, AuthMode.JoinMethod.WEP -> listOfNotNull(
            if (savedPassword != null) ({ ActionRow(tr("Connect with the saved password"), Icons.Rounded.Wifi, onClick = onConnectSaved) }) else null,
            { ActionRow(if (savedPassword != null) tr("Use another password") else tr("Enter password & connect"), Icons.Rounded.Password, onClick = onNeedPassword) },
        )
        AuthMode.JoinMethod.UNSUPPORTED -> emptyList()
    }
    val rows = buildList<@Composable () -> Unit> {
        when {
            // What the phone is on now is the truth, whatever the last join said.
            joiner.isOn(ssid) -> {
                add { StatusRow(tr("Connected")) }
                if (status is WifiJoiner.Status.Suggested && status.ssid == ssid) {
                    add { ActionRow(tr("Forget this network"), Icons.Rounded.DeleteSweep, onClick = { joiner.forget(ssid) }, tint = Spectra.colors.danger) }
                }
            }
            status is WifiJoiner.Status.Joining && status.ssid == ssid -> add { ConnectingRow() }
            joiner.isFor(ssid) && status is WifiJoiner.Status.Saved -> {
                add { NoteRow(tr("Saved in Android. If the phone stays on another network, pick “%s” in the Wi‑Fi list.", ssid), warning = false) }
                add { ActionRow(tr("Open the Wi‑Fi list"), Icons.Rounded.Wifi, onClick = onOpenPanel) }
            }
            joiner.isFor(ssid) && status is WifiJoiner.Status.Joined -> {
                add { StatusRow(tr("Connected")) }
            }
            joiner.isFor(ssid) && status is WifiJoiner.Status.Suggested -> {
                add { NoteRow(tr("Added. Android joins “%s” when it is in range; it may ask you to approve Spectra once.", ssid), warning = false) }
                add { ActionRow(tr("Forget this network"), Icons.Rounded.DeleteSweep, onClick = { joiner.forget(ssid) }, tint = Spectra.colors.danger) }
            }
            joiner.isFor(ssid) && status is WifiJoiner.Status.Failed -> {
                add { NoteRow(reasonText(status.reason), warning = true) }
                addAll(connectRows)
            }
            method != AuthMode.JoinMethod.UNSUPPORTED -> addAll(connectRows)
            else -> add { NoteRow(tr("Enterprise and other 802.1X networks have to be joined from Settings."), warning = false) }
        }
    }
    card(
        header = tr("Connection"),
        key = "connection",
        footer = when (method) {
            AuthMode.JoinMethod.OPEN -> tr("This network is open. Android will ask you to confirm before joining.")
            AuthMode.JoinMethod.PSK -> tr("Android asks to save the network, then joins it. Once the phone is on it, Spectra keeps the password in Saved networks and hands it to the Cardputer.")
            AuthMode.JoinMethod.UNSUPPORTED -> null
            else -> tr("Once the phone joins, Spectra keeps the password and hands it to the Cardputer too.")
        },
        rows = rows,
    )
}

@Composable
private fun ConnectingRow() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(tr("Connecting…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusRow(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Wifi, contentDescription = null, tint = Spectra.colors.fair, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

// Capture raw frames on this network's channel into a .pcap (counterpart of
// the iOS NetworkDetailView capture section).

private fun LazyListScope.captureCard(
    link: SensorLink,
    channel: Int,
    network: TrackedNetwork,
    onDecode: () -> Unit,
) {
    // The sensor keeps one capture; another network's screen starts fresh.
    val mine = link.rawCaptureNetwork == network.id
    val start = { link.startRawCapture(channel, network = network.id) }
    val support = link.captureSupport(channel)
    val rows = buildList<@Composable () -> Unit> {
        when {
            link.rawCapturing && mine -> {
                add { CaptureProgressRow(channel, link.rawFrameCount, link.rawByteCount) }
                add { ActionRow(tr("Stop capture"), Icons.Rounded.Stop, onClick = { link.stopRawCapture() }, tint = Spectra.colors.danger) }
            }
            mine && link.rawFrameCount > 0 -> {
                add { InfoRow(tr("Captured"), tr("%d frames · %s", link.rawFrameCount, byteSize(link.rawByteCount.toLong()))) }
                add { ActionRow(tr("Decode"), Icons.Rounded.Description, onClick = onDecode) }
                add { ActionRow(tr("Capture again"), Icons.Rounded.Refresh, onClick = start) }
            }
            else -> add {
                ActionRow(
                    tr("Capture traffic → save .pcap"),
                    Icons.Rounded.SettingsInputAntenna,
                    onClick = start,
                    enabled = support == SensorLink.CaptureSupport.Ok,
                )
            }
        }
    }
    val footer = buildString {
        append(
            when (support) {
                SensorLink.CaptureSupport.Ok -> tr(
                    "Pins the sensor to channel %d and streams the frames it hears into a .pcap for Wireshark. Bluetooth is narrow, so this is a short, thinned sample — not a full capture — and it stops on its own near %d frames.",
                    channel,
                    SensorLink.RAW_FRAME_CAP,
                )
                SensorLink.CaptureSupport.BandNotHeard -> tr(
                    "This network is on 5 GHz (channel %d), and this sensor hears only 2.4 GHz. Capturing it needs the ESP32-C5 sensor.",
                    channel,
                )
                is SensorLink.CaptureSupport.FirmwareTooOld -> tr(
                    "This network is on 5 GHz (channel %d). The sensor's firmware %s captures on 2.4 GHz only: update the sensor to firmware %s or newer.",
                    channel,
                    support.version,
                    SensorLink.FIVE_GHZ_CAPTURE_FIRMWARE,
                )
            },
        )
        if (mine && link.rawDropped) {
            append(" ")
            append(tr("Some frames were dropped on the way over Bluetooth, so there are gaps."))
        }
    }
    card(header = tr("Traffic capture"), key = "capture", footer = footer, rows = rows)
}

@Composable
private fun CaptureProgressRow(channel: Int, frames: Int, bytes: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Column {
            Text(tr("Capturing on channel %d…", channel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                tr("%d frames · %s", frames, byteSize(bytes.toLong())),
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun captureLabel(network: TrackedNetwork): String {
    val raw = network.revealedSsid ?: network.latest.ssid ?: network.latest.bssid.text
    return raw.replace(Regex("[^A-Za-z0-9_-]"), "-").take(24).ifEmpty { "capture" }
}

private fun reasonText(reason: WifiJoiner.Reason): String = when (reason) {
    WifiJoiner.Reason.WEP_UNSUPPORTED -> tr("WEP networks can't be added on this version of Android.")
    WifiJoiner.Reason.ADD_FAILED -> tr("Android would not add this network.")
    WifiJoiner.Reason.PERMISSION -> tr("Spectra is not allowed to change Wi-Fi settings.")
    WifiJoiner.Reason.GENERIC -> tr("Could not join this network.")
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
            val insight = ProbeInsight.assess(
                probe.ssids,
                model.store.networks().values.filter { it.isInRange }.map { it.latest },
            )
            if (insight.rememberedCount > 0) {
                card(
                    header = tr("Profile"),
                    key = "profile",
                    rows = buildList {
                        add { InfoRow(tr("Remembered networks"), "${insight.rememberedCount}") }
                        if (insight.presentHere.isNotEmpty()) {
                            add { InfoRow(tr("On the air here"), insight.presentHere.joinToString(", ")) }
                        }
                        if (insight.autoJoinOpen.isNotEmpty()) {
                            add {
                                NoteRow(
                                    tr("%s: an open network with this name is here now — the device will auto-join, and the name is easy to impersonate.", insight.autoJoinOpen.joinToString(", ")),
                                    warning = true,
                                )
                            }
                        }
                        if (insight.trackable) {
                            add { NoteRow(tr("Enough remembered networks to recognise this device elsewhere."), warning = false) }
                        }
                    },
                )
            }
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
                footer = tr("Heard by the phone or an ESP32-C5 sensor. The Cardputer and the WROOM-32 hear 2.4 GHz only."),
                padding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
            ) {
                val span = wideDomain(five)
                ChannelSpectrum(five, ticks = listOf(36, 52, 100, 116, 132, 149, 165).filter { it.toDouble() in span }, domain = span)
            }
            val bestFive = ChannelAdvisor.recommendationFive(five)
            freeCard("advice-5") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Spectra.colors.spectrum, alpha = 0.22f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$bestFive", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(tr("Quietest channel here"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            tr("Of the non-DFS channels 36–48 and 149–161, this one meets the least interference."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
