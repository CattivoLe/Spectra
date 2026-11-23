import Foundation

/// One thing the rules noticed about a request, an endpoint or a connection.
nonisolated struct TrafficIssue: Equatable, Sendable {
    enum Kind: String, CaseIterable, Sendable {
        case secretUnencrypted, secretInAddress, privateKey, personal, cleartext, downgrade
        case serverError, clientError, noAnswer, slow, large, repeated
        case uncompressed, uncached, sharedCache, cookie, tracker, refused, mapped
    }

    var kind: Kind
    var risk: CardRisk
    /// What the card shows: kinds, places and numbers, never a value from the request.
    var text: String

    var finding: CardFinding { CardFinding(risk: risk, text: text) }
}

/// The rules behind the ✨ cards of recorded requests: what is wrong is found here, without the model, which only
/// puts it into words. Nothing that comes out of here holds a token, a cookie, a parameter's value or a body.
/// Port of Android `TrafficAudit`; checked by `TrafficAuditTests` with the same cases.
nonisolated enum TrafficAudit {
    static let slowMs: Int64 = 2000
    static let slowTypicalMs: Int64 = 1000
    static let slowWorstMs: Int64 = 5000
    static let largeBytes: Int64 = 1_048_576
    static let compressibleBytes: Int64 = 10 * 1024
    static let repeatWindowMs: Int64 = 10_000
    static let repeatCount = 3
    static let manyRepeats = 5
    /// Of an endpoint's requests, how many of the newest have their bodies searched.
    static let bodiesRead = 5

    /// Reads a stored body: the request it belongs to and whether it is the request's own.
    typealias Bodies = @Sendable (_ hit: RequestHit, _ request: Bool) -> Data?

    /// A request with the secrets found in it.
    struct Scan: Sendable {
        let hit: RequestHit
        let secrets: [TrafficSecrets.Find]
        var exchange: HttpExchange { hit.exchange }
        var cleartext: Bool { TrafficAudit.isCleartext(hit) }
    }

    /// Looks for secrets in each request, newest first; the bodies of the first `bodiesRead` are read too.
    static func scan(_ hits: [RequestHit], bodies: Bodies? = nil) -> [Scan] {
        hits.enumerated().map { number, hit in
            var found = TrafficSecrets.inExchange(hit.exchange)
            if let bodies, number < bodiesRead {
                for request in [true, false] where request ? hit.exchange.requestBodyStored : hit.exchange.responseBodyStored {
                    guard let body = bodies(hit, request) else { continue }
                    let more = TrafficSecrets.inBody(body, contentType: hit.exchange.header("Content-Type", response: !request), request: request)
                    found += more.filter { !found.contains($0) }
                }
            }
            return Scan(hit: hit, secrets: found)
        }
    }

    // MARK: - What a request is

    static func isCleartext(_ hit: RequestHit) -> Bool {
        !hit.flow.tls && hit.exchange.header(":scheme", response: false) != "https"
    }

    /// Certificate status checks and sign-in page probes go over plain HTTP on purpose.
    static func isPlainByDesign(_ host: String) -> Bool {
        ["ocsp.", "ocsp2.", "crl.", "crl3.", "crl4.", "connectivitycheck.", "detectportal."].contains(where: host.hasPrefix)
            || ["captive.apple.com", "www.msftconnecttest.com", "cp.cloudflare.com"].contains(host)
            || host.hasSuffix(".ocsp.sectigo.com") || host.hasSuffix(".lencr.org")
    }

    /// Answered by a Map Local rule: it says nothing about the server.
    static func isMapped(_ exchange: HttpExchange) -> Bool { exchange.header(MapLocal.header, response: true) != nil }

    static func contentType(_ exchange: HttpExchange, response: Bool = true) -> String? {
        exchange.header("Content-Type", response: response)?.split(separator: ";").first
            .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
    }

    private static func isText(_ type: String?) -> Bool {
        guard let type else { return false }
        return type.hasPrefix("text/") || ["json", "xml", "javascript", "svg", "x-www-form-urlencoded", "graphql"].contains(where: type.contains)
    }

    private static func isStatic(_ type: String?) -> Bool {
        guard let type else { return false }
        return type.hasPrefix("image/") || type.hasPrefix("font/") || type.contains("css") || type.contains("javascript") || type.contains("font")
    }

    /// The app asked for a compressed answer and the server did not give one. The recorder passes the request on as
    /// the app sent it, so this is the server's doing.
    static func isUncompressed(_ exchange: HttpExchange) -> Bool {
        guard let status = exchange.status, (200..<300).contains(status), !isMapped(exchange), !exchange.isStream,
              isText(contentType(exchange)), (exchange.responseBodyBytes ?? 0) >= compressibleBytes else { return false }
        let encoding = exchange.header("Content-Encoding", response: true)?.lowercased() ?? ""
        let accepts = exchange.header("Accept-Encoding", response: false)?.lowercased() ?? ""
        return (encoding.isEmpty || encoding == "identity") && ["gzip", "br", "deflate", "zstd"].contains(where: accepts.contains)
    }

    /// An answer that says nothing about how long it may be kept.
    static func saysNothingAboutCaching(_ exchange: HttpExchange) -> Bool {
        exchange.method.uppercased() == "GET" && exchange.status == 200 && !exchange.isStream && !isMapped(exchange)
            && ["Cache-Control", "Expires", "ETag", "Last-Modified", "Pragma"].allSatisfy { exchange.header($0, response: true) == nil }
    }

    /// An answer made for one person that any cache on the way may keep and hand to the next.
    static func isPersonalInSharedCache(_ exchange: HttpExchange) -> Bool {
        let control = exchange.header("Cache-Control", response: true)?.lowercased() ?? ""
        guard control.contains("public") || control.contains("s-maxage"), !control.contains("no-store"), !control.contains("private") else { return false }
        return exchange.header("Set-Cookie", response: true) != nil || exchange.header("Authorization", response: false) != nil
    }

    static func redirectsToPlain(_ hit: RequestHit) -> Bool {
        guard !isCleartext(hit), let status = hit.exchange.status, (300..<400).contains(status) else { return false }
        return hit.exchange.header("Location", response: true)?.lowercased().hasPrefix("http://") == true
    }

    static func noAnswer(_ hit: RequestHit) -> Bool { hit.exchange.status == nil && hit.flow.closed && !hit.exchange.isStream }

    /// A cookie the answer sets with a flag missing that protects it.
    struct WeakCookie: Hashable, Sendable {
        var name: String
        var missing: [String]
        /// Its name says it keeps somebody signed in.
        var signsIn: Bool
    }

    static func weakCookies(_ hit: RequestHit) -> [WeakCookie] {
        hit.exchange.responseHeaders.filter { $0.name.caseInsensitiveCompare("Set-Cookie") == .orderedSame }.compactMap { header in
            let parts = header.value.split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces) }
            guard let first = parts.first, let equals = first.firstIndex(of: "="), first.index(after: equals) != first.endIndex else { return nil }
            let name = String(first[..<equals])
            let flags = parts.dropFirst().map { $0.lowercased().replacingOccurrences(of: " ", with: "") }
            // Setting it to have expired is how a server deletes one.
            if flags.contains("max-age=0") || flags.contains(where: { $0.hasPrefix("expires=thu,01jan1970") || $0.hasPrefix("expires=thu,01-jan-1970") }) { return nil }
            let lower = name.lowercased()
            let signsIn = TrafficSecrets.kind(ofName: name) != nil || ["sess", "auth", "token", "login", "sid"].contains(where: lower.contains)
                && !lower.contains("csrf") && !lower.contains("xsrf")
            var missing: [String] = []
            if !flags.contains("secure") { missing.append("Secure") }
            if signsIn && !flags.contains("httponly") { missing.append("HttpOnly") }
            return missing.isEmpty ? nil : WeakCookie(name: TrafficSecrets.safeName(name), missing: missing, signsIn: signsIn)
        }
    }

    /// How often the very same request went out: in all, and at most within `repeatWindowMs`. Only requests that ask
    /// for something: the same POST twice may carry different bodies.
    static func repeats(_ hits: [RequestHit]) -> (total: Int, burst: Int) {
        var byTarget: [String: [Int64]] = [:]
        for hit in hits where ["GET", "HEAD"].contains(hit.exchange.method.uppercased()) && !hit.exchange.isWebSocket {
            byTarget[Endpoint.of(hit.flow, hit.exchange).host + (hit.exchange.target ?? hit.exchange.path), default: []].append(hit.at)
        }
        var total = 0, burst = 0
        for times in byTarget.values {
            let sorted = times.sorted()
            total = max(total, sorted.count)
            var start = 0
            for end in sorted.indices {
                while sorted[end] - sorted[start] > repeatWindowMs { start += 1 }
                burst = max(burst, end - start + 1)
            }
        }
        return (total, burst)
    }

    // MARK: - Issues

    /// What is wrong with `scans`: one request, or all those of an endpoint or a connection. `same`: the requests
    /// among which repeats are counted, when that is more than `scans`.
    static func issues(_ scans: [Scan], same: [RequestHit]? = nil) -> [TrafficIssue] {
        guard !scans.isEmpty else { return [] }
        let hits = scans.map(\.hit), one = scans.count == 1
        var found: [TrafficIssue] = []
        func add(_ kind: TrafficIssue.Kind, _ risk: CardRisk, _ text: String) { found.append(.init(kind: kind, risk: risk, text: text)) }
        func share(_ count: Int) -> String { one || count == scans.count ? "" : " " + String(localized: "(\(count) of \(scans.count) requests)") }

        secrets(scans, share: share, add: add)
        errors(hits, one: one, add: add)
        speed(hits, one: one, add: add)

        let large = hits.filter(isLarge)
        if let biggest = large.map({ $0.exchange.responseBodyBytes ?? 0 }).max() {
            let text = large.contains { isText(contentType($0.exchange)) }
            add(.large, text ? .warning : .info, (one ? String(localized: "Large answer: \(size(biggest)).")
                                                      : String(localized: "Large answers: up to \(size(biggest)).")) + share(large.count))
        }

        let (total, burst) = repeats(same ?? hits)
        if burst >= repeatCount {
            add(.repeated, burst >= manyRepeats ? .warning : .info,
                String(localized: "The same request went out \(burst) times within \(repeatWindowMs / 1000) s: repeats nobody needed, or a retry loop."))
        }

        let uncompressed = hits.filter { isUncompressed($0.exchange) }
        if let most = uncompressed.compactMap(\.exchange.responseBodyBytes).max() {
            add(.uncompressed, .info, String(localized: "Came uncompressed although the app accepts compression: \(size(most)) of text.") + share(uncompressed.count))
        }
        let uncached = hits.filter { saysNothingAboutCaching($0.exchange) && (isStatic(contentType($0.exchange)) || total >= repeatCount) }
        if !uncached.isEmpty {
            add(.uncached, .info, String(localized: "The answer says nothing about caching, so it is downloaded again every time.") + share(uncached.count))
        }
        let shared = hits.filter { isPersonalInSharedCache($0.exchange) }
        if !shared.isEmpty {
            add(.sharedCache, .warning, String(localized: "A personal answer may be kept by any cache on the way (Cache-Control: public).") + share(shared.count))
        }
        let cookies = AssistantCards.unique(hits.flatMap(weakCookies), by: \.self)
        if !cookies.isEmpty {
            let list = cookies.prefix(3).map { "“\($0.name)” (\(String(localized: "without")) \($0.missing.joined(separator: ", ")))" }.joined(separator: ", ")
            add(.cookie, cookies.contains(where: \.signsIn) ? .warning : .info, String(localized: "Cookies set with weak protection: \(list)."))
        }
        let trackers = AssistantCards.unique(hits.compactMap { hit in
            let host = Endpoint.of(hit.flow, hit.exchange).host
            return TrackerHosts.service(of: host).map { (host: host, service: $0) }
        }, by: \.service.name)
        if let first = trackers.first {
            let names = trackers.prefix(3).map { "\($0.service.name) (\(kindName($0.service.kind)))" }.joined(separator: ", ")
            add(.tracker, .info, trackers.count == 1 ? String(localized: "\(AssistantDigest.clean(first.host)) belongs to \(names): it collects data about the app's use.")
                                                     : String(localized: "Services that collect data about the app's use: \(names)."))
        }
        let mapped = hits.filter { isMapped($0.exchange) }
        if !mapped.isEmpty { add(.mapped, .info, String(localized: "Answered by this phone's own Map Local rule, not by the server.") + share(mapped.count)) }
        return found
    }

    private static func secrets(_ scans: [Scan], share: (Int) -> String, add: (TrafficIssue.Kind, CardRisk, String) -> Void) {
        let open = scans.filter(\.cleartext)
        let bare = AssistantCards.unique(open.flatMap(\.secrets), by: \.self)
        let exposed = open.filter { !$0.secrets.isEmpty }.count
        if !bare.isEmpty {
            add(.secretUnencrypted, .critical,
                String(localized: "Sent unencrypted, readable by anyone on the way: \(list(bare)). Values are hidden here.") + share(exposed))
        } else {
            let plain = open.filter { !isPlainByDesign(Endpoint.of($0.hit.flow, $0.exchange).host) }
            if !plain.isEmpty {
                add(.cleartext, .warning, String(localized: "Unencrypted HTTP: anyone on the way can read and change it.") + share(plain.count))
            } else if !open.isEmpty {
                add(.cleartext, .info, String(localized: "Unencrypted HTTP, as usual for this kind of service check.") + share(open.count))
            }
        }
        let closed = scans.filter { !$0.cleartext }
        func passedOn(_ find: TrafficSecrets.Find) -> Bool {
            find.place.inAddress || find.place == .requestHeader("Referer") || find.place == .responseHeader("Location")
        }
        let address = AssistantCards.unique(closed.flatMap { $0.secrets.filter { passedOn($0) && $0.kind != .email } }, by: \.self)
        if !address.isEmpty {
            let count = closed.filter { $0.secrets.contains { passedOn($0) && $0.kind != .email } }.count
            add(.secretInAddress, .warning,
                String(localized: "Secret in the address: \(list(address)). Addresses stay in server logs and histories. The value is hidden here.") + share(count))
        }
        let emails = AssistantCards.unique(closed.flatMap { $0.secrets.filter { $0.kind == .email && $0.place.inAddress } }, by: \.self)
        if !emails.isEmpty {
            let count = closed.filter { $0.secrets.contains { $0.kind == .email && $0.place.inAddress } }.count
            add(.personal, .info, String(localized: "Personal data in the address: \(list(emails)).") + share(count))
        }
        let keys = AssistantCards.unique(closed.flatMap { $0.secrets.filter { find in
            guard case .responseBody = find.place else { return false }
            return find.kind == .privateKey
        } }, by: \.self)
        if !keys.isEmpty { add(.privateKey, .warning, String(localized: "The answer carries a private key. The value is hidden here.")) }
        let downgraded = scans.filter { redirectsToPlain($0.hit) }
        if !downgraded.isEmpty { add(.downgrade, .warning, String(localized: "Redirects from HTTPS to unencrypted HTTP.") + share(downgraded.count)) }
    }

    private static func errors(_ hits: [RequestHit], one: Bool, add: (TrafficIssue.Kind, CardRisk, String) -> Void) {
        let statuses = hits.compactMap(\.exchange.status)
        let server = statuses.filter { $0 >= 500 }, client = statuses.filter { (400..<500).contains($0) }
        let silent = hits.filter(noAnswer)
        if one {
            if let status = server.first { add(.serverError, .warning, String(localized: "The server failed: status \(status).")) }
            if let status = client.first { add(.clientError, .warning, String(localized: "Refused: status \(status), \(meaning(status)).")) }
            if !silent.isEmpty { add(.noAnswer, .warning, String(localized: "No answer: the connection closed before one came.")) }
            return
        }
        let failed = server.count + client.count
        if failed > 0 {
            let percent = max(1, Int((Double(failed) / Double(hits.count) * 100).rounded()))
            add(server.isEmpty ? .clientError : .serverError, !server.isEmpty || percent >= 5 ? .warning : .info,
                String(localized: "Errors: \(failed) of \(hits.count) requests (\(percent)%): \(tally(server + client))."))
        }
        if !silent.isEmpty { add(.noAnswer, .warning, String(localized: "No answer: \(silent.count) of \(hits.count) requests.")) }
    }

    private static func speed(_ hits: [RequestHit], one: Bool, add: (TrafficIssue.Kind, CardRisk, String) -> Void) {
        let timed = hits.filter { !$0.exchange.isStream && !isMapped($0.exchange) }
        let times = timed.compactMap(\.exchange.durationMs).sorted()
        guard let worst = times.last, let typical = EndpointGroup.median(times) else { return }
        if one {
            guard worst >= slowMs else { return }
            if let wait = timed.first?.exchange.waitMs {
                add(.slow, .warning, String(localized: "Slow: \(duration(worst)), \(duration(wait)) of it waiting for the server."))
            } else {
                add(.slow, .warning, String(localized: "Slow: \(duration(worst))."))
            }
        } else if typical >= slowTypicalMs {
            add(.slow, .warning, String(localized: "Slow: usually \(duration(typical)), at worst \(duration(worst))."))
        } else if worst >= slowWorstMs {
            add(.slow, .info, String(localized: "Usually \(duration(typical)), but once it took \(duration(worst))."))
        }
    }

    private static func isLarge(_ hit: RequestHit) -> Bool {
        let type = contentType(hit.exchange)
        return (hit.exchange.responseBodyBytes ?? 0) >= largeBytes && !hit.exchange.isStream && !isMapped(hit.exchange)
            && (isText(type) || type?.hasPrefix("image/") == true)
    }

    // MARK: - Words

    /// `token — parameter “access_token”, password — header “Authorization”`: kinds and places, never values.
    static func list(_ finds: [TrafficSecrets.Find], limit: Int = 3) -> String {
        let named = finds.prefix(limit).map { "\(name($0.kind)) — \(name($0.place))" }.joined(separator: ", ")
        return finds.count > limit ? named + " " + String(localized: "and \(finds.count - limit) more") : named
    }

    /// The same in English, for the model.
    static func englishList(_ finds: [TrafficSecrets.Find], limit: Int = 4) -> String {
        finds.prefix(limit).map { "\(englishName($0.kind)) in \(englishName($0.place))" }.joined(separator: "; ")
    }

    static func name(_ kind: TrafficSecrets.Kind) -> String {
        switch kind {
        case .password: String(localized: "password")
        case .token: String(localized: "access token")
        case .key: String(localized: "secret key")
        case .session: String(localized: "session cookie or identifier")
        case .card: String(localized: "card number")
        case .privateKey: String(localized: "private key")
        case .email: String(localized: "e-mail address")
        }
    }

    static func name(_ place: TrafficSecrets.Place) -> String {
        switch place {
        case .path: String(localized: "the path")
        case .query(let name): String(localized: "parameter “\(name)”")
        case .requestHeader(let name): String(localized: "header “\(name)”")
        case .responseHeader(let name): String(localized: "header “\(name)” of the answer")
        case .requestBody(let name): name.isEmpty ? String(localized: "the request body") : String(localized: "body field “\(name)”")
        case .responseBody(let name): name.isEmpty ? String(localized: "the answer's body") : String(localized: "field “\(name)” of the answer")
        case .message: String(localized: "a WebSocket message")
        }
    }

    private static func englishName(_ kind: TrafficSecrets.Kind) -> String {
        switch kind {
        case .password: "password"
        case .token: "access token"
        case .key: "secret key"
        case .session: "session cookie or identifier"
        case .card: "card number"
        case .privateKey: "private key"
        case .email: "e-mail address"
        }
    }

    private static func englishName(_ place: TrafficSecrets.Place) -> String {
        switch place {
        case .path: "the URL path"
        case .query(let name): "the URL parameter `\(name)`"
        case .requestHeader(let name): "the request header `\(name)`"
        case .responseHeader(let name): "the response header `\(name)`"
        case .requestBody(let name): name.isEmpty ? "the request body" : "the request body field `\(name)`"
        case .responseBody(let name): name.isEmpty ? "the response body" : "the response body field `\(name)`"
        case .message: "a WebSocket message"
        }
    }

    static func kindName(_ kind: TrackerHosts.Kind) -> String {
        switch kind {
        case .analytics: String(localized: "analytics")
        case .ads: String(localized: "advertising")
        case .attribution: String(localized: "install tracking")
        case .crash: String(localized: "crash reports")
        case .push: String(localized: "push messages")
        }
    }

    static func englishKind(_ kind: TrackerHosts.Kind) -> String {
        switch kind {
        case .analytics: "analytics"
        case .ads: "advertising"
        case .attribution: "install tracking"
        case .crash: "crash reports"
        case .push: "push messages"
        }
    }

    /// What a 4xx status says, in a word.
    static func meaning(_ status: Int) -> String {
        switch status {
        case 400, 422: String(localized: "the request itself is wrong")
        case 401: String(localized: "not signed in, or the sign-in has expired")
        case 403: String(localized: "no permission")
        case 404, 410: String(localized: "there is nothing at this address")
        case 408: String(localized: "the request took too long to arrive")
        case 429: String(localized: "too many requests")
        default: String(localized: "the server did not accept it")
        }
    }

    /// `429 ×10, 500 ×2`, the most frequent first.
    static func tally(_ statuses: [Int], limit: Int = 4) -> String {
        Dictionary(grouping: statuses, by: \.self).map { ($0.key, $0.value.count) }
            .sorted { ($1.1, $0.0) < ($0.1, $1.0) }.prefix(limit).map { "\($0.0) ×\($0.1)" }.joined(separator: ", ")
    }

    static func duration(_ ms: Int64) -> String {
        ms < 1000 ? String(localized: "\(ms) ms") : String(localized: "\(Double(ms) / 1000, specifier: "%.2f") s")
    }

    static func size(_ bytes: Int64) -> String { ByteCountFormatter.string(fromByteCount: bytes, countStyle: .binary) }
}
