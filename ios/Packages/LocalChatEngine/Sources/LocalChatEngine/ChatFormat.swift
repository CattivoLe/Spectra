import Foundation

/// Запасные шаблоны чата.
///
/// В llama.cpp встроенный `llama_chat_apply_template` не исполняет Jinja, а узнаёт шаблон
/// по списку известных. Для свежих моделей он может вернуть ошибку — тогда формат
/// определяется здесь по характерным маркерам в шаблоне модели.
/// На этапе 1 это место займёт полноценный Jinja-рендер.
enum ChatFormat: String {
    case chatML = "ChatML"
    case gemma = "Gemma"
    case gemma4 = "Gemma 4"
    case llama3 = "Llama 3"
    case plain = "no template"

    static func detect(template: String?) -> ChatFormat {
        guard let template else { return .plain }
        if template.contains("<|im_start|>") { return .chatML }
        if template.contains("<|turn>") { return .gemma4 }
        if template.contains("<start_of_turn>") { return .gemma }
        if template.contains("<|start_header_id|>") { return .llama3 }
        return .plain
    }

    func render(_ messages: [ChatMessage]) -> String {
        switch self {
        case .chatML:
            return messages.map { "<|im_start|>\($0.role.rawValue)\n\($0.content)<|im_end|>\n" }.joined()
                + "<|im_start|>assistant\n"

        case .gemma:
            // У Gemma нет роли system: системный текст приклеивается к первой реплике пользователя.
            var out = ""
            var pendingSystem = ""
            for message in messages {
                switch message.role {
                case .system:
                    pendingSystem += message.content + "\n\n"
                case .user:
                    out += "<start_of_turn>user\n\(pendingSystem)\(message.content)<end_of_turn>\n"
                    pendingSystem = ""
                case .assistant:
                    out += "<start_of_turn>model\n\(message.content)<end_of_turn>\n"
                }
            }
            return out + "<start_of_turn>model\n"

        case .gemma4:
            // В отличие от прежних Gemma, здесь есть отдельная реплика system, а ответ модели зовётся model.
            return messages.map { message in
                let role = message.role == .assistant ? "model" : message.role.rawValue
                let content = message.content.trimmingCharacters(in: .whitespacesAndNewlines)
                return "<|turn>\(role)\n\(content)<turn|>\n"
            }.joined() + "<|turn>model\n"

        case .llama3:
            return messages.map {
                "<|start_header_id|>\($0.role.rawValue)<|end_header_id|>\n\n\($0.content)<|eot_id|>"
            }.joined() + "<|start_header_id|>assistant<|end_header_id|>\n\n"

        case .plain:
            return messages.map { "\($0.role.rawValue): \($0.content)\n" }.joined() + "assistant: "
        }
    }
}
