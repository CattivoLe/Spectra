import Foundation
import Network

/// Which servers the speed test runs against.
///
/// Cloudflare is anycast: one name, and the network picks the nearest edge,
/// so there is nothing to choose. Measurement Lab runs its own machines in
/// many cities and names the nearest few, so a specific one can be picked.
enum SpeedServerChoice: Equatable, Hashable {
    case cloudflare
    /// nil picks the nearest machine M-Lab offers at test time.
    case mlab(machine: String?)

    init(raw: String) {
        if raw == "mlab" {
            self = .mlab(machine: nil)
        } else if raw.hasPrefix("mlab:") {
            self = .mlab(machine: String(raw.dropFirst(5)))
        } else {
            self = .cloudflare
        }
    }

    var raw: String {
        switch self {
        case .cloudflare: "cloudflare"
        case .mlab(nil): "mlab"
        case .mlab(let machine?): "mlab:\(machine)"
        }
    }
}

/// One M-Lab machine offered by its locate service, with single-use test URLs.
nonisolated struct MLabServer: Identifiable, Equatable, Sendable {
    /// Like "mlab1-beg01.mlab-oti.measurement-lab.org".
    var machine: String
    var city: String
    var country: String
    var downloadURL: URL
    var uploadURL: URL

    var id: String { machine }

    /// The host the test URLs point at, for latency probes.
    var host: String { downloadURL.host ?? machine }

    /// "mlab1-beg01", the part that tells machines in one city apart.
    var shortName: String { machine.split(separator: ".").first.map(String.init) ?? machine }
}

/// The ndt7 protocol of Measurement Lab: one WebSocket per direction. The
/// server streams binary messages for a download; for an upload the client
/// sends them and the server reports, in text messages, how much arrived.
nonisolated enum MLab {
    static let subprotocol = "net.measurementlab.ndt.v7"

    /// The nearest machines for whoever asks, with fresh access tokens.
    static func locate(interface: NWInterface.InterfaceType?) async -> [MLabServer] {
        guard let result = try? await LinkHTTP.send(
            host: "locate.measurementlab.net",
            path: "/v2/nearest/ndt/ndt7?client_name=spectra-ios",
            accept: "application/json",
            timeout: 8,
            maxBytes: 200_000,
            interfaceType: interface
        ), result.status == 200 else { return [] }
        return parseLocate(result.body)
    }

    static func parseLocate(_ data: Data) -> [MLabServer] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let results = root["results"] as? [[String: Any]] else { return [] }
        return results.compactMap { entry in
            guard let machine = entry["machine"] as? String,
                  let urls = entry["urls"] as? [String: String],
                  let down = urls["wss:///ndt/v7/download"].flatMap(URL.init(string:)),
                  let up = urls["wss:///ndt/v7/upload"].flatMap(URL.init(string:)) else { return nil }
            let location = entry["location"] as? [String: Any]
            return MLabServer(
                machine: machine,
                city: location?["city"] as? String ?? "",
                country: location?["country"] as? String ?? "",
                downloadURL: down,
                uploadURL: up
            )
        }
    }

    /// Counts every byte the server streams until the deadline.
    static func download(url: URL, counter: ByteCounter, until deadline: ContinuousClock.Instant, interface: NWInterface.InterfaceType?) async {
        let connection = makeConnection(url, interface: interface)
        defer { connection.cancel() }
        do {
            try await ready(connection)
            while ContinuousClock.now < deadline, !Task.isCancelled {
                let message = try await receive(connection)
                if message.closed { return }
                counter.add(message.data?.count ?? 0)
            }
        } catch {
            return
        }
    }

    /// Sends binary messages until the deadline, growing them as ndt7
    /// suggests, and counts what the server says it received: what the
    /// phone has merely queued in its socket would inflate the number.
    static func upload(url: URL, counter: ByteCounter, until deadline: ContinuousClock.Instant, interface: NWInterface.InterfaceType?) async {
        let connection = makeConnection(url, interface: interface)
        defer { connection.cancel() }
        do {
            try await ready(connection)
        } catch {
            return
        }
        let tally = UploadTally(counter: counter)
        let reader = Task.detached {
            while !Task.isCancelled {
                guard let message = try? await receive(connection), !message.closed else { return }
                // Measurements are JSON text; parse whatever looks like it.
                guard let data = message.data, data.first == UInt8(ascii: "{"),
                      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
                if let total = serverReceived(object) { tally.server(total) }
            }
        }
        var size = 1 << 13
        var sent = 0
        do {
            while ContinuousClock.now < deadline, !Task.isCancelled {
                let chunk = Data(count: size)
                try await send(connection, chunk)
                sent += size
                tally.sent(size)
                if size < (1 << 20), sent >= size * 16 { size *= 2 }
            }
        } catch {}
        reader.cancel()
    }

    /// Bytes the server says it has received: TCPInfo.BytesReceived or
    /// AppInfo.NumBytes, whichever is larger.
    static func serverReceived(_ measurement: [String: Any]) -> Int? {
        let tcp = (measurement["TCPInfo"] as? [String: Any])?["BytesReceived"] as? NSNumber
        let app = (measurement["AppInfo"] as? [String: Any])?["NumBytes"] as? NSNumber
        let values = [tcp, app].compactMap { $0?.intValue }
        return values.max()
    }

    // MARK: Plumbing

    private static func makeConnection(_ url: URL, interface: NWInterface.InterfaceType?) -> NWConnection {
        let parameters = NWParameters.tls
        let websocket = NWProtocolWebSocket.Options()
        websocket.setSubprotocols([subprotocol])
        websocket.autoReplyPing = true
        websocket.maximumMessageSize = 1 << 25
        parameters.defaultProtocolStack.applicationProtocols.insert(websocket, at: 0)
        if let interface { parameters.requiredInterfaceType = interface }
        return NWConnection(to: .url(url), using: parameters)
    }

    private static func ready(_ connection: NWConnection) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let once = Once()
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: once.run { continuation.resume() }
                case .failed(let error), .waiting(let error): once.run { continuation.resume(throwing: error) }
                case .cancelled: once.run { continuation.resume(throwing: CancellationError()) }
                default: break
                }
            }
            connection.start(queue: .global(qos: .userInitiated))
        }
    }

    private struct Message {
        var data: Data?
        var text: Bool
        var closed: Bool
    }

    private static func receive(_ connection: NWConnection) async throws -> Message {
        try await withCheckedThrowingContinuation { continuation in
            connection.receiveMessage { data, context, _, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata
                let opcode = metadata?.opcode
                continuation.resume(returning: Message(data: data, text: opcode == .text, closed: opcode == .close))
            }
        }
    }

    private static func send(_ connection: NWConnection, _ data: Data) async throws {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "ndt7", metadata: [metadata])
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            })
        }
    }

    /// Feeds the shared counter during an upload. Until the server reports,
    /// bytes count when handed to the socket; once it does, its total takes
    /// over, so queued bytes never count twice and the number settles on
    /// what actually arrived.
    private final class UploadTally: @unchecked Sendable {
        private let lock = NSLock()
        private let counter: ByteCounter
        private var counted = 0
        private var reported = false

        init(counter: ByteCounter) { self.counter = counter }

        func sent(_ bytes: Int) {
            lock.lock()
            defer { lock.unlock() }
            guard !reported else { return }
            counted += bytes
            counter.add(bytes)
        }

        func server(_ total: Int) {
            lock.lock()
            defer { lock.unlock() }
            reported = true
            if total > counted {
                counter.add(total - counted)
                counted = total
            }
        }
    }

    private final class Once: @unchecked Sendable {
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
