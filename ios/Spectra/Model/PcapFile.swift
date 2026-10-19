import Foundation

/// A libpcap link-layer type, as stored in a `.pcap` global header or a
/// `.pcapng` interface block. Only the ones Spectra dissects are named; the
/// rest still parse and count, they just show as "link type N".
nonisolated struct LinkType: RawRepresentable, Hashable, Sendable {
    var rawValue: UInt32

    static let null = LinkType(rawValue: 0)              // BSD loopback, 4-byte family
    static let ethernet = LinkType(rawValue: 1)
    static let raw = LinkType(rawValue: 101)             // raw IP, v4 or v6
    static let ieee802_11 = LinkType(rawValue: 105)
    static let linuxSLL = LinkType(rawValue: 113)
    static let ieee802_11_radiotap = LinkType(rawValue: 127)
    static let ipv4 = LinkType(rawValue: 228)
    static let ipv6 = LinkType(rawValue: 229)
    static let linuxSLL2 = LinkType(rawValue: 276)

    var name: String {
        switch self {
        case .null: "BSD loopback"
        case .ethernet: "Ethernet"
        case .raw, .ipv4, .ipv6: "Raw IP"
        case .ieee802_11: "802.11"
        case .ieee802_11_radiotap: "802.11 + radiotap"
        case .linuxSLL, .linuxSLL2: "Linux cooked"
        default: "link type \(rawValue)"
        }
    }
}

/// One packet record lifted out of the file. `data` is what was captured, which
/// may be shorter than `originalLength` when the capture used a snap length.
nonisolated struct CapturedPacket: Sendable {
    var timestamp: Date?
    var originalLength: Int
    var data: [UInt8]
    var linkType: LinkType
}

/// A parsed capture file: the packets and which container held them.
nonisolated struct CaptureFile: Sendable {
    enum Format: String, Sendable { case pcap, pcapng }
    var format: Format
    var packets: [CapturedPacket]
}

nonisolated enum PcapError: Error, Equatable {
    case tooShort
    case notACapture
    case truncated
}

/// Parses classic `.pcap` and the newer `.pcapng` into a flat list of packets.
/// Bounds are checked on every read, so a truncated or hostile file fails with
/// an error (or returns what parsed cleanly) rather than crashing.
nonisolated enum PcapParser {
    static func parse(_ data: Data) throws -> CaptureFile {
        let bytes = [UInt8](data)
        guard bytes.count >= 4 else { throw PcapError.tooShort }
        let first = UInt32(bytes[0]) << 24 | UInt32(bytes[1]) << 16 | UInt32(bytes[2]) << 8 | UInt32(bytes[3])
        switch first {
        case 0xA1B2_C3D4, 0xD4C3_B2A1, 0xA1B2_3C4D, 0x4D3C_B2A1:
            return try parsePcap(bytes)
        case 0x0A0D_0D0A:
            return try parsePcapng(bytes)
        default:
            throw PcapError.notACapture
        }
    }

    // MARK: Classic pcap

    private static func parsePcap(_ bytes: [UInt8]) throws -> CaptureFile {
        guard bytes.count >= 24 else { throw PcapError.tooShort }
        let magic = bytes[0...3]
        let bigEndian: Bool
        let nanos: Bool
        switch Array(magic) {
        case [0xA1, 0xB2, 0xC3, 0xD4]: bigEndian = true;  nanos = false
        case [0xD4, 0xC3, 0xB2, 0xA1]: bigEndian = false; nanos = false
        case [0xA1, 0xB2, 0x3C, 0x4D]: bigEndian = true;  nanos = true
        case [0x4D, 0x3C, 0xB2, 0xA1]: bigEndian = false; nanos = true
        default: throw PcapError.notACapture
        }
        var cur = Cursor(bytes: bytes, offset: 24, bigEndian: bigEndian)
        let linkType = LinkType(rawValue: read32(bytes, at: 20, bigEndian: bigEndian))
        let tickScale = nanos ? 1_000_000_000.0 : 1_000_000.0

        var packets: [CapturedPacket] = []
        while cur.remaining >= 16 {
            let tsSec = try cur.u32()
            let tsFrac = try cur.u32()
            let capLen = Int(try cur.u32())
            let origLen = Int(try cur.u32())
            guard capLen >= 0, cur.remaining >= capLen else { break } // truncated tail: keep what we have
            let packet = try cur.take(capLen)
            let stamp = Date(timeIntervalSince1970: Double(tsSec) + Double(tsFrac) / tickScale)
            packets.append(CapturedPacket(
                timestamp: tsSec == 0 ? nil : stamp,
                originalLength: origLen,
                data: packet,
                linkType: linkType
            ))
        }
        return CaptureFile(format: .pcap, packets: packets)
    }

    // MARK: pcapng

    private static func parsePcapng(_ bytes: [UInt8]) throws -> CaptureFile {
        var packets: [CapturedPacket] = []
        var offset = 0
        var bigEndian = false
        // Interfaces in the current section, in order; each carries a link type
        // and a timestamp resolution (ticks per second).
        var interfaces: [(link: LinkType, ticksPerSecond: Double)] = []

        while offset + 12 <= bytes.count {
            let blockType = read32(bytes, at: offset, bigEndian: bigEndian)
            // A Section Header Block resets endianness from its own magic.
            if blockType == 0x0A0D_0D0A {
                let bom = read32(bytes, at: offset + 8, bigEndian: false)
                bigEndian = (bom == 0x4D3C_2B1A)
                interfaces.removeAll()
            }
            let total = Int(read32(bytes, at: offset + 4, bigEndian: bigEndian))
            guard total >= 12, offset + total <= bytes.count else { break }
            let bodyStart = offset + 8
            let bodyEnd = offset + total - 4 // trailing total-length word

            switch blockType {
            case 0x0000_0001: // Interface Description Block
                let link = LinkType(rawValue: UInt32(read16(bytes, at: bodyStart, bigEndian: bigEndian)))
                let ticks = timestampTicks(bytes, optionsStart: bodyStart + 8, optionsEnd: bodyEnd, bigEndian: bigEndian)
                interfaces.append((link, ticks))
            case 0x0000_0006: // Enhanced Packet Block
                let interfaceID = Int(read32(bytes, at: bodyStart, bigEndian: bigEndian))
                let tsHigh = UInt64(read32(bytes, at: bodyStart + 4, bigEndian: bigEndian))
                let tsLow = UInt64(read32(bytes, at: bodyStart + 8, bigEndian: bigEndian))
                let capLen = Int(read32(bytes, at: bodyStart + 12, bigEndian: bigEndian))
                let origLen = Int(read32(bytes, at: bodyStart + 16, bigEndian: bigEndian))
                let dataStart = bodyStart + 20
                guard capLen >= 0, dataStart + capLen <= bodyEnd else { break }
                let iface = interfaces.indices.contains(interfaceID) ? interfaces[interfaceID] : nil
                let link = iface?.link ?? .ethernet
                let ticks = iface?.ticksPerSecond ?? 1_000_000
                let raw = (tsHigh << 32) | tsLow
                let stamp = raw == 0 ? nil : Date(timeIntervalSince1970: Double(raw) / ticks)
                packets.append(CapturedPacket(
                    timestamp: stamp, originalLength: origLen,
                    data: Array(bytes[dataStart..<dataStart + capLen]), linkType: link
                ))
            case 0x0000_0003: // Simple Packet Block
                let origLen = Int(read32(bytes, at: bodyStart, bigEndian: bigEndian))
                let dataStart = bodyStart + 4
                let capLen = min(origLen, bodyEnd - dataStart)
                guard capLen >= 0, dataStart + capLen <= bodyEnd else { break }
                let link = interfaces.first?.link ?? .ethernet
                packets.append(CapturedPacket(
                    timestamp: nil, originalLength: origLen,
                    data: Array(bytes[dataStart..<dataStart + capLen]), linkType: link
                ))
            default:
                break // other blocks (name resolution, stats, custom) are skipped
            }
            offset += total
        }
        guard !packets.isEmpty || !interfaces.isEmpty else { throw PcapError.notACapture }
        return CaptureFile(format: .pcapng, packets: packets)
    }

    /// Reads the `if_tsresol` option (code 9) to get ticks per second, default 1e6.
    private static func timestampTicks(_ bytes: [UInt8], optionsStart: Int, optionsEnd: Int, bigEndian: Bool) -> Double {
        var offset = optionsStart
        while offset + 4 <= optionsEnd {
            let code = read16(bytes, at: offset, bigEndian: bigEndian)
            let length = Int(read16(bytes, at: offset + 2, bigEndian: bigEndian))
            let valueStart = offset + 4
            if code == 0 { break } // end of options
            if code == 9, length >= 1, valueStart < optionsEnd {
                let raw = bytes[valueStart]
                if raw & 0x80 == 0 {
                    return pow(10.0, Double(raw))
                } else {
                    return pow(2.0, Double(raw & 0x7F))
                }
            }
            offset = valueStart + length + ((4 - (length % 4)) % 4) // 32-bit padding
        }
        return 1_000_000
    }
}

// MARK: - Bounds-checked reading

private func read16(_ bytes: [UInt8], at offset: Int, bigEndian: Bool) -> UInt16 {
    guard offset >= 0, offset + 2 <= bytes.count else { return 0 }
    let a = UInt16(bytes[offset]), b = UInt16(bytes[offset + 1])
    return bigEndian ? (a << 8 | b) : (b << 8 | a)
}

private func read32(_ bytes: [UInt8], at offset: Int, bigEndian: Bool) -> UInt32 {
    guard offset >= 0, offset + 4 <= bytes.count else { return 0 }
    let a = UInt32(bytes[offset]), b = UInt32(bytes[offset + 1])
    let c = UInt32(bytes[offset + 2]), d = UInt32(bytes[offset + 3])
    return bigEndian ? (a << 24 | b << 16 | c << 8 | d) : (d << 24 | c << 16 | b << 8 | a)
}

/// A forward cursor used for the classic pcap record loop.
private struct Cursor {
    let bytes: [UInt8]
    var offset: Int
    var bigEndian: Bool

    var remaining: Int { bytes.count - offset }

    mutating func u32() throws -> UInt32 {
        guard remaining >= 4 else { throw PcapError.truncated }
        defer { offset += 4 }
        return read32(bytes, at: offset, bigEndian: bigEndian)
    }

    mutating func take(_ count: Int) throws -> [UInt8] {
        guard count >= 0, remaining >= count else { throw PcapError.truncated }
        defer { offset += count }
        return Array(bytes[offset..<offset + count])
    }
}
