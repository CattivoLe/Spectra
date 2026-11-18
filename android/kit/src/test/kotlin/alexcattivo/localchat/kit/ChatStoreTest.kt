package alexcattivo.localchat.kit

import alexcattivo.localchat.engine.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStoreTest {
    @Test fun schemaHasBuiltInPersonasAndCascadeDeletes() = runBlocking {
        val store = ChatStore(JdbcDatabase())
        assertEquals(listOf("Собеседник", "Попутчик из такси", "Помощник", "Ворчун"), store.personas().map { it.name })
        val chat = store.createChat("промпт")
        store.append(ChatMessage.Role.User, "Очень длинный первый вопрос, который точно длиннее сорока символов", chat.id)
        store.append(ChatMessage.Role.Assistant, "ответ", chat.id, "Gemma")
        val loaded = store.chats().single()
        assertEquals("Очень длинный первый вопрос, который точ…", loaded.title)
        assertEquals("Gemma", loaded.modelName)
        assertEquals(listOf(1, 2), store.messages(chat.id).map { it.seq })
        store.deleteChat(chat.id)
        assertTrue(store.messages(chat.id).isEmpty())
    }

    @Test fun readableModelNames() {
        assertEquals("gemma 3 4b", InstalledModel.readableName("gemma-3-4b-it-Q4_K_M.gguf"))
        assertEquals("Qwen3.5 4B", InstalledModel.readableName("Qwen3.5-4B-Instruct-IQ4_XS.gguf"))
        assertEquals("model", InstalledModel.readableName("model.gguf"))
    }

    @Test fun catalogFromTheIosAppDecodes() {
        // In Spectra the catalog is the iOS app's AssistantModels.json.
        val json =java.io.File("../../ios/Spectra/Assistant/AssistantModels.json").readText()
        val models = ModelCatalog.decode(json)
        assertTrue(models.any { it.id == "gemma-4-e2b" && it.recommended })
        assertTrue(models.any { it.isEmbedding })
        assertEquals(ModelFit.Comfortable, ModelCatalog.fit(3_000_000_000, 8_000_000_000))
        assertEquals(ModelFit.TooBig, ModelCatalog.fit(5_500_000_000, 8_000_000_000))
    }
}
