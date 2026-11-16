import Foundation

/// Блок разметки в ответе модели. Внутристрочное оформление (жирный, курсив, `код`, ссылки)
/// в тексте блоков остаётся как есть: его разбирает `AttributedString` при показе.
public enum MarkdownBlock: Equatable, Sendable {
    case paragraph(String)
    case heading(level: Int, text: String)
    /// `marker` — «•» для маркированного списка или «1.» для нумерованного.
    case listItem(marker: String, text: String, indent: Int)
    case quote(String)
    /// `isClosed == false`, пока модель ещё не дописала закрывающие кавычки: блок всё равно показывается как код.
    case code(language: String?, text: String, isClosed: Bool)
}

public enum MarkdownParser {
    /// Разбирает текст на блоки. Работает с недописанным текстом: ответ приходит по кусочку,
    /// и в любой момент он может обрываться посреди блока кода или списка.
    public static func parse(_ source: String) -> [MarkdownBlock] {
        var blocks: [MarkdownBlock] = []
        var paragraph: [String] = []
        var quote: [String] = []
        var code: (language: String?, lines: [String])?

        func flushParagraph() {
            if !paragraph.isEmpty { blocks.append(.paragraph(paragraph.joined(separator: "\n"))) }
            paragraph = []
        }
        func flushQuote() {
            if !quote.isEmpty { blocks.append(.quote(quote.joined(separator: "\n"))) }
            quote = []
        }

        for line in source.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)

            if var open = code {
                if trimmed.hasPrefix("```") {
                    blocks.append(.code(language: open.language, text: open.lines.joined(separator: "\n"), isClosed: true))
                    code = nil
                } else {
                    open.lines.append(line)
                    code = open
                }
                continue
            }

            if trimmed.hasPrefix("```") {
                flushParagraph(); flushQuote()
                let language = String(trimmed.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                code = (language.isEmpty ? nil : language, [])
            } else if trimmed.isEmpty {
                flushParagraph(); flushQuote()
            } else if let heading = headingMatch(trimmed) {
                flushParagraph(); flushQuote()
                blocks.append(.heading(level: heading.level, text: heading.text))
            } else if let item = listMatch(line) {
                flushParagraph(); flushQuote()
                blocks.append(.listItem(marker: item.marker, text: item.text, indent: item.indent))
            } else if trimmed.hasPrefix(">") {
                flushParagraph()
                quote.append(String(trimmed.dropFirst()).trimmingCharacters(in: .whitespaces))
            } else {
                flushQuote()
                paragraph.append(trimmed)
            }
        }

        if let open = code {
            blocks.append(.code(language: open.language, text: open.lines.joined(separator: "\n"), isClosed: false))
        }
        flushParagraph(); flushQuote()
        return blocks
    }

    private static func headingMatch(_ line: String) -> (level: Int, text: String)? {
        let hashes = line.prefix { $0 == "#" }.count
        guard (1...6).contains(hashes), line.dropFirst(hashes).first == " " else { return nil }
        return (hashes, line.dropFirst(hashes).trimmingCharacters(in: .whitespaces))
    }

    private static func listMatch(_ line: String) -> (marker: String, text: String, indent: Int)? {
        let indent = line.prefix { $0 == " " || $0 == "\t" }.count
        let body = line.dropFirst(indent)

        if let first = body.first, "-*+".contains(first), body.dropFirst().first == " " {
            return ("•", body.dropFirst(2).trimmingCharacters(in: .whitespaces), indent / 2)
        }
        let digits = body.prefix { $0.isNumber }
        if !digits.isEmpty, digits.count <= 3 {
            let rest = body.dropFirst(digits.count)
            if let mark = rest.first, ".)".contains(mark), rest.dropFirst().first == " " {
                return ("\(digits).", rest.dropFirst(2).trimmingCharacters(in: .whitespaces), indent / 2)
            }
        }
        return nil
    }
}
