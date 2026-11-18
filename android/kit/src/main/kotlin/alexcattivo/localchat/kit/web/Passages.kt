package alexcattivo.localchat.kit.web

import kotlin.math.ln

/** Кусок текста с найденной страницы, который может попасть к модели. */
data class Passage(
    /** Номер источника в выдаче (с нуля); в ответе он виден как [номер + 1]. */
    val sourceIndex: Int,
    /** Ближайший заголовок раздела: помогает модели понять, о чём кусок. */
    val heading: String? = null,
    val text: String,
    /** Порядок внутри страницы; -1 — описание из выдачи. */
    val position: Int,
)

/** Нарезка страницы на куски примерно по 300 токенов: целые абзацы, длинные абзацы — по предложениям. */
object Chunker {
    fun chunk(page: ExtractedPage, sourceIndex: Int, target: Int = 800, limit: Int = 1200, tableChunk: Int = 300): List<Passage> {
        val passages = mutableListOf<Passage>()
        var heading: String? = null
        val buffer = mutableListOf<String>()
        var size = 0
        fun flush() {
            if (buffer.isEmpty()) return
            passages.add(Passage(sourceIndex, heading, buffer.joinToString("\n"), passages.size))
            buffer.clear()
            size = 0
        }
        var tableHeader: String? = null
        val tableRows = mutableListOf<String>()
        // Таблица режется на куски по несколько строк, и в каждом повторяется первая строка (названия столбцов):
        // иначе нужная строка («Доллар США | 79,5») тонет в большом куске среди десятков других.
        fun flushTable() {
            if (tableRows.isEmpty()) return
            val rows = mutableListOf<String>()
            var length = 0
            fun emit() {
                if (rows.isEmpty()) return
                passages.add(Passage(sourceIndex, heading, (listOfNotNull(tableHeader) + rows).joinToString("\n"), passages.size))
                rows.clear()
                length = 0
            }
            for (row in tableRows) {
                if (length + row.length > tableChunk) emit()
                rows.add(row)
                length += row.length + 1
            }
            emit()
            tableHeader = null
            tableRows.clear()
        }
        for (block in page.blocks) {
            if (block.isTableRow) {
                flush()
                if (tableHeader == null) tableHeader = block.text else tableRows.add(block.text)
                continue
            }
            if (tableHeader != null) {
                if (tableRows.isEmpty()) { buffer.add(tableHeader!!); size += tableHeader!!.length }
                tableHeader = if (tableRows.isEmpty()) null else tableHeader
                flushTable()
            }
            if (block.isHeading) {
                flush()
                heading = block.text
                continue
            }
            for (piece in split(block.text, limit)) {
                if (size + piece.length > limit) flush()
                buffer.add(piece)
                size += piece.length + 1
                if (size >= target) flush()
            }
        }
        if (tableHeader != null && tableRows.isEmpty()) buffer.add(tableHeader!!)
        flushTable()
        flush()
        return passages
    }

    /** Абзац длиннее предела режется по концам предложений. */
    fun split(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val parts = mutableListOf<String>()
        var current = ""
        for (sentence in sentences(text)) {
            if (current.length + sentence.length + 1 > limit && current.isNotEmpty()) {
                parts.add(current)
                current = ""
            }
            current += (if (current.isEmpty()) "" else " ") + sentence
            while (current.length > limit) {
                parts.add(current.take(limit))
                current = current.drop(limit)
            }
        }
        if (current.isNotEmpty()) parts.add(current)
        return parts
    }

    /** Предложения со знаком конца: делим после «.», «!», «?», «…», за которыми идёт пробел. */
    fun sentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var previous: Char? = null
        for (character in text) {
            if (character.isWhitespace() && previous != null && previous in ".!?…" && current.isNotEmpty()) {
                result.add(current.toString())
                current.clear()
            } else if (!(character.isWhitespace() && current.isEmpty())) {
                current.append(character)
            }
            previous = character
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }
}

/**
 * Слова для поиска по тексту: нижний регистр, «ё» как «е», без служебных слов, с грубым отсечением окончаний,
 * чтобы «высота», «высоту» и «высоте» считались одним словом.
 */
object Terms {
    private val stopWords = setOf(
        "и", "в", "во", "на", "с", "со", "к", "ко", "по", "о", "об", "от", "до", "за", "из", "у", "для", "про", "при", "без",
        "не", "ни", "но", "а", "или", "что", "как", "так", "это", "этот", "эта", "эти", "то", "же", "ли", "бы", "вот",
        "какой", "какая", "какое", "какие", "каков", "почему", "зачем", "где", "когда", "куда", "кто", "сколько",
        "расскажи", "скажи", "подскажи", "пожалуйста", "привет", "мне", "ты", "вы", "я", "мы", "он", "она", "они", "его", "ее", "их",
        "есть", "был", "была", "было", "были", "быть", "будет", "the", "a", "an", "of", "to", "in", "is", "are", "and", "or", "what", "how", "why",
    )
    private val endings = listOf(
        "иями", "ями", "ами", "ией", "ием", "ого", "его", "ому", "ему", "ыми", "ими", "иях", "ость", "ости",
        "ой", "ей", "ий", "ый", "ая", "яя", "ое", "ее", "ые", "ие", "ую", "юю", "ах", "ях", "ов", "ев", "ам", "ям",
        "ом", "ем", "ию", "ия", "ью", "ть", "ет", "ют", "ут", "ит", "ат", "ят",
        "а", "я", "о", "е", "ы", "и", "у", "ю", "ь", "й",
    )

    fun of(text: String): List<String> =
        text.lowercase().replace('ё', 'е')
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() && it !in stopWords }
            .map(::stem)

    fun stem(word: String): String {
        if (word.length <= 3 || !word.first().isLetter()) return word
        if (word.all { it.code < 128 }) {
            for (suffix in listOf("ing", "ed", "es", "s")) if (word.endsWith(suffix) && word.length - suffix.length >= 3) return word.dropLast(suffix.length)
            return word
        }
        for (ending in endings) if (word.endsWith(ending) && word.length - ending.length >= 3) return word.dropLast(ending.length)
        return word
    }
}

/** Способ отбора кусков: по словам, по смыслу или оба сразу. */
enum class RankingMethod { Words, Meaning, Hybrid }

/** Отбор кусков под вопрос. */
object PassageSelector {
    fun select(passages: List<Passage>, query: String, budget: Int, perSource: Int = 2): List<Passage> {
        if (passages.isEmpty() || Terms.of(query).isEmpty()) return emptyList()
        return pack(passages, order(passages, query, RankingMethod.Words), budget, perSource)
    }

    /** BM25 — классическое ранжирование по совпадению слов с поправкой на их редкость и длину текста. */
    fun bm25(query: List<String>, documents: List<List<String>>, k1: Double = 1.2, b: Double = 0.75): List<Double> {
        val count = documents.size.toDouble()
        val averageLength = maxOf(1.0, documents.sumOf { it.size } / count)
        val documentFrequency = HashMap<String, Int>()
        for (document in documents) for (term in document.toSet()) documentFrequency[term] = (documentFrequency[term] ?: 0) + 1
        return documents.map { document ->
            val frequencies = document.groupingBy { it }.eachCount()
            val length = document.size.toDouble()
            query.sumOf { term ->
                val frequency = frequencies[term]?.toDouble() ?: return@sumOf 0.0
                val df = (documentFrequency[term] ?: 0).toDouble()
                val idf = ln(1 + (count - df + 0.5) / (df + 0.5))
                idf * frequency * (k1 + 1) / (frequency + k1 * (1 - b + b * length / averageLength))
            }
        }
    }

    /**
     * Порядок кусков по убыванию полезности. Гибрид сливает два списка «по местам» (Reciprocal Rank Fusion): кусок,
     * высоко стоящий хотя бы в одном из них, поднимается вверх.
     */
    fun order(
        passages: List<Passage>,
        query: String,
        method: RankingMethod,
        queryVector: FloatArray? = null,
        passageVectors: List<FloatArray>? = null,
    ): List<Int> {
        val wordScores = bm25(Terms.of(query).toSet().toList(), passages.map { Terms.of((it.heading ?: "") + " " + it.text) })
        val wordOrder = wordScores.indices.filter { wordScores[it] > 0 }.sortedByDescending { wordScores[it] }
        if (method == RankingMethod.Words || queryVector == null || passageVectors == null || passageVectors.size != passages.size) return wordOrder
        val similarity = passageVectors.map { vector -> vector.indices.sumOf { (vector[it] * queryVector[it]).toDouble() } }
        val meaningOrder = similarity.indices.sortedByDescending { similarity[it] }
        if (method == RankingMethod.Meaning) return meaningOrder
        val fused = DoubleArray(passages.size)
        wordOrder.forEachIndexed { rank, index -> fused[index] += 1.0 / (60 + rank) }
        meaningOrder.forEachIndexed { rank, index -> fused[index] += 1.0 / (60 + rank) }
        return fused.indices.sortedByDescending { fused[it] }
    }

    /** Набирает куски в заданном порядке с ограничениями по объёму и числу кусков с одного сайта. */
    fun pack(passages: List<Passage>, order: List<Int>, budget: Int, perSource: Int = 2): List<Passage> {
        val chosen = mutableListOf<Passage>()
        val perSourceCount = HashMap<Int, Int>()
        var used = 0
        for (index in order) {
            val passage = passages[index]
            if ((perSourceCount[passage.sourceIndex] ?: 0) >= perSource) continue
            if (used + passage.text.length > budget) continue
            if (chosen.any { it.text == passage.text }) continue
            chosen.add(passage)
            perSourceCount[passage.sourceIndex] = (perSourceCount[passage.sourceIndex] ?: 0) + 1
            used += passage.text.length
        }
        return chosen.sortedWith(compareBy({ it.sourceIndex }, { it.position }))
    }
}

/** Векторы смысла для вопроса и кусков. Разные модели ждут разные пометки перед текстом. */
interface PassageEmbedder {
    suspend fun embedQueries(queries: List<String>): List<FloatArray>
    suspend fun embedPassages(passages: List<String>): List<FloatArray>
}
