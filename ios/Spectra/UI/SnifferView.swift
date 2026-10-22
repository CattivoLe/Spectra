import SwiftUI

/// The traffic sniffer screens, reached from the LAN tab. Mirrors the Android
/// `ui/SnifferScreens.kt`: live flow list, flow detail, HTTP exchange, saved
/// sessions. Capture runs in the packet-tunnel extension; this shows what it
/// publishes into the shared App Group.
struct SnifferView: View {
    @Environment(TrafficSniffer.self) private var sniffer
    @State private var exportItem: ShareItem?

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
                    Button("Save session", action: sniffer.saveCurrent)
                        .disabled(sniffer.flows.isEmpty)
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
                Section("\(sniffer.flows.count) connections") {
                    ForEach(sniffer.flows) { flow in
                        NavigationLink { FlowDetailView(flow: flow) } label: { FlowRow(flow: flow) }
                    }
                }
            }

            if !sniffer.saved.isEmpty {
                Section("Saved") {
                    ForEach(sniffer.saved) { session in
                        NavigationLink { SavedSessionView(session: session) } label: {
                            VStack(alignment: .leading) {
                                Text(Date(timeIntervalSince1970: Double(session.stoppedAt) / 1000), style: .date)
                                Text("\(session.flows.count) connections").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete { indexes in indexes.map { sniffer.saved[$0] }.forEach(sniffer.delete) }
                }
            }
        }
        .navigationTitle("Traffic")
        .shareSheet(item: $exportItem)
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

struct SavedSessionView: View {
    let session: TrafficSession
    var body: some View {
        List(session.flows) { flow in
            NavigationLink { FlowDetailView(flow: flow) } label: { FlowRow(flow: flow) }
        }
        .navigationTitle("Session")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private func bytes(_ count: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: count, countStyle: .binary)
}
