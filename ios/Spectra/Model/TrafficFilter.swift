import Foundation

/// What the list of requests in a recording is narrowed to.
nonisolated struct RequestFilter: Equatable, Sendable {
    /// The methods with a chip of their own whatever was recorded; others get one when they occur.
    static let commonMethods = ["GET", "POST", "PUT", "PATCH", "DELETE"]

    /// Methods shown; empty shows all.
    var methods: Set<String> = []
    /// Only answers of 400 and above, and requests that got none.
    var failedOnly = false
    /// Only connections that went over to WebSocket.
    var webSocketOnly = false
    var query = ""

    /// True when a chip is on, so an empty list is the filter's doing.
    var isNarrowing: Bool { !methods.isEmpty || failedOnly || webSocketOnly }

    func passes(_ exchange: HttpExchange, of flow: TrafficFlow) -> Bool {
        if !methods.isEmpty, !methods.contains(exchange.method.uppercased()) { return false }
        if failedOnly, !Self.failed(exchange, of: flow) { return false }
        if webSocketOnly, exchange.messages.isEmpty { return false }
        return Self.matches(query, flow.title, exchange.summary)
    }

    /// An error answer, or none although the connection is over. One still on its way has not failed.
    static func failed(_ exchange: HttpExchange, of flow: TrafficFlow) -> Bool {
        guard let status = exchange.status else { return flow.closed }
        return status >= 400
    }

    /// The methods to offer: the common ones, then whatever else was recorded.
    static func methods(in flows: [TrafficFlow]) -> [String] {
        let recorded = Set(flows.flatMap(\.exchanges).map { $0.method.uppercased() })
        return commonMethods + recorded.subtracting(commonMethods).sorted()
    }

    static func matches(_ query: String, _ texts: String?...) -> Bool {
        let needle = query.trimmingCharacters(in: .whitespaces)
        return needle.isEmpty || texts.contains { $0?.localizedCaseInsensitiveContains(needle) == true }
    }
}

/// What the list of connections is narrowed to: what was read in them (HTTP, TLS, DNS), or the transport they went
/// over. The transport takes in everything on it: nearly every TCP connection is also TLS or HTTP, so TCP without
/// them was always empty, and so was UDP without DNS.
nonisolated enum ConnectionFilter: String, CaseIterable, Sendable {
    case http, tls, dns, tcp, udp

    var title: String { rawValue.uppercased() }

    func passes(_ flow: TrafficFlow) -> Bool {
        switch self {
        case .http: flow.kind == .http
        case .tls: flow.kind == .tls
        case .dns: flow.kind == .dns
        case .tcp: flow.proto == "TCP"
        case .udp: flow.proto == "UDP"
        }
    }

    /// With no filter DNS lookups stay out: they outnumber everything else and say little.
    static func passes(_ filter: ConnectionFilter?, _ flow: TrafficFlow) -> Bool {
        filter?.passes(flow) ?? (flow.kind != .dns)
    }
}
