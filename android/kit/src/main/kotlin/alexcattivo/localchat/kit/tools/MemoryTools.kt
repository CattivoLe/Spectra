package alexcattivo.localchat.kit.tools

import alexcattivo.localchat.kit.ChatStore
import org.json.JSONObject

/** Заметки модели: «запомни» и «забудь». Сохранённое попадает в промпт следующих разговоров. */
object MemoryTools {
    val grammar = """
        root ::= ws "{" ws "\"tool\"" ws ":" ws tool ws "," ws "\"text\"" ws ":" ws "\"" [^"\n]+ "\"" ws "}" ws
        tool ::= "\"remember\"" | "\"forget\""
        ws   ::= [ \t\n]*
    """.trimIndent()

    val instructions = """
        Ты умеешь запоминать сведения о собеседнике между разговорами. Чтобы сохранить заметку, напиши в ответе ровно это:
        <tool_call>{"tool": "remember", "text": "короткий факт"}
        Чтобы удалить устаревшую или ошибочную заметку:
        <tool_call>{"tool": "forget", "text": "слова из этой заметки"}
        Сразу после закрывающей скобки остановись: тебе придёт результат, и тогда ответь собеседнику обычным текстом. Сохраняй заметку, когда собеседник просит запомнить или рассказывает о себе что-то важное надолго (имена близких, предпочтения, планы, ограничения). Пиши коротко и в третьем лице: «Собеседник не ест мясо». Не сохраняй пароли, номера карт и документов. Не говори, что запомнил, если не получил результат.
    """.trimIndent()

    /** Набор для `ChatSession.tools`. `label` даёт подпись на языке интерфейса. */
    fun registry(store: ChatStore, label: (String) -> String = { it }) = ToolRegistry(
        grammar = grammar,
        instructions = instructions,
        maxSteps = 3,
        preflight = ::preflightCalls,
        skipsWebSearch = ::isMemoryRequest,
        runningLabel = label,
    ) { name, argsJSON -> run(name, argsJSON, store) }

    suspend fun run(name: String, argsJSON: String, store: ChatStore): String {
        val text = ToolCalls.argument(argsJSON, "text") ?: return "Не хватает \"text\": укажи, что именно сохранить или удалить."
        return when (name) {
            "remember" -> runCatching { if (store.addNote(text) == null) "Такая заметка уже есть." else "Заметка сохранена." }
                .getOrElse { "Сохранить не удалось: ${it.message}" }
            "forget" -> {
                val removed = runCatching { store.deleteNotes(text) }.getOrDefault(0)
                if (removed > 0) "Удалено заметок: $removed." else "Подходящей заметки нет."
            }
            else -> "Такого действия нет. Есть только remember и forget."
        }
    }

    private val patterns = listOf(
        "remember" to Regex("^(?:пожалуйста[,\\s]+)?(?:запомни|remember)(?:[,:\\s]+(?:что|that|про|о|об))?[,:\\s]+(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        "forget" to Regex("^(?:пожалуйста[,\\s]+)?(?:забудь|forget)(?:[,:\\s]+(?:что|that|про|о|об|about))?[,:\\s]+(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
    )

    /** Вопрос о заметках или просьба запомнить где угодно в тексте («…собака Рекс. Запиши это»). */
    fun isMemoryRequest(question: String): Boolean =
        Regex("(запомни|запиши|забудь|в заметк|заметку|remember|forget|your notes|a note|to notes)").containsMatchIn(question.lowercase())

    /** «Запомни, что…» и «Забудь…» в начале вопроса выполняются сразу, не дожидаясь, сообразит ли модель. */
    fun preflightCalls(question: String): List<String> {
        val text = question.trim()
        for ((tool, pattern) in patterns) {
            val match = pattern.find(text) ?: continue
            var argument = match.groupValues[1].trim().trimEnd('.', '!')
            if (argument.length < 3) continue
            // Просьба записана словами собеседника («я пью кофе без сахара»): помечаем, чьи это слова,
            // иначе модель в другом диалоге не понимает, к кому относится «я».
            if (tool == "remember") argument = "Со слов собеседника: $argument"
            return listOf(JSONObject().put("tool", tool).put("text", argument).toString())
        }
        return emptyList()
    }
}
