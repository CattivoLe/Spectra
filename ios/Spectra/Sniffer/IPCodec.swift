import Foundation

/// IPv4 and IPv6 packets as they come off a tunnel. Checksums are filled in
/// because the kernel drops a packet whose sum does not match. Direct port of
/// the Android `core/Traffic.kt` `IpCodec` object; on iOS the same bytes flow
/// through `NEPacketTunnelFlow.readPackets`/`writePackets`.
nonisolated enum IPCodec {
    static let tcp = 6
    static let udp = 17
    static let FIN = 0x01
    static let SYN = 0x02
    static let RST = 0x04
    static let PSH = 0x08
    static let ACK = 0x10

    struct Packet {
        let version: Int
        let protocolNumber: Int
        let src: [UInt8]
        let dst: [UInt8]
        let payload: [UInt8]
    }

    struct TCP {
        let srcPort: Int
        let dstPort: Int
        let seq: UInt32
        let ack: UInt32
        let flags: Int
        let window: Int
        let payload: [UInt8]
        let windowScale: Int?
    }

    struct UDP {
        let srcPort: Int
        let dstPort: Int
        let payload: [UInt8]
    }

    static func parse(_ packet: [UInt8]) -> Packet? {
        guard !packet.isEmpty else { return nil }
        switch (Int(packet[0]) & 0xF0) >> 4 {
        case 4: return parse4(packet)
        case 6: return parse6(packet)
        default: return nil
        }
    }

    static func parseTCP(_ segment: [UInt8]) -> TCP? {
        guard segment.count >= 20 else { return nil }
        let header = ((Int(segment[12]) & 0xF0) >> 4) * 4
        guard header >= 20, segment.count >= header else { return nil }
        return TCP(
            srcPort: u16(segment, 0),
            dstPort: u16(segment, 2),
            seq: u32(segment, 4),
            ack: u32(segment, 8),
            flags: Int(segment[13]) & 0xFF,
            window: u16(segment, 14),
            payload: Array(segment[header...]),
            windowScale: windowScale(segment, header)
        )
    }

    static func parseUDP(_ segment: [UInt8]) -> UDP? {
        guard segment.count >= 8 else { return nil }
        let length = u16(segment, 4)
        guard length >= 8, length <= segment.count else { return nil }
        return UDP(srcPort: u16(segment, 0), dstPort: u16(segment, 2), payload: Array(segment[8..<length]))
    }

    static func tcpPacket(
        src: [UInt8], dst: [UInt8], srcPort: Int, dstPort: Int,
        seq: UInt32, ack: UInt32, flags: Int, window: Int, payload: [UInt8],
        id: Int = 0, mss: Int? = nil, windowScale: Int? = nil
    ) -> [UInt8] {
        var options = 0
        if mss != nil { options += 4 }
        if windowScale != nil { options += 4 }
        let header = 20 + options
        var segment = [UInt8](repeating: 0, count: header + payload.count)
        put16(&segment, 0, srcPort)
        put16(&segment, 2, dstPort)
        put32(&segment, 4, seq)
        put32(&segment, 8, ack)
        segment[12] = UInt8((header / 4) << 4)
        segment[13] = UInt8(flags & 0xFF)
        put16(&segment, 14, window)
        var at = 20
        if let mss {
            segment[at] = 2; segment[at + 1] = 4
            put16(&segment, at + 2, mss)
            at += 4
        }
        if let windowScale {
            segment[at] = 1; segment[at + 1] = 3; segment[at + 2] = 3
            segment[at + 3] = UInt8(min(max(windowScale, 0), 14))
        }
        for (i, byte) in payload.enumerated() { segment[header + i] = byte }
        put16(&segment, 16, transportSum(src, dst, tcp, segment))
        return wrap(src, dst, tcp, segment, id)
    }

    static func udpPacket(
        src: [UInt8], dst: [UInt8], srcPort: Int, dstPort: Int, payload: [UInt8], id: Int = 0
    ) -> [UInt8] {
        var segment = [UInt8](repeating: 0, count: 8 + payload.count)
        put16(&segment, 0, srcPort)
        put16(&segment, 2, dstPort)
        put16(&segment, 4, segment.count)
        for (i, byte) in payload.enumerated() { segment[8 + i] = byte }
        var sum = transportSum(src, dst, udp, segment)
        if sum == 0 { sum = 0xFFFF }
        put16(&segment, 6, sum)
        return wrap(src, dst, udp, segment, id)
    }

    static func text(_ addr: [UInt8]) -> String {
        if addr.count == 4 {
            return addr.map { String($0) }.joined(separator: ".")
        }
        if addr.count == 16 {
            var bytes = addr
            var out = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
            let result = bytes.withUnsafeBytes { raw -> String? in
                guard inet_ntop(AF_INET6, raw.baseAddress, &out, socklen_t(INET6_ADDRSTRLEN)) != nil
                else { return nil }
                return String(cString: out)
            }
            if let result { return result }
        }
        return addr.map { String(format: "%02x", $0) }.joined(separator: ":")
    }

    static func unicast(_ addr: [UInt8]) -> Bool {
        switch addr.count {
        case 4:
            let first = Int(addr[0]) & 0xFF
            return first >= 1 && first <= 223 && first != 127
        case 16:
            return (Int(addr[0]) & 0xFF) != 0xFF
        default:
            return false
        }
    }

    /// The one's-complement sum over the segment (including its checksum) folds
    /// to 0xFFFF when the checksum is correct.
    static func transportValid(_ src: [UInt8], _ dst: [UInt8], _ protocolNumber: Int, _ segment: [UInt8]) -> Bool {
        var sum = pseudo(src, dst, protocolNumber, segment.count)
        sum = addWords(sum, segment)
        while sum >> 16 != 0 { sum = (sum & 0xFFFF) + (sum >> 16) }
        return sum == 0xFFFF
    }

    // MARK: Parsing

    private static func parse4(_ packet: [UInt8]) -> Packet? {
        guard packet.count >= 20 else { return nil }
        let header = (Int(packet[0]) & 0x0F) * 4
        let total = u16(packet, 2)
        guard header >= 20, total >= header, packet.count >= total else { return nil }
        if u16(packet, 6) & 0x1FFF != 0 { return nil }     // fragment offset
        let proto = Int(packet[9]) & 0xFF
        guard proto == tcp || proto == udp else { return nil }
        return Packet(
            version: 4, protocolNumber: proto,
            src: Array(packet[12..<16]), dst: Array(packet[16..<20]),
            payload: Array(packet[header..<total])
        )
    }

    private static func parse6(_ packet: [UInt8]) -> Packet? {
        guard packet.count >= 40 else { return nil }
        let payloadLength = u16(packet, 4)
        let total = 40 + payloadLength
        guard packet.count >= total else { return nil }
        var next = Int(packet[6]) & 0xFF
        var offset = 40
        var hops = 0
        while next == 0 || next == 43 || next == 44 || next == 60 {
            if next == 44 || offset + 2 > total { return nil }
            hops += 1
            if hops > 6 { return nil }
            let length = (Int(packet[offset + 1]) & 0xFF) * 8 + 8
            guard offset + length <= total else { return nil }
            next = Int(packet[offset]) & 0xFF
            offset += length
        }
        guard next == tcp || next == udp else { return nil }
        return Packet(
            version: 6, protocolNumber: next,
            src: Array(packet[8..<24]), dst: Array(packet[24..<40]),
            payload: Array(packet[offset..<total])
        )
    }

    private static func wrap(_ src: [UInt8], _ dst: [UInt8], _ protocolNumber: Int, _ segment: [UInt8], _ id: Int) -> [UInt8] {
        if src.count == 16 {
            var packet = [UInt8](repeating: 0, count: 40 + segment.count)
            packet[0] = 0x60
            put16(&packet, 4, segment.count)
            packet[6] = UInt8(protocolNumber)
            packet[7] = 64
            for (i, b) in src.enumerated() { packet[8 + i] = b }
            for (i, b) in dst.enumerated() { packet[24 + i] = b }
            for (i, b) in segment.enumerated() { packet[40 + i] = b }
            return packet
        } else {
            var packet = [UInt8](repeating: 0, count: 20 + segment.count)
            packet[0] = 0x45
            put16(&packet, 2, packet.count)
            put16(&packet, 4, id)
            put16(&packet, 6, 0x4000)
            packet[8] = 64
            packet[9] = UInt8(protocolNumber)
            for (i, b) in src.enumerated() { packet[12 + i] = b }
            for (i, b) in dst.enumerated() { packet[16 + i] = b }
            put16(&packet, 10, finish(addWords(0, Array(packet[0..<20]))))
            for (i, b) in segment.enumerated() { packet[20 + i] = b }
            return packet
        }
    }

    private static func windowScale(_ segment: [UInt8], _ header: Int) -> Int? {
        var index = 20
        while index < header {
            let kind = Int(segment[index]) & 0xFF
            if kind == 0 { return nil }
            if kind == 1 { index += 1; continue }
            if index + 1 >= header { return nil }
            let length = Int(segment[index + 1]) & 0xFF
            if length < 2 || index + length > header { return nil }
            if kind == 3 && length >= 3 { return Int(segment[index + 2]) & 0xFF }
            index += length
        }
        return nil
    }

    private static func transportSum(_ src: [UInt8], _ dst: [UInt8], _ protocolNumber: Int, _ segment: [UInt8]) -> Int {
        var sum = pseudo(src, dst, protocolNumber, segment.count)
        sum = addWords(sum, segment)
        return finish(sum)
    }

    private static func pseudo(_ src: [UInt8], _ dst: [UInt8], _ protocolNumber: Int, _ length: Int) -> UInt64 {
        var sum = addWords(0, src)
        sum = addWords(sum, dst)
        if src.count == 4 {
            return sum + UInt64(protocolNumber) + UInt64(length)
        } else {
            return sum + UInt64(length >> 16) + UInt64(length & 0xFFFF) + UInt64(protocolNumber)
        }
    }

    private static func addWords(_ seed: UInt64, _ data: [UInt8]) -> UInt64 {
        var sum = seed
        var index = 0
        let end = data.count
        while index + 1 < end {
            sum += UInt64((Int(data[index]) & 0xFF) << 8 | (Int(data[index + 1]) & 0xFF))
            index += 2
        }
        if index < end { sum += UInt64((Int(data[index]) & 0xFF) << 8) }
        return sum
    }

    private static func finish(_ sum: UInt64) -> Int {
        var value = sum
        while value >> 16 != 0 { value = (value & 0xFFFF) + (value >> 16) }
        return Int(~value & 0xFFFF)
    }
}

// MARK: - Shared byte helpers

func u16(_ data: [UInt8], _ offset: Int) -> Int {
    (Int(data[offset]) & 0xFF) << 8 | (Int(data[offset + 1]) & 0xFF)
}

func u32(_ data: [UInt8], _ offset: Int) -> UInt32 {
    (UInt32(data[offset]) << 24) | (UInt32(data[offset + 1]) << 16)
        | (UInt32(data[offset + 2]) << 8) | UInt32(data[offset + 3])
}

func put16(_ data: inout [UInt8], _ offset: Int, _ value: Int) {
    data[offset] = UInt8((value >> 8) & 0xFF)
    data[offset + 1] = UInt8(value & 0xFF)
}

func put32(_ data: inout [UInt8], _ offset: Int, _ value: UInt32) {
    data[offset] = UInt8((value >> 24) & 0xFF)
    data[offset + 1] = UInt8((value >> 16) & 0xFF)
    data[offset + 2] = UInt8((value >> 8) & 0xFF)
    data[offset + 3] = UInt8(value & 0xFF)
}
