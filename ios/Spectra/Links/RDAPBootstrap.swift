import Foundation

/// Which RDAP server answers for each top-level domain, from IANA's
/// bootstrap file. A snapshot ships in the app (`RDAPServers`); a fresher
/// copy is fetched from IANA now and then and kept in Caches. Either way the
/// domain itself is only ever sent to its own registry.
nonisolated struct RDAPBootstrap: Equatable, Sendable {
    /// Lower-case TLD (ASCII, `xn--` for IDN zones) to the service base URL.
    var servers: [String: URL]
    var publication: String?

    /// The registry for a domain, by its last label.
    func server(for domain: String) -> URL? {
        guard let tld = domain.lowercased().split(separator: ".").last else { return nil }
        return servers[String(tld)]
    }

    /// Parses IANA's `dns.json`. Plain-http servers are left out: App
    /// Transport Security would refuse them anyway.
    static func parse(_ data: Data) -> RDAPBootstrap? {
        guard let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let services = object["services"] as? [[Any]] else { return nil }
        var servers: [String: URL] = [:]
        for service in services {
            guard service.count >= 2, let tlds = service[0] as? [String], let urls = service[1] as? [String] else { continue }
            guard let base = urls.lazy.compactMap({ secureBase($0) }).first else { continue }
            for tld in tlds { servers[tld.lowercased()] = base }
        }
        guard !servers.isEmpty else { return nil }
        return RDAPBootstrap(servers: servers, publication: object["publication"] as? String)
    }

    /// The snapshot compiled into the app.
    static let bundled: RDAPBootstrap = {
        var servers: [String: URL] = [:]
        for line in RDAPServers.table.split(separator: "\n") {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let tlds = line[..<colon].split(separator: " ")
            guard let base = secureBase(String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)) else { continue }
            for tld in tlds { servers[String(tld)] = base }
        }
        return RDAPBootstrap(servers: servers, publication: RDAPServers.publication)
    }()

    private static func secureBase(_ text: String) -> URL? {
        guard text.lowercased().hasPrefix("https://") else { return nil }
        return URL(string: text.hasSuffix("/") ? text : text + "/")
    }
}

/// Keeps the bootstrap reasonably fresh without asking IANA on every check.
nonisolated enum RDAPBootstrapCache {
    static let source = URL(string: "https://data.iana.org/rdap/dns.json")!
    static let maxAge: TimeInterval = 7 * 86_400

    private static var file: URL? {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("rdap-dns.json")
    }

    /// The cached copy when it is recent, else a fresh one from IANA, else
    /// whatever is cached, else the bundled snapshot. Never throws: a lookup
    /// must not fail because the list could not be refreshed.
    static func current(session: URLSession = SiteProbe.session, now: Date = .now) async -> RDAPBootstrap {
        let cached = file.flatMap { url -> (RDAPBootstrap, Date)? in
            guard let data = try? Data(contentsOf: url), let parsed = RDAPBootstrap.parse(data),
                  let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate else { return nil }
            return (parsed, modified)
        }
        if let cached, now.timeIntervalSince(cached.1) < maxAge {
            return cached.0
        }
        if let (data, response) = try? await session.data(from: source),
           (response as? HTTPURLResponse)?.statusCode == 200,
           let fresh = RDAPBootstrap.parse(data) {
            if let file { try? data.write(to: file, options: .atomic) }
            return fresh
        }
        return cached?.0 ?? .bundled
    }
}
