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
                                if let notable = device.notable {
                                    badge(notable == .camera ? "Camera" : "IoT", color: notable == .camera ? .red : .orange)
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

            if !device.profile.isEmpty {
                Section("Services") {
                    if let upnp = device.profile.upnp {
                        if let name = upnp.friendlyName { InfoRow("UPnP name", value: name) }
                        if let model = upnp.model { InfoRow("Model", value: model) }
                        if let maker = upnp.manufacturer { InfoRow("Manufacturer", value: maker) }
                        if let type = upnp.deviceType { InfoRow("Device type", value: type) }
                        if let server = upnp.server { InfoRow("Server", value: server) }
                    }
                    if let windows = device.profile.windows {
                        if let name = windows.computerName { InfoRow("Windows name", value: name) }
                        if let domain = windows.domain { InfoRow("Domain", value: domain) }
                        if windows.shares.isEmpty, windows.guestSession {
                            Text("Guest connection found no shared folders.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        } else if !windows.shares.isEmpty {
                            InfoRow("Shares", value: windows.shares.formatted(.list(type: .and)))
                        }
                    }
                    ForEach(device.profile.banners) { banner in
                        portLine(banner.secure ? "HTTPS \(banner.port)" : "HTTP \(banner.port)", detail: banner.summary)
                    }
                    ForEach(device.profile.rtsp) { service in
                        portLine("RTSP \(service.port)", detail: service.summary ?? "")
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
                            HStack(spacing: 12) {
                                Text(port.number, format: .number.grouping(.never))
                                    .font(.body.monospacedDigit().weight(.semibold))
                                    .lineLimit(1)
                                    .fixedSize(horizontal: true, vertical: false)
                                Text(port.service)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                Spacer(minLength: 8)
                                Image(systemName: "circle.fill")
                                    .font(.system(size: 8))
                                    .foregroundStyle(.green)
                            }
                        }
                    }
                }

                if let fraction = device.portScanFraction {
                    ProgressView(value: fraction) {
                        Text("Scanning ports…")
                    }
                } else {
                    Button {
                        scanner.scanPorts(for: ip)
                    } label: {
                        Label(device.portsScanned ? "Rescan ports" : "Scan ports", systemImage: "magnifyingglass")
                    }
                    .disabled(scanner.isScanning)
                    Button {
                        scanner.scanPorts(for: ip, scope: .all)
                    } label: {
                        Label("Scan all ports", systemImage: "checklist")
                    }
                    .disabled(scanner.isScanning)
                }
            } header: {
                Text("Ports")
            } footer: {
                Text("Checks \(DiscoveredPort.commonPorts.count) common TCP ports, or every TCP port from 1 to 65535.")
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

    private func portLine(_ title: LocalizedStringKey, detail: String) -> some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body.monospacedDigit().weight(.semibold))
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
            if !detail.isEmpty {
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
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
