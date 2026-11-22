import Foundation

/*
 * Search services with an API key. Each gets the query and nothing else: no conversation, nothing about the device.
 * The ones that read pages themselves (Tavily, Exa, Firecrawl, You.com) hand back the page text in
 * `SearchResult.content`, so the phone does not fetch those pages again. Same as ApiProviders.kt on Android.
 */

/// How much of a page the services are asked for: enough for the passages the model gets, no more.
private let pageCharacters = 6000

private typealias JSON = [String: Any]

private func parse(_ data: Data) throws -> JSON {
    guard let object = try? JSONSerialization.jsonObject(with: data) as? JSON else { throw WebSearchError.badResponse }
    return object
}

private func requireKey(_ key: String, _ service: String) throws {
    if key.trimmingCharacters(in: .whitespaces).isEmpty {
        throw WebSearchError.notConfigured(String(localized: "Enter the \(service) key"))
    }
}

private extension Dictionary where Key == String, Value == Any {
    func string(_ name: String) -> String { self[name] as? String ?? "" }
    func objects(_ name: String) -> [JSON] { self[name] as? [JSON] ?? [] }
    func object(_ name: String) -> JSON? { self[name] as? JSON }
}

private func httpURL(_ text: String?) -> URL? {
    guard let text, text.hasPrefix("http"), let url = URL(string: text), !(url.host() ?? "").isEmpty else { return nil }
    return url
}

private func pageText(_ text: String?) -> String? {
    guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
    return String(text.prefix(pageCharacters))
}

private func unique(_ images: [ImageResult], limit: Int) -> [ImageResult] {
    var seen: Set<URL> = []
    return Array(images.filter { seen.insert($0.image).inserted }.prefix(limit))
}

/// Tavily: a search API made for language models; returns the page text and, when asked, pictures.
public struct TavilyProvider: SearchProvider {
    public let name = "Tavily"
    private let apiKey: String
    private let language: String
    private let session: URLSession

    public init(apiKey: String, language: String = "ru", session: URLSession = WebClient.makeSession(requestTimeout: 40, resourceTimeout: 60)) {
        self.apiKey = apiKey.trimmingCharacters(in: .whitespaces)
        self.language = language
        self.session = session
    }

    private func request(_ query: String, limit: Int, images: Bool) async throws -> JSON {
        try requireKey(apiKey, name)
        var body: JSON = [
            "query": query, "max_results": min(max(limit, 1), 20), "search_depth": "basic",
            "include_images": images, "safe_search": true,
        ]
        body["include_raw_content"] = images ? false : "text"
        if language.count == 2 { body["language"] = language }
        return try parse(try await WebClient.post(URL(string: "https://api.tavily.com/search")!, body: body,
                                                  headers: ["Authorization": "Bearer \(apiKey)"], session: session))
    }

    public func search(_ query: String, limit: Int) async throws -> [SearchResult] {
        try await request(query, limit: limit, images: false).objects("results").compactMap { item -> SearchResult? in
            guard let url = httpURL(item.string("url")) else { return nil }
            let title = item.string("title").isEmpty ? (url.host() ?? "") : item.string("title")
            return SearchResult(title: WebClient.plainText(title), url: url, snippet: WebClient.plainText(item.string("content")),
                                content: pageText(item["raw_content"] as? String))
        }.prefix(limit).map { $0 }
    }

    public func searchImages(_ query: String, limit: Int) async throws -> [ImageResult] {
        let json = try await request(query, limit: 5, images: true)
        let page = httpURL(json.objects("results").first?.string("url"))
        let found = (json["images"] as? [Any] ?? []).compactMap { item -> ImageResult? in
            // Either a bare address or {url, description}.
            let object = item as? JSON
            guard let url = httpURL(object?.string("url") ?? item as? String) else { return nil }
            let title = String(WebClient.plainText(object?.string("description") ?? "").prefix(120))
            return ImageResult(title: title, thumbnail: url, image: url, page: page ?? url)
        }
        return unique(found, limit: limit)
    }
}

/// Exa: search by meaning rather than keywords; returns page text and each page's preview picture.
public struct ExaProvider: SearchProvider {
    public let name = "Exa"
    private let apiKey: String
    private let session: URLSession

    public init(apiKey: String, session: URLSession = WebClient.makeSession(requestTimeout: 40, resourceTimeout: 60)) {
        self.apiKey = apiKey.trimmingCharacters(in: .whitespaces)
        self.session = session
    }

    private func request(_ query: String, limit: Int, text: Bool) async throws -> [JSON] {
        try requireKey(apiKey, name)
        let contents: JSON = text ? ["text": ["maxCharacters": pageCharacters]] : ["highlights": true]
        let body: JSON = ["query": query, "numResults": min(max(limit, 1), 25), "type": "auto", "moderation": true, "contents": contents]
        return try parse(try await WebClient.post(URL(string: "https://api.exa.ai/search")!, body: body,
                                                  headers: ["x-api-key": apiKey], session: session)).objects("results")
    }

    public func search(_ query: String, limit: Int) async throws -> [SearchResult] {
        try await request(query, limit: limit, text: true).compactMap { item -> SearchResult? in
            guard let url = httpURL(item.string("url")) else { return nil }
            let text = pageText(item["text"] as? String)
            let highlights = (item["highlights"] as? [String] ?? []).joined(separator: " … ")
            let snippet = highlights.isEmpty ? String((text ?? "").prefix(300)) : highlights
            let title = item.string("title").isEmpty ? (url.host() ?? "") : item.string("title")
            return SearchResult(title: WebClient.plainText(title), url: url, snippet: WebClient.plainText(snippet), content: text)
        }.prefix(limit).map { $0 }
    }

    /// The preview picture each page gives itself: a product page's is usually the product.
    public func searchImages(_ query: String, limit: Int) async throws -> [ImageResult] {
        let found = try await request(query, limit: limit * 2, text: false).compactMap { item -> ImageResult? in
            guard let image = httpURL(item.string("image")) else { return nil }
            return ImageResult(title: WebClient.plainText(item.string("title")), thumbnail: image, image: image,
                               page: httpURL(item.string("url")) ?? image)
        }
        return unique(found, limit: limit)
    }
}

/// Firecrawl: search plus each page read into clean markdown; also an image search.
public struct FirecrawlProvider: SearchProvider {
    public let name = "Firecrawl"
    private let apiKey: String
    private let session: URLSession

    public init(apiKey: String, session: URLSession = WebClient.makeSession(requestTimeout: 40, resourceTimeout: 60)) {
        self.apiKey = apiKey.trimmingCharacters(in: .whitespaces)
        self.session = session
    }

    private func request(_ query: String, limit: Int, source: String, scrape: Bool) async throws -> JSON {
        try requireKey(apiKey, name)
        var body: JSON = ["query": String(query.prefix(500)), "limit": min(max(limit, 1), 20), "sources": [source], "safe": true, "timeout": 35_000]
        if scrape { body["scrapeOptions"] = ["formats": ["markdown"], "onlyMainContent": true] as JSON }
        let json = try parse(try await WebClient.post(URL(string: "https://api.firecrawl.dev/v2/search")!, body: body,
                                                      headers: ["Authorization": "Bearer \(apiKey)"], session: session))
        return json.object("data") ?? [:]
    }

    public func search(_ query: String, limit: Int) async throws -> [SearchResult] {
        try await request(query, limit: limit, source: "web", scrape: true).objects("web").compactMap { item -> SearchResult? in
            guard let url = httpURL(item.string("url")) else { return nil }
            let title = item.string("title").isEmpty ? (url.host() ?? "") : item.string("title")
            return SearchResult(title: WebClient.plainText(title), url: url, snippet: WebClient.plainText(item.string("description")),
                                content: pageText(item["markdown"] as? String))
        }.prefix(limit).map { $0 }
    }

    public func searchImages(_ query: String, limit: Int) async throws -> [ImageResult] {
        let found = try await request(query, limit: limit * 2, source: "images", scrape: false).objects("images").compactMap { item -> ImageResult? in
            guard let image = httpURL(item.string("imageUrl")) else { return nil }
            return ImageResult(title: WebClient.plainText(item.string("title")), thumbnail: image, image: image,
                               page: httpURL(item.string("url")) ?? image)
        }
        return unique(found, limit: limit)
    }
}

/// You.com: its own web index; with full-page extraction it returns each page as markdown.
public struct YouProvider: SearchProvider {
    public let name = "You.com"
    private let apiKey: String
    private let language: String
    private let session: URLSession

    public init(apiKey: String, language: String = "ru", session: URLSession = WebClient.makeSession(requestTimeout: 40, resourceTimeout: 60)) {
        self.apiKey = apiKey.trimmingCharacters(in: .whitespaces)
        self.language = language
        self.session = session
    }

    private func request(_ query: String, limit: Int, pages: Bool) async throws -> [JSON] {
        try requireKey(apiKey, name)
        var body: JSON = ["query": query, "count": min(max(limit, 1), 20), "safesearch": "strict"]
        if language.count == 2 { body["language"] = language.uppercased() }
        if pages {
            body["extraction"] = ["extraction_mode": "full_page", "full_page": ["extraction_formats": ["markdown"]] as JSON] as JSON
        }
        let json = try parse(try await WebClient.post(URL(string: "https://ydc-index.io/v1/search")!, body: body,
                                                      headers: ["X-API-Key": apiKey], session: session))
        return json.object("results")?.objects("web") ?? []
    }

    public func search(_ query: String, limit: Int) async throws -> [SearchResult] {
        try await request(query, limit: limit, pages: true).compactMap { item -> SearchResult? in
            guard let url = httpURL(item.string("url")) else { return nil }
            let snippets = (item["snippets"] as? [String] ?? []).joined(separator: " … ")
            let description = item.string("description").isEmpty ? snippets : item.string("description")
            let title = item.string("title").isEmpty ? (url.host() ?? "") : item.string("title")
            return SearchResult(title: WebClient.plainText(title), url: url, snippet: WebClient.plainText(description),
                                content: pageText(item.object("contents")?["markdown"] as? String))
        }.prefix(limit).map { $0 }
    }

    /// You.com has no image search: each page's own preview picture stands in.
    public func searchImages(_ query: String, limit: Int) async throws -> [ImageResult] {
        let found = try await request(query, limit: limit * 2, pages: false).compactMap { item -> ImageResult? in
            guard let image = httpURL(item.string("thumbnail_url")) else { return nil }
            return ImageResult(title: WebClient.plainText(item.string("title")), thumbnail: image, image: image,
                               page: httpURL(item.string("url")) ?? image)
        }
        return unique(found, limit: limit)
    }
}

/// Serper: Google's results and Google Images through one key; cheap, with a free start. Snippets only, pages are
/// read by the phone.
public struct SerperProvider: SearchProvider {
    public let name = "Serper (Google)"
    private let apiKey: String
    private let language: String
    private let session: URLSession

    public init(apiKey: String, language: String = "ru", session: URLSession = WebClient.makeSession()) {
        self.apiKey = apiKey.trimmingCharacters(in: .whitespaces)
        self.language = language
        self.session = session
    }

    private func request(_ endpoint: String, _ query: String, limit: Int) async throws -> JSON {
        try requireKey(apiKey, name)
        var body: JSON = ["q": query, "num": min(max(limit, 1), 20)]
        if language.count == 2 { body["hl"] = language }
        return try parse(try await WebClient.post(URL(string: "https://google.serper.dev/\(endpoint)")!, body: body,
                                                  headers: ["X-API-KEY": apiKey], session: session))
    }

    public func search(_ query: String, limit: Int) async throws -> [SearchResult] {
        try await request("search", query, limit: limit).objects("organic").compactMap { item -> SearchResult? in
            guard let url = httpURL(item.string("link")) else { return nil }
            let title = item.string("title").isEmpty ? (url.host() ?? "") : item.string("title")
            return SearchResult(title: WebClient.plainText(title), url: url, snippet: WebClient.plainText(item.string("snippet")))
        }.prefix(limit).map { $0 }
    }

    public func searchImages(_ query: String, limit: Int) async throws -> [ImageResult] {
        let found = try await request("images", query, limit: limit * 2).objects("images").compactMap { item -> ImageResult? in
            guard let image = httpURL(item.string("imageUrl")) else { return nil }
            // Google's own small copy: the picture's site sees nothing until the full image is opened.
            let thumbnail = httpURL(item.string("thumbnailUrl")) ?? image
            return ImageResult(title: WebClient.plainText(item.string("title")), thumbnail: thumbnail, image: image,
                               page: httpURL(item.string("link")) ?? image)
        }
        return unique(found, limit: limit)
    }
}
