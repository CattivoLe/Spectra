package alexcattivo.localchat.kit

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.EngineConfig
import alexcattivo.localchat.engine.GenerationEvent
import alexcattivo.localchat.engine.GenerationParams
import alexcattivo.localchat.engine.GenerationStats
import alexcattivo.localchat.engine.InferenceEngine
import alexcattivo.localchat.engine.ModelInfo
import alexcattivo.localchat.engine.StopReason
import java.sql.Connection
import java.sql.DriverManager
import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** SQLite в памяти через JDBC: та же схема и те же запросы, что на телефоне. */
class JdbcDatabase : SqlDatabase {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    override fun execute(sql: String, vararg args: Any?) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.execute()
        }
    }

    override fun query(sql: String, vararg args: Any?): List<Map<String, Any?>> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result ->
                val meta = result.metaData
                buildList {
                    while (result.next()) {
                        add((1..meta.columnCount).associate { meta.getColumnLabel(it) to result.getObject(it) })
                    }
                }
            }
        }

    override fun <T> transaction(block: () -> T): T {
        val wasAuto = connection.autoCommit
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = wasAuto
        }
    }

    override var userVersion: Int
        get() = query("PRAGMA user_version").first().values.first().let { (it as Number).toInt() }
        set(value) = execute("PRAGMA user_version = $value")
}

/** Движок-заглушка: отвечает заготовленным текстом по слову и запоминает, что ему прислали. */
class ScriptedEngine(
    private val pauseMillis: Long = 0,
    private val reply: (Int) -> String,
) : InferenceEngine {
    val received: MutableList<List<ChatMessage>> = Collections.synchronizedList(mutableListOf())
    val receivedParams: MutableList<GenerationParams> = Collections.synchronizedList(mutableListOf())
    var completion: ((List<ChatMessage>) -> String)? = null

    override suspend fun load(path: String, config: EngineConfig) =
        ModelInfo("mock", 0, 0, 0, 4096, 1, 0.0, "mock")

    override suspend fun unload() {}

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> = flow {
        receivedParams.add(params)
        received.add(messages)
        val text = reply(received.size)
        for (word in text.split(" ")) {
            emit(GenerationEvent.Text("$word "))
            if (pauseMillis > 0) delay(pauseMillis)
        }
        emit(GenerationEvent.Finished(GenerationStats(1, generatedTokens = 1, timeToFirstTokenSeconds = 0.0,
            generationSeconds = 0.0, stopReason = StopReason.EndOfTurn)))
    }

    override suspend fun countTokens(messages: List<ChatMessage>, thinking: Boolean): Int =
        messages.sumOf { it.content.length / 4 + 3 }

    override suspend fun complete(messages: List<ChatMessage>, maxTokens: Int): String =
        completion?.invoke(messages) ?: throw IllegalStateException("no completion")
}
