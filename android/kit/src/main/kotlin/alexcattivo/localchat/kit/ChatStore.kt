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
                for (persona in Persona.builtIns) insertPersona(persona)
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

    private fun SqlDatabase.insertPersona(persona: Persona) {
        execute(
            "INSERT INTO persona (id, name, avatar, systemPrompt, temperature, maxTokens, isBuiltIn, sortOrder, createdAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            persona.id.toString(), persona.name, persona.avatar, persona.systemPrompt, persona.temperature, persona.maxTokens,
            if (persona.isBuiltIn) 1 else 0, persona.sortOrder, persona.createdAt,
        )
    }

    // MARK: - Диалоги

    suspend fun createChat(systemPrompt: String = Chat.DEFAULT_SYSTEM_PROMPT, persona: Persona? = null): Chat {
        val now = System.currentTimeMillis()
        val chat = Chat(UUID.randomUUID(), "", persona?.systemPrompt ?: systemPrompt, now, now, persona?.id, null)
        write {
            execute(
                "INSERT INTO chat (id, title, systemPrompt, createdAt, updatedAt, personaID) VALUES (?, ?, ?, ?, ?, ?)",
                chat.id.toString(), chat.title, chat.systemPrompt, now, now, chat.personaID?.toString(),
            )
        }
        return chat
    }

    suspend fun chats(): List<Chat> = read { query("SELECT * FROM chat ORDER BY updatedAt DESC").map(::chat) }

    suspend fun chat(id: UUID): Chat? = read { query("SELECT * FROM chat WHERE id = ?", id.toString()).firstOrNull()?.let(::chat) }

    suspend fun rename(chatID: UUID, title: String) = write { execute("UPDATE chat SET title = ? WHERE id = ?", title, chatID.toString()) }

    suspend fun setSystemPrompt(prompt: String, chatID: UUID) =
        write { execute("UPDATE chat SET systemPrompt = ? WHERE id = ?", prompt, chatID.toString()) }

    suspend fun deleteChat(chatID: UUID) = write { execute("DELETE FROM chat WHERE id = ?", chatID.toString()) }

    /** Удаляет все диалоги и сжимает файл, чтобы удалённое не лежало в свободных страницах базы. */
    suspend fun deleteEverything() {
        write {
            execute("DELETE FROM message")
            execute("DELETE FROM chat")
            execute("DELETE FROM websearch_log")
            // Заметки модель делала по ходу разговоров: удаляя всю переписку, удаляем и их.
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

    /** Удаляет сообщения диалога, начиная с номера `seq` включительно. */
    suspend fun deleteMessages(chatID: UUID, fromSeq: Int) =
        write { execute("DELETE FROM message WHERE chatID = ? AND seq >= ?", chatID.toString(), fromSeq) }

    suspend fun setSearch(json: String?, messageID: UUID) =
        write { execute("UPDATE message SET search = ? WHERE id = ?", json, messageID.toString()) }

    // MARK: - Журнал поиска

    suspend fun logSearch(query: String, provider: String, succeeded: Boolean) = write {
        execute("INSERT INTO websearch_log (date, provider, query, succeeded) VALUES (?, ?, ?, ?)",
            System.currentTimeMillis(), provider, query, if (succeeded) 1 else 0)
    }

    suspend fun searchLog(limit: Int = 200): List<SearchLogEntry> = read {
        query("SELECT * FROM websearch_log ORDER BY date DESC LIMIT $limit").map {
            SearchLogEntry(it.long("id"), it.long("date"), it.string("provider"), it.string("query"), it.bool("succeeded"))
        }
    }

    suspend fun deleteSearchLogEntry(id: Long) = write { execute("DELETE FROM websearch_log WHERE id = ?", id) }

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

    suspend fun searchCacheCount(): Int = read { query("SELECT COUNT(*) AS n FROM websearch_cache").first().long("n").toInt() }

    suspend fun clearSearchCache() {
        write { execute("DELETE FROM websearch_cache") }
        vacuum()
    }

    suspend fun clearSearchLog() {
        write { execute("DELETE FROM websearch_log") }
        vacuum()
    }

    // MARK: - Персонажи

    /** Сначала встроенные, потом свои в порядке создания. */
    suspend fun personas(): List<Persona> = read { query("SELECT * FROM persona ORDER BY sortOrder, createdAt").map(::persona) }

    suspend fun persona(id: UUID): Persona? = read { query("SELECT * FROM persona WHERE id = ?", id.toString()).firstOrNull()?.let(::persona) }

    /** Сохраняет своего персонажа. Встроенных менять нельзя: их можно только скопировать. */
    suspend fun save(persona: Persona) = write {
        val existing = query("SELECT isBuiltIn FROM persona WHERE id = ?", persona.id.toString()).firstOrNull()
        if (existing != null && existing.bool("isBuiltIn")) return@write
        val saved = persona.copy(isBuiltIn = false)
        if (existing == null) {
            insertPersona(saved)
        } else {
            execute("UPDATE persona SET name = ?, avatar = ?, systemPrompt = ?, temperature = ?, maxTokens = ? WHERE id = ?",
                saved.name, saved.avatar, saved.systemPrompt, saved.temperature, saved.maxTokens, saved.id.toString())
        }
    }

    suspend fun duplicate(persona: Persona, suffix: String = " (копия)"): Persona {
        val copy = Persona(name = persona.name + suffix, avatar = persona.avatar, systemPrompt = persona.systemPrompt,
            temperature = persona.temperature, maxTokens = persona.maxTokens)
        save(copy)
        return copy
    }

    /** Удаляет своего персонажа. Диалоги с ним остаются: промпт в них хранится копией. */
    suspend fun deletePersona(id: UUID) = write {
        execute("DELETE FROM persona WHERE id = ? AND isBuiltIn = 0", id.toString())
    }

    // MARK: - Профиль пользователя

    suspend fun profile(): UserProfile? = read {
        query("SELECT * FROM profile WHERE id = 1").firstOrNull()?.let { row ->
            UserProfile(
                isEnabled = row.bool("isEnabled"),
                name = row.string("name"),
                address = UserProfile.Address.entries.firstOrNull { it.raw == row.string("address") } ?: UserProfile.Address.Unspecified,
                about = row.string("about"),
                updatedAt = row.long("updatedAt"),
            )
        }
    }

    suspend fun saveProfile(profile: UserProfile) = write {
        execute("INSERT OR REPLACE INTO profile (id, isEnabled, name, address, about, updatedAt) VALUES (1, ?, ?, ?, ?, ?)",
            if (profile.isEnabled) 1 else 0, profile.name, profile.address.raw, profile.about, System.currentTimeMillis())
    }

    /** Стирает профиль целиком. Диалоги остаются, но модель о пользователе больше ничего не знает. */
    suspend fun deleteProfile() {
        write { execute("DELETE FROM profile") }
        vacuum()
    }

    // MARK: - Заметки модели

    /** Сначала старые: в промпте они идут в том порядке, в каком появлялись. */
    suspend fun notes(): List<MemoryNote> = read {
        query("SELECT * FROM memoryNote ORDER BY createdAt").map { MemoryNote(UUID.fromString(it.string("id")), it.string("text"), it.long("createdAt")) }
    }

    /** Сохраняет заметку. Такая же уже есть — вторую не заводим. Сверх лимита уходят самые старые. */
    suspend fun addNote(text: String): MemoryNote? {
        val cleaned = MemoryNote.clean(text)
        if (cleaned.isEmpty()) return null
        return write {
            val existing = query("SELECT * FROM memoryNote ORDER BY createdAt").map {
                MemoryNote(UUID.fromString(it.string("id")), it.string("text"), it.long("createdAt"))
            }
            if (existing.any { it.text.lowercase() == cleaned.lowercase() }) return@write null
            // Время у новой заметки строго позже прежних: порядок в промпте не зависит от точности часов.
            val note = MemoryNote(text = cleaned, createdAt = maxOf(System.currentTimeMillis(), (existing.maxOfOrNull { it.createdAt } ?: 0) + 1))
            execute("INSERT INTO memoryNote (id, text, createdAt) VALUES (?, ?, ?)", note.id.toString(), note.text, note.createdAt)
            val overflow = existing.size + 1 - MemoryNote.LIMIT
            for (old in existing.take(maxOf(0, overflow))) execute("DELETE FROM memoryNote WHERE id = ?", old.id.toString())
            note
        }
    }

    suspend fun deleteNote(id: UUID) = write { execute("DELETE FROM memoryNote WHERE id = ?", id.toString()) }

    /** Удаляет заметки, в которых есть этот текст (без учёта регистра). Возвращает, сколько удалено. */
    suspend fun deleteNotes(matching: String): Int {
        val needle = matching.trim().lowercase()
        if (needle.isEmpty()) return 0
        return write {
            var removed = 0
            for (row in query("SELECT id, text FROM memoryNote")) {
                val text = row.string("text").lowercase()
                if (text.contains(needle) || needle.contains(text)) {
                    execute("DELETE FROM memoryNote WHERE id = ?", row.string("id"))
                    removed++
                }
            }
            removed
        }
    }

    suspend fun deleteAllNotes() {
        write { execute("DELETE FROM memoryNote") }
        vacuum()
    }

    // MARK: - Память о прошлых разговорах

    /**
     * Прежние ответы, подходящие к вопросу, по совпадению слов (BM25 — тем же способом поиск ранжирует отрывки).
     * Нарочно без эмбеддингов: база диалогов маленькая, подсчёт мгновенный и не требует загруженной модели.
     * Берутся только ответы модели — в них выводы, — но каждый оценивается вместе с вопросом, на который отвечал.
     */
    suspend fun recall(question: String, excluding: UUID?, limit: Int = 2, scanLimit: Int = 300, minimumScore: Double = 1.0): List<Recollection> {
        val terms = alexcattivo.localchat.kit.web.Terms.of(question)
        if (terms.size < 2) return emptyList()
        val rows = read {
            query(
                """SELECT a.id AS id, a.chatID AS chatID, c.title AS title, a.createdAt AS date,
                       COALESCE(q.content, '') AS question, a.content AS answer
                FROM message a
                JOIN chat c ON c.id = a.chatID
                LEFT JOIN message q ON q.chatID = a.chatID AND q.seq = a.seq - 1 AND q.role = 'user'
                WHERE a.role = 'assistant' AND LENGTH(a.content) > 40 """ + (if (excluding == null) "" else "AND a.chatID <> ? ") +
                    "ORDER BY a.createdAt DESC LIMIT $scanLimit",
                *(if (excluding == null) arrayOf() else arrayOf<Any?>(excluding.toString())),
            )
        }
        if (rows.isEmpty()) return emptyList()
        val documents = rows.map { alexcattivo.localchat.kit.web.Terms.of(it.string("question") + " " + it.string("answer")) }
        val scores = alexcattivo.localchat.kit.web.PassageSelector.bm25(terms, documents)
        val chosen = mutableListOf<Recollection>()
        val usedChats = mutableSetOf<String>()
        val wanted = terms.toSet()
        for (index in scores.indices.sortedByDescending { scores[it] }) {
            if (scores[index] < minimumScore || chosen.size >= limit) break
            // Одно общее слово («name», «камера») — ещё не тот же разговор: нужны хотя бы два.
            if (wanted.intersect(documents[index].toSet()).size < 2) continue
            val row = rows[index]
            if (!usedChats.add(row.string("chatID"))) continue
            chosen.add(Recollection(UUID.fromString(row.string("id")), row.string("title"), row.long("date"), shorten(row.string("answer"))))
        }
        return chosen
    }

    // MARK: - Чтение строк

    private fun chat(row: Map<String, Any?>) = Chat(
        id = UUID.fromString(row.string("id")),
        title = row.string("title"),
        systemPrompt = row.string("systemPrompt"),
        createdAt = row.long("createdAt"),
        updatedAt = row.long("updatedAt"),
        personaID = row.stringOrNull("personaID")?.let(UUID::fromString),
        modelName = row.stringOrNull("modelName"),
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

    private fun persona(row: Map<String, Any?>) = Persona(
        id = UUID.fromString(row.string("id")),
        name = row.string("name"),
        avatar = row.string("avatar"),
        systemPrompt = row.string("systemPrompt"),
        temperature = row.double("temperature"),
        maxTokens = row.int("maxTokens"),
        isBuiltIn = row.bool("isBuiltIn"),
        sortOrder = row.int("sortOrder"),
        createdAt = row.long("createdAt"),
    )

    companion object {
        /** Заголовок из первой строки вопроса, не длиннее 40 символов. */
        /** Ответ, сокращённый до пары первых предложений: вывод узнать хватит, а места у разговора не отнимет. */
        fun shorten(text: String, limit: Int = 240): String {
            val flat = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            if (flat.length <= limit) return flat
            val cut = flat.take(limit)
            val stop = cut.indexOfLast { it in ".!?" }
            return if (stop > limit / 2) cut.substring(0, stop + 1) else "$cut…"
        }

        fun title(text: String): String {
            val line = text.lineSequence().firstOrNull()?.trim() ?: text.trim()
            return if (line.length > 40) line.take(40) + "…" else line
        }
    }
}
