package cattivole.spectra.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.Band
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.Capabilities
import cattivole.spectra.core.ChannelAdvisor
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.RadioSource
import cattivole.spectra.core.SecondaryChannel
import cattivole.spectra.platform.DeviceBleScanner
import cattivole.spectra.platform.PhoneWifiScanner
import cattivole.spectra.platform.SensorLink

@Composable
fun NetworksScreen(
    model: SpectraModel,
    onOpen: (MacAddress) -> Unit,
    onOpenHost: (String) -> Unit,
    onSensor: () -> Unit,
    onChannels: () -> Unit,
) {
    model.generation
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("signal") }
    var showOut by rememberSaveable { mutableStateOf(true) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val networks = model.store.networks().values.filter { network ->
        (showOut || network.isInRange) && (
            query.isBlank() ||
                network.title.contains(query, ignoreCase = true) ||
                network.id.text.contains(query, ignoreCase = true)
            )
    }.sortedWith { a, b ->
        if (a.isInRange != b.isInRange) return@sortedWith if (a.isInRange) -1 else 1
        when (sort) {
            "name" -> a.title.compareTo(b.title, ignoreCase = true)
            "channel" -> a.latest.channel.compareTo(b.latest.channel).takeIf { it != 0 } ?: b.latest.rssi.compareTo(a.latest.rssi)
            else -> b.latest.rssi.compareTo(a.latest.rssi)
        }
    }
    val sensorTint = if (model.link.isConnected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    ScreenColumn(
        title = "Wi‑Fi",
        subtitle = "${sensorHeadline(model)} · ${sensorDetail(model)}",
        onTitleClick = onChannels,
        navigationIcon = {
            IconButton(onClick = onSensor) {
                if (model.store.isScanning) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = sensorTint)
                } else {
                    Icon(Icons.Default.Sensors, contentDescription = "Sensor", tint = sensorTint)
                }
            }
        },
        actions = {
            IconButton(
                onClick = { model.history.saveNetworks(model.store, "This phone") },
                enabled = model.store.networks().values.any { it.isInRange },
            ) { Icon(Icons.Default.Save, contentDescription = "Save") }
            IconButton(onClick = {
                model.link.scanNow()
                model.phone.refresh()
            }) { Icon(Icons.Default.Refresh, contentDescription = "Scan") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Sort by signal") }, onClick = { sort = "signal"; menu = false })
                DropdownMenuItem(text = { Text("Sort by name") }, onClick = { sort = "name"; menu = false })
                DropdownMenuItem(text = { Text("Sort by channel") }, onClick = { sort = "channel"; menu = false })
                DropdownMenuItem(text = { Text(if (showOut) "Hide out of range" else "Show out of range") }, onClick = {
                    showOut = !showOut
                    menu = false
                })
                DropdownMenuItem(text = { Text("Clear list") }, onClick = { model.store.clear(); menu = false })
            }
        },
    ) {
        SimpleList {
            item {
                OutlinedTextField(
                    query,
                    { query = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    label = { Text("Name or BSSID") },
                    singleLine = true,
                )
                phoneRadioNote(model)?.let { note ->
                    Text(
                        note,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (model.store.deauthEvents.isNotEmpty()) {
                item { SectionTitle("Deauth detected") }
                items(model.store.deauthEvents, key = { it.id }) { event ->
                    ListItem(
                        headlineContent = { Text("${event.source.text} → ${if (event.isBroadcast) "broadcast" else event.target.text}") },
                        supportingContent = { Text("${event.reasonDescription} · ${event.count} frames · ch ${event.channel}") },
                        trailingContent = { Text(event.severity.name, color = MaterialTheme.colorScheme.error) },
                    )
                }
                item {
                    Text(
                        "Deauthentication frames force devices off the network. A burst or a broadcast target suggests an active attack.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                SectionTitle("${model.store.networks().values.count { it.wasInLastScan }} in the latest pass")
            }
            if (networks.isEmpty()) {
                item {
                    EmptyText(
                        title = if (model.link.isConnected || model.phone.lastResults > 0) "Listening" else "No networks yet",
                        body = "The phone lists what its own radio hears, including 5 GHz. A Cardputer adds clients, probe requests, deauth frames and hidden names.",
                    )
                }
            }
            items(networks, key = { it.id.text + it.source.name }) { network ->
                ClickRow(
                    title = network.title,
                    subtitle = buildString {
                        append(network.id.text)
                        append(" · ch ")
                        append(network.latest.channel)
                        append(" · ")
                        append(network.latest.auth.name)
                        if (network.clients.isNotEmpty()) append(" · ${network.clients.size} clients")
                        if (!network.wasInLastScan && network.isInRange) append(" · missed ${network.missedScans}")
                        if (!network.isInRange) append(" · out of range")
                    },
                    trailing = "${network.latest.rssi}",
                    onClick = { onOpen(network.id) },
                )
            }
            if (model.store.probingDevices.isNotEmpty()) {
                item { SectionTitle("${model.store.probingDevices.size} probing") }
                items(model.store.probingDevices, key = { it.mac.text }) { device ->
                    val host = lanHost(model, device.mac)
                    ClickRow(
                        title = device.displayName,
                        subtitle = buildString {
                            append("ch ${device.channel} · ")
                            append(device.ssids.ifEmpty { listOf("broadcast") }.joinToString())
                            if (host != null && host.displayName != device.displayName) append(" · on LAN ${host.displayName}")
                        },
                        trailing = "${device.rssi}",
                        onClick = { host?.let { onOpenHost(it.ipAddress) } },
                    )
                }
            }
        }
    }
}

private fun phoneRadioNote(model: SpectraModel): String? = when (model.phone.status) {
    PhoneWifiScanner.Status.THROTTLED -> "Android is limiting Wi-Fi scans. Showing the last results the system kept."
    PhoneWifiScanner.Status.WIFI_OFF -> "Wi-Fi is off, so the phone radio is quiet."
    PhoneWifiScanner.Status.PERMISSION -> "Nearby Wi-Fi permission is off, so network names stay hidden."
    PhoneWifiScanner.Status.LOCATION_OFF -> "Location services must be on or this Android version returns an empty scan."
    PhoneWifiScanner.Status.SCANNING, PhoneWifiScanner.Status.IDLE -> model.phone.note
}

@Composable
fun NetworkDetail(model: SpectraModel, id: MacAddress?, onOpenHost: (String) -> Unit) {
    model.generation
    val network = id?.let { model.store.network(it) }
    ScreenColumn(network?.title ?: "Network") {
        if (network == null) {
            EmptyText("Gone", "This network is no longer in the list.")
            return@ScreenColumn
        }
        val reading = network.latest
        SimpleList {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SignalGlyph(reading.rssi, network.isInRange)
                    Column {
                        Text("${reading.rssi} dBm · ${cattivole.spectra.core.SignalQuality.of(reading.rssi).label}")
                        SourceBadge(network.source)
                    }
                }
                Sparkline(network.samples.map { it.rssi }, signalColor(reading.rssi), Modifier.padding(horizontal = 16.dp))
                Text(
                    "avg ${network.averageRssi} · min ${network.weakestRssi} · max ${network.strongestRssi}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            networkNotes(reading, network.revealedSsid != null).forEach { note ->
                item {
                    Text(note, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { InfoLine("BSSID", reading.bssid.text) }
            item {
                InfoLine(
                    "Address type",
                    if (reading.bssid.isLocallyAdministered) "Randomised" else "Maker-assigned (OUI ${reading.bssid.oui})",
                )
            }
            reading.bssid.vendor()?.let { item { InfoLine("Maker", it) } }
            item { InfoLine("Band", reading.band.label) }
            item { InfoLine("Channel", "${reading.channel} · ${reading.frequencyMHz} MHz · ${widthText(reading)}") }
            item { InfoLine("Authentication", reading.auth.name) }
            item { InfoLine("Pairwise cipher", reading.pairwiseCipher.name) }
            item { InfoLine("Group cipher", reading.groupCipher.name) }
            item { InfoLine("WPS", if (reading.capabilities and Capabilities.WPS != 0) "On" else "Off") }
            item { InfoLine("Generation", Capabilities.generation(reading.capabilities, reading.phyNote)) }
            item { InfoLine("Standards", Capabilities.standards(reading.capabilities)) }
            if (reading.capabilities and Capabilities.FTM != 0) item { InfoLine("Fine Timing Measurement", "Supported") }
            if (reading.capabilities and Capabilities.LONG_RANGE != 0) item { InfoLine("Long range", "802.11 LR") }
            reading.countryCode?.let { item { InfoLine("Country", it) } }
            network.revealedSsid?.let { item { InfoLine("Revealed name", it) } }
            network.vendorInfo?.let { info ->
                item { InfoLine("WPS device", listOf(info.deviceName, info.manufacturer).filter { it.isNotEmpty() }.joinToString(" · ")) }
            }
            network.traffic?.let { traffic ->
                item { InfoLine("Volume", traffic.formattedBytes) }
                item { InfoLine("Packets", traffic.packets.toString()) }
                model.store.lastScanDurationMs?.let { traffic.estimatedBitrate(it) }?.let { bitrate ->
                    item { InfoLine("Est. bitrate", bitrate) }
                }
            }
            item { InfoLine("First seen", clock(network.firstSeen)) }
            item { InfoLine("Last seen", ago(network.lastSeen)) }
            item { InfoLine("Readings", network.samples.size.toString()) }
            if (network.missedScans > 0) item { InfoLine("Missed scans", network.missedScans.toString()) }
            if (network.source == RadioSource.PHONE) {
                item {
                    Text(
                        "This sighting is from the phone. Android does not report the 40 MHz side, associated clients, or frames the network is not addressed to.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (network.clients.isNotEmpty()) {
                item { SectionTitle("Clients") }
            }
            items(network.clients, key = { it.mac.text }) { client ->
                val host = lanHost(model, client.mac)
                val probe = model.store.probingDevices.firstOrNull { it.mac == client.mac }
                ClickRow(
                    title = client.displayName,
                    subtitle = buildString {
                        append(client.mac.text)
                        append(" · ${client.rssi} dBm")
                        if (client.mac.isLocallyAdministered) append(" · random")
                        probe?.let { append(" · ch ${it.channel}") }
                        if (host != null) append(" · on LAN ${host.displayName}")
                    },
                    trailing = if (client.isAssociated) "linked" else "probe",
                    onClick = { host?.let { onOpenHost(it.ipAddress) } },
                )
            }
        }
    }
}

@Composable
fun ChannelsScreen(model: SpectraModel, onBack: () -> Unit) {
    model.generation
    val readings = model.store.networks().values
        .filter { it.isInRange && it.latest.band == Band.TWO_FOUR }
        .map { it.latest }
        .sortedByDescending { it.rssi }
    val five = model.store.networks().values.filter { it.isInRange && it.latest.band != Band.TWO_FOUR }
    val best = ChannelAdvisor.recommendation(readings)
    ScreenColumn(
        "Channels",
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
    ) {
        SimpleList {
            if (readings.isEmpty()) {
                item {
                    EmptyText(
                        "Nothing on 2.4 GHz yet",
                        "The chart uses networks in range. A phone scan draws 40 MHz networks as symmetric; the sensor knows whether they extend above or below.",
                    )
                }
            } else {
                item { SectionTitle("2.4 GHz") }
                item { ChannelSpectrum(readings, Modifier.padding(16.dp)) }
                item {
                    ListItem(
                        headlineContent = { Text("Quietest of 1, 6 and 11") },
                        supportingContent = { Text("Set this on the router if you control one. Overlap is weighted by signal, not by how many names share a channel.") },
                        trailingContent = { Text("$best", style = MaterialTheme.typography.headlineMedium) },
                    )
                }
                items((1..13).toList()) { channel ->
                    val level = ChannelAdvisor.level(ChannelAdvisor.congestion(channel, readings)).toFloat()
                    val count = readings.count { it.channel == channel }
                    Meter("Channel $channel", level, "$count networks")
                }
            }
            if (five.isNotEmpty()) {
                item { SectionTitle("5 and 6 GHz from the phone") }
                items(five.toList(), key = { it.id.text }) { network ->
                    ListItem(
                        headlineContent = { Text(network.title) },
                        supportingContent = { Text("${network.latest.band.label} · ch ${network.latest.channel} · ${network.latest.channelWidthMHz} MHz") },
                        trailingContent = { Text("${network.latest.rssi}") },
                    )
                }
            }
        }
    }
}

@Composable
fun LanScreen(model: SpectraModel, onOpen: (String) -> Unit) {
    model.generation
    ScreenColumn(
        "LAN",
        subtitle = lanStatus(model),
        actions = {
            IconButton(
                onClick = { model.history.saveLan(model.lan.devices, "This phone") },
                enabled = model.lan.devices.isNotEmpty(),
            ) { Icon(Icons.Default.Save, contentDescription = "Save") }
            TextButton(onClick = { if (model.lan.scanning) model.lan.stop() else model.lan.scan() }) {
                Text(if (model.lan.scanning) "Stop" else "Scan")
            }
        },
    ) {
        SimpleList {
            if (model.lan.scanning) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            if (model.lan.widePrefix) {
                item {
                    Text(
                        "The prefix is wider than /24, so only the local /24 is walked.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(model.lan.devices, key = { it.ipAddress }) { device ->
                ClickRow(
                    title = device.displayName,
                    subtitle = buildString {
                        append(device.ipAddress)
                        device.macAddress?.let { append(" · ${it.text}") }
                        listOf(device.identity.manufacturer, device.identity.operatingSystem, device.identity.model)
                            .filterNotNull()
                            .filter { it !in device.displayName }
                            .forEach { append(" · $it") }
                        device.notable?.let { append(" · ${it.title}") }
                        if (device.isGateway) append(" · gateway")
                        if (device.isLocalDevice) append(" · this phone")
                    },
                    trailing = device.openPorts.size.takeIf { device.portsScanned }?.let { "$it ports" } ?: "",
                    onClick = { onOpen(device.ipAddress) },
                )
            }
        }
    }
}

@Composable
fun LanDetail(model: SpectraModel, ip: String) {
    model.generation
    val device = model.lan.devices.firstOrNull { it.ipAddress == ip }
    ScreenColumn(device?.displayName ?: ip) {
        if (device == null) {
            EmptyText("Not in this scan", "Run the LAN scan again.")
            return@ScreenColumn
        }
        SimpleList {
            item { InfoLine("Address", device.ipAddress) }
            device.macAddress?.let { mac ->
                item { InfoLine("Hardware", mac.text) }
                item { InfoLine("Address type", if (mac.isLocallyAdministered) "Locally administered" else "Universal") }
                device.identity.manufacturer?.let { item { InfoLine("Manufacturer", it) } }
                device.identity.operatingSystem?.let { item { InfoLine("System", it) } }
                device.identity.model?.let { item { InfoLine("Model", it) } }
                device.manufacturer?.takeIf { maker ->
                    device.identity.manufacturer?.let { maker.contains(it, true) || it.contains(maker, true) } != true
                }?.let { item { InfoLine("Maker", it) } }
            }
            device.notable?.let { item { InfoLine("Notable", it.title) } }
            if (device.services.isNotEmpty()) item { SectionTitle("Advertised") }
            items(device.services, key = { "${it.type}-${it.name}" }) { service ->
                ListItem(
                    headlineContent = { Text(service.kind) },
                    supportingContent = {
                        Text(listOfNotNull(service.name, service.model?.takeIf { model -> model !in service.name }).joinToString(" · "))
                    },
                )
            }
            device.profile.upnp?.let { upnp ->
                upnp.friendlyName?.let { item { InfoLine("UPnP name", it) } }
                upnp.manufacturer?.let { item { InfoLine("Manufacturer", it) } }
                upnp.model?.let { item { InfoLine("Model", it) } }
                upnp.deviceType?.let { item { InfoLine("Device type", it) } }
            }
            device.profile.windows?.let { windows ->
                windows.computerName?.let { item { InfoLine("Computer", it) } }
                windows.domain?.let { item { InfoLine("Domain", it) } }
                if (windows.shares.isEmpty() && windows.guestSession) {
                    item {
                        Text(
                            "Guest connection found no shared folders.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else if (windows.shares.isNotEmpty()) {
                    item { InfoLine("Guest shares", windows.shares.joinToString()) }
                }
            }
            if (device.openPorts.isNotEmpty()) item { SectionTitle("Open ports") }
            items(device.openPorts, key = { it.number }) { port ->
                val banner = device.profile.banners.firstOrNull { it.port == port.number }
                val rtsp = device.profile.rtsp.firstOrNull { it.port == port.number }
                ListItem(
                    headlineContent = { Text("${port.number} ${port.service}") },
                    supportingContent = { Text(banner?.summary ?: rtsp?.summary ?: "") },
                )
            }
        }
    }
}

@Composable
fun BluetoothScreen(model: SpectraModel, onOpen: (String) -> Unit) {
    model.generation
    var query by rememberSaveable { mutableStateOf("") }
    val rows = model.ble.devices.values.filter { device ->
        query.isBlank() || device.displayName.contains(query, true) || device.address.contains(query, true) ||
            device.companyName?.contains(query, true) == true
    }.sortedWith(compareByDescending<cattivole.spectra.core.BleDevice> { it.isNearby(model.ble.now) }.thenByDescending { it.orderRssi })
    val heard = model.ble.devices.values
    val nearby = heard.count { it.isNearby(model.ble.now) }
    ScreenColumn(
        "Bluetooth",
        subtitle = bluetoothStatus(model.ble.state, nearby, heard.size),
        actions = {
            IconButton(
                onClick = { model.history.saveBluetooth(model.ble.devices.values, "This phone") },
                enabled = model.ble.devices.isNotEmpty(),
            ) { Icon(Icons.Default.Save, contentDescription = "Save") }
            TextButton(onClick = { if (model.ble.state is DeviceBleScanner.State.Scanning) model.ble.stop() else model.ble.start() }) {
                Text(if (model.ble.state is DeviceBleScanner.State.Scanning) "Stop" else "Scan")
            }
        },
    ) {
        SimpleList {
            item {
                Text(
                    "Android reports the hardware address. iOS does not. A resolvable private address still changes, so the same device can reappear under a new one.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Classic inquiry", modifier = Modifier.weight(1f))
                    Switch(model.ble.classicInquiry, { model.ble.setClassic(it) })
                }
                model.ble.classicNote?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    query,
                    { query = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    label = { Text("Name, company or address") },
                    singleLine = true,
                )
            }
            items(rows, key = { it.address }) { device ->
                ClickRow(
                    title = device.displayName,
                    subtitle = device.subtitle + " · " + device.address,
                    trailing = if (device.isNearby(model.ble.now)) "${device.rssi}" else "gone",
                    onClick = { onOpen(device.address) },
                )
            }
        }
    }
}

private fun bluetoothStatus(state: DeviceBleScanner.State, nearby: Int, total: Int): String {
    val counts = if (nearby == total) "$nearby nearby" else "$nearby nearby · $total seen"
    return when (state) {
        is DeviceBleScanner.State.Scanning -> "Scanning · $counts"
        DeviceBleScanner.State.Idle -> "Paused · $counts"
        is DeviceBleScanner.State.Unavailable -> when (state.why) {
            DeviceBleScanner.Availability.POWERED_OFF -> "Bluetooth is off"
            DeviceBleScanner.Availability.UNAUTHORIZED -> "Bluetooth permission is off"
            DeviceBleScanner.Availability.UNSUPPORTED -> "This phone has no Bluetooth radio"
            DeviceBleScanner.Availability.STARTING -> "Starting"
        }
    }
}

@Composable
fun BleDetail(model: SpectraModel, address: String) {
    model.generation
    val device = model.ble.devices[address]
    ScreenColumn(device?.displayName ?: address) {
        if (device == null) {
            EmptyText("Not heard", "It has not advertised since this list was cleared.")
            return@ScreenColumn
        }
        SimpleList {
            item { InfoLine("Address", device.address) }
            item { InfoLine("Kind", device.addressKind) }
            item { InfoLine("Transport", if (device.transport == cattivole.spectra.core.BleDevice.Transport.CLASSIC) "Classic" else "Low energy") }
            item { InfoLine("Signal", "${device.rssi} dBm") }
            device.companyName?.let { item { InfoLine("Company", it) } }
            device.txPower?.let { item { InfoLine("TX power", "$it dBm") } }
            device.isConnectable?.let { item { InfoLine("Connectable", if (it) "Yes" else "No") } }
            if (device.apple.isNotEmpty()) item { SectionTitle("Apple") }
            items(device.apple) { message ->
                ListItem(
                    headlineContent = { Text(message.label) },
                    supportingContent = { Text(message.detail ?: "") },
                )
            }
            if (device.services.isNotEmpty()) item { SectionTitle("Services") }
            items(device.services, key = { it.uuid }) { service ->
                ListItem(
                    headlineContent = { Text(service.name ?: service.uuid) },
                    supportingContent = { Text(service.uuid, fontFamily = FontFamily.Monospace) },
                )
            }
            if (device.manufacturerPayload.isNotEmpty()) {
                item { InfoLine("Manufacturer data", cattivole.spectra.core.hex(device.manufacturerPayload)) }
            }
        }
    }
}

@Composable
fun SensorScreen(model: SpectraModel) {
    model.generation
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            model.link.dismissFirmwareUpdate()
        } else {
            model.link.installFirmware(bytes)
        }
    }
    val link = model.link
    ScreenColumn("Sensor") {
        SimpleList {
            item {
                val headline = when (val state = link.state) {
                    is SensorLink.State.Connected -> "Connected to ${state.name}"
                    SensorLink.State.Demo -> "Demo sensor"
                    is SensorLink.State.Connecting -> "Connecting to ${state.name}"
                    SensorLink.State.Searching -> "Searching"
                    SensorLink.State.PoweredOff -> "Bluetooth is off"
                    SensorLink.State.Unauthorized -> "Bluetooth permission is off"
                    SensorLink.State.Unsupported -> "No Bluetooth radio"
                    SensorLink.State.Idle, SensorLink.State.Starting -> "Not connected"
                }
                ListItem(headlineContent = { Text(headline) })
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (link.state) {
                        is SensorLink.State.Connected, SensorLink.State.Demo -> {
                            Button(onClick = { link.scanNow() }) { Text("Scan now") }
                            TextButton(onClick = { link.disconnect() }) { Text("Disconnect") }
                        }
                        SensorLink.State.Searching -> TextButton(onClick = { link.stopSearching() }) { Text("Stop") }
                        else -> Button(onClick = { link.startSearching() }) { Text("Find sensor") }
                    }
                }
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Demo sensor", modifier = Modifier.weight(1f))
                    Switch(link.state is SensorLink.State.Demo, { link.setDemo(it) })
                }
            }
            link.firmwareUpdate?.let { update ->
                item {
                    val text = when (val stage = update.stage) {
                        is cattivole.spectra.core.FirmwareUpdateProgress.Stage.Uploading ->
                            "Sending ${stage.sent} / ${stage.total}"
                        cattivole.spectra.core.FirmwareUpdateProgress.Stage.Preparing -> "Preparing"
                        cattivole.spectra.core.FirmwareUpdateProgress.Stage.Finishing -> "Checking the image"
                        cattivole.spectra.core.FirmwareUpdateProgress.Stage.Rebooting -> "The sensor is rebooting into the new image"
                        is cattivole.spectra.core.FirmwareUpdateProgress.Stage.Succeeded -> stage.version
                        is cattivole.spectra.core.FirmwareUpdateProgress.Stage.Failed -> stage.message
                    }
                    ListItem(headlineContent = { Text(text) })
                    if (!update.isRunning) TextButton(onClick = { link.dismissFirmwareUpdate() }) { Text("Dismiss") }
                    else TextButton(onClick = { link.cancelFirmwareUpdate() }) { Text("Cancel") }
                }
            }
            if (link.state is SensorLink.State.Searching) {
                item { SectionTitle("Nearby sensors") }
                items(link.discovered, key = { it.address }) { sensor ->
                    ClickRow(sensor.name, sensor.address, "${sensor.rssi}", onClick = { link.connect(sensor.address, sensor.name) })
                }
            }
            if (link.isConnected) {
                item { SectionTitle("Automatic scan") }
                items(SensorLink.liveIntervals) { seconds ->
                    ClickRow(
                        title = SensorLink.intervalTitle(seconds),
                        subtitle = if (link.liveInterval == seconds) "Current" else "Tap to use",
                        trailing = if (link.liveInterval == seconds) "On" else "",
                        onClick = { link.setLiveInterval(seconds) },
                    )
                }
                link.info?.let { info ->
                    item { HorizontalDivider() }
                    item { InfoLine("Firmware", info.fw) }
                    item { InfoLine("Reported interval", SensorLink.intervalTitle(info.interval)) }
                    item { InfoLine("Updates", if (info.ota) "From the app" else "Not from the app") }
                    link.batteryLevel?.let { level ->
                        item { InfoLine("Battery", if (link.isCharging == true) "$level% charging" else "$level%") }
                    }
                    item { InfoLine("Chip", info.chip) }
                    item { InfoLine("Protocol", info.proto.toString()) }
                    item { InfoLine("Bands", info.bands.joinToString()) }
                    item { InfoLine("Scans this session", model.store.scanCount.toString()) }
                    if (info.ota && link.state is SensorLink.State.Connected) {
                        item {
                            Text(
                                "Choose firmware.bin from pio run, the same app image Launcher installs. A merged image with a bootloader is rejected.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) { Text("Update firmware") }
                        }
                    }
                }
            }
        }
    }
}

private fun networkNotes(reading: AccessPointReading, revealed: Boolean): List<String> {
    val notes = mutableListOf<String>()
    when (reading.auth.strength) {
        AuthMode.Strength.NONE -> notes += "Open network: traffic is not encrypted, anyone nearby can read it."
        AuthMode.Strength.WEAK -> notes += "${reading.auth.name} is obsolete and can be broken in minutes."
        else -> Unit
    }
    if (reading.capabilities and Capabilities.WPS != 0) {
        notes += "WPS is on. Its PIN method is a known weak point; turn it off if this is your router."
    }
    if (reading.groupCipher == Cipher.TKIP || reading.pairwiseCipher == Cipher.TKIP) {
        notes += "TKIP is enabled for old devices and weakens the whole network."
    }
    if (reading.bssid.isLocallyAdministered) {
        notes += "Randomised address: usually a phone hotspot, a guest network or a mesh node."
    }
    if (revealed) {
        notes += "Hidden SSID revealed from a probe response or association frame — the AP hides its name from beacons."
    }
    return notes
}

private fun widthText(reading: AccessPointReading): String = when (reading.secondaryChannel) {
    SecondaryChannel.ABOVE -> "${reading.channelWidthMHz} MHz, secondary above"
    SecondaryChannel.BELOW -> "${reading.channelWidthMHz} MHz, secondary below"
    else -> "${reading.channelWidthMHz} MHz"
}

@Composable
fun HistoryScreen(model: SpectraModel, onOpen: (String) -> Unit) {
    model.generation
    var confirmClear by remember { mutableStateOf(false) }
    ScreenColumn(
        "History",
        actions = {
            if (model.history.entries.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
        },
    ) {
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Clear history?") },
                text = { Text("Saved scans from this phone and the Cardputer are removed.") },
                confirmButton = {
                    TextButton(onClick = {
                        model.history.clear()
                        confirmClear = false
                    }) { Text("Clear history") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
                },
            )
        }
        SimpleList {
            if (model.history.entries.isEmpty()) {
                item {
                    EmptyText(
                        "Nothing saved",
                        "Save what Wi‑Fi, LAN or Bluetooth just found. The Cardputer's side button saves its own scan, and it arrives here when the phone connects.",
                    )
                }
            }
            for (kind in listOf("networks", "lan", "bluetooth")) {
                val rows = model.history.entries.filter { it.kind == kind }
                if (rows.isEmpty()) continue
                item { SectionTitle(rows.first().kindTitle) }
                items(rows, key = { it.id }) { entry ->
                    ClickRow(
                        title = "${entry.lines.size} · ${entry.source}",
                        subtitle = ago(entry.savedAt),
                        trailing = "",
                        onClick = { onOpen(entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryDetail(model: SpectraModel, id: String, onBack: () -> Unit) {
    model.generation
    val entry = model.history.entry(id)
    ScreenColumn(
        entry?.kindTitle ?: "History",
        subtitle = entry?.let { "${it.source} · ${ago(it.savedAt)}" },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
            if (entry != null) {
                TextButton(onClick = {
                    model.history.delete(id)
                    onBack()
                }) { Text("Delete") }
            }
        },
    ) {
        SimpleList {
            if (entry == null) {
                item { EmptyText("Gone", "This saved scan is no longer here.") }
            } else {
                items(entry.lines) { line ->
                    ListItem(
                        headlineContent = { Text(line.title) },
                        supportingContent = { if (line.detail.isNotEmpty()) Text(line.detail) },
                        trailingContent = { if (line.trailing.isNotEmpty()) Text(line.trailing) },
                    )
                }
            }
        }
    }
}

private fun clock(millis: Long): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(millis))

private fun ago(millis: Long): String {
    val minutes = ((System.currentTimeMillis() - millis) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        else -> "${minutes / 60}h ${minutes % 60}m ago"
    }
}

private fun lanHost(model: SpectraModel, mac: MacAddress) =
    model.lan.devices.firstOrNull { it.macAddress == mac }

private fun lanStatus(model: SpectraModel): String {
    val lan = model.lan
    if (lan.scanning) {
        val phase = lan.progress.ifBlank { "Scanning" }
        return if (phase.startsWith("Scanning")) phase else "Scanning · $phase"
    }
    val info = lan.subnet ?: return "Tap Scan to look at this Wi-Fi"
    return buildString {
        append(info.localIp)
        append("/${info.prefixLength}")
        info.gateway?.let { append(" · $it") }
    }
}
