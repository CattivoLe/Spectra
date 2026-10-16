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
                if !visible.isEmpty {
                    Section {
                        ForEach(visible) { network in
                            NavigationLink(value: network.id) {
                                NetworkRow(network: network)
                            }
                        }
                    } header: {
                        // Matches the count on the Cardputer's screen.
                        Text("\(store.networks.values.filter(\.wasInLastScan).count) in last scan")
                    }
                }
            }
            .overlay {
                if store.networks.isEmpty, link.isConnected {
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
                HStack(spacing: 5) {
                    Text(reading.displayName)
                        .font(.body.weight(.medium))
                        .italic(reading.ssid == nil)
                        .foregroundStyle(reading.ssid == nil ? .secondary : .primary)
                        .lineLimit(1)
                    Image(systemName: reading.securitySymbol)
                        .font(.caption)
                        .foregroundStyle(reading.securityColor)
                }
                Group {
                    if network.wasInLastScan {
                        Text("Ch \(reading.channel) · \(reading.auth.name) · \(reading.capabilities.generation)")
                    } else if network.isInRange {
                        Text("Missed last scan")
                    } else {
                        Text("Out of range · \(network.lastSeen, style: .relative)")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .monospacedDigit()
                .lineLimit(1)
            }

            Spacer(minLength: 8)

            if network.isInRange {
                Sparkline(values: network.samples.suffix(24).map(\.rssi), color: SignalQuality(rssi: reading.rssi).color)
                    .frame(width: 44, height: 20)
                    .accessibilityHidden(true)
            }

            Text("\(reading.rssi)")
                .font(.callout.monospacedDigit().weight(.semibold))
                .foregroundStyle(network.isInRange ? .primary : .tertiary)
                .frame(minWidth: 32, alignment: .trailing)
                .accessibilityLabel("\(reading.rssi) dBm")
        }
        .opacity(network.wasInLastScan ? 1 : network.isInRange ? 0.8 : 0.6)
        .contentTransition(.numericText())
        .animation(.default, value: reading.rssi)
    }
}
