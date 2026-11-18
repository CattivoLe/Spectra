package alexcattivo.localchat.kit

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.EngineController
import alexcattivo.localchat.kit.web.SearchProvider
import alexcattivo.localchat.kit.web.SearchResult
import alexcattivo.localchat.kit.web.SecretStore
import alexcattivo.localchat.kit.web.WebAccess
import alexcattivo.localchat.kit.web.WebSearchException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSessionTest {
    private class MemorySettings : SettingsStore {
        val values = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun getString(key: String) = values[key] as? String
        override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }

    private class MemorySecrets : SecretStore {
        val values = mutableMapOf<String, String>()
        override fun read(account: String) = values[account]
        override fun write(value: String?, account: String) { if (value == null) values.remove(account) else values[account] = value }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Rig(val session: ChatSession, val web: WebAccess, val store: ChatStore, val engine: ScriptedEngine, val provider: FakeProvider)

    private suspend fun rig(enabled: Boolean = true, confirm: Boolean = false, failing: Boolean = false, empty: Boolean = false): Rig {
        val provider = FakeProvider(if (empty) emptyList() else listOf(
            SearchResult("Эйфелева башня", "https://s.example/tower", "Высота 330 метров."),
            SearchResult("Париж", "https://s.example/paris", "Столица Франции."),
        ))
        val web = WebAccess(MemorySettings(), MemorySecrets())
        web.isEnabled = enabled
        web.confirmEachSearch = confirm
        // Здесь проверяется порядок поиска в диалоге; чтение страниц и запрос моделью — в WebResearchTest.
        web.readPages = false
        web.smartQuery = false
        web.providerFactory = {
            if (failing) object : SearchProvider {
                override val name = "сломанный"
                override suspend fun search(query: String, limit: Int): List<SearchResult> = throw WebSearchException.Offline()
            } else provider
        }
        val engine = ScriptedEngine { "Ответ [1]." }
        val store = ChatStore(JdbcDatabase())
        val controller = EngineController(engine)
        controller.load("/tmp/m.gguf")
        val chat = store.createChat("Базовый промпт.")
        return Rig(ChatSession(chat, store, controller, scope, web = web), web, store, engine, provider)
    }

    @Test fun searchResultsReachTheModelInsideTheQuestionOnly() = runBlocking {
        val r = rig()
        r.session.send("Какая высота у Эйфелевой башни?")
        r.session.waitUntilIdle()
        val history = r.engine.received.first()
        assertEquals("системный промпт не меняется: кэш остаётся рабочим", "Базовый промпт.", history[0].content)
        val question = history.last()
        assertEquals(ChatMessage.Role.User, question.role)
        assertTrue(question.content.contains("Высота 330 метров."))
        assertTrue(question.content.contains("[2] Париж"))
        assertTrue(question.content.endsWith("Вопрос: Какая высота у Эйфелевой башни?"))
        assertEquals("Какая высота у Эйфелевой башни?", r.session.messages[0].content)
        assertTrue(r.store.messages(r.session.chat.id).first().searchJson!!.contains("\"query\":\"Какая высота у Эйфелевой башни\""))
        assertNull(r.session.searchNotice)
        assertEquals(listOf("Какая высота у Эйфелевой башни"), r.store.searchLog().map { it.query })
    }

    @Test fun nothingLeavesTheDeviceWhenAccessIsOff() = runBlocking {
        val r = rig(enabled = false)
        r.session.send("Какая высота у Эйфелевой башни?")
        r.session.waitUntilIdle()
        assertTrue(r.provider.queries.isEmpty())
        assertTrue(r.store.searchLog().isEmpty())
    }

    @Test fun confirmationCanEditOrDeclineTheQuery() = runBlocking {
        val r = rig(confirm = true)
        r.web.approve = { "высота башни в метрах" }
        r.session.send("Какая высота у Эйфелевой башни?")
        r.session.waitUntilIdle()
        assertEquals(listOf("высота башни в метрах"), r.provider.queries)

        r.web.approve = { null }
        r.session.send("А сколько она весит?")
        r.session.waitUntilIdle()
        assertEquals("отказ — ничего не уходит, но ответ есть", 1, r.provider.queries.size)
        assertEquals(SearchNotice.Cancelled, r.session.searchNotice)
        assertEquals(ChatMessage.Role.Assistant, r.session.messages.last().role)
    }

    @Test fun voiceModeRespectsTheConfirmationSetting() = runBlocking {
        val r = rig(confirm = true)
        r.session.voiceMode = true
        r.session.send("Какая высота у Эйфелевой башни?")
        r.session.waitUntilIdle()
        assertEquals("в голосе по умолчанию без окна", 1, r.provider.queries.size)
        r.web.voiceSearchWithoutConfirmation = false
        r.session.send("А вес?")
        r.session.waitUntilIdle()
        assertEquals(1, r.provider.queries.size)
        assertEquals(SearchNotice.VoiceNeedsNoConfirmation, r.session.searchNotice)
    }

    @Test fun failuresAndEmptyResultsStillGiveAnAnswerWithNotice() = runBlocking {
        val failing = rig(failing = true)
        failing.session.send("Какая высота у Эйфелевой башни?")
        failing.session.waitUntilIdle()
        assertTrue(failing.session.searchNotice is SearchNotice.Failed)
        assertEquals(false, failing.store.searchLog().single().succeeded)
        assertEquals(ChatMessage.Role.Assistant, failing.session.messages.last().role)

        val empty = rig(empty = true)
        empty.session.send("Какая высота у Эйфелевой башни?")
        empty.session.waitUntilIdle()
        assertTrue(empty.session.searchNotice is SearchNotice.NothingFound)
    }

    @Test fun regenerateReusesStoredResultsWithoutAskingTheNetworkAgain() = runBlocking {
        val r = rig()
        r.session.send("Какая высота у Эйфелевой башни?")
        r.session.waitUntilIdle()
        r.session.regenerate()
        r.session.waitUntilIdle()
        assertEquals(1, r.provider.queries.size)
        assertTrue(r.engine.received.last().last().content.contains("Высота 330 метров."))
        r.session.edit(r.session.messages[0].id, "А какая высота у Биг-Бена?")
        r.session.waitUntilIdle()
        assertEquals("правка вопроса — новый поиск", 2, r.provider.queries.size)
    }

    @Test fun queryExtractionPicksTheQuestion() {
        assertEquals("Какая высота у башни", ChatSession.searchQuery("Привет! Какая высота у башни? Спасибо"))
        assertEquals("Расскажи про Париж", ChatSession.searchQuery("Расскажи про Париж. Подробно"))
    }
}
