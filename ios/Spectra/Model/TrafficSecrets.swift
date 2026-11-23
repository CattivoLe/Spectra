import Foundation

/// Passwords, tokens and keys in recorded requests: by the name of the field that carries them, and by their own look.
/// What is found is named by its kind and its place only. The value is never kept, shown or handed on.
/// Port of Android `TrafficSecrets`.
nonisolated enum TrafficSecrets {
    enum Kind: String, CaseIterable, Sendable { case password, token, key, session, card, privateKey, email }

    enum Place: Hashable, Sendable {
        case path
        case query(String)
        case requestHeader(String)
        case responseHeader(String)
        /// A field of the body by name; empty when the value alone gave it away.
        case requestBody(String)
        case responseBody(String)
        case message

        /// Part of the address itself, which servers write to their logs and browsers to their history.
        var inAddress: Bool {
            switch self {
            case .path, .query: true
            default: false
            }
        }
    }

    struct Find: Hashable, Sendable {
        var kind: Kind
        var place: Place
    }

    /// How much of one body is searched.
    static let bodyLimit = 256 * 1024

    // MARK: - By name

    /// What a field called `name` carries when it carries a secret at all.
    static func kind(ofName name: String) -> Kind? {
        let plain = String(name.lowercased().unicodeScalars.filter { ("a"..."z").contains($0) || ("0"..."9").contains($0) })
        if plain.isEmpty { return nil }
        if ["pwd", "pass", "passwd", "passcode", "passphrase", "pincode", "otp"].contains(plain) { return .password }
        if ["password", "passwd", "passphrase"].contains(where: plain.hasSuffix) { return .password }
        if ["cardnumber", "cardno", "ccnumber", "creditcard", "pan"].contains(plain) { return .card }
        // Cursors, form guards and push addresses are called tokens too, but open nothing.
        let harmless = ["csrf", "xsrf", "page", "next", "prev", "continuation", "cursor", "sync", "push", "device", "fcm", "apns", "recaptcha", "captcha"]
        if plain.hasSuffix("token") || ["jwt", "bearer", "authorization", "oauth"].contains(plain) {
            return harmless.contains(where: plain.contains) ? nil : .token
        }
        if ["apikey", "secretkey", "accesskey", "privatekey", "clientsecret", "appsecret", "apisecret", "secret"].contains(where: plain.hasSuffix) { return .key }
        if ["sid", "session", "sessionkey"].contains(plain) || ["sessionid", "sessid"].contains(where: plain.hasSuffix) { return .session }
        return nil
    }

    /// Whether `value` can be the secret its field is called after: not a flag, a blank or a placeholder.
    static func plausible(_ value: String, as kind: Kind) -> Bool {
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if ["true", "false", "null", "none", "undefined", "nil"].contains(text.lowercased()) { return false }
        switch kind {
        case .password: return text.count >= 4
        case .card: return isCard(text)
        case .email: return isEmail(text)
        default: return text.count >= 8
        }
    }

    // MARK: - By look

    private static let patterns: [(kind: Kind, look: NSRegularExpression)] = {
        let looks: [(Kind, String)] = [
            (.privateKey, #"-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----"#),
            (.key, #"AIza[0-9A-Za-z_-]{35}"#),
            (.key, #"(?:AKIA|ASIA)[0-9A-Z]{16}"#),
            (.key, #"[sr]k_live_[0-9A-Za-z]{16}"#),
            (.key, #"sk-(?:ant-|proj-)?[0-9A-Za-z_-]{32}"#),
            (.token, #"eyJ[0-9A-Za-z_-]{8,}\.eyJ[0-9A-Za-z_-]{8,}"#),
            (.token, #"gh[pousr]_[0-9A-Za-z]{30}"#),
            (.token, #"xox[abprs]-[0-9A-Za-z-]{10}"#),
            (.token, #"ya29\.[0-9A-Za-z_-]{20}"#),
            (.token, #"[0-9]{8,10}:AA[0-9A-Za-z_-]{30}"#),
        ]
        return looks.compactMap { kind, look in (try? NSRegularExpression(pattern: look)).map { (kind, $0) } }
    }()

    /// All of them at once: nearly every value is none of these, and a recording has tens of thousands of values.
    private static let anyLook = try? NSRegularExpression(pattern: patterns.map(\.look.pattern).joined(separator: "|"))
    private static let shortestLook = 15

    /// What `text` holds by its own look, whatever the field is called.
    static func kind(ofValue text: String) -> Kind? {
        guard text.utf8.count >= shortestLook else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        guard anyLook?.firstMatch(in: text, range: range) != nil else { return nil }
        return patterns.first { $0.look.firstMatch(in: text, range: range) != nil }?.kind
    }

    static func isEmail(_ text: String) -> Bool {
        let parts = text.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty, text.count <= 120, !text.contains(where: \.isWhitespace) else { return false }
        let labels = parts[1].split(separator: ".", omittingEmptySubsequences: false)
        return labels.count >= 2 && labels.allSatisfy { !$0.isEmpty } && (labels.last?.count ?? 0) >= 2 && labels.last!.allSatisfy(\.isLetter)
    }

    private static func isCard(_ text: String) -> Bool {
        let digits = text.compactMap(\.wholeNumberValue)
        guard (13...19).contains(digits.count), text.allSatisfy({ $0.isWholeNumber || $0 == " " || $0 == "-" }) else { return false }
        let sum = digits.reversed().enumerated().reduce(0) { total, item in
            let doubled = item.offset % 2 == 1 ? item.element * 2 : item.element
            return total + (doubled > 9 ? doubled - 9 : doubled)
        }
        return sum % 10 == 0
    }

    // MARK: - In a request

    /// A field's name as it may be shown: a name can itself be a token (`?eyJhbGci…`).
    static func safeName(_ name: String) -> String {
        if kind(ofValue: name) != nil || isEmail(name) || Endpoint.isIdentifier(name) || Endpoint.isNumber(name) { return Endpoint.placeholder }
        return AssistantDigest.clean(name, limit: 30)
    }

    static func field(_ name: String, _ value: String, at place: (String) -> Place) -> Find? {
        if let kind = kind(ofName: name), plausible(value, as: kind) { return Find(kind: kind, place: place(safeName(name))) }
        if let kind = kind(ofValue: value) { return Find(kind: kind, place: place(safeName(name))) }
        // `?eyJhbGci…`: the token is the name.
        if let kind = kind(ofValue: name) { return Find(kind: kind, place: place(safeName(name))) }
        return nil
    }

    /// Everything found in the request's address, headers and kept form or JSON fields. Cookies and the usual sign-in
    /// headers are included: whether they are a problem depends on the encryption, which the audit knows.
    static func inExchange(_ exchange: HttpExchange) -> [Find] {
        var found: [Find] = []
        func add(_ find: Find?) { if let find, !found.contains(find) { found.append(find) } }
        let target = exchange.target ?? exchange.path
        let path = String(target.prefix { $0 != "?" })
        if let kind = kind(ofValue: path.removingPercentEncoding ?? path) { add(Find(kind: kind, place: .path)) }
        for segment in path.split(separator: "/") where isEmail(segment.removingPercentEncoding ?? String(segment)) {
            add(Find(kind: .email, place: .path))
        }
        for item in exchange.query {
            add(field(item.name, item.value) { .query($0) })
            if isEmail(item.value) { add(Find(kind: .email, place: .query(safeName(item.name)))) }
        }
        // HTTP/2 sends the address as a header (`:path`): it was read above.
        for header in exchange.requestHeaders where !header.name.hasPrefix(":") {
            switch header.name.lowercased() {
            case "authorization", "proxy-authorization":
                let basic = header.value.lowercased().hasPrefix("basic ")
                if !header.value.isEmpty { add(Find(kind: basic ? .password : .token, place: .requestHeader(safeName(header.name)))) }
            case "cookie":
                if !header.value.isEmpty { add(Find(kind: .session, place: .requestHeader("Cookie"))) }
            case "referer":
                // The page before handed its own address on, secrets and all.
                if queryFields(header.value).contains(where: { field($0.name, $0.value, at: { .query($0) }) != nil }) {
                    add(Find(kind: .token, place: .requestHeader("Referer")))
                }
            case "if-none-match", "etag", "user-agent", "accept", "sec-websocket-key": break
            default: add(field(header.name, header.value) { .requestHeader($0) })
            }
        }
        for header in exchange.responseHeaders where header.name.caseInsensitiveCompare("Location") == .orderedSame {
            if queryFields(header.value).contains(where: { field($0.name, $0.value, at: { .query($0) }) != nil }) {
                add(Find(kind: .token, place: .responseHeader("Location")))
            }
        }
        for item in exchange.parameters { add(field(item.name, item.value) { .requestBody($0) }) }
        for message in exchange.messages.prefix(50) {
            if let preview = message.preview, kind(ofValue: preview) != nil || jsonFinds(Data(preview.utf8), { _ in .message }).first != nil {
                add(Find(kind: kind(ofValue: preview) ?? .token, place: .message))
            }
        }
        return found
    }

    /// Secrets in a stored body. Fields of JSON and of a form by name; anything else by its look.
    static func inBody(_ body: Data, contentType: String?, request: Bool) -> [Find] {
        let data = body.prefix(bodyLimit)
        let type = contentType?.lowercased() ?? ""
        if type.hasPrefix("image/") || type.hasPrefix("video/") || type.hasPrefix("audio/") || type.hasPrefix("font/") { return [] }
        let place: (String) -> Place = { request ? .requestBody($0) : .responseBody($0) }
        var found: [Find] = []
        func add(_ find: Find) { if !found.contains(find) { found.append(find) } }
        if type.contains("x-www-form-urlencoded") {
            for item in formFields([UInt8](data)) { if let find = field(item.name, item.value, at: place) { add(find) } }
        } else {
            jsonFinds(data, place).forEach(add)
        }
        if found.isEmpty, let text = String(data: data, encoding: .utf8), let kind = kind(ofValue: text) { add(Find(kind: kind, place: place(""))) }
        return found
    }

    private static func jsonFinds(_ data: Data, _ place: (String) -> Place) -> [Find] {
        guard let first = data.first(where: { $0 > 32 }), first == UInt8(ascii: "{") || first == UInt8(ascii: "["),
              let json = try? JSONSerialization.jsonObject(with: data) else { return [] }
        var found: [Find] = []
        var left = 2000
        func walk(_ value: Any, depth: Int) {
            guard depth < 8, left > 0 else { return }
            if let object = value as? [String: Any] {
                for (name, inner) in object.sorted(by: { $0.key < $1.key }) {
                    left -= 1
                    let text = inner as? String ?? (inner as? NSNumber).map(\.stringValue)
                    if let text {
                        if let find = field(name, text, at: place), !found.contains(find) { found.append(find) }
                    } else {
                        walk(inner, depth: depth + 1)
                    }
                }
            } else if let list = value as? [Any] {
                for inner in list.prefix(200) { walk(inner, depth: depth + 1) }
            }
        }
        walk(json, depth: 0)
        return found
    }
}
