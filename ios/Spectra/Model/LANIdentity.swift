import Foundation

/// What a host volunteered beyond an open port: UPnP, a web banner, RTSP, or Windows.
nonisolated struct LANProfile: Equatable, Sendable {
    var upnp: UPnPIdentity?
    var banners: [HTTPBanner] = []
    var rtsp: [RTSPService] = []
    var windows: WindowsHost?

    var isEmpty: Bool {
        upnp == nil && banners.isEmpty && rtsp.isEmpty && windows == nil
    }

    /// Keeps a name we already learned when a later probe comes back empty.
    func filled(from previous: LANProfile) -> LANProfile {
        if isEmpty { return previous }
        var result = self
        result.upnp = upnp?.filled(from: previous.upnp) ?? previous.upnp
        result.windows = windows?.filled(from: previous.windows) ?? previous.windows
        if banners.isEmpty { result.banners = previous.banners }
        if rtsp.isEmpty { result.rtsp = previous.rtsp }
        return result
    }
}

nonisolated struct UPnPIdentity: Equatable, Sendable {
    var friendlyName: String?
    var manufacturer: String?
    var model: String?
    var deviceType: String?
    var server: String?

    func filled(from previous: UPnPIdentity?) -> UPnPIdentity {
        guard let previous else { return self }
        return UPnPIdentity(
            friendlyName: friendlyName.keptName(or: previous.friendlyName),
            manufacturer: manufacturer.kept(or: previous.manufacturer),
            model: model.kept(or: previous.model),
            deviceType: deviceType.kept(or: previous.deviceType),
            server: server.kept(or: previous.server)
        )
    }
}

nonisolated struct HTTPBanner: Equatable, Sendable, Identifiable {
    var port: Int
    var secure: Bool
    var status: Int?
    var server: String?
    var poweredBy: String?
    var title: String?
    var realm: String?
    var id: Int { port }

    var summary: String {
        var parts: [String] = []
        if let status { parts.append("HTTP \(status)") }
        if let server { parts.append(server) }
        if let poweredBy { parts.append(poweredBy) }
        if let title { parts.append(title) }
        if let realm { parts.append(realm) }
        return parts.joined(separator: " · ")
    }
}

nonisolated struct RTSPService: Equatable, Sendable, Identifiable {
    var port: Int
    var server: String?
    var methods: String?
    var id: Int { port }

    var summary: String? {
        let parts = [server, methods].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

nonisolated struct WindowsHost: Equatable, Sendable {
    var computerName: String?
    var domain: String?
    /// Shares that accepted a guest tree-connect. Empty when the host named
    /// itself but refused a session.
    var shares: [String] = []
    var guestSession: Bool = false

    func filled(from previous: WindowsHost?) -> WindowsHost {
        guard let previous else { return self }
        return WindowsHost(
            computerName: computerName.keptName(or: previous.computerName),
            domain: domain.kept(or: previous.domain),
            shares: shares.isEmpty ? previous.shares : shares,
            guestSession: guestSession || previous.guestSession
        )
    }
}

private nonisolated extension String? {
    /// A later probe may replace a name only with another real name. A blank,
    /// a null, or a placeholder must not wipe the one already learned.
    func keptName(or previous: String?) -> String? {
        if let self, LANIdentity.presentableName(self) != nil { return LANIdentity.cleanedText(self) }
        return previous
    }

    func kept(or previous: String?) -> String? {
        if let self, !self.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return self }
        return previous
    }
}

nonisolated enum NotableDevice: Equatable, Sendable {
    case camera
    case iot

    var title: String {
        switch self {
        case .camera: String(localized: "Camera")
        case .iot: String(localized: "IoT")
        }
    }

    /// Cameras outrank generic IoT. An RTSP listener is a camera even when
    /// its maker is not in the vendor list.
    static func classify(manufacturer: String?, oui: String?, upnpType: String?, hasRTSP: Bool) -> NotableDevice? {
        if hasRTSP { return .camera }
        let type = (upnpType ?? "").lowercased()
        if type.contains("camera") || type.contains("videosurveillance") || type.contains("doorbell") {
            return .camera
        }
        if let fromVendor = vendorKind(manufacturer) ?? vendorKind(oui.flatMap { SuspiciousOUI.names[$0.uppercased()] }) {
            return fromVendor
        }
        if type.contains("binarylight") || type.contains("dimmable") || type.contains("hvac")
            || type.contains("sensor") || type.contains("basic") {
            return .iot
        }
        return nil
    }

    private static func vendorKind(_ name: String?) -> NotableDevice? {
        guard let name else { return nil }
        let folded = name.lowercased()
        if SuspiciousOUI.cameraMakers.contains(where: { folded.contains($0) }) { return .camera }
        if SuspiciousOUI.iotMakers.contains(where: { folded.contains($0) }) { return .iot }
        return nil
    }
}

/// Makers whose presence on a home LAN is worth a second look, matched on the
/// OUI name rather than by rewriting the vendor table.
nonisolated enum SuspiciousOUI {
    static let cameraMakers = [
        "hikvision", "dahua", "reolink", "amcrest", "foscam", "ezviz", "uniview",
        "hanwha", "vivotek", "geovision", "wyze", "arlo", "imou", "tapo", "ring",
        "axis communications",
    ]
    static let iotMakers = [
        "espressif", "tuya", "philips hue", "shelly", "sonoff", "broadlink",
        "aqara", "meross", "switchbot", "sonos", "wiz",
    ]

    /// Camera prefixes that are not already in `OUIDatabase`.
    static let names: [String: String] = [
        "00:40:8C": "Axis Communications",
        "18:68:CB": "Hikvision",
        "44:19:B6": "Hikvision",
        "4C:BD:8F": "Hikvision",
        "BC:AD:28": "Hikvision",
        "C0:56:E3": "Hikvision",
        "3C:EF:8C": "Dahua",
        "90:02:A9": "Dahua",
        "A0:BD:1D": "Dahua",
        "E0:50:8B": "Dahua",
        "EC:71:DB": "Reolink",
    ]
}

/// Parsers for the text protocols. The sockets live in `LANProbes`.
nonisolated enum LANIdentity {
    static func ssdpSearch(host: String) -> Data {
        Data(("M-SEARCH * HTTP/1.1\r\nHOST: \(host):1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n").utf8)
    }

    static func parseSSDP(_ data: Data) -> (location: String?, server: String?, searchTarget: String?)? {
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else {
            return nil
        }
        let message = HeaderText.parse(text)
        guard message.start.uppercased().contains("HTTP/") else { return nil }
        let location = message.headers["location"]
        let server = message.headers["server"]
        let target = message.headers["st"]
        guard location != nil || server != nil || target != nil else { return nil }
        return (location, server, target)
    }

    /// The description URL is only fetched when it points back at the host we asked.
    static func locationIsOnHost(_ location: String, ip: String) -> Bool {
        guard let url = URL(string: location), let host = url.host?.lowercased() else { return false }
        let scheme = url.scheme?.lowercased()
        guard scheme == "http" || scheme == "https" else { return false }
        return host == ip || host == "[\(ip)]"
    }

    static func parseUPnPDescription(_ xml: String) -> UPnPIdentity {
        let type = tag("deviceType", in: xml).map(shortDeviceType)
        let model = [tag("modelName", in: xml), tag("modelNumber", in: xml)]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        return UPnPIdentity(
            friendlyName: tag("friendlyName", in: xml),
            manufacturer: tag("manufacturer", in: xml),
            model: model.isEmpty ? nil : model,
            deviceType: type,
            server: nil
        )
    }

    static func httpRequest(host: String, method: String, version: String = "1.0") -> Data {
        Data(("\(method) / HTTP/\(version)\r\nHost: \(host)\r\nUser-Agent: Spectra\r\nConnection: close\r\n\r\n").utf8)
    }

    /// Router pages often answer HEAD with 501 and still serve the page for GET.
    static func httpMethodRejected(_ status: Int?) -> Bool {
        status == 400 || status == 405 || status == 501 || status == 505
    }

    /// A later response is worth keeping when it is a real page rather than the HEAD error.
    static func httpPrefers(status: Int?, title: String?, over otherStatus: Int?, otherTitle: String?) -> Bool {
        func rank(_ status: Int?) -> Int {
            guard let status else { return 3 }
            if (200..<400).contains(status) { return 0 }
            if status == 401 || status == 403 { return 1 }
            return 2
        }
        if rank(status) != rank(otherStatus) { return rank(status) < rank(otherStatus) }
        return title != nil && otherTitle == nil
    }

    static func parseHTTP(_ data: Data) -> (status: Int?, server: String?, poweredBy: String?, title: String?, realm: String?)? {
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else {
            return nil
        }
        let message = HeaderText.parse(text)
        guard message.start.uppercased().hasPrefix("HTTP/") else { return nil }
        let status = message.start.split(separator: " ").dropFirst().first.flatMap { Int($0) }
        return (
            status,
            clip(message.headers["server"]),
            clip(message.headers["x-powered-by"]),
            htmlTitle(message.body),
            realm(message.headers["www-authenticate"])
        )
    }

    static func rtspOptions() -> Data {
        Data("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: Spectra\r\n\r\n".utf8)
    }

    static func parseRTSP(_ data: Data) -> (server: String?, methods: String?)? {
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else {
            return nil
        }
        let message = HeaderText.parse(text)
        guard message.start.uppercased().hasPrefix("RTSP/") else { return nil }
        return (clip(message.headers["server"]), clip(message.headers["public"]))
    }

    static func netbiosNodeStatusRequest() -> Data {
        var packet = Data([
            0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x43, 0x4B,
        ])
        packet.append(contentsOf: [UInt8](repeating: 0x41, count: 30))
        packet.append(contentsOf: [0x00, 0x00, 0x21, 0x00, 0x01])
        return packet
    }

    /// Names from a NetBIOS node-status answer. Group names are workgroups.
    static func parseNetBIOS(_ data: Data) -> [(name: String, suffix: UInt8, group: Bool)] {
        let bytes = [UInt8](data)
        guard let marker = find([0x00, 0x21, 0x00, 0x01], in: bytes) else { return [] }
        // The marker is the question's type and class. The answer follows,
        // usually as a compressed name, then its own type, class, TTL and length.
        var index = marker + 4
        if index + 2 <= bytes.count, bytes[index] == 0xC0 { index += 2 }
        guard index + 10 <= bytes.count else { return [] }
        index += 8
        index += 2
        guard index < bytes.count else { return [] }
        let count = Int(bytes[index])
        index += 1
        var names: [(String, UInt8, Bool)] = []
        for _ in 0..<count {
            guard index + 18 <= bytes.count else { break }
            let raw = bytes[index..<(index + 15)]
            let name = String(bytes: raw, encoding: .ascii)?
                .trimmingCharacters(in: .whitespaces) ?? ""
            let suffix = bytes[index + 15]
            let flags = UInt16(bytes[index + 16]) << 8 | UInt16(bytes[index + 17])
            if !name.isEmpty {
                names.append((name, suffix, flags & 0x8000 != 0))
            }
            index += 18
        }
        return names
    }

    static func ntlmType1() -> Data {
        var data = Data("NTLMSSP\0".utf8)
        data.append(contentsOf: [0x01, 0x00, 0x00, 0x00])
        // Unicode, request target, NTLM, extended session security.
        data.append(contentsOf: [0x05, 0x02, 0x08, 0x00])
        data.append(contentsOf: [UInt8](repeating: 0, count: 8))
        data.append(contentsOf: [0x20, 0x00, 0x00, 0x00])
        data.append(contentsOf: [UInt8](repeating: 0, count: 4))
        data.append(contentsOf: [0x20, 0x00, 0x00, 0x00])
        return data
    }

    /// Empty authenticate message. A server that allows guests accepts it; others refuse.
    static func ntlmType3() -> Data {
        var data = Data("NTLMSSP\0".utf8)
        data.append(contentsOf: [0x03, 0x00, 0x00, 0x00])
        for _ in 0..<6 {
            data.append(contentsOf: [0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00])
        }
        data.append(contentsOf: [0x05, 0x02, 0x08, 0x00])
        return data
    }

    /// Computer and domain names from an NTLM challenge, searched for inside an SMB reply.
    static func parseNTLMChallenge(_ data: Data) -> (computer: String?, domain: String?)? {
        let bytes = [UInt8](data)
        let signature = Array("NTLMSSP\0".utf8)
        guard let start = find(signature, in: bytes) else { return nil }
        guard start + 48 <= bytes.count else { return nil }
        let type = UInt32(bytes[start + 8]) | UInt32(bytes[start + 9]) << 8
            | UInt32(bytes[start + 10]) << 16 | UInt32(bytes[start + 11]) << 24
        guard type == 2 else { return nil }
        let infoLength = Int(UInt16(bytes[start + 40]) | UInt16(bytes[start + 41]) << 8)
        let infoOffset = Int(UInt32(bytes[start + 44]) | UInt32(bytes[start + 45]) << 8
            | UInt32(bytes[start + 46]) << 16 | UInt32(bytes[start + 47]) << 24)
        let infoStart = start + infoOffset
        guard infoLength > 0, infoStart >= 0, infoStart + infoLength <= bytes.count else { return nil }
        var computer: String?
        var domain: String?
        var cursor = infoStart
        let end = infoStart + infoLength
        while cursor + 4 <= end {
            let id = UInt16(bytes[cursor]) | UInt16(bytes[cursor + 1]) << 8
            let length = Int(UInt16(bytes[cursor + 2]) | UInt16(bytes[cursor + 3]) << 8)
            cursor += 4
            guard cursor + length <= end else { break }
            if id == 0 { break }
            let value = utf16LE(bytes[cursor..<(cursor + length)])
            if id == 1 || id == 3, computer == nil { computer = value }
            if id == 2 || id == 4, domain == nil { domain = value }
            cursor += length
        }
        guard computer != nil || domain != nil else { return nil }
        return (computer, domain)
    }

    static func smbStatus(_ response: Data) -> UInt32? {
        let bytes = [UInt8](response)
        guard bytes.count >= 12, bytes[0] == 0xFE, bytes[1] == 0x53, bytes[2] == 0x4D, bytes[3] == 0x42 else {
            return nil
        }
        return UInt32(bytes[8]) | UInt32(bytes[9]) << 8 | UInt32(bytes[10]) << 16 | UInt32(bytes[11]) << 24
    }

    static func smbSessionID(_ response: Data) -> UInt64? {
        guard smbStatus(response) != nil, response.count >= 48 else { return nil }
        let bytes = [UInt8](response)
        var value: UInt64 = 0
        for shift in 0..<8 {
            value |= UInt64(bytes[40 + shift]) << (8 * shift)
        }
        return value
    }

    static let smbSuccess: UInt32 = 0
    static let smbMoreProcessing: UInt32 = 0xC0000016

    /// SMB2 directly over TCP, with the 4-byte NetBIOS session header.
    static func smbNegotiate() -> Data { frame(smbHeader(command: 0, messageID: 0) + negotiateBody()) }

    static func smbSessionSetup(ntlm: Data, messageID: UInt64, sessionID: UInt64 = 0) -> Data {
        var body = Data()
        body.appendU16(25)
        body.append(0)
        body.append(1)
        body.appendU32(0)
        body.appendU32(0)
        body.appendU16(88)
        body.appendU16(UInt16(ntlm.count))
        body.appendU64(0)
        body.append(ntlm)
        return frame(smbHeader(command: 1, messageID: messageID, sessionID: sessionID) + body)
    }

    static func smbTreeConnect(share: String, ip: String, messageID: UInt64, sessionID: UInt64) -> Data {
        let path = utf16("\\\\\(ip)\\\(share)")
        var body = Data()
        body.appendU16(9)
        body.appendU16(0)
        body.appendU16(72)
        body.appendU16(UInt16(path.count))
        body.append(path)
        return frame(smbHeader(command: 3, messageID: messageID, sessionID: sessionID) + body)
    }

    static func smbPayload(_ framed: Data) -> Data {
        guard framed.count >= 4 else { return framed }
        let length = (Int(framed[1]) << 16) | (Int(framed[2]) << 8) | Int(framed[3])
        let end = min(framed.count, 4 + length)
        return framed.subdata(in: 4..<end)
    }

    private static func smbHeader(command: UInt16, messageID: UInt64, sessionID: UInt64 = 0) -> Data {
        var header = Data([0xFE, 0x53, 0x4D, 0x42])
        header.appendU16(64)
        header.appendU16(0)
        header.appendU32(0)
        header.appendU16(command)
        header.appendU16(1)
        header.appendU32(0)
        header.appendU32(0)
        header.appendU64(messageID)
        header.appendU32(0)
        header.appendU32(0)
        header.appendU64(sessionID)
        header.append(contentsOf: [UInt8](repeating: 0, count: 16))
        return header
    }

    private static func negotiateBody() -> Data {
        var body = Data()
        body.appendU16(36)
        body.appendU16(4)
        body.appendU16(1)
        body.appendU16(0)
        body.appendU32(0)
        body.append(contentsOf: [UInt8](repeating: 0x11, count: 16))
        body.appendU32(0)
        body.appendU16(0)
        body.appendU16(0)
        for dialect: UInt16 in [0x0202, 0x0210, 0x0300, 0x0302] {
            body.appendU16(dialect)
        }
        return body
    }

    private static func frame(_ smb: Data) -> Data {
        var out = Data([0])
        let length = smb.count
        out.append(UInt8((length >> 16) & 0xFF))
        out.append(UInt8((length >> 8) & 0xFF))
        out.append(UInt8(length & 0xFF))
        out.append(smb)
        return out
    }

    private static func tag(_ name: String, in xml: String) -> String? {
        let pattern = "<\(name)\\b[^>]*>([^<]*)</\(name)>"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return nil }
        let range = NSRange(xml.startIndex..., in: xml)
        for match in regex.matches(in: xml, range: range) {
            guard let valueRange = Range(match.range(at: 1), in: xml) else { continue }
            let value = decodeXMLEntities(String(xml[valueRange]))
            if let cleaned = cleanedText(value) { return cleaned }
        }
        return nil
    }

    /// Drops NULs, other controls and zero-width characters, which otherwise
    /// become a title SwiftUI draws as a blank line.
    static func cleanedText(_ raw: String) -> String? {
        let scalars = raw.unicodeScalars.filter { scalar in
            switch scalar.value {
            case 0x00...0x1F, 0x7F, 0xFEFF, 0x200B, 0x200C, 0x200D, 0x2060:
                return false
            default:
                return true
            }
        }
        let trimmed = String(String.UnicodeScalarView(scalars))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// A label a person would recognise: it contains a letter and it is not a placeholder.
    static func presentableName(_ raw: String) -> String? {
        guard let cleaned = cleanedText(raw), cleaned.contains(where: \.isLetter) else { return nil }
        if genericNames.contains(cleaned.lowercased()) { return nil }
        return cleaned
    }

    private static let genericNames: Set<String> = [
        "unknown", "unnamed", "device", "localhost", "android", "windows",
        "mediarenderer", "mediaserver", "basic", "internetgatewaydevice",
        "wandevice", "wanconnectiondevice",
    ]

    /// Named and numeric character references, one pass. `&amp;` is decoded
    /// with the others, so `&amp;quot;` stays as the text `&quot;`.
    static func decodeXMLEntities(_ raw: String) -> String {
        var result = ""
        result.reserveCapacity(raw.count)
        var index = raw.startIndex
        while index < raw.endIndex {
            if raw[index] == "&" {
                let rest = raw[index...]
                if let end = rest.firstIndex(of: ";"), raw.distance(from: index, to: end) <= 16,
                   let decoded = xmlEntity(raw[raw.index(after: index)..<end]) {
                    result.append(decoded)
                    index = raw.index(after: end)
                    continue
                }
                // Some TVs write the quote entity with a colon instead of a semicolon.
                if rest.hasPrefix("&quot:") {
                    result.append("\"")
                    index = raw.index(index, offsetBy: 6)
                    continue
                }
            }
            result.append(raw[index])
            index = raw.index(after: index)
        }
        return result
    }

    private static func xmlEntity(_ token: Substring) -> Character? {
        switch token.lowercased() {
        case "quot": return "\""
        case "apos": return "'"
        case "amp": return "&"
        case "lt": return "<"
        case "gt": return ">"
        default: break
        }
        let scalar: UInt32?
        if token.hasPrefix("#x") || token.hasPrefix("#X") {
            scalar = UInt32(token.dropFirst(2), radix: 16)
        } else if token.hasPrefix("#") {
            scalar = UInt32(token.dropFirst())
        } else {
            scalar = nil
        }
        guard let scalar, let unicode = Unicode.Scalar(scalar) else { return nil }
        return Character(unicode)
    }

    private static func shortDeviceType(_ urn: String) -> String {
        let parts = urn.split(separator: ":")
        guard parts.count >= 2 else { return urn }
        return String(parts[parts.count - 2])
    }

    private static func htmlTitle(_ body: String) -> String? {
        let lower = body.lowercased()
        guard let open = lower.range(of: "<title") else { return nil }
        guard let start = body[open.upperBound...].firstIndex(of: ">") else { return nil }
        let rest = body[body.index(after: start)...]
        guard let close = rest.range(of: "</", options: .caseInsensitive) else { return nil }
        let title = decodeXMLEntities(String(rest[..<close.lowerBound]))
        return clip(title.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    private static func realm(_ header: String?) -> String? {
        guard let header else { return nil }
        guard let start = header.range(of: "realm=\"", options: .caseInsensitive) else { return nil }
        let rest = header[start.upperBound...]
        guard let end = rest.firstIndex(of: "\"") else { return nil }
        return clip(String(rest[..<end]))
    }

    private static func clip(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(120))
    }

    static func find(_ needle: [UInt8], in bytes: [UInt8]) -> Int? {
        guard !needle.isEmpty, bytes.count >= needle.count else { return nil }
        let last = bytes.count - needle.count
        for index in 0...last where bytes[index..<(index + needle.count)].elementsEqual(needle) {
            return index
        }
        return nil
    }

    private static func utf16(_ text: String) -> Data {
        var data = Data()
        for unit in text.utf16 {
            data.append(UInt8(unit & 0xFF))
            data.append(UInt8((unit >> 8) & 0xFF))
        }
        return data
    }

    private static func utf16LE(_ bytes: ArraySlice<UInt8>) -> String? {
        let data = Data(bytes)
        let text = String(data: data, encoding: .utf16LittleEndian)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return (text?.isEmpty == false) ? text : nil
    }
}

private extension Data {
    mutating func appendU16(_ value: UInt16) {
        append(UInt8(value & 0xFF))
        append(UInt8(value >> 8))
    }

    mutating func appendU32(_ value: UInt32) {
        appendU16(UInt16(value & 0xFFFF))
        appendU16(UInt16(value >> 16))
    }

    mutating func appendU64(_ value: UInt64) {
        appendU32(UInt32(value & 0xFFFF_FFFF))
        appendU32(UInt32(value >> 32))
    }
}

/// A Bonjour service a host advertised, including the model string from its TXT record.
nonisolated struct ServiceAdvertisement: Equatable, Sendable {
    var type: String
    var name: String
    var model: String?

    /// A short name for the service type, for the host card.
    var kind: String {
        let folded = type.lowercased()
        if folded.contains("apple-mobdev") { return "iPhone sync" }
        if folded.contains("companion-link") { return "Continuity" }
        if folded.contains("device-info") { return "Device info" }
        if folded.contains("airplay") { return "AirPlay" }
        if folded.contains("raop") { return "AirPlay audio" }
        if folded.contains("googlecast") { return "Google Cast" }
        if folded.contains("hap") { return "HomeKit" }
        if folded.contains("spotify") { return "Spotify" }
        if folded.contains("ipp") || folded.contains("printer") { return "Printer" }
        if folded.contains("smb") { return "SMB" }
        if folded.contains("ssh") { return "SSH" }
        if folded.contains("http") { return "Web" }
        return folded
    }
}

/// Maker, operating system and model learned from names, Bonjour and open ports.
nonisolated struct HostIdentity: Equatable, Sendable {
    var manufacturer: String?
    var operatingSystem: String?
    var model: String?

    var label: String {
        [manufacturer, operatingSystem, model].compactMap { $0 }.joined(separator: " · ")
    }

    static func recognize(
        names: [String],
        services: [ServiceAdvertisement] = [],
        ports: [Int] = [],
        vendor: String? = nil,
        upnpName: String? = nil,
        upnpModel: String? = nil,
        upnpMaker: String? = nil,
        upnpType: String? = nil
    ) -> HostIdentity {
        var found = HostIdentity()
        let texts = names + services.map(\.name) + services.compactMap(\.model) + [upnpName, upnpModel].compactMap { $0 }
        for text in texts {
            if let apple = appleIdentity(in: text) {
                found.apply(apple, overwrite: true)
            }
        }
        for text in texts {
            if let named = namedIdentity(in: text) {
                found.apply(named, overwrite: false)
            }
        }
        if let upnpMaker {
            found.apply(upnpHint(maker: upnpMaker, type: upnpType, name: upnpName), overwrite: false)
        }
        let types = services.map { $0.type.lowercased() }
        // AirPlay and RAOP are licensed widely (Samsung TVs, Eversolo). Only
        // Apple's own discovery services identify the maker.
        if types.contains(where: { $0.contains("device-info") || $0.contains("companion-link") }) {
            found.apply(HostIdentity(manufacturer: "Apple"), overwrite: false)
        }
        if types.contains(where: { $0.contains("apple-mobdev") }) {
            let ipad = texts.contains { $0.range(of: "ipad", options: .caseInsensitive) != nil }
            found.apply(
                HostIdentity(manufacturer: "Apple", operatingSystem: ipad ? "iPadOS" : "iOS", model: ipad ? "iPad" : "iPhone"),
                overwrite: false
            )
        }
        if ports.contains(62078) {
            found.apply(HostIdentity(manufacturer: "Apple", operatingSystem: "iOS", model: "iPhone"), overwrite: false)
        }
        if ports.contains(548) {
            found.apply(HostIdentity(manufacturer: "Apple", operatingSystem: "macOS"), overwrite: false)
        }
        if ports.contains(3389) || ports.contains(5357) {
            found.apply(HostIdentity(manufacturer: "Microsoft", operatingSystem: "Windows"), overwrite: false)
        }
        if found.manufacturer == nil, let vendor, let short = shortVendor(vendor) {
            found.manufacturer = short
        }
        return found
    }

    /// The phone running Spectra. Bonjour often never resolves the host back to itself.
    static func thisDevice() -> HostIdentity {
        var system = utsname()
        uname(&system)
        let machine = withUnsafePointer(to: &system.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) }
        }
        if let parsed = appleIdentity(in: machine) { return parsed }
        return HostIdentity(manufacturer: "Apple", operatingSystem: "iOS", model: "iPhone")
    }

    func fillingBlanks(from other: HostIdentity) -> HostIdentity {
        var copy = self
        copy.apply(other, overwrite: false)
        return copy
    }

    private mutating func apply(_ other: HostIdentity, overwrite: Bool) {
        if let value = other.manufacturer, manufacturer == nil || overwrite { manufacturer = value }
        if let value = other.operatingSystem, operatingSystem == nil || overwrite { operatingSystem = value }
        if let value = other.model, model == nil || (overwrite && other.model != nil) { model = value }
    }

    private static func appleIdentity(in text: String) -> HostIdentity? {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = appleID.firstMatch(in: text, range: range),
              let span = Range(match.range(at: 1), in: text) else { return nil }
        let id = String(text[span])
        let key = id.lowercased()
        if key.hasPrefix("iphone") || key.hasPrefix("ipod") {
            return HostIdentity(manufacturer: "Apple", operatingSystem: "iOS", model: appleModels[id] ?? "iPhone")
        }
        if key.hasPrefix("ipad") {
            return HostIdentity(manufacturer: "Apple", operatingSystem: "iPadOS", model: appleModels[id] ?? "iPad")
        }
        if key.hasPrefix("watch") {
            return HostIdentity(manufacturer: "Apple", operatingSystem: "watchOS", model: "Apple Watch")
        }
        if key.hasPrefix("appletv") {
            return HostIdentity(manufacturer: "Apple", operatingSystem: "tvOS", model: "Apple TV")
        }
        if key.hasPrefix("audioaccessory") {
            return HostIdentity(manufacturer: "Apple", operatingSystem: "audioOS", model: "HomePod")
        }
        return HostIdentity(manufacturer: "Apple", operatingSystem: "macOS", model: appleModels[id] ?? "Mac")
    }

    private static func namedIdentity(in text: String) -> HostIdentity? {
        let folded = text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
            .replacingOccurrences(of: "-", with: " ")
            .replacingOccurrences(of: "_", with: " ")
        if folded.contains("iphone") || folded.contains("ipod") {
            return HostIdentity(manufacturer: "Apple", operatingSystem: "iOS", model: "iPhone")
        }
        if folded.contains("ipad") {
            return HostIdentity(manufacturer: "Apple", operatingSystem: "iPadOS", model: "iPad")
        }
        if folded.contains("macbook") || folded.contains("imac") || folded.contains("mac mini") || folded.contains("mac studio") {
            return HostIdentity(manufacturer: "Apple", operatingSystem: "macOS", model: folded.contains("macbook") ? "MacBook" : "Mac")
        }
        if folded.contains("apple tv") || folded.contains("appletv") {
            return HostIdentity(manufacturer: "Apple", operatingSystem: "tvOS", model: "Apple TV")
        }
        if let generation = foldGeneration(folded) {
            let title = productTitle(text)
            let named = title?.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current).contains("fold") == true
            return HostIdentity(manufacturer: "Samsung", operatingSystem: "Android", model: named ? title : "Galaxy Z Fold\(generation)")
        }
        if folded.contains("z fold") || folded.contains("galaxy fold") || folded.contains("samsung fold") {
            let title = productTitle(text)
            let named = title?.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current).contains("fold") == true
            return HostIdentity(manufacturer: "Samsung", operatingSystem: "Android", model: named ? title : "Galaxy Z Fold")
        }
        if folded.replacingOccurrences(of: " ", with: "").range(of: #"smf7\d{2}"#, options: .regularExpression) != nil
            || folded.contains("z flip") || folded.contains("galaxy flip") {
            let title = productTitle(text)
            let named = title?.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current).contains("flip") == true
            return HostIdentity(manufacturer: "Samsung", operatingSystem: "Android", model: named ? title : "Galaxy Z Flip")
        }
        let coded = text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
        if folded.contains("galaxy") || coded.range(of: #"\bsm-[a-z]\d"#, options: .regularExpression) != nil {
            return HostIdentity(manufacturer: "Samsung", operatingSystem: "Android", model: productTitle(text))
        }
        if folded.contains("pixel") {
            return HostIdentity(manufacturer: "Google", operatingSystem: "Android", model: productTitle(text) ?? "Pixel")
        }
        if folded.contains("crystal uhd") || folded.contains("qled") || folded.contains("the frame") || folded.contains("neo qled")
            || folded.replacingOccurrences(of: " ", with: "").range(of: #"(?:ue|un|qn|qa|qe|ls)\d{2}"#, options: .regularExpression) != nil {
            return HostIdentity(manufacturer: "Samsung", operatingSystem: "Tizen", model: shortLabel(text))
        }
        if folded.contains("eversolo") {
            return HostIdentity(manufacturer: "Eversolo", model: shortLabel(text, dropping: "eversolo"))
        }
        return nil
    }

    /// Fold2 is SM-F916, Fold4 is SM-F936: the digit after F9 is one less than the generation.
    private static func foldGeneration(_ folded: String) -> Int? {
        let compact = folded.replacingOccurrences(of: " ", with: "")
        if let range = compact.range(of: #"smf9\d\d"#, options: .regularExpression) {
            let token = compact[range]
            let digit = token[token.index(token.startIndex, offsetBy: 4)]
            if let series = Int(String(digit)) { return series + 1 }
        }
        if let range = folded.range(of: #"(?:z\s*)?fold\s*\d"#, options: .regularExpression) {
            let token = folded[range]
            if let digit = token.last, let generation = Int(String(digit)) { return generation }
        }
        return nil
    }

    private static func upnpHint(maker: String, type: String?, name: String?) -> HostIdentity {
        let short = shortVendor(maker) ?? maker
        var hint = HostIdentity(manufacturer: short)
        let kind = type?.lowercased() ?? ""
        if short == "Samsung", kind.contains("mediarenderer") || kind.contains("tv") {
            hint.operatingSystem = "Tizen"
            hint.model = name.flatMap { shortLabel($0) }
        }
        return hint
    }

    /// A short label, or nil when it is only the brand or too long to be a model name.
    private static func shortLabel(_ text: String, dropping brand: String? = nil) -> String? {
        var shown = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if shown.hasSuffix(".local") { shown = String(shown.dropLast(6)) }
        shown = shown.replacingOccurrences(of: "-", with: " ").replacingOccurrences(of: "_", with: " ")
        let words = shown.split(separator: " ").map(String.init)
        guard (1...6).contains(words.count), shown.count <= 40 else { return nil }
        let joined = words.joined(separator: " ")
        if let brand, joined.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current) == brand {
            return nil
        }
        return joined
    }

    /// Keeps a short device name ("Galaxy Z Fold5") and drops a person's label ("Alex's iPhone").
    private static func productTitle(_ text: String) -> String? {
        var shown = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if shown.hasSuffix(".local") { shown = String(shown.dropLast(6)) }
        shown = shown.replacingOccurrences(of: "-", with: " ").replacingOccurrences(of: "_", with: " ")
        let words = shown.split(separator: " ").map(String.init)
        guard (1...6).contains(words.count), shown.count <= 40 else { return nil }
        let folded = shown.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
        guard folded.contains("galaxy") || folded.contains("fold") || folded.contains("flip") || folded.contains("pixel") || folded.contains("sm-") else {
            return nil
        }
        return words.joined(separator: " ")
    }

    private static func shortVendor(_ vendor: String) -> String? {
        let folded = vendor.lowercased()
        if folded.contains("apple") { return "Apple" }
        if folded.contains("samsung") { return "Samsung" }
        if folded.contains("google") { return "Google" }
        return vendor
    }

    private static let appleID = try! NSRegularExpression(
        pattern: #"(?i)\b((?:MacBookPro|MacBookAir|Macmini|MacPro|iPhone|iPad|iPod|AppleTV|AudioAccessory|iMac|Watch|Mac)\d+,\d+)\b"#
    )

    private static let appleModels: [String: String] = [
        "iPhone14,2": "iPhone 13 Pro",
        "iPhone14,3": "iPhone 13 Pro Max",
        "iPhone14,4": "iPhone 13 mini",
        "iPhone14,5": "iPhone 13",
        "iPhone14,6": "iPhone SE",
        "iPhone14,7": "iPhone 14",
        "iPhone14,8": "iPhone 14 Plus",
        "iPhone15,2": "iPhone 14 Pro",
        "iPhone15,3": "iPhone 14 Pro Max",
        "iPhone15,4": "iPhone 15",
        "iPhone15,5": "iPhone 15 Plus",
        "iPhone16,1": "iPhone 15 Pro",
        "iPhone16,2": "iPhone 15 Pro Max",
        "iPhone17,1": "iPhone 16 Pro",
        "iPhone17,2": "iPhone 16 Pro Max",
        "iPhone17,3": "iPhone 16",
        "iPhone17,4": "iPhone 16 Plus",
        "iPhone17,5": "iPhone 16e",
    ]
}

nonisolated enum HeaderText {
    struct Message {
        var start: String
        var headers: [String: String]
        var body: String
    }

    static func parse(_ text: String) -> Message {
        let split = text.range(of: "\r\n\r\n") ?? text.range(of: "\n\n")
        let head: String
        let body: String
        if let split {
            head = String(text[..<split.lowerBound])
            body = String(text[split.upperBound...])
        } else {
            head = text
            body = ""
        }
        var lines = head.split(whereSeparator: \.isNewline).map(String.init)
        let start = lines.first ?? ""
        if !lines.isEmpty { lines.removeFirst() }
        var headers: [String: String] = [:]
        for line in lines {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            if headers[key] == nil { headers[key] = value }
        }
        return Message(start: start, headers: headers, body: body)
    }
}
