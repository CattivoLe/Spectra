package alexcattivo.localchat.kit

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.EngineConfig
import alexcattivo.localchat.engine.EngineController
import alexcattivo.localchat.engine.GenerationEvent
import alexcattivo.localchat.engine.GenerationParams
import alexcattivo.localchat.engine.InferenceEngine
import alexcattivo.localchat.engine.ModelInfo
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EngineControllerTest {
    /** Как LlamaEngine: загрузка — один блокирующий вызов в собственном потоке, прервать его нельзя. */
    private class BlockingLoadEngine : InferenceEngine {
        private val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        var loaded = false

        override suspend fun load(path: String, config: EngineConfig): ModelInfo = withContext(thread) {
            Thread.sleep(300)
            loaded = true
            ModelInfo("mock", 0, 0, 0, 4096, 1, 0.3, "mock")
        }

        override suspend fun unload() { loaded = false }
        override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> = emptyFlow()
    }

    /** Модель выбрали в меню, и меню закрылось посреди загрузки: состояние всё равно должно стать Ready. */
    @Test fun cancellingTheCallerMidLoadStillEndsReady() = runBlocking {
        val engine = BlockingLoadEngine()
        val controller = EngineController(engine)
        val caller = CoroutineScope(Dispatchers.Default).launch { controller.load("/tmp/m.gguf") }
        delay(50)
        caller.cancel()
        caller.join()
        // Загрузка в движке доходит до конца и после отмены; ждём её.
        while (!engine.loaded) delay(10)
        delay(20)
        assertEquals(EngineController.State.Ready, controller.state)
        assertNotNull(controller.info)
    }
}
