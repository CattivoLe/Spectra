import SwiftUI

struct NetworksView: View {
    var openSensor: () -> Void

    @Environment(NetworkStore.self) private var store
    @Environment(SensorLink.self) private var link
    @State private var search = ""
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
                Section {
                    SensorStatusCard(openSensor: openSensor)
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
            .navigationTitle("Networks")
            .navigationDestination(for: BSSID.self) { id in
                NetworkDetailView(id: id)
            }
            .toolbar {
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

/// Where the data comes from, and whether it is fresh.
struct SensorStatusCard: View {
    var openSensor: () -> Void

    @Environment(NetworkStore.self) private var store
    @Environment(SensorLink.self) private var link

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: link.isConnected ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                .font(.title2)
                .foregroundStyle(link.isConnected ? Color.accentColor : .secondary)
                .symbolEffect(.variableColor.iterative, isActive: store.isScanning)
                .frame(width: 36)

            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                subtitle
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 0)

            if link.isConnected {
                Button {
                    link.scanNow()
                } label: {
                    if store.isScanning {
                        ProgressView()
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.circle)
                .disabled(store.isScanning)
                .accessibilityLabel("Scan now")
            } else {
                Button("Connect", action: openSensor)
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding(.vertical, 4)
    }

    private var title: String {
        switch link.state {
        case .connected(let name): name
        case .connecting(let name): name
        case .demo: String(localized: "Demo sensor")
        default: String(localized: "No sensor")
        }
    }

    @ViewBuilder private var subtitle: some View {
        switch link.state {
        case .connecting:
            Text("Connecting…")
        case .connected, .demo:
            if store.isScanning {
                Text("Scanning…")
            } else if let date = store.lastScanFinished {
                Text("Updated \(date, style: .relative) ago")
            } else {
                Text("Waiting for the first scan")
            }
        default:
            Text("Connect a Cardputer to see networks")
        }
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
                        Text("Missed last scan")
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
