import SwiftUI

/// The ✨ toolbar button: opens a summary card about what is on screen, right here, in the same navigation stack.
/// The findings show at once; the model on this phone puts them into plain words. `prepare` runs first, to put
/// whatever the card is about where it is read (the last capture, the recorded traffic). Needs a `NavigationStack`
/// around it.
struct SummaryButtonModifier: ViewModifier {
    var isShown: Bool
    var placement: ToolbarItemPlacement
    var subject: () -> CardSubject?
    var prepare: () -> Void

    @Environment(AssistantModel.self) private var assistant
    @State private var cardID: UUID?
    @State private var opening = false
    @State private var nothing = false

    func body(content: Content) -> some View {
        content
            .toolbar {
                if isShown {
                    ToolbarItem(placement: placement) {
                        Button("Summary", systemImage: "sparkles", action: open)
                            .disabled(opening)
                            .accessibilityHint("Opens a summary of what is on this screen from the on-device assistant")
                    }
                }
            }
            .navigationDestination(item: $cardID) { AssistantCardView(id: $0) }
            .alert("There is nothing to summarize for it now.", isPresented: $nothing) { Button("OK") {} }
    }

    private func open() {
        prepare()
        guard let subject = subject(), let cards = assistant.cards else {
            nothing = true
            return
        }
        opening = true
        Task {
            if let id = await cards.open(subject) { cardID = id } else { nothing = true }
            opening = false
        }
    }
}

extension View {
    /// See `SummaryButtonModifier`.
    func summaryButton(isShown: Bool = true, placement: ToolbarItemPlacement = .topBarTrailing,
                       subject: @escaping () -> CardSubject?, prepare: @escaping () -> Void = {}) -> some View {
        modifier(SummaryButtonModifier(isShown: isShown, placement: placement, subject: subject, prepare: prepare))
    }
}
