package cattivole.spectra.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.PlayCircle
import cattivole.spectra.core.streamAddress
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
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import cattivole.spectra.core.DeviceLabel
import cattivole.spectra.core.DeviceTrust
import cattivole.spectra.core.RiskFinding
import cattivole.spectra.core.RiskLevel
import cattivole.spectra.core.needsAttention
import cattivole.spectra.core.riskFindings
import cattivole.spectra.core.riskLevel
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
    if (device.isNew) Pill(tr("New"), MaterialTheme.colorScheme.tertiary)
    when (device.trust) {
        DeviceTrust.TRUSTED -> Icon(
            Icons.Rounded.Verified,
            contentDescription = tr("Trusted"),
            tint = Spectra.colors.success,
            modifier = Modifier.size(14.dp),
        )
        DeviceTrust.SUSPICIOUS -> Pill(tr("Suspicious"), Spectra.colors.danger)
        DeviceTrust.UNKNOWN -> Unit
    }
}

@Composable
internal fun RiskLevel.color(): Color = when (this) {
    RiskLevel.HIGH -> Spectra.colors.danger
    RiskLevel.MEDIUM -> Spectra.colors.warning
    RiskLevel.LOW -> Spectra.colors.weak
    RiskLevel.INFO -> MaterialTheme.colorScheme.outline
}

internal fun RiskLevel.icon(): ImageVector = when (this) {
    RiskLevel.HIGH -> Icons.Rounded.GppMaybe
    RiskLevel.MEDIUM -> Icons.Rounded.Warning
    RiskLevel.LOW, RiskLevel.INFO -> Icons.Rounded.Info
}

/** The finding's explanation in the reader's words, with its list filled in. */
internal fun RiskFinding.shownDetail(): String =
    if (detailArg == null) tr(detail) else tr(detail, detailArg)

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
    var onlyAtRisk by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) {}
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    val hideTrusted = model.hideTrustedHosts
    val needle = query.trim()
    val atRisk = lan.devices.filter { it.needsAttention }
    if (atRisk.isEmpty() && onlyAtRisk) onlyAtRisk = false
    val visible = lan.devices.filter { device ->
        if (onlyAtRisk && !device.needsAttention) return@filter false
        if (hideTrusted && needle.isEmpty() && device.trust == DeviceTrust.TRUSTED) return@filter false
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
            if (lan.devices.isNotEmpty()) {
                AskAssistantButton(model, nav, question = { tr("Analyze the devices on my local network: what each one is and what deserves attention?") })
            }
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
                    DropdownMenuItem(
                        text = { Text(tr("Notify about new devices")) },
                        leadingIcon = { Icon(if (model.notifyNewDevices) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null) },
                        onClick = {
                            model.notifyNewDevices = !model.notifyNewDevices
                            if (model.notifyNewDevices && android.os.Build.VERSION.SDK_INT >= 33 &&
                                !cattivole.spectra.platform.NewDeviceNotifier.allowed(context)
                            ) {
                                askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Hide trusted devices")) },
                        leadingIcon = { Icon(if (hideTrusted) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null) },
                        onClick = { model.hideTrustedHosts = !hideTrusted },
                    )
                    if (lan.hasBaseline) {
                        DropdownMenuItem(
                            text = { Text(tr("Forget known devices")) },
                            leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                            enabled = !lan.scanning,
                            onClick = {
                                confirmForget = true
                                menu = false
                            },
                        )
                    }
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
                lan.devices.isEmpty() && !lan.scanning && lan.noNetwork && lan.ipv6Only -> EmptyState(
                    Icons.Rounded.Lan,
                    tr("IPv6-only network"),
                    tr("This Wi-Fi gives the phone no IPv4 address. The device scan walks an IPv4 subnet, so there is nothing to sweep here. Connection, speed and link checks still work."),
                    action = tr("Scan again"),
                    onAction = lan::scan,
                )
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
                        lan.noNetwork && lan.ipv6Only -> tr("IPv6 only")
                        lan.noNetwork -> tr("No Wi-Fi")
                        info != null -> info.localIp
                        else -> "LAN"
                    },
                    detail = when {
                        lan.scanning -> lanPhase(lan.progress)
                        lan.noNetwork && lan.ipv6Only -> tr("No IPv4 to scan")
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
        val joined = model.store.networks().values.firstOrNull { model.wifiJoiner.isCurrent(it) }
        if (joined != null && joined.latest.auth.raw == 0) {
            card(key = "open-wifi", header = tr("Open Wi-Fi"), rows = listOf {
                NoteRow(tr("This network has no password. Anyone nearby can join it and read traffic that is not encrypted."), warning = true)
            })
        }
        if (lan.widePrefix) {
            card(key = "wide", rows = listOf { NoteRow(tr("The prefix is wider than /20, so only the local /24 is walked."), warning = false) })
        }
        trafficEntry(model, nav)
        connectionEntry(model, nav)
        linkCheckEntry(model, nav)
        if (!lan.scanning && lan.devices.isNotEmpty() && needle.isEmpty()) {
            val worst = atRisk.mapNotNull { it.riskLevel }.maxOrNull()
            card(key = "risk", rows = listOf {
                ListRow(
                    title = if (atRisk.isEmpty()) tr("No risky services found") else tr("%d devices need attention", atRisk.size),
                    subtitle = when {
                        atRisk.isEmpty() -> tr("Open ports were checked against known weak services.")
                        onlyAtRisk -> tr("Showing only those. Tap to show every device.")
                        else -> tr("Telnet, open shares, remote access and the like. Tap to show only those.")
                    },
                    leading = { IconTile(worst?.icon() ?: Icons.Rounded.VerifiedUser, worst?.color() ?: Spectra.colors.success) },
                    trailing = if (onlyAtRisk) {
                        { Icon(Icons.Rounded.FilterList, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    onClick = if (atRisk.isEmpty()) null else ({ onlyAtRisk = !onlyAtRisk }),
                )
            })
        }
        val fresh = lan.devices.count { it.isNew }
        cardSection(
            visible,
            header = when {
                onlyAtRisk -> tr("%d of %d devices", visible.size, lan.devices.size)
                fresh > 0 -> tr("Devices: %d · new: %d", lan.devices.size, fresh)
                else -> tr("Devices: %d", lan.devices.size)
            },
            footer = if (lan.lastVerdict?.isBaseline == true) {
                tr("First scan of this network. Devices that show up in later scans will be marked New.")
            } else {
                null
            },
            key = { it.ipAddress },
            sectionKey = "devices",
            dividerInset = 70.dp,
        ) { device -> DeviceRow(device, onClick = { nav.host(device.ipAddress) }) }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(tr("Forget known devices?")) },
            text = { Text(tr("The next scan of this network becomes the baseline: nothing in it is marked New.")) },
            confirmButton = {
                TextButton(onClick = {
                    lan.forgetKnownHosts()
                    confirmForget = false
                }) { Text(tr("Forget")) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(tr("Cancel")) } },
        )
    }
}

/** Name, verdict and note for one host. */
/** The stream address, editable, then opened in a player or copied. */
@Composable
private fun StreamAddressDialog(address: String, onChange: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notify = LocalNotify.current
    val copy = rememberCopier()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Stream address")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Edit the path if the camera needs one (e.g. /cam, /live)."))
                OutlinedTextField(
                    value = address,
                    onValueChange = onChange,
                    singleLine = true,
                    placeholder = { Text("rtsp://…") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                        autoCorrectEnabled = false,
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address.trim())))
                } catch (_: android.content.ActivityNotFoundException) {
                    // Android has no player for rtsp:// or rtmp:// of its own.
                    copy(tr("Stream address"), address.trim())
                    notify(tr("No app here opens %s links. Install a player such as VLC; the address is copied.", address.substringBefore("://")))
                } catch (_: Exception) {
                }
            }) { Text(tr("Open")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    copy(tr("Stream address"), address.trim())
                    onDismiss()
                }) { Text(tr("Copy")) }
                TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
            }
        },
    )
}

@Composable
private fun LabelDialog(device: LanDevice, onSave: (DeviceLabel) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(device.customName.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(device.note.orEmpty()) }
    var trust by rememberSaveable { mutableStateOf(device.trust) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Name and trust")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChipRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    DeviceTrust.entries.forEach { option ->
                        ToggleChip(tr(option.label), trust == option, { trust = option })
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text(tr("Note")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Text(
                    tr("Kept on this phone. The device is found again by its hardware address or name."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(DeviceLabel(name, trust, note)) }) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun FindingRow(finding: RiskFinding) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(finding.level.icon(), contentDescription = tr(finding.level.label), tint = finding.level.color(), modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            val ports = finding.ports.joinToString(", ")
            Text(
                if (ports.isEmpty()) tr(finding.title) else "${tr(finding.title)} · $ports",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(finding.shownDetail(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
            trailing = device.riskLevel?.takeIf { it >= RiskLevel.MEDIUM }?.let { level ->
                { Icon(level.icon(), contentDescription = tr(level.label), tint = level.color(), modifier = Modifier.size(20.dp)) }
            },
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
    var editing by rememberSaveable { mutableStateOf(false) }
    /** A stream address shown for editing before it opens: cameras often want a path such as /live. */
    var editableStream by rememberSaveable { mutableStateOf<String?>(null) }
    editableStream?.let { address ->
        StreamAddressDialog(
            address,
            onChange = { editableStream = it },
            onDismiss = { editableStream = null },
        )
    }
    if (!snapshot) lan.labels.apply(device)
    if (editing) {
        LabelDialog(
            device,
            onSave = {
                lan.setLabel(device, it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
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
        if (!snapshot) {
            card(
                key = "label",
                rows = buildList {
                    device.note?.let { note -> add { NoteRow(note, warning = false) } }
                    add {
                        ActionRow(
                            if (device.customName == null && device.trust == DeviceTrust.UNKNOWN && device.note == null) tr("Name and trust") else tr("Edit name and trust"),
                            Icons.Rounded.Edit,
                            onClick = { editing = true },
                        )
                    }
                },
            )
        }
        val findings = device.riskFindings
        if (findings.isNotEmpty()) {
            card(
                header = tr("Security"),
                key = "risk",
                footer = tr("Judged from the open ports alone. Nothing was logged in to or tested."),
                rows = findings.map { finding -> { FindingRow(finding) } },
            )
        }
        val table = device.profile.portForwarding
        if (device.isGateway && (table != null || !snapshot)) {
            card(
                header = tr("Port forwarding"),
                key = "forwarding",
                footer = tr("Read from the router over UPnP. Rules made in the router's own settings may not be listed."),
                rows = buildList {
                    when {
                        table == null -> Unit
                        !table.supported -> add { NoteRow(tr("The router does not answer UPnP, so its forwardings cannot be read from here."), warning = false) }
                        table.mappings.isEmpty() -> add {
                            ListRow(
                                title = tr("No forwarded ports"),
                                leading = { IconTile(Icons.Rounded.Shield, Spectra.colors.success, size = 32.dp) },
                            )
                        }
                        else -> table.mappings.forEach { rule ->
                            add {
                                val target = lan.devices.firstOrNull { it.ipAddress == rule.internalClient }?.displayName ?: rule.internalClient
                                InfoRow(
                                    "${rule.proto} ${rule.externalPort}",
                                    listOfNotNull(
                                        "$target:${rule.internalPort}",
                                        rule.description,
                                        if (rule.enabled) null else tr("off"),
                                    ).joinToString(" · "),
                                    mono = false,
                                )
                            }
                        }
                    }
                    table?.externalIp?.let { ip -> add { InfoRow(tr("Public address"), ip, mono = true, copyable = true) } }
                    if (!snapshot) {
                        if (lan.checkingForwarding) {
                            add { ProgressRow(tr("Asking the router…"), null) }
                        } else {
                            add { ActionRow(tr("Check again"), Icons.Rounded.Refresh, onClick = { lan.checkPortForwarding() }, enabled = !lan.scanning) }
                        }
                    }
                },
            )
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
                                val web = device.webAddress(port.number)
                                val stream = if (web == null) device.streamAddress(port.number) else null
                                add {
                                    val notify = LocalNotify.current
                                    val copy = rememberCopier()
                                    PortRow(
                                        port,
                                        device,
                                        stream = stream != null,
                                        onOpen = if (stream != null) {
                                            { editableStream = stream }
                                        } else web?.let { address ->
                                            {
                                                try {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
                                                } catch (_: android.content.ActivityNotFoundException) {
                                                    // Android has no player for rtsp:// or rtmp:// of its own.
                                                    copy(tr("Stream address"), address)
                                                    notify(tr("No app here opens %s links. Install a player such as VLC; the address is copied.", address.substringBefore("://")))
                                                } catch (_: Exception) {
                                                }
                                            }
                                        },
                                        onCopy = stream?.let { address -> { copy(tr("Stream address"), address) } },
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PortRow(
    port: DiscoveredPort,
    device: LanDevice,
    stream: Boolean = false,
    onOpen: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
) {
    val detail = device.profile.banners.firstOrNull { it.port == port.number }?.summary
        ?: device.profile.rtsp.firstOrNull { it.port == port.number }?.summary
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onOpen != null) {
                    Modifier.combinedClickable(
                        onClickLabel = if (stream) tr("Open stream in a player") else tr("Open in browser"),
                        onClick = onOpen,
                        onLongClick = onCopy,
                    )
                } else {
                    Modifier
                },
            )
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
            Icon(
                if (stream) Icons.Rounded.PlayCircle else Icons.Rounded.OpenInBrowser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(Spectra.colors.success))
    }
}
