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
    @State private var kindFilter: TrafficKind?
    @State private var search = ""
    @State private var saves = 0

    /// Flows after the protocol filter and the host/request search.
    private var filtered: [TrafficFlow] {
        sniffer.flows.filter { flow in
            (kindFilter == nil || flow.kind == kindFilter)
                && (search.isEmpty
                    || flow.title.localizedCaseInsensitiveContains(search)
                    || (flow.request ?? "").localizedCaseInsensitiveContains(search))
        }
    }

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
                            Text("Install & trust Spectra's certificate to read HTTPS.")
                                .font(.footnote).foregroundStyle(.secondary)
                            Text(fingerprint).font(.caption2.monospaced()).foregroundStyle(.secondary)
                            Button("Export certificate") { exportItem = writeCertificate() }
                        }
                    }
                }
            } footer: {
                if sniffer.decryptTLS {
                    Text("After installing, enable full trust in Settings → General → VPN & Device Management → Certificate Trust Settings.")
                }
            }

            if !sniffer.flows.isEmpty {
                Section(filtered.count == sniffer.flows.count
                        ? "\(sniffer.flows.count) connections"
                        : "\(filtered.count) of \(sniffer.flows.count) connections") {
                    if filtered.isEmpty {
                        Text("Nothing matches the filter.").foregroundStyle(.secondary).font(.footnote)
                    }
                    ForEach(filtered) { flow in
                        NavigationLink { FlowDetailView(flow: flow, sessionID: nil) } label: { FlowRow(flow: flow) }
                    }
                }
            }

        }
        .navigationTitle("Traffic")
        .sensoryFeedback(.success, trigger: saves)
        .searchable(text: $search, prompt: "Host or request")
        .summaryButton(isShown: !sniffer.flows.isEmpty,
                       subject: { .traffic },
                       prepare: { captures.keep(traffic: sniffer.flows, name: sniffer.running ? "recording in progress" : "current recording") })
        .shareSheet(item: $exportItem)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("Protocol", selection: $kindFilter) {
                        Text("All").tag(TrafficKind?.none)
                        ForEach([TrafficKind.http, .tls, .dns, .tcp, .udp], id: \.self) { kind in
                            Text(kind.rawValue.uppercased()).tag(TrafficKind?.some(kind))
                        }
                    }
                } label: {
                    Label("Filter", systemImage: kindFilter == nil
                          ? "line.3.horizontal.decrease.circle"
                          : "line.3.horizontal.decrease.circle.fill")
                }
            }
            if !sniffer.flows.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    HarExportButton(flows: sniffer.flows, sessionID: nil, item: $exportItem)
                }
            }
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
                Text(flow.exchanges.count > 1 ? "\(line) · \(flow.exchanges.count) requests" : line)
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
                                Text(exchange.summary).font(.callout).lineLimit(1)
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
                if let status = exchange.status { InfoRow("Status", value: "\(status)") } }
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
                    BodySection(flow: flow, index: index, sessionID: sessionID, request: true, full: false)
                }
            }
            if !exchange.responseHeaders.isEmpty { fields("Response headers", exchange.responseHeaders) }
            Section("Response") {
                BodySection(flow: flow, index: index, sessionID: sessionID, request: false, full: false)
            }
            Section {
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
/// kept. `full` shows all of it; otherwise a start, with a link to the rest.
private struct BodySection: View {
    let flow: TrafficFlow
    let index: Int
    let sessionID: String?
    let request: Bool
    let full: Bool
    @Environment(TrafficSniffer.self) private var sniffer
    @State private var loaded: Loaded?

    private struct Loaded {
        var bytes: [UInt8]?
        var view: BodyView?
    }

    private var exchange: HttpExchange { flow.exchanges[index] }
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
                MonoText(text: legacy, full: full) { BodyFullView(flow: flow, index: index, sessionID: sessionID, request: request) }
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
            MonoText(text: text, full: full) { BodyFullView(flow: flow, index: index, sessionID: sessionID, request: request) }
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

/// Monospaced text: a start of it, or as much as a screen can carry.
private struct MonoText<Destination: View>: View {
    let text: String
    let full: Bool
    @ViewBuilder let destination: () -> Destination
    @State private var copied = 0

    private static var previewLimit: Int { 3_000 }
    private static var fullLimit: Int { 400_000 }

    var body: some View {
        let limit = full ? Self.fullLimit : Self.previewLimit
        Text(text.count > limit ? String(text.prefix(limit)) : text)
            .font(.caption.monospaced())
            .textSelection(.enabled)
        if !full && text.count > limit {
            NavigationLink("Show all", destination: destination)
        }
        if full && text.count > limit {
            Text("The first \(String(localized: "\(limit) characters")) are shown.").font(.caption).foregroundStyle(.secondary)
        }
        Button("Copy") {
            UIPasteboard.general.string = text
            copied += 1
        }
        .sensoryFeedback(.success, trigger: copied)
    }
}

/// The whole of one body on its own screen.
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
                        Text("\(session.flows.count) connections").font(.caption).foregroundStyle(.secondary)
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
        List(session.flows) { flow in
            NavigationLink { FlowDetailView(flow: flow, sessionID: session.id) } label: { FlowRow(flow: flow) }
        }
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
