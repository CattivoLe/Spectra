package alexcattivo.localchat.kit

import alexcattivo.localchat.engine.ChatMessage
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Хранилище диалогов на SQLite. Всё лежит на устройстве, в облако ничего не уходит. Схема та же, что в iOS. */
class ChatStore(
    private val database: SqlDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val _changes = MutableStateFlow(0)
    /** Растёт при каждом изменении: по нему список диалогов перечитывается (как ValueObservation в iOS). */
    val changes: StateFlow<Int> = _changes

    init {
        migrate()
    }

    private suspend fun <T> read(block: SqlDatabase.() -> T): T = withContext(io) { lock.withLock { database.block() } }

    private suspend fun <T> write(block: SqlDatabase.() -> T): T = withContext(io) {
        lock.withLock { database.transaction { database.block() } }.also { _changes.value++ }
    }

    // MARK: - Схема

    private fun migrate() {
        val migrations: List<SqlDatabase.() -> Unit> = listOf(
            {
                execute("CREATE TABLE chat (id TEXT PRIMARY KEY, title TEXT NOT NULL, systemPrompt TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                execute("""CREATE TABLE message (id TEXT PRIMARY KEY, chatID TEXT NOT NULL REFERENCES chat(id) ON DELETE CASCADE,
                    seq INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL,
                    interrupted INTEGER NOT NULL DEFAULT 0, UNIQUE(chatID, seq))""")
                execute("CREATE INDEX message_on_chatID ON message(chatID)")
            },
            {
                execute("""CREATE TABLE persona (id TEXT PRIMARY KEY, name TEXT NOT NULL, avatar TEXT NOT NULL, systemPrompt TEXT NOT NULL,
                    temperature REAL NOT NULL, maxTokens INTEGER NOT NULL, isBuiltIn INTEGER NOT NULL DEFAULT 0,
                    sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL)""")
                execute("ALTER TABLE chat ADD COLUMN personaID TEXT REFERENCES persona(id) ON DELETE SET NULL")
                // LocalChat's characters were seeded here; Spectra never used them. The tables stay so old files open.
            },
            {
                execute("ALTER TABLE chat ADD COLUMN modelName TEXT")
                execute("ALTER TABLE message ADD COLUMN modelName TEXT")
                execute("""CREATE TABLE profile (id INTEGER PRIMARY KEY, isEnabled INTEGER NOT NULL DEFAULT 1, name TEXT NOT NULL,
                    address TEXT NOT NULL, about TEXT NOT NULL, updatedAt INTEGER NOT NULL)""")
            },
            {
                execute("ALTER TABLE message ADD COLUMN search TEXT")
                execute("""CREATE TABLE websearch_log (id INTEGER PRIMARY KEY AUTOINCREMENT, date INTEGER NOT NULL, provider TEXT NOT NULL,
                    query TEXT NOT NULL, succeeded INTEGER NOT NULL)""")
            },
            {
                execute("CREATE TABLE memoryNote (id TEXT PRIMARY KEY, text TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            },
            {
                execute("""CREATE TABLE websearch_cache (id INTEGER PRIMARY KEY AUTOINCREMENT, date INTEGER NOT NULL, provider TEXT NOT NULL,
                    query TEXT NOT NULL, terms TEXT NOT NULL, gathered TEXT NOT NULL)""")
                execute("CREATE INDEX websearch_cache_on_provider ON websearch_cache(provider, date)")
            },
            {
                execute("CREATE TABLE folder (id TEXT PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                execute("ALTER TABLE chat ADD COLUMN folderID TEXT REFERENCES folder(id) ON DELETE SET NULL")
            },
        )
        database.execute("PRAGMA foreign_keys = ON")
        val current = database.userVersion
        for ((index, migration) in migrations.withIndex()) {
            if (index < current) continue
            database.transaction {
                database.migration()
                database.userVersion = index + 1
            }
        }
    }

    // MARK: - Диалоги

    suspend fun createChat(systemPrompt: String = Chat.DEFAULT_SYSTEM_PROMPT, folderID: UUID? = null): Chat {
        val now = System.currentTimeMillis()
        val chat = Chat(UUID.randomUUID(), "", systemPrompt, now, now, null, folderID)
        write {
            execute(
                "INSERT INTO chat (id, title, systemPrompt, createdAt, updatedAt, folderID) VALUES (?, ?, ?, ?, ?, ?)",
                chat.id.toString(), chat.title, chat.systemPrompt, now, now, folderID?.toString(),
            )
        }
        return chat
    }

    // MARK: - Folders

    suspend fun folders(): List<ChatFolder> = read {
        query("SELECT * FROM folder ORDER BY name COLLATE NOCASE").map {
            ChatFolder(UUID.fromString(it.string("id")), it.string("name"), it.long("createdAt"))
        }
    }

    suspend fun createFolder(name: String): ChatFolder {
        val folder = ChatFolder(UUID.randomUUID(), name.trim(), System.currentTimeMillis())
        write { execute("INSERT INTO folder (id, name, createdAt) VALUES (?, ?, ?)", folder.id.toString(), folder.name, folder.createdAt) }
        return folder
    }

    suspend fun renameFolder(id: UUID, name: String) =
        write { execute("UPDATE folder SET name = ? WHERE id = ?", name.trim(), id.toString()) }

    /** Deletes the folder only: its chats stay, outside any folder. */
    suspend fun deleteFolder(id: UUID) = write {
        execute("UPDATE chat SET folderID = NULL WHERE folderID = ?", id.toString())
        execute("DELETE FROM folder WHERE id = ?", id.toString())
    }

    suspend fun moveChat(chatID: UUID, folderID: UUID?) =
        write { execute("UPDATE chat SET folderID = ? WHERE id = ?", folderID?.toString(), chatID.toString()) }

    suspend fun chats(): List<Chat> = read { query("SELECT * FROM chat ORDER BY updatedAt DESC").map(::chat) }

    suspend fun chat(id: UUID): Chat? = read { query("SELECT * FROM chat WHERE id = ?", id.toString()).firstOrNull()?.let(::chat) }

    suspend fun rename(chatID: UUID, title: String) = write { execute("UPDATE chat SET title = ? WHERE id = ?", title, chatID.toString()) }

    suspend fun deleteChat(chatID: UUID) = write { execute("DELETE FROM chat WHERE id = ?", chatID.toString()) }

    /** Удаляет все диалоги и сжимает файл, чтобы удалённое не лежало в свободных страницах базы. */
    suspend fun deleteEverything() {
        write {
            execute("DELETE FROM message")
            execute("DELETE FROM chat")
            execute("DELETE FROM folder")
            execute("DELETE FROM websearch_log")
            // Заметки остались от чата прежних версий.
            execute("DELETE FROM memoryNote")
        }
        vacuum()
    }

    suspend fun vacuum() = withContext(io) { lock.withLock { database.execute("VACUUM") } }

    // MARK: - Сообщения

    suspend fun messages(chatID: UUID): List<StoredMessage> =
        read { query("SELECT * FROM message WHERE chatID = ? ORDER BY seq", chatID.toString()).map(::message) }

    /** Добавляет сообщение в конец диалога. Заголовок пустого диалога берётся из первой реплики пользователя. */
    suspend fun append(role: ChatMessage.Role, content: String, chatID: UUID, modelName: String? = null): StoredMessage = write {
        val last = query("SELECT MAX(seq) AS last FROM message WHERE chatID = ?", chatID.toString()).first()["last"] as Number?
        val message = StoredMessage(UUID.randomUUID(), chatID, (last?.toInt() ?: 0) + 1, role, content, System.currentTimeMillis(), false, modelName)
        execute(
            "INSERT INTO message (id, chatID, seq, role, content, createdAt, interrupted, modelName) VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
            message.id.toString(), chatID.toString(), message.seq, role.raw, content, message.createdAt, modelName,
        )
        execute("UPDATE chat SET updatedAt = ? WHERE id = ?", message.createdAt, chatID.toString())
        if (modelName != null) execute("UPDATE chat SET modelName = ? WHERE id = ?", modelName, chatID.toString())
        if (role == ChatMessage.Role.User) {
            val title = query("SELECT title FROM chat WHERE id = ?", chatID.toString()).firstOrNull()?.stringOrNull("title")
            if (title != null && title.isEmpty()) execute("UPDATE chat SET title = ? WHERE id = ?", title(content), chatID.toString())
        }
        message
    }

    suspend fun update(messageID: UUID, content: String, interrupted: Boolean = false) = write {
        execute("UPDATE message SET content = ?, interrupted = ? WHERE id = ?", content, if (interrupted) 1 else 0, messageID.toString())
    }

    suspend fun setSearch(json: String?, messageID: UUID) =
        write { execute("UPDATE message SET search = ? WHERE id = ?", json, messageID.toString()) }

    // MARK: - Журнал поиска

    suspend fun logSearch(query: String, provider: String, succeeded: Boolean) = write {
        execute("INSERT INTO websearch_log (date, provider, query, succeeded) VALUES (?, ?, ?, ?)",
            System.currentTimeMillis(), provider, query, if (succeeded) 1 else 0)
    }

    // MARK: - Search cache

    /** The freshest earlier search by the same provider whose query is similar to [query], if one is still fresh. */
    suspend fun cachedSearch(query: String, provider: String, now: Long = System.currentTimeMillis()): alexcattivo.localchat.kit.web.SearchCacheHit? {
        val wanted = alexcattivo.localchat.kit.web.Terms.of(query).toSet()
        if (wanted.isEmpty()) return null
        val rows = read {
            query("SELECT * FROM websearch_cache WHERE provider = ? AND date > ? ORDER BY date DESC",
                provider, now - alexcattivo.localchat.kit.web.SearchCache.LIFETIME_MS)
        }
        for (row in rows) {
            val terms = row.string("terms").split(' ').filter { it.isNotEmpty() }.toSet()
            if (!alexcattivo.localchat.kit.web.SearchCache.similarity(wanted, terms)) continue
            val gathered = alexcattivo.localchat.kit.web.SearchCache.decode(row.string("gathered")) ?: continue
            if (gathered.results.isEmpty()) continue
            return alexcattivo.localchat.kit.web.SearchCacheHit(row.string("query"), row.long("date"), gathered)
        }
        return null
    }

    /** Keeps what a search brought back, replacing an entry with the same words; drops expired and surplus ones. */
    suspend fun cacheSearch(query: String, provider: String, gathered: alexcattivo.localchat.kit.web.WebResearch.Gathered, now: Long = System.currentTimeMillis()) {
        if (gathered.results.isEmpty()) return
        val key = alexcattivo.localchat.kit.web.SearchCache.key(query)
        if (key.isEmpty()) return
        val json = alexcattivo.localchat.kit.web.SearchCache.encode(gathered)
        write {
            execute("DELETE FROM websearch_cache WHERE provider = ? AND terms = ?", provider, key)
            execute("INSERT INTO websearch_cache (date, provider, query, terms, gathered) VALUES (?, ?, ?, ?, ?)", now, provider, query, key, json)
            execute("DELETE FROM websearch_cache WHERE date <= ?", now - alexcattivo.localchat.kit.web.SearchCache.LIFETIME_MS)
            execute("DELETE FROM websearch_cache WHERE id NOT IN (SELECT id FROM websearch_cache ORDER BY date DESC LIMIT ${alexcattivo.localchat.kit.web.SearchCache.LIMIT})")
        }
    }

    /** Every search: what went out, and what it brought back while that is still kept. Newest first. */
    suspend fun searches(now: Long = System.currentTimeMillis()): List<alexcattivo.localchat.kit.web.PastSearch> = read {
        val log = query("SELECT * FROM websearch_log ORDER BY date DESC LIMIT 200").map {
            SearchLogEntry(it.long("id"), it.long("date"), it.string("provider"), it.string("query"), it.bool("succeeded"))
        }
        val kept = query("SELECT * FROM websearch_cache WHERE date > ? ORDER BY date DESC", now - alexcattivo.localchat.kit.web.SearchCache.LIFETIME_MS).map {
            alexcattivo.localchat.kit.web.KeptSearch(
                it.long("id"), it.long("date"), it.string("provider"), it.string("query"), it.string("terms"), it.string("gathered"),
            )
        }
        alexcattivo.localchat.kit.web.PastSearch.joined(log, kept)
    }

    /** Forgets one search: the record that it went out and what it found. */
    suspend fun deleteSearch(search: alexcattivo.localchat.kit.web.PastSearch) = write {
        search.logId?.let { execute("DELETE FROM websearch_log WHERE id = ?", it) }
        search.keptId?.let { execute("DELETE FROM websearch_cache WHERE id = ?", it) }
    }

    /** Forgets every search: the records and what they found. */
    suspend fun clearSearches() {
        write {
            execute("DELETE FROM websearch_log")
            execute("DELETE FROM websearch_cache")
        }
        vacuum()
    }

    // MARK: - Чтение строк

    private fun chat(row: Map<String, Any?>) = Chat(
        id = UUID.fromString(row.string("id")),
        title = row.string("title"),
        systemPrompt = row.string("systemPrompt"),
        createdAt = row.long("createdAt"),
        updatedAt = row.long("updatedAt"),
        modelName = row.stringOrNull("modelName"),
        folderID = row.stringOrNull("folderID")?.let(UUID::fromString),
    )

    private fun message(row: Map<String, Any?>) = StoredMessage(
        id = UUID.fromString(row.string("id")),
        chatID = UUID.fromString(row.string("chatID")),
        seq = row.int("seq"),
        role = ChatMessage.Role.entries.first { it.raw == row.string("role") },
        content = row.string("content"),
        createdAt = row.long("createdAt"),
        interrupted = row.bool("interrupted"),
        modelName = row.stringOrNull("modelName"),
        searchJson = row.stringOrNull("search"),
    )

    companion object {
        /** Room for two lines in the chat list: the topic of a ✨ question sits past its first 40 characters. */
        const val TITLE_LENGTH = 90

        fun title(text: String): String {
            val line = text.lineSequence().firstOrNull()?.trim() ?: text.trim()
            return if (line.length > TITLE_LENGTH) line.take(TITLE_LENGTH) + "…" else line
        }
    }
}
