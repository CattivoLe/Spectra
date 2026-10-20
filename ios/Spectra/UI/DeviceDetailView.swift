import SwiftUI

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
                        if let name = upnp.friendlyName { InfoRow("UPnP name", value: name) }
                        if let model = upnp.model { InfoRow("Model", value: model) }
                        if let maker = upnp.manufacturer { InfoRow("Manufacturer", value: maker) }
                        if let type = upnp.deviceType { InfoRow("Device type", value: type) }
                        if let server = upnp.server { InfoRow("Server", value: server) }
                    }
                    if !device.services.isEmpty {
                        ForEach(Array(device.services.enumerated()), id: \.offset) { _, service in
                            HStack(spacing: 12) {
                                Text(service.kind)
                                    .lineLimit(1)
                                Spacer(minLength: 12)
                                Text(serviceDetail(service))
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.trailing)
                            }
                        }
                    }
                    if let windows = device.profile.windows {
                        if let name = windows.computerName { InfoRow("Network name", value: name) }
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

            if snapshot == nil || device.portsScanned {
            Section {
                if device.portsScanned {
                    if device.openPorts.isEmpty {
                        Label("No open ports found", systemImage: "lock.shield")
                            .foregroundStyle(.secondary)
                            .font(.subheadline)
                    } else {
                        ForEach(device.openPorts) { port in
                            if let url = webAddress(port.number, on: device) {
                                Link(destination: url) { portRow(port, web: true) }
                                    .accessibilityHint(Text("Opens \(url.absoluteString) in the browser"))
                            } else {
                                portRow(port, web: false)
                            }
                        }
                    }
                }

                if snapshot == nil, let ip = lookup {
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
                }
            } header: {
                Text("Ports")
            } footer: {
                if snapshot == nil {
                    Text("Checks \(DiscoveredPort.commonPorts.count) common TCP ports, or every TCP port from 1 to 65535.")
                }
            }
            }

            // Timestamps
            Section("Observed") {
                InfoRow("First seen") { Text(device.firstSeen, style: .time) }
                InfoRow("Last seen") { Text(device.lastSeen, style: .relative) }
            }
        }
        .navigationTitle(device.ipAddress)
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(trigger: device.portScanFraction == nil) { wasIdle, isIdle in
            !wasIdle && isIdle ? .success : nil
        }
    }

    private func portRow(_ port: DiscoveredPort, web: Bool) -> some View {
        HStack(spacing: 12) {
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
            }
            Image(systemName: "circle.fill")
                .font(.system(size: 8))
                .foregroundStyle(.green)
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

    private func serviceDetail(_ service: ServiceAdvertisement) -> String {
        var parts = [service.name]
        if let model = service.model, !service.name.localizedCaseInsensitiveContains(model) {
            parts.append(model)
        }
        return parts.joined(separator: " · ")
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
