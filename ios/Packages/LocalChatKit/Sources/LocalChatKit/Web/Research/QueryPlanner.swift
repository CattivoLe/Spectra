import Foundation
import LocalChatEngine

/// Что делать с вопросом: искать (и что именно) или обойтись без интернета.
public enum QueryPlan: Equatable, Sendable {
    case search(String)
    case notNeeded
}

/// Составляет поисковый запрос моделью, с учётом разговора: «а сколько она весит?» после вопроса про Эйфелеву башню
/// превращается в «вес Эйфелевой башни». Работает во втором контексте движка, поэтому кэш диалога не сбивается.
@MainActor
public enum QueryPlanner {
    static let instruction = """
        You write a web search query. From the user's last question, taking the earlier conversation into account, \
        write a search query of 2–8 words in the language of the question. Replace pronouns ("it", "she", "there", «он», «это») \
        with what they refer to, and descriptions with exact names when it is clear what is meant. \
        If the answer needs no internet (a greeting, thanks, a request for a joke, a question about the assistant itself), \
        answer with one word: NO. Answer with the query only, without quotes or explanations.

        Examples:
        «Привет! Как дела?» → NO
        «Спасибо, ты очень помог!» → NO
        «Расскажи анекдот» → NO
        «Сколько весит синий кит?» → вес синего кита
        «Какая погода в Казани завтра?» → погода Казань завтра
        «How tall is the highest mountain on Earth?» → Mount Everest height
        """

    /// Сколько последних реплик разговора показывать и до какой длины их обрезать: служебный контекст маленький.
    static let historyLimit = 4
    static let messageLimit = 300

    /// `note` adds the host app's own rules (what it answers from its own data, so needs no search).
    public static func messages(history: [ChatMessage], question: String, note: String? = nil) -> [ChatMessage] {
        let recent = history.filter { $0.role != .system }.suffix(historyLimit).map { message in
            let who = message.role == .user ? "User" : "Assistant"
            let text = message.content.split(whereSeparator: \.isWhitespace).joined(separator: " ")
            return "\(who): \(text.count > messageLimit ? String(text.prefix(messageLimit)) + "…" : text)"
        }
        var body = ""
        if !recent.isEmpty { body += "Conversation:\n" + recent.joined(separator: "\n") + "\n\n" }
        body += "Last question: \(question)\n\nAnswer (a query or NO):"
        let system = [instruction, note].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "\n\n")
        return [ChatMessage(.system, system), ChatMessage(.user, body)]
    }

    /// Разбирает ответ модели. `nil` — ответ негодный, нужен запасной способ.
    /// «NO (scan_lan has this)», «НЕТ — это вопрос о сети»: a refusal with a reason, the way the host app's note shows
    /// it. A query that starts with the word («нет интернета на телефоне») is not one.
    nonisolated(unsafe) private static let declined = /(?i)(?:нет|no|none)\s*[(\[—–:,.;].*/

    public static func parse(_ raw: String) -> QueryPlan? {
        guard var line = raw.split(whereSeparator: \.isNewline).map({ $0.trimmingCharacters(in: .whitespaces) }).first(where: { !$0.isEmpty }) else {
            return nil
        }
        for prefix in ["поисковый запрос:", "запрос:", "search query:", "query:", "search:"] where line.lowercased().hasPrefix(prefix) {
            line = String(line.dropFirst(prefix.count))
        }
        line = line.trimmingCharacters(in: CharacterSet(charactersIn: " \t«»\"'`*.,:;!?"))
        line = line.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        if ["нет", "no", "none"].contains(line.lowercased()) || line.wholeMatch(of: declined) != nil { return .notNeeded }
        guard !line.isEmpty, line.count <= 150 else { return nil }
        return .search(line)
    }

    /// `nil`, если модель недоступна или ответила что-то непонятное.
    public static func plan(history: [ChatMessage], question: String, note: String? = nil, controller: EngineController) async -> QueryPlan? {
        guard let raw = try? await controller.complete(messages: messages(history: history, question: question, note: note), maxTokens: 24) else {
            return nil
        }
        return parse(raw)
    }
}
