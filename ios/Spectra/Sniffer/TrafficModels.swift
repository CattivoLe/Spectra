import Foundation

/// One header or query/body parameter. Port of Android `HttpField`.
nonisolated struct HttpField: Codable, Hashable, Sendable {
    let name: String
    let value: String
}

/// One cleartext HTTP request and, once it arrives, its response. Port of Android `HttpExchange`.
///
/// Bodies are not kept here: they go to a `TrafficBodies` folder as files, so the list the tunnel publishes
/// stays small. `requestBodyStored`/`responseBodyStored` say a body was handed over to be written.
/// `responseJson` is how older versions kept the response (its text when JSON or readable): recordings saved
/// before bodies were stored still have it, new ones leave it empty.
///
/// Times are milliseconds since the epoch: the request's first byte, its last byte, the response's first byte
/// and its last byte. Sizes count bytes on the wire: the header block, then the body as sent.
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
    var requestCut = false
    var requestBodyStored = false
    var responseBodyStored = false
    var httpVersion: String?
    /// The request target as sent, path and query undecoded. Nil in older recordings.
    var target: String?
    var statusText: String?
    var startedAt: Int64?
    var requestEndAt: Int64?
    var responseStartAt: Int64?
    var endedAt: Int64?
    var requestHeaderBytes: Int64?
    var requestBodyBytes: Int64?
    var responseHeaderBytes: Int64?
    var responseBodyBytes: Int64?
    /// WebSocket messages after a `101 Switching Protocols`, oldest first.
    var messages: [WsMessage] = []
    /// Messages past the cap that were counted but not listed.
    var messagesDropped = 0

    init(
        method: String, path: String, query: [HttpField], requestHeaders: [HttpField],
        parameters: [HttpField], status: Int?, responseHeaders: [HttpField],
        responseJson: String? = nil, responseCut: Bool = false, requestCut: Bool = false,
        requestBodyStored: Bool = false, responseBodyStored: Bool = false,
        httpVersion: String? = nil, target: String? = nil, statusText: String? = nil,
        startedAt: Int64? = nil, requestEndAt: Int64? = nil, responseStartAt: Int64? = nil, endedAt: Int64? = nil,
        requestHeaderBytes: Int64? = nil, requestBodyBytes: Int64? = nil,
        responseHeaderBytes: Int64? = nil, responseBodyBytes: Int64? = nil
    ) {
        self.method = method; self.path = path; self.query = query; self.requestHeaders = requestHeaders
        self.parameters = parameters; self.status = status; self.responseHeaders = responseHeaders
        self.responseJson = responseJson; self.responseCut = responseCut; self.requestCut = requestCut
        self.requestBodyStored = requestBodyStored; self.responseBodyStored = responseBodyStored
        self.httpVersion = httpVersion; self.target = target; self.statusText = statusText
        self.startedAt = startedAt; self.requestEndAt = requestEndAt
        self.responseStartAt = responseStartAt; self.endedAt = endedAt
        self.requestHeaderBytes = requestHeaderBytes; self.requestBodyBytes = requestBodyBytes
        self.responseHeaderBytes = responseHeaderBytes; self.responseBodyBytes = responseBodyBytes
    }

    /// Recordings from before bodies, times and sizes were kept lack those keys: they read as absent.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        method = try c.decode(String.self, forKey: .method)
        path = try c.decode(String.self, forKey: .path)
        query = try c.decodeIfPresent([HttpField].self, forKey: .query) ?? []
        requestHeaders = try c.decodeIfPresent([HttpField].self, forKey: .requestHeaders) ?? []
        parameters = try c.decodeIfPresent([HttpField].self, forKey: .parameters) ?? []
        status = try c.decodeIfPresent(Int.self, forKey: .status)
        responseHeaders = try c.decodeIfPresent([HttpField].self, forKey: .responseHeaders) ?? []
        responseJson = try c.decodeIfPresent(String.self, forKey: .responseJson)
        responseCut = try c.decodeIfPresent(Bool.self, forKey: .responseCut) ?? false
        requestCut = try c.decodeIfPresent(Bool.self, forKey: .requestCut) ?? false
        requestBodyStored = try c.decodeIfPresent(Bool.self, forKey: .requestBodyStored) ?? false
        responseBodyStored = try c.decodeIfPresent(Bool.self, forKey: .responseBodyStored) ?? false
        httpVersion = try c.decodeIfPresent(String.self, forKey: .httpVersion)
        target = try c.decodeIfPresent(String.self, forKey: .target)
        statusText = try c.decodeIfPresent(String.self, forKey: .statusText)
        startedAt = try c.decodeIfPresent(Int64.self, forKey: .startedAt)
        requestEndAt = try c.decodeIfPresent(Int64.self, forKey: .requestEndAt)
        responseStartAt = try c.decodeIfPresent(Int64.self, forKey: .responseStartAt)
        endedAt = try c.decodeIfPresent(Int64.self, forKey: .endedAt)
        requestHeaderBytes = try c.decodeIfPresent(Int64.self, forKey: .requestHeaderBytes)
        requestBodyBytes = try c.decodeIfPresent(Int64.self, forKey: .requestBodyBytes)
        responseHeaderBytes = try c.decodeIfPresent(Int64.self, forKey: .responseHeaderBytes)
        responseBodyBytes = try c.decodeIfPresent(Int64.self, forKey: .responseBodyBytes)
        messages = try c.decodeIfPresent([WsMessage].self, forKey: .messages) ?? []
        messagesDropped = try c.decodeIfPresent(Int.self, forKey: .messagesDropped) ?? 0
    }

    var summary: String { "\(method) \(path)" }

    /// Header block plus body, as sent. Nil for recordings made before sizes were kept.
    var requestSize: Int64? { requestHeaderBytes.map { $0 + (requestBodyBytes ?? 0) } }
    var responseSize: Int64? { responseHeaderBytes.map { $0 + (responseBodyBytes ?? 0) } }

    /// From the request's first byte to the response's first byte.
    var waitMs: Int64? {
        guard let startedAt, let responseStartAt else { return nil }
        return max(0, responseStartAt - startedAt)
    }

    /// From the request's first byte to the response's last byte.
    var durationMs: Int64? {
        guard let startedAt, let endedAt else { return nil }
        return max(0, endedAt - startedAt)
    }

    func header(_ name: String, response: Bool) -> String? {
        (response ? responseHeaders : requestHeaders).first { $0.name.caseInsensitiveCompare(name) == .orderedSame }?.value
    }
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
