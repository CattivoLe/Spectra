import Foundation
import NIOCore
import NIOEmbedded
import NIOSSL
import os
import Testing
@testable import Spectra

/// The first TLS connection with "Decrypt TLS" on. On the phone this stalled the proxy's loop on the very first
/// packet, before the MITM even started, and the tunnel's watchdog had to switch the tunnel off.
@Suite(.serialized)
struct TrafficProxyMITMTests {
    private static let phone: [UInt8] = [198, 18, 0, 1]
    private static let server: [UInt8] = [192, 0, 2, 1]

    /// A real ClientHello for `example.com`, written by NIOSSL into memory.
    private static func clientHello() throws -> [UInt8] {
        let channel = EmbeddedChannel()
        var config = TLSConfiguration.makeClientConfiguration()
        config.certificateVerification = .none
        let context = try NIOSSLContext(configuration: config)
        try channel.pipeline.syncOperations.addHandler(try NIOSSLClientHandler(context: context, serverHostname: "example.com"))
        channel.connect(to: try SocketAddress(ipAddress: "192.0.2.1", port: 443), promise: nil)
        channel.embeddedEventLoop.run()
        var bytes: [UInt8] = []
        while let out = try channel.readOutbound(as: ByteBuffer.self) { bytes += Array(out.readableBytesView) }
        _ = try? channel.finish()
        return bytes
    }

    @Test(.timeLimit(.minutes(1)))
    func theFirstDecryptedConnectionDoesNotStallTheLoop() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("mitm-ca-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let ca = try LocalCertificateAuthority.open(dir, guard: PlainKeyGuard())
        let written = OSAllocatedUnfairLock<[[UInt8]]>(initialState: [])
        let proxy = TrafficProxy(writer: { packet, _ in written.withLock { $0.append([UInt8](packet)) } },
                                 decryptTLS: { true }, certificate: { ca }, onFlows: { _ in })
        defer { proxy.stop() }

        let hello = try Self.clientHello()
        #expect(hello.first == 22)
        let port = 50123
        proxy.handle(Data(IPCodec.tcpPacket(src: Self.phone, dst: Self.server, srcPort: port, dstPort: 443,
                                            seq: 1000, ack: 0, flags: IPCodec.SYN, window: 65535, payload: [], mss: 1360)))
        try proxy.loop.submit {}.wait()
        let synAck = try #require(written.withLock { $0.last }.flatMap(IPCodec.parse).flatMap { IPCodec.parseTCP($0.payload) })
        proxy.handle(Data(IPCodec.tcpPacket(src: Self.phone, dst: Self.server, srcPort: port, dstPort: 443,
                                            seq: 1001, ack: synAck.seq &+ 1, flags: IPCodec.ACK, window: 65535, payload: [])))
        proxy.handle(Data(IPCodec.tcpPacket(src: Self.phone, dst: Self.server, srcPort: port, dstPort: 443,
                                            seq: 1001, ack: synAck.seq &+ 1, flags: IPCodec.ACK | IPCodec.PSH,
                                            window: 65535, payload: hello)))

        // The loop has to come back: wait in slices so a stall shows as a failure, not a hung test run.
        let done = proxy.loop.submit { true }
        let returned = OSAllocatedUnfairLock(initialState: false)
        done.whenSuccess { _ in returned.withLock { $0 = true } }
        let deadline = Date().addingTimeInterval(10)
        while !returned.withLock({ $0 }) && Date() < deadline { Thread.sleep(forTimeInterval: 0.05) }
        #expect(returned.withLock { $0 }, "loop stuck in phase \(proxy.backlogState.phase)")
        #expect(proxy.backlogState.pending == 0)
    }
}
