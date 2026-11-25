package cattivole.spectra.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.WebLink
import cattivole.spectra.core.WebLinkIcon

/** Opens a web page in the browser. False when nothing here opens it. */
internal fun openWebPage(context: Context, address: String): Boolean =
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) }.isSuccess

/** The web pages of devices the user kept: a tap opens one in the browser. Mirrors the iOS `WebLinksView`. */
@Composable
fun WebLinksScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val links = model.webLinks.links
    val context = LocalContext.current
    val notify = LocalNotify.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    links.firstOrNull { it.id == editing }?.let { link ->
        WebLinkEditor(
            link,
            onDismiss = { editing = null },
            onSave = { name, address, icon ->
                model.editWebLink(link.id, name, address, icon)
                editing = null
            },
        )
    }
    // By network, the one the phone is on first.
    val current = model.webLinkNetwork.label
    val groups = links.groupBy { it.networkLabel }.toList().sortedBy { (label, _) -> label != current }
    SpectraScreen(
        title = tr("Web interfaces"),
        onBack = nav::back,
        overlay = {
            if (links.isEmpty()) {
                EmptyState(
                    Icons.Rounded.BookmarkBorder,
                    tr("No web interfaces"),
                    tr("Open a device on the LAN tab and tap the bookmark next to a port that has a web page."),
                )
            }
        },
    ) {
        groups.forEachIndexed { index, (label, group) ->
            cardSection(
                group,
                header = if (groups.size > 1) label ?: tr("Other network") else null,
                footer = if (index == groups.lastIndex) {
                    tr("Opens while the phone is on the same network. When the router gives a device another address, the next LAN scan updates its link.")
                } else {
                    null
                },
                key = { it.id },
                sectionKey = "links-${label.orEmpty()}",
                dividerInset = 70.dp,
            ) { link ->
                WebLinkRow(
                    link,
                    onOpen = { if (!openWebPage(context, link.address)) notify(tr("No app here opens web pages.")) },
                    onEdit = { editing = link.id },
                    onDelete = { model.removeWebLink(link.id) },
                )
            }
        }
    }
}

@Composable
private fun WebLinkRow(link: WebLink, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val copy = rememberCopier()
    ListRow(
        title = link.title,
        subtitle = link.shownAddress,
        subtitleLines = 1,
        leading = { IconTile(link.vector(), MaterialTheme.colorScheme.primary) },
        badges = {
            if (link.secure) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = "HTTPS",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        },
        trailing = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = tr("More"))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Copy address")) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = {
                            menu = false
                            copy(tr("Address"), link.address)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Edit…")) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
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
        },
        onClick = onOpen,
        onLongClick = { menu = true },
    )
}

/** Changes a link's name, icon and address, say to add the path of a login page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebLinkEditor(link: WebLink, onDismiss: () -> Unit, onSave: (String, String, String?) -> Unit) {
    var name by rememberSaveable(link.id) { mutableStateOf(link.name.orEmpty()) }
    var address by rememberSaveable(link.id) { mutableStateOf(link.address) }
    var icon by rememberSaveable(link.id) { mutableStateOf(link.customIcon) }
    // By the name being typed, then what the device and its page say about themselves.
    val suggestions = WebLinkIcon.suggestions(listOf(name, link.deviceName) + link.hints, link.port, own = ownIconId(link))
    val valid = WebLink.normalized(address) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        // The list of icons makes it tall: without this the keyboard covers the buttons.
        modifier = Modifier.safeDrawingPadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(tr("Link")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr("Name")) },
                    placeholder = { Text(link.deviceName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(tr("Address")) },
                    placeholder = { Text("http://…") },
                    isError = !valid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(tr("Icon"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconChoice(deviceIcon(link.icon), tr("Auto"), chosen = icon == null, captioned = true) { icon = null }
                    suggestions.forEach { item ->
                        IconChoice(item.vector(), tr(item.title), chosen = icon == item.id, captioned = true) { icon = item.id }
                    }
                }
                Text(tr("All icons"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WebLinkIcon.all.forEach { item ->
                        IconChoice(item.vector(), tr(item.title), chosen = icon == item.id, captioned = false) { icon = item.id }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, address, icon) }, enabled = valid) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** One icon to pick, with its name under it or only spoken. */
@Composable
private fun IconChoice(vector: ImageVector, title: String, chosen: Boolean, captioned: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.then(if (captioned) Modifier.width(68.dp) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (chosen) colors.primary else colors.surfaceContainerHighest)
                .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(vector, contentDescription = title, tint = if (chosen) colors.onPrimary else colors.primary)
        }
        if (captioned) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = if (chosen) colors.primary else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
