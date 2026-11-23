import Foundation
import NIOCore
import NIOEmbedded
import NIOPosix
import NIOSSL
import os
import Testing
@testable import Spectra

/// Whole connections through the proxy: a TLS client speaking IP packets on one side, a real TLS server on a socket
/// on the other. Unit tests of the parts all passed while not one request on the phone was ever decrypted.
@Suite(.serialized)
struct TrafficProxyEndToEndTests {
    /// The connections are driven by blocking; a test's own thread is shared with every other test, and holding it
    /// kept Network.framework from delivering anything at all until the test gave up.
    private func onItsOwnThread(_ body: @escaping @Sendable (World) throws -> Void) async throws {
        try await withCheckedThrowingContinuation { (done: CheckedContinuation<Void, Error>) in
            Thread.detachNewThread {
                done.resume(with: Result {
                    let world = try World()
                    defer { world.close() }
                    try body(world)
                })
            }
        }
    }

    /// The origin's handshake used to wait for an answer that came on another thread and was never picked up.
    @Test(.timeLimit(.minutes(1)))
    func aRequestIsDecrypted() async throws {
        try await onItsOwnThread { world in
            let body = try world.get(host: "api.example.com", path: "/hello?bytes=20", trusting: world.ca.certificateDER)
            #expect(body.count == 20)
            let exchange = try #require(world.exchanges(of: "api.example.com").first)
            #expect(exchange.summary == "GET /hello")
            #expect(exchange.status == 200)
        }
    }

    /// Bytes in flight were only ever added up: every connection stopped after one window, about 64 KB.
    @Test(.timeLimit(.minutes(1)))
    func aLargeAnswerArrivesWhole() async throws {
        try await onItsOwnThread { world in
            let body = try world.get(host: "big.example.com", path: "/big?bytes=700000", trusting: world.ca.certificateDER)
            #expect(body.count == 700_000)
        }
    }

    /// One app refusing the certificate blocked the proxy's loop for good, and with it the phone's network.
    @Test(.timeLimit(.minutes(1)))
    func aRefusalLeavesOtherConnectionsAlone() async throws {
        try await onItsOwnThread { world in
            let stranger = try world.otherAuthority()
            do {
                _ = try world.get(host: "pinned.example.com", path: "/?bytes=5", trusting: stranger.certificateDER)
                Issue.record("a certificate from a stranger was accepted")
            } catch let failure as Phone.Failed {
                #expect(failure.reason == "TLS")
            }
            let body = try world.get(host: "open.example.com", path: "/?bytes=5", trusting: world.ca.certificateDER)
            #expect(body.count == 5)
            #expect(world.flows().contains { $0.host == "pinned.example.com" && $0.refused })
            // The host that refused goes through unread: the app sees the origin's own certificate.
            let direct = try world.get(host: "pinned.example.com", path: "/?bytes=5", trusting: world.originCA.certificateDER)
            #expect(direct.count == 5)
        }
    }

    @Test(.timeLimit(.minutes(1)))
    func connectionsThatGiveUpEarlyDoNoHarm() async throws {
        try await onItsOwnThread { world in
            for _ in 0..<20 { try world.abandon(host: "api.example.com") }
            let body = try world.get(host: "api.example.com", path: "/?bytes=5", trusting: world.ca.certificateDER)
            #expect(body.count == 5)
        }
    }
}

/// The proxy, an origin on a local port, and what the proxy published.
private nonisolated final class World: @unchecked Sendable {
    let ca: LocalCertificateAuthority
    let originCA: LocalCertificateAuthority
    private let base = FileManager.default.temporaryDirectory.appendingPathComponent("e2e-\(UUID().uuidString)")
    private let packets = OSAllocatedUnfairLock<[[UInt8]]>(initialState: [])
    private let published = OSAllocatedUnfairLock<[TrafficFlow]>(initialState: [])
    private let proxy: TrafficProxy
    private let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
    private let server: Channel
    private var nextPort = 40_000

    init() throws {
        let ca = try LocalCertificateAuthority.open(base.appendingPathComponent("ca"), guard: PlainKeyGuard())
        let originCA = try LocalCertificateAuthority.open(base.appendingPathComponent("origin"), guard: PlainKeyGuard())
        self.ca = ca
        self.originCA = originCA
        let packets = packets, published = published
        proxy = TrafficProxy(writer: { packet, _ in packets.withLock { $0.append([UInt8](packet)) } },
                             decryptTLS: { true }, certificate: { ca },
                             onFlows: { flows in published.withLock { $0 = flows } })
        // The origin's certificate follows the name asked for, as a real host's would.
        let context = try originCA.makeServerContext(for: "pinned.example.com")
        server = try ServerBootstrap(group: group)
            .childChannelInitializer { channel in
                channel.eventLoop.makeCompletedFuture {
                    try channel.pipeline.syncOperations.addHandler(NIOSSLServerHandler(context: context))
                    try channel.pipeline.syncOperations.addHandler(Origin())
                }
            }
            .bind(host: "127.0.0.1", port: 0).wait()
        let origin = server.localAddress!.port!
        proxy.redirect = { _, _ in ("127.0.0.1", origin) }
    }

    func close() {
        proxy.stop()
        try? server.close().wait()
        try? group.syncShutdownGracefully()
        try? FileManager.default.removeItem(at: base)
    }

    func otherAuthority() throws -> LocalCertificateAuthority {
        try LocalCertificateAuthority.open(base.appendingPathComponent("other"), guard: PlainKeyGuard())
    }

    func flows() -> [TrafficFlow] {
        // Published at most every 0.2 s, at the latest by the 5 s sweep.
        let deadline = Date().addingTimeInterval(7)
        var last: [TrafficFlow] = []
        repeat {
            last = published.withLock { $0 }
            Thread.sleep(forTimeInterval: 0.1)
        } while Date() < deadline
        return last
    }

    func exchanges(of host: String) -> [HttpExchange] { flows().filter { $0.host == host }.flatMap(\.exchanges) }

    func get(host: String, path: String, trusting caDER: [UInt8]) throws -> [UInt8] {
        let phone = try Phone(proxy: proxy, packets: packets, port: port(), host: host, trusting: caDER)
        return try phone.get(path)
    }

    /// Sends a ClientHello and resets the connection before any answer.
    func abandon(host: String) throws {
        let phone = try Phone(proxy: proxy, packets: packets, port: port(), host: host, trusting: ca.certificateDER)
        phone.abandon()
    }

    private func port() -> Int { nextPort += 1; return nextPort }
}

/// Answers `GET …?bytes=N` with N bytes.
private nonisolated final class Origin: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = ByteBuffer
    typealias OutboundOut = ByteBuffer
    private var text = ""

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        var buffer = unwrapInboundIn(data)
        text += buffer.readString(length: buffer.readableBytes) ?? ""
        guard text.contains("\r\n\r\n") else { return }
        let count = text.split(separator: "bytes=").dropFirst().first.flatMap { Int($0.prefix { $0.isNumber }) } ?? 0
        text = ""
        var out = context.channel.allocator.buffer(capacity: count + 200)
        out.writeString("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: \(count)\r\n\r\n")
        out.writeRepeatingByte(UInt8(ascii: "x"), count: count)
        context.writeAndFlush(wrapOutboundOut(out), promise: nil)
    }
}

/// The phone: TCP in user space, TLS over it. Everything runs on the test's own thread.
private nonisolated final class Phone {
    struct Failed: Error { let reason: String }
    private static let address: [UInt8] = [198, 18, 0, 1]
    private static let server: [UInt8] = [192, 0, 2, 1]

    private let proxy: TrafficProxy
    private let packets: OSAllocatedUnfairLock<[[UInt8]]>
    private let port: Int
    private let channel: EmbeddedChannel
    private let tap = Tap()
    private var seq: UInt32 = 1000, next: UInt32 = 0
    private var open = false, reset = false
    private var waiting: [UInt8] = []
    private var cipherIn = 0, cipherOut = 0

    init(proxy: TrafficProxy, packets: OSAllocatedUnfairLock<[[UInt8]]>, port: Int,
         host: String, trusting caDER: [UInt8]) throws {
        self.proxy = proxy; self.packets = packets; self.port = port
        channel = EmbeddedChannel()
        let context = try LocalCertificateAuthority.makeClientContext(trusting: caDER, fullVerification: false)
        try channel.pipeline.syncOperations.addHandler(try NIOSSLClientHandler(context: context, serverHostname: host))
        try channel.pipeline.syncOperations.addHandler(tap)
        channel.connect(to: try SocketAddress(ipAddress: "192.0.2.1", port: 443), promise: nil)
        emit(IPCodec.SYN)
    }

    func get(_ path: String) throws -> [UInt8] {
        var request = channel.allocator.buffer(capacity: 200)
        request.writeString("GET \(path) HTTP/1.1\r\nHost: \(port)\r\n\r\n")
        channel.writeAndFlush(request, promise: nil)
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline {
            step()
            if let body = complete() { return body }
            if tap.failed {
                step() // the alert that tells the proxy
                throw Failed(reason: "TLS")
            }
            if reset { throw Failed(reason: "reset") }
        }
        let state = proxy.backlogState
        throw Failed(reason: "timed out after \(tap.received.count) bytes: SYN answered \(open), cipher in \(cipherIn), out \(cipherOut), proxy phase \(state.phase), pending \(state.pending), links \(state.links)")
    }

    func abandon() {
        let deadline = Date().addingTimeInterval(0.15)
        while Date() < deadline { step() }
        emit(IPCodec.RST | IPCodec.ACK)
    }

    private func complete() -> [UInt8]? {
        guard let end = tap.received.firstRange(of: Data("\r\n\r\n".utf8)) else { return nil }
        let head = String(decoding: tap.received[..<end.lowerBound], as: UTF8.self)
        guard let length = head.split(separator: "Content-Length: ").last.flatMap({ Int($0.prefix { $0.isNumber }) }),
              tap.received.count - end.upperBound >= length else { return nil }
        return [UInt8](tap.received[end.upperBound...])
    }

    /// Moves what is ready in either direction.
    private func step() {
        channel.embeddedEventLoop.run()
        while let out = try? channel.readOutbound(as: ByteBuffer.self) { waiting += Array(out.readableBytesView) }
        if open {
            while !waiting.isEmpty {
                let piece = Array(waiting.prefix(1360))
                waiting.removeFirst(piece.count)
                cipherOut += piece.count
                emit(IPCodec.PSH | IPCodec.ACK, piece)
            }
        }
        let mine = packets.withLock { all -> [IPCodec.TCP] in
            var taken: [IPCodec.TCP] = []
            all.removeAll { raw in
                guard let tcp = IPCodec.parse(raw).flatMap({ IPCodec.parseTCP($0.payload) }), tcp.dstPort == port else { return false }
                taken.append(tcp)
                return true
            }
            return taken
        }
        if mine.isEmpty { Thread.sleep(forTimeInterval: 0.001); return }
        for tcp in mine { take(tcp) }
    }

    private func take(_ tcp: IPCodec.TCP) {
        if tcp.flags & IPCodec.RST != 0 { reset = true; return }
        if tcp.flags & IPCodec.SYN != 0 { next = tcp.seq &+ 1; open = true; emit(IPCodec.ACK); return }
        if !tcp.payload.isEmpty, tcp.seq == next {
            next = next &+ UInt32(tcp.payload.count)
            cipherIn += tcp.payload.count
            emit(IPCodec.ACK)
            var buffer = channel.allocator.buffer(capacity: tcp.payload.count)
            buffer.writeBytes(tcp.payload)
            _ = try? channel.writeInbound(buffer)
        }
        if tcp.flags & IPCodec.FIN != 0 { next = next &+ 1; emit(IPCodec.ACK) }
    }

    private func emit(_ flags: Int, _ payload: [UInt8] = []) {
        proxy.handle(Data(IPCodec.tcpPacket(src: Self.address, dst: Self.server, srcPort: port, dstPort: 443,
                                            seq: seq, ack: next, flags: flags, window: 65535, payload: payload,
                                            mss: flags & IPCodec.SYN != 0 ? 1360 : nil)))
        seq = seq &+ UInt32(payload.count)
        if flags & (IPCodec.SYN | IPCodec.FIN) != 0 { seq = seq &+ 1 }
    }

    private final class Tap: ChannelInboundHandler {
        typealias InboundIn = ByteBuffer
        var received = Data()
        var failed = false
        func channelRead(context: ChannelHandlerContext, data: NIOAny) {
            var buffer = unwrapInboundIn(data)
            received.append(contentsOf: buffer.readBytes(length: buffer.readableBytes) ?? [])
        }
        func errorCaught(context: ChannelHandlerContext, error: Error) { failed = true }
    }
}
