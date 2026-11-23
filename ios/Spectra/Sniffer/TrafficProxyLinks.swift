import Foundation
import Network
import NIOCore
import os

/// One TCP connection: speaks the phone's side of TCP in user space (SYN/ACK,
/// sequence/acknowledgement, windows) while replaying the payload over an
/// `NWConnection` to the real destination. Optionally MITMs TLS. Ported from the
/// Android `TrafficProxy.TcpLink`; all work runs on the proxy's serial queue, so
/// no locking is needed (the `NWConnection` is started on that same queue).
final class TCPLink {
    private unowned let proxy: TrafficProxy
    private let key: String
    let flow: TrafficProxy.LiveFlow
    private let src: [UInt8], dst: [UInt8]
    private let srcPort: Int, dstPort: Int
    private let v6: Bool
    private var connection: NWConnection?
    private var connected = false

    private let isn: UInt32 = UInt32.random(in: 0..<UInt32.max)
    private lazy var ourSeq: UInt32 = isn
    private var theirNext: UInt32 = 0
    private var theirAcked: UInt32 = 0
    private var clientWindow = 65535
    private var synSeen = false, synSent = false, synAcked = false
    private var clientFin = false, finSent = false, remoteEof = false

    private var outbound: [[UInt8]] = []   // pending bytes for the upstream (pre-connect)
    private var inbound: [[UInt8]] = []     // bytes waiting to be sent to the phone
    /// When each `inbound` chunk may go to the phone: now, or later by the throttle's delay.
    private var inboundReady: [Int64] = []
    /// Bytes for the upstream held back by the throttle, in order.
    private var upQueue: [[UInt8]] = []
    /// The phone finished sending while `upQueue` still held data: the FIN goes after it.
    private var finPending = false
    private var prelude: [UInt8] = []
    /// HTTP/1.1 until a decrypted connection turns out to speak HTTP/2.
    private lazy var http: HttpStream = HttpAssembler(sink: bodySink(), messageSink: { [weak self] index, number, body in
        guard let self else { return false }
        return self.proxy.keepMessage(flowID: self.flow.id, index: index, number: number, body: body)
    })

    private func bodySink() -> HttpAssembler.Sink {
        { [weak self] index, request, body in
            guard let self else { return false }
            return self.proxy.keepBody(flowID: self.flow.id, index: index, request: request, body: body)
        }
    }
    private var held: [UInt8]?
    private var mitm: TLSBridge?
    private enum Inspect { case unknown, tls, http, other }
    private var inspect: Inspect = .unknown

    init(proxy: TrafficProxy, key: String, flow: TrafficProxy.LiveFlow, src: [UInt8], dst: [UInt8],
         srcPort: Int, dstPort: Int, v6: Bool) {
        self.proxy = proxy; self.key = key; self.flow = flow; self.src = src; self.dst = dst
        self.srcPort = srcPort; self.dstPort = dstPort; self.v6 = v6
        theirAcked = isn
    }

    func start() {
        let target = proxy.redirect?(IPCodec.text(dst), dstPort) ?? (host: IPCodec.text(dst), port: dstPort)
        guard let port = NWEndpoint.Port(rawValue: UInt16(target.port)) else { return }
        let conn = NWConnection(host: NWEndpoint.Host(target.host), port: port, using: .tcp)
        connection = conn
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            self.proxy.loop.execute {
                switch state {
                case .ready:
                    snifferLog.debug("upstream ready \(self.flow.destination, privacy: .public):\(self.dstPort) pending=\(self.outbound.count)")
                    self.connected = true
                    self.answerSyn()
                    self.flushRemote()
                    self.receiveLoop()
                case .waiting(let error):
                    // Refused, or no route: Network.framework would wait for the network to change. The app is told
                    // at once, so that it can try another address.
                    snifferLog.error("upstream waiting \(self.flow.destination, privacy: .public):\(self.dstPort): \(String(describing: error), privacy: .public)")
                    if !self.connected { self.fail() }
                case .failed(let error):
                    snifferLog.error("upstream failed \(self.flow.destination, privacy: .public):\(self.dstPort): \(String(describing: error), privacy: .public)")
                    self.fail()
                case .cancelled:
                    self.fail()
                default:
                    break
                }
            }
        }
        conn.start(queue: proxy.ioQueue)
    }

    func onPacket(_ tcp: IPCodec.TCP) {
        flow.lastAt = TrafficProxyNow()
        proxy.markDirty()
        if tcp.flags & IPCodec.RST != 0 { close(); return }
        if tcp.flags & IPCodec.SYN != 0 && !synAcked { handshake(tcp); return }
        if tcp.flags & IPCodec.ACK != 0 { acceptAck(tcp.ack) }
        clientWindow = tcp.window
        acceptData(tcp)
        pumpToClient()
        maybeFinish()
    }

    // MARK: Upstream (NWConnection)

    private func flushRemote() {
        guard connected, let conn = connection else { return }
        let pending = outbound
        outbound.removeAll(keepingCapacity: true)
        for chunk in pending { sendUpstream(chunk) }
        if clientFin {
            if upQueue.isEmpty { conn.send(content: nil, isComplete: true, completion: .contentProcessed { _ in }) } else { finPending = true }
        }
    }

    private func send(_ conn: NWConnection, _ bytes: [UInt8]) {
        conn.send(content: Data(bytes), completion: .contentProcessed { [weak self] error in
            guard let self, error != nil else { return }
            self.proxy.loop.execute { self.fail() }
        })
    }

    /// Sends what the throttle allows of `upQueue`; the rest waits for the proxy's next tick.
    private func flushUp() {
        guard connected, let conn = connection else { return }
        let now = TrafficProxyNow()
        while !upQueue.isEmpty {
            let chunk = upQueue[0]
            let allowed = proxy.throttle?.up.take(chunk.count, now: now) ?? chunk.count
            if allowed <= 0 { proxy.holdBack(self); return }
            send(conn, Array(chunk[0..<allowed]))
            if allowed < chunk.count {
                upQueue[0] = Array(chunk[allowed...])
                proxy.holdBack(self)
                return
            }
            upQueue.removeFirst()
        }
        if finPending {
            finPending = false
            conn.send(content: nil, isComplete: true, completion: .contentProcessed { _ in })
        }
    }

    /// The throttle let more through: carry on in both directions.
    func resume() {
        if flow.closed { return }
        flushUp()
        pumpToClient()
    }

    private func queueInbound(_ bytes: [UInt8]) {
        inbound.append(bytes)
        inboundReady.append(TrafficProxyNow() + (proxy.throttle?.latencyMs ?? 0))
    }

    private func receiveLoop() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] content, _, isComplete, error in
            guard let self else { return }
            self.proxy.loop.execute {
                if let content, !content.isEmpty {
                    let bytes = [UInt8](content)
                    snifferLog.debug("upstream recv \(bytes.count) B from \(self.flow.destination, privacy: .public):\(self.dstPort) mitm=\(self.mitm != nil)")
                    if let bridge = self.mitm {
                        self.flow.received += Int64(bytes.count); self.flow.lastAt = TrafficProxyNow(); self.proxy.markDirty()
                        bridge.fromServer(bytes)
                    } else {
                        self.noteServer(bytes)
                        self.queueInbound(bytes)
                        self.flow.received += Int64(bytes.count); self.flow.lastAt = TrafficProxyNow(); self.proxy.markDirty()
                        self.pumpToClient()
                    }
                }
                if isComplete || error != nil {
                    self.remoteEof = true
                    if let bridge = self.mitm { bridge.serverClosed() } else { self.noteServerClosed() }
                    self.pumpToClient()
                    return
                }
                self.receiveLoop()
            }
        }
    }

    private func sendUpstream(_ bytes: [UInt8]) {
        if connected, let conn = connection {
            if proxy.throttle?.up.limited == true || !upQueue.isEmpty {
                upQueue.append(bytes)
                flushUp()
            } else {
                send(conn, bytes)
            }
        } else {
            outbound.append(bytes)
        }
    }

    // MARK: TCP state machine (ported)

    private func handshake(_ tcp: IPCodec.TCP) {
        if !synSeen {
            synSeen = true
            theirNext = tcp.seq &+ 1
            clientWindow = tcp.window
        }
        answerSyn()
    }

    /// Accepts the app's connection once the server has accepted ours, and not before. An app that gets no answer over
    /// IPv6 goes on to IPv4 by itself; answered at once, it stayed with an address that led nowhere.
    private func answerSyn() {
        if synSeen && connected { sendSynAck() }
    }

    private func sendSynAck() {
        emit(seq: isn, flags: IPCodec.SYN | IPCodec.ACK, payload: [], advance: !synSent, mss: mss())
        synSent = true
    }

    private func acceptAck(_ ack: UInt32) {
        if !seqBefore(theirAcked, ack) || seqBefore(ourSeq, ack) { return }
        theirAcked = ack
        if !seqBefore(ack, isn &+ 1) { synAcked = true }
    }

    private func acceptData(_ tcp: IPCodec.TCP) {
        if flow.closed { return }
        let payload = tcp.payload
        if !payload.isEmpty {
            let diff = Int(Int32(bitPattern: tcp.seq &- theirNext))
            let slice: [UInt8]
            if diff == 0 { slice = payload }
            else if diff < 0 && -diff < payload.count { slice = Array(payload[(-diff)...]) }
            else { slice = [] }
            if !slice.isEmpty { take(slice) }
        }
        if tcp.flags & IPCodec.FIN != 0 && !clientFin {
            let finAt = tcp.seq &+ UInt32(payload.count)
            if Int(Int32(bitPattern: finAt &- theirNext)) == 0 {
                clientFin = true
                theirNext = theirNext &+ 1
                if let bridge = mitm { bridge.clientClosed() }
                else if connected {
                    if upQueue.isEmpty { connection?.send(content: nil, isComplete: true, completion: .contentProcessed { _ in }) }
                    else { finPending = true }
                }
            }
        }
        if !payload.isEmpty || tcp.flags & IPCodec.FIN != 0 { sendAck() }
    }

    private func take(_ data: [UInt8]) {
        theirNext = theirNext &+ UInt32(data.count)
        flow.sent += Int64(data.count)
        if route(data) { return }
        note(data)
        sendUpstream(data)
    }

    /// True when `data` is kept off the raw socket (handled by the MITM path).
    private func route(_ data: [UInt8]) -> Bool {
        if let bridge = mitm { bridge.fromClient(data); return true }
        let buffering = held != nil
        let tls = !data.isEmpty && (Int(data[0]) & 0xFF) == 22
        if !buffering && !(proxy.shouldDecrypt() && inspect == .unknown && tls) { return false }
        var buf = held ?? []
        buf += data
        held = buf
        if !TrafficHints.tlsRecordComplete(buf) {
            if buf.count > 18_000 { passthroughHeld() }
            return true
        }
        if !startMITM(buf) { passthroughHeld() }
        return true
    }

    private func startMITM(_ hello: [UInt8]) -> Bool {
        let sni = TrafficHints.tlsServerName(hello)
        if let sni, !sni.isEmpty { flow.host = sni }
        flow.tls = true
        inspect = .tls
        guard let ca = proxy.authority() else { return false }
        let host = (sni?.isEmpty == false ? sni : nil) ?? IPCodec.text(dst)
        // Refused once already, or decryption gave up for this run: straight through, no second failure.
        guard proxy.mitmAllowed(host: host, sni: sni) else { return false }
        proxy.phase("mitm \(host)")
        defer { proxy.phase("packet") }
        do {
            // HTTP/2 is offered to the origin only when the phone asked for it; the phone then gets what the origin
            // chose. A host with Map Local rules stays on HTTP/1.1, where single requests can be answered here.
            let mapLocal = MapLocal(proxy.mapRules())
            let wantsH2 = TrafficHints.tlsAlpn(hello).contains(TLSBridge.h2) && !mapLocal.covers(host: sni)
            let offer = wantsH2 ? [TLSBridge.h2, TLSBridge.http1] : [TLSBridge.http1]
            let bridge = try TLSBridge(
                host: host,
                phoneContext: { chosen in try ca.makeServerContext(for: host, protocols: [chosen]) },
                clientContext: try LocalCertificateAuthority.makeSystemClientContext(protocols: offer),
                onClientPlain: { [weak self] in self?.http.client($0); self?.applyHTTP() },
                onServerPlain: { [weak self] in self?.http.server($0); self?.applyHTTP() },
                onServerClosed: { [weak self] in self?.http.serverClosed(); self?.applyHTTP() },
                toClient: { [weak self] in self?.queueInbound($0); self?.pumpToClient() },
                toServer: { [weak self] in self?.sendUpstream($0) },
                onPhoneVerdict: { [weak self] accepted in
                    guard let self else { return }
                    if !accepted { self.flow.refused = true }
                    self.proxy.mitmVerdict(host: host, accepted: accepted)
                },
                onProtocol: { [weak self] chosen in
                    guard let self, chosen == TLSBridge.h2 else { return }
                    self.http = Http2Assembler(sink: self.bodySink())
                },
                onOriginFailed: { [weak self] in
                    // The origin's TLS failed before the phone was answered: nothing to read, the phone gets a reset.
                    guard let self else { return }
                    self.proxy.loop.execute { self.fail() }
                },
                mapLocal: mapLocal.isEmpty ? nil : mapLocal
            )
            mitm = bridge
            held = nil
            snifferLog.debug("startMITM ok host=\(host, privacy: .public)")
            bridge.fromClient(hello)
            return true
        } catch {
            mitm = nil
            flow.refused = true
            snifferLog.error("startMITM failed host=\(host, privacy: .public): \(String(describing: error), privacy: .public)")
            return false
        }
    }

    private func passthroughHeld() {
        guard let buf = held else { return }
        held = nil
        note(buf)
        sendUpstream(buf)
    }

    private func pumpToClient() {
        if flow.closed { return }
        // In flight: sent and not acknowledged yet, straight from the sequence numbers. A counter that only grew
        // used to stop every connection after one window, about 64 KB.
        var room = clientWindow - Int(ourSeq &- theirAcked)
        let now = TrafficProxyNow()
        let limit = proxy.throttle
        while room > 0 && !inbound.isEmpty {
            if inboundReady[0] > now { proxy.holdBack(self); break }
            let wanted = min(inbound[0].count, min(room, mss()))
            let size = limit?.down.take(wanted, now: now) ?? wanted
            if size <= 0 { proxy.holdBack(self); break }
            var chunk = inbound.removeFirst()
            let ready = inboundReady.removeFirst()
            let piece = Array(chunk[0..<size])
            if size != chunk.count {
                inbound.insert(Array(chunk[size...]), at: 0)
                inboundReady.insert(ready, at: 0)
            }
            emit(seq: ourSeq, flags: IPCodec.ACK | IPCodec.PSH, payload: piece, advance: true)
            room -= size
            _ = chunk
        }
        if inbound.isEmpty && remoteEof && !finSent {
            emit(seq: ourSeq, flags: IPCodec.FIN | IPCodec.ACK, payload: [], advance: true)
            finSent = true
        }
        maybeFinish()
    }

    private func maybeFinish() {
        if finSent && clientFin && Int(Int32(bitPattern: theirAcked &- ourSeq)) == 0 { close() }
    }

    private func sendAck() { emit(seq: ourSeq, flags: IPCodec.ACK, payload: [], advance: false) }

    // MARK: Inspection (names without MITM)

    private func note(_ data: [UInt8]) {
        switch inspect {
        case .other: return
        case .tls: captureSni(data)
        case .http: http.client(data); applyHTTP()
        case .unknown:
            if prelude.count >= 32_768 { inspect = .other; return }
            prelude += data
            if !prelude.isEmpty && (Int(prelude[0]) & 0xFF) == 22 { inspect = .tls; captureSni(prelude); return }
            let httpReady = TrafficHints.httpRequest(prelude) != nil
            if TrafficHints.awaitsHttp(prelude) || httpReady {
                if !httpReady { return }
                inspect = .http; http.client(prelude); applyHTTP(); return
            }
            if prelude.count >= 8 { inspect = .other }
        }
    }

    private func noteServer(_ data: [UInt8]) {
        if inspect != .http { return }
        http.server(data); applyHTTP()
    }

    private func noteServerClosed() {
        if inspect != .http { return }
        http.serverClosed(); applyHTTP()
    }

    private func applyHTTP() {
        let list = http.exchanges()
        if list.isEmpty { return }
        flow.exchanges = list
        flow.request = list[0].summary
        if flow.host?.isEmpty != false {
            if let host = list[0].requestHeaders.first(where: { $0.name.lowercased() == "host" })?
                .value.split(separator: ":").first.map(String.init), !host.isEmpty {
                flow.host = host
            }
        }
        proxy.markDirty()
    }

    private func captureSni(_ data: [UInt8]) {
        if flow.tls && flow.host != nil { return }
        if prelude.count < 4096 && prelude != data {
            let room = 4096 - prelude.count
            if room > 0 { prelude += data.prefix(room) }
        }
        if let name = TrafficHints.tlsServerName(prelude) { flow.host = name; flow.tls = true }
        else if TrafficHints.tlsRecordComplete(prelude) { flow.tls = true }
    }

    // MARK: Packet emit

    private func emit(seq: UInt32, flags: Int, payload: [UInt8], advance: Bool, mss: Int? = nil) {
        let packet = IPCodec.tcpPacket(src: dst, dst: src, srcPort: dstPort, dstPort: srcPort,
                                       seq: seq, ack: theirNext, flags: flags,
                                       window: windowField(), payload: payload, id: Int(proxy.nextIPID()), mss: mss)
        proxy.write(packet, family: v6 ? AF_INET6 : AF_INET)
        if advance {
            ourSeq = ourSeq &+ UInt32(payload.count)
            if flags & IPCodec.SYN != 0 { ourSeq = ourSeq &+ 1 }
            if flags & IPCodec.FIN != 0 { ourSeq = ourSeq &+ 1 }
        }
    }

    private func windowField() -> Int { 65535 }
    private func mss() -> Int { v6 ? 1200 : 1360 }

    /// Idle past `TrafficProxy.tcpIdle`, or still not connected upstream after `connectTimeout` (a connection the
    /// server never answers sits in `.waiting` forever otherwise).
    func isStale(now: Int64) -> Bool {
        if !connected && now - flow.startedAt > Int64(TrafficProxy.connectTimeout * 1000) { return true }
        return now - flow.lastAt > Int64(TrafficProxy.tcpIdle * 1000)
    }

    func fail() {
        if !flow.closed { emit(seq: ourSeq, flags: IPCodec.RST | IPCodec.ACK, payload: [], advance: false) }
        close()
    }

    func close() {
        if flow.closed { return }
        flow.closed = true
        flow.lastAt = TrafficProxyNow()
        proxy.markDirty()
        proxy.removeTCP(key)
        connection?.cancel()
        connection = nil
        mitm = nil
    }
}

/// One UDP association: forwards datagrams both ways; learns DNS names.
final class UDPLink {
    private unowned let proxy: TrafficProxy
    private let key: String
    let flow: TrafficProxy.LiveFlow
    private let src: [UInt8], dst: [UInt8]
    private let srcPort: Int, dstPort: Int
    private var connection: NWConnection?

    var isDNS: Bool { dstPort == 53 }

    init(proxy: TrafficProxy, key: String, flow: TrafficProxy.LiveFlow, src: [UInt8], dst: [UInt8],
         srcPort: Int, dstPort: Int) {
        self.proxy = proxy; self.key = key; self.flow = flow; self.src = src; self.dst = dst
        self.srcPort = srcPort; self.dstPort = dstPort
    }

    func start() {
        let host = NWEndpoint.Host(IPCodec.text(dst))
        guard let port = NWEndpoint.Port(rawValue: UInt16(dstPort)) else { return }
        let conn = NWConnection(host: host, port: port, using: .udp)
        connection = conn
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            self.proxy.loop.execute {
                if case .ready = state { self.receiveLoop() }
                if case .failed = state { self.close() }
            }
        }
        conn.start(queue: proxy.ioQueue)
    }

    func onPacket(_ payload: [UInt8]) {
        flow.sent += Int64(payload.count)
        flow.lastAt = TrafficProxyNow()
        proxy.markDirty()
        if dstPort == 53, let q = TrafficHints.dnsQuestion(payload), flow.host == nil { flow.host = q }
        connection?.send(content: Data(payload), completion: .contentProcessed { [weak self] error in
            guard let self, error != nil else { return }
            self.proxy.loop.execute { self.close() }
        })
    }

    private func receiveLoop() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] content, _, _, error in
            guard let self else { return }
            self.proxy.loop.execute {
                if let content, !content.isEmpty {
                    let payload = [UInt8](content)
                    self.flow.received += Int64(payload.count); self.flow.lastAt = TrafficProxyNow(); self.proxy.markDirty()
                    if self.dstPort == 53 {
                        for (host, ip) in TrafficHints.dnsAnswers(payload) { self.proxy.remember(ip, host) }
                    }
                    let packet = IPCodec.udpPacket(src: self.dst, dst: self.src, srcPort: self.dstPort,
                                                   dstPort: self.srcPort, payload: payload, id: Int(self.proxy.nextIPID()))
                    self.proxy.write(packet, family: self.src.count == 16 ? AF_INET6 : AF_INET)
                }
                if error != nil { self.close(); return }
                self.receiveLoop()
            }
        }
    }

    func close() {
        if flow.closed { return }
        flow.closed = true
        flow.lastAt = TrafficProxyNow()
        proxy.markDirty()
        proxy.removeUDP(key)
        connection?.cancel()
        connection = nil
    }
}

func TrafficProxyNow() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
