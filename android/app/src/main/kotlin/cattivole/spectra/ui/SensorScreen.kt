package cattivole.spectra.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import kotlinx.coroutines.delay
import cattivole.spectra.core.FirmwareUpdateProgress
import cattivole.spectra.core.SensorProtocol
import cattivole.spectra.platform.SensorLink

internal fun sensorHeadline(model: SpectraModel): String = when (val state = model.link.state) {
    is SensorLink.State.Connected -> state.name
    SensorLink.State.Demo -> tr("Demo sensor")
    is SensorLink.State.Connecting -> state.name
    SensorLink.State.Searching -> tr("Searching")
    SensorLink.State.PoweredOff -> tr("Bluetooth is off")
    SensorLink.State.Unauthorized -> tr("Bluetooth permission")
    SensorLink.State.Unsupported -> tr("No Bluetooth")
    SensorLink.State.Idle, SensorLink.State.Starting -> tr("No sensor")
}

internal fun batteryLine(level: Int, charging: Boolean): String = if (charging) tr("%d%%, charging", level) else "$level%"

/** The status line, where a bare percent would not say what it measures. */
private fun batterySummary(level: Int, charging: Boolean): String =
    if (charging) tr("Battery %d%%, charging", level) else tr("Battery %d%%", level)

internal fun sensorDetail(model: SpectraModel, showCharging: Boolean = true): String {
    val link = model.link
    val parts = listOfNotNull(
        if (model.store.isScanning) tr("Scanning") else null,
        link.batteryLevel?.let { batterySummary(it, showCharging && link.isCharging == true) },
    )
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    return when (link.state) {
        is SensorLink.State.Connected, SensorLink.State.Demo -> tr("Connected")
        is SensorLink.State.Connecting -> tr("Connecting…")
        SensorLink.State.Searching -> tr("Looking for a Cardputer")
        else -> tr("Tap to connect")
    }
}

internal fun intervalLabel(seconds: Int): String = when {
    seconds <= 0 -> tr("Only when asked")
    seconds % 60 == 0 -> tr("Every %d min", seconds / 60)
    else -> tr("Every %d s", seconds)
}

private class PendingFirmware(val name: String, val data: ByteArray)

@Composable
fun SensorScreen(model: SpectraModel, onFlash: () -> Unit = {}) {
    model.generation
    // USB attach is not always delivered while the screen is up, so the port is
    // re-read here. An unchanged list does not redraw.
    LaunchedEffect(Unit) {
        while (true) {
            model.usb.refresh()
            delay(1000)
        }
    }
    val link = model.link
    val context = LocalContext.current
    var pending by remember { mutableStateOf<PendingFirmware?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) {
            link.failFirmwareUpdate(tr("Could not read the firmware file."))
        } else {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: "firmware.bin"
            pending = PendingFirmware(name, bytes)
        }
    }
    pending?.let { firmware ->
        AlertDialog(
            onDismissRequest = { pending = null },
            icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
            title = { Text(tr("Install this firmware?")) },
            text = {
                Text(tr("%s (%s). The Cardputer reboots when the transfer finishes. Keep it powered and nearby.", firmware.name, byteSize(firmware.data.size.toLong())))
            },
            confirmButton = {
                Button(onClick = {
                    link.installFirmware(firmware.data)
                    pending = null
                }) { Text(tr("Install firmware")) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(tr("Cancel")) } },
        )
    }
    SpectraScreen(tr("Sensor")) {
        freeCard("status") { SensorStatus(model) }
        if (model.usb.boards.isNotEmpty()) {
            card(
                header = tr("New Cardputer"),
                key = "usb-flash",
                footer = tr("A blank ESP32-S3 does not speak Bluetooth yet. Connect it with a data cable and flash it from here. If a program is already running, hold G0, tap reset, and let go."),
                rows = listOf {
                    ActionRow(tr("Flash over USB"), Icons.Rounded.Usb, onClick = onFlash)
                },
            )
        }
        link.firmwareUpdate?.let { update -> firmwareCard(update, link) }
        if (link.state is SensorLink.State.Searching) {
            if (link.discovered.isEmpty()) {
                card(header = tr("Nearby sensors"), key = "nearby-empty", rows = listOf {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(tr("Looking for Cardputers running Spectra…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                })
            }
            cardSection(
                link.discovered,
                header = tr("Nearby sensors"),
                key = { it.address },
                sectionKey = "nearby",
                dividerInset = 70.dp,
            ) { sensor ->
                ListRow(
                    title = sensor.name,
                    subtitle = sensor.address,
                    monoSubtitle = true,
                    leading = { IconTile(Icons.Rounded.Memory, MaterialTheme.colorScheme.primary) },
                    trailing = { SignalBars(sensor.rssi + 20, size = 20.dp) },
                    onClick = { link.connect(sensor.address, sensor.name) },
                )
            }
        }
        if (link.isConnected) {
            card(
                header = tr("Scanning"),
                key = "scanning",
                rows = buildList {
                    add { IntervalRow(link) }
                    model.store.lastScanDurationMs?.let { add { InfoRow(tr("Last sweep"), tr("%.1f s", it / 1000.0)) } }
                    add { InfoRow(tr("Scans this session"), "${model.store.scanCount}") }
                },
            )
            card(
                header = tr("Alerts on sensor"),
                key = "alerts",
                footer = tr("The LED blinks red for a threat, yellow for a warning, orange for a tracker that keeps showing up, green for an open network, blue for a watched Bluetooth device and purple for a watched network."),
                rows = buildList { add { AlertRow(link) } },
            )
            link.info?.let { info ->
                val connected = link.state is SensorLink.State.Connected
                card(
                    header = tr("Device"),
                    key = "device",
                    footer = when {
                        connected && !info.ota -> tr("This firmware cannot take updates from the app. Connect the Cardputer with a USB cable and use Flash over USB, or use the launcher, to install 0.6.0 or newer once. After that, updates install from here.")
                        connected && info.ota -> tr("Choose firmware.bin from pio run, the same app image Launcher installs. A merged image with a bootloader is rejected.")
                        else -> null
                    },
                    rows = buildList {
                        add { InfoRow(tr("Firmware"), info.fw) }
                        add { InfoRow(tr("Reported interval"), intervalLabel(info.interval)) }
                        add { InfoRow(tr("Updates"), if (info.ota) tr("From the app") else tr("Not from the app")) }
                        link.batteryLevel?.let { level -> add { InfoRow(tr("Battery"), batteryLine(level, link.isCharging == true)) } }
                        add { InfoRow(tr("Chip"), info.chip) }
                        add { InfoRow(tr("Protocol"), "${info.proto}") }
                        add { InfoRow(tr("Bands"), info.bands.joinToString(", ") { tr("%s GHz", it) }) }
                        if (info.proto != SensorProtocol.VERSION) {
                            add {
                                NoteRow(
                                    tr("This firmware speaks protocol %d; the app expects %d. Update the one that is older.", info.proto, SensorProtocol.VERSION),
                                    warning = true,
                                )
                            }
                        }
                        if (connected && info.ota && link.firmwareUpdate?.isRunning != true) {
                            add {
                                ActionRow(tr("Choose a firmware file…"), Icons.Rounded.FolderOpen, onClick = {
                                    picker.launch(arrayOf("application/octet-stream", "*/*"))
                                })
                            }
                        }
                    },
                )
                if (info.lora || link.capFix != null) {
                    val fix = link.capFix
                    card(
                        header = tr("LoRa"),
                        key = "lora",
                        footer = tr("The Cardputer's Cap LoRa-1262 listens on EU868 and RU864 channels. A side-button save keeps the list and the GPS fix for when the phone reconnects."),
                        rows = buildList {
                            add { InfoRow(tr("Module"), tr("Cap LoRa-1262")) }
                            add {
                                InfoRow(
                                    tr("GPS"),
                                    if (fix == null) {
                                        tr("Searching for satellites")
                                    } else {
                                        tr(
                                            "%s · %d satellites",
                                            String.format(java.util.Locale.US, "%.5f, %.5f", fix.latitude, fix.longitude),
                                            fix.satellites,
                                        )
                                    },
                                )
                            }
                            if (link.state is SensorLink.State.Connected) {
                                if (link.loraScanning) {
                                    val tune = link.loraProgress
                                    add {
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            Text(
                                                tune?.line() ?: tr("Scanning the 864–870 MHz band…"),
                                                style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                                            )
                                        }
                                    }
                                } else {
                                    add {
                                        ActionRow(tr("Scan LoRa"), Icons.Rounded.CellTower, onClick = link::scanLora)
                                    }
                                }
                            }
                            if (link.loraDevices.isEmpty() && !link.loraScanning) {
                                add { NoteRow(tr("No LoRa devices yet. LoRa transmitters speak in short bursts, so a quiet pass is common indoors."), warning = false) }
                            }
                            link.loraDevices.forEach { device ->
                                val hex = "%08X".format(device.id and 0xFFFF_FFFFL)
                                val title = when {
                                    device.join -> tr("Join %s", hex)
                                    device.lorawan -> tr("DevAddr %s", hex)
                                    else -> tr("Packet %s", hex)
                                }
                                add {
                                    ListRow(
                                        title = title,
                                        subtitle = device.detail(),
                                        trailing = {
                                            Text(
                                                device.rssi.toString(),
                                                style = MaterialTheme.typography.titleMedium.merge(Tabular),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
//        card(
//            key = "demo",
//            footer = tr("Made-up networks with drifting signal, for trying the app without a Cardputer."),
//            rows = listOf {
//                val demo = link.state is SensorLink.State.Demo
//                Row(
//                    Modifier
//                        .fillMaxWidth()
//                        .toggleable(value = demo, role = Role.Switch, onValueChange = { link.setDemo(it) })
//                        .padding(horizontal = 16.dp, vertical = 8.dp),
//                    verticalAlignment = Alignment.CenterVertically,
//                    horizontalArrangement = Arrangement.spacedBy(14.dp),
//                ) {
//                    IconTile(Icons.Rounded.AutoAwesome, Spectra.colors.revealed, size = 36.dp)
//                    Text(tr("Demo sensor"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
//                    Switch(demo, null)
//                }
//            },
//        )
        freeCard("about", header = tr("About the sensor")) {
            Text(
                tr("Android lists nearby networks by itself, including 5 and 6 GHz. A Cardputer adds what a phone cannot hear: clients, probe requests, deauth frames, hidden names and traffic. Its ESP32-S3 hears the 2.4 GHz band only."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        card(
            header = tr("Supported boards"),
            key = "boards",
            rows = listOf(
                {
                    ListRow(
                        title = "M5Stack Cardputer",
                        subtitle = tr("ESP32-S3, 2.4 GHz"),
                        leading = { IconTile(Icons.Rounded.Memory, MaterialTheme.colorScheme.primary) },
                    )
                },
                {
                    ListRow(
                        title = "M5Stack Cardputer-Adv",
                        subtitle = tr("ESP32-S3, 2.4 GHz, LoRa cap fits this board"),
                        leading = { IconTile(Icons.Rounded.Memory, MaterialTheme.colorScheme.primary) },
                    )
                },
            ),
        )
    }
}

@Composable
private fun SensorStatus(model: SpectraModel) {
    val link = model.link
    val context = LocalContext.current
    val colors = Spectra.colors
    val (tint, title) = when (val state = link.state) {
        is SensorLink.State.Connected -> colors.success to state.name
        SensorLink.State.Demo -> colors.revealed to tr("Demo sensor")
        is SensorLink.State.Connecting -> MaterialTheme.colorScheme.primary to tr("Connecting to %s", state.name)
        SensorLink.State.Searching -> MaterialTheme.colorScheme.primary to tr("Searching…")
        SensorLink.State.PoweredOff -> colors.warning to tr("Bluetooth is off")
        SensorLink.State.Unauthorized -> colors.warning to tr("Bluetooth permission is off")
        SensorLink.State.Unsupported -> colors.danger to tr("No Bluetooth radio")
        SensorLink.State.Idle, SensorLink.State.Starting -> MaterialTheme.colorScheme.onSurfaceVariant to tr("Not connected")
    }
    val icon = when (link.state) {
        is SensorLink.State.Connected -> Icons.Rounded.CheckCircle
        SensorLink.State.Demo -> Icons.Rounded.AutoAwesome
        SensorLink.State.PoweredOff, SensorLink.State.Unauthorized, SensorLink.State.Unsupported -> Icons.Rounded.BluetoothDisabled
        else -> Icons.Rounded.SettingsInputAntenna
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(contentAlignment = Alignment.Center) {
            IconTile(icon, tint, size = 56.dp)
            if (link.state is SensorLink.State.Searching || link.state is SensorLink.State.Connecting) {
                CircularProgressIndicator(Modifier.size(64.dp), strokeWidth = 2.dp, color = tint)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(sensorDetail(model), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (link.state) {
            is SensorLink.State.Connected -> OutlinedButton(onClick = link::disconnect) { Text(tr("Disconnect")) }
            SensorLink.State.Searching -> OutlinedButton(onClick = link::stopSearching) { Text(tr("Stop")) }
            is SensorLink.State.Connecting -> OutlinedButton(onClick = link::disconnect) { Text(tr("Cancel")) }
            SensorLink.State.Unauthorized -> Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text(tr("Open Settings")) }
            SensorLink.State.PoweredOff -> Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text(tr("Bluetooth settings")) }
            SensorLink.State.Demo, SensorLink.State.Unsupported -> Unit
            SensorLink.State.Idle, SensorLink.State.Starting -> Button(onClick = link::startSearching) { Text(tr("Find sensor")) }
        }
    }
}

@Composable
private fun IntervalRow(link: SensorLink) {
    var open by remember { mutableStateOf(false) }
    Box {
        InfoRow(
            tr("Automatic scan"),
            intervalLabel(link.liveInterval),
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.CenterEnd)) {
            SensorLink.liveIntervals.forEach { seconds ->
                DropdownMenuItem(
                    text = { Text(intervalLabel(seconds)) },
                    leadingIcon = {
                        if (seconds == link.liveInterval) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        link.setLiveInterval(seconds)
                        open = false
                    },
                )
            }
        }
    }
}

private fun alertLabel(mode: SensorLink.AlertMode): String = when (mode) {
    SensorLink.AlertMode.SoundAndLight -> tr("Sound and light")
    SensorLink.AlertMode.LightOnly -> tr("Light only")
    SensorLink.AlertMode.SoundOnly -> tr("Sound only")
    SensorLink.AlertMode.Silent -> tr("Silent")
}

@Composable
private fun AlertRow(link: SensorLink) {
    var open by remember { mutableStateOf(false) }
    Box {
        InfoRow(
            tr("Mode"),
            alertLabel(link.alertMode),
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.CenterEnd)) {
            SensorLink.AlertMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(alertLabel(mode)) },
                    leadingIcon = {
                        if (mode == link.alertMode) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        link.setAlertMode(mode)
                        open = false
                    },
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.firmwareCard(update: FirmwareUpdateProgress, link: SensorLink) {
    freeCard("firmware", header = tr("Firmware update")) {
        when (val stage = update.stage) {
            FirmwareUpdateProgress.Stage.Preparing -> StageLine(tr("Preparing the sensor…"), busy = true)
            is FirmwareUpdateProgress.Stage.Uploading -> {
                val fraction = if (stage.total > 0) stage.sent.toFloat() / stage.total else 0f
                Row(Modifier.fillMaxWidth()) {
                    Text(tr("Sending"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        tr("%s of %s", byteSize(stage.sent.toLong()), byteSize(stage.total.toLong())),
                        style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            FirmwareUpdateProgress.Stage.Finishing -> StageLine(tr("Checking the image…"), busy = true)
            FirmwareUpdateProgress.Stage.Rebooting -> StageLine(tr("Waiting for the sensor to reboot…"), busy = true)
            is FirmwareUpdateProgress.Stage.Succeeded -> StageLine(
                if (stage.version.isEmpty()) tr("Firmware installed") else tr("Firmware installed: %s", stage.version),
                icon = Icons.Rounded.CheckCircle,
                tint = Spectra.colors.success,
            )
            is FirmwareUpdateProgress.Stage.Failed -> StageLine(tr(stage.message), icon = Icons.Rounded.ErrorOutline, tint = Spectra.colors.danger)
        }
        Spacer(Modifier.height(8.dp))
        if (update.isRunning && update.stage !is FirmwareUpdateProgress.Stage.Rebooting) {
            TextButton(onClick = link::cancelFirmwareUpdate) { Text(tr("Cancel update")) }
        } else if (!update.isRunning) {
            TextButton(onClick = link::dismissFirmwareUpdate) { Text(tr("Dismiss")) }
        }
    }
}

@Composable
private fun StageLine(text: String, busy: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, tint: Color = MaterialTheme.colorScheme.primary) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
