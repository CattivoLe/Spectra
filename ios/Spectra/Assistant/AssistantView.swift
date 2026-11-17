import SwiftUI
import LocalChatEngine
import LocalChatKit

/// The Assistant tab: the list of chats, with model, internet and privacy settings in the menu.
struct AssistantView: View {
    @Environment(AssistantModel.self) private var model
    @State private var chats: [Chat] = []
    @State private var path: [Chat] = []
    @State private var showModels = false
    @State private var showPrivacy = false
    @State private var showWeb = false
    @State private var errorText: String?
    @State private var started = false

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if let warning = model.storageWarning {
                    Text(warning).font(.footnote).foregroundStyle(.red)
                }
                ForEach(chats) { chat in
                    NavigationLink(value: chat) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(chat.title.isEmpty ? String(localized: "New chat") : chat.title).lineLimit(1)
                            if let modelName = chat.modelName {
                                Label(modelName, systemImage: "cpu").labelStyle(.titleAndIcon)
                                    .font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Text(chat.updatedAt, format: .relative(presentation: .named))
                                .font(.caption).foregroundStyle(.tertiary)
                        }
                    }
                }
                .onDelete(perform: delete)
            }
            .overlay {
                if chats.isEmpty {
                    ContentUnavailableView {
                        Label("No chats yet", systemImage: "bubble.left.and.bubble.right")
                    } description: {
                        Text(model.modelFiles.isEmpty
                             ? "The assistant runs on this phone. Download a model first, then ask it about the networks and devices Spectra has found."
                             : "Tap the compose button to start. The assistant can read what Spectra has found and runs entirely on this phone.")
                    } actions: {
                        if model.modelFiles.isEmpty {
                            Button("Get a model") { showModels = true }
                                .buttonStyle(.borderedProminent)
                        } else {
                            Button("New chat", systemImage: "square.and.pencil", action: newChat)
                                .buttonStyle(.borderedProminent)
                        }
                    }
                }
            }
            .navigationTitle("Assistant")
            .navigationDestination(for: Chat.self) { chat in
                AssistantChatView(session: model.session(for: chat))
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { modelMenu }
                ToolbarItem(placement: .primaryAction) {
                    Button("New chat", systemImage: "square.and.pencil", action: newChat)
                }
            }
            .safeAreaInset(edge: .bottom) { modelStatus }
            .alert("Error", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                Button("OK") { errorText = nil }
            } message: { Text(errorText ?? "") }
            .sheet(isPresented: $showModels) { AssistantModelsView().environment(model) }
            .sheet(isPresented: $showPrivacy) { AssistantPrivacyView().environment(model) }
            .sheet(isPresented: $showWeb) { AssistantWebSheet().environment(model) }
            .refreshable { model.refreshModels() }
            .task {
                // The model is loaded when the tab is first opened, not at launch: it takes gigabytes of memory.
                guard !started else { return }
                started = true
                await model.start()
            }
            .task {
                do {
                    for try await list in model.store.observeChats() { chats = list }
                } catch {
                    errorText = error.localizedDescription
                }
            }
        }
    }

    private var modelMenu: some View {
        Menu {
            Section("Model") {
                ForEach(model.library.installed) { item in
                    Button {
                        Task { await model.select(item.fileName) }
                    } label: {
                        if item.fileName == model.selectedModel { Label(item.menuName, systemImage: "checkmark") } else { Text(item.menuName) }
                    }
                }
                if model.modelFiles.isEmpty { Text("No models") }
            }
            Button("Manage models…", systemImage: "square.and.arrow.down") { showModels = true }
            Button("Internet…", systemImage: "globe") { showWeb = true }
            Button("Privacy…", systemImage: "hand.raised") { showPrivacy = true }
        } label: {
            Label("Model", systemImage: "cpu")
        }
    }

    @ViewBuilder
    private var modelStatus: some View {
        let text: String? = switch model.controller.state {
        case .loading: String(localized: "Loading the model…")
        case .unloadedForMemory: String(localized: "The model was unloaded to free memory and will load again with the next message")
        case .empty where model.modelFiles.isEmpty: nil
        default: model.loadError
        }
        if let text {
            Text(text)
                .font(.footnote).foregroundStyle(.secondary)
                .padding(8).frame(maxWidth: .infinity).background(.bar)
        }
    }

    private func newChat() {
        Task {
            do {
                path.append(try await model.store.createChat())
            } catch {
                errorText = error.localizedDescription
            }
        }
    }

    private func delete(at offsets: IndexSet) {
        let ids = offsets.map { chats[$0].id }
        Task {
            for id in ids { try? await model.store.deleteChat(id) }
        }
    }
}
