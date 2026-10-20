import SwiftUI

struct NetworksView: View {
    var openSensor: () -> Void

    @Environment(NetworkStore.self) private var store
    @Environment(SensorLink.self) private var link
    @Environment(WatchList.self) private var watchList
    @Environment(WiFiJoiner.self) private var joiner
    @Environment(\.scenePhase) private var scenePhase
    @State private var search = ""
    @State private var showChannels = false
    @State private var expandedThreats: Set<String> = []
    @AppStorage("networkSort") private var sort: Sort = .signal
    @AppStorage("showOutOfRange") private var showOutOfRange = false

    enum Sort: String, CaseIterable, Identifiable {
        case signal, name, channel
        var id: Self { self }

        var label: LocalizedStringKey {
            switch self {
            case .signal: "Signal"
            case .name: "Name"
            case .channel: "Channel"
            }
        }
    }

    private var threats: [Threats.Finding] {
        Threats.assess(networks: Array(store.networks.values), deauths: store.deauthEvents)
    }

    private func isCurrent(_ network: TrackedNetwork) -> Bool {
        joiner.isCurrent(network)
    }

    private var watching: [TrackedNetwork] {
        WatchList.present(watchList.ids, in: Array(store.networks.values))
    }

    private var visible: [TrackedNetwork] {
        let query = search.trimmingCharacters(in: .whitespaces)
        let filtered = store.networks.values.filter { network in
            (showOutOfRange || network.isInRange)
                && (query.isEmpty
                    || network.latest.displayName.localizedStandardContains(query)
                    || network.revealedSSID?.localizedStandardContains(query) == true
                    || network.id.description.localizedStandardContains(query))
        }
        return filtered.sorted { a, b in
            if a.isInRange != b.isInRange { return a.isInRange }
            switch sort {
            case .signal:
                return a.latest.rssi > b.latest.rssi
            case .name:
                return a.latest.displayName.localizedStandardCompare(b.latest.displayName) == .orderedAscending
            case .channel:
                return (a.latest.channel, -a.latest.rssi) < (b.latest.channel, -b.latest.rssi)
            }
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if !threats.isEmpty {
                    Section {
                        ForEach(threats) { finding in
                            let open = expandedThreats.contains(finding.id)
                            Button {
                                withAnimation(.easeInOut(duration: 0.2)) {
                                    if open { expandedThreats.remove(finding.id) } else { expandedThreats.insert(finding.id) }
                                }
                            } label: {
                                ThreatRow(finding: finding, isExpanded: open)
                            }
                            .buttonStyle(.plain)
                            if open {
                                if let event = finding.deauth {
                                    DeauthRow(event: event)
                                }
                                ForEach(finding.networks.compactMap { store.networks[$0] }) { network in
                                    NavigationLink(value: network.id) {
                                        NetworkRow(network: network, isCurrent: isCurrent(network))
                                    }
                                }
                            }
                        }
                    } header: {
                        Label("Threats", systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                            .font(.subheadline.weight(.semibold))
                    }
                }
                if !watching.isEmpty {
                    Section {
                        ForEach(watching) { network in
                            NavigationLink(value: network.id) {
                                NetworkRow(network: network, isCurrent: isCurrent(network))
                            }
                        }
                    } header: {
                        Label("Watching", systemImage: "star.fill")
                            .foregroundStyle(.yellow)
                            .font(.subheadline.weight(.semibold))
                    }
                }
                if !store.deauthEvents.isEmpty {
                    Section {
                        ForEach(store.deauthEvents) { event in
                            DeauthRow(event: event)
                        }
                    } header: {
                        Label("Deauth detected", systemImage: "exclamationmark.shield.fill")
                            .foregroundStyle(.red)
                            .font(.subheadline.weight(.semibold))
                    } footer: {
                        Text("Deauthentication frames force devices off the network. A burst or broadcast targets suggest an active attack.")
                    }
                }
                if !visible.isEmpty {
                    Section {
                        ForEach(visible) { network in
                            NavigationLink(value: network.id) {
                                NetworkRow(network: network, isCurrent: isCurrent(network))
                            }
                        }
                    } header: {
                        Text("\(store.networks.values.filter(\.wasInLastScan).count) in last scan")
                    }
                }
                if !store.probingDevices.isEmpty {
                    Section {
                        ForEach(store.probingDevices) { device in
                            ProbeDeviceRow(device: device)
                        }
                    } header: {
                        Text("\(store.probingDevices.count) probing")
                    } footer: {
                        Text("Devices sending probe requests nearby, revealing networks they have connected to before.")
                    }
                }
            }
            .overlay {
                if store.networks.isEmpty, !link.isConnected {
                    ContentUnavailableView {
                        Label("No sensor", systemImage: "antenna.radiowaves.left.and.right.slash")
                    } description: {
                        Text("iPhone cannot list nearby Wi-Fi networks by itself. Spectra hears them through a Cardputer.")
                    } actions: {
                        Button("Find sensor", action: openSensor)
                            .buttonStyle(.borderedProminent)
                    }
                } else if store.networks.isEmpty {
                    ContentUnavailableView(
                        "Listening",
                        systemImage: "wifi",
                        description: Text("Networks appear here after the first scan.")
                    )
                } else if visible.isEmpty, !search.isEmpty {
                    ContentUnavailableView.search(text: search)
                }
            }
            .sensoryFeedback(.warning, trigger: store.deauthEvents.filter { $0.severity == .critical }.count) { old, new in
                new > old
            }
            .searchable(text: $search, prompt: Text("Name or BSSID"))
            .refreshable { link.scanNow() }
            .task(id: store.scanCount) { await joiner.refreshCurrent() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await joiner.refreshCurrent() } }
            }
            .navigationTitle("Wi‑Fi")
            .navigationDestination(for: BSSID.self) { id in
                NetworkDetailView(id: id)
            }
            .navigationDestination(isPresented: $showChannels) {
                ChannelsView()
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    SensorBar(openSensor: openSensor)
                }
                if #available(iOS 26, *) {
                    ToolbarSpacer(.fixed, placement: .topBarTrailing)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Channels", systemImage: "chart.bar.xaxis") {
                        showChannels = true
                    }
                }
                if #available(iOS 26, *) {
                    ToolbarSpacer(.fixed, placement: .topBarTrailing)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("Sort by", selection: $sort) {
                            ForEach(Sort.allCases) { Text($0.label).tag($0) }
                        }
                        Toggle("Show out of range", isOn: $showOutOfRange)
                        Divider()
                        Button("Clear list", systemImage: "trash", role: .destructive) {
                            store.clear()
                        }
                    } label: {
                        Label("Options", systemImage: "line.3.horizontal.decrease")
                    }
                }
            }
        }
    }
}

/// Sensor name, battery and time since the last sweep, in its own glass
/// capsule, like the LAN and Bluetooth status. Tapping scans, or opens the
/// Sensor tab when there is nothing to scan with.
struct SensorBar: View {
    var openSensor: () -> Void

    @Environment(NetworkStore.self) private var store
    @Environment(SensorLink.self) private var link
    @State private var scanStarted: Date?
    @State private var taps = 0

    var body: some View {
        Button(action: tap) {
            HStack(spacing: 6) {
                Image(systemName: symbol)
                    .symbolEffect(.variableColor.iterative, isActive: store.isScanning)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        HStack(spacing: 4) {
                            Text(detail(at: context.date))
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                            if link.isCharging == true, link.batteryLevel != nil {
                                Circle()
                                    .fill(.green)
                                    .frame(width: 6, height: 6)
                                    .accessibilityLabel("Charging")
                            }
                        }
                    }
                }
            }
            .frame(maxWidth: 168, alignment: .leading)
        }
        .sensoryFeedback(.impact(weight: .light), trigger: taps)
        .accessibilityLabel(title)
        .accessibilityValue(spokenDetail)
        .accessibilityHint(link.isConnected ? Text("Scans now") : Text("Opens the Sensor tab"))
        .onChange(of: store.isScanning, initial: true) { _, scanning in
            scanStarted = scanning ? .now : nil
        }
    }

    private func tap() {
        taps += 1
        if link.isConnected {
            link.scanNow()
        } else {
            openSensor()
        }
    }

    private var symbol: String {
        link.isConnected
            ? "antenna.radiowaves.left.and.right"
            : "antenna.radiowaves.left.and.right.slash"
    }

    private var title: String {
        switch link.state {
        case .connected(let name), .connecting(let name): name
        case .demo: String(localized: "Demo sensor")
        case .searching: String(localized: "Searching")
        case .bluetoothUnavailable(.poweredOff): String(localized: "Bluetooth is off")
        case .bluetoothUnavailable(.unauthorized): String(localized: "Bluetooth permission")
        case .bluetoothUnavailable(.unsupported): String(localized: "No Bluetooth")
        case .idle, .bluetoothUnavailable(.starting): String(localized: "No sensor")
        }
    }

    private var battery: String? {
        guard let level = link.batteryLevel else { return nil }
        // Charging is shown as a small green dot in the row, not spelled out.
        return "\(level)%"
    }

    /// Battery, then the sweep clock: time through a running scan, or since the last one.
    private func detail(at now: Date) -> String {
        guard link.isConnected else {
            switch link.state {
            case .connecting: return String(localized: "Connecting…")
            case .searching: return String(localized: "Looking for a Cardputer")
            default: return String(localized: "Tap to connect")
            }
        }
        var parts: [String] = []
        if store.isScanning {
            parts.append(String(localized: "Scanning \(Self.clock(now.timeIntervalSince(scanStarted ?? now)))"))
        } else if let finished = store.lastScanFinished {
            parts.append("↻ \(Self.clock(now.timeIntervalSince(finished)))")
        }
        if let battery { parts.append(battery) }
        return parts.isEmpty ? String(localized: "Tap to scan") : parts.joined(separator: " · ")
    }

    private var spokenDetail: String {
        var parts: [String] = []
        if let anchor = store.isScanning ? scanStarted : store.lastScanFinished {
            let span = Duration.seconds(max(0, Int(Date.now.timeIntervalSince(anchor))))
                .formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide))
            parts.append(store.isScanning ? String(localized: "Scanning for \(span)") : String(localized: "Updated \(span) ago"))
        }
        if let battery { parts.append(battery) }
        return parts.joined(separator: ", ")
    }

    private static func clock(_ interval: TimeInterval) -> String {
        let seconds = max(0, Int(interval))
        if seconds < 3600 {
            return String(format: "%d:%02d", seconds / 60, seconds % 60)
        }
        return String(format: "%dh %02dm", seconds / 3600, (seconds % 3600) / 60)
    }
}

/// One live security finding, colored by severity. Expanded, it says what the
/// finding means; the rows under it list what raised it.
struct ThreatRow: View {
    var finding: Threats.Finding
    var isExpanded = false

    private var color: Color {
        finding.severity == .critical ? .red : .orange
    }

    private var symbol: String {
        switch finding.kind {
        case .deauthAttack: "exclamationmark.shield.fill"
        case .evilTwin: "person.2.slash.fill"
        case .streaming: "video.fill"
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(color)
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(finding.title)
                    .font(.body.weight(.semibold))
                Text(finding.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if isExpanded {
                    Text(explanation)
                        .font(.caption)
                        .padding(.top, 4)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
                .rotationEffect(.degrees(isExpanded ? 90 : 0))
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(isExpanded ? Text("Hides the details") : Text("Shows the details"))
    }

    private var explanation: LocalizedStringKey {
        switch finding.kind {
        case .deauthAttack:
            "Someone is sending frames that throw devices off the network. It is how handshakes are captured for password cracking and how devices are pushed toward a fake access point. The frames are listed below."
        case .evilTwin:
            "Copies of one network that differ in security or maker can be an impostor waiting for devices that join by name. Compare the access points below and avoid the one you do not recognise."
        case .streaming:
            "A network that moves data the whole time may be carrying a camera or microphone feed. Open it below to see which devices are connected."
        }
    }
}

struct NetworkRow: View {
    var network: TrackedNetwork
    /// The phone is on this access point.
    var isCurrent = false
    @Environment(WatchList.self) private var watchList

    private var isWatched: Bool { watchList.isWatched(network.latest.bssid.description) }

    var body: some View {
        let reading = network.latest
        HStack(spacing: 12) {
            SignalGlyph(rssi: reading.rssi, inRange: network.isInRange)
                .font(.title3)
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 4) {
                    Text("\(nameText(reading)) \(lockText(reading))")
                        .font(.body.weight(.medium))
                    if isRevealed {
                        Image(systemName: "eye.fill")
                            .font(.caption2)
                            .foregroundStyle(.purple)
                    }
                    if isWatched {
                        Image(systemName: "star.fill")
                            .font(.caption2)
                            .foregroundStyle(.yellow)
                    }
                    if isCurrent {
                        Text("Connected")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(.green, in: .capsule)
                            .lineLimit(1)
                            .fixedSize()
                    }
                }
                Group {
                    if network.wasInLastScan {
                        Text("Ch \(reading.channel) · \(reading.auth.name) · \(reading.capabilities.generation)")
                        if !network.clients.isEmpty || network.traffic != nil {
                            HStack(spacing: 8) {
                                if !network.clients.isEmpty {
                                    HStack(spacing: 2) {
                                        Image(systemName: "person.fill")
                                            .imageScale(.small)
                                            .foregroundStyle(.blue)
                                        Text("\(network.clients.count)")
                                    }
                                }
                                if let traffic = network.traffic {
                                    HStack(spacing: 2) {
                                        Image(systemName: "arrow.up.arrow.down")
                                            .imageScale(.small)
                                            .foregroundStyle(.purple)
                                        Text(traffic.formattedBytes)
                                    }
                                }
                            }
                        }
                    } else if network.isInRange {
                        Text("Missed \(network.missedScans) \(network.missedScans == 1 ? "scan" : "scans")")
                    } else {
                        Text("Out of range · \(network.lastSeen, style: .relative)")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .monospacedDigit()
            }
            .fixedSize(horizontal: false, vertical: true)

            Spacer(minLength: 8)

            // Level over its recent trend, in one narrow column, so the name
            // keeps most of the row.
            VStack(alignment: .trailing, spacing: 4) {
                Text("\(reading.rssi)")
                    .font(.callout.monospacedDigit().weight(.semibold))
                    .foregroundStyle(network.isInRange ? .primary : .tertiary)
                    .accessibilityLabel("\(reading.rssi) dBm")
                if network.isInRange {
                    Sparkline(values: network.samples.suffix(24).map(\.rssi), color: SignalQuality(rssi: reading.rssi).color)
                        .frame(width: 40, height: 14)
                        .accessibilityHidden(true)
                }
            }
        }
        .opacity(network.wasInLastScan ? 1 : network.isInRange ? 0.8 : 0.6)
        .contentTransition(.numericText())
        .animation(.default, value: reading.rssi)
        .contextMenu {
            if let ssid = reading.ssid {
                Button("Copy name", systemImage: "doc.on.doc") {
                    UIPasteboard.general.string = ssid
                }
            }
            Button("Copy BSSID", systemImage: "number") {
                UIPasteboard.general.string = reading.bssid.description
            }
            Button(isWatched ? "Stop watching" : "Watch network", systemImage: isWatched ? "star.slash" : "star") {
                watchList.toggle(reading.bssid.description)
            }
        }
    }

    private var effectiveName: String {
        network.revealedSSID ?? network.latest.displayName
    }

    private var isRevealed: Bool {
        network.revealedSSID != nil
    }

    private func nameText(_ reading: AccessPointReading) -> Text {
        if let revealed = network.revealedSSID {
            return Text(revealed)
        }
        return Text(reading.displayName)
            .italic(reading.ssid == nil)
            .foregroundStyle(reading.ssid == nil ? .secondary : .primary)
    }

    private func lockText(_ reading: AccessPointReading) -> Text {
        Text(Image(systemName: reading.securitySymbol))
            .font(.caption)
            .foregroundStyle(reading.securityColor)
    }
}
