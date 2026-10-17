import SwiftUI

struct NetworksView: View {
    var openSensor: () -> Void

    @Environment(NetworkStore.self) private var store
    @Environment(SensorLink.self) private var link
    @Environment(HistoryStore.self) private var history
    @State private var search = ""
    @State private var showChannels = false
    @AppStorage("networkSort") private var sort: Sort = .signal
    @AppStorage("showOutOfRange") private var showOutOfRange = true

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
                                NetworkRow(network: network)
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
            .searchable(text: $search, prompt: Text("Name or BSSID"))
            .refreshable { link.scanNow() }
            .navigationTitle("Wi‑Fi")
            .navigationDestination(for: BSSID.self) { id in
                NetworkDetailView(id: id)
            }
            .navigationDestination(isPresented: $showChannels) {
                ChannelsView()
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    SensorBar(open: { showChannels = true })
                }
                if #available(iOS 26, *) {
                    ToolbarSpacer(.fixed, placement: .topBarTrailing)
                }
                if store.lastScanFinished != nil || store.isScanning {
                    ToolbarItem(placement: .topBarTrailing) {
                        ScanTimer()
                    }
                    if #available(iOS 26, *) {
                        ToolbarSpacer(.fixed, placement: .topBarTrailing)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save", systemImage: "square.and.arrow.down") {
                        history.saveNetworks(store, source: "This iPhone")
                    }
                    .disabled(store.networks.isEmpty && store.probingDevices.isEmpty && store.deauthEvents.isEmpty)
                }
                if #available(iOS 26, *) {
                    ToolbarSpacer(.fixed, placement: .topBarTrailing)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Scan now", systemImage: "arrow.clockwise") {
                            link.scanNow()
                        }
                        .disabled(!link.isConnected)
                        Divider()
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

/// Sensor name and status, in its own glass capsule. Tapping opens the channel chart.
struct SensorBar: View {
    var open: () -> Void

    @Environment(NetworkStore.self) private var store
    @Environment(SensorLink.self) private var link

    var body: some View {
        Button(action: open) {
            HStack(spacing: 6) {
                Image(systemName: symbol)
                    .symbolEffect(.variableColor.iterative, isActive: store.isScanning)
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
        .accessibilityLabel("\(title), \(detail)")
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

    private var detail: String {
        var parts: [String] = []
        if store.isScanning { parts.append(String(localized: "Scanning")) }
        if let level = link.batteryLevel {
            parts.append(link.isCharging == true ? String(localized: "\(level)% charging") : "\(level)%")
        }
        if !parts.isEmpty { return parts.joined(separator: " · ") }
        switch link.state {
        case .connected, .demo: return String(localized: "Connected")
        case .connecting: return String(localized: "Connecting…")
        case .searching: return String(localized: "Looking for a Cardputer")
        default: return String(localized: "Tap to connect")
        }
    }
}

/// Time since the last finished scan, or through the one in progress.
/// Its own glass capsule. Tapping starts a scan.
struct ScanTimer: View {
    @Environment(NetworkStore.self) private var store
    @Environment(SensorLink.self) private var link
    @State private var scanStarted: Date?

    var body: some View {
        Button(action: link.scanNow) {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let anchor = store.isScanning ? (scanStarted ?? context.date) : store.lastScanFinished
                if let anchor {
                    Label(Self.clock(context.date.timeIntervalSince(anchor)), systemImage: "timer")
                        .font(.caption.monospacedDigit())
                        .labelStyle(.titleAndIcon)
                } else {
                    Label("Scan", systemImage: "timer")
                        .font(.caption.monospacedDigit())
                        .labelStyle(.titleAndIcon)
                }
            }
        }
        .disabled(!link.isConnected)
        .accessibilityLabel(scanLabel)
        .onChange(of: store.isScanning, initial: true) { _, scanning in
            scanStarted = scanning ? .now : nil
        }
    }

    private static func clock(_ interval: TimeInterval) -> String {
        let seconds = max(0, Int(interval))
        if seconds < 3600 {
            return String(format: "%d:%02d", seconds / 60, seconds % 60)
        }
        return String(format: "%dh %02dm", seconds / 3600, (seconds % 3600) / 60)
    }

    private var scanLabel: String {
        guard let anchor = store.isScanning ? scanStarted : store.lastScanFinished else {
            return String(localized: "Scan now")
        }
        return Self.spoken(since: anchor, at: .now, scanning: store.isScanning)
    }

    private static func spoken(since date: Date, at now: Date, scanning: Bool) -> String {
        let span = Duration.seconds(max(0, Int(now.timeIntervalSince(date))))
            .formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide))
        if scanning {
            return String(localized: "Scanning for \(span)")
        }
        return String(localized: "Updated \(span) ago")
    }
}

struct NetworkRow: View {
    var network: TrackedNetwork

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
                }
                Group {
                    if network.wasInLastScan {
                        let clientSuffix = network.clients.isEmpty ? "" : " · 👤 \(network.clients.count)"
                        let trafficSuffix = network.traffic.map { " · ↕ \($0.formattedBytes)" } ?? ""
                        Text("Ch \(reading.channel) · \(reading.auth.name) · \(reading.capabilities.generation)\(clientSuffix)\(trafficSuffix)")
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
