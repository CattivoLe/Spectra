import Foundation

/// Where a recorded connection is: its recording (nil is the live one), its number there and when it began. The
/// start tells a connection of this recording from the one that had the same number in another.
nonisolated struct TrafficRef: Hashable, Sendable {
    var session: String?
    var flow: Int64
    var startedAt: Int64
    /// The request in it; nil for the connection itself.
    var index: Int?

    init(session: String?, flow: TrafficFlow, index: Int? = nil) {
        self.session = session
        self.flow = flow.id
        startedAt = flow.startedAt
        self.index = index
    }

    init?(_ text: String) {
        let parts = text.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4, let flow = Int64(parts[1]), let started = Int64(parts[2]) else { return nil }
        session = parts[0].isEmpty ? nil : parts[0]
        self.flow = flow
        startedAt = started
        index = Int(parts[3])
    }

    /// What a saved card keeps: numbers only, nothing of the request itself.
    var text: String { "\(session ?? "")|\(flow)|\(startedAt)|\(index.map(String.init) ?? "")" }
}

/// What the cards of recorded traffic read it through: the live recording, the saved ones and their bodies.
@MainActor
struct TrafficLibrary {
    var live: () -> [TrafficFlow] = { [] }
    var saved: () -> [TrafficSession] = { [] }
    /// The recorder is on: a check made now would go through it and, with decryption, meet Spectra's own certificate.
    var recording: () -> Bool = { false }
    var body: @Sendable (_ session: String?, _ flow: Int64, _ index: Int, _ request: Bool) -> Data? = { _, _, _, _ in nil }

    func flows(_ session: String?) -> [TrafficFlow]? {
        guard let session else { return live() }
        return saved().first { $0.id == session }?.flows
    }

    /// The connection `ref` points to, and the recording it is in now: one that was live may have been saved since.
    func find(_ ref: TrafficRef) -> (session: String?, flow: TrafficFlow)? {
        func same(_ flow: TrafficFlow) -> Bool { flow.id == ref.flow && flow.startedAt == ref.startedAt }
        if let flow = flows(ref.session)?.first(where: same) { return (ref.session, flow) }
        if let flow = live().first(where: same) { return (nil, flow) }
        for session in saved() { if let flow = session.flows.first(where: same) { return (session.id, flow) } }
        return nil
    }

    func bodies(_ session: String?) -> TrafficAudit.Bodies {
        let body = body
        return { hit, request in body(session, hit.flow.id, hit.index, request) }
    }
}

/// Summary cards of one recorded request, of an endpoint's requests and of one connection. Same contract as
/// `AssistantCards`: findings and buttons come from the rules (`TrafficAudit`), the model only puts them into words.
/// Everything a card keeps or hands the model is kinds, names, places and numbers: no value from a request, no body.
/// Port of Android `TrafficCards`; checked by `TrafficCardsTests`.
nonisolated enum TrafficCards {
    /// What the model reads about one subject, in characters.
    static let budget = 1800
    static let hidden = "Values of parameters, headers, cookies and bodies are hidden on purpose: only their names, kinds and sizes are given. Never guess a value."

    struct Context: Sendable {
        var session: String?
        /// Every connection of the recording: where repeats and neighbours are counted.
        var flows: [TrafficFlow]
        var bodies: TrafficAudit.Bodies?
        var recording = false
        /// Devices of the user's own network by their address, as the LAN scan names them: a server may be one.
        var local: [String: String] = [:]
    }

    /// The server is a device of the user's own network: the LAN scan knows it by the same address.
    static func localTie(_ flow: TrafficFlow, _ context: Context) -> CrossLink? {
        guard let name = context.local[flow.destination] else { return nil }
        return CrossLink(
            finding: .init(risk: .info, text: String(localized: "The server is a device on your network: \(name) (\(flow.destination)).")),
            line: "The server is a device on the user's own local network, found by the LAN scan: \(name) (\(flow.destination))")
    }

    /// The card's lines with the tie right after the head, where the length cut cannot reach it.
    private static func tied(_ lines: [String], _ tie: CrossLink?) -> [String] {
        guard let tie else { return lines }
        var result = lines
        result.insert(tie.line, at: min(2, result.count))
        return result
    }

    // MARK: - One request

    static func request(_ flow: TrafficFlow, _ index: Int, _ context: Context) -> CardFacts? {
        guard flow.exchanges.indices.contains(index) else { return nil }
        let hit = RequestHit(flow: flow, index: index)
        let exchange = hit.exchange
        let endpoint = Endpoint.of(flow, exchange)
        let target = exchange.target ?? exchange.path
        let same = RequestHit.all(context.flows).filter {
            $0.exchange.method == exchange.method && ($0.exchange.target ?? $0.exchange.path) == target && Endpoint.of($0.flow, $0.exchange).host == endpoint.host
        }
        let scans = TrafficAudit.scan([hit], bodies: context.bodies)
        let issues = TrafficAudit.issues(scans, same: same)
        var found = issues.map(\.finding)
        found.append(.init(risk: .info, text: answerLine(hit)))
        if exchange.isWebSocket {
            let sent = exchange.messages.count(where: \.fromClient)
            found.append(.init(risk: .info, text: String(localized: "WebSocket: \(sent) messages sent, \(exchange.messages.count - sent) received.")))
        }
        if !scans[0].cleartext {
            let clean = !issues.contains { $0.kind == .secretInAddress || $0.kind == .personal }
            found.append(.init(risk: .ok, text: clean ? String(localized: "Sent encrypted, with no secrets in the address.") : String(localized: "Sent encrypted.")))
        }
        let tie = localTie(flow, context)
        if let finding = tie?.finding { found.append(finding) }
        let ref = TrafficRef(session: context.session, flow: flow, index: index)
        let data = text(tied(requestLines(hit, scans[0], same: same), tie))
        let group = EndpointGroup.find(endpoint.key, in: context.flows)
        let deep = [data, group.map { text(endpointLines($0, TrafficAudit.scan($0.hits))) }, text(connectionLines(flow, intro: false))].compactMap { $0 }
        return CardFacts(
            subject: .request(ref), title: title(endpoint), findings: AssistantCards.ranked(found),
            steps: Array(steps([hit], issues, ref: ref, host: endpoint.host, context).prefix(3)),
            data: data, deepData: deep.joined(separator: "\n\n")
        )
    }

    private static func answerLine(_ hit: RequestHit) -> String {
        let exchange = hit.exchange
        guard let status = exchange.status else {
            return hit.flow.closed ? String(localized: "No answer was recorded.") : String(localized: "The answer has not come yet.")
        }
        var parts = ["\(status)"]
        if !exchange.isStream, let duration = exchange.durationMs { parts.append(TrafficAudit.duration(duration)) }
        if let size = exchange.responseSize { parts.append(TrafficAudit.size(size)) }
        if let type = TrafficAudit.contentType(exchange) { parts.append(AssistantDigest.clean(type)) }
        return String(localized: "Answer: \(parts.joined(separator: " · ")).")
    }

    private static func requestLines(_ hit: RequestHit, _ scan: TrafficAudit.Scan, same: [RequestHit]) -> [String] {
        let flow = hit.flow, exchange = hit.exchange
        let endpoint = Endpoint.of(flow, exchange)
        var lines = ["One HTTP request an app on the user's phone made. " + hidden,
                     "Request: \(title(endpoint))\(endpoint.path.contains(Endpoint.placeholder) ? " ({id} stands for a changing part)" : "")"]
        lines.append("App: \(app(hit))")
        lines.append("Server: \(service(endpoint.host))")
        lines.append(encryption(hit))
        if let status = exchange.status {
            lines.append("Answer: status \(status)\(status >= 400 ? " (an error)" : status >= 300 ? " (a redirect)" : " (success)"), type \(TrafficAudit.contentType(exchange).map { AssistantDigest.clean($0) } ?? "not given")")
            if (300..<400).contains(status) {
                let to = exchange.header("Location", response: true).flatMap { URL(string: $0)?.host() }
                lines.append("Redirects to: \(to.map { AssistantDigest.clean($0) } ?? "another address on the same server")")
            }
        } else {
            lines.append(flow.closed ? "Answer: none, the connection closed without one" : "Answer: not yet, the request is still under way")
        }
        if exchange.isWebSocket {
            lines.append("This is a WebSocket: it stays open on purpose. Messages: \(exchange.messages.count + exchange.messagesDropped)")
        } else if let duration = exchange.durationMs {
            lines.append("Time: \(duration) ms\(exchange.waitMs.map { ", \($0) ms of it waiting for the server" } ?? "") — \(duration >= TrafficAudit.slowMs ? "slow" : "not slow")")
        } else {
            lines.append("Time: not recorded")
        }
        lines.append("Size: request \(bytes(exchange.requestSize)), answer \(bytes(exchange.responseSize))")
        lines.append("URL parameters: \(names(exchange.query))")
        lines.append("Body fields sent: \(names(exchange.parameters))")
        lines.append("Secrets: \(secrets([scan]))")
        lines.append("Cookies set with missing protection: \(cookies([hit]))")
        lines.append("Compression: \(compression(exchange))")
        lines.append("Caching: \(TrafficAudit.saysNothingAboutCaching(exchange) ? "the answer does not say" : exchange.header("Cache-Control", response: true).map { AssistantDigest.clean($0, limit: 60) } ?? "not applicable")")
        let (total, burst) = TrafficAudit.repeats(same)
        lines.append(total > 1 ? "The same request in this recording: \(total) times, at most \(burst) within 10 s" : "The same request in this recording: only this one")
        if TrafficAudit.isMapped(exchange) { lines.append("The answer came from the user's own Map Local rule on the phone, not from the server.") }
        return lines
    }

    // MARK: - An endpoint

    static func endpoint(_ key: String, _ context: Context) -> CardFacts? {
        guard let group = EndpointGroup.find(key, in: context.flows), let newest = group.hits.first else { return nil }
        let scans = TrafficAudit.scan(group.hits, bodies: context.bodies)
        let issues = TrafficAudit.issues(scans)
        var found = issues.map(\.finding)
        var line = String(localized: "Requests: \(group.hits.count).")
        if let typical = group.typicalMs, let worst = group.worstMs, !issues.contains(where: { $0.kind == .slow }) {
            line += " " + (typical == worst ? String(localized: "Usually \(TrafficAudit.duration(typical)).")
                                            : String(localized: "Usually \(TrafficAudit.duration(typical)), at worst \(TrafficAudit.duration(worst))."))
        }
        if group.bytes > 0 { line += " " + String(localized: "In all \(TrafficAudit.size(group.bytes)).") }
        found.append(.init(risk: .info, text: line))
        if group.errors == 0 { found.append(.init(risk: .ok, text: String(localized: "No errors."))) }
        if !scans.contains(where: \.cleartext) {
            let clean = !issues.contains { $0.kind == .secretInAddress || $0.kind == .personal }
            found.append(.init(risk: .ok, text: clean ? String(localized: "Sent encrypted, with no secrets in the address.") : String(localized: "Sent encrypted.")))
        }
        // The button repeats a request that failed, when there is one: that is the one worth trying again.
        let example = group.hits.first { RequestFilter.failed($0.exchange, of: $0.flow) } ?? newest
        let tie = localTie(newest.flow, context)
        if let finding = tie?.finding { found.append(finding) }
        let ref = TrafficRef(session: context.session, flow: example.flow, index: example.index)
        let data = text(tied(endpointLines(group, scans), tie))
        let host = group.endpoint.host
        let neighbours = EndpointGroup.build(context.flows).filter { $0.endpoint.host == host && $0.endpoint != group.endpoint }
        var deep = [data]
        deep.append(neighbours.isEmpty ? "Other endpoints of this server in the recording: none."
            : "Other endpoints of this server in the recording:\n" + neighbours.prefix(10).map { "- " + brief($0) }.joined(separator: "\n"))
        return CardFacts(
            subject: .endpoint(session: context.session, key: key), title: title(group.endpoint), findings: AssistantCards.ranked(found),
            steps: Array(steps([example], issues, ref: ref, host: host, context).prefix(3)),
            data: data, deepData: deep.joined(separator: "\n\n")
        )
    }

    private static func endpointLines(_ group: EndpointGroup, _ scans: [TrafficAudit.Scan]) -> [String] {
        let hits = group.hits
        guard let newest = hits.first else { return [] }
        var lines = ["All requests of one endpoint (one method, server and path; {id} stands for the part that changes) in a recording of the user's phone. " + hidden,
                     "Endpoint: \(title(group.endpoint))"]
        lines.append("App: \(app(newest))")
        lines.append("Server: \(service(group.endpoint.host))")
        let plain = scans.count(where: \.cleartext)
        lines.append(plain == 0 ? "Encrypted: yes, all requests" : "Encrypted: no, \(plain) of \(hits.count) requests went over plain HTTP")
        lines.append("Requests: \(hits.count), over \(max(0, group.lastAt - group.firstAt) / 1000) s")
        let statuses = group.statuses.prefix(5).map { $0.status == 0 ? "no answer ×\($0.count)" : "\($0.status) ×\($0.count)" }
        lines.append("Answers by status: \(statuses.isEmpty ? "none yet" : statuses.joined(separator: ", "))")
        lines.append("Errors: \(group.errors == 0 ? "none" : "\(group.errors) of \(hits.count)")")
        if group.webSocket {
            lines.append("This is a WebSocket: it stays open on purpose. Messages: \(hits.reduce(0) { $0 + $1.exchange.messages.count + $1.exchange.messagesDropped })")
        } else if let typical = group.typicalMs, let worst = group.worstMs {
            lines.append("Time: usually \(typical) ms, at worst \(worst) ms — \(typical >= TrafficAudit.slowTypicalMs ? "slow" : worst >= TrafficAudit.slowWorstMs ? "usually not slow" : "not slow")")
        } else {
            lines.append("Time: not recorded")
        }
        lines.append("Traffic in all: \(bytes(group.bytes))")
        lines.append("URL parameters: \(names(AssistantCards.unique(hits.flatMap(\.exchange.query), by: \.name)))")
        lines.append("Secrets: \(secrets(scans))")
        lines.append("Cookies set with missing protection: \(cookies(hits))")
        let uncompressed = hits.count { TrafficAudit.isUncompressed($0.exchange) }
        lines.append("Answers that could have been compressed and were not: \(uncompressed == 0 ? "none" : "\(uncompressed)")")
        let (total, burst) = TrafficAudit.repeats(hits)
        lines.append(burst >= TrafficAudit.repeatCount ? "Identical requests: up to \(total), at most \(burst) within 10 s" : "Needless repeats of an identical request: none")
        if group.http2 { lines.append("Protocol: HTTP/2, several requests share one connection; that is normal") }
        return lines
    }

    // MARK: - A connection

    static func connection(_ flow: TrafficFlow, _ context: Context) -> CardFacts {
        let hits = flow.exchanges.indices.map { RequestHit(flow: flow, index: $0) }.filter { !$0.exchange.method.isEmpty }.sorted { $0.at > $1.at }
        let scans = TrafficAudit.scan(hits, bodies: context.bodies)
        var issues = TrafficAudit.issues(scans)
        let host = (flow.host?.isEmpty == false ? flow.host! : flow.destination).lowercased()
        if hits.isEmpty, let service = TrackerHosts.service(of: host) {
            issues.append(.init(kind: .tracker, risk: .info, text: String(localized: "\(AssistantDigest.clean(host)) belongs to \("\(service.name) (\(TrafficAudit.kindName(service.kind)))"): it collects data about the app's use.")))
        }
        var found = issues.map(\.finding)
        if flow.refused {
            issues.append(.init(kind: .refused, risk: .info, text: String(localized: "The app refused Spectra's certificate and stayed unread: it accepts only its own, or the certificate is not trusted on this phone. That protects it and is not a fault.")))
            found.append(issues[issues.count - 1].finding)
        }
        switch flow.kind {
        case .http:
            let endpoints = Set(hits.map { Endpoint.of($0.flow, $0.exchange).key }).count
            found.append(.init(risk: .info, text: String(localized: "Requests in it: \(hits.count), to \(endpoints) endpoints.")))
            if flow.tls { found.append(.init(risk: .ok, text: String(localized: "Encrypted (TLS); Spectra opened it on this phone to show it."))) }
            // Read before the request was split off: an old recording with a request line and nothing more.
            if hits.isEmpty && !flow.tls { found.append(.init(risk: .warning, text: String(localized: "Unencrypted HTTP: anyone on the way can read and change it."))) }
        case .tls:
            if !flow.refused { found.append(.init(risk: .ok, text: String(localized: "Encrypted (TLS), not opened: only the server's name and the amount of data are known."))) }
        case .dns: found.append(.init(risk: .info, text: String(localized: "A name lookup (DNS): the phone asks for a server's address.")))
        case .tcp, .udp:
            found.append(.init(risk: .info, text: String(localized: "\(flow.proto), port \(flow.destinationPort): not HTTP and not TLS, so its content is not read.")))
        }
        found.append(.init(risk: .info, text: String(localized: "Sent \(TrafficAudit.size(flow.sent)), received \(TrafficAudit.size(flow.received)).")))
        if flow.sent >= TrafficAudit.largeBytes, flow.sent > flow.received * 4 {
            found.append(.init(risk: .info, text: String(localized: "Mostly upload: the phone sent far more than it received.")))
        }
        let tie = localTie(flow, context)
        if let finding = tie?.finding { found.append(finding) }
        let ref = TrafficRef(session: context.session, flow: flow)
        let data = text(tied(connectionLines(flow, intro: true), tie) + [
            "Secrets: \(hits.isEmpty ? "unknown, no request was read" : secrets(scans))",
            "Errors: \(hits.isEmpty ? "unknown, no request was read" : tallyErrors(hits))",
        ])
        let same = context.flows.filter { $0.title == flow.title && $0.id != flow.id }
        var deep = [data]
        deep.append(same.isEmpty ? "Other connections to this server in the recording: none."
            : "Other connections to this server in the recording: \(same.count), \(bytes(same.reduce(0) { $0 + $1.sent + $1.received })) in all, refused the certificate: \(same.filter(\.refused).count).")
        return CardFacts(
            subject: .connection(ref), title: AssistantDigest.clean(flow.title, limit: 60), findings: AssistantCards.ranked(found),
            steps: Array(steps(hits, issues, ref: ref, host: host, context).prefix(3)),
            data: data, deepData: deep.joined(separator: "\n\n")
        )
    }

    private static func connectionLines(_ flow: TrafficFlow, intro: Bool) -> [String] {
        var lines = intro ? ["One connection an app on the user's phone opened, recorded on the phone. " + hidden] : []
        lines.append("Connection to: \(AssistantDigest.clean(flow.title, limit: 60)), \(flow.proto) port \(flow.destinationPort)")
        if let app = flow.app { lines.append("App: \(AssistantDigest.clean(app))") }
        lines.append("Server: \(service((flow.host?.isEmpty == false ? flow.host! : flow.destination).lowercased()))")
        switch flow.kind {
        case .dns: lines.append("This is a DNS lookup; plain DNS is normal on most networks.")
        case .tls: lines.append(flow.refused ? "Encrypted: yes. The app refused Spectra's certificate (it accepts only its own, or the certificate is not trusted on this phone), so the content is unknown; that is a protection, not a fault." : "Encrypted: yes. Spectra did not open it, so the content is unknown.")
        case .http: lines.append(flow.tls ? "Encrypted: yes; Spectra opened it on this phone to show the requests." : "Encrypted: no, plain HTTP.")
        case .tcp, .udp: lines.append("Neither HTTP nor TLS was recognized: whether it is encrypted is unknown.")
        }
        lines.append("Sent \(bytes(flow.sent)), received \(bytes(flow.received)), lasted \(max(0, flow.lastAt - flow.startedAt) / 1000) s, \(flow.closed ? "closed" : "still open")")
        let groups = EndpointGroup.build([flow])
        if groups.isEmpty {
            lines.append("Requests read in it: none")
        } else {
            lines.append("Requests read in it: \(flow.exchanges.count)\(flow.exchanges.contains { $0.httpVersion == Http2Assembler.version } ? " over HTTP/2, which shares one connection; that is normal" : ""):")
            lines += groups.prefix(6).map { "- " + brief($0) }
            if groups.count > 6 { lines.append("- and \(groups.count - 6) more endpoints") }
        }
        return lines
    }

    // MARK: - Buttons

    /// Steps in the order they help with what was found. What asks the server something is not offered while the
    /// recorder is on: it would go through the recorder and, with decryption, meet Spectra's own certificate.
    private static func steps(_ hits: [RequestHit], _ issues: [TrafficIssue], ref: TrafficRef, host: String, _ context: Context) -> [CardStep] {
        let kinds = Set(issues.map(\.kind))
        var steps: [CardStep] = []
        let wrong = !kinds.isDisjoint(with: [.serverError, .clientError, .noAnswer, .slow])
        if let hit = hits.first, ref.index != nil, !hit.exchange.isWebSocket, hit.exchange.method.uppercased() != "CONNECT" {
            let quoted = AssistantCards.jsonString(ref.text) ?? "\"\""
            // Plain HTTP the system does not let the app send: a failure there would say nothing about the server.
            if ["GET", "HEAD"].contains(hit.exchange.method.uppercased()), !TrafficAudit.isCleartext(hit), !context.recording,
               wrong || kinds.contains(.repeated) {
                steps.append(call(.repeatRequest, quoted, String(localized: "Repeat the request and compare")))
            }
            // A request that changes something is only prepared: the user looks at it and sends it.
            steps.append(call(.openRepeat, quoted, String(localized: "Edit and repeat…")))
            if hit.flow.tls, wrong || kinds.contains(.mapped) {
                steps.append(call(.openMapLocal, quoted, String(localized: "Answer it from the phone…")))
            }
        }
        if kinds.contains(.refused) { steps.append(call(.openTLSHosts, nil, String(localized: "Choose the hosts to decrypt…"))) }
        if isSite(host), !context.recording {
            let site = AssistantCards.jsonString("https://\(host)") ?? "\"\""
            if !kinds.isDisjoint(with: [.cleartext, .secretUnencrypted, .tracker, .downgrade]) || !wrong {
                steps.append(call(.checkSite, site, String(localized: "Check the site's certificate and domain")))
            }
            if kinds.contains(.slow) || kinds.contains(.noAnswer), let quoted = AssistantCards.jsonString(host) {
                steps.append(CardStep(call: "{\"tool\": \"\(AssistantActions.Name.traceRoute.rawValue)\", \"host\": \(quoted)}",
                                      label: String(localized: "Trace the path to \(AssistantDigest.clean(host, limit: 30))")))
            }
        }
        return AssistantCards.unique(steps, by: \.tool)
    }

    private static func call(_ name: AssistantActions.Name, _ quoted: String?, _ label: String) -> CardStep {
        CardStep(call: quoted.map { "{\"tool\": \"\(name.rawValue)\", \"text\": \($0)}" } ?? name.rawValue, label: label)
    }

    /// A public name the registry and a certificate can be asked about: not an address, a port or a local name.
    private static func isSite(_ host: String) -> Bool {
        host.contains(".") && !host.contains(":") && !host.hasSuffix(".local") && host.unicodeScalars.contains { $0.properties.isAlphabetic }
            && LinkInspector.inspect("https://\(host)").map(LinkShelf.isWeb) == true
    }

    // MARK: - Words for the model

    static func title(_ endpoint: Endpoint) -> String { AssistantDigest.clean(endpoint.key, limit: 90) }

    /// `GET api.example.com/v1/users/{id} ×40, 3 errors, usually 240 ms`.
    static func brief(_ group: EndpointGroup) -> String {
        var parts = ["\(AssistantDigest.clean(group.endpoint.key, limit: 70)) ×\(group.hits.count)"]
        parts.append(group.errors == 0 ? "no errors" : "errors: \(group.errors)")
        if let typical = group.typicalMs { parts.append("usually \(typical) ms") }
        return parts.joined(separator: ", ")
    }

    private static func service(_ host: String) -> String {
        let name = AssistantDigest.clean(host, limit: 60)
        guard let service = TrackerHosts.service(of: host) else { return "\(name); not in the list of known analytics and advertising services" }
        return "\(name); belongs to \(service.name), known for \(TrafficAudit.englishKind(service.kind))"
    }

    private static func app(_ hit: RequestHit) -> String {
        if let app = hit.flow.app, !app.isEmpty { return AssistantDigest.clean(app) }
        // `Telegram/10.2 CFNetwork/…`: the product's name only.
        guard let agent = hit.exchange.header("User-Agent", response: false), let product = agent.split(whereSeparator: { "/ (".contains($0) }).first else { return "unknown" }
        if product == "Mozilla" { return "a browser, or a page inside an app" }
        return AssistantDigest.clean(String(product), limit: 30) + " (by its User-Agent)"
    }

    private static func encryption(_ hit: RequestHit) -> String {
        TrafficAudit.isCleartext(hit) ? "Encrypted: no, plain HTTP" : "Encrypted: yes; Spectra opened it on this phone to show it"
    }

    private static func secrets(_ scans: [TrafficAudit.Scan]) -> String {
        let found = AssistantCards.unique(scans.flatMap(\.secrets), by: \.self)
        if found.isEmpty { return "none found" }
        let open = scans.contains { $0.cleartext && !$0.secrets.isEmpty }
        let address = found.filter(\.place.inAddress)
        var text = open ? "sent UNENCRYPTED: \(TrafficAudit.englishList(found))" : address.isEmpty ? "none in the address" : "in the address: \(TrafficAudit.englishList(address))"
        let inside = found.filter { !$0.place.inAddress }
        // Signing in with a header over TLS is how it is done: said outright, or the model calls it a leak.
        if !open && !inside.isEmpty { text += "; sign-in data in headers or the body, sent encrypted, which is normal" }
        return text
    }

    private static func cookies(_ hits: [RequestHit]) -> String {
        let weak = AssistantCards.unique(hits.flatMap(TrafficAudit.weakCookies), by: \.self)
        return weak.isEmpty ? "none" : weak.prefix(4).map { "`\($0.name)` without \($0.missing.joined(separator: " and "))" }.joined(separator: "; ")
    }

    private static func compression(_ exchange: HttpExchange) -> String {
        if TrafficAudit.isUncompressed(exchange) { return "none, although the app accepts it and the answer is text" }
        return exchange.header("Content-Encoding", response: true).map { AssistantDigest.clean($0, limit: 20) } ?? "not applicable"
    }

    /// Names only, each safe to show; explicitly none when there are none.
    private static func names(_ fields: [HttpField]) -> String {
        let names = AssistantCards.unique(fields.map { TrafficSecrets.safeName($0.name) }, by: \.self)
        return names.isEmpty ? "none" : names.prefix(8).map { "`\($0)`" }.joined(separator: ", ") + (names.count > 8 ? " and \(names.count - 8) more" : "")
    }

    private static func tallyErrors(_ hits: [RequestHit]) -> String {
        let errors = hits.compactMap(\.exchange.status).filter { $0 >= 400 }
        return errors.isEmpty ? "none" : "\(errors.count) of \(hits.count) (\(TrafficAudit.tally(errors)))"
    }

    private static func text(_ lines: [String]) -> String {
        let text = lines.joined(separator: "\n")
        return text.count > budget ? String(text.prefix(budget)) + "\n…(cut)" : text
    }

    static func bytes(_ count: Int64?) -> String {
        guard let count else { return "unknown" }
        return count >= 1_048_576 ? String(format: "%.1f MB", Double(count) / 1_048_576) : count >= 1024 ? "\(count / 1024) KB" : "\(count) B"
    }

    // MARK: - Repeating a request

    /// What a repeated request showed against the recorded one, for the model: statuses, times and sizes only.
    static func compare(_ exchange: HttpExchange, _ again: RepeatResult) -> String {
        let before = "Recorded: \(exchange.status.map { "status \($0)" } ?? "no answer"), \(exchange.durationMs.map { "\($0) ms" } ?? "time unknown"), body \(bytes(exchange.responseBodyBytes))."
        // The system's own message is not passed on: it can quote the address.
        guard let status = again.status else { return before + "\nSent again just now: no answer." }
        var text = before + "\nSent again just now: status \(status), \(again.durationMs) ms, body \(bytes(Int64(again.body.count))) after decompression."
        text += "\nStatus: \(exchange.status == status ? "the same" : "changed")."
        if status == 401 || status == 403 { text += " The recorded sign-in may simply have expired since." }
        return text
    }
}
