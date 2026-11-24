import Foundation
import Network
import Observation

/// One point on the live throughput chart.
struct SpeedPoint: Identifiable, Equatable, Sendable {
    enum Direction: String, Codable, Sendable { case down, up }
    var id: Int
    var direction: Direction
    /// Seconds since the phase started.
    var seconds: Double
    var bps: Double
}

/// A finished full speed test.
struct SpeedResult: Codable, Equatable, Sendable {
    var measuredAt: Date
    var downBps: Double?
    var upBps: Double?
    var idleLatencyMs: Int?
    var jitterMs: Int?
    var loadedDownLatencyMs: Int?
    var loadedUpLatencyMs: Int?
    /// Throughput every quarter second, in bit/s, for the chart in History.
    var downSamples: [Double] = []
    var upSamples: [Double] = []
    var streams: Int
    /// Upload streams; nil in tests saved before M-Lab was an option.
    var upStreams: Int?
    /// The Cloudflare edge that served the test, like "BEG", or the M-Lab
    /// machine and its city.
    var server: String?
    /// "Cloudflare" or "M-Lab"; nil in older tests, which were all Cloudflare.
    var provider: String?

    /// Latency added by a full download or upload queue, the "bufferbloat"
    /// grade speed-test sites give: A under 30 ms, B under 60, C under 200,
    /// D under 400, F beyond.
    var bufferbloatGrade: String? {
        guard let idle = idleLatencyMs else { return nil }
        let loaded = [loadedDownLatencyMs, loadedUpLatencyMs].compactMap { $0 }.max()
        guard let loaded else { return nil }
        return Self.grade(added: loaded - idle)
    }

    static func grade(added: Int) -> String {
        switch added {
        case ..<30: "A"
        case ..<60: "B"
        case ..<200: "C"
        case ..<400: "D"
        default: "F"
        }
    }

    /// The short form kept on a connection check.
    var sample: SpeedSample {
        SpeedSample(
            latencyMs: idleLatencyMs, downBps: downBps, upBps: upBps,
            jitterMs: jitterMs, loadedLatencyMs: [loadedDownLatencyMs, loadedUpLatencyMs].compactMap { $0 }.max(),
            streams: streams, downSamples: downSamples, upSamples: upSamples, server: server
        )
    }
}

/// Measures the line the way a speed-test site does: several parallel
/// streams for a fixed time, with the quarter-second throughput kept for a
/// chart, and small requests before and during the load to see how much the
/// full queue slows everything else down.
///
/// Uses Cloudflare's speed endpoints. Sockets are pinned to Wi-Fi or Ethernet
/// unless the VPN switch is on, like the connection check.
@Observable
final class SpeedTester {
    enum Phase: Equatable {
        case idle
        case preparing
        case latency
        case download
        case upload
        case done
        case failed(String)
    }

    static let downloadSeconds: TimeInterval = 8
    static let uploadSeconds: TimeInterval = 8
    nonisolated static let downloadStreams = 4
    nonisolated static let uploadStreams = 3
    nonisolated static let host = "speed.cloudflare.com"

    private(set) var phase: Phase = .idle
    private(set) var progress = 0.0
    /// Throughput over the last second, in bit/s, while a transfer runs.
    private(set) var liveBps = 0.0
    private(set) var points: [SpeedPoint] = []
    private(set) var result: SpeedResult?
    /// Machines M-Lab offered at the last look, for the server picker.
    private(set) var mlabServers: [MLabServer] = []
    private(set) var locating = false
    /// Set when the chosen M-Lab machine was not on offer and another ran the test.
    private(set) var substitution: String?
    /// Cloudflare answered 429 during the last test: it limits tests from one
    /// address after several in a row. The figures may then be lower than the line.
    private(set) var throttled = false

    /// Asks M-Lab which machines are nearest, for the picker.
    func loadServers(viaVpn: Bool) {
        guard !locating else { return }
        locating = true
        Task { @MainActor in
            let interface: NWInterface.InterfaceType?
            if case .pinned(let type) = await LinkHTTP.probeInterface(viaVpn: viaVpn) { interface = type } else { interface = nil }
            let found = await MLab.locate(interface: interface)
            if !found.isEmpty { mlabServers = found }
            locating = false
        }
    }

    var running: Bool {
        switch phase {
        case .preparing, .latency, .download, .upload: true
        default: false
        }
    }

    private var task: Task<Void, Never>?
    private var token = 0

    func start(viaVpn: Bool, server: SpeedServerChoice = .cloudflare) {
        token += 1
        let mine = token
        task?.cancel()
        phase = .preparing
        progress = 0.02
        liveBps = 0
        points = []
        result = nil
        substitution = nil
        throttled = false
        task = Task { @MainActor in
            await run(mine, viaVpn: viaVpn, server: server)
        }
    }

    func stop() {
        token += 1
        task?.cancel()
        task = nil
        if running {
            phase = result == nil ? .idle : .done
            progress = 0
            liveBps = 0
        }
    }

    // MARK: - Run

    @MainActor
    private func run(_ mine: Int, viaVpn: Bool, server choice: SpeedServerChoice) async {
        func live() -> Bool { token == mine && !Task.isCancelled }
        let interface: NWInterface.InterfaceType?
        switch await LinkHTTP.probeInterface(viaVpn: viaVpn) {
        case .unavailable:
            guard live() else { return }
            phase = .failed(String(localized: "No network to measure."))
            return
        case .system: interface = nil
        case .pinned(let type): interface = type
        }
        var outcome = SpeedResult(measuredAt: .now, streams: Self.downloadStreams)
        let backend: Backend
        switch choice {
        case .cloudflare:
            backend = .cloudflare(interface: interface)
            outcome.provider = "Cloudflare"
            // Where the test lands.
            if let trace = try? await LinkHTTP.send(host: Self.host, path: "/cdn-cgi/trace", timeout: 6, maxBytes: 8_000, interfaceType: interface),
               trace.status == 200 {
                outcome.server = LinkParse.external(fromTrace: String(decoding: trace.body, as: UTF8.self))?.colo
            }
        case .mlab(let machine):
            // Fresh single-use URLs every run. M-Lab offers a varying few of
            // the nearby machines, so ask again before giving up on the chosen one.
            var offered: [MLabServer] = []
            for _ in 0..<(machine == nil ? 1 : 3) {
                offered = await MLab.locate(interface: interface)
                guard live() else { return }
                if machine == nil || offered.contains(where: { $0.machine == machine }) { break }
            }
            if !offered.isEmpty {
                // Keep the machines the picker already knows, adding the new ones.
                mlabServers = offered + mlabServers.filter { old in !offered.contains { $0.machine == old.machine } }
            }
            let chosen = offered.first(where: { $0.machine == machine })
            if let machine, chosen == nil, let stand = offered.first {
                let wanted = machine.split(separator: ".").first.map(String.init) ?? machine
                substitution = String(localized: "M-Lab does not offer \(wanted) right now, so \(stand.city.isEmpty ? stand.shortName : stand.city) ran the test.")
            }
            guard let server = chosen ?? offered.first else {
                phase = .failed(String(localized: "M-Lab did not offer a server. Try again or pick Cloudflare."))
                return
            }
            backend = .mlab(server, interface: interface)
            outcome.provider = "M-Lab"
            outcome.server = [server.city, server.shortName].filter { !$0.isEmpty }.joined(separator: " · ")
        }
        outcome.streams = backend.downloadStreams
        outcome.upStreams = backend.uploadStreams
        guard live() else { return }

        // Idle latency and jitter.
        phase = .latency
        progress = 0.08
        var idle: [Int] = []
        for _ in 0..<8 {
            if let ms = await backend.latency() { idle.append(ms) }
            guard live() else { return }
        }
        guard !idle.isEmpty else {
            phase = .failed(String(localized: "The speed server did not answer."))
            return
        }
        outcome.idleLatencyMs = SpeedMath.median(idle)
        outcome.jitterMs = SpeedMath.jitter(idle)
        progress = 0.15

        // Download.
        phase = .download
        let down = await transfer(.down, backend: backend, mine: mine)
        guard live() else { return }
        outcome.downBps = down.bps
        outcome.downSamples = down.samples
        outcome.loadedDownLatencyMs = down.loadedLatencyMs
        progress = 0.58

        // Upload.
        phase = .upload
        let up = await transfer(.up, backend: backend, mine: mine)
        guard live() else { return }
        outcome.upBps = up.bps
        outcome.upSamples = up.samples
        outcome.loadedUpLatencyMs = up.loadedLatencyMs

        liveBps = 0
        progress = 1
        if outcome.downBps == nil, outcome.upBps == nil, throttled {
            phase = .failed(String(localized: "Cloudflare is turning away speed tests from this address for now (HTTP 429). Try again in a few minutes."))
            return
        }
        result = outcome
        phase = .done
    }

    private struct Transfer {
        var bps: Double?
        var samples: [Double]
        var loadedLatencyMs: Int?
    }

    /// Runs the streams for the phase's duration while sampling the shared
    /// byte counter four times a second and probing latency in between.
    @MainActor
    private func transfer(_ direction: SpeedPoint.Direction, backend: Backend, mine: Int) async -> Transfer {
        let counter = ByteCounter()
        let duration = direction == .down ? Self.downloadSeconds : Self.uploadSeconds
        let streams = direction == .down ? backend.downloadStreams : backend.uploadStreams
        let deadline = ContinuousClock.now + .seconds(duration)
        let started = ContinuousClock.now
        let baseProgress = direction == .down ? 0.15 : 0.58
        let span = direction == .down ? 0.43 : 0.42

        let workers = Task.detached(priority: .userInitiated) {
            await withTaskGroup(of: Void.self) { group in
                for _ in 0..<streams {
                    group.addTask {
                        await backend.stream(direction, counter: counter, until: deadline)
                    }
                }
            }
        }
        let loaded = Task.detached(priority: .utility) { () -> [Int] in
            var values: [Int] = []
            // Let the queues fill before measuring what they do to latency.
            try? await Task.sleep(for: .milliseconds(1500))
            while ContinuousClock.now < deadline - .milliseconds(500) {
                if let ms = await backend.latency() { values.append(ms) }
                try? await Task.sleep(for: .milliseconds(400))
            }
            return values
        }

        var raw: [Double] = []
        var samples: [Double] = []
        var lastBytes = 0
        var lastTime = started
        var pointID = points.count
        while ContinuousClock.now < deadline, token == mine, !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(250))
            let now = ContinuousClock.now
            let bytes = counter.total
            let elapsed = seconds(from: lastTime, to: now)
            let bps = elapsed > 0 ? Double(bytes - lastBytes) * 8 / elapsed : 0
            lastBytes = bytes
            lastTime = now
            raw.append(bps)
            // A one-second moving average: request/response uploads arrive in
            // bursts that would otherwise saw-tooth the chart.
            let window = raw.suffix(4)
            let smoothed = window.reduce(0, +) / Double(window.count)
            samples.append(smoothed)
            pointID += 1
            // Both directions share the chart's time axis, upload drawn over download.
            points.append(SpeedPoint(id: pointID, direction: direction, seconds: seconds(from: started, to: now), bps: smoothed))
            liveBps = smoothed
            progress = baseProgress + span * min(1, seconds(from: started, to: now) / duration)
        }
        workers.cancel()
        _ = await workers.value
        loaded.cancel()
        let loadedValues = await loaded.value
        if counter.wasLimited { throttled = true }
        let total = seconds(from: started, to: ContinuousClock.now)
        let totalBytes = counter.total
        // The first second is slow start; the average over the steady part is the number people compare.
        let steady = samples.dropFirst(min(4, max(0, samples.count - 4)))
        let steadyBps = steady.isEmpty ? nil : steady.reduce(0, +) / Double(steady.count)
        let overall = total > 0 && totalBytes > 0 ? Double(totalBytes) * 8 / total : nil
        return Transfer(
            bps: totalBytes >= 50_000 ? (steadyBps ?? overall) : nil,
            samples: samples,
            loadedLatencyMs: SpeedMath.median(loadedValues)
        )
    }

    private func seconds(from start: ContinuousClock.Instant, to end: ContinuousClock.Instant) -> TimeInterval {
        let parts = (end - start).components
        return TimeInterval(parts.seconds) + TimeInterval(parts.attoseconds) / 1e18
    }
}

/// Where the bytes go: Cloudflare's HTTP endpoints over several streams, or
/// one ndt7 WebSocket per direction to an M-Lab machine.
nonisolated enum Backend: Sendable {
    case cloudflare(interface: NWInterface.InterfaceType?)
    case mlab(MLabServer, interface: NWInterface.InterfaceType?)

    var downloadStreams: Int {
        switch self {
        case .cloudflare: SpeedTester.downloadStreams
        case .mlab: 1
        }
    }

    var uploadStreams: Int {
        switch self {
        case .cloudflare: SpeedTester.uploadStreams
        case .mlab: 1
        }
    }

    /// Cloudflare: a request's round trip on a warm connection. M-Lab offers
    /// no such endpoint, so the TCP handshake to the machine stands in.
    func latency() async -> Int? {
        switch self {
        case .cloudflare(let interface):
            await SpeedProbe.requestMillis(host: SpeedTester.host, interface: interface)
        case .mlab(let server, let interface):
            await LinkHTTP.connectMillis(host: server.host, port: 443, timeout: 3, interfaceType: interface)
        }
    }

    func stream(_ direction: SpeedPoint.Direction, counter: ByteCounter, until deadline: ContinuousClock.Instant) async {
        switch (self, direction) {
        case (.cloudflare(let interface), .down):
            await SpeedProbe.download(host: SpeedTester.host, counter: counter, until: deadline, interface: interface)
        case (.cloudflare(let interface), .up):
            await SpeedProbe.upload(host: SpeedTester.host, counter: counter, until: deadline, interface: interface)
        case (.mlab(let server, let interface), .down):
            await MLab.download(url: server.downloadURL, counter: counter, until: deadline, interface: interface)
        case (.mlab(let server, let interface), .up):
            await MLab.upload(url: server.uploadURL, counter: counter, until: deadline, interface: interface)
        }
    }
}

/// Bytes moved by all streams, read by the sampler, and the download size
/// the streams ask for: Cloudflare turns away big downloads with 429 after
/// several tests from one address, so the size halves each time until it is
/// let through. Mirrors the Android `Leg`.
nonisolated final class ByteCounter: @unchecked Sendable {
    static let chunkStart = 25_000_000
    static let chunkFloor = 1_000_000

    private let lock = NSLock()
    private var count = 0
    private var chunk = ByteCounter.chunkStart
    private var limited = false

    var downloadChunk: Int {
        lock.lock()
        defer { lock.unlock() }
        return chunk
    }

    var wasLimited: Bool {
        lock.lock()
        defer { lock.unlock() }
        return limited
    }

    /// A 429: ask for less next time, down to the floor.
    func throttle(shrink: Bool) {
        lock.lock()
        limited = true
        if shrink { chunk = max(Self.chunkFloor, chunk / 2) }
        lock.unlock()
    }

    func add(_ bytes: Int) {
        lock.lock()
        count += bytes
        lock.unlock()
    }

    var total: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }
}

nonisolated enum SpeedMath {
    static func median(_ values: [Int]) -> Int? {
        guard !values.isEmpty else { return nil }
        let sorted = values.sorted()
        return sorted[sorted.count / 2]
    }

    /// Mean absolute difference between consecutive measurements.
    static func jitter(_ values: [Int]) -> Int? {
        guard values.count >= 2 else { return nil }
        var sum = 0
        for index in 1..<values.count { sum += abs(values[index] - values[index - 1]) }
        return sum / (values.count - 1)
    }
}

/// How large the next upload body is. A body has to be answered well within the test to count, and large enough that
/// the wait for the answer does not eat the speed: it grows while answers come quickly and shrinks when they are late.
nonisolated struct UploadPace: Equatable, Sendable {
    static let floor = 16_000
    static let ceiling = 1_000_000

    private(set) var size = floor

    mutating func answered(in seconds: TimeInterval) {
        if seconds < 0.4 {
            size = min(Self.ceiling, size * 2)
        } else if seconds > 1.2 {
            size = max(Self.floor, size / 2)
        }
    }
}

/// The raw transfers. Each stream is one TLS connection to the speed host.
nonisolated enum SpeedProbe {
    /// Time from request to the first byte of the reply on a fresh
    /// connection, handshake excluded: what a page feels as latency.
    static func requestMillis(host: String, interface: NWInterface.InterfaceType?) async -> Int? {
        let connection = makeConnection(host: host, interface: interface)
        defer { connection.cancel() }
        do {
            return try await withTimeout(seconds: 5) {
                try await closing(connection) {
                    try await ready(connection)
                    let request = Data("GET /__down?bytes=0 HTTP/1.1\r\nHost: \(host)\r\nUser-Agent: Spectra\r\n\r\n".utf8)
                    // The first request on a connection pays for session setup
                    // on both sides; the second is the round trip itself.
                    try await write(connection, request)
                    try await readResponse(connection)
                    let start = ContinuousClock.now
                    try await write(connection, request)
                    try await readResponse(connection)
                    return Int((elapsed(since: start) * 1000).rounded())
                }
            }
        } catch {
            return nil
        }
    }

    /// One download after another until the deadline, each as large as the
    /// server allows right now (see `ByteCounter`). A refused one is not
    /// counted; the next asks for half as much.
    static func download(host: String, counter: ByteCounter, until deadline: ContinuousClock.Instant, interface: NWInterface.InterfaceType?) async {
        while ContinuousClock.now < deadline, !Task.isCancelled {
            let connection = makeConnection(host: host, interface: interface)
            let goOn = await closing(connection) { await downloadOnce(connection, host: host, counter: counter, until: deadline) }
            if !goOn { return }
        }
    }

    /// One download. False when the stream is over.
    private static func downloadOnce(_ connection: NWConnection, host: String, counter: ByteCounter, until deadline: ContinuousClock.Instant) async -> Bool {
        do {
            try await ready(connection)
            let request = Data("GET /__down?bytes=\(counter.downloadChunk) HTTP/1.1\r\nHost: \(host)\r\nUser-Agent: Spectra\r\nConnection: close\r\n\r\n".utf8)
            try await write(connection, request)
            var header = Data()
            var status: Int?
            var finished = false
            while ContinuousClock.now < deadline, !Task.isCancelled {
                let piece = try await read(connection)
                if let data = piece.data {
                    if status != nil {
                        counter.add(data.count)
                    } else {
                        header.append(data)
                        if let range = header.range(of: Data("\r\n\r\n".utf8)) {
                            status = statusCode(header[..<range.lowerBound])
                            guard let code = status, (200..<300).contains(code) else { break }
                            counter.add(header.count - range.upperBound)
                        }
                    }
                }
                if piece.done { finished = true; break }
            }
            connection.cancel()
            if let code = status, !(200..<300).contains(code) {
                if code == 429 { counter.throttle(shrink: true) }
                try? await Task.sleep(for: .milliseconds(code == 429 ? 300 : 200))
                return true
            }
            return finished
        } catch {
            connection.cancel()
            return false
        }
    }

    /// The status of an HTTP reply's first line, like 200 in "HTTP/1.1 200 OK".
    static func statusCode(_ header: Data) -> Int? {
        let line = String(decoding: header.prefix(64), as: UTF8.self).split(separator: "\r\n").first ?? ""
        let parts = line.split(separator: " ")
        return parts.count > 1 ? Int(parts[1]) : nil
    }

    /// POSTs bodies on one kept-alive connection until the deadline. A body only counts once the server has answered,
    /// so socket buffers do not inflate the number. Bodies start small and grow with the line (`UploadPace`): on a slow
    /// mobile uplink a whole megabyte per stream was never answered in time, and nothing was counted at all.
    static func upload(host: String, counter: ByteCounter, until deadline: ContinuousClock.Instant, interface: NWInterface.InterfaceType?) async {
        var pace = UploadPace()
        // A connection the mobile network drops is opened again.
        while ContinuousClock.now < deadline, !Task.isCancelled {
            let connection = makeConnection(host: host, interface: interface)
            defer { connection.cancel() }
            do {
                try await closing(connection) {
                    try await ready(connection)
                    while ContinuousClock.now < deadline, !Task.isCancelled {
                        let size = pace.size
                        let header = Data("POST /__up HTTP/1.1\r\nHost: \(host)\r\nUser-Agent: Spectra\r\nContent-Type: application/octet-stream\r\nContent-Length: \(size)\r\n\r\n".utf8)
                        let start = ContinuousClock.now
                        try await write(connection, header + Data(count: size))
                        let status = try await readResponse(connection)
                        if let status, (200..<300).contains(status) {
                            counter.add(size)
                            pace.answered(in: elapsed(since: start))
                        } else {
                            if status == 429 { counter.throttle(shrink: false) }
                            try? await Task.sleep(for: .milliseconds(status == 429 ? 300 : 200))
                        }
                    }
                }
            } catch {
                try? await Task.sleep(for: .milliseconds(200))
            }
        }
    }

    // MARK: Plumbing

    /// Runs `body` and closes the connection when the task is cancelled: reads and writes wait for the network and
    /// never look at cancellation themselves, so a stalled line kept the test going long after its time was up.
    private static func closing<T>(_ connection: NWConnection, _ body: () async throws -> T) async rethrows -> T {
        try await withTaskCancellationHandler(operation: body, onCancel: { connection.cancel() })
    }

    private static func makeConnection(host: String, interface: NWInterface.InterfaceType?) -> NWConnection {
        let parameters = NWParameters.tls
        if let interface { parameters.requiredInterfaceType = interface }
        return NWConnection(host: NWEndpoint.Host(host), port: 443, using: parameters)
    }

    private static func ready(_ connection: NWConnection) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let once = OnceFlag()
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: once.run { continuation.resume() }
                case .failed(let error): once.run { continuation.resume(throwing: error) }
                case .cancelled: once.run { continuation.resume(throwing: CancellationError()) }
                default: break
                }
            }
            connection.start(queue: .global(qos: .userInitiated))
            // Cancelled before the start: the handler above may never be told.
            if Task.isCancelled {
                connection.cancel()
                once.run { continuation.resume(throwing: CancellationError()) }
            }
        }
    }

    private static func write(_ connection: NWConnection, _ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            })
        }
    }

    private static func read(_ connection: NWConnection) async throws -> (data: Data?, done: Bool) {
        try await withCheckedThrowingContinuation { continuation in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { data, _, isComplete, error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume(returning: (data, isComplete)) }
            }
        }
    }

    /// Reads one HTTP response on a kept-alive connection: headers, then as
    /// many body bytes as Content-Length says. Returns the status.
    @discardableResult
    private static func readResponse(_ connection: NWConnection) async throws -> Int? {
        var raw = Data()
        let marker = Data("\r\n\r\n".utf8)
        var needed: Int?
        var headerEnd: Int?
        while true {
            let piece = try await read(connection)
            if let data = piece.data { raw.append(data) }
            if headerEnd == nil, let range = raw.range(of: marker) {
                headerEnd = range.upperBound
                let header = String(decoding: raw[..<range.lowerBound], as: UTF8.self).lowercased()
                needed = header.split(separator: "\r\n").compactMap { line -> Int? in
                    let pair = line.split(separator: ":", maxSplits: 1)
                    guard pair.count == 2, pair[0].trimmingCharacters(in: .whitespaces) == "content-length" else { return nil }
                    return Int(pair[1].trimmingCharacters(in: .whitespaces))
                }.first ?? 0
            }
            if let headerEnd, let needed, raw.count - headerEnd >= needed { return statusCode(raw[..<headerEnd]) }
            if piece.done { throw LinkTimeout() }
        }
    }

    private static func withTimeout<T: Sendable>(seconds: TimeInterval, _ body: @escaping @Sendable () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await body() }
            group.addTask {
                try await Task.sleep(for: .seconds(seconds))
                throw LinkTimeout()
            }
            guard let first = try await group.next() else { throw LinkTimeout() }
            group.cancelAll()
            return first
        }
    }

    private static func elapsed(since start: ContinuousClock.Instant) -> TimeInterval {
        let parts = (ContinuousClock.now - start).components
        return TimeInterval(parts.seconds) + TimeInterval(parts.attoseconds) / 1e18
    }
}

nonisolated private final class OnceFlag: @unchecked Sendable {
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
