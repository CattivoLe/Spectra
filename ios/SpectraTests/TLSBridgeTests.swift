import Foundation
import Testing
import NIOCore
import NIOEmbedded
import NIOSSL
@testable import Spectra

/// Phase 1 make-or-break proof, ported from the Android `bridgeShowsCleartextHttp`
/// test. A phone (TLS client trusting our CA) talks through the `TLSBridge` to an
/// origin (TLS server with its own CA); the bridge must surface both the request
/// and the response as cleartext, proving on-device TLS MITM works end-to-end.
struct TLSBridgeTests {
    @Test func bridgeShowsCleartextHttp() throws {
        let mitmDir = tempDir(); defer { try? FileManager.default.removeItem(at: mitmDir) }
        let originDir = tempDir(); defer { try? FileManager.default.removeItem(at: originDir) }
        let mitmCA = try LocalCertificateAuthority.open(mitmDir)
        let originCA = try LocalCertificateAuthority.open(originDir)

        // Ciphertext queues between the three TLS endpoints.
        var phoneOut: [[UInt8]] = []
        var originOut: [[UInt8]] = []
        var bridgeToPhone: [[UInt8]] = []
        var bridgeToOrigin: [[UInt8]] = []

        var requestSeenAtBridge = [UInt8]()
        var responseSeenAtBridge = [UInt8]()
        var phoneReceived = [UInt8]()
        var originReceived = [UInt8]()

        let request = Array("GET /search?q=1 HTTP/1.1\r\nHost: example.com\r\n\r\n".utf8)
        let response = Array("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ok\":true}".utf8)

        let bridge = try TLSBridge(
            host: "example.com",
            serverContext: try mitmCA.makeServerContext(for: "example.com"),
            clientContext: try LocalCertificateAuthority.makeClientContext(trusting: originCA.certificateDER),
            onClientPlain: { requestSeenAtBridge += $0 },
            onServerPlain: { responseSeenAtBridge += $0 },
            onServerClosed: {},
            toClient: { bridgeToPhone.append($0) },
            toServer: { bridgeToOrigin.append($0) }
        )

        let phone = try TLSEndpoint(
            clientTrusting: mitmCA.certificateDER, serverHostname: "example.com"
        )
        phone.onOutbound = { phoneOut.append($0) }
        phone.onData = { phoneReceived += $0 }

        var sentResponse = false
        let origin = try TLSEndpoint(server: try originCA.makeServerContext(for: "example.com"))
        origin.onOutbound = { originOut.append($0) }
        origin.onData = { bytes in
            originReceived += bytes
            if !sentResponse, String(decoding: originReceived, as: UTF8.self).contains("GET /search") {
                sentResponse = true
                origin.send(response)
            }
        }

        // Phone buffers the request behind its handshake, then kicks the ClientHello out.
        phone.send(request)

        for _ in 0..<500 {
            if phoneOut.isEmpty && bridgeToOrigin.isEmpty && originOut.isEmpty && bridgeToPhone.isEmpty { break }
            while !phoneOut.isEmpty { bridge.fromClient(phoneOut.removeFirst()) }
            while !bridgeToOrigin.isEmpty { origin.feed(bridgeToOrigin.removeFirst()) }
            while !originOut.isEmpty { bridge.fromServer(originOut.removeFirst()) }
            while !bridgeToPhone.isEmpty { phone.feed(bridgeToPhone.removeFirst()) }
            if String(decoding: phoneReceived, as: UTF8.self).contains("{\"ok\":true}") { break }
        }

        #expect(String(decoding: requestSeenAtBridge, as: UTF8.self).contains("GET /search?q=1"))
        #expect(String(decoding: responseSeenAtBridge, as: UTF8.self).contains("{\"ok\":true}"))
        #expect(String(decoding: phoneReceived, as: UTF8.self).contains("{\"ok\":true}"))
        #expect(String(decoding: originReceived, as: UTF8.self).contains("GET /search?q=1"))
    }

    private func tempDir() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("spectra-bridge-\(UUID().uuidString)")
    }
}

/// Minimal memory-fed TLS endpoint for the bridge test (phone / origin).
private nonisolated final class TLSEndpoint {
    private let loop = EmbeddedEventLoop()
    private let channel: EmbeddedChannel
    private let tap: Tap
    var onOutbound: ([UInt8]) -> Void = { _ in }
    var onData: ([UInt8]) -> Void = { _ in }

    init(server context: NIOSSLContext) throws {
        channel = EmbeddedChannel(loop: loop)
        tap = Tap()
        try channel.pipeline.syncOperations.addHandler(NIOSSLServerHandler(context: context))
        try channel.pipeline.syncOperations.addHandler(tap)
        try channel.connect(to: SocketAddress(ipAddress: "10.111.222.3", port: 443)).wait()
        tap.sink = { [weak self] in self?.onData($0) }
    }

    init(clientTrusting caDER: [UInt8], serverHostname: String) throws {
        channel = EmbeddedChannel(loop: loop)
        tap = Tap()
        let context = try LocalCertificateAuthority.makeClientContext(trusting: caDER)
        try channel.pipeline.syncOperations.addHandler(try NIOSSLClientHandler(context: context, serverHostname: serverHostname))
        try channel.pipeline.syncOperations.addHandler(tap)
        try channel.connect(to: SocketAddress(ipAddress: "10.111.222.4", port: 443)).wait()
        tap.sink = { [weak self] in self?.onData($0) }
    }

    func feed(_ cipher: [UInt8]) {
        var buffer = channel.allocator.buffer(capacity: cipher.count)
        buffer.writeBytes(cipher)
        _ = try? channel.writeInbound(buffer)
        drain()
    }

    func send(_ plain: [UInt8]) {
        var buffer = channel.allocator.buffer(capacity: plain.count)
        buffer.writeBytes(plain)
        _ = try? channel.writeOutbound(buffer)
        drain()
    }

    private func drain() {
        loop.run()
        var moved = true
        var guardCount = 0
        while moved && guardCount < 1024 {
            guardCount += 1
            moved = false
            while let out = try? channel.readOutbound(as: ByteBuffer.self) {
                var copy = out
                onOutbound(copy.readBytes(length: copy.readableBytes) ?? [])
                moved = true
            }
            loop.run()
        }
    }

    private final class Tap: ChannelInboundHandler {
        typealias InboundIn = ByteBuffer
        var sink: ([UInt8]) -> Void = { _ in }
        func channelRead(context: ChannelHandlerContext, data: NIOAny) {
            var buffer = unwrapInboundIn(data)
            if let bytes = buffer.readBytes(length: buffer.readableBytes), !bytes.isEmpty { sink(bytes) }
        }
    }
}
