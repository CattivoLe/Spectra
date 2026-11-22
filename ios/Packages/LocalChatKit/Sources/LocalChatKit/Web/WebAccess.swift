import Foundation
import Observation
import Security

/// Хранилище секретов (ключ поискового сервиса). В приложении — связка ключей, в тестах — память.
public protocol SecretStore: Sendable {
    func read(_ account: String) -> String?
    func write(_ value: String?, for account: String)
}

public struct KeychainSecretStore: SecretStore {
    private let service: String
    public init(service: String = "cattivole.Spectra.assistant.web") { self.service = service }

    public func read(_ account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: account, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public func write(_ value: String?, for account: String) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(base as CFDictionary)
        guard let value, !value.isEmpty else { return }
        var attributes = base
        attributes[kSecValueData as String] = Data(value.utf8)
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        attributes[kSecAttrSynchronizable as String] = false
        SecItemAdd(attributes as CFDictionary, nil)
    }
}

public final class MemorySecretStore: SecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: String] = [:]
    public init() {}
    public func read(_ account: String) -> String? { lock.withLock { values[account] } }
    public func write(_ value: String?, for account: String) { lock.withLock { values[account] = value } }
}

/// Настройки доступа модели в интернет. По умолчанию всё выключено: приложение работает только на устройстве.
/// Наружу уходит одно — короткий поисковый запрос; диалог, профиль и сведения об устройстве не передаются.
@MainActor
@Observable
public final class WebAccess {
    public enum ProviderKind: String, CaseIterable, Codable, Sendable {
        case searxng, brave, tavily, exa, firecrawl, you, serper

        public var title: String {
            switch self {
            case .searxng: "SearXNG"
            case .brave: "Brave Search"
            case .tavily: "Tavily"
            case .exa: "Exa"
            case .firecrawl: "Firecrawl"
            case .you: "You.com"
            case .serper: "Serper (Google)"
            }
        }

        /// One line under the name in the list of services.
        public var summary: String {
            switch self {
            case .searxng: String(localized: "Your own metasearch server, no key")
            case .brave: String(localized: "Independent index, web and pictures")
            case .tavily: String(localized: "Made for AI: page text and pictures")
            case .exa: String(localized: "Search by meaning, page text")
            case .firecrawl: String(localized: "Search with pages read in full, pictures")
            case .you: String(localized: "Own index, page text")
            case .serper: String(localized: "Google results and Google Images")
            }
        }

        /// The chosen service in more words: what it sends, what it returns, where its key comes from.
        public var explanation: String {
            let note: String = switch self {
            case .searxng: String(localized: "Real web search through a SearXNG server you provide (ideally your own). The server must have the JSON format enabled.")
            case .brave: String(localized: "Real Brave web search. Needs your API key (there is a free plan). The key is kept in the phone's keychain.")
            case .tavily: String(localized: "A search service built for language models: it reads the pages itself and returns their text, and finds pictures. Key at tavily.com, with a free monthly allowance. The sites see Tavily, not this phone.")
            case .exa: String(localized: "Finds pages by meaning rather than exact words, which helps with odd model names; returns the pages' text and their preview pictures. Key at exa.ai; new accounts get free credits.")
            case .firecrawl: String(localized: "Searches and reads each page into clean text, and has an image search. Key at firecrawl.dev. The sites see Firecrawl, not this phone.")
            case .you: String(localized: "You.com's own web index; returns the pages' text. Pictures are the pages' previews. Key at you.com/platform, with free credits.")
            case .serper: String(localized: "Google's results and Google Images through one key: the best pictures of devices. Key at serper.dev, 2,500 free searches to start. The pages themselves are read by this phone.")
            }
            // SearXNG takes no key and Brave's own note already says where it is kept.
            guard keyAccount != nil, self != .brave else { return note }
            return note + " " + String(localized: "Only the query is sent; the key is kept in the phone's keychain.")
        }

        /// Every service here can look for pictures (You.com and Exa through the pages' previews).
        public var supportsImages: Bool { true }

        /// The services that need an API key, each kept under its own name in the keychain.
        public var keyAccount: String? {
            switch self {
            case .searxng: nil
            case .brave: Keys.braveAccount
            default: "\(rawValue)-key"
            }
        }
    }

    public var isEnabled: Bool { didSet { defaults.set(isEnabled, forKey: Keys.enabled) } }
    public var provider: ProviderKind { didSet { defaults.set(provider.rawValue, forKey: Keys.provider) } }
    /// Search language ("ru", "en" or "auto"): with an explicit language search engines answer more precisely.
    public var searchLanguage: String { didSet { defaults.set(searchLanguage, forKey: Keys.language) } }
    public var searxngAddress: String { didSet { defaults.set(searxngAddress, forKey: Keys.searxng) } }
    public var resultCount: Int { didSet { defaults.set(resultCount, forKey: Keys.count) } }
    /// Показывать запрос перед отправкой и давать его поправить.
    public var confirmEachSearch: Bool { didSet { defaults.set(confirmEachSearch, forKey: Keys.confirm) } }
    /// The API keys of the services that need one, by service.
    public private(set) var keys: [ProviderKind: String]

    /// The API key of `kind`; "" when it has none or needs none.
    public func key(_ kind: ProviderKind) -> String { keys[kind] ?? "" }

    public func setKey(_ kind: ProviderKind, _ value: String) {
        guard let account = kind.keyAccount else { return }
        keys[kind] = value
        secrets.write(value, for: account)
    }

    public var braveKey: String {
        get { key(.brave) }
        set { setKey(.brave, newValue) }
    }
    /// Read the pages found in full, not just the short descriptions from the results.
    public var readPages: Bool { didSet { defaults.set(readPages, forKey: Keys.readPages) } }
    public var pagesToRead: Int { didSet { defaults.set(pagesToRead, forKey: Keys.pages) } }
    /// Pick page excerpts by meaning (when an embedding model is installed), not only by words.
    public var useMeaning: Bool { didSet { defaults.set(useMeaning, forKey: Keys.meaning) } }
    /// The model for picking by meaning. Set by the app while one is installed.
    public var embedder: (any PassageEmbedder)?
    /// When what was found has no answer, search once more with another query.
    public var retryIfMissing: Bool { didSet { defaults.set(retryIfMissing, forKey: Keys.retry) } }
    /// Let the model write the query from the conversation, and skip searching when it is not needed.
    public var smartQuery: Bool { didSet { defaults.set(smartQuery, forKey: Keys.smartQuery) } }
    /// The host app's own rules for the query writer, e.g. which questions it answers from its own data. Set by the app.
    public var plannerNote: String?

    /// Спрашивает пользователя перед отправкой запроса. Возвращает запрос (возможно, исправленный) или `nil`, если пользователь отказался.
    public var approve: (@MainActor (String) async -> String?)?

    fileprivate enum Keys {
        static let enabled = "web.enabled", provider = "web.provider", language = "web.wikiLanguage"
        static let searxng = "web.searxng", count = "web.count", confirm = "web.confirm", braveAccount = "brave-key"
        static let readPages = "web.readPages", pages = "web.pagesToRead", smartQuery = "web.smartQuery", meaning = "web.useMeaning", retry = "web.retryIfMissing"
    }

    private let defaults: UserDefaults
    private let secrets: any SecretStore
    private let sessionFactory: @Sendable () -> URLSession

    public init(defaults: UserDefaults = .standard, secrets: any SecretStore = KeychainSecretStore(),
                sessionFactory: @escaping @Sendable () -> URLSession = { WebClient.makeSession() }) {
        self.defaults = defaults
        self.secrets = secrets
        self.sessionFactory = sessionFactory
        isEnabled = defaults.bool(forKey: Keys.enabled)
        // Wikipedia used to be the default and is gone: a saved "wikipedia" becomes SearXNG.
        provider = ProviderKind(rawValue: defaults.string(forKey: Keys.provider) ?? "") ?? .searxng
        // The key still says "wiki": it held Wikipedia's language, which is the same "ru"/"en" choice.
        searchLanguage = defaults.string(forKey: Keys.language) ?? (Locale.current.language.languageCode?.identifier == "ru" ? "ru" : "en")
        searxngAddress = defaults.string(forKey: Keys.searxng) ?? ""
        let count = defaults.integer(forKey: Keys.count)
        resultCount = (1...5).contains(count) ? count : 3
        confirmEachSearch = defaults.object(forKey: Keys.confirm) as? Bool ?? true
        var keys: [ProviderKind: String] = [:]
        for kind in ProviderKind.allCases {
            if let account = kind.keyAccount { keys[kind] = secrets.read(account) ?? "" }
        }
        self.keys = keys
        readPages = defaults.object(forKey: Keys.readPages) as? Bool ?? true
        let pages = defaults.integer(forKey: Keys.pages)
        pagesToRead = (1...5).contains(pages) ? pages : 3
        smartQuery = defaults.object(forKey: Keys.smartQuery) as? Bool ?? true
        useMeaning = defaults.object(forKey: Keys.meaning) as? Bool ?? true
        retryIfMissing = defaults.object(forKey: Keys.retry) as? Bool ?? true
    }

    /// How pages are read: the network in the app, canned pages in tests.
    public var fetcher: (any PageFetching)?
    /// How the search service is made: replaced in tests.
    public var providerFactory: (@MainActor (WebAccess) -> any SearchProvider)?

    /// The full search: results, reading pages and picking the excerpts that fit the question.
    /// `light` is the lighter one, for "More detail": fewer pages and a smaller share of the prompt.
    public func makeResearch(light: Bool = false) throws -> WebResearch {
        WebResearch(
            provider: try makeProvider(),
            fetcher: fetcher ?? PageFetcher(session: sessionFactory()),
            pagesToRead: !readPages ? 0 : light ? min(pagesToRead, 2) : pagesToRead,
            // Reading pages benefits from more candidates: picking excerpts weeds out the rest.
            resultCount: readPages ? (light ? 4 : 5) : resultCount,
            budget: light ? 2400 : 3600,
            embedder: useMeaning ? embedder : nil
        )
    }

    /// Готов ли выбранный источник к работе.
    public var configurationProblem: String? {
        switch provider {
        case .searxng: Self.validate(address: searxngAddress) == nil ? String(localized: "Enter the SearXNG server address (https://…)") : nil
        case .brave: braveKey.trimmingCharacters(in: .whitespaces).isEmpty ? String(localized: "Enter the Brave Search key") : nil
        default: key(provider).trimmingCharacters(in: .whitespaces).isEmpty ? String(localized: "Enter the \(provider.title) key") : nil
        }
    }

    /// On, and the chosen service set up: a search can go out.
    public var isReady: Bool { isEnabled && configurationProblem == nil }

    /// "ru", "en"… for the services that take a language; "auto" means none.
    private var language: String { searchLanguage.count == 2 ? searchLanguage : "" }

    public func makeProvider() throws -> any SearchProvider {
        if let providerFactory { return providerFactory(self) }
        if let problem = configurationProblem { throw WebSearchError.notConfigured(problem) }
        switch provider {
        case .searxng: return SearXNGProvider(baseURL: Self.validate(address: searxngAddress)!, language: searchLanguage, session: sessionFactory())
        case .brave: return BraveProvider(apiKey: braveKey.trimmingCharacters(in: .whitespaces), session: sessionFactory())
        case .tavily: return TavilyProvider(apiKey: key(.tavily), language: language)
        case .exa: return ExaProvider(apiKey: key(.exa))
        case .firecrawl: return FirecrawlProvider(apiKey: key(.firecrawl))
        case .you: return YouProvider(apiKey: key(.you), language: language)
        case .serper: return SerperProvider(apiKey: key(.serper), language: language)
        }
    }

    /// Адрес сервера: только https. Обычный http допускается лишь для своей сети (localhost, *.local, частные адреса),
    /// иначе запросы шли бы по интернету открытым текстом.
    public static func validate(address: String) -> URL? {
        let trimmed = address.trimmingCharacters(in: .whitespaces)
        guard let url = URL(string: trimmed), let host = url.host(), !host.isEmpty else { return nil }
        switch url.scheme {
        case "https": return url
        case "http": return isLocal(host: host) ? url : nil
        default: return nil
        }
    }

    static func isLocal(host: String) -> Bool {
        if host == "localhost" || host.hasSuffix(".local") { return true }
        let parts = host.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4 else { return false }
        return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && (16...31).contains(parts[1]))
    }
}
