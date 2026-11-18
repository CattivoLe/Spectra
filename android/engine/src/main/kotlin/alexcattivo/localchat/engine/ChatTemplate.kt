package alexcattivo.localchat.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Шаблон чата, лежащий внутри файла модели (Jinja). Исполняет его движок Jinja из llama.cpp с теми же переменными,
 * что iOS-версия передаёт swift-jinja, поэтому промпт получается тем же.
 */
internal class ChatTemplate private constructor(private val handle: Long) : AutoCloseable {
    fun render(messages: List<ChatMessage>, enableThinking: Boolean): String {
        val variables = JSONObject()
            .put("messages", JSONArray(messages.map { JSONObject().put("role", it.role.raw).put("content", it.content) }))
            .put("add_generation_prompt", true)
            .put("enable_thinking", enableThinking)
        return LlamaNative.renderTemplate(handle, variables.toString().toByteArray()).decodeToString()
    }

    override fun close() = LlamaNative.freeTemplate(handle)

    companion object {
        fun create(source: String, bos: String, eos: String): ChatTemplate? {
            val handle = LlamaNative.newTemplate(source.toByteArray(), bos.toByteArray(), eos.toByteArray())
            return if (handle == 0L) null else ChatTemplate(handle)
        }
    }
}
