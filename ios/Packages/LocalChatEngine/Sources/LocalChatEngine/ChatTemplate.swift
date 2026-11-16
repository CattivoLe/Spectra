import Foundation
import Jinja

/// Шаблон чата, лежащий внутри файла модели (Jinja).
///
/// Именно по нему модель обучали, поэтому формат реплик берём оттуда, а не из собственного списка:
/// так новые модели работают без правок кода. Отключение «размышлений» тоже делает сам шаблон,
/// через переменную `enable_thinking`.
struct ChatTemplate: Sendable {
    private let template: Template
    let bosToken: String
    let eosToken: String

    init(source: String, bosToken: String, eosToken: String) throws {
        // Как в HuggingFace transformers и llama.cpp: перевод строки после блока и отступ перед ним в вывод не попадают.
        self.template = try Template(source, with: .init(lstripBlocks: true, trimBlocks: true))
        self.bosToken = bosToken
        self.eosToken = eosToken
    }

    func render(_ messages: [ChatMessage], enableThinking: Bool) throws -> String {
        let history = messages.map { ["role": $0.role.rawValue, "content": $0.content] }
        return try template.render([
            "messages": try Value(any: history),
            "add_generation_prompt": true,
            "bos_token": .string(bosToken),
            "eos_token": .string(eosToken),
            "enable_thinking": .boolean(enableThinking),
        ])
    }
}
