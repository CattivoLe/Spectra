import Darwin
import Foundation
import Network
import Observation

// Constants from <net/route.h> that are not bridged to Swift.
private nonisolated(unsafe) let RTF_GATEWAY: Int32 = 0x2
private nonisolated(unsafe) let RTF_LLINFO: Int32 = 0x400
private nonisolated(unsafe) let RTA_DST: Int32 = 0x1
private nonisolated(unsafe) let RTA_GATEWAY: Int32 = 0x2

// On arm64 Darwin, sizeof(struct rt_msghdr) = 92.
private nonisolated(unsafe) let kRouteHeaderSize = 92

// MARK: - Subnet info

struct SubnetInfo {
    var localIP: String
    var netmask: String
    var gateway: String?

    var cidr: Int { (parseIP(netmask) ?? 0).nonzeroBitCount }
    var hostCount: Int { max(0, Int(broadcastValue - networkValue) - 1) }

    fileprivate var localIPValue: UInt32 { parseIP(localIP) ?? 0 }
    fileprivate var netmaskValue: UInt32 { parseIP(netmask) ?? 0 }
    fileprivate var networkValue: UInt32 { localIPValue & netmaskValue }
    fileprivate var broadcastValue: UInt32 { networkValue | ~netmaskValue }

    var allHostIPs: [String] {
        guard hostCount > 0, hostCount <= 4094 else { return [] }
        return ((networkValue + 1)..<broadcastValue).map(formatIP)
    }

    static func current() -> SubnetInfo? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(first) }

        var current: UnsafeMutablePointer<ifaddrs>? = first
        while let ifa = current {
            let name = String(cString: ifa.pointee.ifa_name)
            if name == "en0",
               let sa = ifa.pointee.ifa_addr,
               sa.pointee.sa_family == UInt8(AF_INET) {
                let sin = UnsafeRawPointer(sa)
                    .assumingMemoryBound(to: sockaddr_in.self).pointee
                let mask = UnsafeRawPointer(ifa.pointee.ifa_netmask)
                    .assumingMemoryBound(to: sockaddr_in.self).pointee
                let ip = formatAddr(sin.sin_addr)
                let nm = formatAddr(mask.sin_addr)
                let gw = defaultGateway() ?? guessGateway(ip: ip, mask: nm)
                return SubnetInfo(localIP: ip, netmask: nm, gateway: gw)
            }
            current = ifa.pointee.ifa_next
        }
        return nil
    }
}

// MARK: - IP helpers

nonisolated private func parseIP(_ ip: String) -> UInt32? {
    var addr = in_addr()
    guard inet_pton(AF_INET, ip, &addr) == 1 else { return nil }
    return UInt32(bigEndian: addr.s_addr)
}

nonisolated private func formatIP(_ value: UInt32) -> String {
    let addr = in_addr(s_addr: value.bigEndian)
    return formatAddr(addr)
}

nonisolated private func formatAddr(_ addr: in_addr) -> String {
    var a = addr
    var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
    inet_ntop(AF_INET, &a, &buf, socklen_t(buf.count))
    return String(cString: buf)
}

// MARK: - Gateway

nonisolated private func defaultGateway() -> String? {
    var mib: [Int32] = [CTL_NET, PF_ROUTE, 0, AF_INET, NET_RT_FLAGS, RTF_GATEWAY]
    var needed = 0
    guard sysctl(&mib, 6, nil, &needed, nil, 0) == 0, needed > 0 else { return nil }

    let buf = UnsafeMutableRawPointer.allocate(byteCount: needed, alignment: 8)
    defer { buf.deallocate() }
    guard sysctl(&mib, 6, buf, &needed, nil, 0) == 0 else { return nil }

    var ptr = buf
    let end = buf.advanced(by: needed)

    while ptr < end {
        let msgLen = Int(ptr.load(fromByteOffset: 0, as: UInt16.self))
        guard msgLen > kRouteHeaderSize else { break }

        let addrs = ptr.load(fromByteOffset: 12, as: Int32.self)
        if addrs & RTA_DST != 0, addrs & RTA_GATEWAY != 0 {
            let saBase = ptr.advanced(by: kRouteHeaderSize)
            let dst = saBase.load(as: sockaddr_in.self)
            if dst.sin_family == UInt8(AF_INET), dst.sin_addr.s_addr == 0 {
                let dstLen = max(Int(dst.sin_len), MemoryLayout<sockaddr_in>.size)
                let gwPtr = saBase.advanced(by: roundUp(dstLen))
                let gw = gwPtr.load(as: sockaddr_in.self)
                if gw.sin_family == UInt8(AF_INET) {
                    return formatAddr(gw.sin_addr)
                }
            }
        }
        ptr = ptr.advanced(by: msgLen)
    }
    return nil
}

nonisolated private func guessGateway(ip: String, mask: String) -> String? {
    guard let ipVal = parseIP(ip), let maskVal = parseIP(mask) else { return nil }
    let network = ipVal & maskVal
    return formatIP(network + 1)
}

nonisolated private func roundUp(_ size: Int) -> Int {
    let align = MemoryLayout<Int>.size
    return (size + align - 1) & ~(align - 1)
}

// MARK: - ARP table

private struct ARPEntry {
    var ip: String
    /// Nil when the OS hides the hardware address (iOS often does).
    var mac: MACAddress?
}

nonisolated private func readARPTable() -> [ARPEntry] {
    var mib: [Int32] = [CTL_NET, PF_ROUTE, 0, AF_INET, NET_RT_FLAGS, RTF_LLINFO]
    var needed = 0
    guard sysctl(&mib, 6, nil, &needed, nil, 0) == 0, needed > 0 else { return [] }

    let buf = UnsafeMutableRawPointer.allocate(byteCount: needed, alignment: 8)
    defer { buf.deallocate() }
    guard sysctl(&mib, 6, buf, &needed, nil, 0) == 0 else { return [] }

    var entries: [ARPEntry] = []
    var ptr = buf
    let end = buf.advanced(by: needed)

    while ptr < end {
        let msgLen = Int(ptr.load(fromByteOffset: 0, as: UInt16.self))
        guard msgLen > kRouteHeaderSize else { break }

        let sinPtr = ptr.advanced(by: kRouteHeaderSize)
        let sin = sinPtr.load(as: sockaddr_in.self)
        guard sin.sin_family == UInt8(AF_INET) else {
            ptr = ptr.advanced(by: msgLen)
            continue
        }

        let ip = formatAddr(sin.sin_addr)
        let sinLen = max(Int(sin.sin_len), MemoryLayout<sockaddr_in>.size)
        let sdlPtr = sinPtr.advanced(by: roundUp(sinLen))

        let sdl = sdlPtr.load(as: sockaddr_dl.self)
        // An address length of 6 means the entry is resolved: the host
        // answered ARP, whether or not it answers anything else.
        if sdl.sdl_alen == 6 {
            let macBase = sdlPtr.advanced(by: 8 + Int(sdl.sdl_nlen))
            let mac = (0..<6).map { macBase.load(fromByteOffset: $0, as: UInt8.self) }
            let hidden = mac.allSatisfy { $0 == 0 } || mac == [0x02, 0, 0, 0, 0, 0]
            entries.append(ARPEntry(ip: ip, mac: hidden ? nil : MACAddress(bytes: mac)))
        }

        ptr = ptr.advanced(by: msgLen)
    }
    return entries
}

// MARK: - TCP probing

nonisolated enum TCPProbe: Sendable {
    /// The port accepted the connection.
    case open
    /// The host answered with a reset: it is there, the port is closed.
    case refused
    /// Nothing came back in time (host absent, or a firewall drops packets).
    case silent
}

/// Starts a non-blocking connect to every target at once and waits on all of
/// them with a single `poll`, so a batch costs one timeout rather than one per
/// target. Blocking — call off the main thread.
nonisolated private func probeTCP(_ targets: [(ip: String, port: UInt16)], timeout: Duration) -> [TCPProbe] {
    var outcomes = [TCPProbe](repeating: .silent, count: targets.count)
    var pending: [pollfd] = []
    var pendingIndex: [Int] = []

    for (index, target) in targets.enumerated() {
        guard let ipVal = parseIP(target.ip) else { continue }
        let sock = socket(AF_INET, SOCK_STREAM, 0)
        guard sock >= 0 else { continue }

        let flags = fcntl(sock, F_GETFL, 0)
        _ = fcntl(sock, F_SETFL, flags | O_NONBLOCK)
        var on: Int32 = 1
        setsockopt(sock, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        // Reset instead of a graceful close: no half-open sessions left on the
        // device, no TIME_WAIT sockets piling up here.
        var linger = Darwin.linger(l_onoff: 1, l_linger: 0)
        setsockopt(sock, SOL_SOCKET, SO_LINGER, &linger, socklen_t(MemoryLayout<Darwin.linger>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = target.port.bigEndian
        addr.sin_addr.s_addr = ipVal.bigEndian

        let result = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Darwin.connect(sock, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if result == 0 {
            outcomes[index] = .open
            close(sock)
        } else if errno == EINPROGRESS {
            pending.append(pollfd(fd: sock, events: Int16(POLLOUT), revents: 0))
            pendingIndex.append(index)
        } else {
            if errno == ECONNREFUSED { outcomes[index] = .refused }
            close(sock)
        }
    }

    let deadline = ContinuousClock.now + timeout
    var remaining = pending.count
    while remaining > 0 {
        let left = deadline - .now
        guard left > .zero else { break }
        let ms = Int32(max(1, (left / .milliseconds(1)).rounded(.up)))
        let ready = poll(&pending, nfds_t(pending.count), ms)
        if ready < 0, errno == EINTR { continue }
        guard ready > 0 else { break }

        for k in pending.indices where pending[k].fd >= 0 && pending[k].revents != 0 {
            var error: Int32 = 0
            var errorLen = socklen_t(MemoryLayout<Int32>.size)
            getsockopt(pending[k].fd, SOL_SOCKET, SO_ERROR, &error, &errorLen)
            switch error {
            case 0: outcomes[pendingIndex[k]] = .open
            case ECONNREFUSED: outcomes[pendingIndex[k]] = .refused
            default: break
            }
            close(pending[k].fd)
            pending[k].fd = -1  // poll skips negative descriptors
            remaining -= 1
        }
    }
    for entry in pending where entry.fd >= 0 {
        close(entry.fd)
    }
    return outcomes
}

/// How many sockets one probe batch may hold open. Raises the descriptor
/// limit (256 by default) so a /24 sweep takes a few rounds, not dozens.
nonisolated private let probeBatchSize: Int = {
    var limit = rlimit()
    getrlimit(RLIMIT_NOFILE, &limit)
    let wanted = min(limit.rlim_max, 1024)
    if limit.rlim_cur < wanted {
        limit.rlim_cur = wanted
        setrlimit(RLIMIT_NOFILE, &limit)
        getrlimit(RLIMIT_NOFILE, &limit)
    }
    // Leave room for the descriptors the rest of the app needs.
    return min(512, max(64, Int(limit.rlim_cur) - 192))
}()

nonisolated private func runInBackground<T: Sendable>(_ work: @escaping @Sendable () -> T) async -> T {
    await withCheckedContinuation { continuation in
        DispatchQueue.global(qos: .userInitiated).async {
            continuation.resume(returning: work())
        }
    }
}

/// Probes in batches that fit the socket budget, reporting progress in 0...1.
nonisolated private func probeTCP(
    _ targets: [(ip: String, port: UInt16)],
    timeout: Duration,
    progress: @MainActor (Double) -> Void = { _ in }
) async -> [TCPProbe] {
    var outcomes: [TCPProbe] = []
    outcomes.reserveCapacity(targets.count)
    for start in stride(from: 0, to: targets.count, by: probeBatchSize) {
        if Task.isCancelled { break }
        let batch = Array(targets[start..<min(start + probeBatchSize, targets.count)])
        outcomes += await runInBackground { probeTCP(batch, timeout: timeout) }
        await progress(Double(outcomes.count) / Double(targets.count))
    }
    outcomes += [TCPProbe](repeating: .silent, count: targets.count - outcomes.count)
    return outcomes
}

// MARK: - Hostname resolution

nonisolated private func resolveHostname(_ ip: String) -> String? {
    var addr = sockaddr_in()
    addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    addr.sin_family = sa_family_t(AF_INET)
    inet_pton(AF_INET, ip, &addr.sin_addr)

    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
    let result = withUnsafePointer(to: &addr) { ptr in
        ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
            getnameinfo(sa, socklen_t(MemoryLayout<sockaddr_in>.size),
                        &hostname, socklen_t(hostname.count),
                        nil, 0, NI_NAMEREQD)
        }
    }
    guard result == 0 else { return nil }
    let name = String(cString: hostname)
    return name == ip ? nil : name
}

// MARK: - Bonjour

/// Service types to browse, most telling names first. Phones, Apple TVs and
/// smart-home gear often drop every probe but still advertise these. Keep in
/// sync with NSBonjourServices in Config/Spectra-Info.plist.
nonisolated private let bonjourServiceTypes = [
    "_device-info._tcp", "_companion-link._tcp", "_airplay._tcp", "_raop._tcp",
    "_googlecast._tcp", "_hap._tcp", "_spotify-connect._tcp", "_ipp._tcp",
    "_printer._tcp", "_smb._tcp", "_ssh._tcp", "_http._tcp",
]

/// Browses `bonjourServiceTypes` on Wi-Fi and resolves every instance to an
/// IPv4 address. All state is touched only on `queue`, which also runs the
/// browser and connection handlers.
nonisolated private final class BonjourDiscovery: @unchecked Sendable {
    private let queue = DispatchQueue(label: "Spectra.Bonjour")
    private var browsers: [NWBrowser] = []
    private var connections: [NWConnection] = []
    private var resolving: Set<NWEndpoint> = []
    private var namesByIP: [String: [(rank: Int, name: String)]] = [:]
    private var completion: (@Sendable ([String: [String]]) -> Void)?
    private var finished = false

    func start(timeout: TimeInterval, completion: @escaping @Sendable ([String: [String]]) -> Void) {
        queue.async { [self] in
            guard !finished else { return completion([:]) }
            self.completion = completion
            let params = NWParameters()
            params.requiredInterfaceType = .wifi
            for (rank, type) in bonjourServiceTypes.enumerated() {
                let browser = NWBrowser(for: .bonjour(type: type, domain: "local."), using: params)
                browser.browseResultsChangedHandler = { [weak self] _, changes in
                    for case .added(let result) in changes {
                        self?.resolve(result.endpoint, rank: rank)
                    }
                }
                browser.start(queue: queue)
                browsers.append(browser)
            }
            queue.asyncAfter(deadline: .now() + timeout) { [weak self] in self?.finish() }
        }
    }

    func cancel() {
        queue.async { [self] in finish() }
    }

    /// Network.framework has no bare resolve call, so a TCP connection to the
    /// service does it; the address it reaches is the one we want.
    private func resolve(_ endpoint: NWEndpoint, rank: Int) {
        guard !finished, case .service(let name, _, _, _) = endpoint,
              resolving.insert(endpoint).inserted else { return }

        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = 3
        let params = NWParameters(tls: nil, tcp: tcp)
        params.requiredInterfaceType = .wifi
        if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options {
            ip.version = .v4
        }

        let connection = NWConnection(to: endpoint, using: params)
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready:
                if case .hostPort(host: .ipv4(let address), port: _) = connection.currentPath?.remoteEndpoint {
                    record(name, rank: rank, ip: address.rawValue.map(String.init).joined(separator: "."))
                }
                connection.cancel()
            case .waiting, .failed:
                connection.cancel()
            default:
                break
            }
        }
        connection.start(queue: queue)
        connections.append(connection)
    }

    private func record(_ name: String, rank: Int, ip: String) {
        // AirPlay audio names its instances "<MAC>@<device name>".
        var clean = name
        if let at = name.firstIndex(of: "@"),
           name[..<at].count == 12, name[..<at].allSatisfy(\.isHexDigit) {
            clean = String(name[name.index(after: at)...])
        }
        guard !clean.isEmpty else { return }
        namesByIP[ip, default: []].append((rank, clean))
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        browsers.forEach { $0.cancel() }
        connections.forEach { $0.cancel() }
        browsers = []
        connections = []

        let result = namesByIP.mapValues { entries in
            var seen: Set<String> = []
            return entries.sorted { $0.rank < $1.rank }
                .map(\.name)
                .filter { seen.insert($0).inserted }
        }
        completion?(result)
        completion = nil
    }
}

/// Bonjour instance names by IPv4 address, gathered for `timeout` seconds.
nonisolated private func discoverBonjourHosts(timeout: TimeInterval) async -> [String: [String]] {
    let discovery = BonjourDiscovery()
    return await withTaskCancellationHandler {
        await withCheckedContinuation { continuation in
            discovery.start(timeout: timeout) { continuation.resume(returning: $0) }
        }
    } onCancel: {
        discovery.cancel()
    }
}

// MARK: - Scanner

@Observable
final class LANScanner {
    enum State: Equatable {
        case idle
        case noNetwork
        case scanning(progress: Double, phase: String)
        case done
    }

    private(set) var state: State = .idle
    private(set) var devices: [String: LANDevice] = [:]
    private(set) var subnetInfo: SubnetInfo?
    private(set) var scanCount = 0
    private(set) var lastScanDate: Date?

    private var scanTask: Task<Void, Never>?

    var sortedDevices: [LANDevice] {
        devices.values.sorted { a, b in
            if a.isLocalDevice != b.isLocalDevice { return a.isLocalDevice }
            if a.isGateway != b.isGateway { return a.isGateway }
            let aIP = parseIP(a.ipAddress) ?? 0
            let bIP = parseIP(b.ipAddress) ?? 0
            return aIP < bIP
        }
    }

    var isScanning: Bool {
        if case .scanning = state { return true }
        return false
    }

    // MARK: Actions

    func scan() {
        scanTask?.cancel()
        scanTask = Task { @MainActor in await performScan() }
    }

    func stop() {
        scanTask?.cancel()
        scanTask = nil
        if isScanning { state = .idle }
    }

    func scanPorts(for ip: String) {
        guard let device = devices[ip] else { return }
        Task { @MainActor in
            device.portsScanned = false
            device.openPorts = []
            let ports = await scanPorts(on: [ip])[ip] ?? []
            device.openPorts = ports
            device.portsScanned = true
        }
    }

    func clear() {
        devices = [:]
        subnetInfo = nil
        scanCount = 0
        lastScanDate = nil
        state = .idle
    }

    // MARK: Demo

    func loadDemo() {
        let now = Date.now
        subnetInfo = SubnetInfo(localIP: "192.168.1.42", netmask: "255.255.255.0", gateway: "192.168.1.1")
        let demoDevices: [(String, [UInt8], String?, Bool, Bool, [DiscoveredPort])] = [
            ("192.168.1.1",   [0x50, 0xC7, 0xBF, 0x12, 0x34, 0x56], "TL-WR841N.local",    true,  false, [.init(number: 80, service: "HTTP"), .init(number: 443, service: "HTTPS")]),
            ("192.168.1.42",  [0xF0, 0x18, 0x98, 0xAA, 0xBB, 0xCC], nil,                   false, true,  []),
            ("192.168.1.10",  [0x3C, 0x22, 0xFB, 0x11, 0x22, 0x33], "Alexs-MBP.local",     false, false, [.init(number: 22, service: "SSH"), .init(number: 5900, service: "VNC")]),
            ("192.168.1.15",  [0x08, 0x08, 0xC2, 0x44, 0x55, 0x66], "Samsung-TV.local",    false, false, [.init(number: 8001, service: "HTTP"), .init(number: 8002, service: "HTTPS")]),
            ("192.168.1.20",  [0x48, 0xD6, 0xD5, 0x77, 0x88, 0x99], "Chromecast.local",    false, false, [.init(number: 8008, service: "HTTP")]),
            ("192.168.1.25",  [0x68, 0x54, 0xFD, 0xDD, 0xEE, 0xFF], "Echo-Dot.local",      false, false, [.init(number: 443, service: "HTTPS"), .init(number: 8443, service: "HTTPS")]),
            ("192.168.1.30",  [0x3C, 0xD9, 0x2B, 0x01, 0x02, 0x03], "HP-Printer.local",    false, false, [.init(number: 80, service: "HTTP"), .init(number: 443, service: "HTTPS"), .init(number: 631, service: "IPP"), .init(number: 9100, service: "Printer")]),
            ("192.168.1.35",  [0xB8, 0x27, 0xEB, 0x04, 0x05, 0x06], "raspberrypi.local",   false, false, [.init(number: 22, service: "SSH"), .init(number: 80, service: "HTTP")]),
            ("192.168.1.40",  [0x30, 0xAE, 0xA4, 0x07, 0x08, 0x09], "ESP32-Sensor.local",  false, false, []),
            ("192.168.1.50",  [0x78, 0x28, 0xCA, 0x0A, 0x0B, 0x0C], "Sonos-One.local",     false, false, [.init(number: 1443, service: "HTTPS"), .init(number: 1400, service: "HTTP")]),
            ("192.168.1.55",  [0x00, 0x17, 0x88, 0x0D, 0x0E, 0x0F], "Philips-hue.local",   false, false, [.init(number: 80, service: "HTTP"), .init(number: 443, service: "HTTPS")]),
        ]
        var result: [String: LANDevice] = [:]
        for (ip, mac, hostname, gw, local, ports) in demoDevices {
            result[ip] = LANDevice(
                ipAddress: ip,
                macAddress: MACAddress(bytes: mac),
                hostnames: hostname.map { [$0] } ?? [],
                openPorts: ports,
                portsScanned: !ports.isEmpty,
                isGateway: gw,
                isLocalDevice: local,
                firstSeen: now,
                lastSeen: now
            )
        }
        devices = result
        scanCount = 1
        lastScanDate = now
        state = .done
    }

    // MARK: - Scan implementation

    private func performScan() async {
        #if targetEnvironment(simulator)
        loadDemo()
        return
        #endif

        guard let info = SubnetInfo.current() else {
            state = .noNetwork
            return
        }
        subnetInfo = info

        let hosts = info.allHostIPs
        guard !hosts.isEmpty else {
            state = .noNetwork
            return
        }

        state = .scanning(progress: 0, phase: String(localized: "Probing the subnet…"))

        // Phase 1: UDP probe to populate ARP cache (works on older iOS).
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            DispatchQueue.global(qos: .userInitiated).async {
                let sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
                guard sock >= 0 else { cont.resume(); return }
                defer { close(sock) }
                let flags = fcntl(sock, F_GETFL, 0)
                _ = fcntl(sock, F_SETFL, flags | O_NONBLOCK)

                var payload: UInt8 = 0
                for ip in hosts {
                    guard let ipVal = parseIP(ip) else { continue }
                    var addr = sockaddr_in()
                    addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
                    addr.sin_family = sa_family_t(AF_INET)
                    addr.sin_port = UInt16(7).bigEndian
                    addr.sin_addr.s_addr = ipVal.bigEndian
                    withUnsafePointer(to: &addr) { ptr in
                        ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                            _ = sendto(sock, &payload, 1, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                        }
                    }
                }
                cont.resume()
            }
        }

        if Task.isCancelled { return }

        // Phase 2: Try ARP table (bonus — may be empty on iOS 18+).
        state = .scanning(progress: 0.1, phase: String(localized: "Reading ARP table…"))
        try? await Task.sleep(for: .seconds(1))
        if Task.isCancelled { return }

        let now = Date.now
        let hostSet = Set(hosts)
        var arpByIP: [String: MACAddress] = [:]
        var aliveIPs: Set<String> = []
        func absorbARP() {
            for entry in readARPTable() where hostSet.contains(entry.ip) {
                aliveIPs.insert(entry.ip)
                if let mac = entry.mac { arpByIP[entry.ip] = mac }
            }
        }
        absorbARP()

        // Phase 3: TCP connect discovery — the primary method on iOS 18+.
        // Any answer, even a reset, proves the host is there, so a few ports
        // that different kinds of devices tend to listen on are tried at once.
        state = .scanning(progress: 0.2, phase: String(localized: "Discovering devices…"))

        // Bonjour browses meanwhile, for hosts that drop every probe.
        async let bonjourResult = discoverBonjourHosts(timeout: 3)

        let discoveryTargets = hosts.flatMap { ip in
            Self.discoveryPorts.map { (ip: ip, port: $0) }
        }
        let discovery = await probeTCP(discoveryTargets, timeout: .milliseconds(1500)) { fraction in
            self.state = .scanning(progress: 0.2 + 0.4 * fraction, phase: String(localized: "Discovering devices…"))
        }
        let bonjourNames = await bonjourResult.filter { hostSet.contains($0.key) }
        if Task.isCancelled { return }
        for (target, outcome) in zip(discoveryTargets, discovery) where outcome != .silent {
            aliveIPs.insert(target.ip)
        }
        aliveIPs.formUnion(bonjourNames.keys)

        // Phase 4: Re-read ARP after TCP probes — a host that drops every
        // connection still had to answer ARP for them to be sent.
        absorbARP()
        aliveIPs.insert(info.localIP)

        // Build device list.
        var newDevices: [String: LANDevice] = [:]
        for ip in aliveIPs {
            let existing = devices[ip]
            newDevices[ip] = LANDevice(
                ipAddress: ip,
                macAddress: arpByIP[ip] ?? existing?.macAddress,
                hostnames: existing?.hostnames ?? [],
                openPorts: existing?.openPorts ?? [],
                portsScanned: existing?.portsScanned ?? false,
                isGateway: ip == info.gateway,
                isLocalDevice: ip == info.localIP,
                firstSeen: existing?.firstSeen ?? now,
                lastSeen: now
            )
        }

        devices = newDevices

        // Phase 5: Open ports on every host found.
        state = .scanning(progress: 0.6, phase: String(localized: "Scanning ports…"))
        let found = newDevices.keys.sorted { (parseIP($0) ?? 0) < (parseIP($1) ?? 0) }
        let portsByIP = await scanPorts(on: found) { fraction in
            self.state = .scanning(progress: 0.6 + 0.3 * fraction, phase: String(localized: "Scanning ports…"))
        }
        if Task.isCancelled { return }
        for (ip, ports) in portsByIP {
            newDevices[ip]?.openPorts = ports
            newDevices[ip]?.portsScanned = true
        }

        // Phase 6: Hostname resolution.
        state = .scanning(progress: 0.9, phase: String(localized: "Resolving names…"))

        await withTaskGroup(of: (String, String?).self) { group in
            for (ip, device) in newDevices where device.hostnames.isEmpty {
                group.addTask { @Sendable in
                    (ip, resolveHostname(ip))
                }
            }
            for await (ip, hostname) in group {
                if let hostname, let device = devices[ip], device.hostnames.isEmpty {
                    device.hostnames = [hostname]
                }
            }
        }
        for (ip, names) in bonjourNames {
            if let device = newDevices[ip], device.hostnames.isEmpty {
                device.hostnames = names
            }
        }

        scanCount += 1
        lastScanDate = now
        state = .done
    }

    // MARK: - Port scanning

    /// Ports tried on every address to tell whether a host is there: web,
    /// SSH, SMB, iPhone sync, AirPlay, RTSP cameras and printers.
    private static let discoveryPorts: [UInt16] = [80, 443, 22, 445, 62078, 7000, 554, 8080, 9100]

    private func scanPorts(
        on ips: [String],
        progress: @MainActor (Double) -> Void = { _ in }
    ) async -> [String: [DiscoveredPort]] {
        let targets = ips.flatMap { ip in
            DiscoveredPort.commonPorts.map { (ip: ip, port: UInt16($0.port)) }
        }
        let outcomes = await probeTCP(targets, timeout: .milliseconds(1500), progress: progress)
        if Task.isCancelled { return [:] }

        var result = Dictionary(uniqueKeysWithValues: ips.map { ($0, [DiscoveredPort]()) })
        for (index, outcome) in outcomes.enumerated() where outcome == .open {
            let (ip, entry) = (targets[index].ip, DiscoveredPort.commonPorts[index % DiscoveredPort.commonPorts.count])
            result[ip]?.append(DiscoveredPort(number: entry.port, service: entry.name))
        }
        return result
    }
}
