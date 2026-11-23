import SwiftUI
import UIKit

/// A body as text: its start, coloured when it is JSON, and a way to the whole of it, where it can be searched.
struct BodyTextPreview: View {
    let text: String
    /// What the full-screen text is called.
    var title: LocalizedStringKey
    @State private var copied = 0

    private static let limit = 3_000

    var body: some View {
        let cut = text.count > Self.limit
        Text(Self.styled(cut ? String(text.prefix(Self.limit)) : text))
            .font(.caption.monospaced())
            .textSelection(.enabled)
        NavigationLink {
            BodyTextScreen(text: text, title: title)
        } label: {
            Label(cut ? "Show all and search" : "Open full screen", systemImage: "doc.text.magnifyingglass")
        }
        Button {
            UIPasteboard.general.string = text
            copied += 1
        } label: {
            Label("Copy", systemImage: "doc.on.doc")
        }
        .sensoryFeedback(.success, trigger: copied)
    }

    private static func styled(_ text: String) -> AttributedString {
        var styled = AttributedString(text)
        guard JSONSyntax.looksLikeJSON(text) else { return styled }
        for span in JSONSyntax.spans(text) {
            guard let range = Range(span.range, in: styled) else { continue }
            styled[range].foregroundColor = Color(uiColor: span.kind.color)
        }
        return styled
    }
}

/// The whole text on its own screen, with the system's find bar: matches are counted, highlighted and stepped through.
struct BodyTextScreen: View {
    let text: String
    let title: LocalizedStringKey
    @State private var styled: NSAttributedString?
    @State private var finds = 0
    @State private var copied = 0

    /// What a screen can carry, in UTF-16 units.
    private static let limit = 1_000_000

    var body: some View {
        Group {
            if let styled {
                SearchableText(text: styled, finds: finds)
                    .ignoresSafeArea(.container, edges: .bottom)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .safeAreaInset(edge: .top) {
            if text.utf16.count > Self.limit {
                Text("The first \(String(localized: "\(Self.limit) characters")) are shown.")
                    .font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(6)
                    .background(.bar)
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Copy", systemImage: "doc.on.doc") {
                    UIPasteboard.general.string = text
                    copied += 1
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Find in text", systemImage: "magnifyingglass") { finds += 1 }
                    .disabled(styled == nil)
            }
        }
        .sensoryFeedback(.success, trigger: copied)
        .task {
            let text = text
            styled = await Task.detached(priority: .userInitiated) { Self.styled(text) }.value.text
        }
    }

    /// `NSAttributedString` is immutable here, only its class is not marked so.
    private nonisolated struct Built: @unchecked Sendable { let text: NSAttributedString }

    private nonisolated static func styled(_ text: String) -> Built {
        let units = text.utf16
        let shown = units.count > limit ? String(decoding: units.prefix(limit), as: UTF16.self) : text
        let built = NSMutableAttributedString(string: shown, attributes: [
            .font: UIFont.monospacedSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .caption1).pointSize, weight: .regular),
            .foregroundColor: UIColor.label,
        ])
        if JSONSyntax.looksLikeJSON(shown) {
            for span in JSONSyntax.spans(shown) {
                built.addAttribute(.foregroundColor, value: span.kind.color, range: span.range)
            }
        }
        return Built(text: built)
    }
}

/// A read-only text view: SwiftUI's `Text` draws a long body too slowly and cannot be searched.
private struct SearchableText: UIViewRepresentable {
    let text: NSAttributedString
    /// Counts up when the find bar is asked for.
    let finds: Int

    final class Coordinator { var finds = 0 }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.isEditable = false
        view.isFindInteractionEnabled = true
        view.alwaysBounceVertical = true
        view.backgroundColor = .systemBackground
        view.textContainerInset = UIEdgeInsets(top: 12, left: 12, bottom: 24, right: 12)
        view.attributedText = text
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        if view.attributedText !== text, view.attributedText.string != text.string { view.attributedText = text }
        guard finds != context.coordinator.finds else { return }
        context.coordinator.finds = finds
        view.findInteraction?.presentFindNavigator(showingReplace: false)
    }
}

extension JSONSyntax.Kind {
    nonisolated var color: UIColor {
        switch self {
        case .key: Self.dynamic(light: 0x0550AE, dark: 0x79C0FF)
        case .string: Self.dynamic(light: 0x116329, dark: 0x7EE787)
        case .number: Self.dynamic(light: 0x953800, dark: 0xFFA657)
        case .literal: Self.dynamic(light: 0x8250DF, dark: 0xD2A8FF)
        }
    }

    private nonisolated static func dynamic(light: Int, dark: Int) -> UIColor {
        UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255,
                           blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        }
    }
}
