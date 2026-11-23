import Foundation

/// Servers of known analytics, advertising and crash-reporting services, from `trackers.tsv` in the bundled Catalog
/// folder (shared with Android). A name matches itself and everything under it. Port of Android `TrackerHosts`.
nonisolated enum TrackerHosts {
    enum Kind: String, Sendable { case analytics, ads, attribution, crash, push }

    struct Service: Equatable, Sendable {
        var name: String
        var kind: Kind
    }

    static func service(of host: String) -> Service? {
        var name = Substring(host.lowercased().prefix { $0 != ":" })
        while name.contains(".") {
            if let found = table[String(name)] { return found }
            name = name[name.index(after: name.firstIndex(of: ".")!)...]
        }
        return nil
    }

    private static let table: [String: Service] = {
        guard let url = Bundle.main.url(forResource: "trackers", withExtension: "tsv", subdirectory: "Catalog"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [:] }
        var result: [String: Service] = [:]
        for line in text.split(separator: "\n") {
            let fields = line.split(separator: "\t").map(String.init)
            if fields.count == 3, let kind = Kind(rawValue: fields[2]) { result[fields[0]] = Service(name: fields[1], kind: kind) }
        }
        return result
    }()
}
