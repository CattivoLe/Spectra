import Foundation
import Testing
@testable import Spectra

/// Phase 0 spike proof for the packet layer. Ports the IP/DNS/TLS cases from the
/// Android `TrafficTest.kt` to confirm the Swift `IPCodec`/`TrafficHints` build
/// and parse identical bytes (checksums included).
struct IPCodecTests {
    @Test func tcpPacketRoundTripsAndChecksOut() {
        let src: [UInt8] = [10, 0, 0, 2]
        let dst: [UInt8] = [1, 2, 3, 4]
        let payload = Array("hello".utf8)
        let packet = IPCodec.tcpPacket(
            src: src, dst: dst, srcPort: 40000, dstPort: 443, seq: 1000, ack: 2000,
            flags: IPCodec.ACK | IPCodec.PSH, window: 65535, payload: payload, id: 7
        )
        let ip = IPCodec.parse(packet)
        #expect(ip != nil)
        #expect(ip!.version == 4)
        #expect(ip!.protocolNumber == IPCodec.tcp)
        #expect(ip!.src == src)
        #expect(ip!.dst == dst)
        let tcp = IPCodec.parseTCP(ip!.payload)
        #expect(tcp != nil)
        #expect(tcp!.srcPort == 40000)
        #expect(tcp!.dstPort == 443)
        #expect(tcp!.seq == 1000)
        #expect(tcp!.ack == 2000)
        #expect(tcp!.flags == IPCodec.ACK | IPCodec.PSH)
        #expect(tcp!.payload == payload)
        #expect(IPCodec.transportValid(ip!.src, ip!.dst, IPCodec.tcp, ip!.payload))
    }

    @Test func synAckCarriesMssAndWindowScale() {
        let src: [UInt8] = [1, 1, 1, 1]
        let dst: [UInt8] = [10, 111, 222, 1]
        let packet = IPCodec.tcpPacket(
            src: src, dst: dst, srcPort: 443, dstPort: 50000, seq: 9, ack: 2,
            flags: IPCodec.SYN | IPCodec.ACK, window: 65535, payload: [],
            mss: 1360, windowScale: 7
        )
        let tcp = IPCodec.parseTCP(IPCodec.parse(packet)!.payload)!
        #expect(tcp.windowScale == 7)
        #expect(tcp.flags == IPCodec.SYN | IPCodec.ACK)
        #expect(tcp.payload.isEmpty)
        #expect(IPCodec.transportValid(src, dst, IPCodec.tcp, IPCodec.parse(packet)!.payload))
    }

    @Test func udpPacketRoundTrips() {
        let src: [UInt8] = [8, 8, 8, 8]
        let dst: [UInt8] = [10, 0, 0, 2]
        let payload: [UInt8] = [1, 2, 3, 4, 5]
        let packet = IPCodec.udpPacket(src: src, dst: dst, srcPort: 53, dstPort: 53000, payload: payload, id: 3)
        let ip = IPCodec.parse(packet)!
        let udp = IPCodec.parseUDP(ip.payload)!
        #expect(udp.srcPort == 53)
        #expect(udp.dstPort == 53000)
        #expect(udp.payload == payload)
        #expect(IPCodec.transportValid(ip.src, ip.dst, IPCodec.udp, ip.payload))
    }

    @Test func ipv6TcpRoundTrips() {
        var src = [UInt8](repeating: 0, count: 16); src[0] = 0x20; src[15] = 1
        var dst = [UInt8](repeating: 0, count: 16); dst[0] = 0xfd; dst[15] = 2
        let packet = IPCodec.tcpPacket(
            src: src, dst: dst, srcPort: 1234, dstPort: 80, seq: 5, ack: 6,
            flags: IPCodec.ACK, window: 1000, payload: [9]
        )
        let ip = IPCodec.parse(packet)!
        #expect(ip.version == 6)
        #expect(ip.src == src)
        #expect(ip.dst == dst)
        #expect(IPCodec.parseTCP(ip.payload)!.payload.count == 1)
        #expect(IPCodec.transportValid(src, dst, IPCodec.tcp, ip.payload))
    }

    @Test func classicHeaderParses() {
        let header: [UInt8] = [
            0x45, 0x00, 0x00, 0x3c, 0x1c, 0x46, 0x40, 0x00, 0x40, 0x06,
            0xb1, 0xe6, 0xac, 0x10, 0x0a, 0x63, 0xac, 0x10, 0x0a, 0x0c,
        ] + [UInt8](repeating: 0, count: 40)
        let ip = IPCodec.parse(header)!
        #expect(IPCodec.text(ip.src) == "172.16.10.99")
        #expect(IPCodec.text(ip.dst) == "172.16.10.12")
        #expect(ip.protocolNumber == IPCodec.tcp)
    }

    @Test func fragmentsAndMulticastAreDropped() {
        let fragment: [UInt8] = [
            0x45, 0x00, 0x00, 0x14, 0x00, 0x01, 0x00, 0x08, 0x40, 0x06,
            0x00, 0x00, 10, 0, 0, 1, 1, 2, 3, 4,
        ]
        #expect(IPCodec.parse(fragment) == nil)
        #expect(!IPCodec.unicast([224, 0, 0, 1]))
        #expect(!IPCodec.unicast([255, 255, 255, 255]))
        #expect(IPCodec.unicast([8, 8, 8, 8]))
    }

    @Test func dnsQuestionAndAnswer() {
        let query: [UInt8] = [
            0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            7, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65,     // "example"
            3, 0x63, 0x6f, 0x6d, 0,                           // "com"
            0x00, 0x01, 0x00, 0x01,
        ]
        #expect(TrafficHints.dnsQuestion(query) == "example.com")
        let answer: [UInt8] = [
            0x00, 0x01, 0x81, 0x80, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            7, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65,
            3, 0x63, 0x6f, 0x6d, 0,
            0x00, 0x01, 0x00, 0x01,
            0xc0, 0x0c, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3c, 0x00, 0x04,
            1, 2, 3, 4,
        ]
        let answers = TrafficHints.dnsAnswers(answer)
        #expect(answers.count == 1)
        #expect(answers.first?.0 == "example.com")
        #expect(answers.first?.1 == "1.2.3.4")
        #expect(TrafficHints.dnsAnswers(query).isEmpty)
    }

    @Test func tlsServerNameAndCleartextHttp() {
        let name = "example.com"
        let hello = clientHello(name)
        #expect(TrafficHints.tlsServerName(hello) == name)
        #expect(TrafficHints.tlsRecordComplete(hello))
        #expect(TrafficHints.tlsServerName(Array(hello.prefix(10))) == nil)
        let http = Array("GET /index HTTP/1.1\r\nHost: example.com\r\n\r\n".utf8)
        let parsed = TrafficHints.httpRequest(http)
        #expect(parsed?.0 == "GET /index")
        #expect(parsed?.1 == "example.com")
        #expect(TrafficHints.awaitsHttp(Array("GET /ind".utf8)))
        #expect(!TrafficHints.awaitsHttp([1, 2, 3, 4, 5, 6, 7, 8]))
    }

    private func clientHello(_ host: String) -> [UInt8] {
        let name = Array(host.utf8)
        let serverName: [UInt8] =
            [0, 0, 0, UInt8(5 + name.count)]
            + [0, UInt8(3 + name.count), 0]
            + [0, UInt8(name.count)] + name
        var head = [UInt8](repeating: 0, count: 43)
        head[0] = 3; head[1] = 3
        head[35] = 0; head[36] = 2; head[38] = 0x2f; head[39] = 1
        let extLen = serverName.count
        head[41] = UInt8((extLen >> 8) & 0xFF)
        head[42] = UInt8(extLen & 0xFF)
        let body = head + serverName
        let handshake: [UInt8] = [1, 0, 0, UInt8(body.count)] + body
        return [22, 3, 3, UInt8((handshake.count >> 8) & 0xFF), UInt8(handshake.count & 0xFF)] + handshake
    }
}
