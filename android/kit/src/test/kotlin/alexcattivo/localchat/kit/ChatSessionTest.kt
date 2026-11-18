package alexcattivo.localchat.kit

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.EngineController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun makeSession(engine: ScriptedEngine, systemPrompt: String = "Будь краток."): Pair<ChatSession, ChatStore> {
        val store = ChatStore(JdbcDatabase())
        val controller = EngineController(engine)
        controller.load("/tmp/m.gguf")
        val chat = store.createChat(systemPrompt)
        return ChatSession(chat, store, controller, scope) to store
    }

    @Test fun sendStoresQuestionAndAnswerAndPassesSystemPrompt() = runBlocking {
        val engine = ScriptedEngine { "Здравствуй, путник" }
        val (session, store) = makeSession(engine)
        session.send("  Привет  ")
        session.waitUntilIdle()

        assertEquals(listOf(ChatMessage.Role.User, ChatMessage.Role.Assistant), session.messages.map { it.role })
        assertEquals("Привет", session.messages[0].content)
        assertTrue(session.messages[1].content.startsWith("Здравствуй, путник"))
        assertFalse(session.messages[1].interrupted)
        assertEquals("Привет", session.chat.title)
        assertEquals(listOf(ChatMessage.Role.System, ChatMessage.Role.User), engine.received[0].map { it.role })
        assertEquals("Будь краток.", engine.received[0][0].content)
        assertEquals(2, store.messages(session.chat.id).size)
        assertFalse(session.isGenerating)
    }

    @Test fun stopKeepsPartialAnswerMarkedInterrupted() = runBlocking {
        val engine = ScriptedEngine(pauseMillis = 10) { (0 until 200).joinToString(" ") { "слово$it" } }
        val (session, store) = makeSession(engine)
        session.send("Расскажи")
        while ((session.messages.lastOrNull()?.content?.length ?: 0) < 30) delay(5)
        session.stop()
        session.waitUntilIdle()

        val answer = session.messages.last()
        assertEquals(ChatMessage.Role.Assistant, answer.role)
        assertTrue(answer.interrupted)
        assertTrue(answer.content.startsWith("слово0"))
        assertTrue(answer.content.length < 1000)
        val stored = store.messages(session.chat.id).last()
        assertEquals(answer.content, stored.content)
        assertTrue(stored.interrupted)
    }

    @Test fun regenerateReplacesLastAnswer() = runBlocking {
        val engine = ScriptedEngine { "ответ номер $it" }
        val (session, store) = makeSession(engine)
        session.send("Привет")
        session.waitUntilIdle()
        session.regenerate()
        session.waitUntilIdle()

        assertEquals(listOf(ChatMessage.Role.User, ChatMessage.Role.Assistant), session.messages.map { it.role })
        assertTrue(session.messages[1].content.startsWith("ответ номер 2"))
        assertEquals(listOf(ChatMessage.Role.System, ChatMessage.Role.User), engine.received[1].map { it.role })
        assertEquals(2, store.messages(session.chat.id).size)
    }

    @Test fun editingEarlierQuestionDropsEverythingAfterIt() = runBlocking {
        val engine = ScriptedEngine { "ответ $it" }
        val (session, store) = makeSession(engine)
        session.send("Первый")
        session.waitUntilIdle()
        session.send("Второй")
        session.waitUntilIdle()
        session.edit(session.messages[0].id, "Исправленный")
        session.waitUntilIdle()

        assertEquals(listOf("Исправленный"), session.messages.filter { it.role == ChatMessage.Role.User }.map { it.content })
        assertEquals(2, session.messages.size)
        assertEquals("Исправленный", session.chat.title)
        assertEquals(2, store.messages(session.chat.id).size)
    }

    @Test fun continueFinishesTheInterruptedAnswerInPlace() = runBlocking {
        val engine = ScriptedEngine(pauseMillis = 5) { turn ->
            if (turn == 1) (0 until 200).joinToString(" ") { "слово$it" } else "и конец."
        }
        val (session, store) = makeSession(engine)
        session.send("Расскажи")
        while ((session.messages.lastOrNull()?.content?.length ?: 0) < 30) delay(5)
        session.stop()
        session.waitUntilIdle()
        val partial = session.messages.last().content.trim()
        assertTrue(session.messages.last().interrupted)

        session.continueGenerating()
        session.waitUntilIdle()
        val sent = engine.received.last()
        assertEquals(ChatMessage.Role.Assistant, sent.last().role)
        assertEquals(partial, sent.last().content)
        assertEquals(2, session.messages.size)
        val answer = session.messages.last()
        assertTrue(answer.content.startsWith(partial) && answer.content.contains("и конец."))
        assertFalse(answer.interrupted)
        assertEquals(answer.content, store.messages(session.chat.id).last().content)
    }

    @Test fun contextUsageIsCountedAfterAnswer() = runBlocking {
        val engine = ScriptedEngine { "Короткий ответ" }
        val (session, _) = makeSession(engine)
        session.send("Привет")
        session.waitUntilIdle()
        val usage = session.context!!
        assertTrue(usage.used > 0)
        assertEquals(4096, usage.total)
        assertFalse(usage.isOverflowing)
    }

    @Test fun retractLastQuestionRemovesItAndTheAnswer() = runBlocking {
        val engine = ScriptedEngine { "ответ" }
        val (session, store) = makeSession(engine)
        session.send("вопрос")
        session.waitUntilIdle()
        assertEquals("вопрос", session.retractLastQuestion())
        assertTrue(session.messages.isEmpty())
        assertEquals("", session.chat.title)
        assertTrue(store.messages(session.chat.id).isEmpty())
    }
}
