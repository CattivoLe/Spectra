import Foundation
import Testing
@testable import Spectra

struct PcapTests {
    // MARK: Byte builders

    private func le16(_ value: UInt16) -> [UInt8] { [UInt8(value & 0xFF), UInt8(value >> 8)] }
    private func le32(_ value: UInt32) -> [UInt8] {
        [UInt8(value & 0xFF), UInt8((value >> 8) & 0xFF), UInt8((value >> 16) & 0xFF), UInt8((value >> 24) & 0xFF)]
    }
    private func be16(_ value: UInt16) -> [UInt8] { [UInt8(value >> 8), UInt8(value & 0xFF)] }

    private func ethernet(etherType: UInt16, payload: [UInt8]) -> [UInt8] {
        let dst: [UInt8] = [0x11, 0x22, 0x33, 0x44, 0x55, 0x66]
        let src: [UInt8] = [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]
        return dst + src + be16(etherType) + payload
    }

    private func ipv4(proto: UInt8, src: [UInt8], dst: [UInt8], payload: [UInt8]) -> [UInt8] {
        var header: [UInt8] = [0x45, 0x00]
        header += be16(UInt16(20 + payload.count)) // total length
        header += [0x00, 0x00, 0x00, 0x00]         // id, flags
        header += [0x40, proto, 0x00, 0x00]        // ttl, proto, checksum
        header += src + dst
        return header + payload
    }

    private func udp(srcPort: UInt16, dstPort: UInt16) -> [UInt8] {
        be16(srcPort) + be16(dstPort) + be16(8) + be16(0)
    }

    private func tcpMinimal(srcPort: UInt16, dstPort: UInt16) -> [UInt8] {
        be16(srcPort) + be16(dstPort) + [UInt8](repeating: 0, count: 16)
    }

    private func arpRequest() -> [UInt8] {
        var out: [UInt8] = []
        out += be16(1)       // htype = Ethernet
        out += be16(0x0800)  // ptype = IPv4
        out += [6, 4]        // hlen, plen
        out += be16(1)       // op = request
        out += [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]
        out += [192, 168, 1, 10] as [UInt8]
        out += [0, 0, 0, 0, 0, 0]
        out += [192, 168, 1, 1] as [UInt8]
        return out
    }

    /// Little-endian classic pcap; Ethernet link type by default, or `network` (105 = 802.11).
    private func pcapFile(_ packets: [[UInt8]], network: UInt32 = 1) -> Data {
        var out: [UInt8] = []
        out += [0xD4, 0xC3, 0xB2, 0xA1]   // magic (LE, microsecond)
        out += le16(2) + le16(4)          // version
        out += le32(0) + le32(0)          // thiszone, sigfigs
        out += le32(65535)                // snaplen
        out += le32(network)              // link type
        for (index, packet) in packets.enumerated() {
            out += le32(UInt32(1_700_000_000 + index)) // ts_sec
            out += le32(0)                              // ts_usec
            out += le32(UInt32(packet.count))           // incl_len
            out += le32(UInt32(packet.count))           // orig_len
            out += packet
        }
        return Data(out)
    }

    private func pcapngBlock(type: UInt32, body: [UInt8]) -> [UInt8] {
        let padding = (4 - body.count % 4) % 4
        let total = UInt32(12 + body.count + padding)
        return le32(type) + le32(total) + body + [UInt8](repeating: 0, count: padding) + le32(total)
    }

    private func pcapngFile(_ packets: [[UInt8]]) -> Data {
        var out: [UInt8] = []
        out += pcapngBlock(type: 0x0A0D_0D0A, body: le32(0x1A2B_3C4D) + le16(1) + le16(0) + le32(0xFFFF_FFFF) + le32(0xFFFF_FFFF))
        out += pcapngBlock(type: 0x0000_0001, body: le16(1) + le16(0) + le32(65535)) // IDB: Ethernet
        for packet in packets {
            let body = le32(0) + le32(0) + le32(0) + le32(UInt32(packet.count)) + le32(UInt32(packet.count)) + packet
            out += pcapngBlock(type: 0x0000_0006, body: body)
        }
        return Data(out)
    }

    /// An 802.1X EAPOL-Key body with the given 16-bit Key Information field. The
    /// dissector reads only as far as that field, so the rest is left off.
    private func eapolKey(keyInfo: UInt16) -> [UInt8] {
        var out: [UInt8] = [0x02, 0x03]  // version 2, packet type = Key
        out += be16(0x005F)              // body length (nominal)
        out += [0x02]                    // descriptor type = RSN
        out += be16(keyInfo)             // Key Information
        return out
    }

    /// An 802.11 data frame (24-byte MAC header) carrying EAPOL under LLC/SNAP.
    private func dot11EapolData(keyInfo: UInt16) -> [UInt8] {
        var frame: [UInt8] = [0x08, 0x00, 0x00, 0x00]      // FC (data), duration
        frame += [UInt8](repeating: 0x01, count: 6)        // addr1 (receiver)
        frame += [UInt8](repeating: 0x02, count: 6)        // addr2 (transmitter)
        frame += [UInt8](repeating: 0x03, count: 6)        // addr3
        frame += [0x00, 0x00]                              // sequence control
        frame += [0xAA, 0xAA, 0x03, 0x00, 0x00, 0x00, 0x88, 0x8E] // LLC/SNAP → EAPOL
        frame += eapolKey(keyInfo: keyInfo)
        return frame
    }

    // Key Information fields that identify each 4-way handshake message.
    private let keyInfoM1: UInt16 = 0x0088 // pairwise + ACK
    private let keyInfoM2: UInt16 = 0x0108 // pairwise + MIC
    private let keyInfoM3: UInt16 = 0x03C8 // pairwise + ACK + MIC + install + secure
    private let keyInfoM4: UInt16 = 0x0308 // pairwise + MIC + secure

    private func be64(_ value: UInt64) -> [UInt8] { (0..<8).reversed().map { UInt8((value >> ($0 * 8)) & 0xFF) } }

    /// An 802.11 beacon naming a network.
    private func beaconFrame(bssid: [UInt8], ssid: [UInt8]) -> [UInt8] {
        var f: [UInt8] = [0x80, 0x00, 0x00, 0x00]          // FC beacon, duration
        f += [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]          // addr1 broadcast
        f += bssid + bssid                                 // addr2, addr3
        f += [0x00, 0x00]                                  // sequence control
        f += [UInt8](repeating: 0, count: 8)               // timestamp
        f += [0x64, 0x00]                                  // beacon interval
        f += [0x01, 0x04]                                  // capability info
        f += [0x00, UInt8(ssid.count)] + ssid              // SSID element
        return f
    }

    /// A full EAPOL-Key data frame, with the fields hashcat needs.
    private func eapolKeyFrame(ap: [UInt8], sta: [UInt8], fromAP: Bool, keyInfo: UInt16,
                               replay: UInt64, nonce: [UInt8], mic: [UInt8], keyData: [UInt8] = []) -> [UInt8] {
        let addr1 = fromAP ? sta : ap   // receiver
        let addr2 = fromAP ? ap : sta   // transmitter
        var f: [UInt8] = [0x08, 0x00, 0x00, 0x00]          // FC data, duration
        f += addr1 + addr2 + ap                            // addr1, addr2, addr3 (BSSID)
        f += [0x00, 0x00]                                  // sequence control
        f += [0xAA, 0xAA, 0x03, 0x00, 0x00, 0x00, 0x88, 0x8E] // LLC/SNAP → EAPOL
        var body: [UInt8] = [0x02]                         // descriptor type (RSN)
        body += be16(keyInfo)                              // key information
        body += [0x00, 0x10]                               // key length
        body += be64(replay)                               // replay counter
        body += nonce                                      // key nonce (32)
        body += [UInt8](repeating: 0, count: 16)           // key IV
        body += [UInt8](repeating: 0, count: 8)            // key RSC
        body += [UInt8](repeating: 0, count: 8)            // reserved
        body += mic                                        // key MIC (16)
        body += be16(UInt16(keyData.count)) + keyData      // key data length + key data
        f += [0x02, 0x03] + be16(UInt16(body.count)) + body // EAPOL: version, type, length, body
        return f
    }

    private var sampleDNS: [UInt8] {
        ethernet(etherType: 0x0800, payload: ipv4(proto: 17, src: [192, 168, 1, 10], dst: [192, 168, 1, 1], payload: udp(srcPort: 50000, dstPort: 53)))
    }
    private var sampleHTTPS: [UInt8] {
        ethernet(etherType: 0x0800, payload: ipv4(proto: 6, src: [192, 168, 1, 10], dst: [93, 184, 216, 34], payload: tcpMinimal(srcPort: 50001, dstPort: 443)))
    }
    private var sampleARP: [UInt8] {
        ethernet(etherType: 0x0806, payload: arpRequest())
    }

    // MARK: Tests

    @Test func parsesClassicPcap() throws {
        let file = try PcapParser.parse(pcapFile([sampleDNS, sampleHTTPS, sampleARP]))
        #expect(file.format == .pcap)
        #expect(file.packets.count == 3)
        #expect(file.packets.first?.linkType == .ethernet)
        #expect(file.packets.first?.timestamp != nil)
    }

    @Test func parsesPcapng() throws {
        let file = try PcapParser.parse(pcapngFile([sampleDNS, sampleHTTPS]))
        #expect(file.format == .pcapng)
        #expect(file.packets.count == 2)
        #expect(file.packets.first?.linkType == .ethernet)
    }

    @Test func rejectsGarbageAndShortInput() {
        #expect(throws: PcapError.notACapture) { try PcapParser.parse(Data([0x00, 0x01, 0x02, 0x03, 0x04, 0x05])) }
        #expect(throws: PcapError.tooShort) { try PcapParser.parse(Data([0x00])) }
    }

    @Test func dissectsTransportAndApp() {
        let dns = PacketDissector.dissect(linkType: .ethernet, sampleDNS)
        #expect(dns.transport == .udp)
        #expect(dns.app == "DNS")
        #expect(dns.netSource == "192.168.1.10")

        let https = PacketDissector.dissect(linkType: .ethernet, sampleHTTPS)
        #expect(https.transport == .tcp)
        #expect(https.app == "HTTPS/TLS")
        #expect(https.destination == "93.184.216.34:443")

        let arp = PacketDissector.dissect(linkType: .ethernet, sampleARP)
        #expect(arp.transport == .arp)
    }

    @Test func dissectsRadiotapBeacon() {
        // 8-byte radiotap header, then a management/beacon 802.11 frame.
        let radiotap: [UInt8] = [0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00]
        let beacon: [UInt8] = [0x80, 0x00] + [UInt8](repeating: 0, count: 22)
        let info = PacketDissector.dissect(linkType: .ieee802_11_radiotap, radiotap + beacon)
        #expect(info.transport == .dot11Mgmt)
        #expect(info.app == "beacon")
    }

    @Test func analysisAggregates() throws {
        let analysis = try PcapAnalyzer.analyze(pcapFile([sampleDNS, sampleHTTPS, sampleARP]))
        #expect(analysis.format == "pcap")
        #expect(analysis.summary.packetCount == 3)
        #expect(analysis.summary.duration != nil)
        let protocols = Dictionary(uniqueKeysWithValues: analysis.summary.protocols.map { ($0.name, $0.count) })
        #expect(protocols["TCP"] == 1)
        #expect(protocols["UDP"] == 1)
        #expect(protocols["ARP"] == 1)
        let apps = Set(analysis.summary.apps.map(\.name))
        #expect(apps.contains("DNS"))
        #expect(apps.contains("HTTPS/TLS"))
        #expect(analysis.summary.talkers.contains { $0.endpoint == "192.168.1.10" })
        // A plain IP capture carries no handshake and is not Wi-Fi.
        #expect(analysis.summary.eapolMessages.isEmpty)
        #expect(!analysis.summary.hasRadio)
    }

    @Test func classifiesEapolHandshakeMessages() {
        // Inside an 802.11 data frame, found via the LLC/SNAP marker.
        #expect(PacketDissector.dissect(linkType: .ieee802_11, dot11EapolData(keyInfo: keyInfoM1)).eapolMessage == 1)
        #expect(PacketDissector.dissect(linkType: .ieee802_11, dot11EapolData(keyInfo: keyInfoM2)).eapolMessage == 2)
        #expect(PacketDissector.dissect(linkType: .ieee802_11, dot11EapolData(keyInfo: keyInfoM3)).eapolMessage == 3)
        let m4 = PacketDissector.dissect(linkType: .ieee802_11, dot11EapolData(keyInfo: keyInfoM4))
        #expect(m4.eapolMessage == 4)
        #expect(m4.app == "EAPOL")

        // Carried straight over Ethernet (EtherType 0x888E).
        let wired = PacketDissector.dissect(linkType: .ethernet, ethernet(etherType: 0x888E, payload: eapolKey(keyInfo: keyInfoM1)))
        #expect(wired.eapolMessage == 1)

        // A group-key frame (pairwise bit clear) is EAPOL but not a 4-way message.
        let group = PacketDissector.dissect(linkType: .ieee802_11, dot11EapolData(keyInfo: 0x0380))
        #expect(group.eapolMessage == nil)

        // An ordinary data frame carries no handshake.
        let data: [UInt8] = [0x08, 0x00] + [UInt8](repeating: 0, count: 22)
        #expect(PacketDissector.dissect(linkType: .ieee802_11, data).eapolMessage == nil)
    }

    @Test func analysisTalliesHandshakeMessages() throws {
        let frames = [keyInfoM1, keyInfoM2, keyInfoM3, keyInfoM4]
            .map { ethernet(etherType: 0x888E, payload: eapolKey(keyInfo: $0)) }
        let analysis = try PcapAnalyzer.analyze(pcapFile(frames + [sampleDNS]))
        #expect(Set(analysis.summary.eapolMessages.keys) == [1, 2, 3, 4])
        #expect(analysis.summary.eapolMessages.values.allSatisfy { $0 == 1 })
    }

    @Test func exportsHc22000HandshakeAndPmkid() throws {
        let ap: [UInt8] = [0xA0, 0xB1, 0xC2, 0xD3, 0xE4, 0xF5]
        let sta: [UInt8] = [0x11, 0x22, 0x33, 0x44, 0x55, 0x66]
        let anonce = [UInt8](repeating: 0xAA, count: 32)
        let snonce = [UInt8](repeating: 0x55, count: 32)
        let mic = [UInt8](repeating: 0xCD, count: 16)
        let pmkid = [UInt8](repeating: 0x9A, count: 16)
        let pmkidKDE: [UInt8] = [0xDD, 0x14, 0x00, 0x0F, 0xAC, 0x04] + pmkid

        let beacon = beaconFrame(bssid: ap, ssid: Array("spectra".utf8))
        let m1 = eapolKeyFrame(ap: ap, sta: sta, fromAP: true, keyInfo: keyInfoM1, replay: 7, nonce: anonce, mic: [UInt8](repeating: 0, count: 16), keyData: pmkidKDE)
        let m2 = eapolKeyFrame(ap: ap, sta: sta, fromAP: false, keyInfo: keyInfoM2, replay: 7, nonce: snonce, mic: mic)

        let analysis = try PcapAnalyzer.analyze(pcapFile([beacon, m1, m2], network: 105))
        #expect(analysis.hc22000.count == 2)

        let pmkidLine = try #require(analysis.hc22000.first { $0.hasPrefix("WPA*01") })
        #expect(pmkidLine == "WPA*01*9a9a9a9a9a9a9a9a9a9a9a9a9a9a9a9a*a0b1c2d3e4f5*112233445566*73706563747261***")

        let eapol = try #require(analysis.hc22000.first { $0.hasPrefix("WPA*02") })
        let fields = eapol.components(separatedBy: "*")
        #expect(fields.count == 9)
        #expect(fields[2] == "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd")                                   // MIC
        #expect(fields[3] == "a0b1c2d3e4f5")                                                       // AP
        #expect(fields[4] == "112233445566")                                                       // STA
        #expect(fields[5] == "73706563747261")                                                     // ESSID "spectra"
        #expect(fields[6] == "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")   // ANonce (M1)
        #expect(fields[8] == "00")                                                                 // M1+M2, replay matched
        // The EAPOL blob carries the SNonce and has its own MIC field zeroed.
        #expect(fields[7].contains("5555555555555555555555555555555555555555555555555555555555555555"))
        #expect(!fields[7].contains("cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"))
    }
}

struct PacketFilterTests {
    private func row(_ index: Int, _ transport: String, _ src: String, _ dst: String, app: String? = nil, info: String = "") -> PacketRow {
        PacketRow(id: index, timestamp: nil, length: 100, transport: transport, source: src, destination: dst, info: info,
                  app: app, netSource: String(src.split(separator: ":").first ?? ""), netDestination: String(dst.split(separator: ":").first ?? ""))
    }

    @Test func filtersNarrowThePacketList() {
        let rows = [
            row(0, "TCP", "10.0.0.2:51000", "1.1.1.1:443", app: "HTTPS", info: "TCP 10.0.0.2:51000 → 1.1.1.1:443"),
            row(1, "UDP", "10.0.0.2:53000", "8.8.8.8:53", app: "DNS", info: "DNS query example.com"),
            row(2, "UDP", "10.0.0.3:53001", "8.8.8.8:53", app: "DNS", info: "DNS query github.com"),
            row(3, "ARP", "aabb", "ffff", info: "who has 10.0.0.1"),
        ]
        var filter = PacketFilter()
        #expect(!filter.isActive)
        #expect(rows.filter(filter.matches).count == 4)
        filter.toggle(transport: "UDP")
        filter.toggle(transport: "ARP")
        #expect(rows.filter(filter.matches).map(\.id) == [1, 2, 3])
        #expect(rows.filter(PacketFilter(app: "DNS").matches).map(\.id) == [1, 2])
        #expect(rows.filter(PacketFilter(query: "GITHUB").matches).map(\.id) == [2])
        // One host: either end. A conversation: exactly those two ends.
        #expect(rows.filter(PacketFilter(endpoints: ["10.0.0.2"]).matches).map(\.id) == [0, 1])
        #expect(rows.filter(PacketFilter(endpoints: ["8.8.8.8", "10.0.0.2"]).matches).map(\.id) == [1])
    }
}
