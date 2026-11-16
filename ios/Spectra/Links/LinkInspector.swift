import Foundation

/// What a link looks like before anyone opens it.
nonisolated struct LinkInspection: Hashable, Sendable {
    enum Severity: Int, Comparable, Sendable {
        case info, caution, danger
        static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }
    }

    enum Verdict: Int, Comparable, Sendable {
        case noFlags, caution, suspicious, dangerous
        static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }
    }

    struct Finding: Hashable, Sendable, Identifiable {
        enum Kind: String, Sendable {
            case notWeb, insecure, ipAddress, userInfo, encodedHost
            case mixedScripts, lookalikeLetters, foreignScript, punycode
            case lookalike, brandInSubdomain, brandInDomain, official
            case shortener, freeHosting, riskyZone, manySubdomains, baitWords, unusualPort, leadsOn
            // From the on-demand certificate and registry check.
            case certificateUnavailable, certificateUntrusted, certificateMismatch, certificateExpired, certificateNew, certificateInfo
            case domainNew, domainYoung, domainInfo, domainMissing, domainUnknown
        }

        var kind: Kind
        var severity: Severity
        var text: String
        var id: Kind { kind }
    }

    /// The link as it was found in the text.
    var link: String
    var scheme: String
    /// The host as a person reads it, with punycode turned back into letters.
    var host: String
    /// The host as the address bar and DNS see it, when it differs from `host`.
    var asciiHost: String?
    /// Who owns the site: the name registered under its public suffix, or the
    /// account on a free hosting platform.
    var site: String
    var findings: [Finding]
    /// Links carried inside this one, like the `?url=` of a mail filter or a tracker.
    var nested: [LinkInspection]
    /// The address to request when tracing redirects. Nil for anything but a web link.
    var url: URL?

    var ownVerdict: Verdict {
        if findings.contains(where: { $0.severity == .danger }) { return .dangerous }
        switch findings.filter({ $0.severity == .caution }).count {
        case 0: return .noFlags
        case 1: return .caution
        default: return .suspicious
        }
    }

    var verdict: Verdict { max(ownVerdict, nested.map(\.verdict).max() ?? .noFlags) }
}

/// Reads a link the way a careful person would: who really owns the site, and
/// whether the address borrows a brand, hides behind look-alike letters or a
/// shortener, or carries the usual phishing bait.
///
/// Pure and offline. Nothing is fetched, so checking a link never tells the
/// sender that the message was opened.
nonisolated enum LinkInspector {
    /// The first link in `text`, inspected. Nil when the text holds no link.
    static func inspect(_ text: String) -> LinkInspection? {
        guard let link = extractLink(text) else { return nil }
        return inspect(link: link, depth: 0)
    }

    // MARK: - Finding the link

    static func extractLink(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let candidate: String
        if !trimmed.contains(where: \.isWhitespace) {
            candidate = trimmed
        } else if let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue),
                  let match = detector.firstMatch(in: trimmed, range: NSRange(trimmed.startIndex..., in: trimmed)),
                  let range = Range(match.range, in: trimmed) {
            candidate = String(trimmed[range])
        } else {
            return nil
        }
        var link = candidate.trimmingCharacters(in: CharacterSet(charactersIn: "<>\"'«»“”‘’()[]"))
        while let last = link.last, ".,;:!?".contains(last) { link.removeLast() }
        guard !link.isEmpty else { return nil }
        if schemeOf(link) != nil { return link }
        // A bare address needs a dot in its host to count as one.
        let host = link.prefix { !"/?#".contains($0) }
        return host.contains(".") ? link : nil
    }

    /// The scheme of `link`, when it starts with one. "example.com:8080" has none.
    private static func schemeOf(_ link: String) -> String? {
        guard let colon = link.firstIndex(of: ":") else { return nil }
        let head = link[..<colon]
        guard let first = head.first, first.isASCII, first.isLetter,
              head.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "+" || $0 == "-") }) else { return nil }
        return head.lowercased()
    }

    // MARK: - Inspecting

    private static func inspect(link: String, depth: Int) -> LinkInspection {
        var findings: [LinkInspection.Finding] = []
        func note(_ kind: LinkInspection.Finding.Kind, _ severity: LinkInspection.Severity, _ text: String) {
            guard !findings.contains(where: { $0.kind == kind }) else { return }
            findings.append(.init(kind: kind, severity: severity, text: text))
        }

        let scheme = schemeOf(link) ?? "https"
        guard scheme == "http" || scheme == "https" else {
            if ["javascript", "data", "vbscript", "file"].contains(scheme) {
                note(.notWeb, .danger, String(localized: "This is not a web page: a \(scheme): link runs code or opens a file."))
            } else {
                note(.notWeb, .info, String(localized: "This is not a web link but a \(scheme): link."))
            }
            return LinkInspection(link: link, scheme: scheme, host: "", asciiHost: nil, site: "", findings: findings, nested: [], url: nil)
        }

        // Authority: everything up to the first path, query or fragment.
        // Browsers read a backslash as a slash.
        let afterScheme: Substring = link.range(of: "://").map { link[$0.upperBound...] } ?? link[...]
        let authority = afterScheme.prefix { !"/?#\\".contains($0) }
        var hostPort = authority
        if let at = authority.lastIndex(of: "@") {
            hostPort = authority[authority.index(after: at)...]
            let userInfo = String(authority[..<at])
            note(.userInfo, .danger, String(localized: "The browser skips everything before the @ (\(userInfo)) and opens \(String(hostPort))."))
        }
        var rawHost = String(hostPort)
        var port: String?
        if rawHost.hasPrefix("["), let close = rawHost.firstIndex(of: "]") {
            let rest = rawHost[rawHost.index(after: close)...]
            if rest.hasPrefix(":") { port = String(rest.dropFirst()) }
            rawHost = String(rawHost[...close])
        } else if let colon = rawHost.lastIndex(of: ":") {
            let tail = rawHost[rawHost.index(after: colon)...]
            if tail.allSatisfy(\.isNumber) {
                port = tail.isEmpty ? nil : String(tail)
                rawHost = String(rawHost[..<colon])
            }
        }
        if rawHost.contains("%") {
            note(.encodedHost, .danger, String(localized: "The site name contains encoded characters, which real sites do not use."))
            rawHost = rawHost.removingPercentEncoding ?? rawHost
        }
        while rawHost.hasSuffix(".") { rawHost.removeLast() }

        let asciiLabels = rawHost.lowercased().split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        let labels = asciiLabels.map { label in
            label.hasPrefix("xn--") ? (punycodeDecode(String(label.dropFirst(4))) ?? label) : label
        }
        let host = labels.joined(separator: ".")
        let asciiHost = asciiLabels.contains { $0.hasPrefix("xn--") } ? asciiLabels.joined(separator: ".") : nil
        let url = webURL(link, scheme: scheme)

        if scheme == "http" {
            note(.insecure, .caution, String(localized: "The page is not encrypted (http): anything typed on it can be read on the way."))
        }
        if let port, port != "80", port != "443" {
            note(.unusualPort, .caution, String(localized: "The address uses an unusual port, \(port)."))
        }

        if isIPAddress(rawHost) {
            note(.ipAddress, .danger, String(localized: "A bare IP address (\(rawHost)) stands where a site name should be."))
            return LinkInspection(link: link, scheme: scheme, host: rawHost, asciiHost: nil, site: rawHost, findings: findings,
                                  nested: nestedLinks(in: url, depth: depth), url: url)
        }

        let (site, platform) = siteOf(labels)
        let siteLabels = site.split(separator: ".").map(String.init)
        let siteName = siteLabels.first ?? site
        let zone = labels.last ?? ""
        let subdomains = Array(labels.dropLast(siteLabels.count)).filter { $0 != "www" }

        // Letters
        if let asciiHost {
            note(.punycode, .info, String(localized: "In the address bar this shows as \(asciiHost)."))
        }
        for label in labels {
            let scripts = Set(label.unicodeScalars.compactMap(script))
            if scripts.count > 1 {
                note(.mixedScripts, .danger, String(localized: "“\(label)” mixes letters from different alphabets, a classic way to fake a familiar name."))
            } else if let only = scripts.first, only != .latin, label.allSatisfy({ confusables[$0] != nil || !$0.isLetter }),
                      !cyrillicZones.contains(zone) {
                note(.lookalikeLetters, .danger, String(localized: "“\(label)” is written in letters that only look Latin."))
            }
        }
        if asciiHost != nil || host.unicodeScalars.contains(where: { !$0.isASCII }),
           !findings.contains(where: { $0.kind == .mixedScripts || $0.kind == .lookalikeLetters }) {
            note(.foreignScript, .info, String(localized: "The site name uses non-Latin letters."))
        }

        // Brands
        let officialBrand = brands.first { isOfficial(site, for: $0) }
        if let officialBrand {
            note(.official, .info, String(localized: "\(site) belongs to \(officialBrand.display)."))
        } else {
            let siteSkeleton = skeleton(siteName)
            for brand in brands {
                let brandSkeleton = skeleton(brand.name)
                let typo = brand.name.count >= 6 && siteName.first == brand.name.first && siteName.last == brand.name.last
                    && levenshtein(siteName, brand.name) == 1
                if siteName == brand.name || siteSkeleton == brandSkeleton || typo {
                    note(.lookalike, .danger, String(localized: "Pretends to be \(brand.display). Its real site is \(brand.sites[0])."))
                    break
                }
            }
            if let brand = brands.first(where: { brand in subdomains.contains { mentions($0, brand) } }) {
                note(.brandInSubdomain, .danger, String(localized: "The address starts with \(brand.display), but the site belongs to \(site)."))
            }
            if !findings.contains(where: { $0.kind == .lookalike }),
               let brand = brands.first(where: { mentions(siteName, $0) }) {
                note(.brandInDomain, .caution, String(localized: "Uses the name \(brand.display), but it is not its site (\(brand.sites[0]))."))
            }
        }

        // Where the site lives
        if shorteners.contains(site) {
            note(.shortener, .caution, String(localized: "A short link: the real address is hidden. Expand the redirects to see it."))
        }
        if let platform {
            note(.freeHosting, .caution, String(localized: "The site sits on \(platform), where anyone can make an address like this."))
        }
        if riskyZones.contains(zone) {
            note(.riskyZone, .caution, String(localized: "The .\(zone) zone is common in phishing mail."))
        }
        if subdomains.count >= 4 {
            note(.manySubdomains, .caution, String(localized: "An unusually long name: \(subdomains.count) levels in front of \(site)."))
        }
        if officialBrand == nil {
            let tokens = Set(labels.dropLast().flatMap { $0.split(whereSeparator: { $0 == "-" || $0 == "_" || $0.isNumber }).map(String.init) })
            let bait = baitWords.filter(tokens.contains).sorted()
            if !bait.isEmpty {
                note(.baitWords, .caution, String(localized: "The address has words phishing likes to use: \(bait.joined(separator: ", "))."))
            }
        }

        let nested = nestedLinks(in: url, depth: depth)
        if let first = nested.first {
            note(.leadsOn, .info, String(localized: "The link passes on another address: \(first.host.isEmpty ? first.link : first.host)."))
        }
        return LinkInspection(link: link, scheme: scheme, host: host, asciiHost: asciiHost, site: site, findings: findings, nested: nested, url: url)
    }

    private static func webURL(_ link: String, scheme: String) -> URL? {
        let full = link.range(of: "://") == nil ? "\(scheme)://\(link)" : link
        return URL(string: full) ?? URL(string: full, encodingInvalidCharacters: true)
    }

    /// Web addresses carried in the query, as mail filters and trackers wrap them.
    private static func nestedLinks(in url: URL?, depth: Int) -> [LinkInspection] {
        guard depth < 2, let url, let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { return [] }
        for item in items where redirectParameters.contains(item.name.lowercased()) {
            guard let value = item.value?.trimmingCharacters(in: .whitespaces),
                  value.lowercased().hasPrefix("http://") || value.lowercased().hasPrefix("https://") else { continue }
            return [inspect(link: value, depth: depth + 1)]
        }
        return []
    }

    // MARK: - Sites

    /// The registered name: the last two labels, or three under a public
    /// suffix of two labels, or the account under a free hosting platform.
    static func siteOf(_ labels: [String]) -> (site: String, platform: String?) {
        guard labels.count > 2 else { return (labels.joined(separator: "."), nil) }
        let lastTwo = labels.suffix(2).joined(separator: ".")
        if hostingPlatforms.contains(lastTwo) {
            return (labels.suffix(3).joined(separator: "."), lastTwo)
        }
        if twoLabelSuffixes.contains(lastTwo) {
            return (labels.suffix(3).joined(separator: "."), nil)
        }
        return (lastTwo, nil)
    }

    private static func isOfficial(_ site: String, for brand: Brand) -> Bool {
        if brand.sites.contains(site) { return true }
        // The brand's own name in a country zone, like amazon.de or yandex.kz.
        let parts = site.split(separator: ".")
        guard parts.count == 2, parts[0] == brand.name, let zone = parts.last else { return false }
        return zone.count == 2 || ["com", "net", "org"].contains(zone)
    }

    /// Whether a label carries the brand as a word of its own: "paypal",
    /// "paypal-login", "appleid". Short names only count as whole words.
    private static func mentions(_ label: String, _ brand: Brand) -> Bool {
        let words = label.split(whereSeparator: { $0 == "-" || $0 == "_" }).map { skeleton(String($0)) }
        let name = skeleton(brand.name)
        return words.contains { $0 == name || (name.count >= 5 && $0.hasPrefix(name)) }
    }

    // MARK: - Letters

    enum Script: Hashable { case latin, cyrillic, greek, other }

    private static func script(_ scalar: Unicode.Scalar) -> Script? {
        guard scalar.properties.isAlphabetic else { return nil }
        switch scalar.value {
        case 0x41...0x24F: return .latin
        case 0x370...0x3FF: return .greek
        case 0x400...0x52F: return .cyrillic
        default: return .other
        }
    }

    /// Letters that pass for Latin ones, and digits that pass for letters.
    private static let confusables: [Character: Character] = [
        "а": "a", "с": "c", "е": "e", "ё": "e", "о": "o", "р": "p", "х": "x", "у": "y", "ѕ": "s", "і": "l",
        "ї": "l", "ј": "j", "ӏ": "l", "ԁ": "d", "һ": "h", "ԛ": "q", "ԝ": "w", "ү": "y", "к": "k",
        "α": "a", "ο": "o", "ν": "v", "ρ": "p", "ι": "l", "κ": "k", "υ": "u", "χ": "x", "ε": "e",
        "0": "o", "1": "l", "3": "e", "5": "s", "i": "l", "|": "l",
    ]

    /// A name reduced to the shapes a reader sees: "pаypa1" and "paypal" match.
    static func skeleton(_ text: String) -> String {
        String(text.lowercased().map { confusables[$0] ?? $0 })
            .replacingOccurrences(of: "rn", with: "m")
            .replacingOccurrences(of: "vv", with: "w")
    }

    private static func levenshtein(_ a: String, _ b: String) -> Int {
        let a = Array(a), b = Array(b)
        guard !a.isEmpty else { return b.count }
        guard !b.isEmpty else { return a.count }
        var previous = Array(0...b.count)
        for i in 1...a.count {
            var current = [i] + Array(repeating: 0, count: b.count)
            for j in 1...b.count {
                current[j] = min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1))
            }
            previous = current
        }
        return previous[b.count]
    }

    private static func isIPAddress(_ host: String) -> Bool {
        if host.hasPrefix("[") { return true }
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        if parts.count == 4, parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) }) { return true }
        // A whole address as one number, decimal or hex: http://3232235777/
        if parts.count == 1, let only = parts.first, !only.isEmpty {
            return only.allSatisfy(\.isNumber) || (only.lowercased().hasPrefix("0x") && only.dropFirst(2).allSatisfy(\.isHexDigit))
        }
        return false
    }

    /// RFC 3492. Takes a label without its "xn--" prefix.
    static func punycodeDecode(_ input: String) -> String? {
        let base = 36, tMin = 1, tMax = 26, skew = 38, damp = 700
        var n = 128, i = 0, bias = 72
        var output: [Unicode.Scalar] = []
        let scalars = Array(input.unicodeScalars)
        var position = 0
        if let delimiter = scalars.lastIndex(of: "-") {
            for scalar in scalars[..<delimiter] {
                guard scalar.isASCII else { return nil }
                output.append(scalar)
            }
            position = delimiter + 1
        }
        func adapt(_ delta: Int, _ points: Int, _ first: Bool) -> Int {
            var delta = first ? delta / damp : delta / 2
            delta += delta / points
            var k = 0
            while delta > ((base - tMin) * tMax) / 2 {
                delta /= base - tMin
                k += base
            }
            return k + (base - tMin + 1) * delta / (delta + skew)
        }
        while position < scalars.count {
            let oldI = i
            var w = 1
            var k = base
            while true {
                guard position < scalars.count else { return nil }
                let value = scalars[position].value
                position += 1
                let digit: Int
                switch value {
                case 0x30...0x39: digit = Int(value) - 22
                case 0x41...0x5A: digit = Int(value) - 0x41
                case 0x61...0x7A: digit = Int(value) - 0x61
                default: return nil
                }
                i += digit * w
                let t = k <= bias ? tMin : (k >= bias + tMax ? tMax : k - bias)
                if digit < t { break }
                w *= base - t
                k += base
                guard w < 1 << 40, i < 1 << 40 else { return nil }
            }
            bias = adapt(i - oldI, output.count + 1, oldI == 0)
            n += i / (output.count + 1)
            i %= output.count + 1
            guard let scalar = Unicode.Scalar(n) else { return nil }
            output.insert(scalar, at: i)
            i += 1
        }
        var result = ""
        result.unicodeScalars.append(contentsOf: output)
        return result
    }

    // MARK: - Lists

    struct Brand: Sendable {
        var name: String
        var display: String
        var sites: [String]
    }

    static let brands: [Brand] = [
        Brand(name: "apple", display: "Apple", sites: ["apple.com", "icloud.com", "me.com", "apple.news"]),
        Brand(name: "icloud", display: "iCloud", sites: ["icloud.com", "apple.com"]),
        Brand(name: "google", display: "Google", sites: ["google.com", "gmail.com", "youtube.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "google-analytics.com", "googlemail.com", "g.co", "goo.gl"]),
        Brand(name: "gmail", display: "Gmail", sites: ["gmail.com", "google.com"]),
        Brand(name: "youtube", display: "YouTube", sites: ["youtube.com", "youtu.be", "google.com"]),
        Brand(name: "microsoft", display: "Microsoft", sites: ["microsoft.com", "microsoftonline.com", "live.com", "office.com", "outlook.com", "office365.com", "sharepoint.com", "onmicrosoft.com", "msn.com", "bing.com", "azure.com", "windows.net"]),
        Brand(name: "outlook", display: "Outlook", sites: ["outlook.com", "live.com", "office.com", "microsoft.com"]),
        Brand(name: "office365", display: "Microsoft 365", sites: ["office.com", "office365.com", "microsoft.com"]),
        Brand(name: "sharepoint", display: "SharePoint", sites: ["sharepoint.com", "microsoft.com"]),
        Brand(name: "paypal", display: "PayPal", sites: ["paypal.com", "paypal.me", "paypalobjects.com"]),
        Brand(name: "amazon", display: "Amazon", sites: ["amazon.com", "amazon.co.uk", "amazonaws.com"]),
        Brand(name: "netflix", display: "Netflix", sites: ["netflix.com"]),
        Brand(name: "facebook", display: "Facebook", sites: ["facebook.com", "fb.com", "fb.me", "messenger.com", "meta.com"]),
        Brand(name: "instagram", display: "Instagram", sites: ["instagram.com"]),
        Brand(name: "whatsapp", display: "WhatsApp", sites: ["whatsapp.com", "whatsapp.net", "wa.me"]),
        Brand(name: "telegram", display: "Telegram", sites: ["telegram.org", "t.me", "telegram.me", "telegra.ph"]),
        Brand(name: "linkedin", display: "LinkedIn", sites: ["linkedin.com", "lnkd.in"]),
        Brand(name: "dropbox", display: "Dropbox", sites: ["dropbox.com", "dropboxusercontent.com"]),
        Brand(name: "docusign", display: "DocuSign", sites: ["docusign.com", "docusign.net"]),
        Brand(name: "adobe", display: "Adobe", sites: ["adobe.com"]),
        Brand(name: "binance", display: "Binance", sites: ["binance.com"]),
        Brand(name: "coinbase", display: "Coinbase", sites: ["coinbase.com"]),
        Brand(name: "metamask", display: "MetaMask", sites: ["metamask.io"]),
        Brand(name: "steamcommunity", display: "Steam", sites: ["steamcommunity.com", "steampowered.com"]),
        Brand(name: "steampowered", display: "Steam", sites: ["steampowered.com", "steamcommunity.com"]),
        Brand(name: "spotify", display: "Spotify", sites: ["spotify.com"]),
        Brand(name: "tiktok", display: "TikTok", sites: ["tiktok.com"]),
        Brand(name: "booking", display: "Booking.com", sites: ["booking.com"]),
        Brand(name: "airbnb", display: "Airbnb", sites: ["airbnb.com"]),
        Brand(name: "dhl", display: "DHL", sites: ["dhl.com", "dhl.de"]),
        Brand(name: "fedex", display: "FedEx", sites: ["fedex.com"]),
        Brand(name: "yandex", display: String(localized: "Yandex", comment: "Brand or agency name"), sites: ["yandex.ru", "yandex.com", "ya.ru", "yandex.net"]),
        Brand(name: "gosuslugi", display: String(localized: "Gosuslugi", comment: "Brand or agency name"), sites: ["gosuslugi.ru"]),
        Brand(name: "sberbank", display: String(localized: "Sberbank", comment: "Brand or agency name"), sites: ["sberbank.ru", "sber.ru", "sberbank.com"]),
        Brand(name: "sber", display: String(localized: "Sber", comment: "Brand or agency name"), sites: ["sber.ru", "sberbank.ru", "sberbank.com"]),
        Brand(name: "tinkoff", display: String(localized: "T-Bank", comment: "Brand or agency name"), sites: ["tinkoff.ru", "tbank.ru"]),
        Brand(name: "tbank", display: String(localized: "T-Bank", comment: "Brand or agency name"), sites: ["tbank.ru", "tinkoff.ru"]),
        Brand(name: "alfabank", display: String(localized: "Alfa-Bank", comment: "Brand or agency name"), sites: ["alfabank.ru", "alfabank.com"]),
        Brand(name: "vtb", display: String(localized: "VTB", comment: "Brand or agency name"), sites: ["vtb.ru", "vtb.com"]),
        Brand(name: "raiffeisen", display: String(localized: "Raiffeisen", comment: "Brand or agency name"), sites: ["raiffeisen.ru", "raiffeisen.com"]),
        Brand(name: "ozon", display: "Ozon", sites: ["ozon.ru", "ozon.com"]),
        Brand(name: "wildberries", display: "Wildberries", sites: ["wildberries.ru", "wb.ru"]),
        Brand(name: "avito", display: String(localized: "Avito", comment: "Brand or agency name"), sites: ["avito.ru"]),
        Brand(name: "pochta", display: String(localized: "Russian Post", comment: "Brand or agency name"), sites: ["pochta.ru"]),
        Brand(name: "cdek", display: String(localized: "CDEK", comment: "Brand or agency name"), sites: ["cdek.ru"]),
        Brand(name: "nalog", display: String(localized: "the Federal Tax Service", comment: "Brand or agency name"), sites: ["nalog.gov.ru", "nalog.ru"]),
        Brand(name: "vkontakte", display: "VK", sites: ["vk.com", "vk.ru", "vkontakte.ru"]),
    ]

    private static let shorteners: Set<String> = [
        "bit.ly", "bitly.com", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "cutt.ly", "rebrand.ly",
        "clck.ru", "vk.cc", "u.to", "goo.su", "tiny.one", "shorturl.at", "rb.gy", "t.ly", "s.id", "surl.li",
        "tiny.cc", "v.gd", "qps.ru", "bit.do", "shorturl.com", "urlr.me", "lnk.to",
    ]

    /// Hosts where every subdomain is a different owner, for free.
    private static let hostingPlatforms: Set<String> = [
        "github.io", "gitlab.io", "herokuapp.com", "vercel.app", "netlify.app", "pages.dev", "workers.dev",
        "web.app", "firebaseapp.com", "appspot.com", "blogspot.com", "glitch.me", "repl.co", "replit.app",
        "wixsite.com", "weebly.com", "tilda.ws", "webflow.io", "framer.app", "framer.website", "notion.site",
        "square.site", "godaddysites.com", "mystrikingly.com", "carrd.co", "r2.dev", "ngrok-free.app", "ngrok.io",
        "ngrok.app", "azurewebsites.net", "cloudfront.net", "onrender.com", "fly.dev", "surge.sh",
        "000webhostapp.com", "ucoz.ru", "narod.ru", "ipfs.dweb.link", "w3s.link",
    ]

    /// Public suffixes of two labels; the full list has thousands, these are the common ones.
    private static let twoLabelSuffixes: Set<String> = [
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp", "ne.jp", "or.jp",
        "com.br", "com.cn", "com.hk", "com.tw", "com.sg", "com.my", "com.tr", "com.ua", "kiev.ua", "com.kz", "org.kz",
        "co.in", "net.in", "co.za", "com.mx", "com.ar", "com.co", "co.kr", "co.il", "com.ru", "net.ru", "org.ru", "pp.ru",
        "msk.ru", "spb.ru", "gov.ru", "com.by", "gov.by", "com.pl", "co.th", "com.vn", "com.ph", "com.pk", "com.eg",
        "com.sa", "com.ng", "co.ke",
    ]

    private static let riskyZones: Set<String> = [
        "zip", "mov", "xyz", "top", "icu", "click", "link", "cam", "rest", "sbs", "cfd", "quest", "gq", "ml", "cf", "tk",
        "ga", "buzz", "monster", "cyou", "lol", "bond", "win", "loan", "work", "country", "kim", "men", "date", "faith",
        "racing", "review", "science", "stream", "party", "trade", "accountant", "cricket", "download", "gdn", "mom",
        "bar", "beauty", "hair", "skin", "makeup", "autos", "boats", "homes", "yachts",
    ]

    private static let cyrillicZones: Set<String> = ["рф", "рус", "бел", "укр", "қаз", "срб", "мкд", "мон", "москва", "онлайн", "сайт"]

    private static let baitWords: Set<String> = [
        "login", "logon", "signin", "signon", "verify", "verification", "secure", "security", "account", "accounts",
        "update", "confirm", "unlock", "wallet", "bonus", "bonuses", "prize", "gift", "giftcard", "reward", "rewards",
        "refund", "claim", "support", "auth", "password", "billing", "invoice", "payment", "pay", "payout", "recover",
        "recovery", "airdrop", "kompensaciya", "kompensacia", "vyplata", "vyplaty", "vozvrat", "priz", "podarok",
    ]

    private static let redirectParameters: Set<String> = [
        "url", "u", "q", "to", "target", "redirect", "redirect_uri", "redirect_url", "redirecturl", "return",
        "returnurl", "return_to", "next", "continue", "dest", "destination", "link", "goto", "r", "out", "away",
    ]
}
