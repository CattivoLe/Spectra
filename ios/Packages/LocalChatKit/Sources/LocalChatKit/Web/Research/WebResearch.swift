import Foundation

/// Этап поиска — для подписи на экране.
public enum ResearchStage: Sendable, Equatable {
    case searching
    case reading(pages: Int)
    case selecting

    public var title: String {
        switch self {
        case .searching: String(localized: "Searching the web…")
        case .reading(let pages): String(localized: "Reading pages: \(pages)…")
        case .selecting: String(localized: "Picking what matters…")
        }
    }
}

/// Поиск с чтением страниц: найти → скачать и прочитать первые страницы → нарезать → выбрать куски под вопрос.
/// Кроме кусков страниц в отбор идут и короткие описания из выдачи: в них часто уже есть ответ.
public struct WebResearch: Sendable {
    public var provider: any SearchProvider
    public var fetcher: any PageFetching
    /// Сколько страниц читать целиком. 0 — только описания из выдачи.
    public var pagesToRead: Int
    public var resultCount: Int
    /// Сколько символов найденного отдать модели. 3600 символов русского текста — примерно 1300–1500 токенов.
    public var budget: Int
    /// Модель эмбеддингов для отбора по смыслу. Без неё — только по словам.
    public var embedder: (any PassageEmbedder)?

    public init(provider: any SearchProvider, fetcher: any PageFetching, pagesToRead: Int = 3, resultCount: Int = 5, budget: Int = 3600,
                embedder: (any PassageEmbedder)? = nil) {
        self.provider = provider
        self.fetcher = fetcher
        self.pagesToRead = pagesToRead
        self.resultCount = resultCount
        self.budget = budget
        self.embedder = embedder
    }

    /// Все куски-кандидаты до отбора: выдача и прочитанные страницы. Нужен для сравнения способов отбора.
    public struct Gathered: Sendable, Codable {
        public var results: [SearchResult]
        public var passages: [Passage]
        public var pagesRead: Int
    }

    /// How many pieces go through the embedding model: three read pages give 200+ of them, and embedding them all
    /// took minutes on a phone; the best 40 by words are re-ranked by meaning, only a few reach the prompt anyway.
    public static let maxEmbedded = 40
    /// Shorter page text from a service is only a snippet: the page itself is read instead.
    public static let minGivenText = 400

    /// One query: search, read the pages, pick the pieces for the question. No second pass.
    /// - Parameters:
    ///   - sent: told about the query that went online, for the log.
    ///   - initial: the query's results from `SearchCache`: then it does not go online again.
    ///   - fetched: what the query brought back when it did go online, for `SearchCache`.
    public func run(
        query: String,
        question: String,
        initial: Gathered? = nil,
        sent: @escaping @Sendable (String) async -> Void = { _ in },
        fetched: @escaping @Sendable (String, Gathered) async -> Void = { _, _ in },
        progress: @escaping @Sendable (ResearchStage) async -> Void = { _ in }
    ) async throws -> SearchPayload {
        let gathered: Gathered
        if let initial {
            gathered = initial
        } else {
            gathered = try await gather(query: query, progress: progress)
            await sent(query)
            await fetched(query, gathered)
        }
        guard !gathered.results.isEmpty else {
            return SearchPayload(query: query, provider: provider.name, results: [], evidence: [], pagesRead: 0)
        }
        // Без чтения страниц описаний мало и они короткие: отдаём модели все, как есть, без отбора.
        guard pagesToRead > 0 else {
            return SearchPayload(query: query, provider: provider.name, results: gathered.results, evidence: nil, pagesRead: 0)
        }
        await progress(.selecting)
        let evidence = await select(from: gathered.passages, question: question, query: query)
        return SearchPayload(query: query, provider: provider.name, results: gathered.results, evidence: evidence, pagesRead: gathered.pagesRead)
    }

    /// Отбор: по словам, а если есть модель эмбеддингов — гибрид слов и смысла.
    /// Если эмбеддинги не удались, отбор всё равно состоится по словам.
    public func select(from passages: [Passage], question: String, query: String, method: RankingMethod? = nil) async -> [Passage] {
        let text = question + " " + query
        let method = method ?? (embedder == nil ? .words : .hybrid)
        guard method != .words, let embedder else {
            return PassageSelector.pack(passages, order: PassageSelector.order(passages, for: text, method: .words), budget: budget)
        }
        // Only the best pieces by words are re-ranked by meaning (see `maxEmbedded`).
        let pool = PassageSelector.shortlist(passages, for: text, limit: Self.maxEmbedded).map { passages[$0] }
        let queryVector = try? await embedder.embed(queries: [question]).first
        let passageVectors = try? await embedder.embed(passages: pool.map { (($0.heading.map { $0 + ". " }) ?? "") + $0.text })
        let order = PassageSelector.order(pool, for: text, method: method, queryVector: queryVector, passageVectors: passageVectors)
        return PassageSelector.pack(pool, order: order, budget: budget)
    }

    /// Поиск и чтение страниц, без отбора.
    public func gather(query: String, progress: @escaping @Sendable (ResearchStage) async -> Void = { _ in }) async throws -> Gathered {
        await progress(.searching)
        let results = try await provider.search(query, limit: max(resultCount, pagesToRead))
        try Task.checkCancellation()
        guard !results.isEmpty else { return Gathered(results: [], passages: [], pagesRead: 0) }

        let snippets = results.enumerated().compactMap { index, result in
            result.snippet.isEmpty ? nil : Passage(sourceIndex: index, heading: result.title, text: result.snippet, position: -1)
        }
        var passages: [Passage] = []
        let toRead = Array(results.prefix(pagesToRead).enumerated())
        var pagesRead = 0
        if !toRead.isEmpty {
            await progress(.reading(pages: toRead.count))
            let fetcher = fetcher
            let pages = await withTaskGroup(of: [Passage]?.self) { group in
                for (index, result) in toRead {
                    group.addTask {
                        // The service read the page already: its text is used as it is, the site is not asked again.
                        if let given = result.content, given.count >= Self.minGivenText {
                            return Chunker.chunk(ContentExtractor.fromText(title: result.title, text: given), sourceIndex: index)
                        }
                        guard let page = try? await fetcher.fetch(result.url) else { return nil }
                        let extracted = ContentExtractor.extract(html: page.html, url: page.url)
                        return Chunker.chunk(extracted, sourceIndex: index)
                    }
                }
                var collected: [[Passage]] = []
                for await page in group { if let page { collected.append(page) } }
                return collected
            }
            try Task.checkCancellation()
            pagesRead = pages.filter { !$0.isEmpty }.count
            passages += pages.flatMap { $0 }
        }
        // Описание из выдачи нужно только там, где страницу прочитать не удалось: иначе короткое описание
        // занимает место куска страницы, в котором и есть ответ.
        let read = Set(passages.map(\.sourceIndex))
        passages += snippets.filter { !read.contains($0.sourceIndex) }
        return Gathered(results: results, passages: passages, pagesRead: pagesRead)
    }
}
