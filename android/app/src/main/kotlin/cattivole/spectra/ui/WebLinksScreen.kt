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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SwapVert
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.WebLink
import cattivole.spectra.core.WebLinkColor
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
            onSave = { name, address, icon, color ->
                model.editWebLink(link.id, name, address, icon, color)
                editing = null
            },
        )
    }
    val groups = model.webLinks.groups(model.webLinkNetwork)
    val canReorder = groups.any { it.links.size > 1 }
    var reordering by rememberSaveable { mutableStateOf(false) }
    if (!canReorder && reordering) reordering = false
    val listState = rememberLazyListState()
    val drag = remember(listState) { LinkDrag(listState) }
    SpectraScreen(
        title = tr("Web interfaces"),
        onBack = nav::back,
        listState = listState,
        actions = {
            if (canReorder) {
                IconButton(onClick = { reordering = !reordering }) {
                    if (reordering) {
                        Icon(Icons.Rounded.Check, contentDescription = tr("Done"), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(Icons.Rounded.SwapVert, contentDescription = tr("Change order"))
                    }
                }
            }
        },
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
        groups.forEachIndexed { index, (id, label, group) ->
            cardSection(
                group,
                header = if (groups.size > 1) label ?: tr("Other network") else null,
                footer = if (index == groups.lastIndex) {
                    tr("Opens while the phone is on the same network. When the router gives a device another address, the next LAN scan updates its link.")
                } else {
                    null
                },
                key = { it.id },
                sectionKey = "links-$id",
                dividerInset = 70.dp,
                cellModifier = { link ->
                    if (drag.id == link.id) {
                        // Follows the finger, over its neighbours; they move aside on their own.
                        Modifier.zIndex(1f).graphicsLayer { translationY = drag.offset }
                    } else {
                        Modifier.animateItem()
                    }
                },
            ) { link ->
                val ids = group.map { it.id }
                WebLinkRow(
                    link,
                    handle = if (reordering && group.size > 1) {
                        { DragHandle(link, ids, drag, onReorder = model::reorderWebLinks) }
                    } else {
                        null
                    },
                    onOpen = {
                        if (reordering) {
                            editing = link.id
                        } else if (!openWebPage(context, link.address)) {
                            notify(tr("No app here opens web pages."))
                        }
                    },
                    onEdit = { editing = link.id },
                    onDelete = { model.removeWebLink(link.id) },
                )
            }
        }
    }
}

@Composable
private fun WebLinkRow(
    link: WebLink,
    /** Stands in for the menu while the links are being put in order. */
    handle: (@Composable () -> Unit)?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val copy = rememberCopier()
    ListRow(
        title = link.title,
        subtitle = link.shownAddress,
        subtitleLines = 1,
        leading = { IconTile(link.vector(), WebLinkColor.of(link.color).shown()) },
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
        trailing = handle ?: {
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
        onLongClick = if (handle == null) ({ menu = true }) else null,
    )
}

/** A row being dragged to another place among its network's links. */
private class LinkDrag(private val list: LazyListState) {
    var id by mutableStateOf<String?>(null)
        private set

    /** How far the row is drawn from the place it holds in the list. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** Where the row goes with the next layout; until then positions are out of date. */
    private var expected: Int? = null

    fun start(link: String) {
        id = link
        offset = 0f
        expected = null
    }

    fun stop() {
        id = null
        offset = 0f
        expected = null
    }

    /** Moves the row by [delta]; past a neighbour's middle, returns the new order. */
    fun move(delta: Float, ids: List<String>): List<String>? {
        val dragged = id ?: return null
        offset += delta
        val visible = list.layoutInfo.visibleItemsInfo
        val here = visible.firstOrNull { it.key == dragged } ?: return null
        if (expected != null && here.index != expected) return null
        expected = null
        val top = here.offset + offset
        val bottom = top + here.size
        val others = visible.filter { it.key != dragged && it.key in ids }
        // The furthest neighbour whose middle the row's edge has passed.
        val target = others.lastOrNull { it.index > here.index && bottom > it.offset + it.size / 2 }
            ?: others.firstOrNull { it.index < here.index && top < it.offset + it.size / 2 }
            ?: return null
        val place = if (target.index > here.index) target.offset + target.size - here.size else target.offset
        offset += here.offset - place
        expected = target.index
        // The list would otherwise scroll along with its first row.
        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        return ids.filter { it != dragged }.toMutableList().apply { add(ids.indexOf(target.key), dragged) }
    }
}

@Composable
private fun DragHandle(link: WebLink, ids: List<String>, drag: LinkDrag, onReorder: (List<String>) -> Unit) {
    val order by rememberUpdatedState(ids)
    val reorder by rememberUpdatedState(onReorder)
    val haptics = LocalHapticFeedback.current
    val up = tr("Move up")
    val down = tr("Move down")
    fun shift(by: Int): Boolean {
        val at = order.indexOf(link.id)
        if (at + by !in order.indices) return false
        reorder(order.toMutableList().apply { add(at + by, removeAt(at)) })
        return true
    }
    Box(
        Modifier
            .size(40.dp)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(up) { shift(-1) },
                    CustomAccessibilityAction(down) { shift(1) },
                )
            }
            .pointerInput(link.id) {
                detectVerticalDragGestures(
                    onDragStart = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        drag.start(link.id)
                    },
                    onDragEnd = drag::stop,
                    onDragCancel = drag::stop,
                ) { change, delta ->
                    change.consume()
                    drag.move(delta, order)?.let(reorder)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.DragHandle, contentDescription = tr("Change order"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Changes a link's name, address and how its icon looks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebLinkEditor(link: WebLink, onDismiss: () -> Unit, onSave: (String, String, String?, WebLinkColor?) -> Unit) {
    var name by rememberSaveable(link.id) { mutableStateOf(link.name.orEmpty()) }
    var address by rememberSaveable(link.id) { mutableStateOf(link.address) }
    var icon by rememberSaveable(link.id) { mutableStateOf(link.customIcon) }
    var color by rememberSaveable(link.id) { mutableStateOf(WebLinkColor.of(link.color)) }
    val tint = color.shown()
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
                    IconChoice(deviceIcon(link.icon), tr("Auto"), tint, chosen = icon == null, captioned = true) { icon = null }
                    suggestions.forEach { item ->
                        IconChoice(item.vector(), tr(item.title), tint, chosen = icon == item.id, captioned = true) { icon = item.id }
                    }
                }
                Text(tr("Color"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ColorChoice(null, chosen = color == null) { color = null }
                    WebLinkColor.entries.forEach { option -> ColorChoice(option, chosen = color == option) { color = option } }
                }
                Text(tr("All icons"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WebLinkIcon.all.forEach { item ->
                        IconChoice(item.vector(), tr(item.title), tint, chosen = icon == item.id, captioned = false) { icon = item.id }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, address, icon, color) }, enabled = valid) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** One colour to pick; null is the app's own. */
@Composable
private fun ColorChoice(option: WebLinkColor?, chosen: Boolean, onClick: () -> Unit) {
    val color = option.shown()
    val title = tr(option?.title ?: "Auto")
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(if (chosen) Modifier.border(2.5.dp, color, CircleShape) else Modifier)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = title },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30.dp).background(color, CircleShape))
    }
}

/** One icon to pick, with its name under it or only spoken. */
@Composable
private fun IconChoice(vector: ImageVector, title: String, tint: Color, chosen: Boolean, captioned: Boolean, onClick: () -> Unit) {
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
                .background(colors.surfaceContainerHighest)
                // An outline rather than a fill: white on yellow could not be made out.
                .then(if (chosen) Modifier.border(2.5.dp, tint, RoundedCornerShape(12.dp)) else Modifier)
                .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(vector, contentDescription = title, tint = tint)
        }
        if (captioned) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = if (chosen) colors.onSurface else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
