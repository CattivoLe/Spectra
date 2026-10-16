import SwiftUI

struct DeauthRow: View {
    var event: DeauthEvent

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(color)
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(headline)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                    Text(severityBadge)
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(color, in: .capsule)
                        .fixedSize()
                }
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer(minLength: 0)

            VStack(alignment: .trailing, spacing: 2) {
                Text("×\(event.count)")
                    .font(.callout.monospacedDigit().weight(.semibold))
                    .foregroundStyle(color)
                Text("Ch \(event.channel)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .contextMenu {
            Button("Copy source MAC", systemImage: "doc.on.doc") {
                UIPasteboard.general.string = event.source.description
            }
            Button("Copy target MAC", systemImage: "doc.on.doc") {
                UIPasteboard.general.string = event.target.description
            }
            Button("Copy BSSID", systemImage: "number") {
                UIPasteboard.general.string = event.bssid.description
            }
        }
    }

    private var headline: String {
        if event.isBroadcast {
            return String(localized: "Broadcast deauth")
        }
        return String(localized: "Targeted deauth")
    }

    private var severityBadge: String {
        switch event.severity {
        case .critical: String(localized: "ATTACK")
        case .warning: String(localized: "SUSPICIOUS")
        case .info: String(localized: "INFO")
        }
    }

    private var subtitle: String {
        let target = event.isBroadcast
            ? String(localized: "all devices")
            : event.target.description
        return "\(event.reasonDescription) → \(target)"
    }

    private var icon: String {
        switch event.severity {
        case .critical: "xmark.shield.fill"
        case .warning: "exclamationmark.shield.fill"
        case .info: "shield.fill"
        }
    }

    private var color: Color {
        switch event.severity {
        case .critical: .red
        case .warning: .orange
        case .info: .yellow
        }
    }
}
