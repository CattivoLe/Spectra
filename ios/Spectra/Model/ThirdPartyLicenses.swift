import Foundation

/// The third-party notices the Licenses screen shows, read from `Catalog/licenses.json` (shared with Android, built
/// from the components' own license files; see THIRD_PARTY_LICENSES.md).
enum ThirdPartyLicenses {
    enum Group: String, CaseIterable, Decodable {
        case app, code, firmware, data
    }

    struct Component: Identifiable, Hashable {
        var id: Int
        var name: String
        var group: Group
        var version: String
        var url: String
        /// The license by name, as shown in the list: "MIT", "Apache-2.0", "Data".
        var license: String
        var copyright: String
        /// Extra notice the component asks binaries to carry (a NOTICE file, a source offer, a credit).
        var notice: String
        /// The license's full text, or nil when there is none to show (public domain, data credits).
        var text: String?
    }

    private struct File: Decodable {
        struct Item: Decodable {
            var name: String
            var group: Group
            var version: String
            var url: String
            var license: String
            var text: String?
            var copyright: String
            var notice: String
            var platforms: [String]
        }
        var components: [Item]
        var texts: [String: String]
    }

    /// The components that ship in the app for `platform` ("ios" or "android"), in the file's order.
    static func parse(_ data: Data, platform: String = "ios") throws -> [Component] {
        let file = try JSONDecoder().decode(File.self, from: data)
        return file.components.enumerated().compactMap { index, item in
            guard item.platforms.contains(platform) else { return nil }
            return Component(
                id: index, name: item.name, group: item.group, version: item.version, url: item.url,
                license: item.license, copyright: item.copyright, notice: item.notice,
                text: item.text.flatMap { file.texts[$0] }
            )
        }
    }

    /// License files are wrapped at about 80 columns; on a phone those breaks land mid-line. Lines of one paragraph
    /// are joined back, while blank lines, list items and numbered clauses keep their own lines.
    static func reflow(_ text: String) -> String {
        var out = ""
        var previousBlank = true
        for raw in text.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty {
                if !previousBlank { out += "\n\n" }
                previousBlank = true
            } else if previousBlank {
                out += line
                previousBlank = false
            } else if line.range(of: #"^(?:[*\-•]\s|\d+[.)]\s|\([a-z0-9]+\)\s|[a-z][.)]\s)"#, options: .regularExpression) != nil {
                out += "\n" + line
            } else {
                out += " " + line
            }
        }
        while out.last?.isWhitespace == true { out.removeLast() }
        return out
    }

    static let bundled: [Component] = {
        guard let url = Bundle.main.url(forResource: "licenses", withExtension: "json", subdirectory: "Catalog"),
              let data = try? Data(contentsOf: url) else { return [] }
        return (try? parse(data)) ?? []
    }()
}
