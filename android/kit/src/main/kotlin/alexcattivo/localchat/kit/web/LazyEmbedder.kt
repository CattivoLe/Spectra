package alexcattivo.localchat.kit.web

import alexcattivo.localchat.engine.EmbeddingEngine
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Пометки перед текстом, которых ждёт конкретная модель эмбеддингов. */
enum class EmbeddingStyle {
    /** multilingual-e5: «query: …» и «passage: …». */
    E5,
    /** EmbeddingGemma: «task: search result | query: …» и «title: none | text: …». */
    EmbeddingGemma,
    Plain;

    fun query(text: String) = when (this) { E5 -> "query: $text"; EmbeddingGemma -> "task: search result | query: $text"; Plain -> text }
    fun passage(text: String) = when (this) { E5 -> "passage: $text"; EmbeddingGemma -> "title: none | text: $text"; Plain -> text }

    companion object {
        fun guess(fileName: String): EmbeddingStyle {
            val name = fileName.lowercase()
            return when {
                name.contains("e5") -> E5
                name.contains("embeddinggemma") -> EmbeddingGemma
                else -> Plain
            }
        }
    }
}

/** Эмбеддер, который загружает модель только при первом поиске: пока интернет не нужен, память не занята. */
class LazyEmbedder(private val path: String, private val nativeLibraryDir: String) : PassageEmbedder {
    private val style = EmbeddingStyle.guess(File(path).name)
    private val mutex = Mutex()
    private var engine: EmbeddingEngine? = null

    override suspend fun embedQueries(queries: List<String>) = prepared().embed(queries.map(style::query))
    override suspend fun embedPassages(passages: List<String>) = prepared().embed(passages.map(style::passage))

    /** Освобождает память; при следующем поиске модель загрузится снова. */
    suspend fun unload() = mutex.withLock {
        engine?.unload()
        engine = null
    }

    private suspend fun prepared(): EmbeddingEngine = mutex.withLock {
        engine ?: EmbeddingEngine(nativeLibraryDir).also { it.load(path); engine = it }
    }
}
