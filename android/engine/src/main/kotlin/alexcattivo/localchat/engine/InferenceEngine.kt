package alexcattivo.localchat.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/** Одно сообщение диалога в нейтральном виде, не привязанном к движку. */
data class ChatMessage(val role: Role, val content: String) {
    enum class Role(val raw: String) { System("system"), User("user"), Assistant("assistant") }
}

/** Параметры загрузки модели. */
data class EngineConfig(
    /** Размер контекста в токенах. От него зависит память под KV-кэш. */
    val contextLength: Int = 4096,
    /** Слоёв на GPU. На Android считаем на процессоре: GPU-бэкенды llama.cpp там пока ненадёжны. */
    val gpuLayers: Int = 0,
    /** Число потоков. 0 — взять число производительных ядер. */
    val threads: Int = 0,
)

/** Параметры одной генерации. */
data class GenerationParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val seed: Int = 42,
    /** Разрешить модели «думать вслух» перед ответом, если она это умеет. */
    val thinking: Boolean = false,
    /** Грамматика GBNF, которой вывод обязан следовать после одной из [grammarTriggers]. */
    val grammar: String? = null,
    /** Строки, после которых включается [grammar]; ищутся в собственном выводе этой генерации. */
    val grammarTriggers: List<String> = emptyList(),
)

/** Что известно о загруженной модели. */
data class ModelInfo(
    val description: String,
    val parameterCount: Long,
    val fileSizeBytes: Long,
    val trainedContextLength: Int,
    val contextLength: Int,
    val threads: Int,
    val loadSeconds: Double,
    /** Каким шаблоном чата форматируются сообщения. */
    val chatFormat: String,
)

enum class StopReason { EndOfTurn, MaxTokens, ContextFull, Cancelled }

/** Итог одной генерации. */
data class GenerationStats(
    /** Длина промпта целиком, в токенах. */
    val promptTokens: Int,
    /** Сколько из них уже лежало в кэше от прошлой реплики и не пересчитывалось. */
    val reusedPromptTokens: Int = 0,
    /** Сколько самых старых сообщений пришлось выбросить, чтобы история влезла в контекст. */
    val droppedMessages: Int = 0,
    val generatedTokens: Int,
    val timeToFirstTokenSeconds: Double,
    val generationSeconds: Double,
    val stopReason: StopReason,
) {
    val promptTokensPerSecond: Double
        get() = if (timeToFirstTokenSeconds > 0) (promptTokens - reusedPromptTokens) / timeToFirstTokenSeconds else 0.0
    val tokensPerSecond: Double
        get() = if (generationSeconds > 0) generatedTokens / generationSeconds else 0.0
}

sealed interface GenerationEvent {
    data class Text(val piece: String) : GenerationEvent
    data class Finished(val stats: GenerationStats) : GenerationEvent
}

/** Ошибки движка. Текст для человека даёт приложение (см. EngineErrorText в app), здесь — только смысл. */
sealed class EngineException(message: String) : Exception(message) {
    class ModelLoadFailed(val file: String) : EngineException("model load failed: $file")
    class ContextCreationFailed : EngineException("context creation failed")
    class NoModelLoaded : EngineException("no model loaded")
    class TokenizationFailed : EngineException("tokenization failed")
    class PromptTooLong(val tokens: Int, val context: Int) : EngineException("prompt too long: $tokens > $context")
    class DecodeFailed(val code: Int) : EngineException("decode failed: $code")
    class Busy : EngineException("busy")
}

/**
 * Граница между приложением и конкретным движком инференса. За ней llama.cpp; в тестах — заглушка.
 */
interface InferenceEngine {
    suspend fun load(path: String, config: EngineConfig): ModelInfo
    suspend fun unload()
    fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent>

    /** Сколько токенов займёт этот диалог в контексте, без обрезки старого. `null`, если считать нечем. */
    suspend fun countTokens(messages: List<ChatMessage>, thinking: Boolean): Int? = null

    /** Короткий служебный ответ целиком, всегда самый вероятный вариант. */
    suspend fun complete(messages: List<ChatMessage>, maxTokens: Int): String {
        val text = StringBuilder()
        generate(messages, GenerationParams(maxTokens = maxTokens, temperature = 0f)).collect { event ->
            if (event is GenerationEvent.Text) text.append(event.piece)
        }
        return text.toString()
    }
}
