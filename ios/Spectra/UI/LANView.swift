import SwiftUI

struct LANView: View {
    @Environment(LANScanner.self) private var scanner
    @Environment(LinkProbe.self) private var probe
    @AppStorage("probeViaVpn") private var viaVpn = false
    @State private var search = ""
    @State private var path = NavigationPath()

    private var visible: [LANDevice] {
        let query = search.trimmingCharacters(in: .whitespaces)
        return scanner.sortedDevices.filter { device in
            query.isEmpty
                || device.ipAddress.localizedStandardContains(query)
                || device.displayName.localizedStandardContains(query)
                || (device.macAddress?.description ?? "").localizedStandardContains(query)
                || (device.manufacturer ?? "").localizedStandardContains(query)
                || device.identity.label.localizedStandardContains(query)
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                connectionSection

                Section {
                    NavigationLink { SnifferView() } label: {
                        Label("Traffic", systemImage: "waveform.path.ecg")
                    }
                }

                if case .scanning(let progress, let phase) = scanner.state {
                    Section {
                        VStack(alignment: .leading, spacing: 4) {
                            ProgressView(value: progress)
                                .tint(.teal)
                            Text(phase)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                if !visible.isEmpty {
                    Section {
                        ForEach(visible) { device in
                            NavigationLink(value: LANRoute.device(device.ipAddress)) {
                                DeviceRow(device: device)
                            }
                        }
                    } header: {
                        Text("\(scanner.devices.count) devices")
                    }
                } else if scanner.devices.isEmpty, !scanner.isScanning, search.isEmpty {
                    Section {
                        if case .noNetwork = scanner.state {
                            ContentUnavailableView(
                                "No Wi-Fi",
                                systemImage: "wifi.slash",
                                description: Text("Connect to a Wi-Fi network to scan devices on it.")
                            )
                        } else {
                            ContentUnavailableView {
                                Label("Local network", systemImage: "network")
                            } description: {
                                Text("Find devices, their MAC addresses and open ports on the network your iPhone is on.")
                            } actions: {
                                Button("Scan", action: scanner.scan)
                                    .buttonStyle(.borderedProminent)
                            }
                        }
                    }
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }
            }
            .overlay {
                if visible.isEmpty, !search.isEmpty, !scanner.devices.isEmpty {
                    ContentUnavailableView.search(text: search)
                }
            }
            .sensoryFeedback(trigger: scanner.scanCount) { old, new in new > old ? .success : nil }
            .searchable(text: $search, prompt: Text("IP, name or MAC"))
            .refreshable { await runScan() }
            .navigationTitle("LAN")
            .navigationDestination(for: LANRoute.self) { route in
                switch route {
                case .device(let ip):
                    DeviceDetailView(ip: ip)
                case .connection:
                    LinkView()
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    LANStatusBar()
                }
                if #available(iOS 26, *) {
                    ToolbarSpacer(.fixed, placement: .topBarTrailing)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Scan now", systemImage: "arrow.clockwise", action: scanner.scan)
                            .disabled(scanner.isScanning || noNetwork)
                        if !scanner.devices.isEmpty {
                            Divider()
                            Button("Clear list", systemImage: "trash", role: .destructive) {
                                scanner.clear()
                            }
                        }
                    } label: {
                        Label("Options", systemImage: "line.3.horizontal.decrease")
                    }
                }
            }
        }
    }

    private var connectionSection: some View {
        Section {
            // There is nothing to open until the internet has been checked once.
            if probe.report != nil || probe.running {
                NavigationLink(value: LANRoute.connection) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(connectionTitle(probe))
                            .font(.body.weight(.medium))
                        Text(connectionDetail(probe))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
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
                    path.append(LANRoute.connection)
                } label: {
                    Label(probe.report == nil ? "Check connection" : "Check again", systemImage: "gauge.with.dots.needle.67percent")
                }
            }
        } header: {
            Text("Connection")
        }
    }

    private var noNetwork: Bool {
        if case .noNetwork = scanner.state { return true }
        return false
    }

    private func runScan() async {
        scanner.scan()
        while scanner.isScanning {
            try? await Task.sleep(for: .milliseconds(200))
        }
    }

}

/// Subnet and scan state, in its own glass capsule. Tapping starts or stops the scan.
struct LANStatusBar: View {
    @Environment(LANScanner.self) private var scanner
    @State private var taps = 0

    var body: some View {
        Button(action: toggle) {
            HStack(spacing: 6) {
                Image(systemName: "network")
                    .symbolEffect(.variableColor.iterative, isActive: scanner.isScanning)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                    Text(detail)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: 168, alignment: .leading)
        }
        .sensoryFeedback(.impact(weight: .light), trigger: taps)
        .accessibilityLabel("\(title), \(detail)")
    }

    private func toggle() {
        taps += 1
        if scanner.isScanning {
            scanner.stop()
        } else {
            scanner.scan()
        }
    }

    private var title: String {
        if scanner.isScanning { return String(localized: "Scanning…") }
        if case .noNetwork = scanner.state { return String(localized: "No Wi-Fi") }
        if let ip = scanner.subnetInfo?.localIP { return ip }
        return String(localized: "LAN")
    }

    private var detail: String {
        if case .scanning(_, let phase) = scanner.state { return phase }
        if case .noNetwork = scanner.state { return String(localized: "Join a network") }
        if let info = scanner.subnetInfo {
            var parts = ["/\(info.cidr)"]
            if let gateway = info.gateway { parts.append(gateway) }
            if let date = scanner.lastScanDate {
                parts.append(date.formatted(.relative(presentation: .named)))
            }
            return parts.joined(separator: " · ")
        }
        return String(localized: "Tap to scan")
    }
}

// MARK: - Device row

struct DeviceRow: View {
    var device: LANDevice

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: device.deviceIcon)
                .font(.title3)
                .foregroundStyle(device.isLocalDevice ? .teal : device.isGateway ? .orange : .secondary)
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(device.displayName)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                        .layoutPriority(1)
                    if device.isLocalDevice {
                        badge("This device", color: .teal)
                    } else if device.isGateway {
                        badge("Gateway", color: .orange)
                    } else if let notable = device.notable {
                        badge(notable == .camera ? "Camera" : "IoT", color: notable == .camera ? .red : .orange)
                    }
                }
                if !subtitleText.isEmpty {
                    Text(subtitleText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                        .lineLimit(2)
                }
            }

            Spacer(minLength: 0)
        }
        .contextMenu {
            Button("Copy IP", systemImage: "doc.on.doc") {
                UIPasteboard.general.string = device.ipAddress
            }
            if let mac = device.macAddress {
                Button("Copy MAC", systemImage: "number") {
                    UIPasteboard.general.string = mac.description
                }
            }
        }
    }

    private func badge(_ text: LocalizedStringKey, color: Color) -> some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .foregroundStyle(.white)
            .padding(.horizontal, 5)
            .padding(.vertical, 1)
            .background(color, in: .capsule)
            .lineLimit(1)
            .fixedSize()
            .layoutPriority(-1)
    }

    private var subtitleText: String {
        var parts: [String] = []
        if device.displayName != device.ipAddress {
            parts.append(device.ipAddress)
        }
        if let mac = device.macAddress {
            parts.append(mac.description)
        }
        for fact in [device.identity.manufacturer, device.identity.operatingSystem, device.identity.model] {
            guard let fact, !device.displayName.localizedStandardContains(fact),
                  !parts.contains(where: { $0.localizedStandardContains(fact) }) else { continue }
            parts.append(fact)
        }
        if let mfr = device.manufacturer, device.identity.manufacturer == nil, device.displayName != mfr {
            parts.append(mfr)
        }
        if !device.openPorts.isEmpty {
            let portList = device.openPorts.prefix(4).map { "\($0.number)" }.joined(separator: ", ")
            let suffix = device.openPorts.count > 4 ? "…" : ""
            parts.append(String(localized: "ports \(portList)\(suffix)"))
        }
        return parts.joined(separator: " · ")
    }
}
