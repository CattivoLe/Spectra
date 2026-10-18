import Foundation

/// Maker lookup by MAC prefix (OUI). The table is the shared `oui.tsv` in
/// data/spectra, bundled in the Catalog folder and shared with the Android app.
/// It covers common consumer devices, not the full 30 000-entry IEEE list.
enum OUIDatabase {
    static func lookup(_ oui: String) -> String? {
        db[oui.uppercased()]
    }

    private static let db: [String: String] = {
        guard let url = Bundle.main.url(forResource: "oui", withExtension: "tsv", subdirectory: "Catalog"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return [:]
        }
        var result: [String: String] = [:]
        for line in text.split(separator: "\n") {
            guard let tab = line.firstIndex(of: "\t") else { continue }
            let key = String(line[..<tab]).uppercased()
            let name = String(line[line.index(after: tab)...])
            if !name.isEmpty { result[key] = name }
        }
        return result
    }()
}
