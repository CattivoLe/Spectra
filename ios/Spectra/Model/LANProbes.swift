import Darwin
import Foundation
import Network

/// Asks a host what it is, after the port scan already knows it is there.
/// Blocking; call off the main thread.
nonisolated enum LANProbes {
    static func profile(ip: String, ports: [DiscoveredPort]) -> LANProfile {
        let numbers = Set(ports.map(\.number))
        let upnp = discoverUPnP(ip)
        let banners = httpBanners(ip, ports: numbers)
        let rtsp = rtspServices(ip, ports: numbers)
        let windows = windowsHost(ip, smb: numbers.contains(445))
        return LANProfile(upnp: upnp, banners: banners, rtsp: rtsp, windows: windows)
    }

    // MARK: UPnP

    private static func discoverUPnP(_ ip: String) -> UPnPIdentity? {
        let replies = exchangeUDP(ip: ip, port: 1900, payload: LANIdentity.ssdpSearch(host: ip), timeout: .milliseconds(800))
        guard let hit = replies.compactMap(LANIdentity.parseSSDP).first else { return nil }
        var identity = UPnPIdentity(friendlyName: nil, manufacturer: nil, model: nil, deviceType: shortType(hit.searchTarget), server: hit.server)
        if let location = hit.location, LANIdentity.locationIsOnHost(location, ip: ip),
           let body = fetch(location, ip: ip) {
            var described = LANIdentity.parseUPnPDescription(body)
            described.server = hit.server ?? described.server
            if described.deviceType == nil { described.deviceType = identity.deviceType }
            identity = described
        }
        guard identity.friendlyName != nil || identity.manufacturer != nil || identity.model != nil || identity.deviceType != nil else {
            return hit.server == nil ? nil : identity
        }
        return identity
    }

    private static func shortType(_ target: String?) -> String? {
        guard let target, target.lowercased().hasPrefix("urn:") else { return nil }
        let parts = target.split(separator: ":")
        guard parts.count >= 2 else { return target }
        return String(parts[parts.count - 2])
    }

    // MARK: HTTP

    private static let plainWeb: Set<Int> = [80, 81, 8000, 8001, 8008, 8080, 8081, 8123, 8888, 9000, 9090, 32400, 5000, 1400]
    private static let secureWeb: Set<Int> = [443, 8443, 5001]

    private static func httpBanners(_ ip: String, ports: Set<Int>) -> [HTTPBanner] {
        var banners: [HTTPBanner] = []
        for port in ports.sorted() {
            let secure = secureWeb.contains(port)
            guard secure || plainWeb.contains(port) else { continue }
            let payload = LANIdentity.httpRequest(host: ip, method: "HEAD")
            let data = secure
                ? exchangeTLS(ip: ip, port: port, payload: payload)
                : exchangeTCP(ip: ip, port: UInt16(port), payload: payload, timeout: .seconds(2))
            guard var parsed = data.flatMap(LANIdentity.parseHTTP) else { continue }
            if parsed.status == 405 || parsed.status == 400 || parsed.server == nil && parsed.title == nil {
                let get = LANIdentity.httpRequest(host: ip, method: "GET")
                let body = secure
                    ? exchangeTLS(ip: ip, port: port, payload: get)
                    : exchangeTCP(ip: ip, port: UInt16(port), payload: get, timeout: .seconds(2))
                if let richer = body.flatMap(LANIdentity.parseHTTP) { parsed = richer }
            }
            banners.append(HTTPBanner(
                port: port, secure: secure, status: parsed.status, server: parsed.server,
                poweredBy: parsed.poweredBy, title: parsed.title, realm: parsed.realm
            ))
        }
        return banners
    }

    // MARK: RTSP

    private static func rtspServices(_ ip: String, ports: Set<Int>) -> [RTSPService] {
        [554, 8554].filter(ports.contains).compactMap { port in
            guard let data = exchangeTCP(ip: ip, port: UInt16(port), payload: LANIdentity.rtspOptions(), timeout: .seconds(2)),
                  let parsed = LANIdentity.parseRTSP(data) else { return nil }
            return RTSPService(port: port, server: parsed.server, methods: parsed.methods)
        }
    }

    // MARK: Windows

    private static let guestShares = ["Users", "Public", "share", "Shared", "media", "video", "photos", "backup", "homes"]

    private static func windowsHost(_ ip: String, smb: Bool) -> WindowsHost? {
        var host = WindowsHost()
        let node = exchangeUDP(ip: ip, port: 137, payload: LANIdentity.netbiosNodeStatusRequest(), timeout: .milliseconds(800))
        for reply in node {
            for entry in LANIdentity.parseNetBIOS(reply) {
                if entry.suffix == 0x00, !entry.group, host.computerName == nil {
                    host.computerName = entry.name
                } else if entry.group, entry.suffix == 0x00 || entry.suffix == 0x1E, host.domain == nil {
                    host.domain = entry.name
                }
            }
        }
        if smb {
            readSMB(ip, into: &host)
        }
        if host.computerName == nil && host.domain == nil && host.shares.isEmpty && !host.guestSession {
            return nil
        }
        return host
    }

    private static func readSMB(_ ip: String, into host: inout WindowsHost) {
        guard let sock = connectTCP(ip, port: 445, timeout: .seconds(2)) else { return }
        defer { close(sock) }
        guard writeAll(sock, LANIdentity.smbNegotiate()),
              let negotiate = readFrame(sock),
              LANIdentity.smbStatus(LANIdentity.smbPayload(negotiate)) == LANIdentity.smbSuccess else { return }
        guard writeAll(sock, LANIdentity.smbSessionSetup(ntlm: LANIdentity.ntlmType1(), messageID: 1)),
              let setup = readFrame(sock) else { return }
        let payload = LANIdentity.smbPayload(setup)
        if let names = LANIdentity.parseNTLMChallenge(payload) {
            host.computerName = names.computer ?? host.computerName
            host.domain = names.domain ?? host.domain
        }
        let session = LANIdentity.smbSessionID(payload) ?? 0
        let status = LANIdentity.smbStatus(payload)
        var established = status == LANIdentity.smbSuccess && session != 0
        if status == LANIdentity.smbMoreProcessing, session != 0,
           writeAll(sock, LANIdentity.smbSessionSetup(ntlm: LANIdentity.ntlmType3(), messageID: 2, sessionID: session)),
           let second = readFrame(sock),
           LANIdentity.smbStatus(LANIdentity.smbPayload(second)) == LANIdentity.smbSuccess {
            established = true
        }
        guard established else { return }
        host.guestSession = true
        var messageID: UInt64 = 3
        for share in guestShares {
            let request = LANIdentity.smbTreeConnect(share: share, ip: ip, messageID: messageID, sessionID: session)
            messageID += 1
            guard writeAll(sock, request), let response = readFrame(sock) else { break }
            if LANIdentity.smbStatus(LANIdentity.smbPayload(response)) == LANIdentity.smbSuccess {
                host.shares.append(share)
            }
        }
    }

    // MARK: Sockets

    private static func exchangeUDP(ip: String, port: UInt16, payload: Data, timeout: Duration) -> [Data] {
        guard let ipVal = parseIP(ip) else { return [] }
        let sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard sock >= 0 else { return [] }
        defer { close(sock) }
        var destination = sockaddr_in()
        destination.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        destination.sin_family = sa_family_t(AF_INET)
        destination.sin_port = port.bigEndian
        destination.sin_addr.s_addr = ipVal.bigEndian
        let sent = payload.withUnsafeBytes { buffer in
            withUnsafePointer(to: &destination) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { address in
                    sendto(sock, buffer.baseAddress, buffer.count, 0, address, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        guard sent == payload.count else { return [] }

        var collected: [Data] = []
        let deadline = Date().addingTimeInterval(timeoutSeconds(timeout))
        while Date() < deadline {
            var poller = pollfd(fd: sock, events: Int16(POLLIN), revents: 0)
            let wait = max(0, deadline.timeIntervalSinceNow)
            let ready = poll(&poller, 1, Int32(wait * 1000))
            if ready <= 0 { break }
            var buffer = [UInt8](repeating: 0, count: 4096)
            let count = recv(sock, &buffer, buffer.count, 0)
            if count > 0 { collected.append(Data(buffer.prefix(count))) }
        }
        return collected
    }

    private static func exchangeTCP(ip: String, port: UInt16, payload: Data, timeout: Duration, limit: Int = 4096) -> Data? {
        guard let sock = connectTCP(ip, port: port, timeout: timeout) else { return nil }
        defer { close(sock) }
        guard writeAll(sock, payload) else { return nil }
        return readUntilClose(sock, limit: limit, timeout: timeout)
    }

    private static func fetch(_ location: String, ip: String) -> String? {
        guard let url = URL(string: location), let host = url.host, host == ip || host == "[\(ip)]" else { return nil }
        let port = url.port ?? (url.scheme == "https" ? 443 : 80)
        let path = url.path.isEmpty ? "/" : url.path
        let request = Data("GET \(path) HTTP/1.0\r\nHost: \(ip)\r\nUser-Agent: Spectra\r\nConnection: close\r\n\r\n".utf8)
        let data = url.scheme == "https"
            ? exchangeTLS(ip: ip, port: port, payload: request)
            : exchangeTCP(ip: ip, port: UInt16(port), payload: request, timeout: .seconds(2), limit: 65_536)
        guard let data, let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else {
            return nil
        }
        return HeaderText.parse(text).body
    }

    private static func connectTCP(_ ip: String, port: UInt16, timeout: Duration) -> Int32? {
        guard let ipVal = parseIP(ip) else { return nil }
        let sock = socket(AF_INET, SOCK_STREAM, 0)
        guard sock >= 0 else { return nil }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = ipVal.bigEndian
        let flags = fcntl(sock, F_GETFL, 0)
        _ = fcntl(sock, F_SETFL, flags | O_NONBLOCK)
        let started = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                connect(sock, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if started < 0 && errno != EINPROGRESS {
            close(sock)
            return nil
        }
        var poller = pollfd(fd: sock, events: Int16(POLLOUT), revents: 0)
        let ready = poll(&poller, 1, Int32(timeoutSeconds(timeout) * 1000))
        var error: Int32 = 0
        var length = socklen_t(MemoryLayout<Int32>.size)
        getsockopt(sock, SOL_SOCKET, SO_ERROR, &error, &length)
        guard ready > 0, error == 0 else {
            close(sock)
            return nil
        }
        _ = fcntl(sock, F_SETFL, flags)
        let milliseconds = Int(timeoutSeconds(timeout) * 1000)
        var time = timeval(tv_sec: milliseconds / 1000, tv_usec: Int32((milliseconds % 1000) * 1000))
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &time, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &time, socklen_t(MemoryLayout<timeval>.size))
        return sock
    }

    private static func writeAll(_ sock: Int32, _ data: Data) -> Bool {
        var sent = 0
        let bytes = [UInt8](data)
        while sent < bytes.count {
            let remaining = bytes.count - sent
            let count = bytes.withUnsafeBytes { raw -> Int in
                guard let base = raw.baseAddress else { return -1 }
                return write(sock, base.advanced(by: sent), remaining)
            }
            if count <= 0 { return false }
            sent += count
        }
        return true
    }

    private static func readUntilClose(_ sock: Int32, limit: Int, timeout: Duration) -> Data? {
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 2048)
        let deadline = Date().addingTimeInterval(timeoutSeconds(timeout))
        while data.count < limit, Date() < deadline {
            let count = read(sock, &buffer, min(buffer.count, limit - data.count))
            if count > 0 {
                data.append(buffer, count: count)
            } else {
                break
            }
        }
        return data.isEmpty ? nil : data
    }

    private static func readFrame(_ sock: Int32) -> Data? {
        var header = [UInt8](repeating: 0, count: 4)
        guard readFull(sock, &header) else { return nil }
        let length = (Int(header[1]) << 16) | (Int(header[2]) << 8) | Int(header[3])
        guard length > 0, length < 1_000_000 else { return nil }
        var body = [UInt8](repeating: 0, count: length)
        guard readFull(sock, &body) else { return nil }
        return Data(header + body)
    }

    private static func readFull(_ sock: Int32, _ buffer: inout [UInt8]) -> Bool {
        var got = 0
        while got < buffer.count {
            let remaining = buffer.count - got
            let count = buffer.withUnsafeMutableBytes { raw -> Int in
                guard let base = raw.baseAddress else { return -1 }
                return read(sock, base.advanced(by: got), remaining)
            }
            if count <= 0 { return false }
            got += count
        }
        return true
    }

    private static func exchangeTLS(ip: String, port: Int, payload: Data) -> Data? {
        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = 2
        let tls = NWProtocolTLS.Options()
        let parameters = NWParameters(tls: tls, tcp: tcp)
        parameters.requiredInterfaceType = .wifi
        let connection = NWConnection(host: NWEndpoint.Host(ip), port: NWEndpoint.Port(rawValue: UInt16(port)) ?? .https, using: parameters)
        let queue = DispatchQueue(label: "Spectra.TLS")
        let ready = DispatchSemaphore(value: 0)
        let gate = TLSGate()
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                gate.opened = true
                ready.signal()
            case .failed, .cancelled:
                ready.signal()
            default:
                break
            }
        }
        connection.start(queue: queue)
        guard ready.wait(timeout: .now() + 2) == .success, gate.opened else {
            connection.cancel()
            return nil
        }
        let sent = DispatchSemaphore(value: 0)
        connection.send(content: payload, completion: .contentProcessed { _ in sent.signal() })
        guard sent.wait(timeout: .now() + 2) == .success else {
            connection.cancel()
            return nil
        }
        let received = DispatchSemaphore(value: 0)
        func pull() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { chunk, _, isComplete, _ in
                if let chunk { gate.data.append(chunk) }
                if isComplete || gate.data.count >= 4096 || chunk == nil {
                    received.signal()
                } else {
                    pull()
                }
            }
        }
        pull()
        _ = received.wait(timeout: .now() + 2)
        connection.cancel()
        return gate.data.isEmpty ? nil : gate.data
    }

    private static func timeoutSeconds(_ timeout: Duration) -> TimeInterval {
        let parts = timeout.components
        return TimeInterval(parts.seconds) + TimeInterval(parts.attoseconds) / 1e18
    }
}

nonisolated private final class TLSGate: @unchecked Sendable {
    var opened = false
    var data = Data()
}
