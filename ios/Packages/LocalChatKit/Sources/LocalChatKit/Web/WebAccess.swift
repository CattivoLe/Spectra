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
        case wikipedia, searxng, brave

        public var title: String {
            switch self {
            case .wikipedia: "Wikipedia"
            case .searxng: "SearXNG (your own server)"
            case .brave: "Brave Search (your own key)"
            }
        }

        public var explanation: String {
            switch self {
            case .wikipedia: String(localized: "No key or account. Finds facts, people, places and concepts, but not news or prices: it is an encyclopedia, not the whole internet.")
            case .searxng: String(localized: "Real web search through a SearXNG server you provide (ideally your own). The server must have the JSON format enabled.")
            case .brave: String(localized: "Real Brave web search. Needs your API key (there is a free plan). The key is kept in the phone's keychain.")
            }
        }
    }

    public var isEnabled: Bool { didSet { defaults.set(isEnabled, forKey: Keys.enabled) } }
    public var provider: ProviderKind { didSet { defaults.set(provider.rawValue, forKey: Keys.provider) } }
    public var wikipediaLanguage: String { didSet { defaults.set(wikipediaLanguage, forKey: Keys.language) } }
    public var searxngAddress: String { didSet { defaults.set(searxngAddress, forKey: Keys.searxng) } }
    public var resultCount: Int { didSet { defaults.set(resultCount, forKey: Keys.count) } }
    /// Показывать запрос перед отправкой и давать его поправить.
    public var confirmEachSearch: Bool { didSet { defaults.set(confirmEachSearch, forKey: Keys.confirm) } }
    public var braveKey: String { didSet { secrets.write(braveKey, for: Keys.braveAccount) } }

    /// Спрашивает пользователя перед отправкой запроса. Возвращает запрос (возможно, исправленный) или `nil`, если пользователь отказался.
    public var approve: (@MainActor (String) async -> String?)?

    private enum Keys {
        static let enabled = "web.enabled", provider = "web.provider", language = "web.wikiLanguage"
        static let searxng = "web.searxng", count = "web.count", confirm = "web.confirm", braveAccount = "brave-key"
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
        provider = ProviderKind(rawValue: defaults.string(forKey: Keys.provider) ?? "") ?? .wikipedia
        wikipediaLanguage = defaults.string(forKey: Keys.language) ?? (Locale.current.language.languageCode?.identifier == "ru" ? "ru" : "en")
        searxngAddress = defaults.string(forKey: Keys.searxng) ?? ""
        let count = defaults.integer(forKey: Keys.count)
        resultCount = (1...5).contains(count) ? count : 3
        confirmEachSearch = defaults.object(forKey: Keys.confirm) as? Bool ?? true
        braveKey = secrets.read(Keys.braveAccount) ?? ""
    }

    /// Готов ли выбранный источник к работе.
    public var configurationProblem: String? {
        switch provider {
        case .wikipedia: nil
        case .searxng: Self.validate(address: searxngAddress) == nil ? String(localized: "Enter the SearXNG server address (https://…)") : nil
        case .brave: braveKey.trimmingCharacters(in: .whitespaces).isEmpty ? String(localized: "Enter the Brave Search key") : nil
        }
    }

    public func makeProvider() throws -> any SearchProvider {
        if let problem = configurationProblem { throw WebSearchError.notConfigured(problem) }
        switch provider {
        case .wikipedia: return WikipediaProvider(language: wikipediaLanguage, session: sessionFactory())
        case .searxng: return SearXNGProvider(baseURL: Self.validate(address: searxngAddress)!, session: sessionFactory())
        case .brave: return BraveProvider(apiKey: braveKey.trimmingCharacters(in: .whitespaces), session: sessionFactory())
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
