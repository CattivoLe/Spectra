package cattivole.spectra.assistant.ui

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.EngineController
import alexcattivo.localchat.kit.Chat
import alexcattivo.localchat.kit.ChatSession
import alexcattivo.localchat.kit.SearchNotice
import alexcattivo.localchat.kit.StoredMessage
import alexcattivo.localchat.kit.web.ResearchStage
import alexcattivo.localchat.kit.web.SearchPayload
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.assistant.AssistantModel
import cattivole.spectra.assistant.AssistantStarters
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
    fun chat(id: UUID)
    fun models()
    fun internet()
    fun dossier()
}

/** The Assistant tab: chats, and the way to models, internet search and the dossier. */
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
    val changes = assistant.store.changes.collectAsStateValue()
    LaunchedEffect(changes) { chats = runCatching { assistant.store.chats() }.getOrDefault(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text(tr("Delete all chats?")) },
            text = { Text(tr("Every chat is erased from this phone. Models, the dossier and settings stay.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmErase = false
                    scope.launch { assistant.eraseChats() }
                }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text(tr("Cancel")) } },
        )
    }
    val newChat: () -> Unit = {
        scope.launch {
            val chat = assistant.store.createChat()
            nav.chat(chat.id)
        }
    }
    val noModel = assistant.assistantModelFile == null
    SpectraScreen(
        title = tr("Assistant"),
        subtitle = assistant.currentModelName,
        actions = {
            if (!noModel) IconButton(onClick = newChat) { Icon(Icons.Rounded.Add, contentDescription = tr("New chat")) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = tr("More")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("Model…")) }, leadingIcon = { Icon(Icons.Rounded.Memory, null) }, onClick = {
                        menu = false
                        nav.models()
                    })
                    DropdownMenuItem(text = { Text(tr("Internet…")) }, leadingIcon = { Icon(Icons.Rounded.Public, null) }, onClick = {
                        menu = false
                        nav.internet()
                    })
                    DropdownMenuItem(text = { Text(tr("Dossier…")) }, leadingIcon = { Icon(Icons.Rounded.Book, null) }, onClick = {
                        menu = false
                        nav.dossier()
                    })
                    if (chats.isNotEmpty()) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(tr("Delete all chats"), color = MaterialTheme.colorScheme.error) },
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
                chats.isEmpty() -> EmptyState(
                    Icons.Rounded.AutoAwesome,
                    tr("Ask about what Spectra found"),
                    tr("The assistant reads the app's own scans and can run fresh ones: Wi‑Fi, LAN, Bluetooth, the connection. It runs on this phone."),
                    action = tr("New chat"),
                    onAction = newChat,
                )
            }
        },
    ) {
        assistant.storageWarning?.let { warning -> card(key = "storage", rows = listOf { NoteRow(warning, warning = true) }) }
        assistant.loadError?.let { error -> card(key = "load-error", rows = listOf { NoteRow(tr("Could not load the model: %s", error), warning = true) }) }
        if (chats.isNotEmpty()) {
            cardSection(chats, key = { it.id.toString() }, sectionKey = "chats", dividerInset = 70.dp) { chat ->
                ListRow(
                    title = chat.title.ifEmpty { tr("New chat") },
                    subtitle = listOfNotNull(ago(chat.updatedAt), chat.modelName).joinToString(" · "),
                    leading = { IconTile(Icons.Rounded.AutoAwesome, Spectra.colors.revealed) },
                    onClick = { nav.chat(chat.id) },
                    onLongClick = { scope.launch { assistant.store.deleteChat(chat.id) } },
                )
            }
            item(key = "hint") { cattivole.spectra.ui.SectionFooter(tr("Hold a chat to delete it.")) }
        }
    }
}

@Composable
private fun kotlinx.coroutines.flow.StateFlow<Int>.collectAsStateValue(): Int {
    val value by androidx.compose.runtime.produceState(this.value, this) { collect { value = it } }
    return value
}

/** One chat with the assistant. */
@Composable
fun AssistantChatScreen(model: SpectraModel, nav: AssistantNav, chatId: UUID) {
    val assistant = model.assistant
    LaunchedEffect(Unit) { assistant.ensureStarted() }
    var session by remember { mutableStateOf<ChatSession?>(null) }
    LaunchedEffect(chatId) {
        val chat = assistant.store.chat(chatId) ?: return@LaunchedEffect nav.back()
        session = assistant.session(chat).apply { load() }
    }
    val current = session ?: return
    ChatContent(model, assistant, current, nav)
}

@Composable
private fun ChatContent(model: SpectraModel, assistant: AssistantModel, session: ChatSession, nav: AssistantNav) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<StoredMessage?>(null) }
    val listState = rememberLazyListState()
    val engine = assistant.controller.state
    LaunchedEffect(assistant.selectedModel, assistant.library.installed) { session.modelName = assistant.currentModelName }
    LaunchedEffect(engine) { if (engine == EngineController.State.Ready) session.refreshContext() }
    // A question another screen queued (a ✨ button) is sent as soon as the model is there.
    LaunchedEffect(session, engine) {
        if (engine == EngineController.State.Ready || engine == EngineController.State.UnloadedForMemory) {
            assistant.takeQueuedQuestion(session.chat.id)?.let(session::send)
        }
    }
    assistant.pendingSearch?.let { SearchApprovalDialog(it, assistant.web.provider.title) }
    DisposableEffect(session) { onDispose { session.stop() } }
    LaunchedEffect(session.messages.size) { if (session.messages.isNotEmpty()) listState.scrollToItem(0) }
    val ready = engine != EngineController.State.Empty && engine != EngineController.State.Loading
    SpectraScreen(
        title = session.chat.title.ifEmpty { tr("New chat") },
        subtitle = when (engine) {
            EngineController.State.Loading -> tr("Loading the model…")
            EngineController.State.Empty -> if (assistant.assistantModelFile == null) tr("No model yet") else null
            else -> assistant.currentModelName
        } ?: assistant.currentModelName,
        onBack = nav::back,
        actions = { ModelMenu(assistant, nav) },
        fill = {
            Column(Modifier.fillMaxSize().imePadding()) {
                session.context?.let { usage ->
                    val fraction = usage.fraction.toFloat()
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        color = if (usage.isOverflowing) Spectra.colors.danger else MaterialTheme.colorScheme.primary,
                        // One solid bar: Material 3's default gap at the indicator and dot at the end read as glitches here.
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (session.messages.isEmpty()) {
                        Starters(model, assistant, ready) { session.send(it) }
                    } else {
                        LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
                        ) {
                            session.errorText?.let { error ->
                                item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                            session.searchNotice?.let { notice ->
                                item { Text(noticeText(notice), color = Spectra.colors.warning, style = MaterialTheme.typography.bodySmall) }
                            }
                            session.searchReused?.let { (query, date) ->
                                item {
                                    Text(
                                        tr("Used an earlier search, «%s» (%s): nothing was sent.", query, dateTime(date)),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (session.isSearching) item { BusyLine(stageText(session.searchStage)) }
                            itemsIndexed(session.messages.asReversed(), key = { _, message -> message.id }) { reversedIndex, message ->
                                val index = session.messages.lastIndex - reversedIndex
                                val question = if (index > 0 && message.role == ChatMessage.Role.Assistant) session.messages[index - 1] else null
                                MessageRow(
                                    message = message,
                                    status = if (index == session.messages.lastIndex) session.toolStatus else null,
                                    tools = session.toolRuns[message.id].orEmpty(),
                                    readFrom = question?.let { session.attachments[it.id]?.topics }.orEmpty(),
                                    sources = question?.searchJson?.let(SearchPayload::fromJson),
                                    isLast = index == session.messages.lastIndex,
                                    isGenerating = session.isGenerating,
                                    onCopy = { copy(context, message.content) },
                                    onEdit = {
                                        editing = message
                                        draft = message.content
                                    },
                                    onRegenerate = session::regenerate,
                                    onContinue = session::continueGenerating,
                                    offers = if (ready) session.offers[message.id].orEmpty() else emptyList(),
                                    onOffer = session::send,
                                )
                            }
                        }
                    }
                }
                Composer(
                    draft = draft,
                    onDraft = { draft = it },
                    editing = editing != null,
                    isGenerating = session.isGenerating,
                    canSend = draft.isNotBlank() && ready,
                    onCancelEdit = {
                        editing = null
                        draft = ""
                    },
                    onStop = session::stop,
                    onSend = {
                        val target = editing
                        if (target != null) session.edit(target.id, draft) else session.send(draft)
                        editing = null
                        draft = ""
                    },
                )
            }
        },
    )
}

/** The opening questions of an empty chat, picked from what the app can work with right now. */
@Composable
private fun Starters(model: SpectraModel, assistant: AssistantModel, ready: Boolean, onAsk: (String) -> Unit) {
    val questions = remember(model.generation) { AssistantStarters.questions(assistant.sources.readiness()) }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
    ) {
        if (assistant.assistantModelFile == null) {
            Text(tr("Download the assistant's model under Model to start."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        // A failed load leaves the engine Empty: say so here instead of an endless "Loading…".
        val failed = assistant.loadError?.takeIf { assistant.controller.state == EngineController.State.Empty }
        if (failed != null) {
            Text(tr("Could not load the model: %s", failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else if (!ready) {
            BusyLine(tr("Loading the model…"))
        }
        Text(tr("Try asking"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (question in questions) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().clickable(enabled = ready) { onAsk(question) },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Spectra.colors.revealed, modifier = Modifier.size(18.dp))
                    Text(question, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** The assistant has one model: no picker, only the way to its page (download, delete, free up space). */
@Composable
private fun ModelMenu(assistant: AssistantModel, nav: AssistantNav) {
    IconButton(onClick = { nav.models() }) { Icon(Icons.Rounded.Memory, contentDescription = tr("Model")) }
}

@Composable
private fun BusyLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MessageRow(
    message: StoredMessage,
    status: String?,
    tools: List<String>,
    readFrom: List<String>,
    sources: SearchPayload?,
    isLast: Boolean,
    isGenerating: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    offers: List<alexcattivo.localchat.kit.tools.ToolOffer> = emptyList(),
    onOffer: (String) -> Unit = {},
) {
    if (message.role == ChatMessage.Role.User) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 320.dp).clickable(enabled = !isGenerating, onClick = onEdit),
            ) {
                Text(message.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (message.content.isEmpty() && isLast && isGenerating) {
            BusyLine(status?.let(cattivole.spectra.assistant.AssistantActions::runningLabel) ?: tr("Thinking…"))
        } else {
            MarkdownText(message.content)
            if (status != null) BusyLine(cattivole.spectra.assistant.AssistantActions.runningLabel(status))
        }
        val notes = buildList {
            if (readFrom.isNotEmpty()) add(tr("Read from Spectra: %s", readFrom.joinToString(", ")))
            if (tools.isNotEmpty()) add(tr("Ran: %s", tools.distinct().joinToString(", ", transform = cattivole.spectra.assistant.AssistantActions::title)))
            sources?.let { add(tr("Web: %d sources for «%s»", it.results.size, it.query)) }
            message.modelName?.let(::add)
        }
        if (notes.isNotEmpty()) {
            Text(notes.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The answer suggested a check: offered as a question to tap instead of a tool name to type.
        if (isLast && !isGenerating) {
            for (offer in offers) {
                val request = offer.request ?: continue
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.clickable { onOffer(request) },
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = Spectra.colors.revealed, modifier = Modifier.size(16.dp))
                        Text(offer.button.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (!(isLast && isGenerating) && message.content.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.ContentCopy, tr("Copy"), Modifier.size(18.dp)) }
                if (isLast) {
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Refresh, tr("Answer again"), Modifier.size(18.dp)) }
                    if (message.interrupted) TextButton(onClick = onContinue) { Text(tr("Continue")) }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    editing: Boolean,
    isGenerating: Boolean,
    canSend: Boolean,
    onCancelEdit: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (editing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(tr("Editing the question"), Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = onCancelEdit) { Icon(Icons.Rounded.Close, tr("Cancel")) }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = { Text(tr("Ask about your network…")) },
                    modifier = Modifier.weight(1f),
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp),
                )
                if (isGenerating) {
                    IconButton(onClick = onStop) { Icon(Icons.Rounded.StopCircle, tr("Stop"), tint = MaterialTheme.colorScheme.primary) }
                } else {
                    IconButton(onClick = onSend, enabled = canSend) { Icon(Icons.AutoMirrored.Filled.Send, tr("Send")) }
                }
            }
        }
    }
}

@Composable
private fun SearchApprovalDialog(pending: AssistantModel.PendingSearch, provider: String) {
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

private fun stageText(stage: ResearchStage?): String = when (stage) {
    ResearchStage.Planning -> tr("Writing the search query…")
    ResearchStage.Searching, null -> tr("Searching the web…")
    ResearchStage.Checking -> tr("Checking whether that is enough…")
    is ResearchStage.Retrying -> tr("Searching again: «%s»…", stage.query)
    is ResearchStage.Reading -> tr("Reading pages: %d…", stage.pages)
    ResearchStage.Selecting -> tr("Picking what matters…")
}

private fun noticeText(notice: SearchNotice): String = when (notice) {
    SearchNotice.NotNeeded -> tr("This question did not need the internet.")
    SearchNotice.VoiceNeedsNoConfirmation, SearchNotice.Cancelled -> tr("Search cancelled: answering without the internet.")
    is SearchNotice.Unavailable -> tr("Search is not set up: %s.", notice.error.message ?: "")
    is SearchNotice.NothingFound -> tr("Nothing found for «%s». Answering without the internet.", notice.query)
    is SearchNotice.Failed -> tr("Search failed: %s Answering without the internet.", notice.error.message ?: notice.error.javaClass.simpleName)
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Spectra", text))
}

/** Models to download and choose from. */
/**
 * The assistant's model: one, chosen with tools/assistant-eval. Download it (or delete it), optionally add the
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
            card(
                header = tr("Search service"),
                key = "provider",
                footer = when (web.provider) {
                    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Searxng -> tr("Real web search through a SearXNG server you provide (ideally your own). The server must have the JSON format enabled.")
                    alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Brave -> tr("Real Brave web search. Needs your API key (there is a free plan). The key is kept encrypted on this phone.")
                },
                rows = buildList {
                    for (kind in alexcattivo.localchat.kit.web.WebAccess.ProviderKind.entries) {
                        add {
                            ListRow(
                                title = kind.title,
                                leading = { if (web.provider == kind) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) else Box(Modifier.size(24.dp)) },
                                onClick = { web.provider = kind },
                            )
                        }
                    }
                    add {
                        when (web.provider) {
                            alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Searxng -> OutlinedTextField(
                                value = web.searxngAddress,
                                onValueChange = { web.searxngAddress = it.trim() },
                                label = { Text(tr("Server address")) },
                                placeholder = { Text("https://…") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                            alexcattivo.localchat.kit.web.WebAccess.ProviderKind.Brave -> OutlinedTextField(
                                value = web.braveKey,
                                onValueChange = { web.braveKey = it.trim() },
                                label = { Text(tr("API key")) },
                                singleLine = true,
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

/** What the assistant keeps between chats. */
@Composable
fun AssistantDossierScreen(model: SpectraModel, nav: AssistantNav) {
    val memory = model.assistant.memory
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Clear the dossier?")) },
            text = { Text(tr("The notes and what the assistant learned about each device are erased.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    memory.removeAll()
                }) { Text(tr("Clear all"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Cancel")) } },
        )
    }
    SpectraScreen(
        tr("Dossier"),
        onBack = nav::back,
        actions = {
            if (memory.notes.isNotEmpty() || memory.baselines.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, tr("Clear all")) }
            }
        },
        overlay = {
            if (memory.notes.isEmpty()) {
                EmptyState(
                    Icons.Rounded.Book,
                    tr("No notes yet"),
                    tr("The assistant saves important findings here during chats: suspicious devices, weak passwords, conclusions."),
                )
            }
        },
    ) {
        if (memory.notes.isNotEmpty()) {
            cardSection(
                memory.notes.sortedByDescending { it.createdAt },
                footer = tr("Hold a note to delete it."),
                key = { it.id.toString() },
                sectionKey = "notes",
            ) { note ->
                ListRow(
                    title = note.text,
                    subtitle = listOfNotNull(note.subject, dateTime(note.createdAt)).joinToString(" · "),
                    onLongClick = { memory.remove(note.id) },
                )
            }
        }
        val learned = memory.baselines.values.filter { it.isEstablished }
        if (learned.isNotEmpty()) {
            item(key = "learned") {
                cattivole.spectra.ui.SectionFooter(tr("Devices the assistant knows the usual state of: %d.", learned.size))
            }
        }
    }
}
