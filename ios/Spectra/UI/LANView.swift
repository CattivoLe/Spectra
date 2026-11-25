import SwiftUI

struct LANView: View {
    @Environment(LANScanner.self) private var scanner
    @Environment(WiFiInfo.self) private var wifi
    @Environment(SensorLink.self) private var link
    @Environment(NetworkStore.self) private var store
    @Environment(WebLinks.self) private var webLinks
    @AppStorage("notifyNewDevices") private var notifyNewDevices = true
    @AppStorage("hideTrusted") private var hideTrusted = false
    @AppStorage("autosaveHistory") private var autosave = true
    @Environment(HistoryStore.self) private var history
    @State private var search = ""
    @State private var confirmForget = false
    @State private var showHistory = false
    @State private var showWebLinks = false
    /// Show only the hosts whose services need attention.
    @State private var onlyAtRisk = false

    private var isOpenWiFi: Bool {
        guard let current = wifi.current else { return false }
        let heard = store.networks.values
            .filter { ($0.revealedSSID ?? $0.latest.ssid) == current.ssid }
            .map(\.latest.auth.strength)
        return current.isOpen(savedPassword: link.knownPassword(for: current.ssid), heard: heard)
    }

    private var atRisk: [LANDevice] { scanner.sortedDevices.filter(\.needsAttention) }

    private var visible: [LANDevice] {
        let query = search.trimmingCharacters(in: .whitespaces)
        return scanner.sortedDevices.filter { device in
            (!onlyAtRisk || device.needsAttention) && !(hideTrusted && device.trust == .trusted && query.isEmpty) && (query.isEmpty
                || device.ipAddress.localizedStandardContains(query)
                || device.displayName.localizedStandardContains(query)
                || (device.macAddress?.description ?? "").localizedStandardContains(query)
                || (device.manufacturer ?? "").localizedStandardContains(query)
                || device.identity.label.localizedStandardContains(query))
        }
    }

    var body: some View {
        NavigationStack {
            List {
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

                if !webLinks.links.isEmpty, search.isEmpty {
                    Section { webLinksRow }
                }

                if case .done = scanner.state, !scanner.devices.isEmpty, search.isEmpty {
                    Section {
                        if isOpenWiFi {
                            summaryRow(
                                "Open Wi-Fi",
                                detail: "This network has no password. Anyone nearby can join it and read traffic that is not encrypted.",
                                systemImage: "wifi.exclamationmark", color: .red
                            )
                        }
                        if wifi.needsLocationAccess, let settings = URL(string: UIApplication.openSettingsURLString) {
                            Link(destination: settings) {
                                summaryRow(
                                    "Wi-Fi name unavailable",
                                    detail: "Allow location access in Settings so Spectra can read the network name.",
                                    systemImage: "location.slash", color: .secondary
                                )
                            }
                        }
                        Button {
                            withAnimation { onlyAtRisk.toggle() }
                        } label: {
                            riskSummary
                        }
                        .buttonStyle(.plain)
                        .contentShape(Rectangle())
                        .disabled(atRisk.isEmpty)
                    }
                }

                if !visible.isEmpty {
                    Section {
                        ForEach(visible) { device in
                            NavigationLink(value: device.ipAddress) {
                                DeviceRow(device: device)
                            }
                        }
                    } header: {
                        let fresh = scanner.newDevices.count
                        if onlyAtRisk {
                            Text("\(visible.count) of \(scanner.devices.count) devices")
                        } else if fresh > 0 {
                            Text("\(scanner.devices.count) devices · \(fresh) new")
                        } else {
                            Text("\(scanner.devices.count) devices")
                        }
                    } footer: {
                        if scanner.lastVerdict?.isBaseline == true {
                            Text("First scan of this network. Devices that show up in later scans will be marked New.")
                        }
                    }
                } else if scanner.devices.isEmpty, !scanner.isScanning, search.isEmpty {
                    Section {
                        if case .noNetwork = scanner.state {
                            ContentUnavailableView(
                                "No Wi-Fi",
                                systemImage: "wifi.slash",
                                description: Text("Connect to a Wi-Fi network to scan devices on it.")
                            )
                        } else if case .localNetworkDenied = scanner.state {
                            ContentUnavailableView {
                                Label("No local network access", systemImage: "lock.shield")
                            } description: {
                                Text("iOS does not let Spectra reach devices on this Wi-Fi. Turn on Spectra in Settings → Privacy & Security → Local Network, then scan again.")
                            } actions: {
                                Button("Open Settings") {
                                    if let url = URL(string: UIApplication.openSettingsURLString) {
                                        UIApplication.shared.open(url)
                                    }
                                }
                                .buttonStyle(.borderedProminent)
                                Button("Scan again", action: scanner.scan)
                            }
                        } else if case .ipv6Only = scanner.state {
                            ContentUnavailableView {
                                Label("IPv6-only network", systemImage: "network.badge.shield.half.filled")
                            } description: {
                                Text("This Wi-Fi gives the iPhone no IPv4 address. The device scan walks an IPv4 subnet, so there is nothing to sweep here. Connection, speed and link checks on the Internet tab work as usual.")
                            } actions: {
                                Button("Scan again", action: scanner.scan)
                            }
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
            .onChange(of: atRisk.isEmpty) { _, empty in
                if empty { onlyAtRisk = false }
            }
            .sensoryFeedback(trigger: scanner.scanCount) { old, new in new > old ? .success : nil }
            .searchable(text: $search, prompt: Text("IP, name or MAC"))
            .refreshable { await runScan() }
            .navigationTitle("LAN")
            .navigationDestination(for: String.self) { ip in
                DeviceDetailView(ip: ip)
            }
            .navigationDestination(isPresented: $showHistory) {
                HistoryView(area: .lan)
            }
            .navigationDestination(isPresented: $showWebLinks) {
                WebLinksView()
            }
            .toolbar {
                if !autosave {
                    ToolbarItem(placement: .topBarLeading) {
                        SaveToHistoryButton(disabled: scanner.devices.isEmpty || scanner.isScanning) {
                            history.upsertLAN(scanner.sortedDevices, networkKey: scanner.networkKey,
                                              networkLabel: scanner.networkLabel, source: "This iPhone")
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    LANStatusBar()
                }
                if #available(iOS 26, *) {
                    ToolbarSpacer(.fixed, placement: .topBarTrailing)
                }
                HistoryButton(area: .lan, isPresented: $showHistory)
                if #available(iOS 26, *) {
                    ToolbarSpacer(.fixed, placement: .topBarTrailing)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Web interfaces", systemImage: "bookmark") { showWebLinks = true }
                        Divider()
                        Toggle("Notify about new devices", systemImage: "bell.badge", isOn: $notifyNewDevices)
                        Toggle("Hide trusted devices", systemImage: "checkmark.seal", isOn: $hideTrusted)
                        Toggle("Use Wi-Fi name", systemImage: "wifi", isOn: Binding(get: { wifi.enabled }, set: { wifi.setEnabled($0) }))
                        if scanner.hasBaseline {
                            Button("Forget known devices", systemImage: "eraser") {
                                confirmForget = true
                            }
                            .disabled(scanner.isScanning)
                        }
                        Divider()
                        Button("Scan now", systemImage: "arrow.clockwise", action: scanner.scan)
                            .disabled(scanner.isScanning)
                        if !scanner.devices.isEmpty {
                            Button("Clear list", systemImage: "trash", role: .destructive) {
                                scanner.clear()
                            }
                        }
                    } label: {
                        Label("Options", systemImage: "line.3.horizontal.decrease")
                    }
                }
            }
            .summaryButton(isShown: !scanner.devices.isEmpty, placement: .topBarLeading, subject: { .lan })
            .confirmationDialog("Forget known devices?", isPresented: $confirmForget, titleVisibility: .visible) {
                Button("Forget", role: .destructive) { scanner.forgetKnownHosts() }
            } message: {
                Text("The next scan of this network becomes the baseline: nothing in it is marked New.")
            }
        }
    }

    private func summaryRow(_ title: LocalizedStringKey, detail: LocalizedStringKey, systemImage: String, color: Color) -> some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.title3)
                .foregroundStyle(color)
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
    }

    /// The way to the kept web pages; shown before any scan, since they need none.
    private var webLinksRow: some View {
        Button {
            showWebLinks = true
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "bookmark.fill")
                    .font(.title3)
                    .foregroundStyle(.tint)
                    .frame(width: 30)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Web interfaces")
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                    Text(webLinks.titles.joined(separator: ", "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                Text(webLinks.links.count, format: .number)
                    .foregroundStyle(.secondary)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// One line on the network's open services. Tapping filters the list.
    private var riskSummary: some View {
        let count = atRisk.count
        let worst = atRisk.compactMap(\.riskLevel).max()
        return HStack(spacing: 12) {
            Image(systemName: worst?.systemImage ?? "checkmark.shield.fill")
                .font(.title3)
                .foregroundStyle(worst?.color ?? .green)
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(count == 0 ? "No risky services found" : "\(count) devices need attention")
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                Text(count == 0
                     ? "Open ports were checked against known weak services."
                     : (onlyAtRisk ? "Showing only those. Tap to show every device." : "Telnet, open shares, remote access and the like. Tap to show only those."))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if onlyAtRisk {
                Image(systemName: "line.3.horizontal.decrease.circle.fill")
                    .foregroundStyle(.tint)
            }
        }
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
        if case .ipv6Only = scanner.state { return scanner.wifi.current?.ssid ?? String(localized: "IPv6 only") }
        if case .localNetworkDenied = scanner.state { return String(localized: "No access") }
        if let ssid = scanner.wifi.current?.ssid { return ssid }
        if let ip = scanner.subnetInfo?.localIP { return ip }
        return String(localized: "LAN")
    }

    private var detail: String {
        if case .scanning(_, let phase) = scanner.state { return phase }
        if case .noNetwork = scanner.state { return String(localized: "Join a network") }
        if case .ipv6Only = scanner.state { return String(localized: "No IPv4 to scan") }
        if case .localNetworkDenied = scanner.state { return String(localized: "Local network access is off") }
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
                    if device.isNew {
                        badge("New", color: .purple)
                    }
                    if device.trust == .trusted {
                        Image(systemName: "checkmark.seal.fill")
                            .font(.caption)
                            .foregroundStyle(.green)
                            .accessibilityLabel(Text("Trusted"))
                    } else if device.trust == .suspicious {
                        badge("Suspicious", color: .red)
                    }
                }
                if !subtitleText.isEmpty {
                    Text(subtitleText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                        .lineLimit(2)
                }
                if !portsText.isEmpty {
                    Text(portsText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                        .lineLimit(2)
                }
            }

            Spacer(minLength: 0)

            if let level = device.riskLevel, level >= .medium {
                Image(systemName: level.systemImage)
                    .foregroundStyle(level.color)
                    .accessibilityLabel(Text(level.label))
            }
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
        return parts.joined(separator: " · ")
    }

    /// Open ports on a line of their own under the address.
    private var portsText: String {
        guard !device.openPorts.isEmpty else { return "" }
        let shown = 8
        let portList = device.openPorts.prefix(shown).map { "\($0.number)" }.joined(separator: ", ")
        let rest = device.openPorts.count - shown
        let suffix = rest > 0 ? " +\(rest)" : ""
        return String(localized: "ports \(portList)\(suffix)")
    }
}
