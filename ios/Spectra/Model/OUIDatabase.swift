import Foundation

/// Maker lookup by MAC prefix. The table is `oui.tsv` in the bundled Catalog
/// folder: the IEEE MA-L, MA-M and MA-S registries with legal suffixes
/// stripped, built by `Catalog/build_oui.py`. Keys are 24-bit prefixes
/// ("AA:BB:CC"), 28-bit ("AA:BB:CC:D") and 36-bit ("AA:BB:CC:DD:E").
nonisolated enum OUIDatabase {
    /// The maker of a 24-bit prefix.
    static func lookup(_ oui: String) -> String? {
        db[oui.uppercased()]
    }

    /// The maker of a whole address: the most specific registry block wins,
    /// since a 36-bit block is carved out of a 24-bit one that belongs to
    /// the registration authority.
    static func lookup(bytes: [UInt8]) -> String? {
        guard bytes.count >= 5 else { return nil }
        let hex = bytes.prefix(5).map { String(format: "%02X", $0) }
        let small = "\(hex[0]):\(hex[1]):\(hex[2]):\(hex[3]):\(hex[4].prefix(1))"
        if let name = db[small] { return name }
        let medium = "\(hex[0]):\(hex[1]):\(hex[2]):\(hex[3].prefix(1))"
        if let name = db[medium] { return name }
        return db["\(hex[0]):\(hex[1]):\(hex[2])"]
    }

    private static let db: [String: String] = {
        guard let url = Bundle.main.url(forResource: "oui", withExtension: "tsv", subdirectory: "Catalog"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return [:]
        }
        var result: [String: String] = [:]
        result.reserveCapacity(60_000)
        for line in text.split(separator: "\n") {
            guard let tab = line.firstIndex(of: "\t") else { continue }
            let key = String(line[..<tab])
            let name = String(line[line.index(after: tab)...])
            if !name.isEmpty { result[key] = name }
        }
        return result
    }()
}
