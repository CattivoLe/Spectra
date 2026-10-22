import Foundation

/// One header or query/body parameter. Port of Android `HttpField`.
nonisolated struct HttpField: Codable, Equatable, Sendable {
    let name: String
    let value: String
}

/// One cleartext HTTP request and, once it arrives, its response. `responseJson`
/// is the response body when it is text/JSON; a cut body kept what fit, with
/// `responseCut` marking the rest dropped. Port of Android `HttpExchange`.
nonisolated struct HttpExchange: Codable, Equatable, Sendable {
    let method: String
    let path: String
    let query: [HttpField]
    let requestHeaders: [HttpField]
    let parameters: [HttpField]
    let status: Int?
    let responseHeaders: [HttpField]
    let responseJson: String?
    let responseCut: Bool

    var summary: String { "\(method) \(path)" }
}

/// How a row is grouped in the traffic list. HTTP and TLS win over raw TCP.
nonisolated enum TrafficKind: String, Sendable { case http, tls, dns, tcp, udp }

/// One forwarded connection. `host` comes from DNS, the TLS server name, or
/// cleartext HTTP. `exchanges` are the HTTP messages, including those read after
/// TLS was opened with this app's certificate. Port of Android `TrafficFlow`.
nonisolated struct TrafficFlow: Codable, Equatable, Sendable, Identifiable {
    let id: Int64
    let proto: String
    let source: String
    let sourcePort: Int
    let destination: String
    let destinationPort: Int
    var host: String?
    var request: String?
    var app: String?
    var tls: Bool
    var sent: Int64
    var received: Int64
    let startedAt: Int64
    var lastAt: Int64
    var closed: Bool
    var exchanges: [HttpExchange]
    /// The phone refused the certificate this app offered for this connection.
    var refused: Bool

    init(
        id: Int64, proto: String, source: String, sourcePort: Int,
        destination: String, destinationPort: Int, host: String? = nil,
        request: String? = nil, app: String? = nil, tls: Bool = false,
        sent: Int64 = 0, received: Int64 = 0, startedAt: Int64, lastAt: Int64,
        closed: Bool = false, exchanges: [HttpExchange] = [], refused: Bool = false
    ) {
        self.id = id; self.proto = proto; self.source = source; self.sourcePort = sourcePort
        self.destination = destination; self.destinationPort = destinationPort; self.host = host
        self.request = request; self.app = app; self.tls = tls; self.sent = sent
        self.received = received; self.startedAt = startedAt; self.lastAt = lastAt
        self.closed = closed; self.exchanges = exchanges; self.refused = refused
    }

    var title: String { (host?.isEmpty == false ? host : nil) ?? destination }

    var kind: TrafficKind {
        if !exchanges.isEmpty || !(request ?? "").isEmpty { return .http }
        if tls { return .tls }
        if destinationPort == 53 { return .dns }
        if proto == "UDP" { return .udp }
        return .tcp
    }
}

/// A saved recording: the connections forwarded during one run. Port of
/// Android `TrafficSession`.
nonisolated struct TrafficSession: Codable, Equatable, Sendable, Identifiable {
    let id: String
    let startedAt: Int64
    let stoppedAt: Int64
    let flows: [TrafficFlow]

    func encoded() -> Data { (try? JSONEncoder().encode(self)) ?? Data() }

    static func decode(_ data: Data) -> TrafficSession? {
        try? JSONDecoder().decode(TrafficSession.self, from: data)
    }
}
