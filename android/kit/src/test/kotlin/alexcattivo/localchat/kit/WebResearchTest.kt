package alexcattivo.localchat.kit

import alexcattivo.localchat.kit.web.AnswerCheck
import alexcattivo.localchat.kit.web.Chunker
import alexcattivo.localchat.kit.web.ContentExtractor
import alexcattivo.localchat.kit.web.ExtractedPage
import alexcattivo.localchat.kit.web.FetchedPage
import alexcattivo.localchat.kit.web.PageFetchException
import alexcattivo.localchat.kit.web.PageFetcher
import alexcattivo.localchat.kit.web.PageFetching
import alexcattivo.localchat.kit.web.Passage
import alexcattivo.localchat.kit.web.PassageEmbedder
import alexcattivo.localchat.kit.web.PassageSelector
import alexcattivo.localchat.kit.web.QueryPlan
import alexcattivo.localchat.kit.web.QueryPlanner
import alexcattivo.localchat.kit.web.RankingMethod
import alexcattivo.localchat.kit.web.ResearchStage
import alexcattivo.localchat.kit.web.SearchPayload
import alexcattivo.localchat.kit.web.SearchProvider
import alexcattivo.localchat.kit.web.SearchResult
import alexcattivo.localchat.kit.web.Terms
import alexcattivo.localchat.kit.web.WebAccess
import alexcattivo.localchat.kit.web.WebResearch
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

val articleHtml = """
<html><head><title>Сайт про Париж</title><meta property="og:title" content="Высота Эйфелевой башни"></head>
<body>
  <header><nav><a href="/">Главная</a> <a href="/news">Новости</a> <a href="/about">О нас и о том, как мы работаем каждый день</a></nav></header>
  <div class="sidebar"><p>Реклама: купите тур в Париж по самой низкой цене прямо сейчас, скидки до 50 процентов!</p></div>
  <article>
    <h1>Высота Эйфелевой башни</h1>
    <p>Эйфелева башня — металлическая башня в центре Парижа, построенная в 1889 году к Всемирной выставке.</p>
    <h2>Сколько метров</h2>
    <p>Нынешняя высота башни составляет 330 метров вместе с антенной; без неё — около 300 метров.</p>
    <table><tr><td>Год</td><td>Высота</td></tr><tr><td>1889</td><td>312 м</td></tr></table>
    <ul><li><p>Масса металлической конструкции около 7300 тонн, всего башня весит около 10 000 тонн.</p></li></ul>
    <script>var tracker = "секретный счётчик";</script>
  </article>
  <footer><p>© Все права защищены. Копирование материалов сайта запрещено без разрешения редакции.</p></footer>
</body></html>
"""

class FakeProvider(private val results: List<SearchResult>) : SearchProvider {
    override val name = "Тестовый поиск"
    val queries = mutableListOf<String>()
    override suspend fun search(query: String, limit: Int): List<SearchResult> { queries += query; return results.take(limit) }
}

class FakeFetcher(private val pages: Map<String, String>) : PageFetching {
    override suspend fun fetch(url: String) = FetchedPage(url, pages[url] ?: throw PageFetchException.Http(500))
}

class WebResearchTest {
    private val results = listOf(
        SearchResult("Эйфелева башня — Википедия", "https://wiki.example/tower", "Эйфелева башня — металлическая башня в центре Парижа."),
        SearchResult("Высота башни", "https://paris.example/tower", "История и высота Эйфелевой башни."),
        SearchResult("Туры в Париж", "https://tours.example/", "Купить тур недорого."),
    )

    @Test fun extractorTakesArticleTextAndDropsNoise() {
        val page = ContentExtractor.extract(articleHtml, "https://paris.example/tower")
        assertEquals("Высота Эйфелевой башни", page.title)
        val text = page.text
        assertTrue(text.contains("Нынешняя высота башни составляет 330 метров"))
        assertTrue("строки таблицы сохраняются", text.contains("1889 | 312 м"))
        assertTrue(text.contains("Масса металлической конструкции"))
        assertFalse(text.contains("Главная") || text.contains("Реклама") || text.contains("Все права защищены"))
        assertFalse(text.contains("секретный счётчик"))
        assertTrue(page.blocks.any { it.isHeading && it.text == "Сколько метров" })
        assertTrue(ContentExtractor.extract("", "https://x.example").blocks.isEmpty())
    }

    @Test fun chunksKeepHeadingsAndRespectTheLimit() {
        val long = "Длинное предложение про башню и Париж. ".repeat(60)
        val page = ExtractedPage("T", listOf(ExtractedPage.Block("Раздел первый", true), ExtractedPage.Block(long, false)))
        val chunks = Chunker.chunk(page, 0)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.heading == "Раздел первый" && it.text.length <= 1200 })
    }

    @Test fun russianWordFormsMatchAndSelectionFindsTheAnswer() {
        assertEquals(Terms.of("высота"), Terms.of("высоту"))
        val passages = listOf(
            Passage(0, null, "Купите тур в Париж недорого, скидки!", 0),
            Passage(1, null, "Высота башни составляет 330 метров.", 0),
        )
        val chosen = PassageSelector.select(passages, "какая высота башни", 3000)
        assertEquals("330", Regex("\\d+").find(chosen.first().text)?.value)
        assertFalse(chosen.any { it.text.contains("тур") })
    }

    @Test fun onlyPublicWebAddressesAreAllowed() {
        assertTrue(PageFetcher.isAllowed("https://ru.wikipedia.org/wiki/x"))
        for (blocked in listOf("http://192.168.1.1/", "http://10.0.0.5", "http://localhost:8080", "https://nas.local", "ftp://x.org", "http://[::1]/", "http://172.20.1.1"))
            assertFalse(blocked, PageFetcher.isAllowed(blocked))
        assertTrue(WebAccess.validate("https://search.example") != null)
        assertNull("открытый http — только для своей сети", WebAccess.validate("http://search.example"))
        assertTrue(WebAccess.validate("http://192.168.1.85:8080") != null)
    }

    @Test fun decodesOldRussianEncodings() {
        val cp1251 = "<html><head><meta charset=\"windows-1251\"></head><body>Привет</body></html>".toByteArray(Charset.forName("windows-1251"))
        assertTrue(PageFetcher.decode(cp1251, "text/html")!!.contains("Привет"))
        val koi = "Привет".toByteArray(Charset.forName("KOI8-R"))
        assertEquals("Привет", PageFetcher.decode(koi, "text/html; charset=koi8-r"))
    }

    @Test fun readsPagesAndFindsTheFactThatSnippetsDoNotHave() = runBlocking {
        val research = WebResearch(FakeProvider(results), FakeFetcher(mapOf("https://paris.example/tower" to articleHtml)), pagesToRead = 3)
        val stages = mutableListOf<ResearchStage>()
        val payload = research.runWithRetry("высота Эйфелевой башни", "Какая высота у Эйфелевой башни?", null, null, progress = { stages += it })
        assertEquals("две страницы не открылись — это не ошибка всего поиска", 1, payload.pagesRead)
        val evidence = payload.evidence!!
        assertTrue(evidence.any { it.text.contains("330 метров") })
        assertFalse(evidence.any { it.text.contains("Купить тур") })
        assertEquals(listOf(ResearchStage.Searching, ResearchStage.Reading(3), ResearchStage.Selecting), stages)
        val prompt = payload.promptText("Какая высота у Эйфелевой башни?")
        assertTrue("номер источника — его место в выдаче", prompt.contains("[2] Высота башни — https://paris.example/tower"))
        assertFalse(prompt.contains("[3] Туры"))
        assertTrue(payload.promptText("q", spoken = true).contains("не пиши их номера"))
        // Хранится в базе строкой JSON и читается обратно без потерь.
        assertEquals(payload, SearchPayload.fromJson(payload.toJson()))
    }

    @Test fun secondQueryFindsWhatTheFirstMissed() = runBlocking {
        val provider = object : SearchProvider {
            override val name = "p"
            override suspend fun search(query: String, limit: Int) =
                if (query.contains("метр")) listOf(results[1]) else listOf(results[2])
        }
        val research = WebResearch(provider, FakeFetcher(mapOf("https://paris.example/tower" to articleHtml)), pagesToRead = 2)
        val sent = mutableListOf<String>()
        val payload = research.runWithRetry(
            "туры", "Какая высота у Эйфелевой башни?",
            check = { _, evidence, _ -> if (evidence.any { it.text.contains("330") }) null else "высота башни в метрах" },
            approve = null,
            sent = { sent += it },
        )
        assertEquals(listOf("туры", "высота башни в метрах"), sent)
        assertEquals(listOf("высота башни в метрах"), payload.extraQueries)
        assertTrue(payload.evidence!!.any { it.text.contains("330 метров") })
        // Отказ от второго запроса — он не уходит в сеть.
        val declined = mutableListOf<String>()
        research.runWithRetry("туры", "q", { _, _, _ -> "другой запрос" }, { null }, sent = { declined += it })
        assertEquals(listOf("туры"), declined)
    }

    @Test fun hybridRankingPromotesPassageFoundOnlyByMeaning() = runBlocking {
        val passages = listOf(
            Passage(0, null, "Башня высокая, башня в Париже, про башню.", 0),
            Passage(1, null, "Конструкция поднимается на 330 метров над землёй.", 0),
        )
        val embedder = object : PassageEmbedder {
            override suspend fun embedQueries(queries: List<String>) = listOf(floatArrayOf(1f, 0f))
            override suspend fun embedPassages(passages: List<String>) = passages.map { if (it.contains("330")) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
        }
        val order = PassageSelector.order(passages, "высота башни", RankingMethod.Meaning, floatArrayOf(1f, 0f), listOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 0f)))
        assertEquals(1, order.first())
        val research = WebResearch(FakeProvider(emptyList()), FakeFetcher(emptyMap()), embedder = embedder)
        assertTrue(research.select(passages, "Сколько метров в высоту?", "высота").any { it.text.contains("330") })
        // Эмбеддинги упали — отбор всё равно идёт по словам.
        val broken = object : PassageEmbedder {
            override suspend fun embedQueries(queries: List<String>): List<FloatArray> = error("нет модели")
            override suspend fun embedPassages(passages: List<String>): List<FloatArray> = error("нет модели")
        }
        assertTrue(WebResearch(FakeProvider(emptyList()), FakeFetcher(emptyMap()), embedder = broken).select(passages, "башня Париж", "башня").isNotEmpty())
    }

    @Test fun onlyTheBestPassagesByWordsAreEmbedded() = runBlocking {
        val filler = (0 until 200).map { Passage(it % 3, null, "Посторонний текст номер $it про погоду и котиков.", it) }
        val answer = Passage(1, null, "Высота башни — 330 метров.", 500)
        val passages = filler.take(120) + answer + filler.drop(120)
        var embedded = 0
        val embedder = object : PassageEmbedder {
            override suspend fun embedQueries(queries: List<String>) = queries.map { floatArrayOf(1f, 0f) }
            override suspend fun embedPassages(passages: List<String>): List<FloatArray> {
                embedded += passages.size
                return passages.map { if (it.contains("330")) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
            }
        }
        val research = WebResearch(FakeProvider(emptyList()), FakeFetcher(emptyMap()), embedder = embedder)
        val chosen = research.select(passages, "Какая высота башни?", "высота башни")
        assertEquals(WebResearch.MAX_EMBEDDED, embedded)
        assertTrue(chosen.any { it.text.contains("330") })
        // Короткий список уходит в эмбеддинги целиком: по смыслу может подойти кусок без общих слов с вопросом.
        assertEquals(listOf(0, 1, 2), PassageSelector.shortlist(passages.take(3), "высота", 40))
    }

    @Test fun mergeShiftsSourcesAndSkipsDuplicates() {
        val first = WebResearch.Gathered(results.take(2), listOf(Passage(1, null, "a", 0)), 1)
        val second = WebResearch.Gathered(listOf(results[1], results[2]), listOf(Passage(0, null, "dup", 0), Passage(1, null, "b", 0)), 2)
        val merged = WebResearch.merge(first, second)
        assertEquals(3, merged.results.size)
        assertEquals(listOf("a", "b"), merged.passages.map { it.text })
        assertEquals(2, merged.passages.last().sourceIndex)
    }

    @Test fun plannerAndCheckParseTypicalAnswers() {
        assertEquals(QueryPlan.Search("высота Эвереста"), QueryPlanner.parse("Запрос: «высота Эвереста».\nпояснение"))
        assertEquals(QueryPlan.NotNeeded, QueryPlanner.parse("НЕТ"))
        assertNull(QueryPlanner.parse("   "))
        assertEquals(AnswerCheck.Reply.Yes("8849 метров"), AnswerCheck.parse("ДА: 8849 метров"))
        assertEquals(AnswerCheck.Reply.No("высота Эвереста в метрах"), AnswerCheck.parse("**НЕТ:** высота Эвереста в метрах"))
        val evidence = listOf(Passage(0, null, "Высота горы 8 849 метров над уровнем моря.", 0))
        assertTrue(AnswerCheck.isGrounded("8849 метров", evidence))
        assertFalse("число по памяти, а не из найденного", AnswerCheck.isGrounded("330 м", evidence))
    }
}
