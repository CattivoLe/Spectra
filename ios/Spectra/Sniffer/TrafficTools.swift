import Foundation

/// Slows the recorded connections down, like Throttle Settings in Charles: a download and an upload rate, and a
/// delay added to everything the phone receives. `preset` names a ready setting; "custom" keeps the numbers
/// given. Port of Android `ThrottleSettings`.
nonisolated struct ThrottleSettings: Codable, Equatable, Sendable {
    var preset = ThrottleSettings.off
    var downKbps = 0
    var upKbps = 0
    var latencyMs = 0

    static let off = "off"
    static let custom = "custom"

    var enabled: Bool { preset != Self.off }

    /// Ready settings, slowest first.
    static let presets: [ThrottleSettings] = [
        ThrottleSettings(preset: "edge", downKbps: 240, upKbps: 200, latencyMs: 400),
        ThrottleSettings(preset: "3g", downKbps: 780, upKbps: 330, latencyMs: 200),
        ThrottleSettings(preset: "fast3g", downKbps: 1600, upKbps: 750, latencyMs: 100),
        ThrottleSettings(preset: "4g", downKbps: 9000, upKbps: 9000, latencyMs: 50),
    ]

    static func preset(_ name: String) -> ThrottleSettings { presets.first { $0.preset == name } ?? ThrottleSettings() }

    // MARK: Shared settings (App Group), read by the tunnel

    static let key = "traffic_throttle"

    static func load(_ defaults: UserDefaults?) -> ThrottleSettings {
        guard let data = defaults?.data(forKey: key),
              var settings = try? JSONDecoder().decode(ThrottleSettings.self, from: data) else { return ThrottleSettings() }
        settings.downKbps = min(max(settings.downKbps, 0), 1_000_000)
        settings.upKbps = min(max(settings.upKbps, 0), 1_000_000)
        settings.latencyMs = min(max(settings.latencyMs, 0), 10_000)
        return settings
    }

    func save(_ defaults: UserDefaults?) {
        defaults?.set(try? JSONEncoder().encode(self), forKey: Self.key)
    }
}

/// Token buckets for one recording, shared by every connection: `down` for what goes to the phone, `up` for
/// what goes to the servers. A rate of 0 does not limit that direction. Used on the proxy's loop only.
nonisolated final class Throttle {
    let settings: ThrottleSettings
    let down: Bucket
    let up: Bucket
    var latencyMs: Int64 { settings.enabled ? Int64(settings.latencyMs) : 0 }

    init(_ settings: ThrottleSettings) {
        self.settings = settings
        down = Bucket(kbps: settings.downKbps)
        up = Bucket(kbps: settings.upKbps)
    }

    final class Bucket {
        /// Bytes per millisecond; 0 is no limit.
        private let rate: Double
        /// About 50 ms of data, and never less than one packet, so a full segment can always pass.
        private let capacity: Double
        private var tokens: Double
        private var last: Int64 = -1

        init(kbps: Int) {
            rate = Double(kbps) / 8
            capacity = max(rate * 50, 1500)
            tokens = capacity
        }

        var limited: Bool { rate > 0 }

        /// How many of `want` bytes may go at `now`; they are taken from the bucket.
        func take(_ want: Int, now: Int64) -> Int {
            guard limited, want > 0 else { return want }
            if last < 0 { last = now }
            tokens = min(capacity, tokens + Double(max(0, now - last)) * rate)
            last = now
            let allowed = min(want, Int(tokens))
            tokens -= Double(allowed)
            return allowed
        }
    }
}

/// Map Local, as in Charles: requests to a matching host and path are answered from here and never reach the
/// server. `host` is a pattern like the TLS host list's; `path` matches the request target (path and query) with
/// `*` for any characters, empty for any. Port of Android `MapRule`.
nonisolated struct MapRule: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var enabled = true
    var host: String
    var path = ""
    var status = 200
    var contentType = "application/json"
    var body = ""

    func matchesHost(_ name: String?) -> Bool {
        guard let name else { return false }
        return TlsHostFilter.matches(host, name)
    }

    func matches(host name: String?, target: String) -> Bool {
        guard enabled, matchesHost(name) else { return false }
        let pattern = path.trimmingCharacters(in: .whitespaces)
        if pattern.isEmpty { return true }
        let bare = target.components(separatedBy: "?").first ?? target
        return Self.glob(Array(pattern), Array(target)) || Self.glob(Array(pattern), Array(bare))
    }

    private static func glob(_ pattern: [Character], _ text: [Character]) -> Bool {
        var p = 0, t = 0, star = -1, mark = 0
        while t < text.count {
            if p < pattern.count && pattern[p] != "*" && pattern[p] == text[t] {
                p += 1; t += 1
            } else if p < pattern.count && pattern[p] == "*" {
                star = p; p += 1; mark = t
            } else if star >= 0 {
                p = star + 1; mark += 1; t = mark
            } else {
                return false
            }
        }
        while p < pattern.count && pattern[p] == "*" { p += 1 }
        return p == pattern.count
    }

    init(id: String = UUID().uuidString, enabled: Bool = true, host: String, path: String = "", status: Int = 200,
         contentType: String = "application/json", body: String = "") {
        self.id = id; self.enabled = enabled; self.host = host; self.path = path
        self.status = status; self.contentType = contentType; self.body = body
    }

    // MARK: Shared settings (App Group), read by the tunnel

    static let key = "traffic_map_local"

    static func load(_ defaults: UserDefaults?) -> [MapRule] {
        guard let data = defaults?.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([MapRule].self, from: data)) ?? []
    }

    static func save(_ rules: [MapRule], _ defaults: UserDefaults?) {
        defaults?.set(try? JSONEncoder().encode(rules), forKey: key)
    }
}

/// The rules in force for one connection, and the responses they make.
nonisolated struct MapLocal: Sendable {
    private let active: [MapRule]

    /// The header every local answer carries, so the recording shows which requests never left the phone.
    static let header = "X-Spectra-Map-Local"

    init(_ rules: [MapRule]) { active = rules.filter(\.enabled) }

    var isEmpty: Bool { active.isEmpty }

    /// True when some rule could answer requests to `host`: such a connection is kept on HTTP/1.1.
    func covers(host: String?) -> Bool { active.contains { $0.matchesHost(host) } }

    func rule(host: String?, target: String) -> MapRule? { active.first { $0.matches(host: host, target: target) } }

    /// An HTTP/1.1 response for `rule`. HEAD gets the headers only.
    static func response(_ rule: MapRule, method: String) -> [UInt8] {
        let body = Array(rule.body.utf8)
        var head = "HTTP/1.1 \(rule.status) \(reason(rule.status))\r\n"
        if !rule.contentType.trimmingCharacters(in: .whitespaces).isEmpty { head += "Content-Type: \(rule.contentType)\r\n" }
        head += "Content-Length: \(body.count)\r\nCache-Control: no-store\r\n\(header): \(rule.host)\(rule.path)\r\n\r\n"
        return Array(head.utf8) + (method == "HEAD" ? [] : body)
    }

    static func reason(_ status: Int) -> String {
        switch status {
        case 200: "OK"
        case 201: "Created"
        case 204: "No Content"
        case 301: "Moved Permanently"
        case 302: "Found"
        case 304: "Not Modified"
        case 400: "Bad Request"
        case 401: "Unauthorized"
        case 403: "Forbidden"
        case 404: "Not Found"
        case 429: "Too Many Requests"
        case 500: "Internal Server Error"
        case 502: "Bad Gateway"
        case 503: "Service Unavailable"
        default: "Status"
        }
    }
}
