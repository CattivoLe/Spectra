import Foundation
import Network

/// User-space TCP/UDP NAT for the packet tunnel. IP packets captured from the
/// phone are parsed, each connection is replayed over an `NWConnection` kept off
/// the tunnel, and replies are written back as IP packets. Names come from DNS,
/// the TLS SNI, or cleartext HTTP; when `decryptTLS` is on, TLS to a named host
/// is opened with the local CA and read as HTTP. Adapts the Android
/// `platform/TrafficProxy.kt` (Java NIO selector) to Network.framework.
nonisolated final class TrafficProxy {
    /// Writes a reply IP packet back into the tunnel. `family` is AF_INET / AF_INET6.
    typealias TunnelWriter = (_ packet: Data, _ family: Int32) -> Void

    private let queue = DispatchQueue(label: "cattivole.spectra.proxy")
    private let writer: TunnelWriter
    private let onFlows: ([TrafficFlow]) -> Void
    private let decryptTLS: () -> Bool
    private let certificate: () -> LocalCertificateAuthority?

    private var tcp: [String: TCPLink] = [:]
    private var udp: [String: UDPLink] = [:]
    private var shown: [LiveFlow] = []
    private var names: [String: String] = [:]
    private var nextID: Int64 = 0
    private var ipID: UInt16 = 1
    private var dirty = false
    private var lastPublish = Date.distantPast

    init(
        writer: @escaping TunnelWriter,
        decryptTLS: @escaping () -> Bool,
        certificate: @escaping () -> LocalCertificateAuthority?,
        onFlows: @escaping ([TrafficFlow]) -> Void
    ) {
        self.writer = writer
        self.decryptTLS = decryptTLS
        self.certificate = certificate
        self.onFlows = onFlows
    }

    /// Feeds one captured IP packet (called off the tunnel read callback).
    func handle(_ packet: Data) {
        queue.async { self.process([UInt8](packet)) }
    }

    func stop() {
        queue.async {
            self.tcp.values.forEach { $0.close() }
            self.udp.values.forEach { $0.close() }
            self.publish(force: true)
        }
    }

    // MARK: Dispatch

    private func process(_ raw: [UInt8]) {
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
            if decryptTLS() && header.dstPort == 443 {
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

    private func openTCP(_ ip: IPCodec.Packet, _ header: IPCodec.TCP, _ key: String) {
        if tcp.count + udp.count >= Self.maxLinks { reject(ip, header); return }
        let flow = track(ip, "TCP", header.srcPort, header.dstPort)
        let link = TCPLink(proxy: self, key: key, flow: flow, src: ip.src, dst: ip.dst,
                           srcPort: header.srcPort, dstPort: header.dstPort, v6: ip.version == 6)
        tcp[key] = link
        link.start()
        link.onPacket(header)
    }

    private func openUDP(_ ip: IPCodec.Packet, _ header: IPCodec.UDP, _ key: String) -> UDPLink? {
        if tcp.count + udp.count >= Self.maxLinks { return nil }
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
    func authority() -> LocalCertificateAuthority? { certificate() }
    var sharedQueue: DispatchQueue { queue }

    private func publish(force: Bool) {
        let now = Date()
        if !force && (!dirty || now.timeIntervalSince(lastPublish) < 0.2) { return }
        dirty = false
        lastPublish = now
        let rows = shown.map { $0.snapshot() }
            .sorted { a, b in a.closed == b.closed ? a.lastAt > b.lastAt : (!a.closed && b.closed) }
            .prefix(Self.maxShown)
        onFlows(Array(rows))
    }

    private func key(_ proto: String, _ src: [UInt8], _ srcPort: Int, _ dst: [UInt8], _ dstPort: Int) -> String {
        "\(proto) \(IPCodec.text(src)):\(srcPort)>\(IPCodec.text(dst)):\(dstPort)"
    }

    fileprivate static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    static let maxLinks = 400
    static let maxShown = 400
    static let tcpIdle: TimeInterval = 300
    static let udpIdle: TimeInterval = 60

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
