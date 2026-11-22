import Foundation
import Synchronization
import Testing
import LocalChatEngine
@testable import LocalChatKit
@testable import Spectra

// Web search with page reading, ported from LocalChat together with its tests. The network is replaced by
// `WebStubProtocol`: nothing leaves the machine, and every test has its own made-up host.

/// Replaces the network: requests never go out, and both answers and addresses can be checked.
/// Handlers are keyed by host, so tests running in parallel do not disturb each other.
final class WebStubProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) throws -> (Int, Data)
    nonisolated(unsafe) private static var handlers: [String: Handler] = [:]
    nonisolated(unsafe) private static var headers: [String: [String: String]] = [:]
    nonisolated(unsafe) private static var seen: [String: [URLRequest]] = [:]
    private static let lock = NSLock()

    static func register(host: String, headers: [String: String] = [:], _ handler: @escaping Handler) {
        lock.withLock { handlers[host] = handler; self.headers[host] = headers; seen[host] = [] }
    }
    static func requests(host: String) -> [URLRequest] { lock.withLock { seen[host] ?? [] } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let host = request.url?.host() ?? ""
        let (handler, headers) = Self.lock.withLock { () -> (Handler?, [String: String]) in
            Self.seen[host, default: []].append(request)
            return (Self.handlers[host], Self.headers[host] ?? [:])
        }
        do {
            guard let handler else { throw URLError(.cannotFindHost) }
            let (status, data) = try handler(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: headers)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }
    override func stopLoading() {}

    static func session() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [WebStubProtocol.self]
        configuration.httpAdditionalHeaders = ["User-Agent": "Spectra"]
        return URLSession(configuration: configuration)
    }

    static func sentQueries(_ host: String) -> [String] {
        requests(host: host).compactMap { request in
            URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "q" }?.value
        }
    }
}

let webArticleHTML = """
<html><head><title>Сайт про Париж</title><meta property="og:title" content="Высота Эйфелевой башни"></head>
<body>
  <header><nav><a href="/">Главная</a> <a href="/news">Новости</a> <a href="/about">О нас и о том, как мы работаем каждый день</a></nav></header>
  <div class="sidebar"><p>Реклама: купите тур в Париж по самой низкой цене прямо сейчас, скидки до 50 процентов!</p></div>
  <article>
    <h1>Высота Эйфелевой башни</h1>
    <p>Эйфелева башня — металлическая башня в центре Парижа, построенная в 1889 году к Всемирной выставке.</p>
    <h2>Сколько метров</h2>
    <p>Нынешняя высота башни составляет 330 метров вместе с антенной; без неё — около 300 метров.</p>
    <table><tr><td>Год</td><td>Высота</td></tr><tr><td>1889</td><td>312 м</td></tr></table>
    <ul><li><p>Масса металлической конструкции около 7300 тонн, всего башня весит около 10 000 тонн.</p></li></ul>
    <script>var tracker = "секретный счётчик";</script>
  </article>
  <footer><p>© Все права защищены. Копирование материалов сайта запрещено без разрешения редакции.</p></footer>
</body></html>
"""

// MARK: - Search providers and settings

struct AssistantWebSearchTests {
    @Test func searxngParsesSendsLanguageAndRespectsLimit() async throws {
        WebStubProtocol.register(host: "t3.search.example") { _ in
            (200, Data(#"{"results":[{"title":"A","url":"https://a.example","content":"<b>один</b> &amp; два"},{"title":"B","url":"https://b.example","content":"x"},{"url":"https://c.example"}]}"#.utf8))
        }
        let results = try await SearXNGProvider(baseURL: URL(string: "https://t3.search.example")!, language: "ru", session: WebStubProtocol.session())
            .search("тест", limit: 2)
        #expect(results.count == 2)
        #expect(results[0].snippet == "один & два")
        let url = try #require(WebStubProtocol.requests(host: "t3.search.example").first?.url)
        #expect(url.path == "/search")
        #expect(url.query?.contains("language=ru") == true)
        #expect(url.query?.contains("format=json") == true)
    }

    @Test func searxngDropsLinksWithoutHostAndKeepsSecretPath() async throws {
        WebStubProtocol.register(host: "t7.search.example") { _ in
            (200, Data(#"{"results":[{"title":"Broken","url":"http:///go/v1/abc","content":"x"},{"title":"Fine","url":"https://ok.example/page","content":"y"}]}"#.utf8))
        }
        let provider = SearXNGProvider(baseURL: URL(string: "https://t7.search.example/секрет123/")!, language: "en", session: WebStubProtocol.session())
        let results = try await provider.search("test", limit: 5)
        #expect(results.map(\.title) == ["Fine"])
        let url = try #require(WebStubProtocol.requests(host: "t7.search.example").first?.url)
        #expect(url.path.removingPercentEncoding == "/секрет123/search", "a secret path prefix is kept")
        #expect(url.query?.contains("language=en") == true)
    }

}

@MainActor
struct AssistantWebAccessTests {
    private func make(suite: String = "web-\(UUID().uuidString)") -> (WebAccess, UserDefaults) {
        let defaults = UserDefaults(suiteName: suite)!
        return (WebAccess(defaults: defaults, secrets: MemorySecretStore(), sessionFactory: { WebStubProtocol.session() }), defaults)
    }

    @Test func offByDefaultWithSafeDefaults() {
        let (web, _) = make()
        #expect(!web.isEnabled)
        #expect(web.provider == .searxng)
        #expect(web.configurationProblem != nil, "no search until a server address is entered")
        #expect(web.confirmEachSearch, "the query is shown before sending by default")
        #expect(web.readPages && web.pagesToRead == 3 && web.useMeaning)
    }

    @Test func newSettingsPersist() {
        let suite = "web-\(UUID().uuidString)"
        let (first, _) = make(suite: suite)
        first.readPages = false
        first.pagesToRead = 5
        first.useMeaning = false
        first.searchLanguage = "auto"
        let (second, _) = make(suite: suite)
        #expect(!second.readPages && second.pagesToRead == 5 && !second.useMeaning)
        #expect(second.searchLanguage == "auto")
    }

    @Test func oldWikipediaSettingBecomesSearXNG() {
        let defaults = UserDefaults(suiteName: "wiki-\(UUID().uuidString)")!
        defaults.set("wikipedia", forKey: "web.provider")
        defaults.set("en", forKey: "web.wikiLanguage")
        let web = WebAccess(defaults: defaults, secrets: MemorySecretStore())
        #expect(web.provider == .searxng)
        #expect(web.searchLanguage == "en", "the chosen language is kept")
    }

    @Test func researchFollowsTheSettings() throws {
        let (web, _) = make()
        web.searxngAddress = "https://search.example.org"
        web.pagesToRead = 2
        #expect(try web.makeResearch().pagesToRead == 2)
        #expect(try web.makeResearch().resultCount == 5, "reading pages takes more candidates")
        web.readPages = false
        web.resultCount = 4
        #expect(try web.makeResearch().pagesToRead == 0)
        #expect(try web.makeResearch().resultCount == 4)
    }
}

// MARK: - Reading pages

struct AssistantPageReadingTests {
    @Test func extractorTakesArticleTextAndDropsNavigationAdsAndScripts() {
        let page = ContentExtractor.extract(html: webArticleHTML, url: URL(string: "https://paris.example/tower")!)
        #expect(page.title == "Высота Эйфелевой башни")
        let text = page.text
        #expect(text.contains("Нынешняя высота башни составляет 330 метров"))
        #expect(text.contains("1889 | 312 м"), "table rows are kept")
        #expect(!text.contains("Главная") && !text.contains("Реклама") && !text.contains("Все права защищены"))
        #expect(!text.contains("секретный счётчик"))
        #expect(page.blocks.contains { $0.isHeading && $0.text == "Сколько метров" })
    }

    @Test func brokenHTMLDoesNotCrash() {
        let page = ContentExtractor.extract(html: "<p>незакрытый <b>тег <div>", url: URL(string: "https://x.example")!)
        #expect(page.blocks.count <= 1)
        #expect(ContentExtractor.extract(html: "", url: URL(string: "https://x.example")!).blocks.isEmpty)
    }

    @Test func chunksKeepHeadingsAndRespectTheLimit() {
        let long = String(repeating: "Длинное предложение про башню и Париж. ", count: 60)
        let page = ExtractedPage(title: "T", blocks: [
            .init(text: "Раздел первый", isHeading: true),
            .init(text: long, isHeading: false),
            .init(text: "Раздел второй", isHeading: true),
            .init(text: "Короткий абзац второго раздела с важным фактом.", isHeading: false),
        ])
        let passages = Chunker.chunk(page, sourceIndex: 2)
        #expect(passages.allSatisfy { $0.text.count <= 1200 })
        #expect(passages.allSatisfy { $0.sourceIndex == 2 })
        #expect(passages.first?.heading == "Раздел первый")
        #expect(passages.last?.text.contains("важным фактом") == true)
        #expect(passages.map(\.position) == Array(0..<passages.count))
    }

    @Test func wordFormsMatchInBothLanguages() {
        #expect(Terms.of("высота") == Terms.of("высоту"))
        #expect(Terms.of("Какая высота у башни?") == Terms.of("высота башни"))
        #expect(Terms.of("routers") == Terms.of("router"))
        #expect(Terms.of("330 метров").contains("330"))
    }

    @Test func selectionFindsTheAnswerAndSkipsNoise() {
        let passages = [
            Passage(sourceIndex: 0, text: "Париж — столица Франции, город музеев и кафе.", position: 0),
            Passage(sourceIndex: 1, text: "Нынешняя высота Эйфелевой башни составляет 330 метров.", position: 0),
            Passage(sourceIndex: 2, text: "Рецепт борща: свёкла, капуста, картофель.", position: 0),
        ]
        let chosen = PassageSelector.select(passages, for: "Какая высота у Эйфелевой башни", budget: 3000)
        #expect(chosen.first?.text.contains("330 метров") == true)
        #expect(!chosen.contains { $0.text.contains("борща") })
    }

    @Test func onlyPublicWebAddressesAreOpened() {
        for allowed in ["https://ru.wikipedia.org/wiki/X", "http://example.com", "https://8.8.8.8/x"] {
            #expect(PageFetcher.isAllowed(URL(string: allowed)!), "\(allowed)")
        }
        // Links come from search results: they must never reach the user's router, NAS or the LAN devices Spectra found.
        for blocked in ["http://localhost:8080", "http://router.local/", "http://192.168.1.1/admin", "http://10.0.0.2", "http://172.16.5.4",
                        "http://127.0.0.1", "http://169.254.169.254/latest/meta-data", "http://100.64.1.1", "http://[::1]/", "file:///etc/passwd",
                        "http://nas.lan/", "http://0.0.0.0"] {
            #expect(!PageFetcher.isAllowed(URL(string: blocked)!), "\(blocked)")
        }
    }

    @Test func decodesOldRussianEncodings() throws {
        let text = #"<html><head><meta charset="windows-1251"></head><body>Привет, мир</body></html>"#
        let data = try #require(text.data(using: .windowsCP1251))
        #expect(PageFetcher.decode(data, contentType: "text/html")?.contains("Привет, мир") == true)
    }

    @Test func fetcherStopsAtTheSizeLimitAndRejectsNonPages() async throws {
        let host = "f\(UUID().uuidString.prefix(6).lowercased()).example"
        WebStubProtocol.register(host: host, headers: ["Content-Type": "text/html; charset=utf-8"]) { _ in
            (200, Data(("<p>" + String(repeating: "а", count: 50_000) + "</p>").utf8))
        }
        let page = try await PageFetcher(session: WebStubProtocol.session(), maxBytes: 10_000).fetch(URL(string: "https://\(host)/p")!)
        #expect(page.html.utf8.count <= 10_000)

        let pdf = "g\(UUID().uuidString.prefix(6).lowercased()).example"
        WebStubProtocol.register(host: pdf, headers: ["Content-Type": "application/pdf"]) { _ in (200, Data("%PDF".utf8)) }
        await #expect(throws: PageFetchError.self) { try await PageFetcher(session: WebStubProtocol.session()).fetch(URL(string: "https://\(pdf)/f.pdf")!) }
        await #expect(throws: PageFetchError.blockedAddress) { try await PageFetcher(session: WebStubProtocol.session()).fetch(URL(string: "http://192.168.1.1/")!) }
    }
}

// MARK: - Research, retry and ranking without a network

private struct FixedProvider: SearchProvider {
    let name = "Test search"
    let results: [SearchResult]
    func search(_ query: String, limit: Int) async throws -> [SearchResult] { Array(results.prefix(limit)) }
}

private struct PageTable: PageFetching {
    let pages: [String: String]
    func fetch(_ url: URL) async throws -> FetchedPage {
        guard let html = pages[url.absoluteString] else { throw PageFetchError.http(404) }
        return FetchedPage(url: url, html: html)
    }
}

private actor StageLog {
    var all: [ResearchStage] = []
    func add(_ stage: ResearchStage) { all.append(stage) }
}

/// A stand-in embedder: "meaning" is set by hand, one vector per key concept.
private struct ConceptEmbedder: PassageEmbedder {
    static func vector(_ text: String) -> [Float] {
        let lower = text.lowercased()
        if lower.contains("высот") || lower.contains("метр") || lower.contains("макушк") { return [1, 0, 0] }
        if lower.contains("борщ") { return [0, 1, 0] }
        return [0, 0, 1]
    }
    func embed(queries: [String]) async throws -> [[Float]] { queries.map(Self.vector) }
    func embed(passages: [String]) async throws -> [[Float]] { passages.map(Self.vector) }
}

private struct FailingEmbedder: PassageEmbedder {
    func embed(queries: [String]) async throws -> [[Float]] { throw URLError(.unknown) }
    func embed(passages: [String]) async throws -> [[Float]] { throw URLError(.unknown) }
}

struct AssistantResearchTests {
    private let results = [
        SearchResult(title: "Эйфелева башня — Википедия", url: URL(string: "https://wiki.example/tower")!, snippet: "Эйфелева башня — металлическая башня в центре Парижа."),
        SearchResult(title: "Высота башни", url: URL(string: "https://paris.example/tower")!, snippet: "История и высота Эйфелевой башни."),
        SearchResult(title: "Туры в Париж", url: URL(string: "https://tours.example/")!, snippet: "Купить тур недорого."),
    ]
    private let liberty = SearchResult(title: "Статуя Свободы", url: URL(string: "https://a.example/liberty")!, snippet: "")
    private let tower = SearchResult(title: "Эйфелева башня", url: URL(string: "https://b.example/tower")!, snippet: "")
    private var pages: [String: String] {
        ["https://a.example/liberty": "<article><p>Высота головы статуи от макушки до подбородка составляет 5,26 метра, а вся статуя стоит в Нью-Йорке.</p></article>",
         "https://b.example/tower": "<article><p>Высота Эйфелевой башни вместе с антенной составляет 330 метров, сама конструкция — 312 метров.</p></article>"]
    }

    @Test func readsPagesAndFindsTheFactThatSnippetsDoNotHave() async throws {
        let research = WebResearch(provider: FixedProvider(results: results), fetcher: PageTable(pages: ["https://paris.example/tower": webArticleHTML]), pagesToRead: 3)
        let stages = StageLog()
        let payload = try await research.run(query: "высота Эйфелевой башни", question: "Какая высота у Эйфелевой башни?") { await stages.add($0) }

        #expect(payload.pagesRead == 1, "two pages failing to open does not fail the whole search")
        let evidence = try #require(payload.evidence)
        #expect(evidence.contains { $0.text.contains("330 метров") })
        #expect(!evidence.contains { $0.text.contains("Купить тур") })
        #expect(await stages.all == [.searching, .reading(pages: 3), .selecting])

        // A source's number is its place in the results: the article is second, the tours (third) are left out.
        #expect(payload.usedSourceIndices.contains(1))
        #expect(!payload.usedSourceIndices.contains(2))
    }

    @Test func withoutReadingPagesAllSnippetsGoAsIs() async throws {
        let research = WebResearch(provider: FixedProvider(results: results), fetcher: PageTable(pages: [:]), pagesToRead: 0, resultCount: 3)
        let payload = try await research.run(query: "башня", question: "Что за башня?")
        #expect(payload.evidence == nil)
        #expect(payload.results.count == 3)
        #expect(payload.usedSourceIndices == [0, 1, 2])
    }

    @Test func oldStoredPayloadWithoutEvidenceStillDecodes() throws {
        let old = #"{"query":"q","provider":"p","date":0,"results":[{"title":"t","url":"https://x.example","snippet":"s"}]}"#
        let payload = try JSONDecoder().decode(SearchPayload.self, from: Data(old.utf8))
        #expect(payload.evidence == nil && payload.pagesRead == nil)
        #expect(payload.usedSourceIndices == [0])
    }

    @Test func meaningFindsWhatWordsMissAndAFailingEmbedderFallsBackToWords() async {
        let passages = [
            Passage(sourceIndex: 0, text: "Рецепт борща со свёклой.", position: 0),
            Passage(sourceIndex: 1, text: "Сооружение поднимается на 330 метров вместе с антенной.", position: 0),
            Passage(sourceIndex: 2, text: "Париж — главный символ Франции, город музеев.", position: 0),
        ]
        let question = "Сколько до макушки главного символа Парижа?"
        #expect(!PassageSelector.order(passages, for: question, method: .words).contains(1))

        var research = WebResearch(provider: FixedProvider(results: []), fetcher: PageTable(pages: [:]), embedder: ConceptEmbedder())
        #expect(await research.select(from: passages, question: question, query: "").contains { $0.text.contains("330") })
        research.embedder = FailingEmbedder()
        #expect(await research.select(from: passages, question: "рецепт борща", query: "").contains { $0.text.contains("борща") })
    }

    @Test func embedderPrefixesMatchTheModel() {
        #expect(LlamaPassageEmbedder.Style.guess(fileName: "embeddinggemma-300m-qat-Q8_0.gguf") == .embeddingGemma)
        #expect(LlamaPassageEmbedder.Style.guess(fileName: "e5-small-Q8_0.gguf") == .e5)
        #expect(LlamaPassageEmbedder.Style.guess(fileName: "other.gguf") == .plain)
    }

    @MainActor
    @Test func embeddingModelIsNotAChatModel() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "emb-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        for name in ["gemma-4-E2B-it-Q4_K_M.gguf", "embeddinggemma-300m-qat-Q8_0.gguf"] {
            try Data("GGUF".utf8).write(to: directory.appending(path: name))
        }
        let library = ModelLibrary(directory: directory, catalog: [], configuration: .default, freeSpace: { _ in .max })
        library.refreshInstalled()
        #expect(library.chatModels.map(\.fileName) == ["gemma-4-E2B-it-Q4_K_M.gguf"])
        #expect(library.embeddingModel?.fileName == "embeddinggemma-300m-qat-Q8_0.gguf")
    }
}

// MARK: - The model's side jobs: writing the query, checking the answer

// MARK: - Search inside a chat


struct SearchCacheSimilarityTests {
    @Test func wordingDoesNotMatterButMeaningDoes() {
        #expect(SearchCache.isSimilar("Какая высота у Эйфелевой башни?", "эйфелева башня высота"))
        #expect(SearchCache.isSimilar("wifi 6 router price", "router price wifi 6"))
        #expect(SearchCache.isSimilar("WPA3 transition mode", "WPA3 transition mode security"), "one extra word")
        #expect(!SearchCache.isSimilar("высота Эйфелевой башни", "вес Эйфелевой башни"))
        #expect(!SearchCache.isSimilar("wifi 6 router price", "wifi 7 router"))
        #expect(!SearchCache.isSimilar("что", "как"), "nothing meaningful to compare")
    }
}

// MARK: - Questions the app answers itself, without the web

// MARK: - Tools named in an answer: words in the text, a question to tap under it

