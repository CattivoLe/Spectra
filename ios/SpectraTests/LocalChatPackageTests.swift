import Foundation
import LocalChatEngine
@testable import LocalChatKit
import Synchronization
import Testing

/// What the packages gained with the summary cards (Android 1.1.0): folders and card subjects in the store, several
/// saved system prompts, search services that read pages themselves, one search pass.
struct ChatStoreFolderTests {
    @Test func foldersHoldChatsAndDeletingOneKeepsTheChats() async throws {
        let store = try ChatStore()
        let folder = try await store.createFolder(name: "  Дача  ")
        #expect(folder.name == "Дача")
        let card = try await store.createChat(subject: "spectra-card:lan", folder: folder.id)
        let other = try await store.createChat()
        #expect(try await store.chat(card.id)?.subject == "spectra-card:lan")
        #expect(try await store.chat(card.id)?.folderID == folder.id)
        #expect(try await store.chat(other.id)?.subject == nil)

        try await store.renameFolder(folder.id, to: "Дом")
        #expect(try await store.folders().map(\.name) == ["Дом"])

        try await store.moveChat(other.id, to: folder.id)
        #expect(try await store.chats().filter { $0.folderID == folder.id }.count == 2)

        try await store.deleteFolder(folder.id)
        #expect(try await store.folders().isEmpty)
        let left = try await store.chats()
        #expect(left.count == 2)
        #expect(left.allSatisfy { $0.folderID == nil })
    }

    @Test func titlesKeepNinetyCharacters() async throws {
        let store = try ChatStore()
        let chat = try await store.createChat()
        let long = "Очень длинный первый вопрос, который точно длиннее девяноста символов, а значит его заголовок обрежут"
        try await store.append(.user, long, to: chat.id)
        let title = try #require(try await store.chat(chat.id)?.title)
        #expect(title == String(long.prefix(90)) + "…")
    }

    @Test func deletingEverythingTakesTheFoldersToo() async throws {
        let store = try ChatStore()
        let folder = try await store.createFolder(name: "A")
        _ = try await store.createChat(subject: "spectra-card:traffic", folder: folder.id)
        try await store.deleteEverything()
        #expect(try await store.folders().isEmpty)
        #expect(try await store.chats().isEmpty)
    }

    @Test func appendingToADeletedChatThrowsInsteadOfFailingTheDatabase() async throws {
        let store = try ChatStore()
        let chat = try await store.createChat(subject: "spectra-card:lan")
        try await store.deleteChat(chat.id)
        await #expect(throws: ChatStoreError.self) { try await store.append(.assistant, "поздно", to: chat.id) }
        #expect(try await store.chats().isEmpty)
    }
}

struct PromptCacheLimitTests {
    private func key(_ n: Int32) -> PromptCache.Key {
        PromptCache.Key(model: "m.gguf", modelBytes: 1, contextLength: 8192, backend: "metal", prefix: (0..<100).map { $0 + n * 1000 })
    }

    @Test func severalPromptsKeepTheirOwnFilesUpToTheLimit() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("prompt-cache-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let cache = PromptCache(directory: directory, keep: 2)
        let keys = (1...3).map { key(Int32($0)) }
        for (index, each) in keys.enumerated() {
            try Data("state \(index)".utf8).write(to: cache.temporaryURL(for: each))
            #expect(cache.commit(each))
            // Distinct times even on a file system that keeps whole seconds.
            try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 1_000_000 * Double(index + 1))],
                                                  ofItemAtPath: cache.url(for: each).path)
        }
        // The oldest one went; the two latest stay side by side.
        let exists = { (k: PromptCache.Key) in FileManager.default.fileExists(atPath: cache.url(for: k).path) }
        #expect(!exists(keys[0]))
        #expect(exists(keys[1]))
        #expect(exists(keys[2]))

        // Using one makes it the freshest: the next new prompt pushes out the other.
        cache.touch(keys[1])
        let fourth = key(4)
        try Data("state 4".utf8).write(to: cache.temporaryURL(for: fourth))
        #expect(cache.commit(fourth))
        #expect(exists(keys[1]))
        #expect(!exists(keys[2]))
        #expect(try FileManager.default.contentsOfDirectory(atPath: directory.path).count == 2)
    }
}

/// Search services that read pages themselves (Tavily, Exa, Firecrawl, You.com): their text is used, the page is not fetched.
struct ServiceTextTests {
    static let markdown = """
        # Xiaomi Smart Air Fryer
        The air fryer connects over Wi-Fi to the [Mi Home](https://home.mi.com) app.
        ![photo](https://example.com/fryer.jpg)

        ## Security
        Firmware updates arrive through Mi Home; the local miIO protocol on UDP 54321 needs the device token.
        """ + "\n\n" + String(repeating: "Filler sentence about cooking without oil. ", count: 12)

    @Test func markdownBecomesHeadingsAndParagraphsWithoutAddresses() {
        let page = ContentExtractor.fromText(title: "Fryer", text: Self.markdown)
        #expect(page.blocks.first?.isHeading == true)
        #expect(page.blocks.first?.text == "Xiaomi Smart Air Fryer")
        #expect(page.blocks.contains { $0.isHeading && $0.text == "Security" })
        #expect(page.text.contains("to the Mi Home app"))
        #expect(!page.text.contains("https://"))
    }

    @Test func pageTextFromTheServiceIsNotFetchedAgain() async throws {
        let given = SearchResult(title: "Fryer", url: URL(string: "https://example.com/fryer")!, snippet: "short", content: Self.markdown)
        let plain = SearchResult(title: "Other", url: URL(string: "https://example.com/other")!, snippet: "snippet")
        let fetcher = CountingFetcher(pages: ["https://example.com/other": webArticleHTML])
        let research = WebResearch(provider: ListProvider(results: [given, plain]), fetcher: fetcher, pagesToRead: 2)
        let gathered = try await research.gather(query: "xiaomi air fryer")
        #expect(gathered.pagesRead == 2)
        #expect(fetcher.fetched.withLock { $0 } == ["https://example.com/other"])
        #expect(gathered.passages.contains { $0.sourceIndex == 0 && $0.text.contains("UDP 54321") })
    }

    @Test func theServiceTextIsNotStoredWithTheSearch() throws {
        let result = SearchResult(title: "T", url: URL(string: "https://e.example")!, snippet: "s", content: "long page text")
        let decoded = try JSONDecoder().decode(SearchResult.self, from: JSONEncoder().encode(result))
        #expect(decoded.content == nil)
        #expect(decoded.title == "T")
    }

    @MainActor
    @Test func eachServiceKeepsItsOwnKey() throws {
        let web = WebAccess(defaults: UserDefaults(suiteName: "keys-\(UUID().uuidString)")!, secrets: MemorySecretStore())
        web.isEnabled = true
        web.provider = .tavily
        #expect(!web.isReady)
        web.setKey(.tavily, "tvly-1")
        web.setKey(.serper, "serper-2")
        #expect(web.isReady)
        #expect(try web.makeProvider().name == "Tavily")
        web.provider = .serper
        #expect(try web.makeProvider().name == "Serper (Google)")
        #expect(web.key(.tavily) == "tvly-1")
        web.provider = .exa
        #expect(!web.isReady)
        #expect(WebAccess.ProviderKind.allCases.count == 7)
        #expect(WebAccess.ProviderKind.allCases.allSatisfy { $0.supportsImages })
    }

    @MainActor
    @Test func keysSurviveAReopen() {
        let secrets = MemorySecretStore()
        let defaults = UserDefaults(suiteName: "keys-\(UUID().uuidString)")!
        WebAccess(defaults: defaults, secrets: secrets).setKey(.firecrawl, "fc-9")
        #expect(WebAccess(defaults: defaults, secrets: secrets).key(.firecrawl) == "fc-9")
        #expect(secrets.read("firecrawl-key") == "fc-9")
    }

    @MainActor
    @Test func theLightSearchReadsFewerPages() throws {
        let web = WebAccess(defaults: UserDefaults(suiteName: "light-\(UUID().uuidString)")!, secrets: MemorySecretStore())
        web.providerFactory = { _ in ListProvider(results: []) }
        web.pagesToRead = 3
        #expect(try web.makeResearch().pagesToRead == 3)
        #expect(try web.makeResearch(light: true).pagesToRead == 2)
        let light = try web.makeResearch(light: true).budget
        let full = try web.makeResearch().budget
        #expect(light < full)
    }
}

struct SinglePassResearchTests {
    @Test func oneQueryGoesOutAndIsLogged() async throws {
        let results = [
            SearchResult(title: "Туры", url: URL(string: "https://tours.example")!, snippet: "Купить тур в Париж"),
            SearchResult(title: "Башня", url: URL(string: "https://paris.example/tower")!, snippet: "Эйфелева башня"),
        ]
        let research = WebResearch(provider: ListProvider(results: results),
                                   fetcher: CountingFetcher(pages: ["https://paris.example/tower": webArticleHTML]), pagesToRead: 2)
        let sent = Mutex<[String]>([])
        let payload = try await research.run(query: "высота Эйфелевой башни", question: "Какая высота у Эйфелевой башни?",
                                             sent: { query in sent.withLock { $0.append(query) } })
        #expect(sent.withLock { $0 } == ["высота Эйфелевой башни"])
        #expect(payload.evidence?.contains { $0.text.contains("330 метров") } == true)
        #expect(payload.extraQueries == nil)

        // From the cache: nothing goes online.
        let cached = try await research.gather(query: "x")
        let again = Mutex<[String]>([])
        _ = try await research.run(query: "x", question: "x", initial: cached, sent: { query in again.withLock { $0.append(query) } })
        #expect(again.withLock { $0 }.isEmpty)
    }

    @Test func onlyTheBestPiecesByWordsAreEmbedded() {
        let passages = (0..<100).map { Passage(sourceIndex: 0, text: $0 == 70 ? "высота башни 330 метров" : "текст \($0)", position: $0) }
        let shortlist = PassageSelector.shortlist(passages, for: "высота", limit: WebResearch.maxEmbedded)
        #expect(shortlist.count == 40)
        #expect(shortlist.first == 70)
        // A short list goes whole: by meaning a piece with no words in common may still fit.
        #expect(PassageSelector.shortlist(Array(passages.prefix(3)), for: "высота", limit: 40) == [0, 1, 2])
    }

    @Test func picturesAreStoredWithTheSearch() throws {
        let image = ImageResult(title: "Fryer", thumbnail: URL(string: "https://t.example/1.jpg")!, image: URL(string: "https://i.example/1.jpg")!,
                                page: URL(string: "https://shop.example/fryer")!)
        let payload = SearchPayload(query: "q", provider: "p", results: [], images: [image])
        let decoded = try JSONDecoder().decode(SearchPayload.self, from: JSONEncoder().encode(payload))
        #expect(decoded.images == [image])
        #expect(image.host == "shop.example")
    }
}

private struct ListProvider: SearchProvider {
    let name = "List"
    let results: [SearchResult]
    func search(_ query: String, limit: Int) async throws -> [SearchResult] { Array(results.prefix(limit)) }
}

private final class CountingFetcher: PageFetching {
    let pages: [String: String]
    let fetched = Mutex<[String]>([])
    init(pages: [String: String]) { self.pages = pages }
    func fetch(_ url: URL) async throws -> FetchedPage {
        fetched.withLock { $0.append(url.absoluteString) }
        guard let html = pages[url.absoluteString] else { throw PageFetchError.http(404) }
        return FetchedPage(url: url, html: html)
    }
}
