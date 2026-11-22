import Foundation
import NIOCore
import NIOEmbedded
import NIOSSL
import os

let snifferLog = Logger(subsystem: "cattivole.Spectra", category: "sniffer")

/// Speaks TLS to the phone with our certificate and TLS to the origin with the
/// system trust store. The cleartext unwrapped on each side is the HTTP that
/// flows between them. Swift port of the Android `core/TlsBridge.kt`; where that
/// drives two `SSLEngine`s, this drives two memory-fed NIO `EmbeddedChannel`s,
/// which is exactly how the packet tunnel will feed it (raw bytes, no sockets).
///
/// `fromClient`/`fromServer` take ciphertext; `toClient`/`toServer` emit
/// ciphertext; `onClientPlain`/`onServerPlain` observe the cleartext.
nonisolated final class TLSBridge {
    static let h2 = "h2"
    static let http1 = "http/1.1"

    private let loop = EmbeddedEventLoop()
    /// TLS server, faces the phone (presents our leaf). Made once the origin has chosen its protocol.
    private var phoneSide: EmbeddedChannel?
    private let originSide: EmbeddedChannel  // TLS client, faces the origin (validates it)
    private let phoneContext: (String) throws -> NIOSSLContext
    /// What the phone sent before its side existed: its ClientHello, held until the origin has answered.
    private var heldFromPhone: [UInt8] = []

    private let onClientPlain: ([UInt8]) -> Void
    private let onServerPlain: ([UInt8]) -> Void
    private let onServerClosed: () -> Void
    private let toClient: ([UInt8]) -> Void
    private let toServer: ([UInt8]) -> Void
    /// Whether the phone accepted our certificate: once, on its handshake completing or failing.
    private let onPhoneVerdict: (Bool) -> Void
    private let onProtocol: (String) -> Void
    private let onOriginFailed: () -> Void
    private var verdictGiven = false
    private var dead = false
    /// A write failed inside a callback; `drain` fails the bridge once it is safely outside the pipeline.
    private var writeFailed = false
    /// The protocol the origin chose (ALPN); the phone is offered exactly this one.
    private(set) var protocolName: String?
    private var originChose: String??
    /// The origin's TLS failed before the phone was answered: not the phone refusing our certificate.
    private(set) var originFailed = false
    /// Answers requests Map Local rules match, on HTTP/1.1; nil forwards everything as it came.
    private var gate: AcceptEncodingStrip?

    /// The origin's handshake runs first: the phone is answered only once the origin has chosen its protocol,
    /// and is offered exactly that one, so both legs speak the same HTTP version. `phoneContext` makes the
    /// phone-facing context for that protocol; `onProtocol` says what was chosen, before any cleartext flows.
    init(
        host: String,
        phoneContext: @escaping (String) throws -> NIOSSLContext,
        clientContext: NIOSSLContext,
        onClientPlain: @escaping ([UInt8]) -> Void,
        onServerPlain: @escaping ([UInt8]) -> Void,
        onServerClosed: @escaping () -> Void,
        toClient: @escaping ([UInt8]) -> Void,
        toServer: @escaping ([UInt8]) -> Void,
        onPhoneVerdict: @escaping (Bool) -> Void = { _ in },
        onProtocol: @escaping (String) -> Void = { _ in },
        onOriginFailed: @escaping () -> Void = {},
        mapLocal: MapLocal? = nil
    ) throws {
        self.phoneContext = phoneContext
        self.onClientPlain = onClientPlain
        self.onServerPlain = onServerPlain
        self.onServerClosed = onServerClosed
        self.toClient = toClient
        self.toServer = toServer
        self.onPhoneVerdict = onPhoneVerdict
        self.onProtocol = onProtocol
        self.onOriginFailed = onOriginFailed

        originSide = EmbeddedChannel(loop: loop)
        if let mapLocal, !mapLocal.isEmpty {
            gate = AcceptEncodingStrip(host: host, mapLocal: mapLocal, rewriteEncoding: false)
        }

        // Origin side: open our own TLS to the origin, surface its response.
        let hostname = host.isEmpty || isIPLiteral(host) ? nil : host
        try originSide.pipeline.syncOperations.addHandler(
            try NIOSSLClientHandler(context: clientContext, serverHostname: hostname)
        )
        try originSide.pipeline.syncOperations.addHandler(PlainTap(
            label: "origin", onData: { [weak self] in self?.serverPlain($0) },
            onClosed: { [weak self] in self?.onServerClosed() },
            onHandshake: { [weak self] accepted, chosen in
                guard let self, self.phoneSide == nil else { return }
                if accepted { self.originChose = .some(chosen) } else { self.originFailed = true }
            }
        ))

        snifferLog.debug("TLSBridge start host=\(host, privacy: .public) sni=\(hostname ?? "-", privacy: .public)")

        // The channel must be active for the handshake to run; the client side starts its ClientHello toward
        // the origin as soon as it is active.
        let fake = try SocketAddress(ipAddress: "10.111.222.2", port: 443)
        originSide.connect(to: fake, promise: nil)
        drain()
    }

    /// Convenience for a fixed phone-facing context (tests, HTTP/1.1 only).
    convenience init(
        host: String,
        serverContext: NIOSSLContext,
        clientContext: NIOSSLContext,
        onClientPlain: @escaping ([UInt8]) -> Void,
        onServerPlain: @escaping ([UInt8]) -> Void,
        onServerClosed: @escaping () -> Void,
        toClient: @escaping ([UInt8]) -> Void,
        toServer: @escaping ([UInt8]) -> Void,
        onPhoneVerdict: @escaping (Bool) -> Void = { _ in }
    ) throws {
        try self.init(host: host, phoneContext: { _ in serverContext }, clientContext: clientContext,
                      onClientPlain: onClientPlain, onServerPlain: onServerPlain, onServerClosed: onServerClosed,
                      toClient: toClient, toServer: toServer, onPhoneVerdict: onPhoneVerdict)
    }

    /// Ciphertext the phone sent. Decrypted to the request, re-encrypted to the origin.
    func fromClient(_ data: [UInt8]) {
        guard !dead, !data.isEmpty else { return }
        snifferLog.debug("bridge fromClient \(data.count) B")
        guard let phoneSide else {
            heldFromPhone += data
            return
        }
        feed(phoneSide, data)
    }

    /// Ciphertext the origin sent. Decrypted to the response, re-encrypted to the phone.
    func fromServer(_ data: [UInt8]) {
        guard !dead, !data.isEmpty else { return }
        snifferLog.debug("bridge fromServer \(data.count) B")
        feed(originSide, data)
    }

    /// The phone closed; tell the origin.
    func clientClosed() {
        guard !dead else { return }
        if protocolName == Self.http1, let gate { for piece in gate.finish() { writeApp(originSide, piece) } }
        originSide.close(mode: .output, promise: nil)
        drain()
    }

    /// The origin closed; tell the phone.
    func serverClosed() {
        guard !dead else { return }
        phoneSide?.close(mode: .output, promise: nil)
        drain()
    }

    /// The origin has chosen: build the phone's side for that protocol and hand it what the phone sent so far.
    private func openPhoneSide(_ chosen: String) {
        let channel = EmbeddedChannel(loop: loop)
        do {
            try channel.pipeline.syncOperations.addHandler(NIOSSLServerHandler(context: phoneContext(chosen)))
            try channel.pipeline.syncOperations.addHandler(PlainTap(
                label: "phone", onData: { [weak self] in self?.clientPlain($0) }, onClosed: { },
                onHandshake: { [weak self] accepted, _ in self?.verdict(accepted) }
            ))
            channel.connect(to: try SocketAddress(ipAddress: "10.111.222.2", port: 443), promise: nil)
        } catch {
            fail()
            return
        }
        phoneSide = channel
        protocolName = chosen
        onProtocol(chosen)
        let held = heldFromPhone
        heldFromPhone = []
        if !held.isEmpty {
            var buffer = channel.allocator.buffer(capacity: held.count)
            buffer.writeBytes(held)
            if (try? channel.writeInbound(buffer)) == nil { fail() }
        }
    }

    // MARK: Internals

    private func verdict(_ accepted: Bool) {
        guard !verdictGiven else { return }
        verdictGiven = true
        onPhoneVerdict(accepted)
    }

    private func clientPlain(_ bytes: [UInt8]) {
        snifferLog.debug("TLSBridge request plaintext \(bytes.count) B → origin")
        onClientPlain(bytes)
        guard protocolName == Self.http1, let gate else {
            writeApp(originSide, bytes)
            return
        }
        // Map Local: a matched request is answered here and its bytes never reach the origin.
        var answers: [[UInt8]] = []
        let pieces = gate.feed(bytes, onLocal: { answers.append($0) })
        for piece in pieces { writeApp(originSide, piece) }
        for answer in answers {
            onServerPlain(answer)
            if let phoneSide { writeApp(phoneSide, answer) }
        }
    }

    private func serverPlain(_ bytes: [UInt8]) {
        snifferLog.debug("TLSBridge response plaintext \(bytes.count) B → phone")
        onServerPlain(bytes)
        if let phoneSide { writeApp(phoneSide, bytes) }
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

    /// Writes cleartext to be encrypted by `channel`'s TLS handler. Never `EmbeddedChannel.writeOutbound`: that
    /// blocks on the write's future, and while the far side's handshake is still running NIOSSL holds the write
    /// back until it completes — which needs this very thread. The phone's first request after a successful
    /// handshake (a trusted CA) arrives before the origin's handshake is done, so that wait never returned: the
    /// proxy's loop froze and with it every connection on the phone.
    private func writeApp(_ channel: EmbeddedChannel, _ bytes: [UInt8]) {
        guard !dead else { return }
        var buffer = channel.allocator.buffer(capacity: bytes.count)
        buffer.writeBytes(bytes)
        let promise = loop.makePromise(of: Void.self)
        promise.futureResult.whenFailure { [weak self] _ in self?.writeFailed = true }
        channel.writeAndFlush(buffer, promise: promise)
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
            loop.run()   // fire NIOSSL's deferred handshake/promise work
            if writeFailed { fail(); return }
            if originFailed && phoneSide == nil {
                fail()
                onOriginFailed()
                return
            }
            if phoneSide == nil, let chosen = originChose {
                openPhoneSide((chosen?.isEmpty == false ? chosen : nil) ?? Self.http1)
                if dead { return }
                moved = true
                continue
            }
            while let phoneSide, let out = try? phoneSide.readOutbound(as: ByteBuffer.self) {
                let bytes = Array(buffer: out)
                snifferLog.debug("bridge toClient \(bytes.count) B")
                toClient(bytes); moved = true
            }
            while let out = try? originSide.readOutbound(as: ByteBuffer.self) {
                let bytes = Array(buffer: out)
                snifferLog.debug("bridge toServer \(bytes.count) B")
                toServer(bytes); moved = true
            }
        }
    }

    private func fail() {
        if dead { return }
        dead = true
        _ = try? phoneSide?.finish()
        _ = try? originSide.finish()
    }
}

/// Captures the cleartext a TLS channel unwraps, notes close, and logs TLS
/// handshake completion / errors (the key signal for diagnosing a stalled MITM).
nonisolated final class PlainTap: ChannelInboundHandler {
    typealias InboundIn = ByteBuffer
    private let label: String
    private let onData: ([UInt8]) -> Void
    private let onClosed: () -> Void
    /// true (and the negotiated ALPN protocol) when the TLS handshake completed, false when it failed first.
    private let onHandshake: (Bool, String?) -> Void
    private var handshook = false

    init(label: String, onData: @escaping ([UInt8]) -> Void, onClosed: @escaping () -> Void,
         onHandshake: @escaping (Bool, String?) -> Void = { _, _ in }) {
        self.label = label
        self.onData = onData
        self.onClosed = onClosed
        self.onHandshake = onHandshake
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        var buffer = unwrapInboundIn(data)
        if let bytes = buffer.readBytes(length: buffer.readableBytes), !bytes.isEmpty {
            onData(bytes)
        }
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        // Logs TLS handshake-completed / shutdown events (from NIOTLS) without
        // needing to import that module — the description is enough to diagnose.
        let text = String(describing: event)
        snifferLog.debug("TLSBridge \(self.label, privacy: .public) event: \(text, privacy: .public)")
        if !handshook, text.contains("handshakeCompleted") {
            handshook = true
            onHandshake(true, Self.negotiated(text))
        }
        context.fireUserInboundEventTriggered(event)
    }

    /// The ALPN protocol in `handshakeCompleted(negotiatedProtocol: Optional("h2"))`; nil when none was agreed.
    /// Read from the description, which saves the tunnel a link to NIOTLS for one enum.
    static func negotiated(_ text: String) -> String? {
        guard let open = text.range(of: "Optional(\""),
              let close = text.range(of: "\")", range: open.upperBound..<text.endIndex) else { return nil }
        let value = String(text[open.upperBound..<close.lowerBound])
        return value.isEmpty ? nil : value
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        snifferLog.error("TLSBridge \(self.label, privacy: .public) TLS error: \(String(describing: error), privacy: .public)")
        if !handshook {
            handshook = true
            onHandshake(false, nil)
        }
        context.fireErrorCaught(error)
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
