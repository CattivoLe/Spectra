import Darwin
import Foundation

/// A traceroute from this phone: ICMP echo requests with a growing TTL, sent
/// through the unprivileged ICMP datagram socket Darwin gives every app. Each
/// router that drops a probe for running out of TTL answers "time exceeded"
/// from its own address, which names that hop.
///
/// All TTLs go out at once and the replies are sorted afterwards, so a path
/// takes about two seconds instead of one timeout per silent hop. Hops that
/// stayed silent get a second try.
nonisolated enum LocalTracer {
    static let maxHops = 24
    static let roundTimeout: TimeInterval = 2.0
    /// Cloudflare's resolver: one of the most widely reachable addresses there is.
    static let targetV4 = "1.1.1.1"
    static let targetV6 = "2606:4700:4700::1111"

    /// Traces to Cloudflare over IPv4 when the interface has an IPv4 address,
    /// over IPv6 otherwise, as on an IPv6-only network.
    ///
    /// - Parameter interfaceName: Wi-Fi or Ethernet, like "en0". The socket is
    ///   pinned to it, so a VPN is not what gets traced; nil follows the
    ///   system route.
    static func trace(interfaceName: String?) async -> LocalTrace {
        await Task.detached(priority: .utility) {
            let v4 = hasAddress(family: AF_INET, interface: interfaceName)
            return run(target: v4 ? targetV4 : targetV6, family: v4 ? AF_INET : AF_INET6, interfaceName: interfaceName)
        }.value
    }

    /// Traces to a numeric address the caller already resolved.
    static func trace(to address: String, interfaceName: String?) async -> LocalTrace {
        await Task.detached(priority: .utility) {
            run(target: address, family: address.contains(":") ? AF_INET6 : AF_INET, interfaceName: interfaceName)
        }.value
    }

    private static func run(target: String, family: Int32, interfaceName: String?) -> LocalTrace {
        var storage = sockaddr_storage()
        let addressLength: socklen_t
        if family == AF_INET {
            var sin = sockaddr_in()
            sin.sin_family = sa_family_t(AF_INET)
            sin.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            guard inet_pton(AF_INET, target, &sin.sin_addr) == 1 else { return LocalTrace(target: target, hops: [], unavailable: true) }
            withUnsafeMutableBytes(of: &storage) { $0.storeBytes(of: sin, as: sockaddr_in.self) }
            addressLength = socklen_t(MemoryLayout<sockaddr_in>.size)
        } else {
            var sin6 = sockaddr_in6()
            sin6.sin6_family = sa_family_t(AF_INET6)
            sin6.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
            guard inet_pton(AF_INET6, target, &sin6.sin6_addr) == 1 else { return LocalTrace(target: target, hops: [], unavailable: true) }
            withUnsafeMutableBytes(of: &storage) { $0.storeBytes(of: sin6, as: sockaddr_in6.self) }
            addressLength = socklen_t(MemoryLayout<sockaddr_in6>.size)
        }
        let v6 = family == AF_INET6
        let sock = socket(family, SOCK_DGRAM, v6 ? IPPROTO_ICMPV6 : IPPROTO_ICMP)
        guard sock >= 0 else { return LocalTrace(target: target, hops: [], unavailable: true) }
        defer { close(sock) }

        var timeout = timeval(tv_sec: 0, tv_usec: 200_000)
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        if let interfaceName {
            var index = if_nametoindex(interfaceName)
            if index != 0 {
                setsockopt(sock, v6 ? IPPROTO_IPV6 : IPPROTO_IP, v6 ? IPV6_BOUND_IF : IP_BOUND_IF,
                           &index, socklen_t(MemoryLayout<UInt32>.size))
            }
        }

        let identifier = UInt16.random(in: 1 ... .max)
        let state = State()

        for round in 0..<2 {
            let limit = state.reachedAt ?? maxHops
            let pending = (1...limit).filter { state.hop($0) == nil }
            if pending.isEmpty { break }
            // Replies are read on their own thread while probes go out, so a
            // reply's time is taken when it arrives, not after the last send.
            state.stopAt = .now + .seconds(10)
            let receiver = DispatchGroup()
            receiver.enter()
            DispatchQueue.global(qos: .userInitiated).async {
                defer { receiver.leave() }
                var buffer = [UInt8](repeating: 0, count: 1500)
                while ContinuousClock.now < state.stopAt {
                    var from = sockaddr_storage()
                    var length = socklen_t(MemoryLayout<sockaddr_storage>.size)
                    let count = withUnsafeMutablePointer(to: &from) { pointer in
                        pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                            recvfrom(sock, &buffer, buffer.count, 0, $0, &length)
                        }
                    }
                    let arrived = ContinuousClock.now
                    guard count > 0 else { continue }
                    let bytes = Array(buffer.prefix(count))
                    let reply = v6 ? parseV6(bytes, identifier: identifier) : parse(bytes, identifier: identifier)
                    guard let reply, let address = addressText(from) else { continue }
                    state.record(sequence: reply.sequence, final: reply.final, from: address, at: arrived)
                }
            }
            var sentAny = false
            for ttl in pending {
                let sequence = UInt16(round * 100 + ttl)
                var hops = Int32(ttl)
                if v6 {
                    setsockopt(sock, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &hops, socklen_t(MemoryLayout<Int32>.size))
                } else {
                    setsockopt(sock, IPPROTO_IP, IP_TTL, &hops, socklen_t(MemoryLayout<Int32>.size))
                }
                let packet = v6 ? echoRequestV6(identifier: identifier, sequence: sequence)
                                : echoRequest(identifier: identifier, sequence: sequence)
                state.sent(sequence: sequence, ttl: ttl)
                let sent = withUnsafePointer(to: &storage) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(sock, packet, packet.count, 0, $0, addressLength)
                    }
                }
                if sent > 0 { sentAny = true }
                // A short gap: some routers drop bursts of expiring packets.
                usleep(3_000)
            }
            state.stopAt = .now + .milliseconds(Int(roundTimeout * 1000))
            receiver.wait()
            guard sentAny else { return LocalTrace(target: target, hops: [], unavailable: true) }
        }

        let found = state.hops
        let reachedAt = state.reachedAt
        let last = reachedAt ?? (found.keys.max() ?? 0)
        guard last > 0 else { return LocalTrace(target: target, hops: [], unavailable: false) }
        let hops = (1...last).map { found[$0] ?? TraceHop(address: nil, millis: nil) }
        return LocalTrace(target: target, hops: hops, unavailable: false)
    }

    /// What the sender and the receiving thread share.
    private final class State: @unchecked Sendable {
        private let lock = NSLock()
        private var sentAt: [UInt16: (ttl: Int, time: ContinuousClock.Instant)] = [:]
        private var found: [Int: TraceHop] = [:]
        private var reached: Int?
        private var stop = ContinuousClock.now

        var stopAt: ContinuousClock.Instant {
            get { lock.lock(); defer { lock.unlock() }; return stop }
            set { lock.lock(); stop = newValue; lock.unlock() }
        }
        var reachedAt: Int? { lock.lock(); defer { lock.unlock() }; return reached }
        var hops: [Int: TraceHop] { lock.lock(); defer { lock.unlock() }; return found }
        func hop(_ ttl: Int) -> TraceHop? { lock.lock(); defer { lock.unlock() }; return found[ttl] }

        func sent(sequence: UInt16, ttl: Int) {
            lock.lock(); sentAt[sequence] = (ttl, .now); lock.unlock()
        }

        func record(sequence: UInt16, final: Bool, from address: String, at arrived: ContinuousClock.Instant) {
            lock.lock()
            defer { lock.unlock() }
            guard let probe = sentAt[sequence] else { return }
            let millis = max(0, Int(((arrived - probe.time) / .milliseconds(1)).rounded()))
            if found[probe.ttl] == nil {
                found[probe.ttl] = TraceHop(address: address, millis: millis)
            }
            if final { reached = min(reached ?? probe.ttl, probe.ttl) }
        }
    }

    /// An ICMP echo request with a checksum; the kernel adds the IP header.
    static func echoRequest(identifier: UInt16, sequence: UInt16) -> [UInt8] {
        var packet: [UInt8] = [8, 0, 0, 0,
                               UInt8(identifier >> 8), UInt8(identifier & 0xFF),
                               UInt8(sequence >> 8), UInt8(sequence & 0xFF)]
        packet += Array("Spectra traceroute....".utf8)
        let sum = checksum(packet)
        packet[2] = UInt8(sum >> 8)
        packet[3] = UInt8(sum & 0xFF)
        return packet
    }

    /// An ICMPv6 echo request. The kernel fills in the checksum, which
    /// covers a pseudo-header with addresses only it knows.
    static func echoRequestV6(identifier: UInt16, sequence: UInt16) -> [UInt8] {
        [128, 0, 0, 0,
         UInt8(identifier >> 8), UInt8(identifier & 0xFF),
         UInt8(sequence >> 8), UInt8(sequence & 0xFF)] + Array("Spectra traceroute....".utf8)
    }

    /// Reads an ICMPv6 datagram, which Darwin delivers without the IPv6
    /// header. An echo reply (129) is the destination; "time exceeded" (3)
    /// and "unreachable" (1) quote our packet: its 40-byte IPv6 header, then
    /// our echo request with the ID and sequence.
    static func parseV6(_ bytes: [UInt8], identifier: UInt16) -> (sequence: UInt16, final: Bool)? {
        guard bytes.count >= 8 else { return nil }
        func field(_ offset: Int) -> UInt16 { UInt16(bytes[offset]) << 8 | UInt16(bytes[offset + 1]) }
        switch bytes[0] {
        case 129:
            guard field(4) == identifier else { return nil }
            return (field(6), true)
        case 3, 1:
            let icmp = 8 + 40
            guard bytes.count >= icmp + 8, bytes[icmp] == 128, field(icmp + 4) == identifier else { return nil }
            return (field(icmp + 6), bytes[0] == 1)
        default:
            return nil
        }
    }

    static func checksum(_ bytes: [UInt8]) -> UInt16 {
        var sum: UInt32 = 0
        var index = 0
        while index + 1 < bytes.count {
            sum += UInt32(bytes[index]) << 8 | UInt32(bytes[index + 1])
            index += 2
        }
        if index < bytes.count { sum += UInt32(bytes[index]) << 8 }
        while sum >> 16 != 0 { sum = (sum & 0xFFFF) + (sum >> 16) }
        return ~UInt16(sum)
    }

    /// Reads a received datagram, which on Darwin starts with the IP header.
    /// An echo reply is the destination itself; "time exceeded" and
    /// "unreachable" quote our probe, whose ID and sequence say which TTL it was.
    static func parse(_ bytes: [UInt8], identifier: UInt16) -> (sequence: UInt16, final: Bool)? {
        guard let first = bytes.first else { return nil }
        let header = Int(first & 0x0F) * 4
        guard bytes.count >= header + 8 else { return nil }
        let type = bytes[header]
        func field(_ offset: Int) -> UInt16 { UInt16(bytes[offset]) << 8 | UInt16(bytes[offset + 1]) }
        switch type {
        case 0:
            guard field(header + 4) == identifier else { return nil }
            return (field(header + 6), true)
        case 11, 3:
            // The quoted packet: its IP header, then the first 8 bytes of our ICMP.
            let inner = header + 8
            guard bytes.count >= inner + 1 else { return nil }
            let innerHeader = Int(bytes[inner] & 0x0F) * 4
            let icmp = inner + innerHeader
            guard bytes.count >= icmp + 8, bytes[icmp] == 8, field(icmp + 4) == identifier else { return nil }
            return (field(icmp + 6), type == 3)
        default:
            return nil
        }
    }

    private static func addressText(_ storage: sockaddr_storage) -> String? {
        var copy = storage
        let length = socklen_t(storage.ss_len)
        return withUnsafePointer(to: &copy) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { numericHost($0, length: length) }
        }
    }

    private static func numericHost(_ address: UnsafePointer<sockaddr>, length: socklen_t) -> String? {
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        guard getnameinfo(address, length, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { return nil }
        return String(cString: host)
    }

    /// Whether the interface (any, when nil) has an address of this family
    /// that can leave the link.
    static func hasAddress(family: Int32, interface: String?) -> Bool {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return false }
        defer { freeifaddrs(first) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            guard let raw = entry.pointee.ifa_addr, Int32(raw.pointee.sa_family) == family else { continue }
            let name = String(cString: entry.pointee.ifa_name)
            if let interface, name != interface { continue }
            if interface == nil, name.hasPrefix("lo") || name.hasPrefix("utun") { continue }
            if family == AF_INET6 {
                let first = raw.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { $0.pointee.sin6_addr.__u6_addr.__u6_addr8.0 }
                if first & 0xE0 != 0x20 { continue }   // only global 2000::/3
            }
            return true
        }
        return false
    }

    /// Reverse DNS for the hops, a second at most each, all at once.
    static func names(for addresses: [String]) async -> [String: String] {
        await withTaskGroup(of: (String, String?).self) { group in
            for address in Set(addresses) {
                group.addTask { (address, await reverseName(address)) }
            }
            var result: [String: String] = [:]
            for await (address, name) in group {
                if let name { result[address] = name }
            }
            return result
        }
    }

    /// Reverse DNS with a hard deadline. The system lookup cannot be
    /// cancelled and can take half a minute on an address with no name, so
    /// the answer is taken from whichever comes first, the lookup or the
    /// deadline, and a late lookup is simply dropped.
    private static func reverseName(_ address: String, timeout: TimeInterval = 1.5) async -> String? {
        await withCheckedContinuation { (continuation: CheckedContinuation<String?, Never>) in
            let once = ResumeOnce()
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + timeout) {
                once.run { continuation.resume(returning: nil) }
            }
            DispatchQueue.global(qos: .utility).async {
                var hints = addrinfo()
                hints.ai_flags = AI_NUMERICHOST
                var info: UnsafeMutablePointer<addrinfo>?
                var name: String?
                if getaddrinfo(address, nil, &hints, &info) == 0, let info {
                    if let raw = info.pointee.ai_addr {
                        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                        if getnameinfo(raw, info.pointee.ai_addrlen, &host, socklen_t(host.count), nil, 0, NI_NAMEREQD) == 0 {
                            let found = String(cString: host)
                            name = found == address ? nil : found
                        }
                    }
                    freeaddrinfo(info)
                }
                once.run { continuation.resume(returning: name) }
            }
        }
    }

    /// Lets the first of two racers resume a continuation.
    private final class ResumeOnce: @unchecked Sendable {
        private let lock = NSLock()
        private var done = false
        func run(_ body: () -> Void) {
            lock.lock()
            let first = !done
            done = true
            lock.unlock()
            if first { body() }
        }
    }
}
