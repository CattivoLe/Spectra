package alexcattivo.localchat.engine

/**
 * Запасные шаблоны чата: когда шаблон из модели не разобрался или не отработал, формат узнаётся
 * по характерным маркерам. Совпадает с ChatFormat.swift в iOS-версии.
 */
enum class ChatFormat(val title: String) {
    ChatML("ChatML"), Gemma("Gemma"), Gemma4("Gemma 4"), Llama3("Llama 3"), Plain("без шаблона");

    fun render(messages: List<ChatMessage>): String = when (this) {
        ChatML -> messages.joinToString("") { "<|im_start|>${it.role.raw}\n${it.content}<|im_end|>\n" } + "<|im_start|>assistant\n"
        Gemma -> {
            // У Gemma нет роли system: системный текст приклеивается к первой реплике пользователя.
            val out = StringBuilder()
            var pendingSystem = ""
            for (message in messages) when (message.role) {
                ChatMessage.Role.System -> pendingSystem += message.content + "\n\n"
                ChatMessage.Role.User -> {
                    out.append("<start_of_turn>user\n$pendingSystem${message.content}<end_of_turn>\n")
                    pendingSystem = ""
                }
                ChatMessage.Role.Assistant -> out.append("<start_of_turn>model\n${message.content}<end_of_turn>\n")
            }
            out.append("<start_of_turn>model\n").toString()
        }
        Gemma4 -> messages.joinToString("") {
            val role = if (it.role == ChatMessage.Role.Assistant) "model" else it.role.raw
            "<|turn>$role\n${it.content.trim()}<turn|>\n"
        } + "<|turn>model\n"
        Llama3 -> messages.joinToString("") {
            "<|start_header_id|>${it.role.raw}<|end_header_id|>\n\n${it.content}<|eot_id|>"
        } + "<|start_header_id|>assistant<|end_header_id|>\n\n"
        Plain -> messages.joinToString("") { "${it.role.raw}: ${it.content}\n" } + "assistant: "
    }

    companion object {
        fun detect(template: String?): ChatFormat = when {
            template == null -> Plain
            template.contains("<|im_start|>") -> ChatML
            template.contains("<|turn>") -> Gemma4
            template.contains("<start_of_turn>") -> Gemma
            template.contains("<|start_header_id|>") -> Llama3
            else -> Plain
        }
    }
}
