import SwiftUI
import UIKit

/// The traffic sniffer screens, reached from the LAN tab. Mirrors the Android
/// `ui/SnifferScreens.kt`: live flow list, flow detail, HTTP exchange, saved
/// sessions, hosts to decrypt. Capture runs in the packet-tunnel extension; this shows what it
/// publishes into the shared App Group.
struct SnifferView: View {
    @Environment(TrafficSniffer.self) private var sniffer
    @Environment(CaptureShelf.self) private var captures
    @State private var exportItem: ShareItem?
    @Environment(\.scenePhase) private var scenePhase
    @State private var saves = 0

    var body: some View {
        @Bindable var sniffer = sniffer
        List {
            Section {
                Button {
                    Task { sniffer.running ? sniffer.stop() : await sniffer.start() }
                } label: {
                    Label(sniffer.running ? "Stop recording" : "Record traffic",
                          systemImage: sniffer.running ? "stop.circle.fill" : "record.circle")
                }
                if sniffer.running || !sniffer.flows.isEmpty {
                    NavigationLink { LiveTrafficView() } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Label("Requests and connections", systemImage: "list.bullet.rectangle")
                            Text("Requests: \(sniffer.flows.reduce(0) { $0 + $1.exchanges.count }) · connections: \(sniffer.flows.count)")
                                .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                    saveButton
                }
                if !sniffer.saved.isEmpty {
                    NavigationLink { SavedSessionsView() } label: {
                        Label("Saved sessions (\(sniffer.saved.count))", systemImage: "tray.full")
                    }
                }
                if !sniffer.flows.isEmpty {
                    Button("Clear", role: .destructive, action: sniffer.clear)
                }
                if let error = sniffer.error {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                        .font(.footnote)
                }
            }

            Section {
                Toggle("Decrypt TLS", isOn: $sniffer.decryptTLS)
                if sniffer.decryptTLS {
                    NavigationLink { TLSHostsView() } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Hosts to decrypt")
                            Text(hostFilterSummary(sniffer.hostFilter)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    if let fingerprint = sniffer.caFingerprint {
                        VStack(alignment: .leading, spacing: 4) {
                            trustState
                            Text(fingerprint).font(.caption2.monospaced()).foregroundStyle(.secondary)
                            Button("Export certificate") { exportItem = writeCertificate() }
                        }
                    }
                }
            } footer: {
                if sniffer.decryptTLS, sniffer.caTrusted != true {
                    Text("1. Export certificate → Save to Files.\n2. Open the saved file in Files, then Settings → Profile Downloaded → Install.\n3. Settings → General → About → Certificate Trust Settings → turn on Spectra.")
                }
            }

            Section {
                NavigationLink { ThrottleView() } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Throttling")
                        Text(throttleSummary(sniffer.throttle)).font(.caption).foregroundStyle(.secondary)
                    }
                }
                NavigationLink { MapLocalView() } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Map Local")
                        Text(mapLocalSummary(sniffer)).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Traffic")
        .task(id: scenePhase) { if scenePhase == .active { await sniffer.checkTrust() } }
        .sensoryFeedback(.success, trigger: saves)
        .summaryButton(isShown: !sniffer.flows.isEmpty,
                       subject: { .traffic },
                       prepare: { captures.keep(traffic: sniffer.flows, name: sniffer.running ? "recording in progress" : "current recording") })
        .shareSheet(item: $exportItem)
        .toolbar {
            if !sniffer.flows.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    HarExportButton(flows: sniffer.flows, sessionID: nil, item: $exportItem)
                }
            }
        }
    }

    /// Whether apps can accept the certificate at all, as the system itself answers.
    @ViewBuilder
    private var trustState: some View {
        switch sniffer.caTrusted {
        case true?:
            Label("This iPhone trusts Spectra's certificate.", systemImage: "checkmark.seal.fill")
                .font(.footnote).foregroundStyle(.green)
        case false?:
            Label("This iPhone does not trust Spectra's certificate yet: nothing can be decrypted.", systemImage: "xmark.seal.fill")
                .font(.footnote).foregroundStyle(.orange)
        case nil:
            Text("Install & trust Spectra's certificate to read HTTPS.")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    /// Says what a tap does and what it did: saved as it is (nothing to do), save, or update the session this
    /// recording was already saved as — never a second identical session.
    @ViewBuilder
    private var saveButton: some View {
        if sniffer.isCurrentSaved {
            Label("Saved", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        } else {
            Button {
                if sniffer.saveCurrent() { saves += 1 }
            } label: {
                Label(sniffer.currentSessionID == nil ? "Save session" : "Update saved session",
                      systemImage: "square.and.arrow.down")
            }
            .disabled(sniffer.flows.isEmpty)
        }
    }

    private func writeCertificate() -> ShareItem? {
        guard let der = sniffer.caCertificateDER() else { return nil }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Spectra-CA.cer")
        try? der.write(to: url)
        return ShareItem(url: url)
    }
}

/// Builds a HAR of `flows` off the main actor and hands it to the share sheet.
private struct HarExportButton: View {
    let flows: [TrafficFlow]
    let sessionID: String?
    @Binding var item: ShareItem?
    var label: LocalizedStringKey = "Export HAR"
    @Environment(TrafficSniffer.self) private var sniffer
    @State private var busy = false

    var body: some View {
        Button {
            busy = true
            let flows = flows, sessionID = sessionID, sniffer = sniffer
            Task {
                let url = await Task.detached(priority: .userInitiated) { () -> URL? in
                    let text = Har.build(flows, bodies: { flow, index, request in
                        sniffer.body(sessionID: sessionID, flowID: flow.id, index: index, request: request)
                    }, messages: { flow, index, number in
                        sniffer.message(sessionID: sessionID, flowID: flow.id, index: index, number: number)
                    }, version: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                    let stamp = Date().formatted(.iso8601.year().month().day().dateSeparator(.omitted).time(includingFractionalSeconds: false).timeSeparator(.omitted))
                    let url = FileManager.default.temporaryDirectory.appendingPathComponent("spectra-traffic-\(stamp).har")
                    do { try Data(text.utf8).write(to: url, options: .atomic) } catch { return nil }
                    return url
                }.value
                busy = false
                if let url { item = ShareItem(url: url) }
            }
        } label: {
            if busy { ProgressView() } else { Label(label, systemImage: "doc.badge.arrow.up") }
        }
        .disabled(busy)
    }
}

/// One HTTP request of a connection, as a row of its own.
private struct RequestHit: Identifiable {
    let flow: TrafficFlow
    let index: Int
    var id: String { "\(flow.id)-\(index)" }
    var exchange: HttpExchange { flow.exchanges[index] }
    var at: Int64 { exchange.startedAt ?? flow.lastAt }
}

/// The recording in progress, or the last one: its requests and connections.
struct LiveTrafficView: View {
    @Environment(TrafficSniffer.self) private var sniffer
    @Environment(CaptureShelf.self) private var captures
    @State private var exportItem: ShareItem?

    var body: some View {
        TrafficList(flows: sniffer.flows, sessionID: nil)
            .navigationTitle(sniffer.running ? "Recording" : "Recorded")
            .navigationBarTitleDisplayMode(.inline)
            .summaryButton(isShown: !sniffer.flows.isEmpty,
                           subject: { .traffic },
                           prepare: { captures.keep(traffic: sniffer.flows, name: sniffer.running ? "recording in progress" : "current recording") })
            .shareSheet(item: $exportItem)
            .toolbar {
                if !sniffer.flows.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        HarExportButton(flows: sniffer.flows, sessionID: nil, item: $exportItem)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(sniffer.running ? "Stop recording" : "Record traffic",
                           systemImage: sniffer.running ? "stop.circle.fill" : "record.circle") {
                        Task { sniffer.running ? sniffer.stop() : await sniffer.start() }
                    }
                }
            }
    }
}

/// A recording as a list of requests, newest first, or of the connections under them, each with its own filters.
private struct TrafficList: View {
    let flows: [TrafficFlow]
    let sessionID: String?
    @AppStorage("trafficShowsConnections") private var showsConnections = false
    @State private var filter = RequestFilter()
    @State private var connection: ConnectionFilter?

    var body: some View {
        List {
            Section {
                Picker("List", selection: $showsConnections) {
                    Text("Requests").tag(false)
                    Text("Connections").tag(true)
                }
                .pickerStyle(.segmented)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
            .listSectionSpacing(.compact)
            Section {
                FilterChipRow {
                    if showsConnections { connectionChips } else { requestChips }
                }
            }
            .listSectionSpacing(.compact)
            if showsConnections { connectionRows } else { requestRows }
        }
        .searchable(text: $filter.query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Host or request")
    }

    @ViewBuilder
    private var requestChips: some View {
        ForEach(RequestFilter.methods(in: flows), id: \.self) { method in
            FilterChip(title: LocalizedStringKey(method), isOn: filter.methods.contains(method)) {
                if filter.methods.remove(method) == nil { filter.methods.insert(method) }
            }
        }
        FilterChip(title: "Errors", systemImage: "exclamationmark.triangle", isOn: filter.failedOnly) { filter.failedOnly.toggle() }
        if filter.webSocketOnly || flows.contains(where: { $0.exchanges.contains { !$0.messages.isEmpty } }) {
            FilterChip(title: "WebSocket", isOn: filter.webSocketOnly) { filter.webSocketOnly.toggle() }
        }
    }

    @ViewBuilder
    private var connectionChips: some View {
        ForEach(ConnectionFilter.allCases, id: \.self) { kind in
            FilterChip(title: LocalizedStringKey(kind.title), count: flows.count(where: kind.passes), isOn: connection == kind) {
                connection = connection == kind ? nil : kind
            }
        }
    }

    @ViewBuilder
    private var requestRows: some View {
        let filter = filter
        let all = flows.flatMap { flow in flow.exchanges.indices.map { RequestHit(flow: flow, index: $0) } }
        let rows = all.filter { filter.passes($0.exchange, of: $0.flow) }.sorted { $0.at > $1.at }
        let refused = flows.filter(\.refused).count
        Section {
            if rows.isEmpty {
                Text(all.isEmpty ? "No requests read yet." : "Nothing matches the filter.")
                    .foregroundStyle(.secondary).font(.footnote)
            }
            ForEach(rows) { hit in
                NavigationLink {
                    ExchangeDetailView(flow: hit.flow, index: hit.index, sessionID: sessionID)
                } label: { RequestRow(hit: hit) }
            }
        } header: {
            if rows.count == all.count {
                Text("Requests: \(rows.count)")
            } else {
                Text("Requests: \(rows.count) of \(all.count)")
            }
        } footer: {
            if refused > 0 { Text("Connections that refused the certificate and went through unread: \(refused).") }
        }
    }

    @ViewBuilder
    private var connectionRows: some View {
        let rows = flows.filter {
            ConnectionFilter.passes(connection, $0) && RequestFilter.matches(filter.query, $0.title, $0.request)
        }
        Section {
            if rows.isEmpty { Text("Nothing matches the filter.").foregroundStyle(.secondary).font(.footnote) }
            ForEach(rows) { flow in
                NavigationLink { FlowDetailView(flow: flow, sessionID: sessionID) } label: { FlowRow(flow: flow) }
            }
        } header: {
            Text("Connections: \(rows.count) of \(flows.count)")
        } footer: {
            if connection == nil { Text("DNS lookups are left out until their filter is on.") }
        }
    }
}

private struct RequestRow: View {
    let hit: RequestHit

    private var statusColor: Color {
        switch hit.exchange.status ?? 0 {
        case 200..<300: .green
        case 300..<400: .blue
        case 400...: .red
        default: .secondary
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text(verbatim: hit.exchange.method).font(.caption.weight(.semibold).monospaced()).foregroundStyle(.tint)
                if hit.exchange.httpVersion == Http2Assembler.version { Badge(text: "H2") }
                if !hit.exchange.messages.isEmpty { Badge(text: "WS") }
                Text(verbatim: hit.exchange.path).font(.callout).lineLimit(1).truncationMode(.middle)
                Spacer(minLength: 4)
                Text(verbatim: hit.exchange.status.map(String.init) ?? "…")
                    .font(.caption.weight(.semibold).monospacedDigit()).foregroundStyle(statusColor)
            }
            Text(verbatim: ([hit.flow.title, hit.exchange.header("content-type", response: true)?.split(separator: ";").first.map(String.init)]
                .compactMap { $0 } + [exchangeLine(hit.exchange)].compactMap { $0 }).joined(separator: " · "))
                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
    }
}

private struct FlowRow: View {
    let flow: TrafficFlow
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(flow.title).font(.callout.weight(.medium)).lineLimit(1)
                if flow.tls { Image(systemName: "lock.fill").font(.caption2).foregroundStyle(.secondary) }
                Spacer()
                Text(flow.kind.rawValue.uppercased()).font(.caption2).foregroundStyle(.secondary)
            }
            if let request = flow.request {
                Text(request).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            if let first = flow.exchanges.first, let line = exchangeLine(first) {
                Text(verbatim: flow.exchanges.count > 1 ? "\(line) · " + String(localized: "requests: \(flow.exchanges.count)") : line)
                    .font(.caption2).foregroundStyle(.secondary)
            }
            Text("↑\(bytes(flow.sent)) ↓\(bytes(flow.received))").font(.caption2).foregroundStyle(.tertiary)
        }
    }
}

struct FlowDetailView: View {
    let flow: TrafficFlow
    /// The saved session the flow belongs to; nil for the live recording.
    let sessionID: String?
    @Environment(TrafficSniffer.self) private var sniffer
    @State private var exportItem: ShareItem?

    var body: some View {
        List {
            Section {
                InfoRow("Host", value: flow.title)
                InfoRow("Destination", value: "\(flow.destination):\(flow.destinationPort)")
                InfoRow("Protocol", value: flow.proto)
                InfoRow("Sent", value: bytes(flow.sent))
                InfoRow("Received", value: bytes(flow.received))
                if flow.refused { InfoRow("TLS", value: String(localized: "Certificate refused")) }
            }
            if !flow.exchanges.isEmpty {
                Section("HTTP") {
                    ForEach(Array(flow.exchanges.enumerated()), id: \.offset) { index, exchange in
                        NavigationLink {
                            ExchangeDetailView(flow: flow, index: index, sessionID: sessionID)
                        } label: {
                            VStack(alignment: .leading) {
                                HStack(spacing: 4) {
                                    if exchange.httpVersion == Http2Assembler.version { Badge(text: "H2") }
                                    if !exchange.messages.isEmpty { Badge(text: "WS") }
                                    Text(exchange.summary).font(.callout).lineLimit(1)
                                }
                                if let line = exchangeLine(exchange) {
                                    Text(line).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            Section {
                if !flow.exchanges.isEmpty {
                    HarExportButton(flows: [flow], sessionID: sessionID, item: $exportItem, label: "Export HAR for this connection")
                }
                if flow.refused, let host = flow.host, !host.isEmpty, sniffer.hostFilter.allows(host) {
                    Button {
                        sniffer.hostFilter = sniffer.hostFilter.excluding(host)
                    } label: {
                        Label("Leave \(host) encrypted", systemImage: "lock.open")
                    }
                }
            }
        }
        .navigationTitle(flow.title)
        .navigationBarTitleDisplayMode(.inline)
        .shareSheet(item: $exportItem)
    }
}

struct ExchangeDetailView: View {
    let flow: TrafficFlow
    let index: Int
    let sessionID: String?
    @Environment(TrafficSniffer.self) private var sniffer
    @State private var copied = 0

    private var exchange: HttpExchange { flow.exchanges[index] }

    var body: some View {
        List {
            Section { InfoRow("Request", value: exchange.summary)
                if let status = exchange.status { InfoRow("Status", value: "\(status)") }
                if let version = exchange.httpVersion { InfoRow("HTTP version", value: version) } }
            if let started = exchange.startedAt {
                Section("Timing") {
                    InfoRow("Started", value: preciseTime(started))
                    if let wait = exchange.waitMs { InfoRow("Waiting for the response", value: millis(wait)) }
                    if let duration = exchange.durationMs { InfoRow("Duration", value: millis(duration)) }
                    if let size = exchange.requestSize {
                        InfoRow("Request size", value: sizeLine(size, exchange.requestHeaderBytes, exchange.requestBodyBytes))
                    }
                    if let size = exchange.responseSize {
                        InfoRow("Response size", value: sizeLine(size, exchange.responseHeaderBytes, exchange.responseBodyBytes))
                    }
                }
            }
            if !exchange.query.isEmpty { fields("Query", exchange.query) }
            if !exchange.parameters.isEmpty { fields("Parameters", exchange.parameters) }
            if !exchange.requestHeaders.isEmpty { fields("Request headers", exchange.requestHeaders) }
            if exchange.requestBodyStored || (exchange.requestBodyBytes ?? 0) > 0 {
                Section("Request body") {
                    BodySection(flow: flow, index: index, sessionID: sessionID, request: true)
                }
            }
            if !exchange.responseHeaders.isEmpty { fields("Response headers", exchange.responseHeaders) }
            Section("Response") {
                BodySection(flow: flow, index: index, sessionID: sessionID, request: false)
            }
            if !exchange.messages.isEmpty {
                Section {
                    ForEach(Array(exchange.messages.enumerated()), id: \.offset) { number, message in
                        NavigationLink {
                            MessageDetailView(flow: flow, index: index, number: number, sessionID: sessionID)
                        } label: { MessageRow(message: message) }
                    }
                } header: {
                    Text("WebSocket messages (\(exchange.messages.count + exchange.messagesDropped))")
                } footer: {
                    if exchange.messagesDropped > 0 {
                        Text("\(exchange.messagesDropped) later messages were counted but not listed.")
                    }
                }
            }
            Section {
                NavigationLink {
                    RepeatView(draft: RepeatRequest.from(flow, exchange, body: exchange.requestBodyStored
                        ? sniffer.body(sessionID: sessionID, flowID: flow.id, index: index, request: true) : nil))
                } label: {
                    Label("Repeat request", systemImage: "arrow.clockwise")
                }
                NavigationLink {
                    MapRuleView(rule: localRule(), isNew: true)
                } label: {
                    Label("Map locally", systemImage: "arrow.left.arrow.right")
                }
                Button {
                    let body = exchange.requestBodyStored
                        ? sniffer.body(sessionID: sessionID, flowID: flow.id, index: index, request: true) : nil
                    UIPasteboard.general.string = Curl.command(flow, exchange, body: body)
                    copied += 1
                } label: {
                    Label("Copy as cURL", systemImage: "terminal")
                }
            }
        }
        .navigationTitle("Exchange")
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(.success, trigger: copied)
    }

    /// A Map Local rule that answers this request with the response it got.
    private func localRule() -> MapRule {
        var text = exchange.responseJson ?? ""
        if exchange.responseBodyStored,
           let data = sniffer.body(sessionID: sessionID, flowID: flow.id, index: index, request: false) {
            switch BodyPreview.of([UInt8](data), contentType: exchange.header("Content-Type", response: true)) {
            case .json(let value), .text(let value): text = value
            default: break
            }
        }
        let type = exchange.header("Content-Type", response: true)?.components(separatedBy: ";").first?
            .trimmingCharacters(in: .whitespaces) ?? "application/json"
        return MapRule(host: flow.host ?? flow.destination, path: exchange.path, status: exchange.status ?? 200,
                       contentType: type, body: text)
    }

    private func fields(_ title: LocalizedStringKey, _ fields: [HttpField]) -> some View {
        Section(title) {
            ForEach(Array(fields.enumerated()), id: \.offset) { _, field in
                VStack(alignment: .leading, spacing: 1) {
                    Text(field.name).font(.caption).foregroundStyle(.secondary)
                    Text(field.value).font(.callout).textSelection(.enabled)
                }
            }
        }
    }
}

/// One side's body: a picture, formatted JSON, text, or the bytes in hex, with a note when it was cut or not
/// kept. A start of it, with a link to the rest; `full` is that rest for a picture or bytes.
private struct BodySection: View {
    let flow: TrafficFlow
    let index: Int
    let sessionID: String?
    let request: Bool
    var full = false
    @Environment(TrafficSniffer.self) private var sniffer
    @State private var loaded: Loaded?

    private struct Loaded {
        var bytes: [UInt8]?
        var view: BodyView?
    }

    private var exchange: HttpExchange { flow.exchanges[index] }
    private var title: LocalizedStringKey { request ? "Request body" : "Response" }
    private var stored: Bool { request ? exchange.requestBodyStored : exchange.responseBodyStored }

    var body: some View {
        let cut = request ? exchange.requestCut : exchange.responseCut
        let wire = (request ? exchange.requestBodyBytes : exchange.responseBodyBytes) ?? 0
        let legacy = request ? nil : exchange.responseJson
        Group {
            if cut {
                let cap = Int64(request ? HttpAssembler.requestCap : HttpAssembler.responseCap)
                Label("Only the first \(bytes(cap)) of this body were kept.", systemImage: "exclamationmark.triangle")
                    .font(.footnote).foregroundStyle(.orange)
            }
            if !stored, let legacy {
                BodyTextPreview(text: legacy, title: title)
            } else if !stored && wire > 0 {
                Label("This body was not kept: the recording reached its limit for stored bodies.", systemImage: "exclamationmark.triangle")
                    .font(.footnote).foregroundStyle(.orange)
            } else if !stored {
                Text(request ? "This request has no body." : "This response has no body.").foregroundStyle(.secondary)
            } else if let loaded {
                if let bytes = loaded.bytes {
                    content(loaded.view ?? .empty, bytes: bytes)
                } else {
                    Text("The stored body could not be read.").foregroundStyle(.secondary)
                }
            } else {
                // The task sits on the one row that exists while loading: on the `Group` it would run per row.
                ProgressView().task { await load() }
            }
        }
    }

    private func load() async {
        guard stored, loaded == nil else { return }
        let sniffer = sniffer, sessionID = sessionID, flowID = flow.id, index = index, request = request
        let type = exchange.header("Content-Type", response: !request)
        loaded = await Task.detached(priority: .userInitiated) { () -> Loaded in
            guard let data = sniffer.body(sessionID: sessionID, flowID: flowID, index: index, request: request) else {
                return Loaded(bytes: nil, view: nil)
            }
            let bytes = [UInt8](data)
            return Loaded(bytes: bytes, view: BodyPreview.of(bytes, contentType: type))
        }.value
    }

    @ViewBuilder
    private func content(_ view: BodyView, bytes all: [UInt8]) -> some View {
        switch view {
        case .image(let data):
            if let image = UIImage(data: data) {
                Image(uiImage: image).resizable().scaledToFit()
                    .frame(maxHeight: full ? nil : 320)
                Text("Image, \(Int(image.size.width * image.scale)) × \(Int(image.size.height * image.scale)), \(bytes(Int64(data.count)))")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                Text("Image, \(bytes(Int64(data.count))). This phone cannot draw this format.")
            }
        case .json(let text), .text(let text):
            BodyTextPreview(text: text, title: title)
        case .binary(let size, let hex):
            if let encoding = exchange.header("Content-Encoding", response: !request), !encoding.isEmpty {
                Text("The body is encoded as \(encoding) and is not text.")
            } else {
                Text("Binary data, \(bytes(Int64(size))).")
            }
            let shown = full ? BodyPreview.hexDump(all, limit: 64 * 1024) : hex.split(separator: "\n").prefix(16).joined(separator: "\n")
            Text(shown).font(.caption2.monospaced()).textSelection(.enabled)
            if full && all.count > 64 * 1024 {
                Text("The first \(bytes(64 * 1024)) are shown.").font(.caption).foregroundStyle(.secondary)
            } else if !full && size > 256 {
                NavigationLink("Show all") { BodyFullView(flow: flow, index: index, sessionID: sessionID, request: request) }
            }
        case .empty:
            Text(request ? "This request has no body." : "This response has no body.").foregroundStyle(.secondary)
        }
    }
}

/// A small protocol tag in front of a request.
private struct Badge: View {
    let text: String
    var body: some View {
        Text(verbatim: text)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 4)
            .padding(.vertical, 1)
            .background(.tint.opacity(0.15), in: RoundedRectangle(cornerRadius: 4))
            .foregroundStyle(.tint)
    }
}

/// One WebSocket message: who sent it, what kind, when, how big, and the start of its text.
private struct MessageRow: View {
    let message: WsMessage
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: message.fromClient ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                .foregroundStyle(message.fromClient ? Color.accentColor : Color.green)
            VStack(alignment: .leading, spacing: 2) {
                Text(message.preview?.split(separator: "\n").first.map(String.init) ?? messageKind(message))
                    .font(.callout).lineLimit(1)
                Text("\(messageKind(message)) · \(preciseTime(message.at)) · \(bytes(message.size))")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

private func messageKind(_ message: WsMessage) -> String {
    switch message.opcode {
    case WsMessage.text: String(localized: "Text")
    case WsMessage.binary: String(localized: "Binary")
    case WsMessage.close: String(localized: "Close frame")
    case WsMessage.ping: "Ping"
    case WsMessage.pong: "Pong"
    default: message.kind
    }
}

/// One WebSocket message in full.
private struct MessageDetailView: View {
    let flow: TrafficFlow
    let index: Int
    let number: Int
    let sessionID: String?
    @Environment(TrafficSniffer.self) private var sniffer
    @State private var view: BodyView?

    private var message: WsMessage { flow.exchanges[index].messages[number] }

    var body: some View {
        List {
            Section {
                InfoRow("Direction", value: message.fromClient ? String(localized: "↑ sent") : String(localized: "↓ received"))
                InfoRow("Type", value: messageKind(message))
                InfoRow("Time", value: preciseTime(message.at))
                InfoRow("Size", value: bytes(message.size))
                if message.compressed { InfoRow("Compression", value: "permessage-deflate") }
                if message.cut {
                    Label("Only the start of this message was kept.", systemImage: "exclamationmark.triangle")
                        .font(.footnote).foregroundStyle(.orange)
                }
            }
            Section {
                switch view {
                case nil:
                    ProgressView()
                case .json(let text)?, .text(let text)?:
                    BodyTextPreview(text: text, title: "Message")
                case .binary(_, let hex)?:
                    Text(hex).font(.caption2.monospaced()).textSelection(.enabled)
                case .image(let data)?:
                    if let image = UIImage(data: data) { Image(uiImage: image).resizable().scaledToFit() }
                case .empty?:
                    Text("This message is empty.").foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Message")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let message = message, sniffer = sniffer, sessionID = sessionID, flowID = flow.id, index = index, number = number
            view = await Task.detached(priority: .userInitiated) { () -> BodyView in
                if message.stored, let data = sniffer.message(sessionID: sessionID, flowID: flowID, index: index, number: number) {
                    return BodyPreview.of([UInt8](data), contentType: message.opcode == WsMessage.binary ? "application/octet-stream" : "text/plain")
                }
                if let preview = message.preview { return .text(preview) }
                return .empty
            }.value
        }
    }
}

/// The whole of a picture or of binary data on its own screen.
private struct BodyFullView: View {
    let flow: TrafficFlow
    let index: Int
    let sessionID: String?
    let request: Bool

    var body: some View {
        List {
            Section { BodySection(flow: flow, index: index, sessionID: sessionID, request: request, full: true) }
        }
        .navigationTitle(request ? "Request body" : "Response")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Which hosts get decrypted, like SSL Proxying in Charles.
struct TLSHostsView: View {
    @Environment(TrafficSniffer.self) private var sniffer
    @State private var draft = ""
    @State private var draftOnly = true

    var body: some View {
        @Bindable var sniffer = sniffer
        let filter = sniffer.hostFilter
        let refused = TrafficSniffer.refusedHosts(sniffer.flows).filter { filter.allows($0) }
        List {
            Section {
                Picker("Mode", selection: $sniffer.hostFilter.mode) {
                    ForEach(TlsHostFilter.Mode.allCases, id: \.self) { mode in Text(modeName(mode)).tag(mode) }
                }
                .pickerStyle(.segmented)
            } footer: {
                switch filter.mode {
                case .all: Text("Every host is decrypted. A host whose app refuses the certificate goes through unread.")
                case .only: Text("Only the hosts in the first list are decrypted, except those in the second. Everything else goes through unread.")
                case .except: Text("Every host is decrypted except those in the list. They go through unread.")
                }
            }
            if filter.mode == .only {
                hostList("Decrypt these hosts", filter.only) { host in sniffer.hostFilter.only.removeAll { $0 == host } }
            }
            if filter.mode != .all {
                hostList("Leave these encrypted", filter.except) { host in sniffer.hostFilter.except.removeAll { $0 == host } }
                Section {
                    TextField("Host or pattern", text: $draft, prompt: Text(verbatim: "*.example.com"))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .onSubmit(add)
                    if filter.mode == .only {
                        Picker("List", selection: $draftOnly) {
                            Text("Decrypt").tag(true)
                            Text("Leave encrypted").tag(false)
                        }
                        .pickerStyle(.segmented)
                    }
                    Button("Add", systemImage: "plus", action: add)
                        .disabled(TlsHostFilter.normalize(draft) == nil)
                } header: {
                    Text("Add a host")
                } footer: {
                    Text("*.example.com matches example.com and all its subdomains. * stands for any characters. Changes apply to new connections.")
                }
            }
            if !refused.isEmpty {
                Section {
                    ForEach(refused, id: \.self) { host in
                        HStack {
                            Text(host).lineLimit(1)
                            Spacer()
                            Button("Leave encrypted") { sniffer.hostFilter = sniffer.hostFilter.excluding(host) }
                                .buttonStyle(.borderless)
                        }
                    }
                } header: {
                    Text("Refused the certificate")
                } footer: {
                    Text("These hosts did not trust the Spectra certificate in this recording, often because the app pins its own. Leaving them encrypted saves a failed first connection next time.")
                }
            }
        }
        .navigationTitle("Hosts to decrypt")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func add() {
        guard let clean = TlsHostFilter.normalize(draft) else { return }
        if sniffer.hostFilter.mode == .only && draftOnly {
            if !sniffer.hostFilter.only.contains(clean) { sniffer.hostFilter.only.append(clean) }
        } else {
            if !sniffer.hostFilter.except.contains(clean) { sniffer.hostFilter.except.append(clean) }
        }
        draft = ""
    }

    private func hostList(_ title: LocalizedStringKey, _ hosts: [String], remove: @escaping (String) -> Void) -> some View {
        Section(title) {
            if hosts.isEmpty { Text("The list is empty.").foregroundStyle(.secondary) }
            ForEach(hosts, id: \.self) { Text($0) }
                .onDelete { indexes in indexes.map { hosts[$0] }.forEach(remove) }
        }
    }
}

/// Sends a request again, as recorded or edited, and shows what came back.
struct RepeatView: View {
    let draft: RepeatRequest
    @State private var method = ""
    @State private var url = ""
    @State private var headers = ""
    @State private var bodyText = ""
    @State private var started = false
    @State private var sending = false
    @State private var result: RepeatResult?

    /// The recorded body as text, or nil when it is binary and goes out as recorded.
    private var textBody: String? { draft.body.flatMap { String(data: $0, encoding: .utf8) } }

    var body: some View {
        Form {
            Section {
                TextField("Method", text: $method).textInputAutocapitalization(.characters).autocorrectionDisabled()
                TextField(text: $url, axis: .vertical) { Text(verbatim: "URL") }
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                TextField("Headers, one per line", text: $headers, axis: .vertical)
                    .font(.caption.monospaced()).textInputAutocapitalization(.never).autocorrectionDisabled()
                if let data = draft.body, textBody == nil {
                    Text("The body is binary, \(bytes(Int64(data.count))), and is sent as recorded.").foregroundStyle(.secondary)
                } else {
                    TextField("Body", text: $bodyText, axis: .vertical)
                        .font(.caption.monospaced()).textInputAutocapitalization(.never).autocorrectionDisabled()
                }
                Button {
                    send()
                } label: {
                    if sending { ProgressView() } else { Label("Send", systemImage: "paperplane") }
                }
                .disabled(sending || !url.hasPrefix("http"))
            } header: {
                Text("Request")
            } footer: {
                Text("Sent from this app. While recording, it shows up in the list like any other request.")
            }
            if let result {
                Section("Response") {
                    if let error = result.error {
                        Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                    }
                    if let status = result.status { InfoRow("Status", value: "\(status)") }
                    InfoRow("Duration", value: millis(result.durationMs))
                    InfoRow("Size", value: bytes(Int64(result.body.count)))
                    ForEach(Array(result.headers.enumerated()), id: \.offset) { _, field in
                        VStack(alignment: .leading, spacing: 1) {
                            Text(field.name).font(.caption).foregroundStyle(.secondary)
                            Text(field.value).font(.callout).textSelection(.enabled)
                        }
                    }
                }
                if !result.body.isEmpty {
                    Section("Response body") {
                        if result.cut {
                            Label("Only the first \(bytes(Int64(HttpAssembler.responseCap))) of this body were kept.", systemImage: "exclamationmark.triangle")
                                .font(.footnote).foregroundStyle(.orange)
                        }
                        let type = result.headers.first { $0.name.lowercased() == "content-type" }?.value
                        switch BodyPreview.of(result.body, contentType: type) {
                        case .json(let text), .text(let text):
                            BodyTextPreview(text: text, title: "Response body")
                        case .binary(_, let hex):
                            Text(hex).font(.caption2.monospaced()).textSelection(.enabled)
                        case .image(let data):
                            if let image = UIImage(data: data) { Image(uiImage: image).resizable().scaledToFit() }
                        case .empty:
                            EmptyView()
                        }
                    }
                }
            }
        }
        .navigationTitle("Repeat request")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard !started else { return }
            started = true
            method = draft.method
            url = draft.url
            headers = draft.headerText
            bodyText = textBody ?? ""
        }
    }

    private func send() {
        sending = true
        let trimmed = method.trimmingCharacters(in: .whitespaces).uppercased()
        let request = RepeatRequest(
            method: trimmed.isEmpty ? "GET" : trimmed,
            url: url.trimmingCharacters(in: .whitespacesAndNewlines),
            headers: RepeatRequest.parseHeaders(headers),
            body: draft.body != nil && textBody == nil ? draft.body : (bodyText.isEmpty ? nil : Data(bodyText.utf8))
        )
        Task {
            result = await RequestRepeater.send(request)
            sending = false
        }
    }
}

/// How fast connections may go while recording, like Throttle Settings in Charles.
struct ThrottleView: View {
    @Environment(TrafficSniffer.self) private var sniffer
    @State private var down = ""
    @State private var up = ""
    @State private var latency = ""

    var body: some View {
        let current = sniffer.throttle
        Form {
            Section {
                Picker("Throttling", selection: Binding(
                    get: { sniffer.throttle.preset },
                    set: { name in
                        if name == ThrottleSettings.off {
                            sniffer.throttle = ThrottleSettings()
                        } else if name == ThrottleSettings.custom {
                            sniffer.throttle = custom()
                        } else {
                            let preset = ThrottleSettings.preset(name)
                            sniffer.throttle = preset
                            fill(preset)
                        }
                    }
                )) {
                    Text("Off").tag(ThrottleSettings.off)
                    ForEach(ThrottleSettings.presets, id: \.preset) { Text(presetName($0.preset)).tag($0.preset) }
                    Text("Custom").tag(ThrottleSettings.custom)
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } footer: {
                if current.enabled {
                    Text("Download \(rate(current.downKbps)), upload \(rate(current.upKbps)), delay \(millis(Int64(current.latencyMs))). Applies to the recorded connections right away.")
                } else {
                    Text("Connections go at full speed.")
                }
            }
            Section {
                TextField("Download, kbit/s", text: $down).keyboardType(.numberPad)
                TextField("Upload, kbit/s", text: $up).keyboardType(.numberPad)
                TextField("Delay, ms", text: $latency).keyboardType(.numberPad)
                Button("Apply") { sniffer.throttle = custom() }
            } header: {
                Text("Custom")
            } footer: {
                Text("Empty or 0 leaves that direction unlimited.")
            }
        }
        .navigationTitle("Throttling")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { fill(sniffer.throttle) }
    }

    private func fill(_ settings: ThrottleSettings) {
        down = settings.downKbps > 0 ? "\(settings.downKbps)" : ""
        up = settings.upKbps > 0 ? "\(settings.upKbps)" : ""
        latency = settings.latencyMs > 0 ? "\(settings.latencyMs)" : ""
    }

    private func custom() -> ThrottleSettings {
        ThrottleSettings(preset: ThrottleSettings.custom, downKbps: Int(down) ?? 0, upKbps: Int(up) ?? 0,
                         latencyMs: min(Int(latency) ?? 0, 10_000))
    }
}

/// Map Local rules: requests they match are answered by the phone.
struct MapLocalView: View {
    @Environment(TrafficSniffer.self) private var sniffer

    var body: some View {
        @Bindable var sniffer = sniffer
        List {
            Section {
                Text("A matching request over decrypted HTTPS gets this answer from the phone and never reaches the server. Hosts with rules are kept on HTTP/1.1. Changes apply to new connections.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if sniffer.mapRules.isEmpty {
                Text("Open a request and choose Map locally, or add a rule here.").foregroundStyle(.secondary)
            }
            ForEach($sniffer.mapRules) { $rule in
                NavigationLink { MapRuleView(rule: rule, isNew: false) } label: {
                    Toggle(isOn: $rule.enabled) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: rule.host + (rule.path.isEmpty ? "/*" : rule.path)).lineLimit(1)
                            Text(verbatim: "\(rule.status) · \(rule.contentType.isEmpty ? "—" : rule.contentType) · \(bytes(Int64(rule.body.utf8.count)))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .onDelete { sniffer.mapRules.remove(atOffsets: $0) }
        }
        .navigationTitle("Map Local")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink { MapRuleView(rule: MapRule(host: ""), isNew: true) } label: {
                    Label("Add rule", systemImage: "plus")
                }
            }
        }
    }
}

/// One Map Local rule, new or existing.
struct MapRuleView: View {
    let rule: MapRule
    let isNew: Bool
    @Environment(TrafficSniffer.self) private var sniffer
    @Environment(\.dismiss) private var dismiss
    @State private var host = ""
    @State private var path = ""
    @State private var status = ""
    @State private var type = ""
    @State private var bodyText = ""
    @State private var started = false

    private var valid: Bool { TlsHostFilter.normalize(host) != nil && (100...599).contains(Int(status) ?? 0) }

    var body: some View {
        Form {
            Section {
                TextField("Host or pattern", text: $host, prompt: Text(verbatim: "api.example.com"))
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                TextField("Path", text: $path, prompt: Text(verbatim: "/api/user*"))
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
            } header: {
                Text("Requests")
            } footer: {
                Text("Path: * stands for any characters; empty matches every path. The query is compared too when the path has one.")
            }
            Section("Answer") {
                TextField("Status", text: $status).keyboardType(.numberPad)
                TextField(text: $type) { Text(verbatim: "Content-Type") }
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("Body", text: $bodyText, axis: .vertical)
                    .font(.caption.monospaced()).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .lineLimit(6...)
            }
            Section {
                Button("Save", action: save).disabled(!valid)
                if !isNew {
                    Button("Delete", role: .destructive) {
                        sniffer.mapRules.removeAll { $0.id == rule.id }
                        dismiss()
                    }
                }
            }
        }
        .navigationTitle(isNew ? "New rule" : "Rule")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard !started else { return }
            started = true
            host = rule.host
            path = rule.path
            status = "\(rule.status)"
            type = rule.contentType
            bodyText = rule.body
        }
    }

    private func save() {
        guard let clean = TlsHostFilter.normalize(host) else { return }
        var saved = rule
        saved.host = clean
        saved.path = path.trimmingCharacters(in: .whitespaces)
        saved.status = Int(status) ?? 200
        saved.contentType = type.trimmingCharacters(in: .whitespaces)
        saved.body = bodyText
        if let index = sniffer.mapRules.firstIndex(where: { $0.id == saved.id }) {
            sniffer.mapRules[index] = saved
        } else {
            sniffer.mapRules.append(saved)
        }
        dismiss()
    }
}

private func presetName(_ name: String) -> String {
    switch name {
    case "edge": "EDGE"
    case "3g": "3G"
    case "fast3g": String(localized: "Fast 3G")
    case "4g": "4G"
    default: name
    }
}

private func rate(_ kbps: Int) -> String {
    if kbps <= 0 { return String(localized: "unlimited") }
    if kbps >= 1000 { return String(localized: "\(Double(kbps) / 1000, specifier: "%.1f") Mbit/s") }
    return String(localized: "\(kbps) kbit/s")
}

private func throttleSummary(_ settings: ThrottleSettings) -> String {
    switch settings.preset {
    case ThrottleSettings.off: String(localized: "Off")
    case ThrottleSettings.custom: String(localized: "Custom: \(rate(settings.downKbps)) down, \(rate(settings.upKbps)) up")
    default: presetName(settings.preset)
    }
}

@MainActor private func mapLocalSummary(_ sniffer: TrafficSniffer) -> String {
    let on = sniffer.mapRules.filter(\.enabled).count
    if sniffer.mapRules.isEmpty { return String(localized: "Answer chosen requests from the phone") }
    if !sniffer.decryptTLS { return String(localized: "Rules on: \(on). Works only with Decrypt TLS on.") }
    return String(localized: "Rules on: \(on)")
}

/// Every saved recording, newest first, with its time so two from the same day can be told apart.
struct SavedSessionsView: View {
    @Environment(TrafficSniffer.self) private var sniffer

    var body: some View {
        List {
            if sniffer.saved.isEmpty { Text("No saved sessions").foregroundStyle(.secondary) }
            ForEach(sniffer.saved) { session in
                NavigationLink { SavedSessionView(session: session) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(Date(timeIntervalSince1970: Double(session.stoppedAt) / 1000),
                                 format: .dateTime.day().month().hour().minute())
                            if session.id == sniffer.currentSessionID {
                                Text("This recording").font(.caption).foregroundStyle(.green)
                            }
                        }
                        Text("Requests: \(session.flows.reduce(0) { $0 + $1.exchanges.count }) · connections: \(session.flows.count)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .onDelete { indexes in indexes.map { sniffer.saved[$0] }.forEach(sniffer.delete) }
        }
        .navigationTitle("Saved sessions")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { if !sniffer.saved.isEmpty { EditButton() } }
    }
}

struct SavedSessionView: View {
    let session: TrafficSession
    @Environment(CaptureShelf.self) private var captures
    @State private var exportItem: ShareItem?
    var body: some View {
        TrafficList(flows: session.flows, sessionID: session.id)
        .navigationTitle("Session")
        .navigationBarTitleDisplayMode(.inline)
        .summaryButton(isShown: !session.flows.isEmpty,
                       subject: { .traffic },
                       prepare: {
                          let stopped = Date(timeIntervalSince1970: Double(session.stoppedAt) / 1000)
                          captures.keep(traffic: session.flows,
                                        name: "saved session of " + stopped.formatted(.dateTime.day().month().hour().minute()))
                      })
        .shareSheet(item: $exportItem)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                HarExportButton(flows: session.flows, sessionID: session.id, item: $exportItem)
            }
        }
    }
}

private func bytes(_ count: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: count, countStyle: .binary)
}

private func modeName(_ mode: TlsHostFilter.Mode) -> String {
    switch mode {
    case .all: String(localized: "All hosts")
    case .only: String(localized: "Only these")
    case .except: String(localized: "All except these")
    }
}

private func hostFilterSummary(_ filter: TlsHostFilter) -> String {
    switch filter.mode {
    case .all: String(localized: "All hosts")
    case .only: String(localized: "Only listed hosts (\(filter.only.count))")
    case .except: String(localized: "All except listed hosts (\(filter.except.count))")
    }
}

/// Status, time to the end of the response and size, for a request's row.
private func exchangeLine(_ exchange: HttpExchange) -> String? {
    var parts: [String] = []
    if let status = exchange.status { parts.append("\(status)") }
    if let duration = exchange.durationMs { parts.append(millis(duration)) }
    let size = (exchange.requestSize ?? 0) + (exchange.responseSize ?? 0)
    if size > 0 { parts.append(bytes(size)) }
    return parts.isEmpty ? nil : parts.joined(separator: " · ")
}

private func millis(_ ms: Int64) -> String {
    ms < 1000 ? String(localized: "\(ms) ms") : String(localized: "\(Double(ms) / 1000, specifier: "%.2f") s")
}

private func preciseTime(_ millis: Int64) -> String {
    let format = DateFormatter()
    format.dateFormat = "HH:mm:ss.SSS"
    return format.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
}

private func sizeLine(_ total: Int64, _ headers: Int64?, _ body: Int64?) -> String {
    guard let headers else { return bytes(total) }
    return String(localized: "\(bytes(total)) (headers \(bytes(headers)), body \(bytes(body ?? 0)))")
}
