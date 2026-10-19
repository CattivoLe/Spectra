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

    /// Little-endian classic pcap with Ethernet link type.
    private func pcapFile(_ packets: [[UInt8]]) -> Data {
        var out: [UInt8] = []
        out += [0xD4, 0xC3, 0xB2, 0xA1]   // magic (LE, microsecond)
        out += le16(2) + le16(4)          // version
        out += le32(0) + le32(0)          // thiszone, sigfigs
        out += le32(65535)                // snaplen
        out += le32(1)                    // network = Ethernet
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
    }
}
