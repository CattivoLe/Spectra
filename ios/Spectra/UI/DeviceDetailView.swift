import SwiftUI
import UIKit

struct DeviceDetailView: View {
    private var lookup: String?
    private var snapshot: LANDevice?

    init(ip: String) {
        lookup = ip
    }

    /// A saved host, shown with the same card as a live one.
    init(device: LANDevice) {
        snapshot = device
    }

    @Environment(LANScanner.self) private var scanner
    @Environment(KnownHosts.self) private var knownHosts
    @State private var editingLabel = false
    @Environment(\.openURL) private var openURL
    /// A stream address no app on this iPhone would open.
    @State private var unopenedStream: String?

    var body: some View {
        if let device = snapshot ?? lookup.flatMap({ scanner.devices[$0] }) {
            content(device)
        } else {
            ContentUnavailableView("Device cleared", systemImage: "desktopcomputer.trianglebadge.exclamationmark")
        }
    }

    private func content(_ device: LANDevice) -> some View {
        List {
            // Header
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 14) {
                        Image(systemName: device.deviceIcon)
                            .font(.system(size: 34))
                            .foregroundStyle(device.isLocalDevice ? .teal : device.isGateway ? .orange : .secondary)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(device.displayName)
                                .font(.title2.weight(.semibold))
                                .fixedSize(horizontal: false, vertical: true)
                            HStack(spacing: 6) {
                                if device.isLocalDevice {
                                    badge("This device", color: .teal)
                                }
                                if device.isGateway {
                                    badge("Gateway", color: .orange)
                                }
                                if let notable = device.notable {
                                    badge(notable == .camera ? "Camera" : "IoT", color: notable == .camera ? .red : .orange)
                                }
                                if device.isNew {
                                    badge("New", color: .purple)
                                }
                                if device.trust == .trusted {
                                    badge("Trusted", color: .green)
                                } else if device.trust == .suspicious {
                                    badge("Suspicious", color: .red)
                                }
                                if !device.identity.label.isEmpty {
                                    Text(device.identity.label)
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                } else if let mfr = device.manufacturer {
                                    Text(mfr)
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    if let note = device.note {
                        Text(note)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.vertical, 6)
            }

            if device.portsScanned {
                securitySection(device)
            }

            if device.isGateway, device.profile.portForwarding != nil || snapshot == nil {
                forwardingSection(device)
            }

            // Network info
            Section("Network") {
                InfoRow("IP address") {
                    Text(device.ipAddress)
                        .monospaced()
                        .textSelection(.enabled)
                }
                if let mac = device.macAddress {
                    InfoRow("MAC address") {
                        Text(mac.description)
                            .monospaced()
                            .textSelection(.enabled)
                    }
                    InfoRow("OUI", value: mac.oui)
                    if mac.isRandomized {
                        InfoRow("Address type", value: String(localized: "Randomised"))
                    } else if let mfr = mac.manufacturer {
                        InfoRow("Address type", value: String(localized: "Maker-assigned (\(mfr))"))
                    }
                }
                if let maker = device.identity.manufacturer {
                    InfoRow("Manufacturer", value: maker)
                }
                if let system = device.identity.operatingSystem {
                    InfoRow("System", value: system)
                }
                if let model = device.identity.model {
                    InfoRow("Model", value: model)
                }
                if !device.hostnames.isEmpty {
                    ForEach(device.hostnames, id: \.self) { name in
                        InfoRow("Hostname") {
                            Text(name)
                                .textSelection(.enabled)
                        }
                    }
                }
            }

            if !device.profile.isEmpty {
                Section("Services") {
                    if let upnp = device.profile.upnp {
                        ServiceBlock(title: Text(verbatim: "UPnP"), lines: [
                            upnp.friendlyName,
                            [upnp.manufacturer, upnp.model].compactMap { $0 }.joined(separator: " "),
                            upnp.deviceType,
                        ], detail: upnp.server)
                    }
                    ForEach(Array(device.services.enumerated()), id: \.offset) { _, service in
                        let parts = serviceParts(service)
                        ServiceBlock(title: Text(service.kind), lines: parts.lines, identifier: parts.identifier)
                    }
                    if let windows = device.profile.windows {
                        let name = windows.computerName?.trimmingCharacters(in: .whitespaces)
                        let domain = windows.domain?.trimmingCharacters(in: .whitespaces)
                        ServiceBlock(title: Text("Windows sharing"), lines: [
                            name.flatMap { $0.isEmpty ? nil : String(localized: "Network name: \($0)") },
                            domain.flatMap { $0.isEmpty ? nil : String(localized: "Domain: \($0)") },
                            windows.shares.isEmpty
                                ? (windows.guestSession ? String(localized: "Guest connection found no shared folders.") : nil)
                                : String(localized: "Shares: \(windows.shares.formatted(.list(type: .and)))"),
                        ])
                    }
                    ForEach(device.profile.banners) { banner in
                        ServiceBlock(title: Text(verbatim: "\(banner.secure ? "HTTPS" : "HTTP") \(banner.port)").monospacedDigit(),
                                     lines: [banner.summary])
                    }
                    ForEach(device.profile.rtsp) { service in
                        ServiceBlock(title: Text(verbatim: "RTSP \(service.port)").monospacedDigit(), lines: [service.summary])
                    }
                }
            }

            if device.portsScanned {
                Section("Ports") {
                    if device.openPorts.isEmpty {
                        Label("No open ports found", systemImage: "lock.shield")
                            .foregroundStyle(.secondary)
                            .font(.subheadline)
                    } else {
                        ForEach(device.openPorts) { port in
                            if let url = webAddress(port.number, on: device) {
                                Link(destination: url) { portRow(port, web: true) }
                                    .buttonStyle(.plain)
                                    .accessibilityHint(Text("Opens \(url.absoluteString) in the browser"))
                            } else if let stream = device.streamAddress(port.number), let url = URL(string: stream) {
                                Button {
                                    // iOS has no player for rtsp:// or rtmp:// of its own.
                                    openURL(url) { accepted in
                                        if !accepted { unopenedStream = stream }
                                    }
                                } label: {
                                    portRow(port, web: false, stream: true)
                                }
                                .buttonStyle(.plain)
                                .contextMenu {
                                    Button("Copy stream address", systemImage: "doc.on.doc") {
                                        UIPasteboard.general.string = stream
                                    }
                                }
                                .accessibilityHint(Text("Opens \(stream) in a video player"))
                            } else {
                                portRow(port, web: false)
                            }
                        }
                    }
                }
            }

            if snapshot == nil, let ip = lookup {
                Section {
                    if let fraction = device.portScanFraction {
                        ProgressView(value: fraction) {
                            Text("Scanning ports…")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        .tint(.teal)
                    } else {
                        Button {
                            scanner.scanPorts(for: ip)
                        } label: {
                            Label(device.portsScanned ? "Rescan ports" : "Scan ports", systemImage: "magnifyingglass")
                                .font(.body.weight(.medium))
                        }
                        .disabled(scanner.isScanning)
                        Button {
                            scanner.scanPorts(for: ip, scope: .all)
                        } label: {
                            Label("Scan all ports", systemImage: "checklist")
                                .font(.body.weight(.medium))
                        }
                        .disabled(scanner.isScanning)
                    }
                } header: {
                    if !device.portsScanned { Text("Ports") }
                } footer: {
                    Text("Checks \(DiscoveredPort.commonPorts.count) common TCP ports, or every TCP port from 1 to 65535.")
                }
                .listRowBackground(Color.accentColor.opacity(0.14))
            }

            // Timestamps
            Section("Observed") {
                InfoRow("First seen") { Text(device.firstSeen.formatted(date: .abbreviated, time: .shortened)) }
                InfoRow("Last seen") { Text(device.lastSeen, style: .relative) }
                if device.isNew {
                    InfoRow("On this network") { Text("New").foregroundStyle(.purple) }
                } else if snapshot == nil, !device.isLocalDevice, !device.isGateway,
                          let key = scanner.networkKey, let since = knownHosts.firstSeen(device, network: key) {
                    InfoRow("On this network") { Text("Known since \(since.formatted(date: .abbreviated, time: .omitted))") }
                }
            }
        }
        .navigationTitle(device.ipAddress)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Label", systemImage: device.trust == .unknown && device.customName == nil ? "tag" : "tag.fill") {
                    editingLabel = true
                }
            }
        }
        .alert(
            "No video player",
            isPresented: Binding(get: { unopenedStream != nil }, set: { if !$0 { unopenedStream = nil } })
        ) {
            Button("Copy address") { UIPasteboard.general.string = unopenedStream }
            Button("OK", role: .cancel) {}
        } message: {
            Text("No app on this iPhone opens \(unopenedStream ?? ""). Install a player such as VLC, then tap the port again.")
        }
        .sheet(isPresented: $editingLabel) {
            DeviceLabelEditor(device: device)
        }
        .sensoryFeedback(trigger: device.portScanFraction == nil) { wasIdle, isIdle in
            !wasIdle && isIdle ? .success : nil
        }
    }

    private func portRow(_ port: DiscoveredPort, web: Bool, stream: Bool = false) -> some View {
        let risk = PortRisk.level(forPort: port.number)
        return HStack(spacing: 12) {
            Text(port.number, format: .number.grouping(.never))
                .font(.body.monospacedDigit().weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
            Text(port.service)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Spacer(minLength: 8)
            if web {
                Image(systemName: "safari")
                    .foregroundStyle(.tint)
            } else if stream {
                Image(systemName: "play.circle")
                    .foregroundStyle(.tint)
            }
            Image(systemName: "circle.fill")
                .font(.system(size: 8))
                .foregroundStyle(risk.map { $0 >= .low ? $0.color : Color.green } ?? .green)
                .accessibilityLabel(Text(risk?.label ?? String(localized: "Open")))
        }
        .contentShape(Rectangle())
    }

    /// The router's UPnP forwarding table: which internet ports lead to which host.
    private func forwardingSection(_ device: LANDevice) -> some View {
        Section {
            if let table = device.profile.portForwarding {
                if !table.supported {
                    Label {
                        Text("The router does not answer UPnP requests, so its forwardings cannot be listed from here.")
                            .foregroundStyle(.secondary)
                    } icon: {
                        Image(systemName: "questionmark.circle")
                            .foregroundStyle(.secondary)
                    }
                    .font(.subheadline)
                } else if table.byHost.isEmpty {
                    Label {
                        Text("No ports are forwarded from the internet.")
                            .foregroundStyle(.secondary)
                    } icon: {
                        Image(systemName: "checkmark.shield.fill")
                            .foregroundStyle(.green)
                    }
                } else {
                    ForEach(table.byHost, id: \.host) { group in
                        ForEach(group.mappings) { mapping in
                            forwardingRow(mapping)
                        }
                    }
                }
                if let external = table.externalIP {
                    InfoRow("Public address") {
                        Text(external).monospaced()
                    }
                }
            }
            if snapshot == nil {
                if scanner.checkingForwarding {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Asking the router…")
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Button {
                        scanner.checkPortForwarding()
                    } label: {
                        Label(device.profile.portForwarding == nil ? "Check port forwarding" : "Check again", systemImage: "arrow.left.arrow.right")
                    }
                    .disabled(scanner.isScanning)
                }
            }
        } header: {
            Text("Port forwarding")
        } footer: {
            Text("Rules the router reports over UPnP. A rule added by hand in the router's own settings page can be missing here.")
        }
    }

    private func forwardingRow(_ mapping: PortMapping) -> some View {
        let target = scanner.devices[mapping.internalClient]
        return HStack(spacing: 10) {
            Text("\(mapping.proto) \(mapping.externalPort, format: .number.grouping(.never))")
                .font(.body.monospacedDigit().weight(.semibold))
                .lineLimit(1)
                .fixedSize()
            Image(systemName: "arrow.right")
                .font(.caption)
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: "\(target?.displayName ?? mapping.internalClient):\(mapping.internalPort)")
                    .lineLimit(1)
                if let description = mapping.description, !description.isEmpty {
                    Text(description)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "globe")
                .foregroundStyle(.red)
        }
    }

    /// What the open ports say about the host, worst first. Shown only once
    /// the ports were scanned, since "nothing found" means little before that.
    private func securitySection(_ device: LANDevice) -> some View {
        let findings = device.riskFindings
        return Section {
            if findings.isEmpty {
                Label {
                    Text(device.openPorts.isEmpty ? "No open ports, nothing to assess." : "No known risks on the open ports.")
                        .foregroundStyle(.secondary)
                } icon: {
                    Image(systemName: "checkmark.shield.fill")
                        .foregroundStyle(.green)
                }
            } else {
                ForEach(findings) { finding in
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        // Inside Text so the symbol sits on the title's baseline.
                        Text(Image(systemName: finding.level.systemImage))
                            .foregroundStyle(finding.level.color)
                            .frame(width: 22)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(finding.title)
                                .font(.body.weight(.medium))
                                .fixedSize(horizontal: false, vertical: true)
                            Text(finding.detail)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                            if !finding.ports.isEmpty {
                                PortChips(ports: finding.ports)
                                    .padding(.top, 2)
                            }
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        } header: {
            HStack {
                Text("Security")
                Spacer()
                if let level = device.riskLevel, level >= .low {
                    Text(level.label)
                        .foregroundStyle(level.color)
                }
            }
        } footer: {
            Text("Judged by which ports answer. The scan does not try passwords or exploits, so a service can be safer or worse than it looks here.")
        }
    }

    /// The browser address of a port that speaks HTTP: the banner grab says
    /// so, or the port is one web servers commonly use.
    private func webAddress(_ number: Int, on device: LANDevice) -> URL? {
        let https: Bool
        if let banner = device.profile.banners.first(where: { $0.port == number }) {
            https = banner.secure
        } else if LANProbes.secureWeb.contains(number) {
            https = true
        } else if LANProbes.plainWeb.contains(number) {
            https = false
        } else {
            return nil
        }
        let scheme = https ? "https" : "http"
        let isDefault = (https && number == 443) || (!https && number == 80)
        return URL(string: isDefault ? "\(scheme)://\(device.ipAddress)" : "\(scheme)://\(device.ipAddress):\(number)")
    }

    /// The readable name and model on lines of their own. Google Cast and
    /// Spotify name their instances with a long hex ID, which goes on a
    /// separate monospaced line instead of passing for a name.
    private func serviceParts(_ service: ServiceAdvertisement) -> (lines: [String?], identifier: String?) {
        let (name, identifier) = ServiceBlock.splitIdentifier(service.name)
        var lines: [String?] = [name]
        // AirPlay audio once kept its metadata list ("0,1,2") as the model.
        if let model = service.model, !model.allSatisfy({ $0.isNumber || $0 == "," }),
           !(name ?? "").localizedCaseInsensitiveContains(model) {
            lines.append(model)
        }
        return (lines, identifier)
    }

    private func badge(_ text: LocalizedStringKey, color: Color) -> some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color, in: .capsule)
    }
}
