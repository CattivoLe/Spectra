package alexcattivo.localchat.engine

import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Векторы смысла для текстов: маленькая отдельная модель эмбеддингов (GGUF) через llama.cpp. Похожие по смыслу тексты
 * дают близкие векторы, даже если общих слов у них нет. Векторы нормированы: сходство — скалярное произведение.
 */
class EmbeddingEngine(private val nativeLibraryDir: String) {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "LocalChat.EmbeddingEngine") }.asCoroutineDispatcher()
    private var model = 0L
    private var context = 0L
    var dimension = 0
        private set

    suspend fun load(path: String) = withContext(dispatcher) {
        LlamaNative.ensureLoaded(nativeLibraryDir)
        unloadSync()
        val loaded = LlamaNative.loadModel(path, 0)
        if (loaded == 0L) throw EngineException.ModelLoadFailed(File(path).name)
        val created = LlamaNative.newEmbeddingContext(loaded, BATCH_TOKENS, MAX_SEQUENCES, LlamaEngine.performanceCoreCount())
        if (created == 0L) {
            LlamaNative.freeModel(loaded)
            throw EngineException.ContextCreationFailed()
        }
        model = loaded
        context = created
        dimension = LlamaNative.embeddingDimension(loaded)
    }

    suspend fun unload() = withContext(dispatcher) { unloadSync() }

    private fun unloadSync() {
        if (context != 0L) LlamaNative.freeContext(context)
        if (model != 0L) LlamaNative.freeModel(model)
        context = 0L
        model = 0L
    }

    suspend fun embed(texts: List<String>): List<FloatArray> = withContext(dispatcher) {
        if (model == 0L) throw EngineException.NoModelLoaded()
        val tokenized = texts.map { text ->
            val tokens = LlamaNative.tokenize(model, text.toByteArray(), true, false) ?: throw EngineException.TokenizationFailed()
            // Длиннее этого текст обрезается: модели эмбеддингов обучены на коротких кусках.
            if (tokens.size > MAX_TOKENS_PER_TEXT) tokens.copyOf(MAX_TOKENS_PER_TEXT) else tokens
        }
        val started = System.nanoTime()
        val results = ArrayList<FloatArray>(texts.size)
        var start = 0
        while (start < tokenized.size) {
            // Набираем пакет: сколько влезет текстов по числу токенов и последовательностей.
            var end = start
            var total = 0
            while (end < tokenized.size && end - start < MAX_SEQUENCES && total + tokenized[end].size <= BATCH_TOKENS) {
                total += tokenized[end].size
                end++
            }
            if (end == start) end = start + 1
            val batch = tokenized.subList(start, end)
            val flat = IntArray(batch.sumOf { it.size }).also { out ->
                var position = 0
                for (tokens in batch) { tokens.copyInto(out, position); position += tokens.size }
            }
            val vectors = LlamaNative.embedBatch(model, context, flat, batch.map { it.size }.toIntArray())
            for (index in batch.indices) {
                results.add(normalized(vectors.copyOfRange(index * dimension, (index + 1) * dimension)))
            }
            start = end
        }
        android.util.Log.i("LocalChatEngine", String.format(java.util.Locale.ROOT, "embed: %d texts, %d tokens in %.1fs",
            texts.size, tokenized.sumOf { it.size }, (System.nanoTime() - started) / 1e9))
        results
    }

    companion object {
        private const val BATCH_TOKENS = 2048
        private const val MAX_SEQUENCES = 8
        private const val MAX_TOKENS_PER_TEXT = 512

        fun normalized(vector: FloatArray): FloatArray {
            val norm = sqrt(vector.fold(0f) { sum, value -> sum + value * value })
            return if (norm > 0) FloatArray(vector.size) { vector[it] / norm } else vector
        }
    }
}
