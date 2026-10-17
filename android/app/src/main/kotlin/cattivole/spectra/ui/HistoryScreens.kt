package cattivole.spectra.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.HistoryEntry

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "lan" -> Icons.Rounded.Lan
    "bluetooth" -> Icons.Rounded.Bluetooth
    else -> Icons.Rounded.Wifi
}

@Composable
private fun kindTint(kind: String): Color = when (kind) {
    "lan" -> Spectra.colors.good
    "bluetooth" -> Spectra.colors.info
    else -> MaterialTheme.colorScheme.primary
}

private fun kindTitle(kind: String): String = when (kind) {
    "lan" -> "LAN"
    "bluetooth" -> "Bluetooth"
    else -> "Wi‑Fi"
}

/** The phone saves under its own name; translate it, and leave a Cardputer's name alone. */
private fun sourceName(source: String): String = when (source) {
    "This phone", "This iPhone" -> tr("This phone")
    else -> source
}

@Composable
fun HistoryScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val history = model.history
    var confirmClear by remember { mutableStateOf(false) }
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
    SpectraScreen(
        title = tr("History"),
        actions = {
            if (history.entries.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, contentDescription = tr("Clear")) }
            }
        },
        overlay = {
            if (history.entries.isEmpty()) {
                EmptyState(
                    Icons.Rounded.History,
                    tr("Nothing saved"),
                    tr("Save what Wi‑Fi, LAN or Bluetooth just found. The Cardputer's side button saves its own scan, and it arrives here when the phone connects."),
                )
            }
        },
    ) {
        for (kind in listOf("networks", "lan", "bluetooth")) {
            val rows = history.entries.filter { it.kind == kind }
            cardSection(
                rows,
                header = kindTitle(kind),
                key = { it.id },
                sectionKey = kind,
                dividerInset = 70.dp,
            ) { entry ->
                HistoryRow(entry, onOpen = { nav.history(entry.id) }, onDelete = { history.delete(entry.id) })
            }
        }
        if (history.entries.isNotEmpty()) {
            item(key = "hint") { SectionFooter(tr("Swipe a saved scan to the left to delete it.")) }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
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
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
            ListRow(
                title = sourceName(entry.source),
                subtitle = "${ago(entry.savedAt)} · ${dateTime(entry.savedAt)}",
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
