import Foundation
import Network
import NIOCore
import NIOPosix
import os

/// User-space TCP/UDP NAT for the packet tunnel. IP packets captured from the
/// phone are parsed, each connection is replayed over an `NWConnection` kept off
/// the tunnel, and replies are written back as IP packets. Names come from DNS,
/// the TLS SNI, or cleartext HTTP; when `decryptTLS` is on, TLS to a named host
/// is opened with the local CA and read as HTTP. Adapts the Android
/// `platform/TrafficProxy.kt` (Java NIO selector) to Network.framework.
///
/// Everything runs on a single-thread `EventLoop` (`loop`), not a GCD queue:
/// the TLS bridge uses `EmbeddedEventLoop`, which pins to one thread, and a GCD
/// serial queue hops threads. `NWConnection` callbacks arrive on `ioQueue` and
/// hop onto `loop` before touching any state.
nonisolated final class TrafficProxy {
    /// Writes a reply IP packet back into the tunnel. `family` is AF_INET / AF_INET6.
    typealias TunnelWriter = (_ packet: Data, _ family: Int32) -> Void

    private let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
    let loop: EventLoop
    let ioQueue = DispatchQueue(label: "cattivole.spectra.proxy.io")
    private let writer: TunnelWriter
    private let onFlows: ([TrafficFlow]) -> Void
    private let decryptTLS: () -> Bool
    private let certificate: () -> LocalCertificateAuthority?
    private let hostFilter: () -> TlsHostFilter
    private let throttleSettings: () -> ThrottleSettings
    let mapRules: () -> [MapRule]
    /// The buckets for the settings in force; rebuilt when the settings change. Loop only.
    private(set) var throttle: Throttle?
    private var throttleFor: ThrottleSettings?
    /// Links holding data back for the throttle, and whether a tick to resume them is scheduled.
    private var heldBack: [ObjectIdentifier: TCPLink] = [:]
    private var tickScheduled = false
    /// Where finished HTTP bodies are written; nil keeps none. Writes go to their own queue, off the loop.
    private let bodies: TrafficBodies?
    private let bodyQueue = DispatchQueue(label: "cattivole.spectra.proxy.bodies", qos: .utility)

    private var tcp: [String: TCPLink] = [:]
    private var udp: [String: UDPLink] = [:]
    private var shown: [LiveFlow] = []
    private var names: [String: String] = [:]
    private var nextID: Int64 = 0
    private var ipID: UInt16 = 1
    private var dirty = false
    private var lastPublish = Date.distantPast
    private var sweeper: RepeatedTask?

    /// The loop's health as the tunnel's watchdog sees it from another thread, hence the lock: the watchdog must
    /// never wait on the loop itself, or a stuck loop takes the watchdog down with it.
    struct Backlog: Sendable {
        /// Packets handed to `loop` and not processed yet.
        var pending = 0
        /// When the loop last took a packet.
        var lastDrain = Date()
        /// When the 5 s sweep last ran: a loop stuck inside one task stops beating even with nothing queued.
        var lastBeat = Date()
        var links = 0
        /// What the loop is doing right now ("packet", "mitm <host>", …) and since when, for the log of a stall.
        var phase = "idle"
        var phaseSince = Date()
        /// The phone kept refusing the certificate and never accepted it: probably not trusted (a notice only).
        var decryptOff = false
    }
    private let backlog = OSAllocatedUnfairLock(initialState: Backlog())
    var backlogState: Backlog { backlog.withLock { $0 } }

    init(
        writer: @escaping TunnelWriter,
        decryptTLS: @escaping () -> Bool,
        certificate: @escaping () -> LocalCertificateAuthority?,
        hostFilter: @escaping () -> TlsHostFilter = { TlsHostFilter() },
        bodies: TrafficBodies? = nil,
        throttle: @escaping () -> ThrottleSettings = { ThrottleSettings() },
        mapRules: @escaping () -> [MapRule] = { [] },
        onFlows: @escaping ([TrafficFlow]) -> Void
    ) {
        self.hostFilter = hostFilter
        self.throttleSettings = throttle
        self.mapRules = mapRules
        self.bodies = bodies
        self.loop = group.next()
        self.writer = writer
        self.decryptTLS = decryptTLS
        self.certificate = certificate
        self.onFlows = onFlows
        // Without this, links only ever closed on an error — which UDP almost never has — so every DNS query
        // left one behind, the table filled up within minutes, and from then on every new connection on the
        // phone was refused: no app could reach the network until the VPN was turned off.
        sweeper = loop.scheduleRepeatedTask(initialDelay: .seconds(5), delay: .seconds(5)) { [weak self] _ in
            self?.sweep(now: Self.now())
        }
    }

    /// Feeds one captured IP packet (called off the tunnel read callback). Past `maxBacklog` waiting packets the
    /// packet is dropped rather than queued: TCP sends it again, and a proxy that cannot keep up must not grow
    /// without bound inside an extension the system kills at a few dozen megabytes.
    func handle(_ packet: Data) {
        let accepted = backlog.withLock { state -> Bool in
            guard state.pending < Self.maxBacklog else { return false }
            state.pending += 1
            return true
        }
        guard accepted else { return }
        loop.execute {
            self.backlog.withLock { state in
                state.pending -= 1
                state.lastDrain = Date()
                state.phase = "packet"
                state.phaseSince = state.lastDrain
            }
            self.process([UInt8](packet))
            self.phase("idle")
        }
    }

    func stop() {
        loop.execute {
            self.sweeper?.cancel()
            self.sweeper = nil
            self.tcp.values.forEach { $0.close() }
            self.udp.values.forEach { $0.close() }
            self.publish(force: true)
        }
        let group = self.group
        DispatchQueue.global().async { try? group.syncShutdownGracefully() }
    }

    // MARK: Dispatch

    /// Holds `link` until the next throttle tick, about 10 ms away.
    func holdBack(_ link: TCPLink) {
        heldBack[ObjectIdentifier(link)] = link
        guard !tickScheduled else { return }
        tickScheduled = true
        loop.scheduleTask(in: .milliseconds(10)) { [weak self] in
            guard let self else { return }
            self.tickScheduled = false
            self.refreshThrottle()
            let waiting = Array(self.heldBack.values)
            self.heldBack.removeAll(keepingCapacity: true)
            for link in waiting { link.resume() }
            self.publish(force: false)
        }
    }

    private func refreshThrottle() {
        let settings = throttleSettings()
        guard settings != throttleFor else { return }
        throttleFor = settings
        throttle = settings.enabled ? Throttle(settings) : nil
    }

    private func process(_ raw: [UInt8]) {
        refreshThrottle()
        guard let ip = IPCodec.parse(raw), IPCodec.unicast(ip.dst) else { return }
        switch ip.protocolNumber {
        case IPCodec.tcp:
            guard let header = IPCodec.parseTCP(ip.payload) else { return }
            let key = key("TCP", ip.src, header.srcPort, ip.dst, header.dstPort)
            if let link = tcp[key] {
                link.onPacket(header)
            } else if header.flags & IPCodec.SYN != 0 && header.flags & IPCodec.ACK == 0 {
                openTCP(ip, header, key)
            } else {
                reject(ip, header)
            }
        case IPCodec.udp:
            guard let header = IPCodec.parseUDP(ip.payload) else { return }
            // QUIC hides HTTP from the TLS handshake; dropping it makes the app fall back to TCP, which can be
            // read. A host the filter leaves encrypted keeps its QUIC; one whose name is not known yet loses it.
            let name = names[IPCodec.text(ip.dst)]
            if header.dstPort == 443 && shouldDecrypt() && (name == nil || hostFilter().allows(name)) {
                udp.removeValue(forKey: key("UDP", ip.src, header.srcPort, ip.dst, header.dstPort))?.close()
                return
            }
            let key = key("UDP", ip.src, header.srcPort, ip.dst, header.dstPort)
            let link = udp[key] ?? openUDP(ip, header, key)
            link?.onPacket(header.payload)
        default:
            break
        }
        publish(force: false)
    }

    /// Closes links nobody used for a while: a UDP association after `udpIdle` (a DNS one after `dnsIdle`), a TCP
    /// connection after `tcpIdle`, or one whose server never answered within `connectTimeout`.
    func sweep(now: Int64) {
        defer {
            let links = tcp.count + udp.count
            backlog.withLock {
                $0.lastBeat = Date()
                $0.links = links
            }
        }
        for link in udp.values where now - link.flow.lastAt > Int64((link.isDNS ? Self.dnsIdle : Self.udpIdle) * 1000) {
            link.close()
        }
        for link in tcp.values where link.isStale(now: now) {
            link.fail()
        }
        publish(force: false)
    }

    /// With the table full, the link used longest ago makes way for the new one instead of the new one being
    /// refused: a full table must never cut the phone off.
    private func makeRoom() -> Bool {
        while tcp.count + udp.count >= Self.maxLinks {
            let oldUDP = udp.values.min { $0.flow.lastAt < $1.flow.lastAt }
            let oldTCP = tcp.values.min { $0.flow.lastAt < $1.flow.lastAt }
            if let oldUDP, oldUDP.flow.lastAt <= (oldTCP?.flow.lastAt ?? .max) {
                oldUDP.close()
            } else if let oldTCP {
                oldTCP.fail()
            } else {
                return false
            }
        }
        return true
    }

    var linkCount: Int { tcp.count + udp.count }

    private func openTCP(_ ip: IPCodec.Packet, _ header: IPCodec.TCP, _ key: String) {
        guard makeRoom() else { reject(ip, header); return }
        let flow = track(ip, "TCP", header.srcPort, header.dstPort)
        let link = TCPLink(proxy: self, key: key, flow: flow, src: ip.src, dst: ip.dst,
                           srcPort: header.srcPort, dstPort: header.dstPort, v6: ip.version == 6)
        tcp[key] = link
        link.start()
        link.onPacket(header)
    }

    private func openUDP(_ ip: IPCodec.Packet, _ header: IPCodec.UDP, _ key: String) -> UDPLink? {
        guard makeRoom() else { return nil }
        let flow = track(ip, "UDP", header.srcPort, header.dstPort)
        let link = UDPLink(proxy: self, key: key, flow: flow, src: ip.src, dst: ip.dst,
                           srcPort: header.srcPort, dstPort: header.dstPort)
        udp[key] = link
        link.start()
        return link
    }

    private func track(_ ip: IPCodec.Packet, _ proto: String, _ srcPort: Int, _ dstPort: Int) -> LiveFlow {
        nextID += 1
        let destination = IPCodec.text(ip.dst)
        let flow = LiveFlow(id: nextID, proto: proto, source: IPCodec.text(ip.src), sourcePort: srcPort,
                            destination: destination, destinationPort: dstPort, host: names[destination],
                            startedAt: Self.now())
        shown.append(flow)
        evict()
        dirty = true
        return flow
    }

    private func evict() {
        guard shown.count > Self.maxShown else { return }
        var extra = shown.count - Self.maxShown
        var i = 0
        while i < shown.count && extra > 0 {
            if shown[i].closed { shown.remove(at: i); extra -= 1 } else { i += 1 }
        }
    }

    private func reject(_ ip: IPCodec.Packet, _ t: IPCodec.TCP) {
        let ack = t.seq &+ UInt32(t.payload.count)
            &+ (t.flags & IPCodec.SYN != 0 ? 1 : 0) &+ (t.flags & IPCodec.FIN != 0 ? 1 : 0)
        write(IPCodec.tcpPacket(src: ip.dst, dst: ip.src, srcPort: t.dstPort, dstPort: t.srcPort,
                                seq: t.ack, ack: ack, flags: IPCodec.RST | IPCodec.ACK, window: 0,
                                payload: [], id: Int(nextIPID())), family: ip.version == 6 ? AF_INET6 : AF_INET)
    }

    func remember(_ ip: String, _ host: String) {
        names[ip] = host
        if names.count > 512 { names.removeAll(keepingCapacity: true) }
        for flow in shown where flow.destination == ip && flow.host == nil { flow.host = host }
    }

    func write(_ packet: [UInt8], family: Int32) {
        writer(Data(packet), family)
    }

    func nextIPID() -> UInt16 { ipID &+= 1; return ipID }
    func markDirty() { dirty = true }
    func removeTCP(_ key: String) { tcp.removeValue(forKey: key) }
    func removeUDP(_ key: String) { udp.removeValue(forKey: key) }
    func shouldDecrypt() -> Bool { decryptTLS() }

    /// Names what the loop is about to do, so a stall can be pinned to it.
    func phase(_ name: String) {
        backlog.withLock {
            $0.phase = name
            $0.phaseSince = Date()
        }
    }

    // MARK: Hosts that refuse the certificate

    /// A host whose app refused Spectra's certificate is left alone for the rest of the run: its connections go
    /// through untouched instead of failing again and again. Apps and system services that pin their
    /// certificates always refuse, however the CA is trusted, so one refusal never turns decryption off as a whole.
    private var refusedHosts: Set<String> = []
    private var refusals = 0
    private var acceptances = 0
    /// So many refusals and not one acceptance: the phone probably does not trust the certificate at all.
    /// Only reported (`Backlog.decryptOff`), since it cannot be told apart from a run of pinned services.
    static let refusalsBeforeNotice = 10

    /// Refused once already, or left encrypted by the host list: straight through.
    func mitmAllowed(host: String, sni: String?) -> Bool {
        !refusedHosts.contains(host) && hostFilter().allows(sni?.isEmpty == false ? sni : nil)
    }

    /// Hands a WebSocket message's payload to the store, within the recording's budget.
    func keepMessage(flowID: Int64, index: Int, number: Int, body: [UInt8]) -> Bool {
        guard let bodies, bodies.reserve(body.count) else { return false }
        let data = Data(body)
        bodyQueue.async { bodies.writeMessage(flowID: flowID, index: index, number: number, body: data) }
        return true
    }

    /// Hands a finished body to the store, within the recording's budget. True when it will be written.
    func keepBody(flowID: Int64, index: Int, request: Bool, body: [UInt8]) -> Bool {
        guard let bodies, bodies.reserve(body.count) else { return false }
        let data = Data(body)
        bodyQueue.async { bodies.write(flowID: flowID, index: index, request: request, body: data) }
        return true
    }

    func mitmVerdict(host: String, accepted: Bool) {
        if accepted {
            acceptances += 1
            return
        }
        refusedHosts.insert(host)
        refusals += 1
        if refusals == Self.refusalsBeforeNotice && acceptances == 0 {
            backlog.withLock { $0.decryptOff = true }
            snifferLog.error("\(self.refusals) certificate refusals and no acceptance: the CA is probably not trusted")
        }
    }
    func authority() -> LocalCertificateAuthority? { certificate() }

    private func publish(force: Bool) {
        let now = Date()
        if !force && (!dirty || now.timeIntervalSince(lastPublish) < 0.2) { return }
        dirty = false
        lastPublish = now
        let rows = shown.map { $0.snapshot() }
            .sorted { a, b in a.closed == b.closed ? a.lastAt > b.lastAt : (!a.closed && b.closed) }
            .prefix(Self.maxShown)
        let tcp = rows.filter { $0.proto == "TCP" }.count
        snifferLog.debug("publish \(rows.count) flows (tcp=\(tcp), udp=\(rows.count - tcp))")
        onFlows(Array(rows))
    }

    private func key(_ proto: String, _ src: [UInt8], _ srcPort: Int, _ dst: [UInt8], _ dstPort: Int) -> String {
        "\(proto) \(IPCodec.text(src)):\(srcPort)>\(IPCodec.text(dst)):\(dstPort)"
    }

    fileprivate static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    static let maxLinks = 400
    static let maxShown = 400
    static let maxBacklog = 4096
    static let tcpIdle: TimeInterval = 300
    static let udpIdle: TimeInterval = 60
    /// A DNS association is a question or two (A and AAAA may share a port) and their answers: done within seconds.
    static let dnsIdle: TimeInterval = 10
    /// A server that has not accepted the connection by then is not going to.
    static let connectTimeout: TimeInterval = 20

    // MARK: Live flow (mutable, snapshotted for the UI)

    final class LiveFlow {
        let id: Int64
        let proto: String
        let source: String
        let sourcePort: Int
        let destination: String
        let destinationPort: Int
        var host: String?
        let startedAt: Int64
        var request: String?
        var app: String?
        var tls = false
        var sent: Int64 = 0
        var received: Int64 = 0
        var lastAt: Int64
        var closed = false
        var exchanges: [HttpExchange] = []
        var refused = false

        init(id: Int64, proto: String, source: String, sourcePort: Int, destination: String,
             destinationPort: Int, host: String?, startedAt: Int64) {
            self.id = id; self.proto = proto; self.source = source; self.sourcePort = sourcePort
            self.destination = destination; self.destinationPort = destinationPort
            self.host = host; self.startedAt = startedAt; self.lastAt = startedAt
        }

        func snapshot() -> TrafficFlow {
            TrafficFlow(id: id, proto: proto, source: source, sourcePort: sourcePort,
                        destination: destination, destinationPort: destinationPort, host: host,
                        request: request, app: app, tls: tls, sent: sent, received: received,
                        startedAt: startedAt, lastAt: lastAt, closed: closed, exchanges: exchanges, refused: refused)
        }
    }
}

/// `a` is earlier than `b` in TCP sequence space.
func seqBefore(_ a: UInt32, _ b: UInt32) -> Bool { Int32(bitPattern: a &- b) < 0 }
