package alexcattivo.localchat.kit.web

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.EngineController

/** Что делать с вопросом: искать (и что именно) или обойтись без интернета. */
sealed interface QueryPlan {
    data class Search(val query: String) : QueryPlan
    data object NotNeeded : QueryPlan
}

/**
 * Составляет поисковый запрос моделью с учётом разговора: «а сколько она весит?» после вопроса про Эйфелеву башню
 * превращается в «вес Эйфелевой башни». Работает во втором контексте движка, кэш диалога не сбивается.
 */
object QueryPlanner {
    val instruction = """
        Ты составляешь запрос для поиска в интернете. По последнему вопросу пользователя, с учётом предыдущего разговора, напиши поисковый запрос из 2–8 слов на языке вопроса. Вместо местоимений («он», «она», «это», «там») подставь, о чём речь, а описания заменяй точными названиями, если понятно, о чём речь. Если интернет для ответа не нужен (приветствие, благодарность, просьба пошутить, вопрос о самом собеседнике, рассказ о себе или просьба что-то запомнить), ответь одним словом: НЕТ. Ответь только запросом, без кавычек и пояснений.
        Примеры:
        «Привет! Как дела?» → НЕТ
        «Спасибо, ты очень помог!» → НЕТ
        «Расскажи анекдот» → НЕТ
        «Как тебя зовут?» → НЕТ
        «Меня зовут Алекс, у меня собака Рекс. Запиши это.» → НЕТ
        «Сколько весит синий кит?» → вес синего кита
        «Какая погода в Казани завтра?» → погода Казань завтра
        «Какой высоты самая высокая гора на Земле?» → высота Эвереста
    """.trimIndent()

    /** Сколько последних реплик разговора показывать и до какой длины их обрезать: служебный контекст маленький. */
    const val HISTORY_LIMIT = 4
    const val MESSAGE_LIMIT = 300

    fun recent(history: List<ChatMessage>): List<String> =
        history.filter { it.role != ChatMessage.Role.System }.takeLast(HISTORY_LIMIT).map { message ->
            val who = if (message.role == ChatMessage.Role.User) "Пользователь" else "Собеседник"
            val text = message.content.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            "$who: " + if (text.length > MESSAGE_LIMIT) text.take(MESSAGE_LIMIT) + "…" else text
        }

    /** [note] adds the host app's own rules (what it answers from its own data, so needs no search). */
    fun messages(history: List<ChatMessage>, question: String, note: String? = null): List<ChatMessage> {
        val lines = recent(history)
        var body = ""
        if (lines.isNotEmpty()) body += "Разговор:\n" + lines.joinToString("\n") + "\n\n"
        body += "Последний вопрос: $question\n\nОтвет (запрос или НЕТ):"
        val system = listOfNotNull(instruction, note).filter { it.isNotEmpty() }.joinToString("\n\n")
        return listOf(ChatMessage(ChatMessage.Role.System, system), ChatMessage(ChatMessage.Role.User, body))
    }

    /** Разбирает ответ модели. `null` — ответ негодный, нужен запасной способ. */
    fun parse(raw: String): QueryPlan? {
        var line = raw.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        for (prefix in listOf("поисковый запрос:", "запрос:", "query:", "search:")) {
            if (line.lowercase().startsWith(prefix)) line = line.drop(prefix.length)
        }
        line = line.trim(' ', '\t', '«', '»', '"', '\'', '`', '*', '.', ',', ':', ';', '!', '?')
        line = line.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (line.lowercase() in setOf("нет", "no", "none")) return QueryPlan.NotNeeded
        if (line.isEmpty() || line.length > 150) return null
        return QueryPlan.Search(line)
    }

    /** `null`, если модель недоступна или ответила что-то непонятное. */
    suspend fun plan(history: List<ChatMessage>, question: String, controller: EngineController, note: String? = null): QueryPlan? {
        val raw = runCatching { controller.complete(messages(history, question, note), maxTokens = 24) }.getOrNull() ?: return null
        return parse(raw)
    }
}
