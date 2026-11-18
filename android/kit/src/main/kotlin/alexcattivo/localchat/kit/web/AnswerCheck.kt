package alexcattivo.localchat.kit.web

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.EngineController

/** Есть ли в найденном ответ. Если нет — какой запрос попробовать вместо прежнего. */
sealed interface AnswerVerdict {
    data object Enough : AnswerVerdict
    data class Missing(val nextQuery: String?) : AnswerVerdict
}

/**
 * Проверка найденного перед ответом: модель смотрит на отобранные куски и решает, хватает ли их.
 * Работает во втором контексте, как и составление запроса.
 */
object AnswerCheck {
    val instruction = """
        Ты проверяешь, есть ли в найденных отрывках ответ на вопрос пользователя. Если ответ есть, напиши ДА, двоеточие и сам ответ в нескольких словах, взятый из отрывков. Если в отрывках ответа нет (речь о другом или нужного факта не хватает), напиши НЕТ, двоеточие и другой поисковый запрос из 2–8 слов, который скорее найдёт ответ и не повторяет уже испробованные. Ответь одной строкой.
        Примеры:
        ДА: 8849 метров
        НЕТ: высота Эвереста в метрах
    """.trimIndent()

    /** Сколько кусков и какой длины показывать: служебный контекст небольшой, а для решения хватает начала кусков. */
    private const val PASSAGE_LIMIT = 4
    private const val CHARACTERS_PER_PASSAGE = 1000

    fun messages(question: String, passages: List<Passage>, triedQueries: List<String>, history: List<ChatMessage> = emptyList()): List<ChatMessage> {
        // Без разговора «А сколько она весит?» непонятно, о ком речь, и вес любого человека из выдачи сходит за ответ.
        val recent = QueryPlanner.recent(history)
        val shown = passages.take(PASSAGE_LIMIT).mapIndexed { index, passage ->
            val text = passage.text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            "[${index + 1}] " + if (text.length > CHARACTERS_PER_PASSAGE) text.take(CHARACTERS_PER_PASSAGE) + "…" else text
        }
        val context = if (recent.isEmpty()) "" else "Разговор до вопроса:\n" + recent.joinToString("\n") + "\n\n"
        val body = context + "Вопрос: $question\n" +
            "Уже искали: ${triedQueries.joinToString(", ") { "«$it»" }}\n" +
            "Найденные отрывки:\n" + (if (shown.isEmpty()) "(ничего не нашлось)" else shown.joinToString("\n")) + "\n" +
            "Ответ (ДА: ответ или НЕТ: запрос):"
        return listOf(ChatMessage(ChatMessage.Role.System, instruction), ChatMessage(ChatMessage.Role.User, body))
    }

    /** Что модель написала: «ДА: ответ» или «НЕТ: запрос». */
    sealed interface Reply {
        data class Yes(val answer: String?) : Reply
        data class No(val query: String?) : Reply
    }

    fun parse(raw: String): Reply? {
        val line = raw.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val cleaned = line.trim('*', '«', '»', '"', '\'', ' ')
        val upper = cleaned.uppercase()
        val separator = cleaned.indexOfFirst { it == ':' || it == '—' || it == '-' }
        val rest = (if (separator >= 0) cleaned.substring(separator + 1) else "").trim(' ', '*', '«', '»', '"', '\'', '.')
        if (upper.startsWith("ДА") || upper.startsWith("YES")) return Reply.Yes(rest.ifEmpty { null })
        if (!upper.startsWith("НЕТ") && !upper.startsWith("NO")) return null
        return Reply.No((QueryPlanner.parse(rest) as? QueryPlan.Search)?.query)
    }

    /**
     * Опирается ли «ответ» на найденное. Маленькая модель охотно пишет «ДА: 330 м» по памяти, даже когда в кусках про
     * это ничего нет. Числа из ответа обязаны встречаться в кусках; ответ без чисел — хотя бы половиной значимых слов.
     */
    fun isGrounded(answer: String, passages: List<Passage>): Boolean {
        val evidence = passages.joinToString(" ") { it.text }
        val compactEvidence = evidence.filter { !it.isWhitespace() && it != ' ' }
        val numbers = answer.split(Regex("[^\\d,. ]+"))
            .map { part -> part.filter { it.isDigit() || it == ',' || it == '.' }.trim(',', '.') }
            .filter { part -> part.any { it.isDigit() } }
        if (numbers.isNotEmpty()) {
            return numbers.all { compactEvidence.contains(it) || compactEvidence.contains(it.replace('.', ',')) }
        }
        val words = Terms.of(answer).toSet()
        if (words.isEmpty()) return true
        val found = Terms.of(evidence).toSet()
        return words.intersect(found).size >= words.size / 2.0
    }

    /** Решение. `null` — модель недоступна или ответила непонятно: тогда считаем, что хватает. */
    suspend fun verdict(
        question: String,
        passages: List<Passage>,
        triedQueries: List<String>,
        history: List<ChatMessage>,
        controller: EngineController,
        note: String? = null,
    ): AnswerVerdict? {
        val raw = runCatching { controller.complete(messages(question, passages, triedQueries, history), maxTokens = 40) }.getOrNull() ?: return null
        return when (val reply = parse(raw) ?: return null) {
            is Reply.Yes -> {
                val answer = reply.answer
                if (answer == null || isGrounded(answer, passages)) return AnswerVerdict.Enough
                // Ответ взят не из найденного: ищем снова, запрос составляет планировщик по самому вопросу.
                val plan = QueryPlanner.plan(history, question, controller, note)
                AnswerVerdict.Missing((plan as? QueryPlan.Search)?.query)
            }
            is Reply.No -> AnswerVerdict.Missing(reply.query)
        }
    }
}
