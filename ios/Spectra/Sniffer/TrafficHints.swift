import Foundation

/// Names and requests visible without decrypting TLS: DNS questions/answers, the
/// TLS ClientHello server name, and cleartext HTTP request lines. Direct port of
/// the Android `core/Traffic.kt` `TrafficHints` object.
nonisolated enum TrafficHints {
    static func dnsQuestion(_ payload: [UInt8]) -> String? {
        guard payload.count >= 12 else { return nil }
        if u16(payload, 4) < 1 { return nil }
        guard let (name, _) = readName(payload, 12) else { return nil }
        return cleanName(name)
    }

    /// Host names learned from A and AAAA answers, paired with the address text.
    static func dnsAnswers(_ payload: [UInt8]) -> [(String, String)] {
        guard payload.count >= 12 else { return [] }
        if u16(payload, 2) & 0x8000 == 0 { return [] }
        var offset = 12
        for _ in 0..<u16(payload, 4) {
            guard let read = readName(payload, offset) else { return [] }
            offset = read.1 + 4
            if offset > payload.count { return [] }
        }
        var found: [(String, String)] = []
        for _ in 0..<u16(payload, 6) {
            guard let read = readName(payload, offset) else { return found }
            offset = read.1
            if offset + 10 > payload.count { return found }
            let type = u16(payload, offset)
            let length = u16(payload, offset + 8)
            let data = offset + 10
            if data + length > payload.count { return found }
            let host = cleanName(read.0)
            if let host, type == 1, length == 4 {
                found.append((host, IPCodec.text(Array(payload[data..<data + 4]))))
            } else if let host, type == 28, length == 16 {
                found.append((host, IPCodec.text(Array(payload[data..<data + 16]))))
            }
            offset = data + length
        }
        return found
    }

    /// Server name from a TLS ClientHello. Nil when the bytes are not a
    /// ClientHello or the record is still incomplete.
    static func tlsServerName(_ data: [UInt8]) -> String? {
        guard data.count >= 5, (Int(data[0]) & 0xFF) == 22 else { return nil }
        let record = u16(data, 3)
        guard data.count >= 5 + record else { return nil }
        var index = 5
        guard index < data.count, (Int(data[index]) & 0xFF) == 1 else { return nil }
        index += 4
        guard index + 34 <= data.count else { return nil }
        index += 34
        let session = Int(data[index]) & 0xFF
        index += 1 + session
        guard index + 2 <= data.count else { return nil }
        let ciphers = u16(data, index)
        index += 2 + ciphers
        guard index + 1 <= data.count else { return nil }
        let compression = Int(data[index]) & 0xFF
        index += 1 + compression
        guard index + 2 <= data.count else { return nil }
        let extensions = u16(data, index)
        index += 2
        let end = min(index + extensions, data.count)
        while index + 4 <= end {
            let type = u16(data, index)
            let length = u16(data, index + 2)
            index += 4
            guard index + length <= data.count else { return nil }
            if type == 0, length >= 5 {
                let nameLength = u16(data, index + 3)
                if nameLength > 0, index + 5 + nameLength <= data.count {
                    let bytes = Array(data[index + 5..<index + 5 + nameLength])
                    return cleanName(String(decoding: bytes, as: UTF8.self))
                }
            }
            index += length
        }
        return nil
    }

    /// True once a TLS record header is present and its body has arrived.
    static func tlsRecordComplete(_ data: [UInt8]) -> Bool {
        guard data.count >= 5, (Int(data[0]) & 0xFF) == 22 else { return false }
        return data.count >= 5 + u16(data, 3)
    }

    /// Request line and host once the header block has arrived. Empty host when absent.
    static func httpRequest(_ data: [UInt8]) -> (String, String)? {
        guard httpMethod(data) != nil else { return nil }
        let text = String(decoding: data, as: UTF8.self)
        guard text.contains("\r\n\r\n") else { return nil }
        let headerBlock = text.components(separatedBy: "\r\n\r\n")[0]
        let lines = headerBlock.components(separatedBy: "\r\n")
        guard let first = lines.first else { return nil }
        let parts = first.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 2 else { return nil }
        let host = lines.first { $0.lowercased().hasPrefix("host:") }
            .map { line -> String in
                let after = line.drop(while: { $0 != ":" }).dropFirst()
                return after.trimmingCharacters(in: .whitespaces).split(separator: ":").first.map(String.init) ?? ""
            } ?? ""
        return ("\(parts[0]) \(parts[1])", host)
    }

    /// True while the opening bytes could still grow into an HTTP request.
    static func awaitsHttp(_ data: [UInt8]) -> Bool {
        guard !data.isEmpty else { return false }
        if httpMethod(data) != nil {
            return data.count < 4096 && !String(decoding: data, as: UTF8.self).contains("\r\n\r\n")
        }
        let head = String(decoding: data.prefix(8), as: UTF8.self)
        return httpMethods.contains { $0.hasPrefix(head) }
    }

    private static let httpMethods = ["GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "PATCH ", "OPTIONS ", "CONNECT "]

    private static func httpMethod(_ data: [UInt8]) -> String? {
        httpMethods.first { method in
            data.count >= method.count && String(decoding: data.prefix(method.count), as: UTF8.self) == method
        }
    }

    private static func cleanName(_ name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
            .trimmingCharacters(in: CharacterSet(charactersIn: "."))
        guard !trimmed.isEmpty, trimmed.count <= 253 else { return nil }
        if trimmed.unicodeScalars.contains(where: { $0.value < 33 || $0.value > 126 }) { return nil }
        return trimmed
    }

    private static func readName(_ payload: [UInt8], _ start: Int) -> (String, Int)? {
        var labels: [String] = []
        var index = start
        var end = -1
        var hops = 0
        while index < payload.count && hops < 16 {
            let length = Int(payload[index]) & 0xFF
            if length == 0 {
                if end < 0 { end = index + 1 }
                return (labels.joined(separator: "."), end)
            } else if length & 0xC0 == 0xC0 {
                if index + 1 >= payload.count { return nil }
                if end < 0 { end = index + 2 }
                index = ((length & 0x3F) << 8) | (Int(payload[index + 1]) & 0xFF)
                hops += 1
            } else {
                if index + 1 + length > payload.count { return nil }
                labels.append(String(decoding: payload[index + 1..<index + 1 + length], as: UTF8.self))
                index += 1 + length
            }
        }
        return nil
    }
}
