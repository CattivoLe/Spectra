import SwiftUI

struct ProbeDeviceRow: View {
    var device: ProbingDevice

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
                if device.ssids.isEmpty {
                    Text("Broadcast probe · Ch \(device.channel)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    FlowText(ssids: device.ssids)
                }
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

/// Shows SSIDs as inline comma-separated text with an icon.
private struct FlowText: View {
    var ssids: [String]

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "magnifyingglass.circle.fill")
                .font(.caption)
                .foregroundStyle(.orange.opacity(0.7))
            Text(ssids.joined(separator: ", "))
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
    }
}
