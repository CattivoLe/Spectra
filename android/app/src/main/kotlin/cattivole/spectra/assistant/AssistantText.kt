package cattivole.spectra.assistant

/** Small text jobs around the chat: what a chat is about, a question that wants pictures, service words in answers. */
object AssistantText {
    // MARK: - Service words

    // Word edges spelled out: Android's regex engine (ICU) rejects the (?U) flag, and plain \b is ASCII-only on the JVM.
    private const val START = """(?<![\p{L}\p{N}_])"""

    private const val TAG = """`?</?(?:app_data|tool_result|earlier_chats)(?:\s[^>]*)?>`?"""

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
     * Takes the service tags out of an answer: «По состоянию на <app_data>:» reads «По данным Spectra:». The model
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

    // MARK: - Pictures

    private val pictureStems = listOf(
        "как выгляд", "внешний вид", "внешне", "фото", "фотк", "картинк", "изображени", "покажи как",
        "look like", "looks like", "picture", "photo", "image of", "images of", "what it looks",
    )

    /** «Как выглядит эта метка?», «покажи фото роутера»: a question that wants to see the thing. */
    fun asksForPicture(question: String): Boolean {
        val text = question.lowercase()
        return pictureStems.any { text.contains(it) }
    }

    private val whatDeviceStems = listOf(
        "что за устройств", "что это за устройств", "что за bluetooth", "что за блютуз", "что за метк", "что за трекер",
        "что за наушник", "что за камер", "что за роутер", "что это за метк", "что это за трекер",
        "what is the device", "what is this device", "what kind of device", "what is the bluetooth device", "which device is",
    )

    /** «Что за устройство…» (the ✨ of a device screen): the answer gets pictures too when web search is on. */
    fun asksWhatDeviceIs(question: String): Boolean {
        val text = question.lowercase()
        return whatDeviceStems.any { text.contains(it) }
    }

    // MARK: - What a chat is about

    /**
     * The topics a chat's first question names, at most two, for the label in the chat list. «Локальная сеть» is LAN,
     * not Wi‑Fi, though it says «сеть».
     */
    fun topics(question: String): List<AssistantTopic> {
        val text = question.lowercase()
        val found = AssistantTopic.entries.filter { AssistantDigest.matches(it.stems, text) }.toMutableList()
        if (found.isEmpty()) return emptyList()
        if (AssistantTopic.LAN in found && AssistantTopic.WIFI in found &&
            listOf("локальн", "local network", "lan", "хост", "host").any { text.contains(it) } &&
            listOf("wi-fi", "wifi", "wi‑fi", "вай", "ssid", "точк").none { text.contains(it) }
        ) {
            found.remove(AssistantTopic.WIFI)
        }
        // A traffic capture is about a network, but the capture is the subject.
        if (AssistantTopic.CAPTURE in found) found.remove(AssistantTopic.WIFI)
        return found.take(2)
    }
}
