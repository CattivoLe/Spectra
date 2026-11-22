package cattivole.spectra.assistant

/** Service words a small model lets slip into a card's text. */
object AssistantText {
    // MARK: - Service words

    // Word edges spelled out: Android's regex engine (ICU) rejects the (?U) flag, and plain \b is ASCII-only on the JVM.
    private const val START = """(?<![\p{L}\p{N}_])"""

    /** The blocks a card's prompt hands the data in: see CardPrompts. */
    private const val TAG = """`?</?(?:data|check|web)(?:\s[^>]*)?>`?"""

    /** Phrases a small model builds around the tag it was given the data in, and what a person should read instead. */
    private val phrases: List<Pair<Regex, (Boolean) -> String>> = listOf(
        Regex(START + """(?:по состоянию на|согласно|судя по|по данным|исходя из)\s+$TAG""", RegexOption.IGNORE_CASE) to { _ -> "по данным Spectra" },
        Regex(START + """(?:во|в)\s+$TAG""", RegexOption.IGNORE_CASE) to { _ -> "в данных Spectra" },
        Regex(START + """из\s+$TAG""", RegexOption.IGNORE_CASE) to { _ -> "из данных Spectra" },
        Regex(START + """(?:according to|based on|as of|per)\s+(?:the\s+)?$TAG""", RegexOption.IGNORE_CASE) to { _ -> "according to Spectra's data" },
        Regex(START + """(?:in|from)\s+(?:the\s+)?$TAG""", RegexOption.IGNORE_CASE) to { _ -> "in Spectra's data" },
    )
    private val bareTag = Regex(TAG)

    /**
     * Takes the service tags out of a text: «По состоянию на <data>:» reads «По данным Spectra:». The model
     * sees its data inside such tags and now and then names them; the person never saw them.
     */
    fun polish(text: String): String {
        if (!text.contains('<')) return text
        var result = text
        for ((pattern, replacement) in phrases) {
            result = pattern.replace(result) { match ->
                val words = replacement(true)
                if (match.value.first().isUpperCase()) words.replaceFirstChar { it.uppercase() } else words
            }
        }
        val russian = result.any { it in 'а'..'я' || it in 'А'..'Я' }
        result = bareTag.replace(result) { if (russian) "данные Spectra" else "Spectra's data" }
        return result
    }
}
