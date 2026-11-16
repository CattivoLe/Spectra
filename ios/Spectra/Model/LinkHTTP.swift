import Foundation
import Network

struct LinkTimeout: Error {}

/// A small HTTP/1.1 client. `interfaceType` pins the socket to Wi-Fi or Ethernet
/// so a VPN is not the path being measured. HTTP/1.0 would also avoid chunking;
/// 1.1 with `Connection: close` is what the speed host expects, and chunked
/// bodies are decoded when the reply finishes.
enum LinkHTTP {
    struct Result: Sendable {
        var status: Int
        var body: Data
        var bytes: Int
        var seconds: TimeInterval
    }

    static func send(
        host: String,
        path: String,
        method: String = "GET",
        body: Data? = nil,
        accept: String = "*/*",
        timeout: TimeInterval = 8,
        maxBytes: Int = 1_000_000,
        maxSeconds: TimeInterval? = nil,
        keepBody: Bool = true,
        interfaceType: NWInterface.InterfaceType? = nil
    ) async throws -> Result {
        let params = NWParameters.tls
        if let interfaceType { params.requiredInterfaceType = interfaceType }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: 443, using: params)
        defer { connection.cancel() }
        return try await withThrowingTaskGroup(of: Result.self) { group in
            group.addTask {
                try await exchange(
                    connection,
                    host: host,
                    path: path,
                    method: method,
                    body: body,
                    accept: accept,
                    maxBytes: maxBytes,
                    maxSeconds: maxSeconds,
                    keepBody: keepBody
                )
            }
            group.addTask {
                try await Task.sleep(for: .seconds(timeout))
                connection.cancel()
                throw LinkTimeout()
            }
            guard let result = try await group.next() else { throw LinkTimeout() }
            group.cancelAll()
            return result
        }
    }

    /// TCP handshake time, or nil when the host does not answer in time. With
    /// `tls` the TLS handshake has to finish too, certificate check included.
    static func connectMillis(
        host: String,
        port: UInt16,
        timeout: TimeInterval,
        interfaceType: NWInterface.InterfaceType?,
        tls: Bool = false
    ) async -> Int? {
        let params: NWParameters = tls ? .tls : .tcp
        if let interfaceType { params.requiredInterfaceType = interfaceType }
        guard let endpointPort = NWEndpoint.Port(rawValue: port) else { return nil }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: params)
        let start = ContinuousClock.now
        defer { connection.cancel() }
        do {
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask { try await waitReady(connection) }
                group.addTask {
                    try await Task.sleep(for: .seconds(timeout))
                    connection.cancel()
                    throw LinkTimeout()
                }
                try await group.next()
                group.cancelAll()
            }
            return elapsedMillis(since: start)
        } catch {
            return nil
        }
    }

    /// `.system` follows the phone's route, VPN included. `.pinned` stays on Wi-Fi
    /// or Ethernet, or on the mobile network when the route setting asks for it.
    enum ProbeInterface: Sendable {
        case system
        case pinned(NWInterface.InterfaceType)
        case unavailable
    }

    /// Sent as the User-Agent version, so services can tell builds apart.
    nonisolated static let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1"

    /// The BSD name of the interface of this kind the system is using, like "en0".
    static func interfaceName(_ type: NWInterface.InterfaceType) async -> String? {
        await currentPath().availableInterfaces.first { $0.type == type }?.name
    }

    static func probeInterface(viaVpn: Bool) async -> ProbeInterface {
        let path = await currentPath()
        guard path.status == .satisfied else { return .unavailable }
        if viaVpn { return .system }
        // The mobile network is asked for by name: with Wi-Fi joined it is
        // not the path the system uses, only one it has available.
        if UserDefaults.standard.bool(forKey: ProbeRoute.cellularKey) {
            return path.availableInterfaces.contains { $0.type == .cellular } ? .pinned(.cellular) : .unavailable
        }
        if path.usesInterfaceType(.wifi) { return .pinned(.wifi) }
        if path.usesInterfaceType(.wiredEthernet) { return .pinned(.wiredEthernet) }
        return .unavailable
    }
}

private func exchange(
    _ connection: NWConnection,
    host: String,
    path: String,
    method: String,
    body: Data?,
    accept: String,
    maxBytes: Int,
    maxSeconds: TimeInterval?,
    keepBody: Bool
) async throws -> LinkHTTP.Result {
    try await waitReady(connection)
    var header = "\(method) \(path) HTTP/1.1\r\nHost: \(host)\r\nUser-Agent: Spectra/\(LinkHTTP.version)\r\nAccept: \(accept)\r\nConnection: close\r\n"
    if let body {
        header += "Content-Type: application/octet-stream\r\nContent-Length: \(body.count)\r\n"
    }
    header += "\r\n"
    var payload = Data(header.utf8)
    if let body { payload.append(body) }
    try await send(connection, payload)

    let started = ContinuousClock.now
    let marker = Data("\r\n\r\n".utf8)
    var raw = Data()
    var headerLength: Int?
    var contentLength: Int?
    var chunked = false
    var status = 0
    var done = false
    while !done {
        if Task.isCancelled { throw CancellationError() }
        let piece = try await receive(connection)
        if let chunk = piece.data { raw.append(chunk) }
        done = piece.done
        if headerLength == nil, let range = raw.range(of: marker) {
            let headerText = String(decoding: raw.subdata(in: 0..<range.lowerBound), as: UTF8.self)
            status = statusCode(headerText)
            contentLength = contentLengthOf(headerText)
            chunked = headerText.lowercased().contains("chunked")
            headerLength = range.upperBound
        }
        if let headerLength {
            let bodyCount = raw.count - headerLength
            if let contentLength, bodyCount >= contentLength { break }
            if !chunked, bodyCount >= maxBytes { break }
            if let maxSeconds, seconds(since: started) >= maxSeconds { break }
        }
        if done { break }
    }
    guard let headerLength, status > 0 else { throw LinkTimeout() }
    var bodyData = Data(raw.dropFirst(headerLength))
    if chunked, done, let decoded = decodeChunked(bodyData) {
        bodyData = decoded
    }
    let count = min(bodyData.count, max(maxBytes, 0))
    if bodyData.count > count { bodyData = Data(bodyData.prefix(count)) }
    return LinkHTTP.Result(
        status: status,
        body: keepBody ? bodyData : Data(),
        bytes: bodyData.count,
        seconds: seconds(since: started)
    )
}

private func currentPath() async -> NWPath {
    let monitor = NWPathMonitor()
    return await withCheckedContinuation { continuation in
        let once = Once()
        monitor.pathUpdateHandler = { path in
            once.perform {
                continuation.resume(returning: path)
                monitor.cancel()
            }
        }
        monitor.start(queue: .global(qos: .userInitiated))
    }
}

private func waitReady(_ connection: NWConnection) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        let once = Once()
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                once.perform { continuation.resume() }
            case .failed(let error):
                once.perform { continuation.resume(throwing: error) }
            case .cancelled:
                once.perform { continuation.resume(throwing: CancellationError()) }
            default:
                break
            }
        }
        connection.start(queue: .global(qos: .userInitiated))
    }
}

private func send(_ connection: NWConnection, _ data: Data) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        connection.send(content: data, completion: .contentProcessed { error in
            if let error {
                continuation.resume(throwing: error)
            } else {
                continuation.resume()
            }
        })
    }
}

private func receive(_ connection: NWConnection) async throws -> (data: Data?, done: Bool) {
    try await withCheckedThrowingContinuation { continuation in
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
            if let error {
                continuation.resume(throwing: error)
            } else {
                continuation.resume(returning: (data, isComplete))
            }
        }
    }
}

private func statusCode(_ header: String) -> Int {
    let line = header.split(separator: "\r\n", maxSplits: 1).first ?? ""
    let parts = line.split(separator: " ")
    guard parts.count >= 2, let code = Int(parts[1]) else { return 0 }
    return code
}

private func contentLengthOf(_ header: String) -> Int? {
    for line in header.split(separator: "\r\n").dropFirst() {
        let pair = line.split(separator: ":", maxSplits: 1)
        guard pair.count == 2, pair[0].trimmingCharacters(in: .whitespaces).lowercased() == "content-length" else { continue }
        return Int(pair[1].trimmingCharacters(in: .whitespaces))
    }
    return nil
}

private func decodeChunked(_ body: Data) -> Data? {
    var out = Data()
    var index = body.startIndex
    let newline = Data("\r\n".utf8)
    while index < body.endIndex {
        guard let lineEnd = body[index...].range(of: newline) else { return nil }
        let line = String(decoding: body[index..<lineEnd.lowerBound], as: UTF8.self)
        let sizeText = line.split(separator: ";").first.map(String.init) ?? ""
        guard let size = Int(sizeText.trimmingCharacters(in: .whitespaces), radix: 16) else { return nil }
        if size == 0 { return out }
        let dataStart = lineEnd.upperBound
        let dataEnd = dataStart + size
        guard dataEnd + 2 <= body.endIndex else { return nil }
        out.append(body[dataStart..<dataEnd])
        index = dataEnd + 2
    }
    return nil
}

private func elapsedMillis(since start: ContinuousClock.Instant) -> Int {
    max(1, Int((seconds(since: start) * 1000).rounded()))
}

private func seconds(since start: ContinuousClock.Instant) -> TimeInterval {
    let parts = (ContinuousClock.now - start).components
    return TimeInterval(parts.seconds) + TimeInterval(parts.attoseconds) / 1e18
}

private final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func perform(_ body: () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        guard !done else { return }
        done = true
        body()
    }
}
