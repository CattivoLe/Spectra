package cattivole.spectra.assistant.ui

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.EngineController
import alexcattivo.localchat.kit.Chat
import alexcattivo.localchat.kit.ChatFolder
import alexcattivo.localchat.kit.StoredMessage
import alexcattivo.localchat.kit.web.SearchPayload
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.assistant.AssistantCards
import cattivole.spectra.assistant.AssistantModel
import cattivole.spectra.assistant.CardRisk
import cattivole.spectra.assistant.CardSubjects
import cattivole.spectra.assistant.AssistantText
import cattivole.spectra.assistant.AssistantTopic
import cattivole.spectra.ui.Pill
import cattivole.spectra.ui.ActionRow
import cattivole.spectra.ui.EmptyState
import cattivole.spectra.ui.IconTile
import cattivole.spectra.ui.InfoRow
import cattivole.spectra.ui.ListRow
import cattivole.spectra.ui.LocalNotify
import cattivole.spectra.ui.NoteRow
import cattivole.spectra.ui.ProgressRow
import cattivole.spectra.ui.Spectra
import cattivole.spectra.ui.SpectraScreen
import cattivole.spectra.ui.ago
import cattivole.spectra.ui.byteSize
import cattivole.spectra.ui.card
import cattivole.spectra.ui.cardSection
import cattivole.spectra.ui.dateTime
import cattivole.spectra.ui.tr
import kotlinx.coroutines.launch
import java.util.UUID

/** Where the assistant screens go. Set up by the app's navigation. */
interface AssistantNav {
    fun back()
    /** A summary card, live or saved. */
    fun card(id: UUID)
    fun models()
    fun internet()
}

/**
 * The Assistant tab: the journal of summary cards (✨ on a screen makes one), in folders, and the way to the model
 * and internet search.
 */
@Composable
fun AssistantHomeScreen(model: SpectraModel, nav: AssistantNav) {
    if (!AssistantModel.isSupported) {
        SpectraScreen(tr("Assistant"), overlay = {
            EmptyState(Icons.Rounded.AutoAwesome, tr("Assistant"), tr("The on-device assistant needs Android 12 or newer."))
        })
        return
    }
    val assistant = model.assistant
    LaunchedEffect(Unit) { assistant.ensureStarted() }
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf<List<Chat>>(emptyList()) }
    var folders by remember { mutableStateOf<List<ChatFolder>>(emptyList()) }
    val changes = assistant.store.changes.collectAsStateValue()
    /** Each saved card's worst finding, for the color of its row. */
    var risks by remember { mutableStateOf<Map<UUID, CardRisk>>(emptyMap()) }
    /** Summaries whose last text was cut short: they get "Continue". */
    var unfinished by remember { mutableStateOf<Set<UUID>>(emptySet()) }
    LaunchedEffect(changes) {
        // Only summaries: chats from before the cards are erased at start, and are not shown meanwhile.
        chats = runCatching { assistant.store.chats().filter { CardSubjects.isCard(it.systemPrompt) } }.getOrDefault(emptyList())
        folders = runCatching { assistant.store.folders() }.getOrDefault(emptyList())
        val messages = chats.associate { chat -> chat.id to runCatching { assistant.store.messages(chat.id) }.getOrDefault(emptyList()) }
        risks = chats.associate { chat ->
            val head = messages[chat.id].orEmpty().firstOrNull { it.role == ChatMessage.Role.User }
            chat.id to (head?.content?.let(AssistantCards::parse)?.second?.let(AssistantCards::worst) ?: CardRisk.OK)
        }
        unfinished = chats.filter { chat ->
            val last = messages[chat.id].orEmpty().lastOrNull()
            last?.role == ChatMessage.Role.Assistant && last.interrupted
        }.map { it.id }.toSet()
    }
    val recording = assistant.recordingFolderId?.takeIf { id -> folders.any { it.id == id } } ?: assistant.summariesFolderId
    val openToContinue: (Chat) -> Unit = { chat ->
        assistant.cards.continueOnOpen(chat.id)
        nav.card(chat.id)
    }
    val notify = LocalNotify.current
    // The folder shown; null is every summary. Kept as text so it survives the tab being recreated.
    var shownFolder by rememberSaveable { mutableStateOf<String?>(null) }
    val folder = folders.firstOrNull { it.id.toString() == shownFolder }
    androidx.activity.compose.BackHandler(enabled = folder != null) { shownFolder = null }
    var menu by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    /** The folder dialog: a new one (null id) or renaming one. */
    var naming by remember { mutableStateOf<Pair<ChatFolder?, UUID?>?>(null) }
    var moving by remember { mutableStateOf<Chat?>(null) }
    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text(tr("Delete all summaries?")) },
            text = { Text(tr("Every summary and folder is erased from this phone. Models and settings stay.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmErase = false
                    shownFolder = null
                    scope.launch { assistant.eraseChats() }
                }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text(tr("Cancel")) } },
        )
    }
    naming?.let { (renamed, chatToFile) ->
        FolderNameDialog(
            // A new folder is named by its date and time unless the user writes something else.
            initial = renamed?.name ?: folderDateName(),
            title = if (renamed == null) tr("New folder") else tr("Rename folder"),
            onDismiss = { naming = null },
            onSave = { name ->
                naming = null
                scope.launch {
                    if (renamed != null) {
                        assistant.store.renameFolder(renamed.id, name)
                    } else {
                        // From now on new summaries are saved in the new folder.
                        val created = assistant.startFolder(name)
                        if (chatToFile != null) assistant.store.moveChat(chatToFile, created) else shownFolder = created.toString()
                    }
                }
            },
        )
    }
    moving?.let { chat ->
        MoveChatDialog(
            chat = chat,
            folders = folders,
            onDismiss = { moving = null },
            onMove = { target ->
                moving = null
                scope.launch { assistant.store.moveChat(chat.id, target) }
            },
            onNewFolder = {
                moving = null
                naming = null to chat.id
            },
        )
    }
    val noModel = assistant.assistantModelFile == null
    val shown = if (folder == null) chats else chats.filter { it.folderID == folder.id }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    SpectraScreen(
        title = tr("Assistant"),
        subtitle = assistant.currentModelName,
        actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = tr("More")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (folder != null) {
                        DropdownMenuItem(text = { Text(tr("Rename folder")) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = {
                            menu = false
                            naming = folder to null
                        })
                        DropdownMenuItem(
                            text = { Text(tr("Delete folder"), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.FolderOff, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                shownFolder = null
                                scope.launch { assistant.store.deleteFolder(folder.id) }
                            },
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(text = { Text(tr("Model…")) }, leadingIcon = { Icon(Icons.Rounded.Memory, null) }, onClick = {
                        menu = false
                        nav.models()
                    })
                    DropdownMenuItem(text = { Text(tr("Internet…")) }, leadingIcon = { Icon(Icons.Rounded.Public, null) }, onClick = {
                        menu = false
                        nav.internet()
                    })
                    if (chats.isNotEmpty()) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(tr("Delete all summaries"), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                confirmErase = true
                            },
                        )
                    }
                }
            }
        },
        overlay = {
            when {
                noModel -> EmptyState(
                    Icons.Rounded.AutoAwesome,
                    tr("No model yet"),
                    tr("The assistant runs a language model on this phone: nothing you ask leaves it. Download it once to start."),
                    action = tr("Download the model"),
                    onAction = nav::models,
                )
                folder != null && shown.isEmpty() -> EmptyState(
                    Icons.Rounded.Folder,
                    tr("The folder is empty"),
                    if (folder.id == recording) tr("New ✨ summaries will be saved here.") else tr("Hold a summary in the list and choose Move to folder."),
                )
                chats.isEmpty() -> EmptyState(
                    Icons.Rounded.AutoAwesome,
                    tr("No summaries yet"),
                    tr("Tap ✨ on a Wi‑Fi network, the LAN list, a device or recorded traffic: the findings show at once, and the model on this phone puts them into plain words."),
                )
            }
        },
    ) {
        assistant.storageWarning?.let { warning -> card(key = "storage", rows = listOf { NoteRow(warning, warning = true) }) }
        assistant.loadError?.let { error -> card(key = "load-error", rows = listOf { NoteRow(tr("Could not load the model: %s", error), warning = true) }) }
        if (!noModel) {
            item(key = "folders") {
                // Tap: show that folder's summaries right here (tap again for all). Hold: new summaries go there.
                FolderStrip(
                    folders, chats, recording, shown = folder?.id,
                    onOpen = { picked -> shownFolder = if (folder?.id == picked.id) null else picked.id.toString() },
                    onRecord = { picked ->
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        assistant.recordInto(picked.id)
                        notify(tr("New summaries are saved to «%s»", picked.name))
                    },
                    onNew = { naming = null to null },
                )
            }
            if (folders.isNotEmpty()) {
                item(key = "folders-hint") {
                    cattivole.spectra.ui.SectionFooter(
                        tr("New summaries go to the highlighted folder. Hold another folder to save there."),
                        Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
        cardSection(shown, header = tr("Summaries"), key = { it.id.toString() }, sectionKey = "summaries") { chat ->
            SummaryRow(
                chat = chat,
                risk = risks[chat.id] ?: CardRisk.OK,
                onOpen = { nav.card(chat.id) },
                onContinue = if (chat.id in unfinished || assistant.cards.isStopped(chat.id)) ({ openToContinue(chat) }) else null,
                onMove = { moving = chat },
                onDelete = { scope.launch { assistant.deleteChat(chat.id) } },
            )
        }
        if (shown.isNotEmpty()) {
            item(key = "hint") { cattivole.spectra.ui.SectionFooter(tr("Hold a row to move it to a folder or delete it.")) }
        }
    }
}

/**
 * The folders as a row of chips above the summaries, with the count in each and a way to add one. The folder new
 * summaries go to is filled with the accent colour; the one shown below has an outline.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderStrip(
    folders: List<ChatFolder>,
    chats: List<Chat>,
    recording: UUID?,
    shown: UUID?,
    onOpen: (ChatFolder) -> Unit,
    onRecord: (ChatFolder) -> Unit,
    onNew: () -> Unit,
) {
    val counts = chats.groupingBy { it.folderID }.eachCount()
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (folder in folders) {
            val active = folder.id == recording
            val open = folder.id == shown
            val content = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                border = if (open) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { onOpen(folder) }, onLongClick = { onRecord(folder) }),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (open) Icons.Rounded.FolderOpen else Icons.Rounded.Folder, null, Modifier.size(18.dp), tint = if (active) content else MaterialTheme.colorScheme.primary)
                    Text(folder.name, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                    Text("${counts[folder.id] ?: 0}", style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.7f))
                }
            }
        }
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onNew)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CreateNewFolder, tr("New folder"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * One summary in the list. No icon: the whole width goes to its title (two lines), with the worst finding as a
 * coloured label and when it was made.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SummaryRow(
    chat: Chat,
    risk: CardRisk,
    onOpen: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onContinue: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                chat.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(risk.label(), risk.color())
                Text(
                    ago(chat.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            // Stopped in the middle of a text: one tap opens it and picks the text up.
            if (onContinue != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(top = 2.dp).clickable(onClick = onContinue),
                ) {
                    Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(tr("Continue"), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(tr("Move to folder…")) }, leadingIcon = { Icon(Icons.Rounded.DriveFileMove, null) }, onClick = {
                menu = false
                onMove()
            })
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

/** «22 ноября, 14:30»: a new folder's suggested name, so a round of checks is easy to find later. */
private fun folderDateName(now: Long = System.currentTimeMillis()): String =
    java.text.SimpleDateFormat("d MMMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now))

@Composable
private fun FolderNameDialog(initial: String, title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text(tr("Name")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun MoveChatDialog(chat: Chat, folders: List<ChatFolder>, onDismiss: () -> Unit, onMove: (UUID?) -> Unit, onNewFolder: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Move to folder")) },
        text = {
            Column {
                @Composable
                fun option(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                option(tr("No folder"), Icons.Rounded.FolderOff, chat.folderID == null) { onMove(null) }
                for (folder in folders) option(folder.name, Icons.Rounded.Folder, chat.folderID == folder.id) { onMove(folder.id) }
                option(tr("New folder…"), Icons.Rounded.CreateNewFolder, false, onNewFolder)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun kotlinx.coroutines.flow.StateFlow<Int>.collectAsStateValue(): Int {
    val value by androidx.compose.runtime.produceState(this.value, this) { collect { value = it } }
    return value
}

@Composable
internal fun SearchApprovalDialog(pending: AssistantModel.PendingSearch, provider: String) {
    var query by remember(pending) { mutableStateOf(pending.query) }
    AlertDialog(
        onDismissRequest = { pending.answer(null) },
        title = { Text(tr("Send this query to the internet?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Will be sent to “%s”", provider), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { pending.answer(query) }, enabled = query.isNotBlank()) { Text(tr("Send")) } },
        dismissButton = { TextButton(onClick = { pending.answer(null) }) { Text(tr("Don't send")) } },
    )
}

/**
 * The assistant's model: one, chosen by comparing small models on Spectra's own tasks. Download it (or delete it), optionally add the
 * small model that helps web search rank pages by meaning, and clear out models left from before there was one.
 */
@Composable
fun AssistantModelsScreen(model: SpectraModel, nav: AssistantNav) {
    val assistant = model.assistant
    val library = assistant.library
    val scope = rememberCoroutineScope()
    var confirmDownload by remember { mutableStateOf<alexcattivo.localchat.kit.CatalogModel?>(null) }
    confirmDownload?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            icon = { Icon(Icons.Rounded.Warning, null) },
            title = { Text(item.localizedName) },
            text = { Text(tr(item.localizedNotice ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownload = null
                    library.download(item)
                }) { Text(tr("Download")) }
            },
            dismissButton = { TextButton(onClick = { confirmDownload = null }) { Text(tr("Cancel")) } },
        )
    }

    /** A downloaded file: its size, and a way to delete it. */
    fun installedRow(installed: alexcattivo.localchat.kit.InstalledModel, subtitle: String?): @Composable () -> Unit = {
        ListRow(
            title = installed.displayName,
            subtitle = subtitle,
            detail = byteSize(installed.sizeBytes),
            leading = { IconTile(Icons.Rounded.Check, Spectra.colors.success) },
            trailing = {
                IconButton(onClick = { scope.launch { assistant.delete(installed) } }) {
                    Icon(Icons.Rounded.Delete, tr("Delete"), tint = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    /** A catalog entry not on the phone yet: what it is and its download, with pause, cancel and the checksum step. */
    fun downloadRow(item: alexcattivo.localchat.kit.CatalogModel): @Composable () -> Unit = {
        val state = library.downloads[item.id]
        val fit = alexcattivo.localchat.kit.ModelCatalog.fit(item.sizeBytes, assistant.physicalMemoryBytes)
        ListRow(
            title = item.localizedName,
            subtitle = tr(item.summary),
            detail = when (state) {
                is alexcattivo.localchat.kit.DownloadState.Downloading ->
                    tr("Downloading: %s of %s", byteSize(state.received), byteSize(state.total))
                is alexcattivo.localchat.kit.DownloadState.Paused -> tr("Paused at %s", byteSize(state.received))
                is alexcattivo.localchat.kit.DownloadState.Failed -> tr("Download failed: %s", state.reason.toString())
                alexcattivo.localchat.kit.DownloadState.Verifying -> tr("Checking the file…")
                else -> byteSize(item.sizeBytes) + when (fit) {
                    alexcattivo.localchat.kit.ModelFit.Comfortable -> ""
                    alexcattivo.localchat.kit.ModelFit.Tight -> " · " + tr("tight on this phone's memory")
                    alexcattivo.localchat.kit.ModelFit.TooBig -> " · " + tr("too big for this phone")
                }
            },
            leading = { IconTile(Icons.Rounded.Download, MaterialTheme.colorScheme.secondary) },
            trailing = {
                when (state) {
                    is alexcattivo.localchat.kit.DownloadState.Downloading -> Row {
                        IconButton(onClick = { library.pause(item) }) { Icon(Icons.Rounded.Pause, tr("Pause")) }
                        IconButton(onClick = { library.cancel(item) }) { Icon(Icons.Rounded.Close, tr("Cancel")) }
                    }
                    alexcattivo.localchat.kit.DownloadState.Verifying -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> IconButton(onClick = {
                        if (item.localizedNotice != null) confirmDownload = item else library.download(item)
                    }) { Icon(Icons.Rounded.Download, tr("Download")) }
                }
            },
        )
    }

    SpectraScreen(tr("Model"), onBack = nav::back) {
        val chat = assistant.assistantCatalogModel
        if (chat != null) {
            val installed = assistant.assistantModelFile
            card(
                header = tr("Assistant"),
                footer = tr("Downloaded once from Hugging Face, then it works on this phone without the internet. The file is checked against its checksum."),
                key = "assistant",
                dividerInset = 70.dp,
                rows = listOf(if (installed != null) installedRow(installed, tr(chat.summary)) else downloadRow(chat)),
            )
        }
        library.catalog.firstOrNull { it.isEmbedding }?.let { helper ->
            val installed = library.installed.firstOrNull { it.catalog?.id == helper.id }
            card(
                header = tr("Search by meaning (optional)"),
                key = "embedding",
                dividerInset = 70.dp,
                rows = listOf(if (installed != null) installedRow(installed, tr(helper.summary)) else downloadRow(helper)),
            )
        }
        val others = assistant.otherModels
        if (others.isNotEmpty()) {
            cardSection(
                others,
                header = tr("Other models on this phone"),
                footer = tr("The assistant no longer uses them. Delete them to free up space."),
                key = { it.fileName },
                sectionKey = "others",
                dividerInset = 70.dp,
            ) { other ->
                ListRow(
                    title = other.displayName,
                    detail = byteSize(other.sizeBytes),
                    leading = { IconTile(Icons.Rounded.Memory, MaterialTheme.colorScheme.outline) },
                    trailing = {
                        IconButton(onClick = { scope.launch { assistant.delete(other) } }) {
                            Icon(Icons.Rounded.Delete, tr("Delete"), tint = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            }
        }
        card(key = "wifi-only", footer = tr("A model is several gigabytes: on mobile data the download waits for Wi‑Fi unless this is off."), rows = listOf {
            SwitchLine(tr("Download over Wi‑Fi only"), assistant.wifiOnly) { assistant.setWifiOnly(it) }
        })
    }
}

@Composable
private fun SwitchLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Web search: whether the assistant may search, through which service, and what went out. */
@Composable
fun AssistantInternetScreen(model: SpectraModel, nav: AssistantNav) {
    val assistant = model.assistant
    val web = assistant.web
    val scope = rememberCoroutineScope()
    val notify = LocalNotify.current
    val changes = assistant.store.changes.collectAsStateValue()
    var log by remember { mutableStateOf<List<alexcattivo.localchat.kit.SearchLogEntry>>(emptyList()) }
    var cached by remember { mutableStateOf(0) }
    LaunchedEffect(changes) {
        log = runCatching { assistant.store.searchLog() }.getOrDefault(emptyList())
        cached = runCatching { assistant.store.searchCacheCount() }.getOrDefault(0)
    }
    SpectraScreen(tr("Internet"), onBack = nav::back) {
        card(
            key = "enabled",
            footer = tr("Off by default. When on, a question about something the app cannot measure (what WPS is, which firmware is vulnerable) may be searched on the web. Questions about your own network never are."),
            rows = listOf { SwitchLine(tr("Search the web"), web.isEnabled) { web.isEnabled = it } },
        )
        if (web.isEnabled) {
            val kinds = alexcattivo.localchat.kit.web.WebAccess.ProviderKind.entries
            card(
                header = tr("Search service"),
                key = "provider",
                footer = providerNote(web.provider),
                rows = buildList {
                    for (kind in kinds) {
                        add {
                            ListRow(
                                title = kind.title,
                                subtitle = providerLine(kind),
                                leading = { if (web.provider == kind) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) else Box(Modifier.size(24.dp)) },
                                onClick = { web.provider = kind },
                            )
                        }
                    }
                    add {
                        if (web.provider == alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Searxng) {
                            OutlinedTextField(
                                value = web.searxngAddress,
                                onValueChange = { web.searxngAddress = it.trim() },
                                label = { Text(tr("Server address")) },
                                placeholder = { Text("https://…") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        } else {
                            val kind = web.provider
                            OutlinedTextField(
                                value = web.key(kind),
                                onValueChange = { web.setKey(kind, it.trim()) },
                                label = { Text(tr("%s API key", kind.title)) },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                },
            )
            card(header = tr("Privacy"), key = "confirm", rows = listOf(
                { SwitchLine(tr("Confirm each search"), web.confirmEachSearch) { web.confirmEachSearch = it } },
                { SwitchLine(tr("Read the pages found"), web.readPages) { web.readPages = it } },
            ))
            card(
                header = tr("Saved searches"),
                key = "cache",
                footer = tr("A similar question within a day reuses an earlier search: nothing is sent again."),
                rows = listOf(
                    { InfoRow(tr("Kept"), "$cached") },
                    {
                        ActionRow(tr("Forget saved searches"), Icons.Rounded.DeleteSweep, enabled = cached > 0, onClick = {
                            scope.launch {
                                assistant.store.clearSearchCache()
                                cached = 0
                                notify(tr("Saved searches forgotten"))
                            }
                        })
                    },
                ),
            )
        }
        if (log.isNotEmpty()) {
            cardSection(
                log,
                header = tr("What was sent"),
                footer = tr("Hold an entry to delete it."),
                key = { it.id.toString() },
                sectionKey = "log",
            ) { entry ->
                ListRow(
                    title = entry.query,
                    subtitle = listOf(dateTime(entry.date), entry.provider, if (entry.succeeded) tr("sent") else tr("failed")).joinToString(" · "),
                    onLongClick = { scope.launch { assistant.store.deleteSearchLogEntry(entry.id) } },
                )
            }
        }
    }
}

/** One line under a service's name: what it is good for. */
private fun providerLine(kind: alexcattivo.localchat.kit.web.WebAccess.ProviderKind): String = when (kind) {
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Searxng -> tr("Your own metasearch server, no key")
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Brave -> tr("Independent index, web and pictures")
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Tavily -> tr("Made for AI: page text and pictures")
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Exa -> tr("Search by meaning, page text")
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Firecrawl -> tr("Search with pages read in full, pictures")
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.You -> tr("Own index, page text")
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Serper -> tr("Google results and Google Images")
}

/** The chosen service in more words: what it sends, what it returns, where its key comes from. */
private fun providerNote(kind: alexcattivo.localchat.kit.web.WebAccess.ProviderKind): String = when (kind) {
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Searxng -> tr("Real web search through a SearXNG server you provide (ideally your own). The server must have the JSON format enabled.")
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Brave -> tr("Real Brave web search. Needs your API key (there is a free plan). The key is kept encrypted on this phone.")
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Tavily -> tr("A search service built for language models: it reads the pages itself and returns their text, and finds pictures. Key at tavily.com, with a free monthly allowance. The sites see Tavily, not this phone.")
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Exa -> tr("Finds pages by meaning rather than exact words, which helps with odd model names; returns the pages' text and their preview pictures. Key at exa.ai; new accounts get free credits.")
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Firecrawl -> tr("Searches and reads each page into clean text, and has an image search. Key at firecrawl.dev. The sites see Firecrawl, not this phone.")
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.You -> tr("You.com's own web index; returns the pages' text. Pictures are the pages' previews. Key at you.com/platform, with free credits.")
    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Serper -> tr("Google's results and Google Images through one key: the best pictures of devices. Key at serper.dev, 2,500 free searches to start. The pages themselves are read by this phone.")
}.let { note ->
    // SearXNG takes no key and Brave's own note already says where it is kept.
    if (kind.keyAccount == null || kind == alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Brave) note
    else note + " " + tr("Only the query is sent; the key is kept encrypted on this phone.")
}
