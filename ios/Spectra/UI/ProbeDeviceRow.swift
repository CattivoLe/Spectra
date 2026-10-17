import SwiftUI

struct ProbeDeviceRow: View {
    var device: ProbingDevice

    @Environment(LANScanner.self) private var scanner

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: deviceIcon)
                .font(.title3)
                .foregroundStyle(.orange)
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(device.displayName)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                    if device.mac.isLocallyAdministered {
                        Text("Random")
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(.purple, in: .capsule)
                            .fixedSize()
                    }
                }
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer(minLength: 0)

            Text("\(device.rssi)")
                .font(.callout.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .contextMenu {
            Button("Copy MAC", systemImage: "doc.on.doc") {
                UIPasteboard.general.string = device.mac.description
            }
            if !device.ssids.isEmpty {
                Button("Copy SSIDs", systemImage: "list.clipboard") {
                    UIPasteboard.general.string = device.ssids.joined(separator: ", ")
                }
            }
        }
    }

    private var subtitle: String {
        var parts = ["Ch \(device.channel)"]
        if device.ssids.isEmpty {
            parts.append(String(localized: "broadcast"))
        } else {
            parts.append(device.ssids.joined(separator: ", "))
        }
        if let host = lanHost, host.displayName != device.displayName {
            parts.append(String(localized: "on LAN \(host.displayName)"))
        }
        return parts.joined(separator: " · ")
    }

    private var lanHost: LANDevice? {
        let address = device.mac.description
        return scanner.devices.values.first { $0.macAddress?.description == address }
    }

    private var deviceIcon: String {
        let name = device.displayName.lowercased()
        if name.contains("apple") || name.contains("iphone") { return "iphone" }
        if name.contains("ipad") { return "ipad" }
        if name.contains("mac") || name.contains("book") { return "laptopcomputer" }
        if name.contains("samsung") || name.contains("xiaomi") || name.contains("huawei") { return "smartphone" }
        if name.contains("amazon") || name.contains("echo") { return "homepod.fill" }
        if name.contains("google") || name.contains("chromecast") { return "tv" }
        if name.contains("raspberry") || name.contains("espressif") { return "cpu" }
        if device.mac.isLocallyAdministered { return "questionmark.circle" }
        return "antenna.radiowaves.left.and.right"
    }
}
