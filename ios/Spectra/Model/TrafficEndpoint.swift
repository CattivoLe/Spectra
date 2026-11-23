import Foundation
import os

/// What a request asks for, whoever it is about: the method, the server and the path without its query, with the
/// changing parts (numbers, UUIDs, long tokens) written as `{id}`. Two requests with the same one are the same call.
/// Port of Android `Endpoint`: both follow one rule, checked by the same table of paths in the tests.
nonisolated struct Endpoint: Hashable, Sendable {
    let method: String
    let host: String
    let path: String

    static let placeholder = "{id}"

    /// `GET api.example.com/v1/users/{id}`.
    var key: String { "\(method) \(host)\(path)" }

    static func of(_ flow: TrafficFlow, _ exchange: HttpExchange) -> Endpoint {
        let method = exchange.method.uppercased()
        var target = exchange.target ?? exchange.path
        var authority = exchange.header(":authority", response: false) ?? exchange.header("Host", response: false)
        if method == "CONNECT" {
            authority = target
            target = ""
        } else if let scheme = target.range(of: "://"), target.hasPrefix("http") {
            // A request to a proxy names the whole address.
            let rest = target[scheme.upperBound...]
            let slash = rest.firstIndex { $0 == "/" || $0 == "?" } ?? rest.endIndex
            authority = String(rest[..<slash])
            target = String(rest[slash...])
        }
        // One HTTP/2 connection can carry requests to several names, so the request's own name comes first.
        let name = authority.map(cleanHost).flatMap { $0.isEmpty ? nil : $0 }
            ?? cleanHost(flow.host?.isEmpty == false ? flow.host! : flow.destination)
        return Endpoint(method: method, host: name, path: method == "CONNECT" ? "" : template(target))
    }

    private static func cleanHost(_ text: String) -> String {
        var host = text.trimmingCharacters(in: .whitespaces).lowercased()
        if let at = host.lastIndex(of: "@") { host = String(host[host.index(after: at)...]) }
        for port in [":443", ":80"] where host.hasSuffix(port) && host.filter({ $0 == ":" }).count == 1 || host.hasSuffix("]" + port) {
            host.removeLast(port.count)
            break
        }
        while host.hasSuffix(".") { host.removeLast() }
        return host
    }

    // MARK: - The path

    private static let cache = OSAllocatedUnfairLock<[String: String]>(initialState: [:])
    private static let cacheLimit = 20_000

    /// The path of a request target with its changing parts as `{id}`. Remembered: a live list asks again and again.
    static func template(_ target: String) -> String {
        if let known = cache.withLock({ $0[target] }) { return known }
        let made = build(target)
        cache.withLock {
            if $0.count >= cacheLimit { $0.removeAll() }
            $0[target] = made
        }
        return made
    }

    private static func build(_ target: String) -> String {
        let path = target.prefix { $0 != "?" && $0 != "#" }
        let segments = path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        if segments.isEmpty { return "/" }
        var out = ""
        var previous: String?
        for (index, raw) in segments.enumerated() {
            let segment = readable(String(raw.prefix { $0 != ";" }))
            out += "/" + masked(segment, after: previous, last: index == segments.count - 1)
            previous = segment
        }
        return out
    }

    /// A segment as it reads, unless decoding would change where the path splits.
    private static func readable(_ raw: String) -> String {
        guard raw.contains("%"), let decoded = raw.removingPercentEncoding,
              !decoded.unicodeScalars.contains(where: { $0 == "/" || $0 == "?" || $0 == "#" || $0.value < 32 }) else { return raw }
        return decoded
    }

    private static func masked(_ segment: String, after previous: String?, last: Bool) -> String {
        if isNumber(segment) {
            // `/2/tweets`, `/api/3/movie`: a version, not somebody's number.
            let version = segment.utf8.count <= 2 && !last
                && (previous == nil || ["api", "rest", "v", "ver", "version"].contains(previous!.lowercased()))
            return version ? segment : placeholder
        }
        if let whole = variable(segment) { return whole }
        // `8f3a…e1.png`: the name changes, the kind of file does not.
        if let dot = segment.lastIndex(of: "."), dot != segment.startIndex {
            let ext = segment[segment.index(after: dot)...]
            let base = String(segment[..<dot])
            if (1...5).contains(ext.utf8.count), ext.utf8.allSatisfy(isAlphanumeric), ext.utf8.contains(where: isLetter),
               let changed = isNumber(base) ? placeholder : variable(base) {
                return changed + "." + ext
            }
        }
        return segment
    }

    /// A segment that names one thing among many, with the part that changes as `{id}`; nil for an ordinary word.
    private static func variable(_ text: String) -> String? {
        if isIdentifier(text) { return placeholder }
        // `user-<uuid>`, `order_100482`: a short word, then what changes.
        let bytes = Array(text.utf8)
        if let cut = bytes.firstIndex(where: { !isLetter($0) }), (2...12).contains(cut), [UInt8(ascii: "-"), UInt8(ascii: "_"), UInt8(ascii: ":")].contains(bytes[cut]) {
            let rest = String(decoding: bytes[(cut + 1)...], as: UTF8.self)
            if (isNumber(rest) && rest.utf8.count >= 5) || isIdentifier(rest) {
                return String(decoding: bytes[...cut], as: UTF8.self) + placeholder
            }
        }
        // Also for what must never be shown: a key or an e-mail address in the path.
        if text.utf8.count >= 16, TrafficSecrets.kind(ofValue: text) != nil { return placeholder }
        if TrafficSecrets.isEmail(text) { return placeholder }
        return nil
    }

    /// A UUID, a long hex number, a list or chain of numbers (dates, coordinates), or a long random-looking token.
    static func isIdentifier(_ text: String) -> Bool {
        isUUID(text) || isHex(text) || isNumbers(text) || isToken(text)
    }

    static func isNumber(_ text: String) -> Bool {
        var bytes = Array(text.utf8)
        if let first = bytes.first, first == UInt8(ascii: "-") || first == UInt8(ascii: "+") { bytes.removeFirst() }
        return !bytes.isEmpty && bytes.allSatisfy(isDigit)
    }

    /// `12,15,18`, `2026-11-23`, `55.7558,37.6173`: digits and separators only. Short ones (`1.1`, `2.0.1`) are versions.
    private static func isNumbers(_ text: String) -> Bool {
        let bytes = Array(text.utf8)
        guard bytes.allSatisfy({ isDigit($0) || ".,:+-_".utf8.contains($0) }) else { return false }
        let digits = bytes.count(where: isDigit)
        if digits >= 8 { return true }
        let parts = text.split(separator: ",", omittingEmptySubsequences: false)
        return parts.count >= 2 && parts.allSatisfy { isNumber(String($0)) }
    }

    private static func isUUID(_ text: String) -> Bool {
        var bytes = Array(text.utf8)
        if bytes.count == 38, bytes.first == UInt8(ascii: "{"), bytes.last == UInt8(ascii: "}") { bytes = Array(bytes.dropFirst().dropLast()) }
        guard bytes.count == 36 else { return false }
        return bytes.enumerated().allSatisfy { index, c in
            [8, 13, 18, 23].contains(index) ? c == UInt8(ascii: "-") : isHexDigit(c)
        }
    }

    private static func isHex(_ text: String) -> Bool {
        let bytes = Array((text.hasPrefix("0x") || text.hasPrefix("0X") ? String(text.dropFirst(2)) : text).utf8)
        return bytes.count >= 12 && bytes.allSatisfy(isHexDigit) && bytes.contains(where: isDigit)
    }

    /// Twenty characters or more in which letters and digits keep changing places: words and slugs do not.
    private static func isToken(_ text: String) -> Bool {
        let bytes = Array(text.utf8)
        guard bytes.count >= 20 else { return false }
        if text.hasPrefix("eyJ"), bytes.allSatisfy({ isAlphanumeric($0) || "._-=".utf8.contains($0) }) { return true }
        guard bytes.allSatisfy({ isAlphanumeric($0) || "_-~=+".utf8.contains($0) }) else { return false }
        func kind(_ c: UInt8) -> Int {
            if isDigit(c) { return 1 }
            if c >= UInt8(ascii: "a") && c <= UInt8(ascii: "z") { return 2 }
            if c >= UInt8(ascii: "A") && c <= UInt8(ascii: "Z") { return 3 }
            return 0
        }
        var changes = 0, digitRuns = 0, before = 0
        for c in bytes {
            let now = kind(c)
            if now == 1 && before != 1 { digitRuns += 1 }
            if now != 0 && before != 0 && now != before { changes += 1 }
            before = now
        }
        return digitRuns >= 2 && changes * 4 >= bytes.count
    }

    private static func isDigit(_ c: UInt8) -> Bool { c >= UInt8(ascii: "0") && c <= UInt8(ascii: "9") }
    private static func isLetter(_ c: UInt8) -> Bool {
        (c >= UInt8(ascii: "a") && c <= UInt8(ascii: "z")) || (c >= UInt8(ascii: "A") && c <= UInt8(ascii: "Z"))
    }
    private static func isAlphanumeric(_ c: UInt8) -> Bool { isDigit(c) || isLetter(c) }
    private static func isHexDigit(_ c: UInt8) -> Bool {
        isDigit(c) || (c >= UInt8(ascii: "a") && c <= UInt8(ascii: "f")) || (c >= UInt8(ascii: "A") && c <= UInt8(ascii: "F"))
    }
}

/// One HTTP request of a connection, as a row of its own.
nonisolated struct RequestHit: Identifiable, Sendable {
    let flow: TrafficFlow
    let index: Int
    var id: String { "\(flow.id)-\(index)" }
    var exchange: HttpExchange { flow.exchanges[index] }
    var at: Int64 { exchange.startedAt ?? flow.lastAt }

    static func all(_ flows: [TrafficFlow]) -> [RequestHit] {
        flows.flatMap { flow in flow.exchanges.indices.map { RequestHit(flow: flow, index: $0) } }
    }
}

extension HttpExchange {
    /// A connection that stays open and talks both ways: how long it lasted says nothing about speed.
    nonisolated var isWebSocket: Bool { status == 101 || !messages.isEmpty }

    /// An answer that is meant to last: a WebSocket, server-sent events, sound or video.
    nonisolated var isStream: Bool {
        if isWebSocket || method.uppercased() == "CONNECT" { return true }
        let type = header("Content-Type", response: true)?.lowercased() ?? ""
        return type.hasPrefix("text/event-stream") || type.hasPrefix("video/") || type.hasPrefix("audio/")
    }
}

/// What a list of connections held at one moment: when it is the same, nothing in the list has changed. Cheap to
/// compare, unlike the list itself.
nonisolated struct TrafficMark: Equatable, Sendable {
    var connections = 0, closed = 0, requests = 0
    var lastAt: Int64 = 0, bytes: Int64 = 0

    init(_ flows: [TrafficFlow] = []) {
        connections = flows.count
        for flow in flows {
            if flow.closed { closed += 1 }
            lastAt = max(lastAt, flow.lastAt)
            bytes += flow.sent + flow.received
            requests += flow.exchanges.count
        }
    }
}

/// The requests of one endpoint in a recording. Port of Android `EndpointGroup`.
nonisolated struct EndpointGroup: Identifiable, Sendable {
    let endpoint: Endpoint
    /// Every request of it, newest first: what the numbers count.
    let hits: [RequestHit]
    /// Those that pass the list's filter, newest first: the rows under it.
    let shown: [RequestHit]
    let errors: Int
    /// The middle duration and the longest one, in milliseconds. Streams are left out of both.
    let typicalMs: Int64?
    let worstMs: Int64?
    let bytes: Int64
    let lastAt: Int64
    let firstAt: Int64
    let webSocket: Bool
    let http2: Bool

    var id: String { endpoint.key }

    init(endpoint: Endpoint, hits unordered: [RequestHit], shown isShown: (RequestHit) -> Bool = { _ in true }) {
        self.endpoint = endpoint
        hits = unordered.sorted { $0.at > $1.at }
        shown = hits.filter(isShown)
        errors = hits.count { RequestFilter.failed($0.exchange, of: $0.flow) }
        let times = hits.compactMap { $0.exchange.isStream ? nil : $0.exchange.durationMs }.sorted()
        typicalMs = Self.median(times)
        worstMs = times.last
        bytes = hits.reduce(0) { $0 + ($1.exchange.requestSize ?? 0) + ($1.exchange.responseSize ?? 0) }
        lastAt = hits.first?.at ?? 0
        firstAt = hits.last?.at ?? 0
        webSocket = hits.contains { $0.exchange.isWebSocket }
        http2 = hits.contains { $0.exchange.httpVersion == "HTTP/2" }
    }

    /// The middle value of a sorted list; of two middle ones, the lower.
    static func median(_ sorted: [Int64]) -> Int64? {
        sorted.isEmpty ? nil : sorted[(sorted.count - 1) / 2]
    }

    /// The statuses its answers had, the most frequent first; 0 stands for no answer on a connection that is over.
    var statuses: [(status: Int, count: Int)] {
        var counts: [Int: Int] = [:]
        for hit in hits {
            if let status = hit.exchange.status { counts[status, default: 0] += 1 }
            else if hit.flow.closed { counts[0, default: 0] += 1 }
        }
        return counts.map { ($0.key, $0.value) }.sorted { ($1.count, $0.status) < ($0.count, $1.status) }
    }

    /// The endpoints of `flows` with a request the filter lets through: the most requested first, then by name, so
    /// that a live list does not jump about with every request.
    static func build(_ flows: [TrafficFlow], filter: RequestFilter = RequestFilter()) -> [EndpointGroup] {
        var order: [Endpoint: [RequestHit]] = [:]
        for hit in RequestHit.all(flows) where !hit.exchange.method.isEmpty {
            order[Endpoint.of(hit.flow, hit.exchange), default: []].append(hit)
        }
        return order.map { endpoint, hits in
            EndpointGroup(endpoint: endpoint, hits: hits) { filter.passes($0.exchange, of: $0.flow) }
        }
        .filter { !$0.shown.isEmpty }
        .sorted { ($1.hits.count, $0.endpoint.key) < ($0.hits.count, $1.endpoint.key) }
    }

    /// One endpoint of `flows`, whole.
    static func find(_ key: String, in flows: [TrafficFlow]) -> EndpointGroup? {
        let hits = RequestHit.all(flows).filter { !$0.exchange.method.isEmpty && Endpoint.of($0.flow, $0.exchange).key == key }
        return hits.first.map { EndpointGroup(endpoint: Endpoint.of($0.flow, $0.exchange), hits: hits) }
    }
}
