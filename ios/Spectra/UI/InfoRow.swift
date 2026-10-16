import SwiftUI

/// A parameter and its value. Side by side when both fit on one line;
/// otherwise the value moves under the title instead of being cut off, which
/// long network names and Russian labels need.
struct InfoRow<Value: View>: View {
    var title: LocalizedStringKey
    var value: Value

    init(_ title: LocalizedStringKey, @ViewBuilder value: () -> Value) {
        self.title = title
        self.value = value()
    }

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                Text(title)
                Spacer(minLength: 12)
                value
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.trailing)
            }
            .lineLimit(1)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                value
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }
}

extension InfoRow where Value == Text {
    init(_ title: LocalizedStringKey, value: String) {
        self.init(title) { Text(value) }
    }
}
