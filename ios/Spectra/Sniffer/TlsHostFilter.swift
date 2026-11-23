import Foundation

/// Which hosts are decrypted, like SSL Proxying in Charles. In `.all` every host is; in `.only` the hosts
/// matching `only` are, except those matching `except`; in `.except` every host is, except those matching
/// `except`. Port of Android `core/TlsHostFilter.kt`.
///
/// A pattern is a host name with `*` for any run of characters and `?` for one. `*.example.com` also matches
/// `example.com` itself. A port after a colon is ignored. Case does not matter.
nonisolated struct TlsHostFilter: Codable, Equatable, Sendable {
    enum Mode: String, Codable, CaseIterable, Sendable { case all, only, except }

    var mode: Mode = .all
    var only: [String] = []
    var except: [String] = []

    /// True when a connection to `host` is decrypted. Without a name only `.only` says no.
    func allows(_ host: String?) -> Bool {
        switch mode {
        case .all:
            return true
        case .only:
            guard let host else { return false }
            return only.contains { Self.matches($0, host) } && !except.contains { Self.matches($0, host) }
        case .except:
            guard let host else { return true }
            return !except.contains { Self.matches($0, host) }
        }
    }

    /// Adds `host` to the exceptions; when everything was decrypted, the filter starts using them.
    func excluding(_ host: String) -> TlsHostFilter {
        guard let clean = Self.normalize(host) else { return self }
        var next = self
        if !except.contains(where: { $0.caseInsensitiveCompare(clean) == .orderedSame }) { next.except.append(clean) }
        if mode == .all { next.mode = .except }
        return next
    }

    /// Trims a typed pattern; nil when nothing is left or it has characters a host cannot have.
    static func normalize(_ pattern: String) -> String? {
        var text = pattern.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        for scheme in ["https://", "http://"] where text.hasPrefix(scheme) { text.removeFirst(scheme.count) }
        text = String(text.prefix { $0 != "/" })
        if text.hasPrefix("[") {
            text = String(text.dropFirst().prefix { $0 != "]" })
        } else if text.filter({ $0 == ":" }).count == 1 {
            text = String(text.prefix { $0 != ":" })
        }
        while text.hasSuffix(".") { text.removeLast() }
        if text.isEmpty || text.count > 253 { return nil }
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: ".-_*?:"))
        if text.unicodeScalars.contains(where: { !allowed.contains($0) }) { return nil }
        return text
    }

    static func matches(_ pattern: String, _ host: String) -> Bool {
        guard let p = normalize(pattern) else { return false }
        var h = host.trimmingCharacters(in: .whitespaces).lowercased()
        while h.hasSuffix(".") { h.removeLast() }
        if p.hasPrefix("*."), h == String(p.dropFirst(2)) { return true }
        return glob(Array(p), Array(h))
    }

    private static func glob(_ pattern: [Character], _ text: [Character]) -> Bool {
        var p = 0, t = 0, star = -1, mark = 0
        while t < text.count {
            if p < pattern.count && (pattern[p] == "?" || pattern[p] == text[t]) {
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

    // MARK: Shared settings (App Group), read by the tunnel

    static let modeKey = "traffic_tls_mode"
    static let onlyKey = "traffic_tls_only"
    static let exceptKey = "traffic_tls_except"

    static func load(_ defaults: UserDefaults?) -> TlsHostFilter {
        TlsHostFilter(
            mode: Mode(rawValue: defaults?.string(forKey: modeKey) ?? "") ?? .all,
            only: defaults?.stringArray(forKey: onlyKey) ?? [],
            except: defaults?.stringArray(forKey: exceptKey) ?? []
        )
    }

    func save(_ defaults: UserDefaults?) {
        defaults?.set(mode.rawValue, forKey: Self.modeKey)
        defaults?.set(only, forKey: Self.onlyKey)
        defaults?.set(except, forKey: Self.exceptKey)
    }
}
