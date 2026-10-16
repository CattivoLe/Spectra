import SwiftUI

/// A single Wi-Fi client (station) in a list.
struct ClientRow: View {
    var client: WiFiClient

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
        client.mac.description
    }
}
