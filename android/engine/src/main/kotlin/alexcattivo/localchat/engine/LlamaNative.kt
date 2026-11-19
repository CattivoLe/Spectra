package alexcattivo.localchat.engine

/** Вызовы llama.cpp через JNI (src/main/cpp/llama_jni.cpp). Текст ходит байтами UTF-8: так не ломаются эмодзи. */
internal object LlamaNative {
    @Volatile private var loaded = false

    /** Загружает библиотеку и подгружает лучший вариант процессорного бэкенда из каталога библиотек приложения. */
    @Synchronized
    fun ensureLoaded(nativeLibraryDir: String) {
        if (loaded) return
        System.loadLibrary("localchat_engine")
        initBackend(nativeLibraryDir)
        loaded = true
    }

    external fun initBackend(libraryDir: String)
    external fun systemInfo(): String
    external fun loadModel(path: String, gpuLayers: Int): Long
    external fun freeModel(model: Long)
    external fun newContext(model: Long, contextLength: Int, batch: Int, threads: Int): Long
    external fun freeContext(context: Long)
    external fun contextLength(context: Long): Int
    external fun modelDescription(model: Long): String
    external fun parameterCount(model: Long): Long
    external fun trainedContextLength(model: Long): Int
    external fun chatTemplate(model: Long): ByteArray?
    external fun tokenText(model: Long, token: Int): ByteArray
    external fun bosToken(model: Long): Int
    external fun eosToken(model: Long): Int
    external fun isEndOfGeneration(model: Long, token: Int): Boolean
    external fun tokenize(model: Long, text: ByteArray, addSpecial: Boolean, parseSpecial: Boolean): IntArray?
    external fun tokenPiece(model: Long, token: Int): ByteArray
    external fun decode(context: Long, tokens: IntArray, offset: Int, count: Int): Int
    external fun memoryClear(context: Long)
    external fun memoryRemoveFrom(context: Long, position: Int): Boolean
    external fun newSampler(
        model: Long, temperature: Float, topP: Float, topK: Int, seed: Int, grammar: ByteArray?, triggers: Array<ByteArray>?,
    ): Long
    external fun sample(sampler: Long, context: Long): Int
    external fun freeSampler(sampler: Long)
    external fun newEmbeddingContext(model: Long, batchTokens: Int, maxSequences: Int, threads: Int): Long
    external fun embeddingDimension(model: Long): Int
    external fun embedBatch(model: Long, context: Long, tokens: IntArray, lengths: IntArray): FloatArray
    external fun newTemplate(source: ByteArray, bos: ByteArray, eos: ByteArray): Long
    external fun freeTemplate(template: Long)
    external fun renderTemplate(template: Long, variablesJson: ByteArray): ByteArray
}
