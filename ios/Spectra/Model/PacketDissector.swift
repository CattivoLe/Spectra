import Foundation

/// A best-effort reading of one packet: enough to summarise it in a list and to
/// count it in the stats. It is deliberately shallow — the common link, network
/// and transport layers, plus a guess at the application from well-known ports.
/// Malformed offsets fall back to a short/partial reading rather than crashing.
nonisolated struct PacketInfo: Sendable {
    var transport: Transport
    var source: String
    var destination: String
    var app: String?
    var info: String
    /// Network-layer address for the talkers table (an IP when there is one,
    /// otherwise the 802.11/Ethernet MAC), without the port.
    var netSource: String?
    var netDestination: String?
    /// The WPA/WPA2 4-way handshake message this frame carries (1…4), when it is
    /// an EAPOL-Key frame of a pairwise handshake; `nil` otherwise.
    var eapolMessage: Int? = nil
}

nonisolated enum Transport: String, Sendable, CaseIterable {
    case tcp = "TCP"
    case udp = "UDP"
    case icmp = "ICMP"
    case icmpv6 = "ICMPv6"
    case igmp = "IGMP"
    case arp = "ARP"
    case ipOther = "IP"
    case dot11Mgmt = "802.11 mgmt"
    case dot11Ctrl = "802.11 ctrl"
    case dot11Data = "802.11 data"
    case other = "Other"
}

nonisolated enum PacketDissector {
    static func dissect(linkType: LinkType, _ packet: [UInt8]) -> PacketInfo {
        switch linkType {
        case .ethernet: return ethernet(packet)
        case .ieee802_11: return dot11(packet)
        case .ieee802_11_radiotap: return radiotap(packet)
        case .raw, .ipv4, .ipv6: return rawIP(packet)
        case .null: return nullLoopback(packet)
        case .linuxSLL: return linuxSLL(packet)
        case .linuxSLL2: return linuxSLL2(packet)
        default:
            return PacketInfo(transport: .other, source: "—", destination: "—",
                              app: nil, info: linkType.name, netSource: nil, netDestination: nil)
        }
    }

    // MARK: Link layer

    private static func ethernet(_ p: [UInt8]) -> PacketInfo {
        guard p.count >= 14 else { return short() }
        let dst = mac(p, 0), src = mac(p, 6)
        var etherType = be16(p, 12)
        var l3 = 14
        while etherType == 0x8100 || etherType == 0x88A8 { // VLAN / Q-in-Q tags
            guard p.count >= l3 + 4 else { break }
            etherType = be16(p, l3 + 2)
            l3 += 4
        }
        return afterL2(etherType: etherType, p: p, l3: l3, l2src: src, l2dst: dst)
    }

    private static func linuxSLL(_ p: [UInt8]) -> PacketInfo {
        guard p.count >= 16 else { return short() }
        return afterL2(etherType: be16(p, 14), p: p, l3: 16, l2src: "—", l2dst: "—")
    }

    private static func linuxSLL2(_ p: [UInt8]) -> PacketInfo {
        guard p.count >= 20 else { return short() }
        return afterL2(etherType: be16(p, 0), p: p, l3: 20, l2src: "—", l2dst: "—")
    }

    private static func nullLoopback(_ p: [UInt8]) -> PacketInfo {
        guard p.count >= 4 else { return short() }
        let host = UInt32(p[0]) | UInt32(p[1]) << 8 | UInt32(p[2]) << 16 | UInt32(p[3]) << 24
        switch host {
        case 2: return ipv4(p, 4)
        case 24, 28, 30: return ipv6(p, 4)
        default:
            if be32(p, 0) == 2 { return ipv4(p, 4) }
            return rawIP(Array(p.dropFirst(4)))
        }
    }

    private static func rawIP(_ p: [UInt8]) -> PacketInfo {
        guard let first = p.first else { return short() }
        switch first >> 4 {
        case 4: return ipv4(p, 0)
        case 6: return ipv6(p, 0)
        default: return short()
        }
    }

    private static func radiotap(_ p: [UInt8]) -> PacketInfo {
        guard p.count >= 4 else { return short() }
        let length = Int(UInt16(p[2]) | UInt16(p[3]) << 8) // radiotap header length is little-endian
        guard length >= 4, length < p.count else { return short() }
        return dot11(Array(p[length...]))
    }

    private static func afterL2(etherType: UInt16, p: [UInt8], l3: Int, l2src: String, l2dst: String) -> PacketInfo {
        switch etherType {
        case 0x0800: return ipv4(p, l3)
        case 0x86DD: return ipv6(p, l3)
        case 0x0806: return arp(p, l3, l2src: l2src)
        case 0x888E: // 802.1X / EAPOL, e.g. a wired WPA key exchange
            if let eapol = eapol(p, offset: l3, src: l2src, dst: l2dst) { return eapol }
            return PacketInfo(transport: .other, source: l2src, destination: l2dst, app: "EAPOL",
                              info: "EAPOL \(l2src) → \(l2dst)", netSource: l2src, netDestination: l2dst)
        default:
            return PacketInfo(transport: .other, source: l2src, destination: l2dst, app: nil,
                              info: String(format: "EtherType 0x%04X", etherType),
                              netSource: nil, netDestination: nil)
        }
    }

    // MARK: 802.11

    private static func dot11(_ p: [UInt8]) -> PacketInfo {
        guard p.count >= 2 else { return short() }
        let frameControl = p[0]
        let type = (frameControl >> 2) & 0x3
        let subtype = (frameControl >> 4) & 0xF
        let addr1 = p.count >= 10 ? mac(p, 4) : "—"  // receiver
        let addr2 = p.count >= 16 ? mac(p, 10) : "—" // transmitter
        switch type {
        case 0:
            let name = dot11MgmtName(subtype)
            return PacketInfo(transport: .dot11Mgmt, source: addr2, destination: addr1, app: name,
                              info: "802.11 \(name) \(addr2) → \(addr1)", netSource: addr2, netDestination: addr1)
        case 1:
            let name = dot11CtrlName(subtype)
            return PacketInfo(transport: .dot11Ctrl, source: addr2, destination: addr1, app: name,
                              info: "802.11 \(name)", netSource: nil, netDestination: nil)
        case 2:
            // A data frame may carry an EAPOL key exchange (the WPA handshake). The
            // EAPOL-Key frames are not encrypted at the link layer, so the SNAP
            // header that precedes them is in the clear and can be spotted.
            if let eapol = eapolInDataFrame(p, addr2: addr2, addr1: addr1) { return eapol }
            return PacketInfo(transport: .dot11Data, source: addr2, destination: addr1, app: nil,
                              info: "802.11 data \(addr2) → \(addr1)", netSource: addr2, netDestination: addr1)
        default:
            return PacketInfo(transport: .other, source: addr2, destination: addr1, app: nil,
                              info: "802.11", netSource: nil, netDestination: nil)
        }
    }

    private static func dot11MgmtName(_ subtype: UInt8) -> String {
        switch subtype {
        case 0: "assoc req"
        case 1: "assoc resp"
        case 2: "reassoc req"
        case 3: "reassoc resp"
        case 4: "probe req"
        case 5: "probe resp"
        case 8: "beacon"
        case 9: "ATIM"
        case 10: "disassoc"
        case 11: "auth"
        case 12: "deauth"
        case 13: "action"
        default: "mgmt \(subtype)"
        }
    }

    private static func dot11CtrlName(_ subtype: UInt8) -> String {
        switch subtype {
        case 8: "block ack req"
        case 9: "block ack"
        case 10: "PS-poll"
        case 11: "RTS"
        case 12: "CTS"
        case 13: "ACK"
        case 14: "CF-end"
        default: "ctrl \(subtype)"
        }
    }

    // MARK: EAPOL (WPA handshake)

    /// An 802.11 data frame may carry an EAPOL key exchange under an LLC/SNAP
    /// header. The SNAP position shifts with QoS and the address count, and the
    /// handshake frames are not link-encrypted, so a short scan for the SNAP
    /// signature finds it without walking every header variant.
    private static func eapolInDataFrame(_ p: [UInt8], addr2: String, addr1: String) -> PacketInfo? {
        let snap: [UInt8] = [0xAA, 0xAA, 0x03, 0x00, 0x00, 0x00, 0x88, 0x8E]
        guard let snapAt = indexOf(snap, in: p, searchLimit: 48) else { return nil }
        guard let found = classifyEapol(p, at: snapAt + snap.count) else { return nil }
        return PacketInfo(transport: .dot11Data, source: addr2, destination: addr1, app: "EAPOL",
                          info: "802.11 \(found.label) \(addr2) → \(addr1)",
                          netSource: addr2, netDestination: addr1, eapolMessage: found.message)
    }

    /// EAPOL carried straight over Ethernet/SLL (EtherType 0x888E), e.g. a wired
    /// 802.1X exchange.
    private static func eapol(_ p: [UInt8], offset: Int, src: String, dst: String) -> PacketInfo? {
        guard let found = classifyEapol(p, at: offset) else { return nil }
        return PacketInfo(transport: .other, source: src, destination: dst, app: "EAPOL",
                          info: "\(found.label) \(src) → \(dst)",
                          netSource: src, netDestination: dst, eapolMessage: found.message)
    }

    /// Reads the 802.1X header at `offset` and, for an EAPOL-Key frame of a
    /// pairwise (4-way) handshake, says which message it is (1…4). Returns `nil`
    /// when the bytes are not a readable 802.1X frame.
    private static func classifyEapol(_ p: [UInt8], at offset: Int) -> (message: Int?, label: String)? {
        // 802.1X header: version(1) type(1) length(2).
        guard offset >= 0, offset + 4 <= p.count else { return nil }
        guard p[offset + 1] == 0x03 else {
            // A non-Key 802.1X packet (EAP, EAPOL-Start, Logoff): no handshake number.
            return (nil, "EAPOL")
        }
        // EAPOL-Key body: descriptor type(1) then the 16-bit Key Information field.
        guard offset + 7 <= p.count else { return (nil, "EAPOL-Key") }
        let keyInfo = be16(p, offset + 5)
        let pairwise = (keyInfo & 0x0008) != 0 // bit 3: 1 = pairwise, 0 = group
        let ack      = (keyInfo & 0x0080) != 0 // bit 7: Key ACK
        let mic      = (keyInfo & 0x0100) != 0 // bit 8: Key MIC
        let secure   = (keyInfo & 0x0200) != 0 // bit 9: Secure
        guard pairwise else { return (nil, "EAPOL-Key (group)") }
        let message: Int
        if ack && !mic { message = 1 }               // M1: ACK, no MIC
        else if ack && mic { message = 3 }            // M3: ACK + MIC (+ Install/Secure)
        else if !ack && mic && !secure { message = 2 } // M2: MIC, not yet secure
        else if !ack && mic && secure { message = 4 }  // M4: MIC, secure
        else { return (nil, "EAPOL-Key") }
        return (message, "EAPOL M\(message)")
    }

    /// First index of `needle` within the first `searchLimit` bytes of `haystack`.
    private static func indexOf(_ needle: [UInt8], in haystack: [UInt8], searchLimit: Int) -> Int? {
        guard !needle.isEmpty, haystack.count >= needle.count else { return nil }
        let last = min(haystack.count - needle.count, searchLimit)
        guard last >= 0 else { return nil }
        var i = 0
        while i <= last {
            var j = 0
            while j < needle.count && haystack[i + j] == needle[j] { j += 1 }
            if j == needle.count { return i }
            i += 1
        }
        return nil
    }

    // MARK: Network layer

    private static func ipv4(_ p: [UInt8], _ o: Int) -> PacketInfo {
        guard p.count >= o + 20 else { return short() }
        let ihl = Int(p[o] & 0x0F) * 4
        let proto = p[o + 9]
        let src = ipv4Addr(p, o + 12), dst = ipv4Addr(p, o + 16)
        return afterL3(proto: proto, p: p, l4: o + max(ihl, 20), src: src, dst: dst)
    }

    private static func ipv6(_ p: [UInt8], _ o: Int) -> PacketInfo {
        guard p.count >= o + 40 else { return short() }
        // Extension headers are not walked; ports past them are left approximate.
        return afterL3(proto: p[o + 6], p: p, l4: o + 40, src: ipv6Addr(p, o + 8), dst: ipv6Addr(p, o + 24))
    }

    private static func afterL3(proto: UInt8, p: [UInt8], l4: Int, src: String, dst: String) -> PacketInfo {
        switch proto {
        case 6: return ports(.tcp, p, l4, src: src, dst: dst)
        case 17: return ports(.udp, p, l4, src: src, dst: dst)
        case 1: return simple(.icmp, "ICMP \(src) → \(dst)", src: src, dst: dst)
        case 2: return simple(.igmp, "IGMP \(src) → \(dst)", src: src, dst: dst)
        case 58: return simple(.icmpv6, "ICMPv6 \(src) → \(dst)", src: src, dst: dst)
        default: return simple(.ipOther, "IP proto \(proto) \(src) → \(dst)", src: src, dst: dst)
        }
    }

    private static func arp(_ p: [UInt8], _ o: Int, l2src: String) -> PacketInfo {
        guard p.count >= o + 28 else { return short() }
        let op = be16(p, o + 6)
        let sender = ipv4Addr(p, o + 14), target = ipv4Addr(p, o + 24)
        let verb = switch op {
        case 1: "who has \(target)? tell \(sender)"
        case 2: "\(sender) is at \(l2src)"
        default: "op \(op)"
        }
        return PacketInfo(transport: .arp, source: sender, destination: target, app: "ARP",
                          info: "ARP \(verb)", netSource: sender, netDestination: target)
    }

    // MARK: Transport layer

    private static func ports(_ transport: Transport, _ p: [UInt8], _ o: Int, src: String, dst: String) -> PacketInfo {
        guard p.count >= o + 4 else {
            return simple(transport, "\(transport.rawValue) \(src) → \(dst)", src: src, dst: dst)
        }
        let sp = Int(be16(p, o)), dp = Int(be16(p, o + 2))
        let app = appName(sp, dp, udp: transport == .udp)
        let suffix = app.map { " [\($0)]" } ?? ""
        return PacketInfo(
            transport: transport,
            source: "\(src):\(sp)", destination: "\(dst):\(dp)", app: app,
            info: "\(transport.rawValue) \(src):\(sp) → \(dst):\(dp)\(suffix)",
            netSource: src, netDestination: dst
        )
    }

    /// Guesses the application from the well-known side of the port pair.
    private static func appName(_ a: Int, _ b: Int, udp: Bool) -> String? {
        func has(_ port: Int) -> Bool { a == port || b == port }
        if has(443) { return udp ? "QUIC/HTTP3" : "HTTPS/TLS" }
        if has(80) || has(8080) { return "HTTP" }
        if has(53) { return "DNS" }
        if udp && (has(67) || has(68)) { return "DHCP" }
        if has(5353) { return "mDNS" }
        if udp && has(1900) { return "SSDP" }
        if has(123) { return "NTP" }
        if has(22) { return "SSH" }
        if has(445) { return "SMB" }
        if has(139) || has(137) || has(138) { return "NetBIOS" }
        if has(993) { return "IMAPS" }
        if has(995) { return "POP3S" }
        if has(25) || has(465) || has(587) { return "SMTP" }
        if has(3478) || has(3479) { return "STUN/TURN" }
        if has(51820) { return "WireGuard" }
        if has(1883) || has(8883) { return "MQTT" }
        if has(554) { return "RTSP" }
        if has(5060) || has(5061) { return "SIP" }
        if has(5228) { return "Google services" }
        if has(3306) { return "MySQL" }
        if has(5432) { return "PostgreSQL" }
        if has(6379) { return "Redis" }
        return nil
    }

    // MARK: Helpers

    private static func simple(_ transport: Transport, _ info: String, src: String, dst: String) -> PacketInfo {
        PacketInfo(transport: transport, source: src, destination: dst, app: nil, info: info,
                   netSource: src, netDestination: dst)
    }

    private static func short() -> PacketInfo {
        PacketInfo(transport: .other, source: "—", destination: "—", app: nil,
                   info: String(localized: "Truncated frame"), netSource: nil, netDestination: nil)
    }

    private static func be16(_ p: [UInt8], _ o: Int) -> UInt16 {
        guard o >= 0, o + 2 <= p.count else { return 0 }
        return UInt16(p[o]) << 8 | UInt16(p[o + 1])
    }

    private static func be32(_ p: [UInt8], _ o: Int) -> UInt32 {
        guard o >= 0, o + 4 <= p.count else { return 0 }
        return UInt32(p[o]) << 24 | UInt32(p[o + 1]) << 16 | UInt32(p[o + 2]) << 8 | UInt32(p[o + 3])
    }

    private static func mac(_ p: [UInt8], _ o: Int) -> String {
        guard o + 6 <= p.count else { return "—" }
        return (o..<o + 6).map { String(format: "%02X", p[$0]) }.joined(separator: ":")
    }

    private static func ipv4Addr(_ p: [UInt8], _ o: Int) -> String {
        guard o + 4 <= p.count else { return "?" }
        return "\(p[o]).\(p[o + 1]).\(p[o + 2]).\(p[o + 3])"
    }

    private static func ipv6Addr(_ p: [UInt8], _ o: Int) -> String {
        guard o + 16 <= p.count else { return "?" }
        var groups: [String] = []
        for i in stride(from: o, to: o + 16, by: 2) {
            groups.append(String(format: "%x", Int(be16(p, i))))
        }
        return groups.joined(separator: ":")
    }
}
