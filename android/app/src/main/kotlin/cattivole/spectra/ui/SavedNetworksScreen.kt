package cattivole.spectra.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.AuthMode
import cattivole.spectra.platform.WifiJoiner

/**
 * Joins the phone to a network the best way this Android allows: from 11 on, the system's "Save this network?"
 * sheet, so the network lands in Android's own list and the phone switches to it; before that, a suggestion or the
 * old configuration. [auth] is the scan's [AuthMode.raw], -1 when not known.
 */
@Composable
fun rememberWifiJoin(model: SpectraModel): (ssid: String, password: String?, auth: Int) -> Unit {
    val joiner = model.wifiJoiner
    val target = remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        target.value?.let { joiner.onAddResult(it, result.resultCode, result.data) }
    }
    return remember(joiner, launcher) {
        { ssid, password, auth ->
            // Only the old configuration knows WEP; the joiner says so when it cannot.
            val intent = if (auth == 1) null else joiner.addNetworkIntent(ssid, password, auth)
            if (intent != null) {
                target.value = ssid
                val launched = runCatching { launcher.launch(intent) }.isSuccess
                if (!launched) joiner.join(ssid, password, wep = false, auth = auth)
            } else {
                joiner.join(ssid, password, wep = auth == 1, auth = auth)
            }
        }
    }
}

/** Android's Wi-Fi panel over the app, to pick a saved network when the phone does not switch by itself. */
fun openWifiPanel(context: android.content.Context) {
    val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
    runCatching { context.startActivity(panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * The networks the app keeps: the passwords that put the phone or the sensor on a network, open networks it
 * joined, and ones added by hand. Android does not let apps read its own saved passwords, so this is the app's list.
 */
@Composable
fun SavedNetworksScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val link = model.link
    val saved = link.savedNetworks()
    val join = rememberWifiJoin(model)
    val context = LocalContext.current
    val copy = rememberCopier()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    // The networks heard now, by name: whether a saved one is in range, and its security for joining.
    val nearby = model.store.networks().values.filter { it.isInRange }
        .groupBy { it.revealedSsid ?: it.latest.ssid }
    if (adding || editing != null) {
        NetworkEditor(
            initial = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { ssid, password ->
                editing?.first?.takeIf { it != ssid }?.let(link::forgetSaved)
                link.saveNetwork(ssid, password)
                adding = false
                editing = null
            },
        )
    }
    SpectraScreen(
        title = tr("Saved networks"),
        onBack = nav::back,
        actions = {
            IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, contentDescription = tr("Add network")) }
        },
        overlay = {
            if (saved.isEmpty()) {
                EmptyState(
                    Icons.Rounded.WifiFind,
                    tr("No saved networks"),
                    tr("Networks the phone or the sensor joins through Spectra show up here with their passwords. Add one with +."),
                )
            }
        },
    ) {
        val status = model.wifiJoiner.status
        if (status is WifiJoiner.Status.Saved && !model.wifiJoiner.isOn(status.ssid)) {
            card(key = "saved-note", rows = listOf(
                { NoteRow(tr("“%s” is saved in Android. If the phone stays on another network, pick it in the Wi‑Fi list.", status.ssid), warning = false) },
                { ActionRow(tr("Open the Wi‑Fi list"), Icons.Rounded.Wifi, onClick = { openWifiPanel(context) }) },
            ))
        }
        cardSection(
            saved,
            header = if (saved.isEmpty()) null else tr("On this phone: %d", saved.size),
            footer = tr("Android keeps its own saved passwords out of reach of apps, so this list is Spectra's: what worked through it, and what you add. Passwords go to the sensor too."),
            key = { it.first },
            sectionKey = "saved",
            dividerInset = 70.dp,
        ) { (ssid, password) ->
            val here = nearby[ssid].orEmpty()
            SavedNetworkRow(
                ssid = ssid,
                password = password,
                inRange = here.isNotEmpty(),
                rssi = here.maxOfOrNull { it.latest.rssi },
                current = model.wifiJoiner.isOn(ssid),
                joining = (status as? WifiJoiner.Status.Joining)?.ssid == ssid,
                onConnect = { join(ssid, password.ifEmpty { null }, here.firstOrNull()?.latest?.auth?.raw ?: if (password.isEmpty()) 0 else -1) },
                onCopy = { copy(tr("Password"), password) },
                onEdit = { editing = ssid to password },
                onDelete = { link.forgetSaved(ssid) },
            )
        }
    }
}

@Composable
private fun SavedNetworkRow(
    ssid: String,
    password: String,
    inRange: Boolean,
    rssi: Int?,
    current: Boolean,
    joining: Boolean,
    onConnect: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var shown by rememberSaveable(ssid) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Box {
        ListRow(
            title = ssid,
            subtitle = when {
                password.isEmpty() -> tr("No password")
                shown -> password
                else -> "•".repeat(password.length.coerceIn(6, 12))
            },
            monoSubtitle = shown && password.isNotEmpty(),
            detail = when {
                current -> tr("Connected")
                joining -> tr("Connecting…")
                inRange -> tr("In range")
                else -> null
            },
            leading = {
                IconTile(
                    if (password.isEmpty()) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                    when {
                        current -> Spectra.colors.success
                        inRange -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
            },
            trailing = {
                Column(verticalArrangement = Arrangement.Center) {
                    if (password.isNotEmpty()) {
                        IconButton(onClick = { shown = !shown }) {
                            Icon(
                                if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (shown) tr("Hide password") else tr("Show password"),
                            )
                        }
                    } else if (rssi != null) {
                        SignalBars(rssi, size = 20.dp)
                    }
                }
            },
            onClick = { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!current) {
                DropdownMenuItem(
                    text = { Text(tr("Connect")) },
                    leadingIcon = { Icon(Icons.Rounded.Wifi, null) },
                    onClick = {
                        menu = false
                        onConnect()
                    },
                )
            }
            if (password.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(tr("Copy password")) },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = {
                        menu = false
                        onCopy()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(tr("Edit…")) },
                leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                onClick = {
                    menu = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun NetworkEditor(initial: Pair<String, String>?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var ssid by remember { mutableStateOf(initial?.first.orEmpty()) }
    var password by remember { mutableStateOf(initial?.second.orEmpty()) }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tr("Add network") else tr("Edit network")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it.take(32) },
                    label = { Text(tr("Network name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(63) },
                    label = { Text(tr("Password")) },
                    placeholder = { Text(tr("Empty for an open network")) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ssid.trim(), password) }, enabled = ssid.isNotBlank()) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
