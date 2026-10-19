package cattivole.spectra.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.EspFlashFailure
import cattivole.spectra.core.EspFlashProgress
import cattivole.spectra.platform.UsbEspFlasher

@Composable
fun FlashScreen(model: SpectraModel, onBack: () -> Unit) {
    model.generation
    val flasher = model.usb
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<UsbEspFlasher.Board?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) {
            flasher.failed(EspFlashFailure.UNREADABLE)
        } else {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: "spectra-flash.zip"
            flasher.choose(name, bytes)
        }
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, flasher) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) flasher.refresh()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    confirm?.let { board ->
        val image = flasher.image
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Rounded.Usb, contentDescription = null) },
            title = { Text(tr("Flash this chip?")) },
            text = {
                Text(
                    tr(
                        "The bootloader and the firmware on %s are replaced (%s). Keep the cable connected until it finishes.",
                        board.title,
                        byteSize(image?.segments?.sumOf { it.data.size.toLong() } ?: 0),
                    ),
                )
            },
            confirmButton = {
                Button(onClick = {
                    flasher.flash(board.id)
                    confirm = null
                }) { Text(tr("Flash")) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Cancel")) } },
        )
    }
    SpectraScreen(
        title = tr("Flash over USB"),
        onBack = onBack,
        subtitle = tr("Blank ESP32-S3"),
    ) {
        flasher.progress?.let { update -> flashProgress(update, flasher) }
        val image = flasher.image
        card(
            header = tr("Firmware"),
            key = "flash-image",
            footer = tr("The bundle is the bootloader, the partition table and the application from pio run. firmware.bin on its own cannot start a blank chip."),
            rows = buildList {
                when {
                    image != null -> {
                        add { InfoRow(tr("Image"), flasher.chosenName ?: tr("Included firmware")) }
                        add { InfoRow(tr("Size"), byteSize(image.segments.sumOf { it.data.size.toLong() })) }
                    }
                    flasher.bundledKnown -> add {
                        NoteRow(tr("This build of the app has no firmware inside. Run pio run, rebuild the app, or pick a flash bundle."), warning = true)
                    }
                }
                add {
                    ActionRow(tr("Choose a flash bundle…"), Icons.Rounded.FolderOpen, onClick = {
                        picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    })
                }
                if (flasher.chosen != null && flasher.bundled != null) {
                    add { ActionRow(tr("Use the included firmware"), Icons.Rounded.Memory, onClick = flasher::useBundled) }
                }
            },
        )
        if (flasher.boards.isEmpty()) {
            card(
                header = tr("USB devices"),
                key = "flash-empty",
                footer = tr("Plug the Cardputer into this phone with a cable that carries data. A blank chip is already waiting. If a program is running, hold the G0 button, tap reset, and let go."),
                rows = listOf {
                    Text(
                        tr("Nothing is plugged in."),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        } else {
            cardSection(
                flasher.boards,
                header = tr("USB devices"),
                footer = tr("Plug the Cardputer into this phone with a cable that carries data. A blank chip is already waiting. If a program is running, hold the G0 button, tap reset, and let go."),
                key = { it.id },
                sectionKey = "flash-boards",
                dividerInset = 70.dp,
            ) { board ->
                ListRow(
                    title = board.title,
                    subtitle = board.port,
                    monoSubtitle = true,
                    leading = { IconTile(Icons.Rounded.Usb, MaterialTheme.colorScheme.primary) },
                    dimmed = flasher.progress?.isRunning == true,
                    onClick = when {
                        flasher.progress?.isRunning == true -> null
                        flasher.image == null -> ({ flasher.flash(board.id) })
                        else -> ({ confirm = board })
                    },
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.flashProgress(update: EspFlashProgress, flasher: UsbEspFlasher) {
    freeCard("flash-progress", header = tr("Flashing"), padding = PaddingValues(0.dp)) {
        when (val stage = update.stage) {
            EspFlashProgress.Stage.Preparing -> ProgressRow(tr("Preparing the chip…"), null)
            is EspFlashProgress.Stage.Writing -> {
                val fraction = if (stage.total > 0) stage.sent.toFloat() / stage.total else 0f
                ProgressRow(
                    tr("Writing"),
                    fraction,
                    tr("%s of %s", byteSize(stage.sent), byteSize(stage.total)),
                )
            }
            EspFlashProgress.Stage.Checking -> ProgressRow(tr("Checking the image…"), null)
            EspFlashProgress.Stage.Restarting -> ProgressRow(tr("Restarting the chip…"), null)
            EspFlashProgress.Stage.Done -> Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Spectra.colors.success, modifier = Modifier.size(22.dp))
                    Text(
                        tr("Firmware is on the chip. It will show up over Bluetooth in a moment. Tap reset if it does not start."),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            is EspFlashProgress.Stage.Failed -> Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Spectra.colors.danger, modifier = Modifier.size(22.dp))
                    Text(tr(stage.failure.message), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
        if (update.isRunning) {
            TextButton(onClick = flasher::cancel, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("Cancel flash")) }
        } else {
            TextButton(onClick = flasher::dismiss, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("Dismiss")) }
        }
        Spacer(Modifier.height(4.dp))
    }
}
