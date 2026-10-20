package cattivole.spectra.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WifiOff
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.DiscoveredPort
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.NotableDevice
import cattivole.spectra.core.webAddress

@Composable
private fun LanDevice.tint(): Color = when {
    isLocalDevice -> MaterialTheme.colorScheme.primary
    isGateway -> Spectra.colors.warning
    notable == NotableDevice.CAMERA -> Spectra.colors.danger
    notable == NotableDevice.IOT -> Spectra.colors.weak
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun LanBadges(device: LanDevice, all: Boolean = false) {
    if (device.isLocalDevice) Pill(tr("This device"), MaterialTheme.colorScheme.primary)
    if (device.isGateway && (all || !device.isLocalDevice)) Pill(tr("Gateway"), Spectra.colors.warning)
    val notable = device.notable
    if (notable != null && (all || (!device.isLocalDevice && !device.isGateway))) {
        Pill(if (notable == NotableDevice.CAMERA) tr("Camera") else tr("IoT"), if (notable == NotableDevice.CAMERA) Spectra.colors.danger else Spectra.colors.weak)
    }
}

/** The scanner reports its phase in English with numbers in it; this puts it in the reader's words. */
internal fun lanPhase(text: String): String {
    Regex("""Looking for (\d+) addresses""").matchEntire(text)?.let { return tr("Looking for %s addresses", it.groupValues[1]) }
    Regex("""Checking (\d+) hosts""").matchEntire(text)?.let { return tr("Checking %s hosts", it.groupValues[1]) }
    Regex("""(\d+) devices""").matchEntire(text)?.let { return tr("Devices: %s", it.groupValues[1]) }
    return tr(text)
}

@Composable
fun LanScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val lan = model.lan
    var query by rememberSaveable { mutableStateOf("") }
    var menu by rememberSaveable { mutableStateOf(false) }
    val needle = query.trim()
    val visible = lan.devices.filter { device ->
        needle.isEmpty() ||
            device.ipAddress.contains(needle, true) ||
            device.displayName.contains(needle, true) ||
            device.macAddress?.text?.contains(needle, true) == true ||
            device.manufacturer?.contains(needle, true) == true ||
            device.identity.label.contains(needle, true)
    }
    SpectraScreen(
        title = "LAN",
        actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = tr("Options")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (lan.scanning) tr("Stop") else tr("Scan now")) },
                        leadingIcon = { Icon(if (lan.scanning) Icons.Rounded.Stop else Icons.Rounded.Refresh, null) },
                        enabled = lan.scanning || !lan.noNetwork,
                        onClick = {
                            if (lan.scanning) lan.stop() else lan.scan()
                            menu = false
                        },
                    )
                    if (lan.devices.isNotEmpty()) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(tr("Clear list")) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                            onClick = {
                                lan.clear()
                                menu = false
                            },
                        )
                    }
                }
            }
        },
        refreshing = false,
        onRefresh = { if (!lan.scanning) lan.scan() },
        searchQuery = query.takeIf { lan.devices.isNotEmpty() },
        onSearchChange = if (lan.devices.isNotEmpty()) {
            { query = it }
        } else {
            null
        },
        searchPlaceholder = tr("IP, name or MAC"),
        overlay = {
            when {
                lan.devices.isEmpty() && !lan.scanning && lan.noNetwork -> EmptyState(
                    Icons.Rounded.WifiOff,
                    tr("No Wi-Fi"),
                    tr("Connect to a Wi-Fi network to scan devices on it."),
                    action = tr("Try again"),
                    onAction = lan::scan,
                )
                lan.devices.isEmpty() && !lan.scanning -> EmptyState(
                    Icons.Rounded.Lan,
                    tr("Local network"),
                    tr("Find devices, their hardware addresses and open ports on the network this phone is on."),
                    action = tr("Scan"),
                    onAction = lan::scan,
                )
                visible.isEmpty() && needle.isNotEmpty() -> EmptyState(Icons.Rounded.SearchOff, tr("No results"), tr("Nothing matches “%s”.", needle))
            }
        },
    ) {
        item(key = "status") {
            ChipRow(Modifier.padding(bottom = 4.dp)) {
                val info = lan.subnet
                StatusChip(
                    icon = Icons.Rounded.Lan,
                    title = when {
                        lan.scanning -> tr("Scanning…")
                        lan.noNetwork -> tr("No Wi-Fi")
                        info != null -> info.localIp
                        else -> "LAN"
                    },
                    detail = when {
                        lan.scanning -> lanPhase(lan.progress)
                        lan.noNetwork -> tr("Join a network")
                        info != null -> listOfNotNull("/${info.prefixLength}", info.gateway?.let { tr("gateway %s", it) }, lan.lastScanDate?.let { ago(it) })
                            .joinToString(" · ")
                        else -> tr("Tap to scan")
                    },
                    busy = lan.scanning,
                    onClick = { if (lan.scanning) lan.stop() else lan.scan() },
                )
            }
        }
        if (lan.scanning) {
            card(key = "progress", rows = listOf { ProgressRow(lanPhase(lan.progress), lan.fraction) })
        }
        if (lan.widePrefix) {
            card(key = "wide", rows = listOf { NoteRow(tr("The prefix is wider than /20, so only the local /24 is walked."), warning = false) })
        }
        connectionEntry(model, nav)
        cardSection(
            visible,
            header = tr("Devices: %d", lan.devices.size),
            key = { it.ipAddress },
            sectionKey = "devices",
            dividerInset = 70.dp,
        ) { device -> DeviceRow(device, onClick = { nav.host(device.ipAddress) }) }
    }
}

@Composable
fun DeviceRow(device: LanDevice, onClick: () -> Unit) {
    val subtitle = buildList {
        if (device.displayName != device.ipAddress) add(device.ipAddress)
        device.macAddress?.let { add(it.text) }
        for (fact in listOf(device.identity.manufacturer, device.identity.operatingSystem, device.identity.model)) {
            if (fact == null || device.displayName.contains(fact, true) || any { it.contains(fact, true) }) continue
            add(fact)
        }
        val maker = device.manufacturer
        if (maker != null && device.identity.manufacturer == null && device.displayName != maker) add(maker)
    }.joinToString(" · ")
    val ports = device.openPorts.takeIf { it.isNotEmpty() }?.let { open ->
        val list = open.take(4).joinToString(", ") { "${it.number}" }
        tr("ports %s", list + if (open.size > 4) "…" else "")
    }
    WithCopyMenu(
        listOfNotNull(
            CopyItem(tr("Copy IP"), "IP", device.ipAddress),
            device.macAddress?.let { CopyItem(tr("Copy MAC"), "MAC", it.text) },
        ),
    ) { open ->
        ListRow(
            title = device.displayName,
            subtitle = subtitle,
            detail = ports,
            leading = { IconTile(device.icon(), device.tint()) },
            badges = { LanBadges(device) },
            onClick = onClick,
            onLongClick = open,
        )
    }
}

@Composable
fun LanDetail(model: SpectraModel, nav: Navigator, ip: String, entryId: String?) {
    model.generation
    val lan = model.lan
    val entry = entryId?.let { model.history.entry(it) }
    val device = if (entryId != null) entry?.host(ip) else lan.devices.firstOrNull { it.ipAddress == ip }
    if (device == null) {
        SpectraScreen(ip, onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.Lan, tr("Device cleared"), tr("It is not in this scan. Run the LAN scan again."))
        }) {}
        return
    }
    val snapshot = entryId != null
    val context = LocalContext.current
    SpectraScreen(
        title = device.displayName,
        subtitle = if (snapshot) entry?.let { tr("Saved %s", dateTime(it.savedAt)) } else device.ipAddress,
        onBack = nav::back,
    ) {
        freeCard("hero") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconTile(device.icon(), device.tint(), size = 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(device.displayName, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val label = device.identity.label.ifEmpty { device.manufacturer.orEmpty() }
                    if (label.isNotEmpty()) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        LanBadges(device, all = true)
                        if (device.portsScanned) Pill(tr("Ports: %d", device.openPorts.size), MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
        card(
            header = tr("Network"),
            key = "network",
            rows = buildList {
                add { InfoRow(tr("IP address"), device.ipAddress, mono = true, copyable = true) }
                device.macAddress?.let { mac ->
                    add { InfoRow(tr("MAC address"), mac.text, mono = true, copyable = true) }
                    add { InfoRow("OUI", mac.oui, mono = true) }
                    add {
                        InfoRow(
                            tr("Address type"),
                            when {
                                mac.isLocallyAdministered -> tr("Randomised")
                                device.manufacturer != null -> tr("Maker-assigned (%s)", device.manufacturer)
                                else -> tr("Maker-assigned")
                            },
                        )
                    }
                }
                device.identity.manufacturer?.let { add { InfoRow(tr("Manufacturer"), it) } }
                device.identity.operatingSystem?.let { add { InfoRow(tr("System"), it) } }
                device.identity.model?.let { add { InfoRow(tr("Model"), it) } }
                device.hostnames.forEach { name -> add { InfoRow(tr("Hostname"), name, copyable = true) } }
            },
        )
        val profile = device.profile
        if (!profile.isEmpty || device.services.isNotEmpty()) {
            card(
                header = tr("Services"),
                key = "services",
                rows = buildList {
                    profile.upnp?.let { upnp ->
                        upnp.friendlyName?.let { add { InfoRow(tr("UPnP name"), it) } }
                        upnp.model?.let { add { InfoRow(tr("Model"), it) } }
                        upnp.manufacturer?.let { add { InfoRow(tr("Manufacturer"), it) } }
                        upnp.deviceType?.let { add { InfoRow(tr("Device type"), it) } }
                        upnp.server?.let { add { InfoRow(tr("Server"), it) } }
                    }
                    device.services.forEach { service ->
                        add {
                            val detail = listOfNotNull(service.name, service.model?.takeIf { !service.name.contains(it, true) }).joinToString(" · ")
                            InfoRow(tr(service.kind), detail)
                        }
                    }
                    profile.windows?.let { windows ->
                        windows.computerName?.let { add { InfoRow(tr("Network name"), it) } }
                        windows.domain?.let { add { InfoRow(tr("Domain"), it) } }
                        if (windows.shares.isEmpty() && windows.guestSession) {
                            add { NoteRow(tr("Guest connection found no shared folders."), warning = false) }
                        } else if (windows.shares.isNotEmpty()) {
                            add { InfoRow(tr("Guest shares"), windows.shares.joinToString(", ")) }
                        }
                    }
                    profile.banners.forEach { banner ->
                        add { InfoRow(if (banner.secure) "HTTPS ${banner.port}" else "HTTP ${banner.port}", banner.summary) }
                    }
                    profile.rtsp.forEach { service -> add { InfoRow("RTSP ${service.port}", service.summary.orEmpty()) } }
                },
            )
        }
        if (!snapshot || device.portsScanned) {
            card(
                header = tr("Ports"),
                key = "ports",
                footer = if (snapshot) null else tr("Checks %d common TCP ports, or every TCP port from 1 to 65535.", DiscoveredPort.commonPorts.size),
                rows = buildList {
                    if (device.portsScanned) {
                        if (device.openPorts.isEmpty()) {
                            add {
                                ListRow(
                                    title = tr("No open ports found"),
                                    leading = { IconTile(Icons.Rounded.Shield, Spectra.colors.success, size = 32.dp) },
                                )
                            }
                        } else {
                            device.openPorts.forEach { port ->
                                val url = device.webAddress(port.number)
                                add {
                                    PortRow(
                                        port,
                                        device,
                                        onOpen = url?.let {
                                            { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (!snapshot) {
                        val fraction = device.portScanFraction
                        if (fraction != null) {
                            add { ProgressRow(tr("Scanning ports…"), fraction, "${(fraction * 100).toInt()}%") }
                        } else {
                            add {
                                ActionRow(
                                    if (device.portsScanned) tr("Rescan ports") else tr("Scan ports"),
                                    Icons.Rounded.Search,
                                    onClick = { lan.scanHostPorts(ip) },
                                    enabled = !lan.scanning,
                                )
                            }
                            add {
                                ActionRow(
                                    tr("Scan all ports"),
                                    Icons.AutoMirrored.Rounded.ManageSearch,
                                    onClick = { lan.scanHostPorts(ip, all = true) },
                                    enabled = !lan.scanning,
                                )
                            }
                        }
                    }
                },
            )
        }
        card(
            header = tr("Observed"),
            key = "observed",
            rows = listOf(
                { InfoRow(tr("First seen"), clockTime(device.firstSeen)) },
                { InfoRow(tr("Last seen"), ago(device.lastSeen, entry?.savedAt ?: System.currentTimeMillis())) },
            ),
        )
    }
}

@Composable
private fun PortRow(port: DiscoveredPort, device: LanDevice, onOpen: (() -> Unit)? = null) {
    val detail = device.profile.banners.firstOrNull { it.port == port.number }?.summary
        ?: device.profile.rtsp.firstOrNull { it.port == port.number }?.summary
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = tr("Open in browser"), onClick = onOpen) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "${port.number}",
            style = MaterialTheme.typography.titleSmall.merge(Tabular),
            modifier = Modifier.widthIn(min = 48.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(tr(port.service), style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onOpen != null) {
            Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(Spectra.colors.success))
    }
}
