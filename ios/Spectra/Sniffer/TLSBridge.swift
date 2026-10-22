import Foundation
import NIOCore
import NIOEmbedded
import NIOSSL

/// Speaks TLS to the phone with our certificate and TLS to the origin with the
/// system trust store. The cleartext unwrapped on each side is the HTTP that
/// flows between them. Swift port of the Android `core/TlsBridge.kt`; where that
/// drives two `SSLEngine`s, this drives two memory-fed NIO `EmbeddedChannel`s,
/// which is exactly how the packet tunnel will feed it (raw bytes, no sockets).
///
/// `fromClient`/`fromServer` take ciphertext; `toClient`/`toServer` emit
/// ciphertext; `onClientPlain`/`onServerPlain` observe the cleartext.
nonisolated final class TLSBridge {
    private let loop = EmbeddedEventLoop()
    private let phoneSide: EmbeddedChannel   // TLS server, faces the phone (presents our leaf)
    private let originSide: EmbeddedChannel  // TLS client, faces the origin (validates it)

    private let onClientPlain: ([UInt8]) -> Void
    private let onServerPlain: ([UInt8]) -> Void
    private let onServerClosed: () -> Void
    private let toClient: ([UInt8]) -> Void
    private let toServer: ([UInt8]) -> Void
    private var dead = false

    init(
        host: String,
        serverContext: NIOSSLContext,
        clientContext: NIOSSLContext,
        onClientPlain: @escaping ([UInt8]) -> Void,
        onServerPlain: @escaping ([UInt8]) -> Void,
        onServerClosed: @escaping () -> Void,
        toClient: @escaping ([UInt8]) -> Void,
        toServer: @escaping ([UInt8]) -> Void
    ) throws {
        self.onClientPlain = onClientPlain
        self.onServerPlain = onServerPlain
        self.onServerClosed = onServerClosed
        self.toClient = toClient
        self.toServer = toServer

        phoneSide = EmbeddedChannel(loop: loop)
        originSide = EmbeddedChannel(loop: loop)

        // Phone side: terminate the phone's TLS, surface its request cleartext.
        try phoneSide.pipeline.syncOperations.addHandler(NIOSSLServerHandler(context: serverContext))
        try phoneSide.pipeline.syncOperations.addHandler(PlainTap(
            onData: { [weak self] in self?.clientPlain($0) },
            onClosed: { }
        ))

        // Origin side: open our own TLS to the origin, surface its response.
        let hostname = host.isEmpty || isIPLiteral(host) ? nil : host
        try originSide.pipeline.syncOperations.addHandler(
            try NIOSSLClientHandler(context: clientContext, serverHostname: hostname)
        )
        try originSide.pipeline.syncOperations.addHandler(PlainTap(
            onData: { [weak self] in self?.serverPlain($0) },
            onClosed: { [weak self] in self?.onServerClosed() }
        ))

        // Both channels must be active for the handshakes to run; the client side
        // starts its ClientHello toward the origin as soon as it is active.
        let fake = try SocketAddress(ipAddress: "10.111.222.2", port: 443)
        phoneSide.connect(to: fake, promise: nil)
        originSide.connect(to: fake, promise: nil)
        drain()
    }

    /// Ciphertext the phone sent. Decrypted to the request, re-encrypted to the origin.
    func fromClient(_ data: [UInt8]) {
        guard !dead, !data.isEmpty else { return }
        feed(phoneSide, data)
    }

    /// Ciphertext the origin sent. Decrypted to the response, re-encrypted to the phone.
    func fromServer(_ data: [UInt8]) {
        guard !dead, !data.isEmpty else { return }
        feed(originSide, data)
    }

    /// The phone closed; tell the origin.
    func clientClosed() {
        guard !dead else { return }
        originSide.close(mode: .output, promise: nil)
        drain()
    }

    /// The origin closed; tell the phone.
    func serverClosed() {
        guard !dead else { return }
        phoneSide.close(mode: .output, promise: nil)
        drain()
    }

    // MARK: Internals

    private func clientPlain(_ bytes: [UInt8]) {
        onClientPlain(bytes)
        // The forwarded copy is where Accept-Encoding stripping will go (not yet
        // ported); for now the request is forwarded unchanged to the origin.
        writeApp(originSide, bytes)
    }

    private func serverPlain(_ bytes: [UInt8]) {
        onServerPlain(bytes)
        writeApp(phoneSide, bytes)
    }

    private func feed(_ channel: EmbeddedChannel, _ data: [UInt8]) {
        var buffer = channel.allocator.buffer(capacity: data.count)
        buffer.writeBytes(data)
        do {
            try channel.writeInbound(buffer)
        } catch {
            fail()
            return
        }
        drain()
    }

    private func writeApp(_ channel: EmbeddedChannel, _ bytes: [UInt8]) {
        guard !dead else { return }
        var buffer = channel.allocator.buffer(capacity: bytes.count)
        buffer.writeBytes(bytes)
        do {
            try channel.writeOutbound(buffer)
        } catch {
            fail()
            return
        }
        drain()
    }

    /// Moves whatever ciphertext each side has produced out to the far end, until
    /// nothing more is produced. Mirrors the Android bridge's `spin()`.
    private func drain() {
        guard !dead else { return }
        var moved = true
        var guardCount = 0
        while moved && !dead && guardCount < 1024 {
            guardCount += 1
            moved = false
            while let out = try? phoneSide.readOutbound(as: ByteBuffer.self) {
                toClient(Array(buffer: out)); moved = true
            }
            while let out = try? originSide.readOutbound(as: ByteBuffer.self) {
                toServer(Array(buffer: out)); moved = true
            }
        }
    }

    private func fail() {
        if dead { return }
        dead = true
        _ = try? phoneSide.finish()
        _ = try? originSide.finish()
    }
}

/// Captures the cleartext a TLS channel unwraps, and notes close.
private nonisolated final class PlainTap: ChannelInboundHandler {
    typealias InboundIn = ByteBuffer
    private let onData: ([UInt8]) -> Void
    private let onClosed: () -> Void

    init(onData: @escaping ([UInt8]) -> Void, onClosed: @escaping () -> Void) {
        self.onData = onData
        self.onClosed = onClosed
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        var buffer = unwrapInboundIn(data)
        if let bytes = buffer.readBytes(length: buffer.readableBytes), !bytes.isEmpty {
            onData(bytes)
        }
    }

    func channelInactive(context: ChannelHandlerContext) {
        onClosed()
        context.fireChannelInactive()
    }
}

private func isIPLiteral(_ host: String) -> Bool {
    var v4 = in_addr()
    if host.contains("."), inet_pton(AF_INET, host, &v4) == 1 { return true }
    var v6 = in6_addr()
    if host.contains(":"), inet_pton(AF_INET6, host, &v6) == 1 { return true }
    return false
}

private extension Array where Element == UInt8 {
    init(buffer: ByteBuffer) {
        var copy = buffer
        self = copy.readBytes(length: copy.readableBytes) ?? []
    }
}
