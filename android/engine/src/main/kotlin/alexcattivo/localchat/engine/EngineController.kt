package alexcattivo.localchat.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Хозяин движка в приложении: один запрос за раз, выгрузка модели при нехватке памяти и загрузка обратно
 * при следующем запросе. Состояние наблюдаемо из Compose, как @Observable в iOS-версии.
 */
class EngineController(private val engine: InferenceEngine) {
    enum class State {
        Empty, Loading, Ready, Generating,
        /** Модель выгружена из-за нехватки памяти и будет загружена заново при следующем запросе. */
        UnloadedForMemory,
    }

    var state by mutableStateOf(State.Empty)
        private set
    var info by mutableStateOf<ModelInfo?>(null)
        private set

    private var modelPath: String? = null
    private var config = EngineConfig()
    private var generation: Job? = null

    /** Загружает модель. Прежняя выгружается. */
    suspend fun load(path: String, config: EngineConfig = EngineConfig()) {
        stop()
        state = State.Loading
        modelPath = path
        this.config = config
        try {
            // Загрузку не прервать: llama.cpp грузит файл в одном вызове. Если отменить вызывающую корутину
            // (меню, из которого выбрали модель, закрылось), модель всё равно загрузится, а состояние без
            // NonCancellable осталось бы Empty — и чат навсегда показывал бы «Загружаю модель…».
            info = withContext(NonCancellable) { engine.load(path, config) }
            state = State.Ready
        } catch (error: Throwable) {
            modelPath = null
            info = null
            state = State.Empty
            throw error
        }
    }

    suspend fun unload() {
        stop()
        engine.unload()
        modelPath = null
        info = null
        state = State.Empty
    }

    /** Запускает генерацию. Если модель была выгружена из-за памяти, сначала загружает её снова. */
    fun send(messages: List<ChatMessage>, params: GenerationParams = GenerationParams()): Flow<GenerationEvent> = callbackFlow {
        if (state == State.Generating || state == State.Loading) throw EngineException.Busy()
        val path = modelPath ?: throw EngineException.NoModelLoaded()
        // Состояние меняется сразу: иначе два запроса подряд оба прошли бы проверку выше.
        val needsReload = state == State.UnloadedForMemory
        state = if (needsReload) State.Loading else State.Generating
        val job = launch {
            try {
                if (needsReload) info = engine.load(path, config)
                state = State.Generating
                engine.generate(messages, params).collect { send(it) }
                close()
            } catch (error: CancellationException) {
                close()
            } catch (error: Throwable) {
                if (state == State.Loading) state = State.UnloadedForMemory
                close(error)
            } finally {
                if (state == State.Generating) state = State.Ready
            }
        }
        generation = job
        awaitClose { job.cancel() }
    }

    /** Короткий служебный ответ (например, поисковый запрос). Только когда модель загружена и свободна. */
    suspend fun complete(messages: List<ChatMessage>, maxTokens: Int = 32): String {
        if (state != State.Ready) {
            throw if (state == State.Generating || state == State.Loading) EngineException.Busy() else EngineException.NoModelLoaded()
        }
        return engine.complete(messages, maxTokens)
    }

    /** Сколько токенов займёт диалог в контексте. Считается, только пока модель загружена и свободна. */
    suspend fun countTokens(messages: List<ChatMessage>, thinking: Boolean = false): Int? =
        if (state == State.Ready) engine.countTokens(messages, thinking) else null

    /** Останавливает текущую генерацию. Движок заканчивает на ближайшем токене. */
    fun stop() {
        generation?.cancel()
        generation = null
        // A batch of the prompt can take tens of seconds: break it off instead of waiting for its end.
        engine.abort()
    }

    /** Система просит освободить память (onTrimMemory). */
    suspend fun handleMemoryWarning() {
        if (state == State.Empty || state == State.Loading) return
        val running = generation
        stop()
        // Выгружать модель под работающим вычислением нельзя: дожидаемся конца.
        running?.join()
        engine.unload()
        info = null
        state = State.UnloadedForMemory
    }
}
