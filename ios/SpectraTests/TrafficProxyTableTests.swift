import Foundation
import os
import Testing
@testable import Spectra

/// The proxy's connection table. It used to close a link only on an error, which UDP almost never has: every DNS
/// query left one behind, 400 of them filled the table within minutes, and from then on every new connection on
/// the phone was refused with a reset — the phone was offline until the VPN was turned off.
@Suite(.serialized)
struct TrafficProxyTableTests {
    private static let phone: [UInt8] = [198, 18, 0, 1]
    /// TEST-NET-1: nothing answers there, which is what a link that never closes on its own looks like.
    private static let server: [UInt8] = [192, 0, 2, 1]

    private final class Sink: @unchecked Sendable {
        let packets = OSAllocatedUnfairLock<[[UInt8]]>(initialState: [])
        var resets: Int { count(IPCodec.RST) }
        /// SYN-ACKs: connections accepted on the server's behalf.
        var answers: Int { count(IPCodec.SYN) }

        private func count(_ flag: Int) -> Int {
            packets.withLock { list in
                list.filter { raw in
                    guard let ip = IPCodec.parse(raw), let tcp = IPCodec.parseTCP(ip.payload) else { return false }
                    return tcp.flags & flag != 0
                }.count
            }
        }
    }

    private func makeProxy() -> (TrafficProxy, Sink) {
        let sink = Sink()
        let proxy = TrafficProxy(
            writer: { packet, _ in sink.packets.withLock { $0.append([UInt8](packet)) } },
            decryptTLS: { false }, certificate: { nil }, onFlows: { _ in }
        )
        return (proxy, sink)
    }

    private func dnsQuery(fromPort port: Int) -> Data {
        Data(IPCodec.udpPacket(src: Self.phone, dst: Self.server, srcPort: port, dstPort: 53,
                               payload: [0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0]))
    }

    private func syn(fromPort port: Int) -> Data {
        Data(IPCodec.tcpPacket(src: Self.phone, dst: Self.server, srcPort: port, dstPort: 443,
                               seq: 1000, ack: 0, flags: IPCodec.SYN, window: 65535, payload: [], mss: 1360))
    }

    @Test func aFullTableMakesRoomInsteadOfRefusing() throws {
        let (proxy, sink) = makeProxy()
        defer { proxy.stop() }
        for port in 0..<(TrafficProxy.maxLinks + 50) { proxy.handle(dnsQuery(fromPort: 20000 + port)) }
        try proxy.loop.submit {}.wait()
        #expect(try proxy.loop.submit { proxy.linkCount }.wait() == TrafficProxy.maxLinks)

        // A new connection still gets through: the oldest DNS association made way for it.
        proxy.handle(syn(fromPort: 40000))
        try proxy.loop.submit {}.wait()
        #expect(sink.resets == 0)
        #expect(try proxy.loop.submit { proxy.linkCount }.wait() == TrafficProxy.maxLinks)
    }

    @Test func idleLinksAreSweptAway() throws {
        let (proxy, _) = makeProxy()
        defer { proxy.stop() }
        for port in 0..<20 { proxy.handle(dnsQuery(fromPort: 30000 + port)) }
        proxy.handle(syn(fromPort: 41000))
        try proxy.loop.submit {}.wait()
        #expect(try proxy.loop.submit { proxy.linkCount }.wait() == 21)

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        // Not yet: everything is seconds old.
        try proxy.loop.submit { proxy.sweep(now: now) }.wait()
        #expect(try proxy.loop.submit { proxy.linkCount }.wait() == 21)

        // DNS goes after `dnsIdle`; a server that never accepted the connection after `connectTimeout`.
        let later = now + Int64((TrafficProxy.connectTimeout + 1) * 1000)
        try proxy.loop.submit { proxy.sweep(now: later) }.wait()
        #expect(try proxy.loop.submit { proxy.linkCount }.wait() == 0)
    }

    /// A server that does not answer must look that way to the app: it then tries its next address. Answered at once,
    /// an app on a network with dead IPv6 held a connection that led nowhere and never came to IPv4.
    @Test func anAppIsAnsweredOnlyAfterTheServer() throws {
        let (proxy, sink) = makeProxy()
        defer { proxy.stop() }
        proxy.handle(syn(fromPort: 42000))
        proxy.handle(syn(fromPort: 42000)) // the app's own retransmission
        Thread.sleep(forTimeInterval: 0.5)
        try proxy.loop.submit {}.wait()
        #expect(sink.answers == 0)
        #expect(try proxy.loop.submit { proxy.linkCount }.wait() == 1)
    }

    /// Nothing listens there: the app is refused at once instead of waiting for the network to change.
    @Test func aRefusedConnectionIsResetAtOnce() throws {
        let (proxy, sink) = makeProxy()
        defer { proxy.stop() }
        proxy.redirect = { _, _ in ("127.0.0.1", 1) }
        proxy.handle(syn(fromPort: 43000))
        let deadline = Date().addingTimeInterval(5)
        while sink.resets == 0, Date() < deadline { Thread.sleep(forTimeInterval: 0.02) }
        #expect(sink.resets == 1)
        #expect(sink.answers == 0)
        #expect(try proxy.loop.submit { proxy.linkCount }.wait() == 0)
    }

    @Test func theBacklogIsBounded() throws {
        let (proxy, _) = makeProxy()
        defer { proxy.stop() }
        // Hold the loop so nothing drains, then flood it.
        let gate = DispatchSemaphore(value: 0)
        proxy.loop.execute { gate.wait() }
        for port in 0..<(TrafficProxy.maxBacklog + 100) { proxy.handle(dnsQuery(fromPort: port % 60000)) }
        #expect(proxy.backlogState.pending == TrafficProxy.maxBacklog)
        gate.signal()
        try proxy.loop.submit {}.wait()
        #expect(proxy.backlogState.pending == 0)
    }

    /// A host that refused the certificate is let through after that, so the phone's apps work even when some
    /// of them pin. Decryption itself stays on: pinned system services refuse however the CA is trusted.
    @Test func refusedHostsAreLetThrough() throws {
        let proxy = TrafficProxy(writer: { _, _ in }, decryptTLS: { true }, certificate: { nil }, onFlows: { _ in })
        defer { proxy.stop() }
        try proxy.loop.submit {
            proxy.mitmVerdict(host: "pinned.example", accepted: false)
            #expect(!proxy.mitmAllowed(host: "pinned.example", sni: "pinned.example"))
            #expect(proxy.mitmAllowed(host: "other.example", sni: "other.example"))
            for i in 0..<(TrafficProxy.refusalsBeforeNotice * 2) { proxy.mitmVerdict(host: "h\(i).example", accepted: false) }
            #expect(proxy.shouldDecrypt())
        }.wait()
        // Only a notice: refusals and not a single acceptance.
        #expect(proxy.backlogState.decryptOff)

        let trusted = TrafficProxy(writer: { _, _ in }, decryptTLS: { true }, certificate: { nil }, onFlows: { _ in })
        defer { trusted.stop() }
        try trusted.loop.submit {
            trusted.mitmVerdict(host: "ok.example", accepted: true)
            for i in 0..<(TrafficProxy.refusalsBeforeNotice * 2) { trusted.mitmVerdict(host: "h\(i).example", accepted: false) }
        }.wait()
        #expect(!trusted.backlogState.decryptOff)
    }

    /// The watchdog reads the loop's heartbeat without waiting on the loop.
    @Test func theSweepBeats() throws {
        let (proxy, _) = makeProxy()
        defer { proxy.stop() }
        let before = proxy.backlogState.lastBeat
        Thread.sleep(forTimeInterval: 0.01)
        try proxy.loop.submit { proxy.sweep(now: Int64(Date().timeIntervalSince1970 * 1000)) }.wait()
        #expect(proxy.backlogState.lastBeat > before)
        #expect(proxy.backlogState.phase == "idle")
    }
}
