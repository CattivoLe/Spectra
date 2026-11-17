import Foundation
import LocalChatEngine

/// Есть ли в найденном ответ. Если нет — какой запрос попробовать вместо прежнего.
public enum AnswerVerdict: Equatable, Sendable {
    case enough
    case missing(nextQuery: String?)
}

/// Проверка найденного перед ответом: модель смотрит на отобранные куски и решает, хватает ли их.
/// Работает во втором контексте, как и составление запроса, поэтому кэш диалога не сбивается.
@MainActor
public enum AnswerCheck {
    static let instruction = """
        You check whether the found excerpts answer the user's question. \
        If they do, write YES, a colon and the answer itself in a few words, taken from the excerpts. \
        If they do not (they are about something else or the needed fact is missing), write NO, a colon and a different search query \
        of 2–8 words, in the language of the question, that is more likely to find the answer and does not repeat the ones already tried. \
        Answer in one line.

        Examples:
        YES: 8849 metres
        NO: высота Эвереста в метрах
        """

    /// Сколько кусков и какой длины показывать: служебный контекст небольшой, а для решения хватает начала кусков.
    static let passageLimit = 4
    static let charactersPerPassage = 1000

    public static func messages(question: String, passages: [Passage], triedQueries: [String], history: [ChatMessage] = []) -> [ChatMessage] {
        // Без разговора «А сколько она весит?» непонятно, о ком речь, и вес любого человека из выдачи сходит за ответ.
        let recent = history.filter { $0.role != .system }.suffix(QueryPlanner.historyLimit).map { message in
            let who = message.role == .user ? "User" : "Assistant"
            let text = message.content.split(whereSeparator: \.isWhitespace).joined(separator: " ")
            return "\(who): \(text.count > QueryPlanner.messageLimit ? String(text.prefix(QueryPlanner.messageLimit)) + "…" : text)"
        }
        let shown = passages.prefix(passageLimit).enumerated().map { index, passage in
            let text = passage.text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
            return "[\(index + 1)] " + (text.count > charactersPerPassage ? String(text.prefix(charactersPerPassage)) + "…" : text)
        }
        let context = recent.isEmpty ? "" : "Conversation before the question:\n" + recent.joined(separator: "\n") + "\n\n"
        let body = context + """
            Question: \(question)
            Already searched: \(triedQueries.map { "«\($0)»" }.joined(separator: ", "))

            Found excerpts:
            \(shown.isEmpty ? "(nothing was found)" : shown.joined(separator: "\n"))

            Answer (YES: answer or NO: query):
            """
        return [ChatMessage(.system, instruction), ChatMessage(.user, body)]
    }

    /// Что модель написала: «ДА: ответ» или «НЕТ: запрос».
    public enum Reply: Equatable, Sendable {
        case yes(answer: String?)
        case no(query: String?)
    }

    public static func parse(_ raw: String) -> Reply? {
        guard let line = raw.split(whereSeparator: \.isNewline).map({ $0.trimmingCharacters(in: .whitespaces) }).first(where: { !$0.isEmpty }) else {
            return nil
        }
        let cleaned = line.trimmingCharacters(in: CharacterSet(charactersIn: "*«»\"' "))
        let upper = cleaned.uppercased()
        let rest = String(cleaned.drop { $0 != ":" && $0 != "—" && $0 != "-" }.dropFirst())
            .trimmingCharacters(in: CharacterSet(charactersIn: " *«»\"'."))
        if upper.hasPrefix("ДА") || upper.hasPrefix("YES") { return .yes(answer: rest.isEmpty ? nil : rest) }
        guard upper.hasPrefix("НЕТ") || upper.hasPrefix("NO") else { return nil }
        if case .search(let query)? = QueryPlanner.parse(rest) { return .no(query: query) }
        return .no(query: nil)
    }

    /// Опирается ли «ответ» на найденное. Маленькая модель охотно пишет «ДА: 330 м» по памяти, даже когда в кусках
    /// про это ничего нет. Числа из ответа обязаны встречаться в кусках; ответ без чисел — хотя бы половиной значимых слов.
    public static func isGrounded(_ answer: String, in passages: [Passage]) -> Bool {
        let evidence = passages.map(\.text).joined(separator: " ")
        let compactEvidence = evidence.filter { !$0.isWhitespace && $0 != "\u{00A0}" }
        let numbers = answer.split { !$0.isNumber && $0 != "," && $0 != "." && $0 != " " }
            .map { $0.filter { $0.isNumber || $0 == "," || $0 == "." } }
            .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: ",.")) }
            .filter { $0.contains(where: \.isNumber) }
        if !numbers.isEmpty {
            return numbers.allSatisfy { compactEvidence.contains($0) || compactEvidence.contains($0.replacingOccurrences(of: ".", with: ",")) }
        }
        let words = Set(Terms.of(answer))
        guard !words.isEmpty else { return true }
        let found = Set(Terms.of(evidence))
        return Double(words.intersection(found).count) >= Double(words.count) / 2
    }

    /// Решение: хватает найденного или нужен другой запрос.
    /// `nil` — модель недоступна или ответила непонятно: тогда считаем, что хватает, и повторно не ищем.
    public static func verdict(question: String, passages: [Passage], triedQueries: [String], history: [ChatMessage] = [],
                               note: String? = nil, controller: EngineController) async -> AnswerVerdict? {
        let prompt = messages(question: question, passages: passages, triedQueries: triedQueries, history: history)
        guard let raw = try? await controller.complete(messages: prompt, maxTokens: 40), let reply = parse(raw) else { return nil }
        switch reply {
        case .yes(let answer):
            guard let answer, !isGrounded(answer, in: passages) else { return .enough }
            // Ответ взят не из найденного: ищем снова, запрос составляет планировщик по самому вопросу.
            if case .search(let query)? = await QueryPlanner.plan(history: history, question: question, note: note, controller: controller) {
                return .missing(nextQuery: query)
            }
            return .missing(nextQuery: nil)
        case .no(let query):
            return .missing(nextQuery: query)
        }
    }
}
