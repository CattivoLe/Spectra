import SwiftUI
import LocalChatKit

/// The model's "memory gauge": how much of its context the chat already fills.
/// When it is full the oldest messages stop reaching the model: it forgets them.
struct AssistantContextGauge: View {
    let usage: ContextUsage
    @State private var showInfo = false

    private var tint: Color {
        if usage.isOverflowing || usage.fraction > 0.85 { return .red }
        if usage.fraction > 0.6 { return .orange }
        return .green
    }

    var body: some View {
        Button { showInfo = true } label: {
            HStack(spacing: 8) {
                Image(systemName: "thermometer.medium").font(.caption).foregroundStyle(tint)
                ProgressView(value: usage.fraction).tint(tint)
                Text("\(usage.used.formatted()) of \(usage.total.formatted())")
                    .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
            }
            .padding(.horizontal).padding(.vertical, 6)
            .background(.bar)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Chat memory is \(Int(usage.fraction * 100)) percent full")
        .popover(isPresented: $showInfo) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Chat memory").font(.headline)
                Text("The model only “remembers” the last \(usage.total.formatted()) tokens of the conversation (a token is roughly a syllable). \(usage.used.formatted()) are used now. Spectra data attached to a question counts too.")
                Text(usage.isOverflowing
                     ? "The memory is full: the oldest messages no longer reach the model and are ignored. Start a new chat to bring them back."
                     : "When the bar fills up, the oldest messages stop being taken into account.")
                    .foregroundStyle(usage.isOverflowing ? .red : .secondary)
            }
            .font(.callout).padding().frame(idealWidth: 300).presentationCompactAdaptation(.popover)
        }
    }
}
