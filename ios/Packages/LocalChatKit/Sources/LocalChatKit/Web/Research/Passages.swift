import Foundation

/// Кусок текста с найденной страницы, который может попасть к модели.
public struct Passage: Codable, Hashable, Sendable {
    /// Номер источника в выдаче (с нуля); в ответе он виден как [номер + 1].
    public var sourceIndex: Int
    /// Ближайший заголовок раздела: помогает модели понять, о чём кусок.
    public var heading: String?
    public var text: String
    /// Порядок внутри страницы: выбранные куски показываются в том порядке, в каком шли на странице.
    public var position: Int

    public init(sourceIndex: Int, heading: String? = nil, text: String, position: Int) {
        self.sourceIndex = sourceIndex
        self.heading = heading
        self.text = text
        self.position = position
    }
}

/// Нарезка страницы на куски примерно по 300 токенов: целые абзацы, длинные абзацы — по предложениям.
public enum Chunker {
    public static func chunk(_ page: ExtractedPage, sourceIndex: Int, target: Int = 800, limit: Int = 1200, tableChunk: Int = 300) -> [Passage] {
        var passages: [Passage] = []
        var heading: String?
        var buffer: [String] = []
        var size = 0

        func flush() {
            guard !buffer.isEmpty else { return }
            passages.append(Passage(sourceIndex: sourceIndex, heading: heading, text: buffer.joined(separator: "\n"), position: passages.count))
            buffer = []
            size = 0
        }

        var tableHeader: String?
        var tableRows: [String] = []

        // Таблица режется на куски по несколько строк, и в каждом повторяется первая строка (названия столбцов):
        // иначе нужная строка («Доллар США | 79,5») тонет в большом куске среди десятков других.
        func flushTable() {
            guard !tableRows.isEmpty else { return }
            var rows: [String] = []
            var length = 0
            func emit() {
                guard !rows.isEmpty else { return }
                let text = ([tableHeader].compactMap { $0 } + rows).joined(separator: "\n")
                passages.append(Passage(sourceIndex: sourceIndex, heading: heading, text: text, position: passages.count))
                rows = []
                length = 0
            }
            for row in tableRows {
                if length + row.count > tableChunk { emit() }
                rows.append(row)
                length += row.count + 1
            }
            emit()
            tableHeader = nil
            tableRows = []
        }

        for block in page.blocks {
            if block.isTableRow {
                flush()
                if tableHeader == nil { tableHeader = block.text } else { tableRows.append(block.text) }
                continue
            }
            if tableHeader != nil {
                if tableRows.isEmpty { buffer.append(tableHeader!); size += tableHeader!.count }
                tableHeader = tableRows.isEmpty ? nil : tableHeader
                flushTable()
            }
            if block.isHeading {
                flush()
                heading = block.text
                continue
            }
            for piece in split(block.text, limit: limit) {
                if size + piece.count > limit { flush() }
                buffer.append(piece)
                size += piece.count + 1
                if size >= target { flush() }
            }
        }
        if tableHeader != nil, tableRows.isEmpty { buffer.append(tableHeader!) }
        flushTable()
        flush()
        return passages
    }

    /// Абзац длиннее предела режется по концам предложений.
    static func split(_ text: String, limit: Int) -> [String] {
        guard text.count > limit else { return [text] }
        var parts: [String] = []
        var current = ""
        for sentence in sentences(of: text) {
            if current.count + sentence.count + 1 > limit, !current.isEmpty {
                parts.append(current)
                current = ""
            }
            current += (current.isEmpty ? "" : " ") + sentence
            while current.count > limit {
                parts.append(String(current.prefix(limit)))
                current = String(current.dropFirst(limit))
            }
        }
        if !current.isEmpty { parts.append(current) }
        return parts
    }

    /// Предложения со знаком конца: делим после «.», «!», «?», «…», за которыми идёт пробел.
    static func sentences(of text: String) -> [String] {
        var result: [String] = []
        var current = ""
        var previous: Character?
        for character in text {
            if character.isWhitespace, let previous, ".!?…".contains(previous), !current.isEmpty {
                result.append(current)
                current = ""
            } else if !(character.isWhitespace && current.isEmpty) {
                current.append(character)
            }
            previous = character
        }
        if !current.isEmpty { result.append(current) }
        return result
    }
}

/// Слова для поиска по тексту: нижний регистр, «ё» как «е», без служебных слов, с грубым отсечением окончаний,
/// чтобы «высота», «высоту» и «высоте» считались одним словом.
public enum Terms {
    private static let stopWords: Set<String> = [
        "и", "в", "во", "на", "с", "со", "к", "ко", "по", "о", "об", "от", "до", "за", "из", "у", "для", "про", "при", "без",
        "не", "ни", "но", "а", "или", "что", "как", "так", "это", "этот", "эта", "эти", "то", "же", "ли", "бы", "вот",
        "какой", "какая", "какое", "какие", "каков", "почему", "зачем", "где", "когда", "куда", "кто", "сколько", "ли",
        "расскажи", "скажи", "подскажи", "пожалуйста", "привет", "мне", "ты", "вы", "я", "мы", "он", "она", "они", "его", "ее", "их",
        "есть", "был", "была", "было", "были", "быть", "будет", "the", "a", "an", "of", "to", "in", "is", "are", "and", "or", "what", "how", "why",
    ]
    private static let endings = [
        "иями", "ями", "ами", "ией", "ием", "ого", "его", "ому", "ему", "ыми", "ими", "иях", "ость", "ости",
        "ой", "ей", "ий", "ый", "ая", "яя", "ое", "ее", "ые", "ие", "ую", "юю", "ах", "ях", "ов", "ев", "ам", "ям",
        "ом", "ем", "ию", "ия", "ью", "ть", "ет", "ют", "ут", "ит", "ат", "ят",
        "а", "я", "о", "е", "ы", "и", "у", "ю", "ь", "й",
    ]

    public static func of(_ text: String) -> [String] {
        text.lowercased().replacingOccurrences(of: "ё", with: "е")
            .split { !$0.isLetter && !$0.isNumber }
            .map(String.init)
            .filter { !stopWords.contains($0) }
            .map(stem)
    }

    static func stem(_ word: String) -> String {
        guard word.count > 3, word.first?.isLetter == true else { return word }
        if word.unicodeScalars.allSatisfy({ $0.isASCII }) {
            for suffix in ["ing", "ed", "es", "s"] where word.hasSuffix(suffix) && word.count - suffix.count >= 3 {
                return String(word.dropLast(suffix.count))
            }
            return word
        }
        for ending in endings where word.hasSuffix(ending) && word.count - ending.count >= 3 {
            return String(word.dropLast(ending.count))
        }
        return word
    }
}

/// Отбор кусков под вопрос: BM25 — классическое ранжирование по совпадению слов с поправкой на их редкость и длину текста.
public enum PassageSelector {
    public static func select(_ passages: [Passage], for query: String, budget: Int, perSource: Int = 2) -> [Passage] {
        guard !passages.isEmpty, !Terms.of(query).isEmpty else { return [] }
        return pack(passages, order: order(passages, for: query, method: .words), budget: budget, perSource: perSource)
    }

    static func bm25(query: [String], documents: [[String]], k1: Double = 1.2, b: Double = 0.75) -> [Double] {
        let count = Double(documents.count)
        let averageLength = max(1, Double(documents.reduce(0) { $0 + $1.count }) / count)
        var documentFrequency: [String: Int] = [:]
        for document in documents {
            for term in Set(document) { documentFrequency[term, default: 0] += 1 }
        }
        return documents.map { document in
            var frequencies: [String: Int] = [:]
            for term in document { frequencies[term, default: 0] += 1 }
            let length = Double(document.count)
            return query.reduce(0.0) { total, term in
                guard let frequency = frequencies[term].map(Double.init) else { return total }
                let df = Double(documentFrequency[term] ?? 0)
                let idf = log(1 + (count - df + 0.5) / (df + 0.5))
                return total + idf * frequency * (k1 + 1) / (frequency + k1 * (1 - b + b * length / averageLength))
            }
        }
    }
}
