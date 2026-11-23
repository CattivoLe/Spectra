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

    /// Network address and prefix, e.g. `192.168.1.0/24`. Stable across a DHCP
    /// lease change, unlike `localIP`.
    var networkLabel: String { "\(formatIP(networkValue))/\(cidr)" }

    /// Whether Wi-Fi has a global IPv6 address: joined to a network that
    /// simply offers no IPv4.
    static func hasRoutableIPv6(interface: String = "en0") -> Bool {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return false }
        defer { freeifaddrs(first) }
        var current: UnsafeMutablePointer<ifaddrs>? = first
        while let ifa = current {
            if String(cString: ifa.pointee.ifa_name) == interface,
               let sa = ifa.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET6) {
                let bytes = sa.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) {
                    withUnsafeBytes(of: $0.pointee.sin6_addr) { Array($0) }
                }
                // Link-local fe80::/10 is on every interface; 2000::/3 is global.
                if bytes[0] & 0xE0 == 0x20 { return true }
            }
            current = ifa.pointee.ifa_next
        }
        return false
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

nonisolated func parseIP(_ ip: String) -> UInt32? {
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
    return LANDevice.usefulHostname(name, ip: ip) == nil ? nil : name
}

// MARK: - Bonjour

/// Service types to browse, most telling names first. Phones, Apple TVs and
/// smart-home gear often drop every probe but still advertise these. Keep in
/// sync with NSBonjourServices in Config/Spectra-Info.plist.
nonisolated private let bonjourServiceTypes = [
    "_googlecast._tcp", "_device-info._tcp", "_companion-link._tcp", "_apple-mobdev2._tcp",
    "_airplay._tcp", "_raop._tcp", "_hap._tcp", "_spotify-connect._tcp", "_ipp._tcp",
    "_printer._tcp", "_smb._tcp", "_ssh._tcp", "_http._tcp", Miio.serviceType,
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
    private var servicesByIP: [String: [ServiceAdvertisement]] = [:]
    private var ipByEndpoint: [NWEndpoint: String] = [:]
    private var modelByEndpoint: [NWEndpoint: String] = [:]
    /// Instances no connection could reach, matched to a host by name at the end.
    private var unresolved: [NWEndpoint: Unresolved] = [:]
    private var completion: (@Sendable (BonjourFindings) -> Void)?
    private var finished = false

    func start(timeout: TimeInterval, completion: @escaping @Sendable (BonjourFindings) -> Void) {
        queue.async { [self] in
            guard !finished else { return completion(BonjourFindings()) }
            self.completion = completion
            let params = NWParameters()
            params.requiredInterfaceType = .wifi
            for (rank, type) in bonjourServiceTypes.enumerated() {
                let browser = NWBrowser(for: .bonjourWithTXTRecord(type: type, domain: "local."), using: params)
                browser.browseResultsChangedHandler = { [weak self] _, changes in
                    for change in changes {
                        switch change {
                        case .added(let result), .changed(_, let result, _):
                            self?.note(result, rank: rank, service: type)
                        default:
                            break
                        }
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

    private func note(_ result: NWBrowser.Result, rank: Int, service: String) {
        guard !finished, case .service(let name, _, _, _) = result.endpoint else { return }
        // AirPlay audio keeps the model in "am"; its "md" lists metadata types ("0,1,2").
        let modelValue = service.contains("raop")
            ? bonjourValue(result, key: "am")
            : bonjourValue(result, key: "model") ?? bonjourValue(result, key: "md")
        if let model = modelValue {
            modelByEndpoint[result.endpoint] = model
        }
        let model = modelByEndpoint[result.endpoint]
        let friendly = bonjourValue(result, key: "fn")
        if let ip = ipByEndpoint[result.endpoint] {
            record(name, rank: rank, ip: ip, service: service, model: model, friendly: friendly)
            return
        }
        resolve(result.endpoint, name: name, rank: rank, service: service, model: model, friendly: friendly)
    }

    private func bonjourValue(_ result: NWBrowser.Result, key: String) -> String? {
        guard case .bonjour(let record) = result.metadata else { return nil }
        let value = record[key]?.trimmingCharacters(in: .whitespacesAndNewlines)
        return value?.isEmpty == false ? value : nil
    }

    /// Network.framework has no bare resolve call, so a TCP connection to the
    /// service does it; the address it reaches is the one we want.
    private struct Unresolved {
        var name: String
        var rank: Int
        var service: String
        var model: String?
        var friendly: String?
    }

    private func resolve(_ endpoint: NWEndpoint, name: String, rank: Int, service: String, model: String?, friendly: String?) {
        guard !finished, resolving.insert(endpoint).inserted else { return }
        let pending = Unresolved(name: name, rank: rank, service: service, model: model, friendly: friendly)
        // device-info has no listener (its port is 0), so it never connects.
        // It still carries the model id, like "MacBookPro18,3"; the same
        // instance name on another service says which host it belongs to.
        if service.hasPrefix("_device-info.") {
            unresolved[endpoint] = pending
            return
        }

        let params: NWParameters
        if service.hasSuffix("._udp") {
            // A UDP service (miIO) has no handshake: the connection is ready once the name resolves.
            params = NWParameters.udp
        } else {
            let tcp = NWProtocolTCP.Options()
            tcp.connectionTimeout = 3
            params = NWParameters(tls: nil, tcp: tcp)
        }
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
                    let ip = address.rawValue.map(String.init).joined(separator: ".")
                    ipByEndpoint[endpoint] = ip
                    record(name, rank: rank, ip: ip, service: service, model: modelByEndpoint[endpoint] ?? model, friendly: friendly)
                }
                connection.cancel()
            case .waiting, .failed:
                if ipByEndpoint[endpoint] == nil {
                    var latest = pending
                    latest.model = modelByEndpoint[endpoint] ?? model
                    unresolved[endpoint] = latest
                }
                connection.cancel()
            default:
                break
            }
        }
        connection.start(queue: queue)
        connections.append(connection)
    }

    private func record(_ name: String, rank: Int, ip: String, service: String, model: String?, friendly: String?) {
        // A Mi Home instance name is an identifier with the model in it, never a name to show.
        if service.contains("miio") {
            var ads = servicesByIP[ip] ?? []
            if !ads.contains(where: { $0.type.contains("miio") }) {
                ads.append(ServiceAdvertisement(type: Miio.serviceType, name: "Mi Home", model: Miio.modelFromInstance(name)))
            }
            servicesByIP[ip] = ads
            return
        }
        // AirPlay audio names its instances "<MAC>@<device name>".
        var clean = name
        if let at = name.firstIndex(of: "@"),
           name[..<at].count == 12, name[..<at].allSatisfy(\.isHexDigit) {
            clean = String(name[name.index(after: at)...])
        }
        guard !clean.isEmpty else { return }
        namesByIP[ip, default: []].append((rank, clean))
        if let friendly, !friendly.isEmpty, friendly.caseInsensitiveCompare(clean) != .orderedSame {
            namesByIP[ip, default: []].append((rank, friendly))
        }
        var ads = servicesByIP[ip] ?? []
        if let index = ads.firstIndex(where: { $0.type == service && $0.name == clean }) {
            if ads[index].model == nil { ads[index].model = model }
        } else {
            ads.append(ServiceAdvertisement(type: service, name: clean, model: model))
        }
        servicesByIP[ip] = ads
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        browsers.forEach { $0.cancel() }
        connections.forEach { $0.cancel() }
        browsers = []
        connections = []
        attachUnresolved()

        let names = namesByIP.mapValues { entries in
            var seen: Set<String> = []
            return entries.sorted { $0.rank < $1.rank }
                .map(\.name)
                .filter { seen.insert($0).inserted }
        }
        completion?(BonjourFindings(names: names, services: servicesByIP))
        completion = nil
    }
}

private extension BonjourDiscovery {
    /// Gives each unreachable instance to the one host that advertised the same name elsewhere.
    func attachUnresolved() {
        for (endpoint, entry) in unresolved where ipByEndpoint[endpoint] == nil {
            let owners = servicesByIP.filter { _, ads in
                ads.contains { $0.name.caseInsensitiveCompare(entry.name) == .orderedSame }
            }.map(\.key)
            guard owners.count == 1, let ip = owners.first else { continue }
            record(entry.name, rank: entry.rank, ip: ip, service: entry.service, model: modelByEndpoint[endpoint] ?? entry.model, friendly: entry.friendly)
        }
        unresolved = [:]
    }
}

nonisolated private struct BonjourFindings: Sendable {
    var names: [String: [String]] = [:]
    var services: [String: [ServiceAdvertisement]] = [:]
}

/// Bonjour instance names by IPv4 address, gathered for `timeout` seconds.
nonisolated private func discoverBonjourHosts(timeout: TimeInterval) async -> BonjourFindings {
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
        /// Wi-Fi is up but has no IPv4 subnet, as on an IPv6-only network:
        /// the scan walks IPv4 addresses, so there is nothing to sweep.
        case ipv6Only
        /// The person turned down local network access: nothing on the subnet can be reached.
        case localNetworkDenied
        case scanning(progress: Double, phase: String)
        case done
    }

    private(set) var state: State = .idle
    private(set) var devices: [String: LANDevice] = [:]
    private(set) var subnetInfo: SubnetInfo?
    private(set) var scanCount = 0
    private(set) var lastScanDate: Date?
    /// What the last finished scan learned about new hosts on this network.
    private(set) var lastVerdict: KnownHosts.Verdict?
    /// Hosts every network has shown before, to tell a newcomer apart.
    let knownHosts: KnownHosts
    /// The user's names and verdicts for hosts.
    let labels: DeviceLabels
    /// The Wi-Fi name, when the user lets the app read it.
    let wifi: WiFiInfo
    /// When a host was first met on this network, if it was before.
    @ObservationIgnored var earlier: ((LANDevice, [String]) -> Date?)?
    /// Set while the router is being asked for its port forwardings.
    private(set) var checkingForwarding = false

    private var scanTask: Task<Void, Never>?
    private var portScanTask: Task<Void, Never>?
    private var forwardingTask: Task<Void, Never>?

    init(knownHosts: KnownHosts = KnownHosts(), labels: DeviceLabels = DeviceLabels(), wifi: WiFiInfo = WiFiInfo()) {
        self.knownHosts = knownHosts
        self.labels = labels
        self.wifi = wifi
    }

    /// This iPhone, the gateway, then newcomers, then everything else by address.
    var sortedDevices: [LANDevice] {
        devices.values.sorted { a, b in
            if a.isLocalDevice != b.isLocalDevice { return a.isLocalDevice }
            if a.isGateway != b.isGateway { return a.isGateway }
            if a.isNew != b.isNew { return a.isNew }
            let aIP = parseIP(a.ipAddress) ?? 0
            let bIP = parseIP(b.ipAddress) ?? 0
            return aIP < bIP
        }
    }

    /// Stable identity of the joined network, so a re-scan of the same network
    /// enriches its history session instead of starting a new one. Prefers the
    /// gateway's hardware address; falls back to the subnet.
    var networkKey: String? {
        if let ssid = wifi.current?.ssid, !ssid.isEmpty {
            return "lan:ssid:\(ssid)"
        }
        if let gateway = devices.values.first(where: { $0.isGateway })?.macAddress {
            return "lan:gw:\(gateway.description)"
        }
        guard let subnet = subnetInfo else { return nil }
        return "lan:net:\(subnet.networkLabel)"
    }

    /// Every key this network may have been saved under: before its name was readable, or its router's address known.
    var networkKeys: [String] {
        var keys: [String] = []
        if let ssid = wifi.current?.ssid, !ssid.isEmpty { keys.append("lan:ssid:\(ssid)") }
        if let router = gateway?.macAddress { keys.append("lan:gw:\(router.description)") }
        if let subnet = subnetInfo { keys.append("lan:net:\(subnet.networkLabel)") }
        return keys
    }

    /// Human label for the joined network, shown in History's by-place view.
    var networkLabel: String? { wifi.current?.ssid ?? subnetInfo?.networkLabel }

    var gateway: LANDevice? { devices.values.first { $0.isGateway } }

    var isScanning: Bool {
        if case .scanning = state { return true }
        return false
    }

    /// Hosts marked new since the list was last cleared, strongest claim first.
    var newDevices: [LANDevice] { sortedDevices.filter(\.isNew) }

    /// Whether this network has a baseline to compare scans against.
    var hasBaseline: Bool {
        guard let key = networkKey else { return false }
        return knownHosts.knows(network: key)
    }

    /// Drops what is known about the joined network and the New marks with it.
    func forgetKnownHosts() {
        guard let key = networkKey else { return }
        knownHosts.forget(network: key)
        for device in devices.values { device.isNew = false }
        lastVerdict = nil
    }

    /// Asks the router for its UPnP port-forwarding table again, by hand.
    func checkPortForwarding() {
        forwardingTask?.cancel()
        forwardingTask = Task { @MainActor in await refreshPortForwarding() }
    }

    /// Reads the gateway's forwardings and notes on every host which rules
    /// point at it. Quiet when there is no gateway or it does not speak UPnP.
    private func refreshPortForwarding(now: Date = .now) async {
        guard let router = gateway else { return }
        checkingForwarding = true
        defer { checkingForwarding = false }
        let ip = router.ipAddress
        let table = await runInBackground { LANProbes.portForwarding(gateway: ip, now: now) }
        guard !Task.isCancelled, devices[ip] === router else { return }
        router.profile.portForwarding = table ?? PortForwarding(checkedAt: now, supported: false, externalIP: nil, mappings: [])
        distributeForwardings()
    }

    private func distributeForwardings() {
        let rules = gateway?.profile.portForwarding?.mappings.filter(\.enabled) ?? []
        for device in devices.values where !device.isGateway {
            let mine = rules.filter { $0.internalClient == device.ipAddress }
            if device.profile.inbound != mine { device.profile.inbound = mine }
        }
    }

    /// Marks the hosts this network has not shown before and records the rest.
    private func classifyNewHosts(now: Date) {
        guard let key = networkKey else { return }
        lastVerdict = knownHosts.classify(Array(devices.values), network: key, now: now)
        // The names and hardware addresses are in by now, so the hosts can be told apart across scans.
        for device in devices.values {
            let dates = [knownHosts.firstSeen(device, network: key), earlier?(device, networkKeys)].compactMap { $0 }
            if let date = dates.min(), date < device.firstSeen { device.firstSeen = date }
        }
    }

    // MARK: Actions

    func scan() {
        scanTask?.cancel()
        portScanTask?.cancel()
        scanTask = Task { @MainActor in await performScan() }
    }

    func stop() {
        scanTask?.cancel()
        portScanTask?.cancel()
        scanTask = nil
        if isScanning { state = .idle }
    }

    /// `common` probes the short list. `all` probes every TCP port on this one host.
    func scanPorts(for ip: String, scope: PortScope = .common) {
        guard let device = devices[ip], !isScanning else { return }
        portScanTask?.cancel()
        portScanTask = Task { @MainActor in
            device.portScanFraction = 0
            let ports: [DiscoveredPort]
            switch scope {
            case .common:
                let found = await scanPorts(on: [ip]) { fraction in
                    device.portScanFraction = fraction
                }
                ports = found[ip] ?? []
            case .all:
                ports = await scanEveryPort(on: ip) { fraction in
                    device.portScanFraction = fraction
                }
            }
            let miio = await Self.miioHello([ip])
            guard !Task.isCancelled, devices[ip] === device else {
                device.portScanFraction = nil
                return
            }
            device.openPorts = miio.contains(ip) ? Self.mergedPorts(ports + [Miio.discoveredPort], kept: []) : ports
            device.portsScanned = true
            device.allPortsScannedAt = scope == .all ? .now : nil
            if miio.contains(ip), !device.services.contains(where: { $0.type.contains("miio") }) {
                device.services.append(ServiceAdvertisement(type: Miio.serviceType, name: "Mi Home"))
            }
            device.portScanFraction = nil
            await enrich([device])
        }
    }

    enum PortScope {
        case common
        case all
    }

    func clear() {
        portScanTask?.cancel()
        devices = [:]
        subnetInfo = nil
        scanCount = 0
        lastScanDate = nil
        lastVerdict = nil
        state = .idle
    }

    // MARK: Demo

    func loadDemo() {
        let now = Date.now
        Task { await wifi.refresh() }
        subnetInfo = SubnetInfo(localIP: "192.168.1.42", netmask: "255.255.255.0", gateway: "192.168.1.1")
        let demoDevices: [(String, [UInt8], String?, Bool, Bool, [DiscoveredPort])] = [
            ("192.168.1.1",   [0x50, 0xC7, 0xBF, 0x12, 0x34, 0x56], "TL-WR841N.local",    true,  false, [.init(number: 80, service: "HTTP"), .init(number: 443, service: "HTTPS")]),
            ("192.168.1.42",  [0xF0, 0x18, 0x98, 0xAA, 0xBB, 0xCC], nil,                   false, true,  []),
            ("192.168.1.10",  [0x3C, 0x22, 0xFB, 0x11, 0x22, 0x33], "MacBook-Pro.local",   false, false, [.init(number: 22, service: "SSH"), .init(number: 5900, service: "VNC")]),
            ("192.168.1.15",  [0x08, 0x08, 0xC2, 0x44, 0x55, 0x66], "Samsung-TV.local",    false, false, [.init(number: 8001, service: "HTTP"), .init(number: 8002, service: "HTTPS")]),
            ("192.168.1.20",  [0x48, 0xD6, 0xD5, 0x77, 0x88, 0x99], "Chromecast.local",    false, false, [.init(number: 8008, service: "HTTP")]),
            ("192.168.1.25",  [0x68, 0x54, 0xFD, 0xDD, 0xEE, 0xFF], "Echo-Dot.local",      false, false, [.init(number: 443, service: "HTTPS"), .init(number: 8443, service: "HTTPS")]),
            ("192.168.1.30",  [0x3C, 0xD9, 0x2B, 0x01, 0x02, 0x03], "HP-Printer.local",    false, false, [.init(number: 80, service: "HTTP"), .init(number: 443, service: "HTTPS"), .init(number: 631, service: "IPP"), .init(number: 9100, service: "Printer")]),
            ("192.168.1.35",  [0xB8, 0x27, 0xEB, 0x04, 0x05, 0x06], "raspberrypi.local",   false, false, [.init(number: 22, service: "SSH"), .init(number: 80, service: "HTTP")]),
            ("192.168.1.40",  [0x30, 0xAE, 0xA4, 0x07, 0x08, 0x09], "ESP32-Sensor.local",  false, false, []),
            ("192.168.1.50",  [0x78, 0x28, 0xCA, 0x0A, 0x0B, 0x0C], "Sonos-One.local",     false, false, [.init(number: 1443, service: "HTTPS"), .init(number: 1400, service: "HTTP")]),
            ("192.168.1.55",  [0x00, 0x17, 0x88, 0x0D, 0x0E, 0x0F], "Philips-hue.local",   false, false, [.init(number: 80, service: "HTTP"), .init(number: 443, service: "HTTPS")]),
            ("192.168.1.60",  [0x44, 0x19, 0xB6, 0x10, 0x20, 0x30], nil,                   false, false, [.init(number: 80, service: "HTTP"), .init(number: 554, service: "RTSP"), .init(number: 8000, service: "HTTP")]),
            ("192.168.1.70",  [0x8C, 0xEC, 0x4B, 0x21, 0x32, 0x43], nil,                   false, false, [.init(number: 445, service: "SMB"), .init(number: 139, service: "NetBIOS")]),
        ]
        var demo = demoDevices
        // A rescan brings in one host the first demo did not have, so the
        // New mark can be seen in the simulator.
        if !devices.isEmpty {
            demo.append(("192.168.1.80", [0x02, 0x9A, 0x11, 0xF2, 0x33, 0x44], nil, false, false, [.init(number: 22, service: "SSH"), .init(number: 23, service: "Telnet")]))
        }
        var result: [String: LANDevice] = [:]
        for (ip, mac, hostname, gw, local, ports) in demo {
            result[ip] = LANDevice(
                ipAddress: ip,
                macAddress: MACAddress(bytes: mac),
                hostnames: hostname.map { [$0] } ?? [],
                openPorts: ports,
                portsScanned: !ports.isEmpty,
                isGateway: gw,
                isLocalDevice: local,
                isNew: devices[ip]?.isNew ?? false,
                firstSeen: now,
                lastSeen: now
            )
        }
        result["192.168.1.60"]?.profile = LANProfile(
            upnp: UPnPIdentity(friendlyName: "Front door", manufacturer: "Hikvision", model: "DS-2CD2043", deviceType: "DigitalSecurityCamera", server: "Hikvision-Webs"),
            banners: [HTTPBanner(port: 80, secure: false, status: 200, server: "Webs", poweredBy: nil, title: "Hikvision", realm: "DS-2CD2043")],
            rtsp: [RTSPService(port: 554, server: "Hikvision streaming", methods: "OPTIONS, DESCRIBE, SETUP, PLAY")]
        )
        result["192.168.1.70"]?.profile = LANProfile(
            windows: WindowsHost(computerName: "OFFICE-PC", domain: "HOME", shares: ["Users", "Public"], guestSession: true)
        )
        result["192.168.1.1"]?.profile = LANProfile(
            upnp: UPnPIdentity(friendlyName: "TL-WR841N", manufacturer: "TP-Link", model: "TL-WR841N", deviceType: "InternetGatewayDevice", server: "Linux UPnP/1.0"),
            portForwarding: PortForwarding(checkedAt: now, supported: true, externalIP: "203.0.113.17", mappings: [
                PortMapping(externalPort: 8080, internalPort: 80, internalClient: "192.168.1.60", proto: "TCP", description: "Camera", enabled: true, leaseSeconds: 0, remoteHost: nil),
                PortMapping(externalPort: 3389, internalPort: 3389, internalClient: "192.168.1.70", proto: "TCP", description: "Remote desktop", enabled: true, leaseSeconds: 0, remoteHost: nil),
                PortMapping(externalPort: 51413, internalPort: 51413, internalClient: "192.168.1.10", proto: "TCP", description: "Transmission", enabled: false, leaseSeconds: 0, remoteHost: nil),
            ])
        )
        for device in result.values { labels.apply(to: device) }
        devices = result
        distributeForwardings()
        classifyNewHosts(now: now)
        scanCount += 1
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
            state = SubnetInfo.hasRoutableIPv6() ? .ipv6Only : .noNetwork
            return
        }
        subnetInfo = info
        await wifi.refresh()

        let hosts = info.allHostIPs
        guard !hosts.isEmpty else {
            state = .noNetwork
            return
        }

        // Phase 0: local network access. On the first scan iOS shows its prompt and
        // drops every packet to the subnet until it is answered, so wait for the answer.
        state = .scanning(progress: 0, phase: String(localized: "Waiting for local network access…"))
        if await LocalNetworkAccess.check() == .denied {
            state = .localNetworkDenied
            return
        }
        if Task.isCancelled { return }

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
        async let bonjourResult = discoverBonjourHosts(timeout: 5)
        // Mi Home appliances usually open no TCP port at all: the miIO hello on UDP is their one door.
        async let miioResult = Self.miioHello(hosts)

        let discoveryTargets = hosts.flatMap { ip in
            Self.discoveryPorts.map { (ip: ip, port: $0) }
        }
        let discovery = await probeTCP(discoveryTargets, timeout: .milliseconds(1500)) { fraction in
            self.state = .scanning(progress: 0.2 + 0.25 * fraction, phase: String(localized: "Discovering devices…"))
        }
        for (target, outcome) in zip(discoveryTargets, discovery) where outcome != .silent {
            aliveIPs.insert(target.ip)
        }
        if Task.isCancelled { return }

        // A second pass over the addresses that stayed quiet. The first connects to
        // a host wait on ARP, which a cold cache (the first scan after joining, or
        // a phone asleep) may not finish in time; by now the answers are cached.
        absorbARP()
        let retryTargets = hosts.filter { !aliveIPs.contains($0) }.flatMap { ip in
            Self.discoveryPorts.map { (ip: ip, port: $0) }
        }
        let retry = await probeTCP(retryTargets, timeout: .milliseconds(1500)) { fraction in
            self.state = .scanning(progress: 0.45 + 0.1 * fraction, phase: String(localized: "Discovering devices…"))
        }
        for (target, outcome) in zip(retryTargets, retry) where outcome != .silent {
            aliveIPs.insert(target.ip)
        }
        let bonjour = await bonjourResult
        let miio = await miioResult.intersection(hostSet)
        let bonjourNames = bonjour.names.filter { hostSet.contains($0.key) }
        let bonjourServices = bonjour.services.filter { hostSet.contains($0.key) }
        if Task.isCancelled { return }
        aliveIPs.formUnion(bonjourNames.keys)
        aliveIPs.formUnion(bonjourServices.keys)
        aliveIPs.formUnion(miio)

        // Phase 4: Re-read ARP after TCP probes — a host that drops every
        // connection still had to answer ARP for them to be sent.
        absorbARP()
        aliveIPs.insert(info.localIP)

        // Build device list.
        var newDevices: [String: LANDevice] = [:]
        for ip in aliveIPs {
            let existing = devices[ip]
            let keptNames = (existing?.hostnames ?? []).filter { LANDevice.usefulHostname($0, ip: ip) != nil }
            let device = LANDevice(
                ipAddress: ip,
                macAddress: arpByIP[ip] ?? existing?.macAddress,
                hostnames: Self.mergedNames(bonjourNames[ip] ?? [], keptNames),
                openPorts: existing?.openPorts ?? [],
                services: Self.mergedServices(bonjourServices[ip] ?? [], existing?.services ?? []),
                portsScanned: existing?.portsScanned ?? false,
                isGateway: ip == info.gateway,
                isLocalDevice: ip == info.localIP,
                isNew: existing?.isNew ?? false,
                profile: existing?.profile ?? LANProfile(),
                firstSeen: existing?.firstSeen ?? now,
                lastSeen: now
            )
            device.preservePinnedName(from: existing)
            // A rescan of the network only tries the common ports: a recent full scan still stands, with what it found.
            if let full = existing?.allPortsScannedAt, now.timeIntervalSince(full) < Self.fullScanKeep {
                device.allPortsScannedAt = full
            }
            if miio.contains(ip), !device.services.contains(where: { $0.type.contains("miio") }) {
                device.services.append(ServiceAdvertisement(type: Miio.serviceType, name: "Mi Home"))
            }
            labels.apply(to: device)
            newDevices[ip] = device
        }

        devices = newDevices

        // Phase 5: Open ports on every host found.
        state = .scanning(progress: 0.55, phase: String(localized: "Scanning ports…"))
        let found = newDevices.keys.sorted { (parseIP($0) ?? 0) < (parseIP($1) ?? 0) }
        let portsByIP = await scanPorts(on: found) { fraction in
            self.state = .scanning(progress: 0.55 + 0.25 * fraction, phase: String(localized: "Scanning ports…"))
        }
        if Task.isCancelled { return }
        for (ip, ports) in portsByIP {
            guard let device = newDevices[ip] else { continue }
            var found = ports
            if miio.contains(ip) { found.append(Miio.discoveredPort) }
            device.openPorts = Self.mergedPorts(found, kept: device.allPortsScanned ? device.openPorts : [])
            device.portsScanned = true
        }
        devices = newDevices

        state = .scanning(progress: 0.82, phase: String(localized: "Identifying devices…"))
        await enrich(found.compactMap { newDevices[$0] }.filter { !$0.isLocalDevice })
        if Task.isCancelled { return }

        // Phase 6: Hostname resolution.
        state = .scanning(progress: 0.92, phase: String(localized: "Resolving names…"))

        let gateway = info.gateway
        await withTaskGroup(of: (String, String?).self) { group in
            for (ip, device) in newDevices where !device.hostnames.contains(where: { LANDevice.usefulHostname($0, ip: ip) != nil }) {
                group.addTask { @Sendable in
                    // The system resolver first, then the router and the host directly.
                    (ip, resolveHostname(ip) ?? LANProbes.reverseName(ip, gateway: gateway))
                }
            }
            for await (ip, hostname) in group {
                guard let hostname, LANDevice.usefulHostname(hostname, ip: ip) != nil,
                      let device = devices[ip],
                      !device.hostnames.contains(where: { LANDevice.usefulHostname($0, ip: ip) != nil }) else { continue }
                device.hostnames = [hostname]
            }
        }
        for (ip, names) in bonjourNames {
            if let device = newDevices[ip], !device.hostnames.contains(where: { LANDevice.usefulHostname($0, ip: ip) != nil }) {
                device.hostnames = names
            }
        }
        for (ip, services) in bonjourServices {
            if let device = newDevices[ip], device.services.isEmpty {
                device.services = services
            }
        }

        // Names are in, so a host can be matched by them even without a MAC.
        for device in newDevices.values { labels.apply(to: device) }
        classifyNewHosts(now: now)

        state = .scanning(progress: 0.96, phase: String(localized: "Asking the router…"))
        await refreshPortForwarding(now: now)
        if Task.isCancelled { return }

        scanCount += 1
        lastScanDate = now
        state = .done
    }

    // MARK: - Port scanning

    /// Bonjour names from this sweep first, then names learned earlier.
    private static func mergedNames(_ fresh: [String], _ kept: [String]) -> [String] {
        var names = fresh
        for name in kept where !names.contains(where: { $0.caseInsensitiveCompare(name) == .orderedSame }) {
            names.append(name)
        }
        return names
    }

    /// A sweep that only resolved one service must not forget the others.
    private static func mergedServices(_ fresh: [ServiceAdvertisement], _ kept: [ServiceAdvertisement]) -> [ServiceAdvertisement] {
        var ads = kept
        for service in fresh {
            if let index = ads.firstIndex(where: { $0.type == service.type && $0.name == service.name }) {
                if ads[index].model == nil { ads[index].model = service.model }
            } else {
                ads.append(service)
            }
        }
        return ads
    }

    /// How long a full port scan of a host outlives a rescan of the network.
    static let fullScanKeep: TimeInterval = 30 * 60

    /// Ports found now, plus those a recent full scan found: one entry per number, in order.
    static func mergedPorts(_ fresh: [DiscoveredPort], kept: [DiscoveredPort]) -> [DiscoveredPort] {
        var byNumber: [Int: DiscoveredPort] = [:]
        for port in kept { byNumber[port.number] = port }
        for port in fresh { byNumber[port.number] = port }
        return byNumber.values.sorted { $0.number < $1.number }
    }

    /// The miIO hello to each address in turn (iOS sends nothing to the broadcast address); the ones that answered.
    nonisolated private static func miioHello(_ hosts: [String]) async -> Set<String> {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: Miio.sayHello(to: hosts))
            }
        }
    }

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

    /// Every TCP port on one host. Closed ports reset at once, so a batch
    /// usually finishes well inside the timeout.
    private func scanEveryPort(
        on ip: String,
        progress: @MainActor (Double) -> Void = { _ in }
    ) async -> [DiscoveredPort] {
        let targets = (UInt16(1)...UInt16(65535)).map { (ip: ip, port: $0) }
        let outcomes = await probeTCP(targets, timeout: .milliseconds(1500), progress: progress)
        if Task.isCancelled { return [] }
        var open: [DiscoveredPort] = []
        for (index, outcome) in outcomes.enumerated() where outcome == .open {
            let port = Int(targets[index].port)
            open.append(DiscoveredPort(number: port, service: DiscoveredPort.serviceName(for: port)))
        }
        return open
    }

    /// UPnP, web banners, RTSP and Windows names. A few hosts at a time, so a
    /// quiet device cannot hold the whole scan for its timeouts.
    private func enrich(_ hosts: [LANDevice]) async {
        let chunkSize = 8
        var start = 0
        while start < hosts.count {
            if Task.isCancelled { return }
            let end = min(start + chunkSize, hosts.count)
            let chunk = Array(hosts[start..<end])
            await withTaskGroup(of: (String, LANProfile).self) { group in
                for device in chunk {
                    let ip = device.ipAddress
                    let ports = device.openPorts
                    group.addTask {
                        let profile = await runInBackground { LANProbes.profile(ip: ip, ports: ports) }
                        return (ip, profile)
                    }
                }
                for await (ip, profile) in group {
                    guard let device = devices[ip] else { continue }
                    device.profile = profile.filled(from: device.profile)
                }
            }
            start = end
        }
    }
}
