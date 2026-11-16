import Foundation

/// What a scanned code carries. Most phishing codes are plain links; a
/// Wi-Fi code is the other kind worth explaining before anyone acts on it.
nonisolated enum QRPayload: Equatable, Sendable {
    /// A link, or text with a link in it, for the link inspector.
    case link(String)
    /// A Wi-Fi join code: network name, security and whether the network is hidden.
    case wifi(ssid: String, security: String?, hidden: Bool)
    /// Anything else: contact cards, plain words, numbers.
    case text(String)

    static func classify(_ raw: String) -> QRPayload {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.uppercased().hasPrefix("WIFI:") {
            return wifi(text)
        }
        if LinkInspector.extractLink(text) != nil {
            return .link(text)
        }
        return .text(text)
    }

    /// `WIFI:T:WPA;S:Cafe;P:secret;H:true;;` with `\` escaping `;`, `:`, `,` and `\`.
    private static func wifi(_ text: String) -> QRPayload {
        var fields: [String: String] = [:]
        var key = ""
        var value = ""
        var readingKey = true
        var escaped = false
        for character in text.dropFirst(5) {
            if escaped {
                value.append(character)
                escaped = false
                continue
            }
            switch character {
            case "\\" where !readingKey:
                escaped = true
            case ":" where readingKey:
                readingKey = false
            case ";" where !readingKey:
                fields[key.uppercased()] = value
                key = ""
                value = ""
                readingKey = true
            case ";" where readingKey:
                // Two semicolons close the record.
                key = ""
            default:
                if readingKey { key.append(character) } else { value.append(character) }
            }
        }
        if !readingKey, !key.isEmpty { fields[key.uppercased()] = value }
        let ssid = fields["S"] ?? ""
        let security = fields["T"].flatMap { $0.isEmpty || $0.lowercased() == "nopass" ? nil : $0.uppercased() }
        let hidden = (fields["H"] ?? "").lowercased() == "true"
        return .wifi(ssid: ssid, security: security, hidden: hidden)
    }
}
