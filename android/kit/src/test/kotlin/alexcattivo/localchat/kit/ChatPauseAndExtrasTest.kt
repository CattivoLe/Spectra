package alexcattivo.localchat.kit

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.EngineController
import alexcattivo.localchat.kit.tools.ToolOffer
import alexcattivo.localchat.kit.tools.ToolRegistry
import alexcattivo.localchat.kit.web.ImageQuery
import alexcattivo.localchat.kit.web.ImageResult
import alexcattivo.localchat.kit.web.SearchPayload
import alexcattivo.localchat.kit.web.SearchProvider
import alexcattivo.localchat.kit.web.SearchResult
import alexcattivo.localchat.kit.web.SecretStore
import alexcattivo.localchat.kit.web.WebAccess
import alexcattivo.localchat.kit.web.WebClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Pausing a chat and picking it up, folders, next-step buttons and pictures. */
class ChatPauseAndExtrasTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun session(engine: ScriptedEngine, web: WebAccess? = null): Pair<ChatSession, ChatStore> {
        val store = ChatStore(JdbcDatabase())
        val controller = EngineController(engine)
        controller.load("/tmp/m.gguf")
        return ChatSession(store.createChat("Промпт."), store, controller, scope, web = web) to store
    }

    // MARK: - Folders

    @Test fun foldersHoldChatsAndDeletingOneKeepsTheChats() = runBlocking {
        val store = ChatStore(JdbcDatabase())
        val home = store.createFolder("  Дом ")
        val office = store.createFolder("Офис")
        assertEquals(listOf("Дом", "Офис"), store.folders().map { it.name })
        val inHome = store.createChat(folderID = home.id)
        val loose = store.createChat()
        store.moveChat(loose.id, office.id)
        assertEquals(home.id, store.chat(inHome.id)!!.folderID)
        assertEquals(office.id, store.chat(loose.id)!!.folderID)
        store.renameFolder(office.id, "Работа")
        assertEquals(listOf("Дом", "Работа"), store.folders().map { it.name })
        store.deleteFolder(home.id)
        assertNull(store.chat(inHome.id)!!.folderID)
        assertEquals(listOf("Работа"), store.folders().map { it.name })
        store.deleteEverything()
        assertTrue(store.folders().isEmpty())
    }

    // MARK: - Pause and resume

    @Test fun leavingDuringACheckLeavesTheQuestionWaitingAndResumeDoesNotRunItAgain() = runBlocking {
        val engine = ScriptedEngine { "Готово." }
        val (session, store) = session(engine)
        val runs = AtomicInteger()
        val blocked = CompletableDeferred<Unit>()
        var block = true
        session.tools = ToolRegistry(
            grammar = "root ::= \"{}\"",
            preflight = { listOf("scan_lan", "check_connection") },
            run = { name, _ ->
                runs.incrementAndGet()
                if (name == "check_connection" && block) {
                    blocked.complete(Unit)
                    awaitCancellation()
                }
                "result of $name"
            },
        )
        session.send("Просканируй сеть и проверь интернет")
        withTimeout(5_000) { blocked.await() }
        session.stop()
        session.waitUntilIdle()

        // No empty answer is left behind: the question waits, and the store agrees.
        assertTrue(session.isUnanswered)
        assertEquals(listOf(ChatMessage.Role.User), session.messages.map { it.role })
        assertEquals(1, store.messages(session.chat.id).size)

        block = false
        session.resume()
        session.waitUntilIdle()
        assertEquals(listOf(ChatMessage.Role.User, ChatMessage.Role.Assistant), session.messages.map { it.role })
        // scan_lan finished before the pause and is reused; check_connection ran twice (cut, then finished).
        assertEquals(3, runs.get())
        val prompt = engine.received.last().joinToString("\n") { it.content }
        assertTrue(prompt.contains("result of scan_lan"))
        assertTrue(prompt.contains("result of check_connection"))
        assertFalse(session.isUnanswered)
    }

    @Test fun resumeContinuesAnAnswerCutShort() = runBlocking {
        val engine = ScriptedEngine(pauseMillis = 10) { call -> if (call == 1) (0 until 200).joinToString(" ") { "слово$it" } else "конец" }
        val (session, _) = session(engine)
        session.send("Расскажи")
        while ((session.messages.lastOrNull()?.content?.length ?: 0) < 30) delay(5)
        session.stop()
        session.waitUntilIdle()
        assertTrue(session.messages.last().interrupted)
        session.resume()
        session.waitUntilIdle()
        assertTrue(session.messages.last().content.trimEnd().endsWith("конец"))
        assertFalse(session.messages.last().interrupted)
        assertFalse(session.awaitingText)
    }

    // MARK: - Polish and next steps

    @Test fun polishAndSuggestionsShapeTheFinalAnswer() = runBlocking {
        val engine = ScriptedEngine { "По состоянию на <app_data>: всё спокойно. Проверьте порты роутера." }
        val (session, _) = session(engine)
        session.polish = { it.replace("<app_data>", "данным Spectra") }
        session.tools = ToolRegistry(
            grammar = "root ::= \"{}\"",
            suggest = { answer, _ ->
                if (answer.contains("порты")) listOf(ToolOffer("scan_ports", "сканирование портов", "Просканировать порты?", "Просканируй порты роутера")) else emptyList()
            },
            run = { _, _ -> "" },
        )
        session.send("Что в сети?")
        session.waitUntilIdle()
        val answer = session.messages.last()
        assertFalse(answer.content.contains("<app_data>"))
        assertTrue(answer.content.startsWith("По состоянию на данным Spectra"))
        assertEquals(listOf("Просканируй порты роутера"), session.offers[answer.id]!!.map { it.request })
    }

    // MARK: - Pictures

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
        override fun read(account: String): String? = null
        override fun write(value: String?, account: String) {}
    }

    private class PictureProvider : SearchProvider {
        val imageQueries = mutableListOf<String>()
        override val name = "картинки"
        override suspend fun search(query: String, limit: Int): List<SearchResult> = error("text search must not run")
        override suspend fun searchImages(query: String, limit: Int): List<ImageResult> {
            imageQueries.add(query)
            return listOf(ImageResult("Tile Mate", "https://img.example/t.jpg", "https://img.example/full.jpg", "https://shop.example/tile"))
        }
    }

    @Test fun aPictureQuestionSearchesImagesWithTheModelsQuery() = runBlocking {
        val provider = PictureProvider()
        val web = WebAccess(MemorySettings(), MemorySecrets()).apply {
            isEnabled = true
            confirmEachSearch = false
            providerFactory = { provider }
        }
        val engine = ScriptedEngine { "Это брелок-трекер." }
        engine.completion = { "Query: «Tile Mate tracker»" }
        val (session, store) = session(engine, web)
        session.asksForImages = { it.lowercase().contains("как выглядит") }
        session.send("Как выглядит эта метка?")
        session.waitUntilIdle()

        assertEquals(listOf("Tile Mate tracker"), provider.imageQueries)
        val payload = SearchPayload.fromJson(session.messages.first().searchJson!!)!!
        assertEquals("https://img.example/t.jpg", payload.images!!.single().thumbnail)
        assertTrue(engine.received.last().last().content.contains("уже показаны картинки из интернета по запросу «Tile Mate tracker»"))
        assertEquals(listOf("Tile Mate tracker"), store.searchLog().map { it.query })
    }

    @Test fun aPictureQuestionWithSearchOffSaysSoAndSendsNothing() = runBlocking {
        val provider = PictureProvider()
        val web = WebAccess(MemorySettings(), MemorySecrets()).apply { providerFactory = { provider } }
        val (session, _) = session(ScriptedEngine { "Ответ." }, web)
        session.asksForImages = { true }
        session.send("Покажи фото AirTag")
        session.waitUntilIdle()
        assertEquals(SearchNotice.ImagesNeedWeb, session.searchNotice)
        assertTrue(provider.imageQueries.isEmpty())
    }

    @Test fun imagePayloadSurvivesJsonAndAddressesResolve() {
        val payload = SearchPayload("q", "p", 1L, emptyList(), images = listOf(ImageResult("t", "https://a/1.jpg", "https://a/2.jpg", "https://a/")))
        assertEquals(payload.images, SearchPayload.fromJson(payload.toJson())!!.images)
        assertNull(SearchPayload.fromJson(SearchPayload("q", "p", 1L, emptyList()).toJson())!!.images)
        assertEquals("https://cdn.example/x.png", WebClient.absolute("//cdn.example/x.png", "https://searx.example"))
        assertEquals("https://searx.example/image_proxy?u=1", WebClient.absolute("/image_proxy?u=1", "https://searx.example/"))
        assertNull(WebClient.absolute("data:image/png;base64,AAA", "https://searx.example"))
        assertEquals("Apple AirTag", ImageQuery.parse("Query: \"Apple AirTag\".\nmore"))
    }
}
