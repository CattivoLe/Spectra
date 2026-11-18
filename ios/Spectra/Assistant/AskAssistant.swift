import SwiftUI
import LocalChatKit

/// The ✨ toolbar button that opens a new assistant chat right here, in the same navigation stack, with a question
/// already asked about what is on screen. `prepare` runs first, to put whatever the question is about where the
/// assistant reads it (the last capture, the recorded traffic, the last GATT read). Needs a `NavigationStack` around it.
struct AskAssistantModifier: ViewModifier {
    var isShown: Bool
    var placement: ToolbarItemPlacement
    var question: () -> String
    var prepare: () -> Void

    @Environment(AssistantModel.self) private var assistant
    @State private var chat: Chat?
    @State private var error: String?

    func body(content: Content) -> some View {
        content
            .toolbar {
                if isShown {
                    ToolbarItem(placement: placement) {
                        Button("Ask the assistant", systemImage: "sparkles", action: ask)
                            .accessibilityHint("Opens a chat with the on-device assistant about what is on this screen")
                    }
                }
            }
            .navigationDestination(item: $chat) { chat in
                AssistantChatView(session: assistant.session(for: chat))
            }
            .alert("Error", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("OK") { error = nil }
            } message: { Text(error ?? "") }
    }

    private func ask() {
        prepare()
        let text = question()
        Task {
            do {
                let created = try await assistant.store.createChat()
                assistant.queue(text, for: created.id)
                chat = created
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

extension View {
    /// See `AskAssistantModifier`.
    func askAssistant(isShown: Bool = true, placement: ToolbarItemPlacement = .topBarTrailing,
                      question: @escaping () -> String, prepare: @escaping () -> Void = {}) -> some View {
        modifier(AskAssistantModifier(isShown: isShown, placement: placement, question: question, prepare: prepare))
    }
}
