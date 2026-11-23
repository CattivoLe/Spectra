package cattivole.spectra.assistant

import alexcattivo.localchat.engine.EngineConfig
import alexcattivo.localchat.engine.EngineController
import alexcattivo.localchat.engine.LlamaEngine
import alexcattivo.localchat.kit.ChatStore
import alexcattivo.localchat.kit.CipherSqlDatabase
import alexcattivo.localchat.kit.InstalledModel
import alexcattivo.localchat.kit.KeystoreKeyProvider
import alexcattivo.localchat.kit.KeystoreSecretStore
import alexcattivo.localchat.kit.ModelCatalog
import alexcattivo.localchat.kit.ModelLibrary
import alexcattivo.localchat.kit.SettingsStore
import alexcattivo.localchat.kit.web.LazyEmbedder
import alexcattivo.localchat.kit.web.WebAccess
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cattivole.spectra.ui.tr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Settings of the kit (web search, lock) in SharedPreferences. */
class PreferencesSettings(private val prefs: SharedPreferences) : SettingsStore {
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()
}

/**
 * Shared state of the Assistant tab: the store of saved summaries, the on-device engine (llama.cpp, from LocalChat) and the model
 * library. Mirrors AssistantModel.swift. Created lazily: nothing is loaded until the tab or a ✨ button needs it.
 */
class AssistantModel(context: Context, private val scope: CoroutineScope, val sources: AssistantSources) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    private val base = File(app.filesDir, "assistant").apply { mkdirs() }
    private val modelsDirectory = File(base, "models").apply { mkdirs() }

    // Computed system prompts are kept in the cache folder: a card loads its prompt instead of reading it again.
    val controller = EngineController(LlamaEngine(app.applicationInfo.nativeLibraryDir, File(app.cacheDir, "prompt-cache"), promptCacheFiles = PROMPT_CACHE_FILES))
    val store: ChatStore
    val library: ModelLibrary
    val web = WebAccess(PreferencesSettings(prefs), KeystoreSecretStore(File(base, "secrets")))
    val actions = AssistantActions(sources)
    /** The tap when an answer or a card text is finished. */
    val haptics = cattivole.spectra.platform.Haptics(app)
    /** The ✨ summary cards: findings at once, the model's plain words under them, buttons instead of a text field. */
    val cards by lazy { AssistantCardRunner(this, store, controller, sources, actions, scope) }

    /** The folder every summary card is saved in: the Assistant tab lists it as the journal. Made on first use. */
    suspend fun summariesFolder(): UUID {
        prefs.getString(SUMMARIES_FOLDER, null)?.let { saved ->
            val id = runCatching { UUID.fromString(saved) }.getOrNull()
            if (id != null && store.folders().any { it.id == id }) return id
        }
        val folder = store.createFolder(tr("Summaries"))
        prefs.edit().putString(SUMMARIES_FOLDER, folder.id.toString()).apply()
        return folder.id
    }

    /** The saved folder id, without making one: how the tab tells summaries from old chats. */
    val summariesFolderId: UUID? get() = prefs.getString(SUMMARIES_FOLDER, null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /**
     * The folder new summaries go to: the last one the user made or chose, until another is made or chosen.
     * Null means the Summaries folder. Shown on the Assistant tab so it is clear where checks are being recorded.
     */
    var recordingFolderId by mutableStateOf(prefs.getString(RECORDING_FOLDER, null)?.let { runCatching { UUID.fromString(it) }.getOrNull() })
        private set

    fun recordInto(folder: UUID?) {
        recordingFolderId = folder
        prefs.edit().putString(RECORDING_FOLDER, folder?.toString()).apply()
    }

    /** Where the next summary is saved: the recording folder while it exists, else Summaries. */
    suspend fun recordingFolder(): UUID {
        recordingFolderId?.let { id -> if (store.folders().any { it.id == id }) return id }
        if (recordingFolderId != null) recordInto(null)
        return summariesFolder()
    }

    /** A new folder that new summaries are saved in from now on. */
    suspend fun startFolder(name: String): UUID {
        val folder = store.createFolder(name)
        recordInto(folder.id)
        return folder.id
    }

    /** The name of the folder a summary is in, for its screen's subtitle. */
    suspend fun folderNameOf(chat: UUID): String? {
        val folder = store.chat(chat)?.folderID ?: return null
        return store.folders().firstOrNull { it.id == folder }?.name
    }

    /** A query waiting for the user's OK before it goes to the internet. */
    var pendingSearch by mutableStateOf<PendingSearch?>(null)
        private set

    class PendingSearch(val query: String, private val answer: CompletableDeferred<String?>) {
        fun answer(edited: String?) {
            answer.complete(edited)
        }
    }

    var selectedModel by mutableStateOf(prefs.getString(SELECTED_MODEL, null))
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    /** When the database cannot be opened the tab runs on a temporary in-memory one and says so. */
    var storageWarning by mutableStateOf<String?>(null)
        private set

    val currentModelName: String? get() = library.installed.firstOrNull { it.fileName == selectedModel }?.displayName

    /** The one model the assistant runs (chosen by comparing small models on Spectra's own tasks): the catalog's only chat model. */
    val assistantCatalogModel: alexcattivo.localchat.kit.CatalogModel? get() = library.catalog.firstOrNull { !it.isEmbedding }

    /** That model's file, once it is downloaded. */
    val assistantModelFile: alexcattivo.localchat.kit.InstalledModel?
        get() = assistantCatalogModel?.let { wanted -> library.installed.firstOrNull { it.catalog?.id == wanted.id } }

    /** Model files from before the assistant settled on one model: nothing loads them, they only take space. */
    val otherModels: List<alexcattivo.localchat.kit.InstalledModel> get() = library.installed.filter { it.catalog == null }

    val physicalMemoryBytes: Long =
        ActivityManager.MemoryInfo().also { (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.totalMem

    init {
        val databaseFile = File(base, "chats.sqlite")
        store = try {
            // Encrypted (SQLCipher, AES-256) with a key wrapped by the Android Keystore, as in LocalChat.
            ChatStore(CipherSqlDatabase.open(databaseFile, KeystoreKeyProvider(File(base, "database.key"), "Spectra.assistant-database").key()))
        } catch (error: Throwable) {
            storageWarning = tr("Could not open the saved summaries: %s. New ones will not be saved.", error.localizedMessage ?: error.toString())
            ChatStore(CipherSqlDatabase.inMemory())
        }
        val catalogJson = runCatching { app.assets.open("AssistantModels.json").bufferedReader().use { it.readText() } }.getOrDefault("{\"models\":[]}")
        library = ModelLibrary(modelsDirectory, ModelCatalog.decode(catalogJson), scope, isMetered = ::isMetered)
        library.wifiOnly = prefs.getBoolean(WIFI_ONLY, true)
        library.refreshInstalled()
        library.onInstalledChanged = {
            refreshModels()
            if (controller.state == EngineController.State.Empty) scope.launch { loadSelectedModel() }
        }
        library.onActiveDownloadsChanged = { active -> onDownloadsActive(active) }
        // The dossier is gone: its notes and per-device baselines (up to half a megabyte) are not kept any more.
        java.io.File(base, "memory.json").delete()
        // "Send this query to the internet?": closing the sheet without choosing sends nothing.
        web.approve = { query ->
            val answer = CompletableDeferred<String?>()
            pendingSearch = PendingSearch(query, answer)
            try {
                answer.await()
            } finally {
                pendingSearch = null
            }
        }
        scope.launch { removeOldChats() }
    }

    /** Set by the app: keeps a foreground service alive while a multi-gigabyte model downloads. */
    var onDownloadsActive: (Boolean) -> Unit = {}

    private var started = false

    /** Loads the model the first time anything needs the assistant: its tab, or a ✨ summary from another screen. */
    fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            refreshModels()
            loadSelectedModel()
        }
    }

    suspend fun deleteChat(id: UUID) {
        cards.forget(id)
        store.deleteChat(id)
    }

    /**
     * The assistant was a chat before it became summary cards. Those chats are erased once, with whatever they
     * searched: nothing shows them any more. Summaries and folders stay.
     */
    private suspend fun removeOldChats() {
        if (prefs.getBoolean(OLD_CHATS_REMOVED, false)) return
        runCatching {
            val old = store.chats().filterNot { CardSubjects.isCard(it.systemPrompt) }
            for (chat in old) store.deleteChat(chat.id)
            if (old.isNotEmpty()) store.vacuum()
            android.util.Log.i("SpectraAssistant", "old chats removed: ${old.size}, summaries kept: ${store.chats().size}")
        }.onSuccess { prefs.edit().putBoolean(OLD_CHATS_REMOVED, true).apply() }
    }

    fun refreshModels() {
        library.refreshInstalled()
        updateEmbedder()
        // Always the assistant's own model: a model picked before there was only one is never loaded again.
        val wanted = assistantModelFile?.fileName
        if (selectedModel != wanted) setSelected(wanted)
    }

    private var embedderFile: String? = null

    /** The model for search by meaning joins the web search as soon as it is installed; it loads at the first search. */
    private fun updateEmbedder() {
        val file = library.embeddingModel?.fileName
        if (file == embedderFile) return
        embedderFile = file
        web.embedder = file?.let { LazyEmbedder(File(modelsDirectory, it).path, app.applicationInfo.nativeLibraryDir) }
    }

    private fun setSelected(file: String?) {
        selectedModel = file
        prefs.edit().putString(SELECTED_MODEL, file).apply()
    }

    fun setWifiOnly(value: Boolean) {
        library.wifiOnly = value
        prefs.edit().putBoolean(WIFI_ONLY, value).apply()
    }

    val wifiOnly: Boolean get() = library.wifiOnly

    /** Deletes a model file. If it is loaded, it is unloaded first. */
    suspend fun delete(file: InstalledModel) {
        if (file.fileName == selectedModel) {
            controller.unload()
            setSelected(null)
        }
        library.delete(file)
        if (controller.state == EngineController.State.Empty) loadSelectedModel()
    }

    suspend fun loadSelectedModel() {
        val file = selectedModel ?: return
        loadError = null
        try {
            // 8192, as in LocalChat: a deep analysis carries the wide data, a check result and web excerpts at once.
            controller.load(File(modelsDirectory, file).path, EngineConfig(contextLength = 8192))
        } catch (error: Throwable) {
            loadError = error.localizedMessage ?: error.toString()
        }
    }

    /** Erases every summary and folder. Models and settings stay. */
    suspend fun eraseChats() {
        cards.forgetAll()
        store.deleteEverything()
    }

    fun handleMemoryWarning() {
        scope.launch { controller.handleMemoryWarning() }
    }

    private fun isMetered(): Boolean {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        /** The engine and the encrypted chat store need Android 12. */
        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 31

        /** System prompts kept computed on disk: one per card action and subject in recent use. */
        const val PROMPT_CACHE_FILES = 6

        private const val SUMMARIES_FOLDER = "summariesFolder"
        private const val RECORDING_FOLDER = "recordingFolder"
        private const val SELECTED_MODEL = "selectedModel"
        private const val WIFI_ONLY = "wifiOnly"
        private const val OLD_CHATS_REMOVED = "oldChatsRemoved"
    }
}
