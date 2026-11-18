import SwiftUI

/// The traffic sniffer screens, reached from the LAN tab. Mirrors the Android
/// `ui/SnifferScreens.kt`: live flow list, flow detail, HTTP exchange, saved
/// sessions. Capture runs in the packet-tunnel extension; this shows what it
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
                        NavigationLink { FlowDetailView(flow: flow) } label: { FlowRow(flow: flow) }
                    }
                }
            }

        }
        .navigationTitle("Traffic")
        .sensoryFeedback(.success, trigger: saves)
        .searchable(text: $search, prompt: "Host or request")
        .askAssistant(isShown: !sniffer.flows.isEmpty,
                      question: { String(localized: "Analyze the recorded traffic of this phone: which servers and apps did it talk to, and is anything unusual?") },
                      prepare: { captures.keep(traffic: sniffer.flows, name: sniffer.running ? "recording in progress" : "current recording") })
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
            Text("↑\(bytes(flow.sent)) ↓\(bytes(flow.received))").font(.caption2).foregroundStyle(.tertiary)
        }
    }
}

struct FlowDetailView: View {
    let flow: TrafficFlow
    var body: some View {
        List {
            Section {
                InfoRow("Host", value: flow.title)
                InfoRow("Destination", value: "\(flow.destination):\(flow.destinationPort)")
                InfoRow("Protocol", value: flow.proto)
                InfoRow("Sent", value: bytes(flow.sent))
                InfoRow("Received", value: bytes(flow.received))
                if flow.refused { InfoRow("TLS", value: "Certificate refused") }
            }
            if !flow.exchanges.isEmpty {
                Section("HTTP") {
                    ForEach(Array(flow.exchanges.enumerated()), id: \.offset) { _, exchange in
                        NavigationLink { ExchangeDetailView(exchange: exchange) } label: {
                            VStack(alignment: .leading) {
                                Text(exchange.summary).font(.callout).lineLimit(1)
                                if let status = exchange.status {
                                    Text("\(status)").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(flow.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct ExchangeDetailView: View {
    let exchange: HttpExchange
    var body: some View {
        List {
            Section { InfoRow("Request", value: exchange.summary)
                if let status = exchange.status { InfoRow("Status", value: "\(status)") } }
            if !exchange.query.isEmpty { fields("Query", exchange.query) }
            if !exchange.parameters.isEmpty { fields("Parameters", exchange.parameters) }
            if !exchange.requestHeaders.isEmpty { fields("Request headers", exchange.requestHeaders) }
            if !exchange.responseHeaders.isEmpty { fields("Response headers", exchange.responseHeaders) }
            if let json = exchange.responseJson {
                Section(exchange.responseCut ? "Body (truncated)" : "Body") {
                    Text(json).font(.caption.monospaced()).textSelection(.enabled)
                }
            }
        }
        .navigationTitle("Exchange")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func fields(_ title: String, _ fields: [HttpField]) -> some View {
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
    var body: some View {
        List(session.flows) { flow in
            NavigationLink { FlowDetailView(flow: flow) } label: { FlowRow(flow: flow) }
        }
        .navigationTitle("Session")
        .navigationBarTitleDisplayMode(.inline)
        .askAssistant(isShown: !session.flows.isEmpty,
                      question: { String(localized: "Analyze the recorded traffic of this phone: which servers and apps did it talk to, and is anything unusual?") },
                      prepare: {
                          let stopped = Date(timeIntervalSince1970: Double(session.stoppedAt) / 1000)
                          captures.keep(traffic: session.flows,
                                        name: "saved session of " + stopped.formatted(.dateTime.day().month().hour().minute()))
                      })
    }
}

private func bytes(_ count: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: count, countStyle: .binary)
}
