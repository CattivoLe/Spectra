import SwiftUI

struct DeviceDetailView: View {
    var ip: String

    @Environment(LANScanner.self) private var scanner

    var body: some View {
        if let device = scanner.devices[ip] {
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
                                if let mfr = device.manufacturer {
                                    Text(mfr)
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
                .padding(.vertical, 6)
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
                if !device.hostnames.isEmpty {
                    ForEach(device.hostnames, id: \.self) { name in
                        InfoRow("Hostname") {
                            Text(name)
                                .textSelection(.enabled)
                        }
                    }
                }
            }

            // Ports
            Section {
                if device.portsScanned {
                    if device.openPorts.isEmpty {
                        Label("No open ports found", systemImage: "lock.shield")
                            .foregroundStyle(.secondary)
                            .font(.subheadline)
                    } else {
                        ForEach(device.openPorts) { port in
                            HStack {
                                Text("\(port.number)")
                                    .font(.body.monospacedDigit().weight(.semibold))
                                    .frame(width: 56, alignment: .trailing)
                                Text(port.service)
                                    .foregroundStyle(.secondary)
                                Spacer()
                                Image(systemName: "circle.fill")
                                    .font(.system(size: 8))
                                    .foregroundStyle(.green)
                            }
                        }
                    }
                }

                Button {
                    scanner.scanPorts(for: ip)
                } label: {
                    Label(device.portsScanned ? "Rescan ports" : "Scan ports", systemImage: "magnifyingglass")
                }
            } header: {
                Text("Ports")
            } footer: {
                Text("Probes \(DiscoveredPort.commonPorts.count) common TCP ports with a 1.5 s timeout each.")
            }

            // Timestamps
            Section("Observed") {
                InfoRow("First seen") { Text(device.firstSeen, style: .time) }
                InfoRow("Last seen") { Text(device.lastSeen, style: .relative) }
            }
        }
        .navigationTitle(device.ipAddress)
        .navigationBarTitleDisplayMode(.inline)
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
