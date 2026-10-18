import Foundation

/// Human readings of standard Bluetooth SIG characteristic values. Vendor
/// 128-bit characteristics have no published format, so `decode` returns nil
/// for them and the caller falls back to hex. Mirrors the Android `GattValue`.
nonisolated enum GATTValue {
    static func decode(uuid: String, bytes: Data) -> String? {
        guard !bytes.isEmpty else { return nil }
        let b = [UInt8](bytes)
        switch BLENumbers.canonical(uuid) {
        case "2A19": return "\(u8(b, 0))%"
        case "2A07": return "\(i8(b, 0)) dBm"
        case "2A06", "2A3F": return alertLevel(u8(b, 0))
        case "2A01": return b.count >= 2 ? appearance(u16(b, 0)) : nil
        case "2A04": return connectionParameters(b)
        case "2A1C", "2A1E": return temperature(b)
        case "2A2B": return currentTime(b)
        case "2A38": return bodyLocation(u8(b, 0))
        case "2A1D": return tempType(u8(b, 0))
        case "2A50": return pnpID(b)
        case "2A23": return b.map { String(format: "%02X", $0) }.joined(separator: ":")
        case "2A37": return heartRate(b)
        case "2A6E": return b.count >= 2 ? String(format: "%.2f °C", Double(i16(b, 0)) / 100) : nil
        case "2A6F": return b.count >= 2 ? String(format: "%.2f%%", Double(u16(b, 0)) / 100) : nil
        case "2A6D": return b.count >= 4 ? String(format: "%.1f Pa", Double(u32(b, 0)) / 10) : nil
        case "2A76": return "\(u8(b, 0))"
        case "2A00", "2A24", "2A25", "2A26", "2A27", "2A28", "2A29", "2A8A", "2A90":
            return text(bytes)
        default: return nil
        }
    }

    private static func u8(_ b: [UInt8], _ i: Int) -> Int { Int(b[i]) }
    private static func i8(_ b: [UInt8], _ i: Int) -> Int { Int(Int8(bitPattern: b[i])) }
    private static func u16(_ b: [UInt8], _ i: Int) -> Int { u8(b, i) | (u8(b, i + 1) << 8) }
    private static func i16(_ b: [UInt8], _ i: Int) -> Int { Int(Int16(bitPattern: UInt16(u16(b, i)))) }
    private static func u32(_ b: [UInt8], _ i: Int) -> UInt32 {
        UInt32(b[i]) | (UInt32(b[i + 1]) << 8) | (UInt32(b[i + 2]) << 16) | (UInt32(b[i + 3]) << 24)
    }

    private static func appearance(_ value: Int) -> String {
        let category = value & 0xFFC0
        let named: String?
        switch category {
        case 64: named = "Phone"
        case 128: named = "Computer"
        case 192: named = "Watch"
        case 256: named = "Clock"
        case 320: named = "Display"
        case 384: named = "Remote control"
        case 448: named = "Eye-glasses"
        case 512: named = "Tag"
        case 576: named = "Keyring"
        case 640: named = "Media player"
        case 704: named = "Barcode scanner"
        case 768: named = "Thermometer"
        case 832: named = "Heart rate sensor"
        case 896: named = "Blood pressure"
        case 960: named = "Human interface device"
        case 1024: named = "Glucose meter"
        case 1088: named = "Running sensor"
        case 1152: named = "Cycling"
        case 1216: named = "Control device"
        case 1280: named = "Network device"
        case 1344: named = "Sensor"
        case 1408: named = "Light"
        case 1472: named = "Fan"
        case 1536: named = "HVAC"
        case 1600: named = "Air conditioning"
        case 1664: named = "Humidifier"
        case 1728: named = "Heating"
        case 1792: named = "Access control"
        case 1856: named = "Motorized device"
        case 1920: named = "Power device"
        case 1984: named = "Light source"
        case 2048: named = "Window covering"
        case 2112: named = "Audio sink"
        case 2176: named = "Audio source"
        case 2240: named = "Motorized vehicle"
        case 2304: named = "Domestic appliance"
        case 2368: named = "Wearable audio"
        case 2432: named = "Aircraft"
        case 2496: named = "AV equipment"
        case 2560: named = "Display equipment"
        case 2624: named = "Hearing aid"
        case 2688: named = "Gaming"
        case 2752: named = "Signage"
        case 2816: named = "Pulse oximeter"
        case 2880: named = "Weight scale"
        case 2944: named = "Personal mobility"
        case 3008: named = "Continuous glucose monitor"
        case 3072: named = "Insulin pump"
        case 3136: named = "Medication delivery"
        case 3200: named = "Outdoor sports"
        default: named = nil
        }
        return named ?? String(format: "0x%04X", value)
    }

    /// IEEE-11073 32-bit FLOAT: 24-bit signed mantissa, 8-bit signed exponent.
    private static func temperature(_ b: [UInt8]) -> String? {
        guard b.count >= 5 else { return nil }
        let raw = u32(b, 1)
        var mantissa = Int(raw & 0xFFFFFF)
        if mantissa & 0x800000 != 0 { mantissa -= 0x1000000 }
        let exponent = Int(Int8(bitPattern: UInt8(raw >> 24)))
        if mantissa == 0x7FFFFF { return nil }
        let value = Double(mantissa) * pow(10.0, Double(exponent))
        let unit = (u8(b, 0) & 1) != 0 ? "°F" : "°C"
        return String(format: "%.2f %@", value, unit)
    }

    private static func connectionParameters(_ b: [UInt8]) -> String? {
        guard b.count >= 8 else { return nil }
        let minMs = Double(u16(b, 0)) * 1.25
        let maxMs = Double(u16(b, 2)) * 1.25
        let latency = u16(b, 4)
        let timeout = u16(b, 6) * 10
        return String(format: "%.0f–%.0f ms · latency %d · timeout %d ms", minMs, maxMs, latency, timeout)
    }

    private static func currentTime(_ b: [UInt8]) -> String? {
        guard b.count >= 7 else { return nil }
        return String(format: "%04d-%02d-%02d %02d:%02d:%02d",
                      u16(b, 0), u8(b, 2), u8(b, 3), u8(b, 4), u8(b, 5), u8(b, 6))
    }

    private static func alertLevel(_ v: Int) -> String {
        switch v {
        case 0: "No alert"
        case 1: "Mild alert"
        case 2: "High alert"
        default: "\(v)"
        }
    }

    private static func bodyLocation(_ v: Int) -> String {
        switch v {
        case 0: "Other"; case 1: "Chest"; case 2: "Wrist"; case 3: "Finger"
        case 4: "Hand"; case 5: "Ear lobe"; case 6: "Foot"
        default: "\(v)"
        }
    }

    private static func tempType(_ v: Int) -> String {
        switch v {
        case 1: "Armpit"; case 2: "Body"; case 3: "Ear"; case 4: "Finger"; case 5: "GI tract"
        case 6: "Mouth"; case 7: "Rectum"; case 8: "Toe"; case 9: "Tympanum"
        default: "\(v)"
        }
    }

    private static func pnpID(_ b: [UInt8]) -> String? {
        guard b.count >= 7 else { return nil }
        let source: String
        switch u8(b, 0) {
        case 1: source = "Bluetooth SIG"
        case 2: source = "USB-IF"
        default: source = "source \(u8(b, 0))"
        }
        let vid = u16(b, 1)
        let pid = u16(b, 3)
        let ver = u16(b, 5)
        let vidText = BLENumbers.company(id: UInt16(vid)).map { "\($0) (\(String(format: "0x%04X", vid)))" }
            ?? String(format: "0x%04X", vid)
        return String(format: "%@ · VID %@ · PID 0x%04X · v%d.%d.%d",
                      source, vidText, pid, ver >> 8, (ver >> 4) & 0xF, ver & 0xF)
    }

    private static func heartRate(_ b: [UInt8]) -> String? {
        guard b.count >= 2 else { return nil }
        let flags = u8(b, 0)
        let bpm: Int
        if flags & 1 != 0 {
            guard b.count >= 3 else { return nil }
            bpm = u16(b, 1)
        } else {
            bpm = u8(b, 1)
        }
        return "\(bpm) bpm"
    }

    private static func text(_ bytes: Data) -> String? {
        guard let s = String(data: bytes, encoding: .utf8) else { return nil }
        let trimmed = s.replacingOccurrences(of: "\u{0000}", with: "")
        guard !trimmed.isEmpty, trimmed.unicodeScalars.allSatisfy({ ($0.value >= 32 && $0.value <= 126) || $0.value > 160 }) else {
            return nil
        }
        return trimmed
    }
}
