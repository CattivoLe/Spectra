package alexcattivo.localchat.kit.web

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/** Этап поиска — для подписи на экране. */
sealed interface ResearchStage {
    data object Planning : ResearchStage
    data object Searching : ResearchStage
    data object Checking : ResearchStage
    data class Retrying(val query: String) : ResearchStage
    data class Reading(val pages: Int) : ResearchStage
    data object Selecting : ResearchStage
}

/**
 * Поиск с чтением страниц: найти → скачать и прочитать первые страницы → нарезать → выбрать куски под вопрос.
 * Кроме кусков страниц в отбор идут и короткие описания из выдачи: в них часто уже есть ответ.
 */
class WebResearch(
    val provider: SearchProvider,
    private val fetcher: PageFetching,
    /** Сколько страниц читать целиком. 0 — только описания из выдачи. */
    private val pagesToRead: Int = 3,
    private val resultCount: Int = 5,
    /** Сколько символов найденного отдать модели: 3600 символов русского текста — около 1300–1500 токенов. */
    private val budget: Int = 3600,
    /** Модель эмбеддингов для отбора по смыслу. Без неё — только по словам. */
    private val embedder: PassageEmbedder? = null,
) {
    /** Все куски-кандидаты до отбора: выдача и прочитанные страницы. */
    data class Gathered(val results: List<SearchResult>, val passages: List<Passage>, val pagesRead: Int)

    /** Отбор: по словам, а если есть модель эмбеддингов — гибрид слов и смысла. Если эмбеддинги не удались — по словам. */
    suspend fun select(passages: List<Passage>, question: String, query: String, method: RankingMethod? = null): List<Passage> {
        val text = "$question $query"
        val chosen = method ?: if (embedder == null) RankingMethod.Words else RankingMethod.Hybrid
        val embedder = embedder
        if (chosen == RankingMethod.Words || embedder == null) {
            return PassageSelector.pack(passages, PassageSelector.order(passages, text, RankingMethod.Words), budget)
        }
        // Три прочитанные страницы дают 200+ кусков, и эмбеддинги всех на процессоре телефона шли по две минуты.
        // По смыслу переранжируются только лучшие по словам; в промпт всё равно попадает несколько кусков.
        val candidates = PassageSelector.shortlist(passages, text, MAX_EMBEDDED)
        val pool = candidates.map { passages[it] }
        val queryVector = runCatching { embedder.embedQueries(listOf(question)).first() }.getOrNull()
        val passageVectors = runCatching { embedder.embedPassages(pool.map { (it.heading?.let { h -> "$h. " } ?: "") + it.text }) }.getOrNull()
        val order = PassageSelector.order(pool, text, chosen, queryVector, passageVectors)
        return PassageSelector.pack(pool, order, budget)
    }

    /**
     * Поиск с одним повторным заходом. `check` смотрит на отобранное и возвращает новый запрос, если ответа нет;
     * `approve` спрашивает пользователя перед вторым запросом (`null` — второго захода не будет);
     * `sent` сообщает о каждом запросе, ушедшем в сеть, — для журнала.
     */
    suspend fun runWithRetry(
        query: String,
        question: String,
        check: (suspend (question: String, evidence: List<Passage>, tried: List<String>) -> String?)?,
        approve: (suspend (String) -> String?)?,
        sent: suspend (String) -> Unit = {},
        progress: suspend (ResearchStage) -> Unit = {},
        /** The first query's results from [SearchCache]: then that query does not go online again. */
        initial: Gathered? = null,
        /** Called with what each query that did go online brought back, for [SearchCache]. */
        fetched: suspend (String, Gathered) -> Unit = { _, _ -> },
    ): SearchPayload = coroutineScope {
        var gathered = initial ?: gather(query, progress).also {
            sent(query)
            fetched(query, it)
        }
        val queries = mutableListOf(query)
        // Без чтения страниц отбирать и проверять нечего: отдаём описания из выдачи как есть.
        if (pagesToRead <= 0) return@coroutineScope SearchPayload(query, provider.name, results = gathered.results, pagesRead = 0)
        if (gathered.results.isNotEmpty()) progress(ResearchStage.Selecting)
        var evidence = if (gathered.results.isEmpty()) emptyList() else select(gathered.passages, question, query)
        if (check != null) {
            progress(ResearchStage.Checking)
            var next: String? = null
            val proposed = check(question, evidence, queries)
            if (proposed != null && queries.none { sameQuery(it, proposed) }) {
                next = if (approve != null) approve(proposed) else proposed
            }
            if (next != null && next.isNotBlank()) {
                ensureActive()
                progress(ResearchStage.Retrying(next))
                val more = gather(next) {}
                sent(next)
                fetched(next, more)
                queries.add(next)
                gathered = merge(gathered, more)
                progress(ResearchStage.Selecting)
                // Слова неудачного первого запроса больше не помогают: отбираем по вопросу и новому запросу.
                evidence = select(gathered.passages, question, next)
            }
        }
        SearchPayload(query, provider.name, results = gathered.results, evidence = evidence, pagesRead = gathered.pagesRead,
            extraQueries = queries.drop(1))
    }

    /** Поиск и чтение страниц, без отбора. */
    suspend fun gather(query: String, progress: suspend (ResearchStage) -> Unit = {}): Gathered = coroutineScope {
        progress(ResearchStage.Searching)
        val results = provider.search(query, maxOf(resultCount, pagesToRead))
        ensureActive()
        if (results.isEmpty()) return@coroutineScope Gathered(emptyList(), emptyList(), 0)
        val snippets = results.mapIndexedNotNull { index, result ->
            if (result.snippet.isEmpty()) null else Passage(index, result.title, result.snippet, -1)
        }
        val passages = mutableListOf<Passage>()
        val toRead = results.take(pagesToRead)
        var pagesRead = 0
        if (toRead.isNotEmpty()) {
            progress(ResearchStage.Reading(toRead.size))
            val pages = toRead.mapIndexed { index, result ->
                async {
                    runCatching {
                        val page = fetcher.fetch(result.url)
                        Chunker.chunk(ContentExtractor.extract(page.html, page.url), index)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
            ensureActive()
            pagesRead = pages.count { it.isNotEmpty() }
            passages += pages.flatten()
        }
        // Описание из выдачи нужно только там, где страницу прочитать не удалось: иначе короткое описание занимает
        // место куска страницы, в котором и есть ответ.
        val read = passages.map { it.sourceIndex }.toSet()
        passages += snippets.filter { it.sourceIndex !in read }
        Gathered(results, passages, pagesRead)
    }

    companion object {
        /** Сколько кусков считать моделью эмбеддингов: на телефоне 200 кусков — около двух минут, 40 — секунд двадцать. */
        const val MAX_EMBEDDED = 40

        fun sameQuery(a: String, b: String) = Terms.of(a).toSet() == Terms.of(b).toSet()

        /** Сливает две выдачи: одинаковые страницы не повторяются, номера источников второй сдвигаются. */
        fun merge(first: Gathered, second: Gathered): Gathered {
            val results = first.results.toMutableList()
            val mapping = HashMap<Int, Int>()
            val duplicates = HashSet<Int>()
            second.results.forEachIndexed { index, result ->
                val existing = results.indexOfFirst { it.url == result.url }
                if (existing >= 0) {
                    mapping[index] = existing
                    duplicates.add(index)
                } else {
                    mapping[index] = results.size
                    results.add(result)
                }
            }
            val added = second.passages.mapNotNull { passage ->
                if (passage.sourceIndex in duplicates) null else mapping[passage.sourceIndex]?.let { passage.copy(sourceIndex = it) }
            }
            val readSecond = (second.passages.filter { it.position >= 0 }.map { it.sourceIndex }.toSet() - duplicates).size
            return Gathered(results, first.passages + added, first.pagesRead + readSecond)
        }
    }
}
