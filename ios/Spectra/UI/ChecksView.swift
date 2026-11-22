import SwiftUI

/// The tools that check something on demand: a link, and the connection.
struct ChecksView: View {
    @Environment(LinkProbe.self) private var probe
    @Environment(SpeedTester.self) private var tester
    @AppStorage("probeViaVpn") private var viaVpn = false
    @State private var showConnection = false
    @State private var linkText = ""
    @State private var inspection: LinkInspection?
    @State private var noLink = false
    @State private var scanning = false
    /// A code read by the scanner, handled once its sheet is gone: an alert
    /// presented while the sheet is still closing is dropped by iOS.
    @State private var scanned: String?
    /// A scanned Wi-Fi join code, explained instead of inspected as a link.
    @State private var wifiCode: (ssid: String, security: String?, hidden: Bool)?
    @FocusState private var editingLink: Bool

    /// The last full test, or the speed kept on the current connection check.
    private var speedSummary: String? {
        if tester.running { return String(localized: "Measuring…") }
        let down = tester.result?.downBps ?? probe.report?.speed?.downBps
        let up = tester.result?.upBps ?? probe.report?.speed?.upBps
        let parts = [down.map { "↓ " + rate($0) }, up.map { "↑ " + rate($0) }].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: "  ")
    }

    var body: some View {
        Group {
            List {
                Section {
                    // Multi-line, so Return adds a line instead of closing the
                    // keyboard: Done in the navigation bar and scrolling close it.
                    HStack(alignment: .firstTextBaseline) {
                        TextField("Paste a link or a whole message", text: $linkText, axis: .vertical)
                            .lineLimit(1...5)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                            .focused($editingLink)
                            .onChange(of: linkText) { _, _ in noLink = false }
                        if !linkText.isEmpty {
                            Button("Clear", systemImage: "xmark.circle.fill") {
                                linkText = ""
                                noLink = false
                            }
                            .labelStyle(.iconOnly)
                            .foregroundStyle(.secondary)
                            .buttonStyle(.borderless)
                        }
                    }
                    HStack {
                        PasteButton(payloadType: String.self) { strings in
                            Task { @MainActor in
                                linkText = strings.first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                            }
                        }
                        .labelStyle(.titleAndIcon)
                        .buttonBorderShape(.capsule)
                        Button {
                            editingLink = false
                            scanning = true
                        } label: {
                            // Icon only: "Scan QR" wraps onto three lines in Russian.
                            Image(systemName: "qrcode.viewfinder")
                                .font(.body.weight(.medium))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(.fill.tertiary, in: .capsule)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(Text("Scan QR code"))
                        Spacer()
                        // Borderless: in a List row, any other button style fires
                        // together with the Paste button next to it.
                        let empty = linkText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        Button(action: checkLink) {
                            Text("Check")
                                .fontWeight(.semibold)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 8)
                                .foregroundStyle(empty ? AnyShapeStyle(.tertiary) : AnyShapeStyle(.white))
                                .background(empty ? AnyShapeStyle(.fill.tertiary) : AnyShapeStyle(.tint), in: .capsule)
                        }
                        .buttonStyle(.borderless)
                        .disabled(empty)
                    }
                    if noLink {
                        Text("No link found in that text.")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                } header: {
                    Text("Link")
                } footer: {
                    Text("Checked on this iPhone: the link is not sent anywhere. A QR code is read, never opened.")
                }

                Section {
                    // There is nothing to open until the internet has been checked once.
                    if probe.report != nil || probe.running {
                        NavigationLink {
                            LinkView()
                        } label: {
                            HStack(spacing: 12) {
                                TrafficLight(access: probe.report?.access?.verdict, lamp: 9)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(probe.report?.access?.verdict.title ?? connectionTitle(probe))
                                        .font(.body.weight(.medium))
                                    Text(connectionDetail(probe))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                }
                            }
                        }
                    }
                    VpnToggle(viaVpn: $viaVpn)
                        .disabled(probe.running)
                    if probe.running {
                        ProgressView(value: probe.fraction)
                            .tint(.teal)
                    } else {
                        Button {
                            probe.check()
                            showConnection = true
                        } label: {
                            Label(probe.report == nil ? "Check connection" : "Check again", systemImage: "gauge.with.dots.needle.67percent")
                        }
                    }
                } header: {
                    Text("Connection")
                } footer: {
                    Text("The check contacts Cloudflare, ipwho.is and check-host.net. They see your public IP address.")
                }

                ResourcesSection()

                Section {
                    NavigationLink {
                        TraceRouteView()
                    } label: {
                        Label("Traceroute", systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                    }
                } footer: {
                    Text("The path from this phone to any site or address, hop by hop, on a map.")
                }

                Section {
                    NavigationLink {
                        SpeedTestView()
                    } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Speed test")
                                if let summary = speedSummary {
                                    Text(summary)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .monospacedDigit()
                                }
                            }
                        } icon: {
                            Image(systemName: "speedometer")
                        }
                    }
                } header: {
                    Text("Speed")
                } footer: {
                    Text("Eight seconds down and eight up, to Cloudflare or a Measurement Lab server you pick, with latency under load.")
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .toolbar {
                if editingLink {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Done") { editingLink = false }
                            .fontWeight(.semibold)
                    }
                }
            }
            .navigationTitle("Checks")
            .summaryButton(placement: .topBarLeading, subject: { .internet })
            .navigationDestination(isPresented: $showConnection) {
                LinkView()
            }
            .navigationDestination(item: $inspection) { inspection in
                LinkInspectionView(inspection: inspection)
            }
            .sheet(isPresented: $scanning, onDismiss: {
                guard let payload = scanned else { return }
                scanned = nil
                handleScanned(payload)
            }) {
                QRScanView { payload in
                    scanned = payload
                }
            }
            .alert(
                "Wi-Fi network code",
                isPresented: Binding(get: { wifiCode != nil }, set: { if !$0 { wifiCode = nil } })
            ) {
                Button("OK", role: .cancel) {}
            } message: {
                if let code = wifiCode {
                    Text(wifiDescription(code))
                }
            }
        }
    }

    /// A scanned code: links go to the inspector, Wi-Fi codes are explained,
    /// anything else lands in the field so the person can see it.
    private func handleScanned(_ payload: String) {
        switch QRPayload.classify(payload) {
        case .link(let text):
            linkText = text
            checkLink()
        case .wifi(let ssid, let security, let hidden):
            wifiCode = (ssid, security, hidden)
        case .text(let text):
            linkText = text
            noLink = true
        }
    }

    private func wifiDescription(_ code: (ssid: String, security: String?, hidden: Bool)) -> String {
        var lines: [String] = []
        lines.append(String(localized: "This code joins a Wi-Fi network named “\(code.ssid)”."))
        if let security = code.security {
            lines.append(String(localized: "Security: \(security)."))
        } else {
            lines.append(String(localized: "The network has no password: traffic on it can be read by anyone nearby."))
        }
        if code.hidden {
            lines.append(String(localized: "The network is hidden, which is unusual for a public one."))
        }
        lines.append(String(localized: "Spectra did not join it. Join only networks you expected to find here."))
        return lines.joined(separator: " ")
    }

    private func checkLink() {
        editingLink = false
        guard let found = LinkInspector.inspect(linkText) else {
            noLink = !linkText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            return
        }
        noLink = false
        inspection = found
    }
}
