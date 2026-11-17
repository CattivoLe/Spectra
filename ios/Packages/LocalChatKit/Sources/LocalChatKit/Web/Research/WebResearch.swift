import Foundation

/// Этап поиска — для подписи на экране.
public enum ResearchStage: Sendable, Equatable {
    case planning
    case searching
    case checking
    case retrying(query: String)
    case reading(pages: Int)
    case selecting

    public var title: String {
        switch self {
        case .planning: String(localized: "Writing the search query…")
        case .searching: String(localized: "Searching the web…")
        case .checking: String(localized: "Checking whether that is enough…")
        case .retrying(let query): String(localized: "Searching again: «\(query)»…")
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

    public func run(query: String, question: String, progress: @escaping @Sendable (ResearchStage) async -> Void = { _ in }) async throws -> SearchPayload {
        let gathered = try await gather(query: query, progress: progress)
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
        var queryVector: [Float]?
        var passageVectors: [[Float]]?
        if method != .words, let embedder {
            queryVector = try? await embedder.embed(queries: [question]).first
            passageVectors = try? await embedder.embed(passages: passages.map { (($0.heading.map { $0 + ". " }) ?? "") + $0.text })
        }
        let order = PassageSelector.order(passages, for: text, method: method, queryVector: queryVector, passageVectors: passageVectors)
        return PassageSelector.pack(passages, order: order, budget: budget)
    }

    /// Поиск с одним повторным заходом.
    /// `check` смотрит на отобранное и возвращает новый запрос, если ответа нет (`nil` — найденного хватает).
    /// `approve` спрашивает пользователя перед вторым запросом (как и перед первым); `nil` от него — второго захода не будет.
    /// `sent` сообщает о каждом запросе, ушедшем в сеть, — для журнала.
    public func runWithRetry(
        query: String,
        question: String,
        check: (@Sendable (_ question: String, _ evidence: [Passage], _ tried: [String]) async -> String?)?,
        approve: (@Sendable (String) async -> String?)?,
        sent: @escaping @Sendable (String) async -> Void = { _ in },
        progress: @escaping @Sendable (ResearchStage) async -> Void = { _ in }
    ) async throws -> SearchPayload {
        var gathered = try await gather(query: query, progress: progress)
        await sent(query)
        var queries = [query]
        // Без чтения страниц отбирать и проверять нечего: отдаём описания из выдачи как есть.
        guard pagesToRead > 0 else {
            return SearchPayload(query: query, provider: provider.name, results: gathered.results, evidence: nil, pagesRead: 0)
        }

        if !gathered.results.isEmpty { await progress(.selecting) }
        var evidence = gathered.results.isEmpty ? [] : await select(from: gathered.passages, question: question, query: query)

        if let check {
            await progress(.checking)
            var next: String?
            if let proposed = await check(question, evidence, queries), !queries.contains(where: { Self.sameQuery($0, proposed) }) {
                if let approve { next = await approve(proposed) } else { next = proposed }
            }
            if let next, !next.trimmingCharacters(in: .whitespaces).isEmpty {
                try Task.checkCancellation()
                await progress(.retrying(query: next))
                let more = try await gather(query: next, progress: { _ in })
                await sent(next)
                queries.append(next)
                gathered = Self.merge(gathered, more)
                await progress(.selecting)
                // Слова неудачного первого запроса больше не помогают: отбираем по вопросу и новому запросу.
                evidence = await select(from: gathered.passages, question: question, query: next)
            }
        }
        return SearchPayload(query: query, provider: provider.name, results: gathered.results, evidence: evidence,
                             pagesRead: gathered.pagesRead, extraQueries: Array(queries.dropFirst()))
    }

    static func sameQuery(_ a: String, _ b: String) -> Bool {
        Set(Terms.of(a)) == Set(Terms.of(b))
    }

    /// Сливает две выдачи: одинаковые страницы не повторяются, номера источников второй сдвигаются.
    public static func merge(_ first: Gathered, _ second: Gathered) -> Gathered {
        var results = first.results
        var mapping: [Int: Int] = [:]
        var duplicates: Set<Int> = []
        for (index, result) in second.results.enumerated() {
            if let existing = results.firstIndex(where: { $0.url == result.url }) {
                mapping[index] = existing
                duplicates.insert(index)
            } else {
                mapping[index] = results.count
                results.append(result)
            }
        }
        let added = second.passages.compactMap { passage -> Passage? in
            guard !duplicates.contains(passage.sourceIndex), let target = mapping[passage.sourceIndex] else { return nil }
            var moved = passage
            moved.sourceIndex = target
            return moved
        }
        let readSecond = Set(second.passages.filter { $0.position >= 0 }.map(\.sourceIndex)).subtracting(duplicates).count
        return Gathered(results: results, passages: first.passages + added, pagesRead: first.pagesRead + readSecond)
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
