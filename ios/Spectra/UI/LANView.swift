import SwiftUI

struct LANView: View {
    @Environment(LANScanner.self) private var scanner
    @State private var search = ""

    private var visible: [LANDevice] {
        let query = search.trimmingCharacters(in: .whitespaces)
        return scanner.sortedDevices.filter { device in
            query.isEmpty
                || device.ipAddress.localizedStandardContains(query)
                || device.displayName.localizedStandardContains(query)
                || (device.macAddress?.description ?? "").localizedStandardContains(query)
                || (device.manufacturer ?? "").localizedStandardContains(query)
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    networkInfoCard
                }

                if !visible.isEmpty {
                    Section {
                        ForEach(visible) { device in
                            NavigationLink(value: device.ipAddress) {
                                DeviceRow(device: device)
                            }
                        }
                    } header: {
                        Text("\(scanner.devices.count) devices")
                    }
                }
            }
            .overlay {
                if scanner.devices.isEmpty, !scanner.isScanning {
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
                } else if visible.isEmpty, !search.isEmpty {
                    ContentUnavailableView.search(text: search)
                }
            }
            .searchable(text: $search, prompt: Text("IP, name or MAC"))
            .refreshable { await runScan() }
            .navigationTitle("LAN")
            .navigationDestination(for: String.self) { ip in
                DeviceDetailView(ip: ip)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Scan now", systemImage: "arrow.clockwise", action: scanner.scan)
                            .disabled(scanner.isScanning)
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

    private func runScan() async {
        scanner.scan()
        while scanner.isScanning {
            try? await Task.sleep(for: .milliseconds(200))
        }
    }

    // MARK: - Network info card

    @ViewBuilder private var networkInfoCard: some View {
        HStack(spacing: 12) {
            Image(systemName: "network")
                .font(.title2)
                .foregroundStyle(scanner.subnetInfo != nil ? .teal : .secondary)
                .frame(width: 36)

            VStack(alignment: .leading, spacing: 2) {
                if let info = scanner.subnetInfo {
                    Text(info.localIP)
                        .font(.headline)
                        .monospacedDigit()
                    if let gw = info.gateway {
                        Text("gateway \(gw)")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                            .lineLimit(1)
                    }
                    Text(subnetSummary(info))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                } else if case .noNetwork = scanner.state {
                    Text("Not connected")
                        .font(.headline)
                    Text("Join a Wi-Fi network first")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    Text("Local network")
                        .font(.headline)
                    Text("Tap Scan to find devices")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: 0)

            if scanner.isScanning {
                ProgressView()
            } else {
                Button {
                    scanner.scan()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.circle)
                .accessibilityLabel("Scan")
            }
        }
        .padding(.vertical, 4)

        if case .scanning(let progress, let phase) = scanner.state {
            VStack(alignment: .leading, spacing: 4) {
                ProgressView(value: progress)
                    .tint(.teal)
                Text(phase)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func subnetSummary(_ info: SubnetInfo) -> String {
        var parts: [String] = ["/\(info.cidr)"]
        parts.append(String(localized: "\(info.hostCount) hosts"))
        if let date = scanner.lastScanDate {
            parts.append(date.formatted(.relative(presentation: .named)))
        }
        return parts.joined(separator: " · ")
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
        if let mfr = device.manufacturer, device.displayName != mfr {
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
