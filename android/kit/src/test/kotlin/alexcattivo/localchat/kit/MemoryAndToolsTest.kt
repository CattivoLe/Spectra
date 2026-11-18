package alexcattivo.localchat.kit

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.EngineController
import alexcattivo.localchat.kit.tools.MemoryTools
import alexcattivo.localchat.kit.tools.ToolCalls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAndToolsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun makeSession(engine: ScriptedEngine, store: ChatStore = ChatStore(JdbcDatabase()), tools: Boolean = true): Pair<ChatSession, ChatStore> {
        val controller = EngineController(engine)
        controller.load("/tmp/m.gguf")
        val session = ChatSession(store.createChat("Будь краток."), store, controller, scope)
        if (tools) {
            session.tools = MemoryTools.registry(store)
            session.usesNotes = true
        }
        return session to store
    }

    @Test fun modelCallsRememberAndThenAnswersFromTheResult() = runBlocking {
        val engine = ScriptedEngine { turn -> if (turn == 1) "<tool_call>{\"tool\": \"remember\", \"text\": \"Собеседник любит кофе\"}" else "Запомнил!" }
        val (session, store) = makeSession(engine)
        session.send("Я очень люблю кофе")
        session.waitUntilIdle()
        assertEquals(listOf("Собеседник любит кофе"), store.notes().map { it.text })
        assertTrue(session.messages.last().content.startsWith("Запомнил!"))
        assertFalse(session.messages.last().content.contains("tool_call"))
        assertEquals(listOf("remember"), session.toolRuns[session.messages.last().id])
        assertNotNull(engine.receivedParams[0].grammar)
        assertEquals(listOf("<tool_call>"), engine.receivedParams[0].grammarTriggers)
        assertTrue("модель знает, как вызывать", engine.received[0][0].content.contains("<tool_call>{\"tool\": \"remember\""))
        val result = engine.received[1].last()
        assertTrue(result.role == ChatMessage.Role.User && result.content.contains("<tool_result name=\"remember\">"))
        assertTrue(result.content.contains("Заметка сохранена."))
    }

    @Test fun plainRememberRequestIsRunByTheAppAndNotesReachLaterChats() = runBlocking {
        val engine = ScriptedEngine { "Хорошо." }
        val (session, store) = makeSession(engine)
        session.send("Запомни, что я не ем мясо.")
        session.waitUntilIdle()
        assertEquals(listOf("Со слов собеседника: я не ем мясо"), store.notes().map { it.text })
        assertEquals("вызов сделало приложение, модель только ответила", 1, engine.received.size)
        assertTrue(engine.received[0].any { it.role == ChatMessage.Role.User && it.content.contains("<tool_result") })

        val (next, _) = makeSession(engine, store)
        next.send("Что приготовить на ужин?")
        next.waitUntilIdle()
        assertTrue(engine.received[1][0].content.contains("- Со слов собеседника: я не ем мясо"))

        next.send("Забудь, что я не ем мясо")
        next.waitUntilIdle()
        assertTrue(store.notes().isEmpty())
    }

    @Test fun toolCallIsNeitherShownNorSpoken() = runBlocking {
        val engine = ScriptedEngine { turn -> if (turn == 1) "Сейчас запомню. <tool_call>{\"tool\": \"remember\", \"text\": \"кофе\"}" else "Готово." }
        val (session, _) = makeSession(engine)
        val spoken = StringBuilder()
        session.onPiece = { spoken.append(it) }
        session.send("Я пью кофе")
        session.waitUntilIdle()
        assertFalse(spoken.contains("<"))
        assertTrue(spoken.contains("Сейчас запомню.") && spoken.contains("Готово."))
        val answer = session.messages.last().content
        assertTrue(answer.startsWith("Сейчас запомню.") && answer.contains("Готово.") && !answer.contains("tool"))
    }

    @Test fun withoutToolsNothingChanges() = runBlocking {
        val engine = ScriptedEngine { "Привет" }
        val (session, store) = makeSession(engine, tools = false)
        session.send("Запомни, что я люблю чай")
        session.waitUntilIdle()
        assertTrue(store.notes().isEmpty())
        assertNull(engine.receivedParams[0].grammar)
        assertFalse(engine.received[0][0].content.contains("tool_call"))
    }

    @Test fun preflightAndVisiblePart() {
        assertTrue(MemoryTools.preflightCalls("Запомни, что у меня кошка Муся.").first().contains("у меня кошка Муся"))
        assertTrue(MemoryTools.preflightCalls("Remember that I live in Kazan").first().contains("I live in Kazan"))
        assertTrue(MemoryTools.preflightCalls("Забудь про кошку").first().contains("\"forget\""))
        assertTrue(MemoryTools.preflightCalls("Как запомнить таблицу умножения?").isEmpty())
        assertTrue(MemoryTools.preflightCalls("Запомни").isEmpty())
        assertEquals("Привет ", ToolCalls.visiblePart("Привет <tool_call>{", "<tool_call>"))
        assertEquals("Привет ", ToolCalls.visiblePart("Привет <tool", "<tool_call>"))
        assertEquals("a < b", ToolCalls.visiblePart("a < b", "<tool_call>"))
    }

    @Test fun notesAreDeduplicatedAndLimited() = runBlocking {
        val store = ChatStore(JdbcDatabase())
        assertNotNull(store.addNote("Любит кофе"))
        assertNull(store.addNote("  любит   КОФЕ "))
        for (i in 0 until MemoryNote.LIMIT) store.addNote("Заметка $i")
        val notes = store.notes()
        assertEquals(MemoryNote.LIMIT, notes.size)
        assertFalse("сверх лимита уходят самые старые", notes.any { it.text == "Любит кофе" })
        assertTrue(store.deleteNotes("заметка 1") >= 1)
        store.deleteAllNotes()
        assertTrue(store.notes().isEmpty())
    }

    @Test fun pastChatsAreRecalledForARelatedQuestion() = runBlocking {
        val engine = ScriptedEngine { turn -> if (turn == 1) "Для дома лучше взять камеру Tapo C200: она недорогая, надёжная и снимает ночью." else "Это была Tapo C200." }
        val store = ChatStore(JdbcDatabase())
        val (first, _) = makeSession(engine, store, tools = false)
        first.send("Какую камеру видеонаблюдения взять для дома?")
        first.waitUntilIdle()

        val (second, _) = makeSession(engine, store, tools = false)
        second.recallsPastChats = true
        second.send("Напомни, какую камеру для дома ты советовал?")
        second.waitUntilIdle()
        val question = engine.received[1].last()
        assertTrue(question.content.contains("<earlier_chats>") && question.content.contains("Tapo C200"))
        assertEquals(1, second.recollections[second.messages[0].id]?.size)

        val (third, _) = makeSession(engine, store, tools = false)
        third.send("Напомни, какую камеру для дома ты советовал?")
        third.waitUntilIdle()
        assertFalse(engine.received[2].last().content.contains("earlier_chats"))
    }

    @Test fun profileReachesThePromptAndPersonasAreEditable() = runBlocking {
        val engine = ScriptedEngine { "Привет, Алекс" }
        val store = ChatStore(JdbcDatabase())
        store.saveProfile(UserProfile(name = "Алекс", address = UserProfile.Address.Informal, about = "Люблю велосипед"))
        val (session, _) = makeSession(engine, store, tools = false)
        session.send("Привет")
        session.waitUntilIdle()
        val system = engine.received[0][0].content
        assertTrue(system.contains("Собеседника зовут Алекс.") && system.contains("на «ты»") && system.contains("Люблю велосипед"))
        store.saveProfile(UserProfile(isEnabled = false, name = "Алекс"))
        assertNull(store.profile()!!.promptBlock)
        store.deleteProfile()
        assertNull(store.profile())

        val builtIn = store.personas().first()
        store.save(builtIn.copy(name = "Взлом"))
        assertEquals("встроенного не изменить", builtIn.name, store.personas().first().name)
        val copy = store.duplicate(builtIn)
        store.save(copy.copy(systemPrompt = "Новый промпт", temperature = 0.3))
        assertEquals("Новый промпт", store.persona(copy.id)!!.systemPrompt)
        store.deletePersona(builtIn.id)
        store.deletePersona(copy.id)
        assertEquals(4, store.personas().size)
        assertEquals(AnswerLength.Medium, AnswerLength.nearest(700))
    }

    @Test fun memoryRequestsDoNotGoToTheWebAndOneSharedWordIsNotRecall() = runBlocking {
        assertTrue(MemoryTools.isMemoryRequest("My name is Alex and I have a dog named Rex. Save this to your notes, please."))
        assertTrue(MemoryTools.isMemoryRequest("У меня собака Рекс, запиши это"))
        assertFalse(MemoryTools.isMemoryRequest("Какая высота у Эйфелевой башни?"))

        val engine = ScriptedEngine { "Для Италии: Рим, Милан и Неаполь — у каждого своя история и кухня." }
        val store = ChatStore(JdbcDatabase())
        val (first, _) = makeSession(engine, store, tools = false)
        first.send("Name three cities in Italy")
        first.waitUntilIdle()
        val (second, _) = makeSession(engine, store, tools = false)
        second.recallsPastChats = true
        second.send("My name is Alex and I have a dog named Rex")
        second.waitUntilIdle()
        assertTrue("одно общее слово name — не тот же разговор", second.recollections.isEmpty())
    }
}
