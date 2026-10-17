import SwiftUI

/// A single Wi-Fi client (station) in a list.
struct ClientRow: View {
    var client: WiFiClient

    @Environment(LANScanner.self) private var scanner

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: clientIcon)
                .font(.title3)
                .foregroundStyle(client.isAssociated ? .green : .secondary)
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(client.displayName)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                    if client.mac.isLocallyAdministered {
                        Text("Random")
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(.purple, in: .capsule)
                            .lineLimit(1)
                            .fixedSize()
                    }
                }
                Text(subtitleText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospaced()
                    .lineLimit(1)
            }

            Spacer(minLength: 0)

            Text("\(client.rssi)")
                .font(.callout.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .contextMenu {
            Button("Copy MAC", systemImage: "doc.on.doc") {
                UIPasteboard.general.string = client.mac.description
            }
        }
    }

    private var clientIcon: String {
        let name = client.displayName.lowercased()
        if name.contains("apple") || name.contains("iphone") { return "iphone" }
        if name.contains("ipad") { return "ipad" }
        if name.contains("mac") || name.contains("book") { return "laptopcomputer" }
        if name.contains("samsung") || name.contains("xiaomi") || name.contains("huawei") { return "smartphone" }
        if name.contains("amazon") || name.contains("echo") { return "homepod.fill" }
        if name.contains("google") || name.contains("chromecast") { return "tv" }
        if name.contains("raspberry") || name.contains("espressif") { return "cpu" }
        if client.mac.isLocallyAdministered { return "questionmark.circle" }
        return client.isAssociated ? "wifi" : "antenna.radiowaves.left.and.right"
    }

    private var subtitleText: String {
        var parts = [client.mac.description]
        if let host = lanHost, host.displayName != client.displayName {
            parts.append(String(localized: "on LAN \(host.displayName)"))
        }
        return parts.joined(separator: " · ")
    }

    private var lanHost: LANDevice? {
        let address = client.mac.description
        return scanner.devices.values.first { $0.macAddress?.description == address }
    }
}

/// What the sensor already knows about one station, including a LAN host with the same address.
struct ClientDetailView: View {
    var client: WiFiClient
    /// Probe list captured with a saved scan, when the live store no longer has it.
    var savedProbe: ProbingDevice?

    @Environment(NetworkStore.self) private var store
    @Environment(LANScanner.self) private var scanner

    var body: some View {
        List {
            Section {
                InfoRow("MAC address", value: client.mac.description)
                InfoRow("Signal", value: "\(client.rssi) dBm")
                if client.mac.isLocallyAdministered {
                    InfoRow("Address type", value: String(localized: "Randomised"))
                } else if let maker = client.manufacturer {
                    InfoRow("Maker", value: maker)
                }
                InfoRow("Associated", value: client.isAssociated ? String(localized: "Yes") : String(localized: "Probe only"))
                if let ap = client.apBSSID, let network = store.networks[ap] {
                    InfoRow("Network", value: network.revealedSSID ?? network.latest.displayName)
                }
            }
            if let probe = savedProbe ?? store.probingDevices.first(where: { $0.mac == client.mac }) {
                Section("Probes") {
                    InfoRow("Channel", value: "\(probe.channel)")
                    if probe.ssids.isEmpty {
                        InfoRow("Looking for", value: String(localized: "Broadcast"))
                    } else {
                        InfoRow("Looking for", value: probe.ssids.joined(separator: ", "))
                    }
                }
            }
            if let host = lanHost {
                Section("On the LAN") {
                    NavigationLink {
                        DeviceDetailView(ip: host.ipAddress)
                    } label: {
                        HStack {
                            Text(host.displayName)
                            Spacer()
                            Text(host.ipAddress)
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
                    }
                }
            }
        }
        .navigationTitle(client.displayName)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var lanHost: LANDevice? {
        let address = client.mac.description
        return scanner.devices.values.first { $0.macAddress?.description == address }
    }
}
