import Foundation
import SwiftSoup

/// Основной текст страницы: заголовок и абзацы по порядку, без меню, рекламы и подвала.
public struct ExtractedPage: Sendable, Equatable {
    public struct Block: Sendable, Equatable {
        public var text: String
        public var isHeading: Bool
        /// Строка таблицы: такие режутся мельче и с повтором заголовка таблицы.
        public var isTableRow: Bool = false

        public init(text: String, isHeading: Bool, isTableRow: Bool = false) {
            self.text = text
            self.isHeading = isHeading
            self.isTableRow = isTableRow
        }
    }
    public var title: String
    public var blocks: [Block]

    public var text: String { blocks.map(\.text).joined(separator: "\n") }
}

/// Упрощённый «режим чтения»: убрать заведомый мусор, найти элемент, где больше всего связного текста, и взять из него абзацы.
public enum ContentExtractor {
    private static let junkTags = "script, style, noscript, svg, iframe, form, nav, header, footer, aside, button, select, template, sup, figure > figcaption + *, [role=navigation], [role=banner], [role=contentinfo], [aria-hidden=true]"
    /// Классы и id, по которым видно служебный блок. Регулярное выражение собирается на месте: Regex не Sendable.
    private static let junkNamePattern = #"(^|[\s_-])(nav|navbar|menu|footer|sidebar|comments?|share|social|advert|ads?|banner|cookie|subscribe|related|breadcrumbs?|promo|popup|modal|newsletter|navbox|mw-editsection|reference|toc)([\s_-]|$)"#
    private static let blockTags: Set<String> = ["h1", "h2", "h3", "h4", "p", "li", "pre", "blockquote", "dd", "tr"]

    /// Text a search service already took out of the page (markdown or plain): «#» lines become headings, the rest
    /// paragraphs, so `Chunker` cuts it like a fetched page.
    public static func fromText(title: String, text: String) -> ExtractedPage {
        var blocks: [ExtractedPage.Block] = []
        var paragraph: [String] = []
        func flush() {
            let text = plainMarkdown(paragraph.joined(separator: " "))
            if !text.isEmpty { blocks.append(.init(text: text, isHeading: false)) }
            paragraph = []
        }
        for raw in text.split(omittingEmptySubsequences: false, whereSeparator: \.isNewline) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty {
                flush()
            } else if line.hasPrefix("#") {
                flush()
                let heading = plainMarkdown(String(line.drop { $0 == "#" || $0 == " " }))
                if !heading.isEmpty { blocks.append(.init(text: heading, isHeading: true)) }
            } else {
                paragraph.append(line)
            }
        }
        flush()
        return ExtractedPage(title: title, blocks: blocks)
    }

    /// Markdown links and images carry addresses the model does not need: only their words stay.
    private static func plainMarkdown(_ text: String) -> String {
        clean(text.replacing(/!\[[^\]]*\]\([^)]*\)/, with: "").replacing(/\[([^\]]*)\]\([^)]*\)/) { String($0.output.1) })
    }

    public static func extract(html: String, url: URL) -> ExtractedPage {
        guard let document = try? SwiftSoup.parse(html, url.absoluteString) else { return ExtractedPage(title: "", blocks: []) }

        let title = (try? document.select("meta[property=og:title]").first()?.attr("content")).flatMap { $0 }
            ?? (try? document.title()) ?? ""

        try? document.select(junkTags).remove()
        let junkNames = try? Regex(junkNamePattern)
        if let junkNames, let all = try? document.body()?.getAllElements() {
            for element in all.array() where element.tagName() != "body" && element.tagName() != "main" && element.tagName() != "article" {
                let names = ((try? element.className()) ?? "") + " " + element.id()
                if names.lowercased().contains(junkNames) { try? element.remove() }
            }
        }

        guard let body = document.body() else { return ExtractedPage(title: clean(title), blocks: []) }
        var root = bestRoot(in: body)
        if textLength(root) < 300 { root = body }

        var blocks: [ExtractedPage.Block] = []
        for element in (try? root.getAllElements().array()) ?? [] {
            let tag = element.tagName()
            guard blockTags.contains(tag) else { continue }
            // Элемент, внутри которого есть другие блоки (пункт списка с абзацем), пропускаем: возьмём вложенные.
            if tag != "tr", element.children().array().contains(where: { blockTags.contains($0.tagName()) }) { continue }
            if tag != "tr", element.parents().array().contains(where: { $0.tagName() == "tr" }) { continue }

            let text: String
            if tag == "tr" {
                let cells = element.children().array().map { clean((try? $0.text()) ?? "") }.filter { !$0.isEmpty }
                text = cells.joined(separator: " | ")
            } else {
                text = clean((try? element.text()) ?? "")
            }
            let isHeading = tag.hasPrefix("h")
            guard !text.isEmpty else { continue }
            if !isHeading && text.count < 25 && tag != "tr" { continue }
            if linkDensity(element, textLength: text.count) > 0.6 { continue }
            if blocks.last?.text == text { continue }
            blocks.append(.init(text: text, isHeading: isHeading, isTableRow: tag == "tr"))
        }
        return ExtractedPage(title: clean(title), blocks: blocks)
    }

    /// Элемент с наибольшим «весом» абзацев: каждый абзац добавляет свою длину родителю и половину — деду.
    private static func bestRoot(in body: Element) -> Element {
        if let article = (try? body.select("article, main, [role=main]").array())?.max(by: { textLength($0) < textLength($1) }),
           textLength(article) >= 300 {
            return article
        }
        var scores: [ObjectIdentifier: (Element, Int)] = [:]
        for paragraph in (try? body.select("p, pre, li").array()) ?? [] {
            let length = ((try? paragraph.text()) ?? "").count
            guard length >= 25 else { continue }
            if let parent = paragraph.parent() {
                scores[ObjectIdentifier(parent), default: (parent, 0)].1 += length
                if let grand = parent.parent() { scores[ObjectIdentifier(grand), default: (grand, 0)].1 += length / 2 }
            }
        }
        return scores.values.max { $0.1 < $1.1 }?.0 ?? body
    }

    private static func textLength(_ element: Element) -> Int {
        ((try? element.text()) ?? "").count
    }

    private static func linkDensity(_ element: Element, textLength: Int) -> Double {
        guard textLength > 0 else { return 0 }
        let linked = ((try? element.select("a").array()) ?? []).reduce(0) { $0 + ((try? $1.text()) ?? "").count }
        return Double(linked) / Double(textLength)
    }

    static func clean(_ text: String) -> String {
        text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }
}
