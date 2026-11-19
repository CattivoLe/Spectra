package alexcattivo.localchat.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Движок на llama.cpp (модели GGUF, процессор).
 *
 * Все вызовы llama.cpp идут через один поток: контекст не потокобезопасен, а вычисления блокирующие.
 * Логика повторяет LlamaEngine.swift: кэш промпта, обрезка старых реплик, служебный второй контекст,
 * продолжение начатого ответа, ленивая грамматика для вызова инструментов и сохранённый на диск системный промпт.
 *
 * @param promptCacheDir куда сохранять посчитанный системный промпт между чатами (см. [PromptCache]); `null` — не сохранять.
 */
class LlamaEngine(private val nativeLibraryDir: String, promptCacheDir: File? = null) : InferenceEngine {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "LocalChat.LlamaEngine") }.asCoroutineDispatcher()

    // Состояние ниже меняется только в потоке движка.
    private var model = 0L
    private var context = 0L
    /** Второй контекст той же модели для коротких служебных задач: кэш основного диалога не сбивается. */
    private var utilityContext = 0L
    private var threadCount = 4
    private var template: String? = null
    private var chatTemplate: ChatTemplate? = null
    private var fallbackFormat: ChatFormat? = null
    private var bosText = ""
    /** Токены, которые сейчас лежат в KV-кэше, по порядку: следующий запрос досчитывает только отличие. */
    private var cachedTokens = IntArray(0)
    /** Сообщения, уже выброшенные из начала текущего диалога: граница не «ползёт» с каждой репликой. */
    private var droppedHistory: List<ChatMessage> = emptyList()
    private val promptCache = promptCacheDir?.let(::PromptCache)
    /** От чего, кроме промпта, зависят сохранённые состояния: файл модели и настройки. */
    private var cacheIdentity: Triple<String, Long, String>? = null
    /** Системная часть последнего промпта в токенах, чтобы не рендерить её заново к каждой реплике. */
    private var systemPrefix: Triple<List<ChatMessage>, Boolean, IntArray>? = null

    override suspend fun load(path: String, config: EngineConfig): ModelInfo = withContext(dispatcher) {
        LlamaNative.ensureLoaded(nativeLibraryDir)
        unloadSync()
        val start = System.nanoTime()

        val loadedModel = LlamaNative.loadModel(path, config.gpuLayers)
        if (loadedModel == 0L) throw EngineException.ModelLoadFailed(File(path).name)
        val threads = if (config.threads > 0) config.threads else performanceCoreCount()
        val loadedContext = LlamaNative.newContext(loadedModel, config.contextLength, BATCH, threads)
        if (loadedContext == 0L) {
            LlamaNative.freeModel(loadedModel)
            throw EngineException.ContextCreationFailed()
        }
        model = loadedModel
        context = loadedContext
        threadCount = threads
        template = LlamaNative.chatTemplate(loadedModel)?.decodeToString()
        bosText = LlamaNative.tokenText(loadedModel, LlamaNative.bosToken(loadedModel)).decodeToString()
        val eosText = LlamaNative.tokenText(loadedModel, LlamaNative.eosToken(loadedModel)).decodeToString()
        cachedTokens = IntArray(0)
        droppedHistory = emptyList()
        chatTemplate = template?.let { source ->
            // Шаблон должен не только разобраться, но и отработать на простом диалоге.
            ChatTemplate.create(source, bosText, eosText)?.takeIf { candidate ->
                runCatching { candidate.render(listOf(ChatMessage(ChatMessage.Role.User, "ping")), false) }.isSuccess
                    .also { ok -> if (!ok) candidate.close() }
            }
        }
        fallbackFormat = if (chatTemplate == null) ChatFormat.detect(template) else null
        cacheIdentity = Triple(File(path).name, File(path).length(), if (config.gpuLayers > 0) "gpu" else "cpu")
        systemPrefix = null
        android.util.Log.i("LocalChatEngine", String.format(java.util.Locale.ROOT,
            "load: %s, ctx %d, %d threads, %.1fs; %s", File(path).name, LlamaNative.contextLength(loadedContext), threads,
            (System.nanoTime() - start) / 1e9, LlamaNative.systemInfo()))

        ModelInfo(
            description = LlamaNative.modelDescription(loadedModel),
            parameterCount = LlamaNative.parameterCount(loadedModel),
            fileSizeBytes = File(path).length(),
            trainedContextLength = LlamaNative.trainedContextLength(loadedModel),
            contextLength = LlamaNative.contextLength(loadedContext),
            threads = threads,
            loadSeconds = (System.nanoTime() - start) / 1e9,
            chatFormat = if (chatTemplate != null) "шаблон из модели (Jinja)" else "запасной: ${(fallbackFormat ?: ChatFormat.Plain).title}",
        )
    }

    override suspend fun unload() = withContext(dispatcher) { unloadSync() }

    private fun unloadSync() {
        if (utilityContext != 0L) LlamaNative.freeContext(utilityContext)
        if (context != 0L) LlamaNative.freeContext(context)
        if (model != 0L) LlamaNative.freeModel(model)
        chatTemplate?.close()
        utilityContext = 0L
        context = 0L
        model = 0L
        template = null
        chatTemplate = null
        fallbackFormat = null
        cachedTokens = IntArray(0)
        droppedHistory = emptyList()
        cacheIdentity = null
        systemPrefix = null
    }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> = callbackFlow {
        val cancelled = AtomicBoolean(false)
        val job = launch(dispatcher) {
            try {
                val stats = generateSync(messages, params, cancelled) { trySend(GenerationEvent.Text(it)) }
                trySend(GenerationEvent.Finished(stats))
                close()
            } catch (error: Throwable) {
                close(error)
            }
        }
        // Остановка — флаг, который движок проверяет перед каждым токеном: прервать вызов llama.cpp посреди нельзя.
        awaitClose { cancelled.set(true); job.cancel() }
    }

    override suspend fun countTokens(messages: List<ChatMessage>, thinking: Boolean): Int? = withContext(dispatcher) {
        if (model == 0L) return@withContext null
        runCatching { tokenize(render(messages, thinking)).size }.getOrNull()
    }

    override suspend fun complete(messages: List<ChatMessage>, maxTokens: Int): String = withContext(dispatcher) {
        if (model == 0L) throw EngineException.NoModelLoaded()
        if (utilityContext == 0L) {
            utilityContext = LlamaNative.newContext(model, UTILITY_CONTEXT, BATCH, threadCount)
            if (utilityContext == 0L) throw EngineException.ContextCreationFailed()
        }
        val ctx = utilityContext
        LlamaNative.memoryClear(ctx)
        val started = System.nanoTime()
        val tokens = tokenize(render(messages, false))
        if (tokens.size + maxTokens >= UTILITY_CONTEXT) throw EngineException.PromptTooLong(tokens.size, UTILITY_CONTEXT)
        decodeAll(ctx, tokens, 0)

        // Служебный ответ должен быть предсказуемым: всегда самый вероятный токен.
        val sampler = LlamaNative.newSampler(model, 0f, 1f, 0, 0, null, null)
        try {
            val bytes = java.io.ByteArrayOutputStream()
            repeat(maxTokens) {
                val token = LlamaNative.sample(sampler, ctx)
                if (LlamaNative.isEndOfGeneration(model, token)) return@withContext bytes.toByteArray().decodeToString()
                bytes.write(LlamaNative.tokenPiece(model, token))
                val code = LlamaNative.decode(ctx, intArrayOf(token), 0, 1)
                if (code != 0) throw EngineException.DecodeFailed(code)
            }
            bytes.toByteArray().decodeToString()
        } finally {
            LlamaNative.freeSampler(sampler)
            android.util.Log.i("LocalChatEngine", String.format(java.util.Locale.ROOT, "complete: prompt %d in %.1fs total", tokens.size, (System.nanoTime() - started) / 1e9))
        }
    }

    /** Шаблон чата из метаданных загруженной модели — для диагностики. */
    suspend fun chatTemplateSource(): String? = withContext(dispatcher) { template }

    // MARK: - Генерация

    private fun generateSync(
        messages: List<ChatMessage>,
        params: GenerationParams,
        cancelled: AtomicBoolean,
        emit: (String) -> Unit,
    ): GenerationStats {
        if (model == 0L || context == 0L) throw EngineException.NoModelLoaded()
        val contextLength = LlamaNative.contextLength(context)
        val (tokens, dropped) = preparePrompt(messages, params, contextLength)

        val sampler = makeSampler(params)
        try {
            val start = System.nanoTime()
            // Всё, что совпадает с уже посчитанным началом, пересчитывать не нужно.
            // Хотя бы один токен досчитываем всегда: без него не будет данных для выбора следующего.
            var reused = minOf(commonPrefix(cachedTokens, tokens), tokens.size - 1)
            if (reused <= 0) {
                reused = 0
                LlamaNative.memoryClear(context)
            } else if (reused < cachedTokens.size && !LlamaNative.memoryRemoveFrom(context, reused)) {
                // Часть моделей (с рекуррентными слоями) не умеет отбрасывать хвост кэша: считаем заново.
                reused = 0
                LlamaNative.memoryClear(context)
            }
            cachedTokens = tokens.copyOfRange(0, reused)

            // Новый чат: системной части в кэше ещё нет. Если она уже считалась — загружаем с диска,
            // иначе считаем её первой и по пути сохраняем.
            var pendingSave: PromptCache.Key? = null
            val key = systemPrefixKey(messages, tokens, params.thinking, contextLength)
            if (key != null && reused < key.prefix.size) {
                when (restore(key)) {
                    Restore.Loaded -> {
                        reused = key.prefix.size
                        cachedTokens = key.prefix.copyOf()
                    }
                    // Кэш остаётся каким был: цикл ниже досчитает системную часть и сохранит её.
                    Restore.Missing -> pendingSave = key
                    Restore.Failed -> {
                        reused = 0
                        cachedTokens = IntArray(0)
                        pendingSave = key
                    }
                }
            }

            var offset = reused
            while (offset < tokens.size) {
                if (cancelled.get()) {
                    return GenerationStats(tokens.size, reused, dropped, 0, seconds(start), 0.0, StopReason.Cancelled)
                }
                // Пакет кончается ровно на границе системной части: в сохранённом состоянии не будет ничего лишнего.
                val stop = pendingSave?.let { if (offset < it.prefix.size) it.prefix.size else null } ?: tokens.size
                val count = minOf(BATCH, stop - offset)
                val code = LlamaNative.decode(context, tokens, offset, count)
                if (code != 0) {
                    resetCache()
                    throw EngineException.DecodeFailed(code)
                }
                offset += count
                cachedTokens = tokens.copyOfRange(0, offset)
                if (pendingSave != null && offset == pendingSave.prefix.size) {
                    save(pendingSave)
                    pendingSave = null
                }
            }

            var generated = 0
            var firstTokenAt = 0L
            val pending = Utf8Accumulator()
            var stopReason = StopReason.MaxTokens
            val generatedText = StringBuilder()
            val grammarActive = params.grammar != null && params.grammarTriggers.isNotEmpty()
            val cache = ArrayList<Int>(tokens.size + params.maxTokens).apply { addAll(cachedTokens.toList()) }

            while (generated < params.maxTokens) {
                if (cancelled.get()) {
                    stopReason = StopReason.Cancelled
                    break
                }
                val token = LlamaNative.sample(sampler, context)
                if (firstTokenAt == 0L) firstTokenAt = System.nanoTime()
                if (LlamaNative.isEndOfGeneration(model, token)) {
                    stopReason = StopReason.EndOfTurn
                    break
                }
                generated++
                val text = pending.append(LlamaNative.tokenPiece(model, token))
                if (text != null) {
                    emit(text)
                    // Ленивая грамматика роняет процесс, если ей подать токен после того, как корневое правило
                    // уже совпало: останавливаемся ДО следующего sample, как только скобки после триггера закрылись.
                    if (grammarActive) {
                        generatedText.append(text)
                        if (grammarOutputComplete(generatedText.toString(), params.grammarTriggers)) {
                            stopReason = StopReason.EndOfTurn
                            break
                        }
                    }
                }
                if (tokens.size + generated >= contextLength) {
                    stopReason = StopReason.ContextFull
                    break
                }
                val code = LlamaNative.decode(context, intArrayOf(token), 0, 1)
                if (code != 0) {
                    resetCache()
                    throw EngineException.DecodeFailed(code)
                }
                cache.add(token)
            }
            cachedTokens = cache.toIntArray()
            pending.flush()?.let(emit)

            val end = System.nanoTime()
            val first = if (firstTokenAt == 0L) end else firstTokenAt
            android.util.Log.i("LocalChatEngine", String.format(java.util.Locale.ROOT,
                "generate: prompt %d (reused %d) in %.1fs, %d tokens in %.1fs (%.1f tok/s), %s",
                tokens.size, reused, (first - start) / 1e9, generated, (end - first) / 1e9,
                if (end > first) generated / ((end - first) / 1e9) else 0.0, stopReason))
            return GenerationStats(
                promptTokens = tokens.size,
                reusedPromptTokens = reused,
                droppedMessages = dropped,
                generatedTokens = generated,
                timeToFirstTokenSeconds = (first - start) / 1e9,
                generationSeconds = (end - first) / 1e9,
                stopReason = stopReason,
            )
        } finally {
            LlamaNative.freeSampler(sampler)
        }
    }

    private fun makeSampler(params: GenerationParams): Long {
        val grammar = params.grammar?.takeIf { it.isNotEmpty() && params.grammarTriggers.isNotEmpty() }
        // llama.cpp проигрывает вывод в грамматику начиная с самого триггера, поэтому корневое правило вызывающего
        // оборачивается внешним, которое сначала принимает текст триггера. Иначе первый же символ опустошит стек.
        val wrapped = grammar?.let {
            it + "\n$TRIGGERED_ROOT ::= ( ${params.grammarTriggers.joinToString(" | ") { trigger -> gbnfLiteral(trigger) }} ) root\n"
        }
        val triggers = if (wrapped != null) params.grammarTriggers.map { regexEscaped(it).toByteArray() }.toTypedArray() else null
        return LlamaNative.newSampler(model, params.temperature, params.topP, params.topK, params.seed, wrapped?.toByteArray(), triggers)
    }

    // MARK: - Сохранённый системный промпт

    private enum class Restore { Loaded, Missing, Failed }

    /** Ключ сохранённого состояния для системной части этого промпта или `null`, если сохранять нечего. */
    private fun systemPrefixKey(messages: List<ChatMessage>, prompt: IntArray, thinking: Boolean, contextLength: Int): PromptCache.Key? {
        val identity = cacheIdentity
        if (promptCache == null || identity == null) return null
        val system = messages.takeWhile { it.role == ChatMessage.Role.System }
        if (system.isEmpty()) return null
        val memo = systemPrefix
        val tokens = if (memo != null && memo.first == system && memo.second == thinking) {
            memo.third
        } else {
            // Те же системные сообщения перед двумя разными вопросами: их общее начало и есть системная часть.
            val a = runCatching { tokenize(render(system + ChatMessage(ChatMessage.Role.User, "a"), thinking)) }.getOrNull() ?: return null
            val b = runCatching { tokenize(render(system + ChatMessage(ChatMessage.Role.User, "b"), thinking)) }.getOrNull() ?: return null
            a.copyOfRange(0, PromptCache.sharedPrefixLength(a, b)).also { systemPrefix = Triple(system.toList(), thinking, it) }
        }
        val prefix = PromptCache.usablePrefix(tokens, prompt) ?: return null
        return PromptCache.Key(identity.first, identity.second, contextLength, identity.third, prefix)
    }

    /**
     * Загружает сохранённую системную часть вместо того, что было в кэше. `Missing` кэш не трогает; `Failed` оставляет
     * его пустым (и удаляет файл) — тогда промпт считается как раньше.
     */
    private fun restore(key: PromptCache.Key): Restore {
        val cache = promptCache ?: return Restore.Missing
        val file = cache.file(key)
        if (!file.isFile) return Restore.Missing
        val started = System.nanoTime()
        LlamaNative.memoryClear(context)
        val loaded = LlamaNative.stateLoad(context, file.path, key.prefix)
        android.util.Log.i("LocalChatEngine", String.format(java.util.Locale.ROOT,
            "prompt cache: %s %d tokens in %.2fs", if (loaded) "loaded" else "failed to load", key.prefix.size, seconds(started)))
        if (loaded) return Restore.Loaded
        LlamaNative.memoryClear(context)
        cache.discard(key)
        return Restore.Failed
    }

    /** Записывает кэш — в нём сейчас ровно системная часть — для следующего нового чата. Не вышло — не страшно. */
    private fun save(key: PromptCache.Key) {
        val cache = promptCache ?: return
        if (!cache.prepareDirectory()) return
        val started = System.nanoTime()
        val temporary = cache.temporaryFile(key)
        val saved = LlamaNative.stateSave(context, temporary.path, key.prefix) && cache.commit(key)
        if (!saved) temporary.delete()
        android.util.Log.i("LocalChatEngine", String.format(java.util.Locale.ROOT,
            "prompt cache: %s %d tokens (%d MB) in %.2fs", if (saved) "saved" else "failed to save", key.prefix.size,
            cache.file(key).length() / 1_048_576, seconds(started)))
    }

    // MARK: - Текст и токены

    private fun resetCache() {
        cachedTokens = IntArray(0)
        if (context != 0L) LlamaNative.memoryClear(context)
    }

    private fun render(messages: List<ChatMessage>, thinking: Boolean): String {
        val prompt = chatTemplate?.render(messages, thinking) ?: (fallbackFormat ?: ChatFormat.Plain).render(messages)
        val last = messages.lastOrNull()
        if (last == null || last.role != ChatMessage.Role.Assistant) return prompt
        return continuing(prompt, last.content) ?: prompt
    }

    /**
     * Собирает промпт и, если он не помещается в контекст, выбрасывает самые старые реплики. Системные сообщения
     * и последний вопрос остаются всегда; старое убирается целыми обменами и сразу с запасом (до 60 % бюджета),
     * чтобы начало промпта не менялось на каждой реплике и кэш продолжал работать.
     */
    private fun preparePrompt(messages: List<ChatMessage>, params: GenerationParams, contextLength: Int): Pair<IntArray, Int> {
        val reserve = minOf(params.maxTokens, contextLength / 2)
        val budget = contextLength - reserve - 8
        fun tokensFor(list: List<ChatMessage>) = tokenize(render(list, params.thinking))

        val systemCount = messages.takeWhile { it.role == ChatMessage.Role.System }.size
        val system = messages.subList(0, systemCount)
        var rest = messages.subList(systemCount, messages.size).toList()

        // Тот же диалог, что и в прошлый раз: сразу начинаем с уже выброшенного начала.
        if (droppedHistory.isNotEmpty() && rest.size > droppedHistory.size && rest.subList(0, droppedHistory.size) == droppedHistory) {
            rest = rest.drop(droppedHistory.size)
        } else {
            droppedHistory = emptyList()
        }

        var result = tokensFor(system + rest)
        val soft = params.historyTokens?.coerceAtMost(budget) ?: budget
        if (result.size > soft) {
            val target = soft * 6 / 10
            while (result.size > target) {
                // Первый обмен: от первой реплики до следующего вопроса пользователя.
                val next = rest.drop(1).indexOfFirst { it.role == ChatMessage.Role.User }.takeIf { it >= 0 }?.plus(1) ?: break
                droppedHistory = droppedHistory + rest.subList(0, next)
                rest = rest.drop(next)
                result = tokensFor(system + rest)
            }
        }
        if (result.size > budget) throw EngineException.PromptTooLong(result.size, contextLength)
        return result to droppedHistory.size
    }

    private fun tokenize(text: String): IntArray {
        // Шаблон сам ставит служебный токен начала текста; второй раз добавлять его нельзя.
        val addSpecial = bosText.isEmpty() || !text.startsWith(bosText)
        return LlamaNative.tokenize(model, text.toByteArray(), addSpecial, true) ?: throw EngineException.TokenizationFailed()
    }

    private fun decodeAll(ctx: Long, tokens: IntArray, from: Int) {
        var offset = from
        while (offset < tokens.size) {
            val count = minOf(BATCH, tokens.size - offset)
            val code = LlamaNative.decode(ctx, tokens, offset, count)
            if (code != 0) throw EngineException.DecodeFailed(code)
            offset += count
        }
    }

    private fun seconds(start: Long) = (System.nanoTime() - start) / 1e9

    companion object {
        private const val BATCH = 512
        private const val UTILITY_CONTEXT = 3072
        const val TRIGGERED_ROOT = "triggered-root"

        /**
         * Последняя реплика — начатый ответ модели: промпт обрезается сразу после него, без конца реплики и без
         * нового приглашения ответить. Так модель дописывает тот же ответ. Не нашёлся — `null`.
         */
        fun continuing(prompt: String, partial: String): String? {
            val tail = partial.trim()
            if (tail.isEmpty()) return null
            val index = prompt.lastIndexOf(tail)
            return if (index < 0) null else prompt.substring(0, index + tail.length)
        }

        /** Строковый литерал GBNF, совпадающий ровно с `text`. */
        fun gbnfLiteral(text: String): String {
            val out = StringBuilder("\"")
            for (ch in text) when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(ch)
            }
            return out.append('"').toString()
        }

        /** `text` как регулярное выражение ECMAScript (им пользуется llama.cpp), совпадающее буквально. */
        fun regexEscaped(text: String): String {
            val special = setOf('\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}', '/')
            return buildString { for (ch in text) { if (ch in special) append('\\'); append(ch) } }
        }

        /** Правда, когда после триггера в выводе уже есть закрытый `{…}`: корневое правило выдано целиком. */
        fun grammarOutputComplete(text: String, triggers: List<String>): Boolean {
            for (trigger in triggers) {
                val index = text.indexOf(trigger)
                if (index < 0) continue
                var depth = 0
                var opened = false
                for (ch in text.substring(index + trigger.length)) {
                    if (ch == '{') { depth++; opened = true } else if (ch == '}') depth--
                    if (opened && depth == 0) return true
                }
            }
            return false
        }

        private fun commonPrefix(a: IntArray, b: IntArray): Int {
            var index = 0
            val limit = minOf(a.size, b.size)
            while (index < limit && a[index] == b[index]) index++
            return index
        }

        /** Число производительных ядер: у телефонных процессоров «большие» ядра с самой высокой частотой. */
        fun performanceCoreCount(): Int {
            val frequencies = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
                runCatching { File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
            }
            if (frequencies.isEmpty()) return maxOf(1, Runtime.getRuntime().availableProcessors() / 2)
            val top = frequencies.max()
            // Средние ядра (не ниже 70 % от верхней частоты) тоже ускоряют расчёт; маленькие — только мешают.
            return frequencies.count { it >= top * 7 / 10 }.coerceIn(2, 8)
        }
    }
}

/**
 * Токен может оборваться посреди многобайтового символа (кириллица — два байта, эмодзи — четыре).
 * Наружу отдаются только целые символы, хвост ждёт следующего токена.
 */
internal class Utf8Accumulator {
    private val bytes = java.io.ByteArrayOutputStream()

    fun append(piece: ByteArray): String? {
        bytes.write(piece)
        val all = bytes.toByteArray()
        for (trailing in 0..minOf(3, all.size)) {
            val end = all.size - trailing
            if (end <= 0) return null
            val decoded = decodeStrict(all, end) ?: continue
            bytes.reset()
            bytes.write(all, end, all.size - end)
            return decoded
        }
        // Больше трёх «висящих» байт — это уже мусор, а не оборванный символ: отдаём как есть.
        bytes.reset()
        return all.decodeToString()
    }

    fun flush(): String? {
        if (bytes.size() == 0) return null
        val text = bytes.toByteArray().decodeToString()
        bytes.reset()
        return text
    }

    private fun decodeStrict(data: ByteArray, length: Int): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(data, 0, length)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}
