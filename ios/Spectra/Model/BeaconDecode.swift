import Foundation

/// A human reading of a well-known service-data beacon payload, shown above the
/// raw hex in the device card. Kept pure so it can be tested without a radio.
nonisolated struct BeaconInfo: Equatable {
    var title: String
    var detail: String?
}

nonisolated enum BeaconDecode {
    /// Decodes the service data for a UUID, or nil when the format is unknown.
    static func decode(uuid: String, data: Data) -> BeaconInfo? {
        switch BLENumbers.canonical(uuid) {
        case "FEAA": return eddystone([UInt8](data))
        case "FE2C": return fastPair([UInt8](data))
        case "FD6F": return BeaconInfo(title: String(localized: "Exposure Notification"), detail: nil)
        case "FEED", "FEEC": return BeaconInfo(title: "Tile", detail: nil)
        default: return nil
        }
    }

    // MARK: Eddystone (Google)

    private static func eddystone(_ b: [UInt8]) -> BeaconInfo? {
        guard let frame = b.first else { return nil }
        switch frame {
        case 0x00: // UID
            guard b.count >= 18 else { return nil }
            let power = Int(Int8(bitPattern: b[1]))
            let namespace = hex(b[2..<12])
            let instance = hex(b[12..<18])
            return BeaconInfo(title: "Eddystone-UID", detail: "\(namespace) · \(instance) · \(power) dBm")
        case 0x10: // URL
            guard b.count >= 3 else { return nil }
            let power = Int(Int8(bitPattern: b[1]))
            let url = eddystoneURL(scheme: b[2], body: Array(b[3...]))
            return BeaconInfo(title: "Eddystone-URL", detail: "\(url) · \(power) dBm")
        case 0x20: // TLM
            guard b.count >= 14 else { return nil }
            let mv = Int(b[2]) << 8 | Int(b[3])
            let tempRaw = Int16(bitPattern: UInt16(b[4]) << 8 | UInt16(b[5]))
            let temp = Double(tempRaw) / 256
            let adv = UInt32(b[6]) << 24 | UInt32(b[7]) << 16 | UInt32(b[8]) << 8 | UInt32(b[9])
            let sec = UInt32(b[10]) << 24 | UInt32(b[11]) << 16 | UInt32(b[12]) << 8 | UInt32(b[13])
            return BeaconInfo(
                title: "Eddystone-TLM",
                detail: String(format: "%d mV · %.1f °C · %u adv · %.0f s uptime", mv, temp, adv, Double(sec) / 10)
            )
        case 0x30:
            return BeaconInfo(title: "Eddystone-EID", detail: nil)
        default:
            return BeaconInfo(title: "Eddystone", detail: nil)
        }
    }

    private static func eddystoneURL(scheme: UInt8, body: [UInt8]) -> String {
        let prefix: String
        switch scheme {
        case 0: prefix = "http://www."
        case 1: prefix = "https://www."
        case 2: prefix = "http://"
        case 3: prefix = "https://"
        default: prefix = ""
        }
        var url = prefix
        for byte in body {
            if let expansion = Self.urlSuffixes[byte] {
                url += expansion
            } else if byte >= 0x20, byte < 0x7F {
                url.unicodeScalars.append(UnicodeScalar(byte))
            }
        }
        return url
    }

    private static let urlSuffixes: [UInt8: String] = [
        0x00: ".com/", 0x01: ".org/", 0x02: ".edu/", 0x03: ".net/",
        0x04: ".info/", 0x05: ".biz/", 0x06: ".gov/",
        0x07: ".com", 0x08: ".org", 0x09: ".edu", 0x0A: ".net",
        0x0B: ".info", 0x0C: ".biz", 0x0D: ".gov",
    ]

    // MARK: Google Fast Pair

    private static func fastPair(_ b: [UInt8]) -> BeaconInfo? {
        guard !b.isEmpty else { return nil }
        if b.count == 3 {
            let model = Int(b[0]) << 16 | Int(b[1]) << 8 | Int(b[2])
            return BeaconInfo(title: "Fast Pair", detail: String(format: "model 0x%06X", model))
        }
        return BeaconInfo(title: "Fast Pair", detail: nil)
    }

    private static func hex(_ bytes: ArraySlice<UInt8>) -> String {
        bytes.map { String(format: "%02X", $0) }.joined()
    }
}
