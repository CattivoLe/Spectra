import SwiftUI
import LocalChatEngine
import LocalChatKit

/// The Assistant tab: the journal of summary cards (✨ on a screen makes one), in folders, and the way to the model,
/// internet search and privacy settings. Mirrors AssistantHomeScreen in AssistantScreens.kt.
struct AssistantView: View {
    @Environment(AssistantModel.self) private var model
    @State private var chats: [Chat] = []
    @State private var folders: [ChatFolder] = []
    /// Each saved card's worst finding, for the label of its row.
    @State private var risks: [UUID: CardRisk] = [:]
    /// Summaries whose last text was cut short: they get "Continue".
    @State private var unfinished: Set<UUID> = []
    @State private var path: [UUID] = []
    /// The folder shown; `nil` is every summary.
    @State private var shownFolder: UUID?
    @State private var showModels = false
    @State private var showPrivacy = false
    @State private var showWeb = false
    @State private var confirmErase = false
    /// The folder name dialog: a new folder (optionally to file a summary into) or a rename.
    @State private var naming: FolderNaming?
    @State private var folderName = ""
    @State private var moving: Chat?
    @State private var notice: String?
    @State private var errorText: String?

    private struct FolderNaming: Identifiable {
        var renamed: ChatFolder?
        var fileChat: UUID?
        let id = UUID()
    }

    private var noModel: Bool { model.assistantModelFile == nil }
    private var recording: UUID? {
        if let id = model.recordingFolderID, folders.contains(where: { $0.id == id }) { return id }
        return UserDefaults.standard.string(forKey: "assistant.summariesFolder").flatMap(UUID.init(uuidString:))
    }
    private var folder: ChatFolder? { folders.first { $0.id == shownFolder } }
    private var shown: [Chat] { folder.map { folder in chats.filter { $0.folderID == folder.id } } ?? chats }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if let warning = model.storageWarning {
                    Text(warning).font(.footnote).foregroundStyle(.red)
                }
                if let error = model.loadError {
                    Text("Could not load the model: \(error)").font(.footnote).foregroundStyle(.orange)
                }
                if !noModel {
                    Section {
                        folderStrip
                    } footer: {
                        if !folders.isEmpty {
                            Text("Tap a folder to show its summaries. New summaries go to the highlighted folder; hold another one to save there.")
                        }
                    }
                }
                if !shown.isEmpty {
                    Section {
                        ForEach(shown) { chat in row(chat) }
                            .onDelete { offsets in delete(offsets.map { shown[$0].id }) }
                    } header: {
                        Text(folder?.name ?? String(localized: "Summaries"))
                    } footer: {
                        Text("Hold a row to move it to a folder or delete it.")
                    }
                }
            }
            .overlay { emptyState }
            .navigationTitle("Assistant")
            .navigationDestination(for: UUID.self) { AssistantCardView(id: $0) }
            .toolbar {
                ToolbarItem(placement: .primaryAction) { menu }
            }
            .safeAreaInset(edge: .bottom) { modelStatus }
            .alert("Error", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                Button("OK") { errorText = nil }
            } message: { Text(errorText ?? "") }
            .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
                Button("OK") { notice = nil }
            }
            .alert(naming?.renamed == nil ? String(localized: "New folder") : String(localized: "Rename folder"),
                   isPresented: Binding(get: { naming != nil }, set: { if !$0 { naming = nil } })) {
                TextField("Name", text: $folderName)
                Button("Save") { saveFolder() }
                    .disabled(folderName.trimmingCharacters(in: .whitespaces).isEmpty)
                Button("Cancel", role: .cancel) { naming = nil }
            }
            .confirmationDialog("Delete all summaries?", isPresented: $confirmErase, titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    shownFolder = nil
                    Task {
                        do { try await model.eraseChats() } catch { errorText = error.localizedDescription }
                    }
                }
            } message: {
                Text("Every summary and folder is erased from this phone. Models and settings stay.")
            }
            .sheet(item: $moving) { chat in
                MoveToFolderSheet(chat: chat, folders: folders) { target in
                    moving = nil
                    Task { try? await model.store.moveChat(chat.id, to: target) }
                } onNewFolder: {
                    moving = nil
                    startNaming(FolderNaming(renamed: nil, fileChat: chat.id))
                }
            }
            .sheet(isPresented: $showModels) { AssistantModelsView().environment(model) }
            .sheet(isPresented: $showPrivacy) { AssistantPrivacyView().environment(model) }
            .sheet(isPresented: $showWeb) { AssistantWebSheet().environment(model) }
            .refreshable { model.refreshModels() }
            .task {
                // The model is loaded when the tab is first opened, not at launch: it takes gigabytes of memory.
                await model.ensureStarted()
            }
            .task {
                do {
                    for try await list in model.store.observeChats() { await reload(list) }
                } catch {
                    errorText = error.localizedDescription
                }
            }
            .task {
                do {
                    for try await list in model.store.observeFolders() {
                        folders = list
                        if let shownFolder, !list.contains(where: { $0.id == shownFolder }) { self.shownFolder = nil }
                    }
                } catch {}
            }
        }
    }

    // MARK: - Pieces

    /// The folders as chips above the summaries, with the count in each and a way to add one. The folder new
    /// summaries go to is filled; the one shown below has an outline.
    private var folderStrip: some View {
        let counts = Dictionary(grouping: chats, by: \.folderID).mapValues(\.count)
        return FlowLayout(spacing: 8, lineSpacing: 8) {
            ForEach(folders) { folder in
                let active = folder.id == recording
                let open = folder.id == shownFolder
                HStack(spacing: 6) {
                    Image(systemName: open ? "folder.fill" : "folder")
                    Text(folder.name).lineLimit(1)
                    Text("\(counts[folder.id] ?? 0)").foregroundStyle(active ? .white.opacity(0.75) : .secondary)
                }
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .foregroundStyle(active ? .white : .primary)
                .background(active ? AnyShapeStyle(.tint) : AnyShapeStyle(.fill.tertiary), in: .capsule)
                .overlay { if open { Capsule().strokeBorder(.tint, lineWidth: 1.5) } }
                .contentShape(.capsule)
                .onTapGesture { shownFolder = open ? nil : folder.id }
                .onLongPressGesture {
                    model.record(into: folder.id)
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    notice = String(localized: "New summaries are saved to «\(folder.name)»")
                }
                .contextMenu {
                    Button("Save new summaries here", systemImage: "record.circle") { model.record(into: folder.id) }
                    Button("Rename folder", systemImage: "pencil") { startNaming(FolderNaming(renamed: folder)) }
                    Button("Delete folder", systemImage: "folder.badge.minus", role: .destructive) {
                        Task { try? await model.store.deleteFolder(folder.id) }
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .accessibilityValue(Text(active ? String(localized: "New summaries are saved here") : ""))
            }
            Button {
                startNaming(FolderNaming())
            } label: {
                Image(systemName: "folder.badge.plus")
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(.fill.quaternary, in: .capsule)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("New folder")
        }
        .padding(.vertical, 4)
    }

    /// One summary: its title on two lines, the worst finding as a coloured label and when it was made.
    private func row(_ chat: Chat) -> some View {
        let risk = risks[chat.id] ?? .ok
        let stopped = unfinished.contains(chat.id) || model.cards?.isStopped(chat.id) == true
        return NavigationLink(value: chat.id) {
            VStack(alignment: .leading, spacing: 4) {
                Text(chat.title.isEmpty ? String(localized: "Summary") : chat.title)
                    .font(.body.weight(.medium))
                    .lineLimit(2)
                HStack(spacing: 6) {
                    Text(risk.label)
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .foregroundStyle(risk.color)
                        .background(risk.color.opacity(0.15), in: .capsule)
                    Text(chat.updatedAt, format: .relative(presentation: .named))
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                // Stopped in the middle of a text: one tap opens it and picks the text up.
                if stopped {
                    Button("Continue", systemImage: "play.fill") {
                        model.cards?.continueOnOpen(chat.id)
                        path.append(chat.id)
                    }
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            }
            .padding(.vertical, 2)
        }
        .contextMenu {
            Button("Move to folder…", systemImage: "folder") { moving = chat }
            Button("Delete", systemImage: "trash", role: .destructive) { delete([chat.id]) }
        }
    }

    private var menu: some View {
        Menu {
            if let folder {
                Button("Rename folder", systemImage: "pencil") { startNaming(FolderNaming(renamed: folder)) }
                Button("Delete folder", systemImage: "folder.badge.minus", role: .destructive) {
                    shownFolder = nil
                    Task { try? await model.store.deleteFolder(folder.id) }
                }
                Divider()
            }
            // One model, no picker: this only leads to its page (download, delete, free up space).
            Button("Model…", systemImage: "cpu") { showModels = true }
            Button("Internet…", systemImage: "globe") { showWeb = true }
            Button("Privacy…", systemImage: "hand.raised") { showPrivacy = true }
            if !chats.isEmpty {
                Divider()
                Button("Delete all summaries", systemImage: "trash", role: .destructive) { confirmErase = true }
            }
        } label: {
            Label("More", systemImage: "ellipsis.circle")
        }
    }

    @ViewBuilder
    private var emptyState: some View {
        if noModel {
            ContentUnavailableView {
                Label("No model yet", systemImage: "sparkles")
            } description: {
                Text("The assistant runs a language model on this phone: nothing you ask leaves it. Download it once to start.")
            } actions: {
                Button("Download the model") { showModels = true }.buttonStyle(.borderedProminent)
            }
        } else if let folder, shown.isEmpty {
            ContentUnavailableView {
                Label("The folder is empty", systemImage: "folder")
            } description: {
                Text(folder.id == recording ? "New ✨ summaries will be saved here." : "Hold a summary in the list and choose Move to folder.")
            }
        } else if chats.isEmpty {
            ContentUnavailableView {
                Label("No summaries yet", systemImage: "sparkles")
            } description: {
                Text("Tap ✨ on a Wi‑Fi network, the LAN list, a device, an internet check or recorded traffic: the findings show at once, and the model on this phone puts them into plain words.")
            }
        }
    }

    @ViewBuilder
    private var modelStatus: some View {
        let text: String? = switch model.controller.state {
        case .loading: String(localized: "Loading the model…")
        case .unloadedForMemory: String(localized: "The model was unloaded to free memory and will load again with the next summary")
        default: nil
        }
        if let text {
            Text(text)
                .font(.footnote).foregroundStyle(.secondary)
                .padding(8).frame(maxWidth: .infinity).background(.bar)
        }
    }

    // MARK: - Actions

    /// Only summaries: chats from before the cards are erased at start, and are not shown meanwhile.
    private func reload(_ list: [Chat]) async {
        let summaries = list.filter { CardSubjects.isCard($0.subject) }
        var risks: [UUID: CardRisk] = [:]
        var unfinished: Set<UUID> = []
        for chat in summaries {
            let messages = (try? await model.store.messages(in: chat.id)) ?? []
            if let head = messages.first(where: { $0.role == .user }), let parsed = AssistantCards.parse(head.content) {
                risks[chat.id] = AssistantCards.worst(parsed.findings)
            }
            if let last = messages.last, last.role == .assistant, last.interrupted { unfinished.insert(chat.id) }
        }
        chats = summaries
        self.risks = risks
        self.unfinished = unfinished
    }

    private func startNaming(_ request: FolderNaming) {
        // A new folder is named by its date and time unless the user writes something else.
        folderName = request.renamed?.name ?? Date.now.formatted(.dateTime.day().month(.wide).hour().minute())
        naming = request
    }

    private func saveFolder() {
        guard let request = naming else { return }
        naming = nil
        let name = String(folderName.trimmingCharacters(in: .whitespaces).prefix(60))
        Task {
            if let renamed = request.renamed {
                try? await model.store.renameFolder(renamed.id, to: name)
            } else if let created = await model.startFolder(named: name) {
                // From now on new summaries are saved in the new folder.
                if let chat = request.fileChat {
                    try? await model.store.moveChat(chat, to: created)
                } else {
                    shownFolder = created
                }
            }
        }
    }

    private func delete(_ ids: [UUID]) {
        Task {
            for id in ids { try? await model.deleteChat(id) }
        }
    }
}

/// Where to put one summary: no folder, an existing one, or a new one.
private struct MoveToFolderSheet: View {
    let chat: Chat
    let folders: [ChatFolder]
    let onMove: (UUID?) -> Void
    let onNewFolder: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                option(String(localized: "No folder"), symbol: "folder.badge.minus", selected: chat.folderID == nil) { onMove(nil) }
                ForEach(folders) { folder in
                    option(folder.name, symbol: "folder", selected: chat.folderID == folder.id) { onMove(folder.id) }
                }
                Button("New folder…", systemImage: "folder.badge.plus", action: onNewFolder)
            }
            .navigationTitle("Move to folder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func option(_ title: String, symbol: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Label(title, systemImage: symbol).foregroundStyle(.primary)
                Spacer()
                if selected { Image(systemName: "checkmark").foregroundStyle(.tint) }
            }
        }
    }
}
