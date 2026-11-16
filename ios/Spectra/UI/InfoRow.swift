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

/// A service on the host card: its kind on top and every value on a line of
/// its own underneath, so long names and models wrap instead of being cut.
struct ServiceBlock: View {
    var title: Text
    var lines: [String?] = []
    /// A machine identifier (hex ID, server banner), monospaced and quieter.
    var identifier: String? = nil
    var detail: String? = nil

    init(title: Text, lines: [String?] = [], identifier: String? = nil, detail: String? = nil) {
        self.title = title
        self.lines = lines
        self.identifier = identifier
        self.detail = detail
    }

    private var shown: [String] {
        lines.compactMap { $0?.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            title
                .font(.body.weight(.medium))
            ForEach(Array(shown.enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            ForEach([detail, identifier].compactMap { $0 }.filter { !$0.isEmpty }, id: \.self) { text in
                Text(text)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 1)
        .accessibilityElement(children: .combine)
    }

    /// "U8000F-66f11bc9458a5a04c86daa67f6b4c556" → ("U8000F", the hex), a
    /// bare hex ID → (nil, the hex), anything else → (the name, nil).
    static func splitIdentifier(_ name: String) -> (name: String?, identifier: String?) {
        func isHexID(_ text: Substring) -> Bool {
            text.count >= 16 && text.allSatisfy(\.isHexDigit)
        }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if isHexID(trimmed[...]) { return (nil, trimmed) }
        if let dash = trimmed.lastIndex(of: "-") {
            let tail = trimmed[trimmed.index(after: dash)...]
            let head = trimmed[..<dash]
            if isHexID(tail), !head.isEmpty { return (String(head), String(tail)) }
        }
        return (trimmed, nil)
    }
}

/// Port numbers as small capsules under a finding.
struct PortChips: View {
    var ports: [Int]

    var body: some View {
        FlowLayout(spacing: 6) {
            Text(ports.count == 1 ? "Port" : "Ports")
                .font(.caption)
                .foregroundStyle(.secondary)
            ForEach(ports, id: \.self) { port in
                Text(verbatim: String(port))
                    .font(.caption.monospacedDigit().weight(.medium))
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(.fill.tertiary, in: .capsule)
            }
        }
    }
}

/// Lays its children out in rows, wrapping to the next row when one is full.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6
    var lineSpacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let width = rows.map(\.width).max() ?? 0
        let height = rows.map(\.height).reduce(0, +) + lineSpacing * CGFloat(max(0, rows.count - 1))
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for index in row.items {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: y + (row.height - size.height) / 2), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + lineSpacing
        }
    }

    private struct Row {
        var items: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var current = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let needed = current.items.isEmpty ? size.width : current.width + spacing + size.width
            if needed > width, !current.items.isEmpty {
                rows.append(current)
                current = Row()
            }
            current.width = current.items.isEmpty ? size.width : current.width + spacing + size.width
            current.height = max(current.height, size.height)
            current.items.append(index)
        }
        if !current.items.isEmpty { rows.append(current) }
        return rows
    }
}

/// Shown in place of autosave when it is off: keeps what is on screen in
/// History, then shows a check mark for a moment.
struct SaveToHistoryButton: View {
    var disabled = false
    var action: () -> Void
    @State private var saves = 0
    @State private var justSaved = false

    var body: some View {
        Button(justSaved ? "Saved" : "Save to History", systemImage: justSaved ? "checkmark" : "square.and.arrow.down") {
            action()
            saves += 1
            justSaved = true
            Task {
                try? await Task.sleep(for: .seconds(2))
                justSaved = false
            }
        }
        .disabled(disabled)
        .sensoryFeedback(.success, trigger: saves)
        .contentTransition(.symbolEffect(.replace))
    }
}
