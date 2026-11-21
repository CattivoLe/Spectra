package alexcattivo.localchat.kit.web

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.EngineController

/**
 * The query for a picture search: «как выглядит эта метка» after a question about «Tile Mate» becomes «Tile Mate».
 * Written by the model in the engine's second context, like [QueryPlanner]; never answers "not needed".
 */
object ImageQuery {
    val instruction = """
        You write an image search query. From the user's last message and the conversation, name the thing the user wants to see: brand and model when they are known, otherwise the kind of device. 1–6 words, no quotes, no explanation, nothing else. Replace "this", "it", «эта», «это», «он» with what they refer to. If nothing names a product, a brand or a recognizable kind of device (only an address or a random code), reply NONE.
        Examples:
        «Как выглядит AirTag?» → Apple AirTag
        Conversation about the Bluetooth device «Tile Mate»; «как выглядит эта метка» → Tile Mate tracker
        Conversation about a host «Hikvision DS-2CD1043»; «покажи, как выглядит это устройство» → Hikvision DS-2CD1043 camera
        «What does a Flipper Zero look like?» → Flipper Zero
        «Что за устройство 192.168.1.23 (192.168.1.23) в моей сети?» → NONE
    """.trimIndent()

    fun messages(history: List<ChatMessage>, question: String): List<ChatMessage> {
        val lines = QueryPlanner.recent(history)
        var body = ""
        if (lines.isNotEmpty()) body += "Conversation:\n" + lines.joinToString("\n") + "\n\n"
        body += "Last message: $question\n\nQuery:"
        return listOf(ChatMessage(ChatMessage.Role.System, instruction), ChatMessage(ChatMessage.Role.User, body))
    }

    /** The cleaned first line of the model's reply; `null` when it gave nothing usable. */
    fun parse(raw: String): String? {
        var line = raw.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        line = line.trim('*', ' ')
        for (prefix in listOf("query:", "запрос:")) if (line.lowercase().startsWith(prefix)) line = line.drop(prefix.length)
        line = line.trim(' ', '\t', '«', '»', '"', '\'', '`', '*', '.', ',', ':', ';', '!', '?')
        line = line.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (line.uppercase() in setOf("NONE", "НЕТ", "NO")) return null
        return line.takeIf { it.isNotEmpty() && it.length <= 80 }
    }

    suspend fun write(history: List<ChatMessage>, question: String, controller: EngineController): String? {
        val raw = runCatching { controller.complete(messages(history, question), maxTokens = 16) }.getOrNull() ?: return null
        return parse(raw)
    }
}
