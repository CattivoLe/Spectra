import Foundation

public struct FetchedPage: Sendable {
    public var url: URL
    public var html: String
}

public enum PageFetchError: LocalizedError, Equatable {
    case blockedAddress
    case notHTML(String)
    case http(Int)
    case unreadable

    public var errorDescription: String? {
        switch self {
        case .blockedAddress: String(localized: "This address is on a local network: such pages are never opened.")
        case .notHTML(let type): String(localized: "This is not a web page (\(type)).")
        case .http(let code): String(localized: "The site answered with error \(code).")
        case .unreadable: String(localized: "Could not read the page's text.")
        }
    }
}

/// Источник страниц. В приложении — сеть, в тестах — заготовки.
public protocol PageFetching: Sendable {
    func fetch(_ url: URL) async throws -> FetchedPage
}

/// Скачивает найденную страницу. Читает не больше 1,5 МБ, только HTML и текст, без cookie.
/// Адреса домашней сети не открывает: ссылки приходят из выдачи поиска, и через неё нельзя добраться до роутера или NAS.
public struct PageFetcher: PageFetching {
    private let session: URLSession
    private let maxBytes: Int
    private let timeout: TimeInterval

    public init(session: URLSession = WebClient.makeSession(), maxBytes: Int = 1_500_000, timeout: TimeInterval = 8) {
        self.session = session
        self.maxBytes = maxBytes
        self.timeout = timeout
    }

    public func fetch(_ url: URL) async throws -> FetchedPage {
        guard Self.isAllowed(url) else { throw PageFetchError.blockedAddress }
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.setValue("text/html,application/xhtml+xml,text/plain;q=0.8", forHTTPHeaderField: "Accept")

        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw PageFetchError.unreadable }
        guard (200..<300).contains(http.statusCode) else { throw PageFetchError.http(http.statusCode) }
        let type = (http.value(forHTTPHeaderField: "Content-Type") ?? "text/html").lowercased()
        guard type.contains("html") || type.contains("text/plain") else { throw PageFetchError.notHTML(type) }
        // После переадресации адрес тоже должен быть внешним.
        if let final = http.url, !Self.isAllowed(final) { throw PageFetchError.blockedAddress }

        var data = Data()
        data.reserveCapacity(min(maxBytes, 256_000))
        for try await byte in bytes {
            data.append(byte)
            if data.count >= maxBytes { break }
        }
        guard let html = Self.decode(data, contentType: type) else { throw PageFetchError.unreadable }
        return FetchedPage(url: http.url ?? url, html: html)
    }

    // MARK: - Кодировка

    /// Кодировка из заголовка, иначе из `<meta charset>`, иначе UTF-8. Старые русские сайты бывают в windows-1251 и KOI8-R.
    static func decode(_ data: Data, contentType: String) -> String? {
        let head = String(decoding: data.prefix(4096), as: UTF8.self).lowercased()
        let declared = charset(in: contentType) ?? charset(in: head)
        if let declared, let encoding = encoding(named: declared), let text = String(data: data, encoding: encoding) {
            return text
        }
        if let text = String(data: data, encoding: .utf8) { return text }
        // Обрезка на 1,5 МБ могла разрезать последний символ: отбрасываем хвост.
        if let text = String(data: data.dropLast(3), encoding: .utf8) { return text }
        return String(data: data, encoding: .windowsCP1251)
    }

    private static func charset(in text: String) -> String? {
        guard let range = text.range(of: "charset=") else { return nil }
        let rest = text[range.upperBound...].drop { $0 == "\"" || $0 == "'" }
        let name = rest.prefix { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        return name.isEmpty ? nil : String(name)
    }

    private static func encoding(named name: String) -> String.Encoding? {
        switch name {
        case "utf-8", "utf8": .utf8
        case "windows-1251", "cp1251", "win-1251": .windowsCP1251
        case "koi8-r", "koi8r":
            String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.KOI8_R.rawValue)))
        case "iso-8859-1", "latin1": .isoLatin1
        case "windows-1252", "cp1252": .windowsCP1252
        default: nil
        }
    }

    // MARK: - Адреса

    /// Только http(s) во внешнем интернете: не localhost, не *.local и не частные, служебные или IPv6-адреса.
    public static func isAllowed(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = url.host()?.lowercased(), !host.isEmpty else { return false }
        if host == "localhost" || host.hasSuffix(".local") || host.hasSuffix(".internal") || host.hasSuffix(".lan") { return false }
        if host.contains(":") { return false }
        let parts = host.split(separator: ".").compactMap { Int($0) }
        if parts.count == 4 {
            let (a, b) = (parts[0], parts[1])
            if a == 0 || a == 10 || a == 127 || a >= 224 { return false }
            if a == 169 && b == 254 { return false }
            if a == 172 && (16...31).contains(b) { return false }
            if a == 192 && b == 168 { return false }
            if a == 100 && (64...127).contains(b) { return false }
        }
        return true
    }
}
