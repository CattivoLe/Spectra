import SwiftUI
import LocalChatEngine
import LocalChatKit

struct AssistantChatView: View {
    @Environment(AssistantModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @State private var session: ChatSession
    @State private var draft = ""
    @State private var editing: StoredMessage?
    @State private var wasGeneratingBeforeBackground = false
    @FocusState private var composerFocused: Bool

    init(session: ChatSession) {
        _session = State(initialValue: session)
    }

    /// How many different models answered in this chat: with more than one, every answer is labelled.
    private var modelsInChat: Int {
        Set(session.messages.filter { $0.role == .assistant }.compactMap(\.modelName)).count
    }

    private var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        && model.controller.state != .loading
        && model.controller.state != .empty
    }

    /// Questions to start from, for an empty chat. Chosen from what the app already has — see `AssistantStarters`.
    @State private var starters: [String] = []

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                if session.messages.isEmpty && !session.isGenerating {
                    startersList
                }
                ForEach(Array(session.messages.enumerated()), id: \.element.id) { index, message in
                    let question = index > 0 ? session.messages[index - 1] : nil
                    AssistantMessageRow(
                        message: message,
                        sources: question.flatMap { message.role == .assistant ? $0.search : nil },
                        appData: question.flatMap { message.role == .assistant ? session.attachments[$0.id] : nil },
                        toolsUsed: message.role == .assistant ? session.toolRuns[message.id] : nil,
                        showModel: modelsInChat > 1,
                        isLast: message.id == session.messages.last?.id,
                        isGenerating: session.isGenerating,
                        onEdit: { beginEditing(message) },
                        onRegenerate: { session.regenerate() },
                        onContinue: { session.continueGenerating() }
                    )
                }
                if session.isSearching {
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.mini)
                        Text(session.searchStage?.title ?? String(localized: "Searching the web…"))
                    }
                    .font(.footnote).foregroundStyle(.secondary)
                }
                if let status = session.toolStatus {
                    Label(status, systemImage: "bolt.horizontal").font(.footnote).foregroundStyle(.secondary)
                }
                if let reused = session.searchReused {
                    Label(reused, systemImage: "arrow.uturn.backward.circle").font(.footnote).foregroundStyle(.secondary)
                }
                if let notice = session.searchNotice {
                    Label(notice, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.orange)
                }
                if let error = session.errorText {
                    Text(error).font(.footnote).foregroundStyle(.red)
                }
            }
            .padding()
        }
        .defaultScrollAnchor(.bottom)
        .defaultScrollAnchor(.bottom, for: .sizeChanges)
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle(session.chat.title.isEmpty ? String(localized: "New chat") : session.chat.title)
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) { composer }
        .safeAreaInset(edge: .top, spacing: 0) {
            if let usage = session.context { AssistantContextGauge(usage: usage) }
        }
        .task {
            session.modelName = model.currentModelName
            starters = AssistantStarters.questions(for: model.actions?.sources.readiness() ?? .init())
            await session.load()
            if let question = model.takeQueuedQuestion(for: session.chat.id) { await ask(question) }
        }
        // Model switched or loaded: label the answers with the new model and recount the memory in use.
        .onChange(of: model.selectedModel) { session.modelName = model.currentModelName }
        .onChange(of: model.controller.state) { _, new in
            if new == .ready { Task { await session.refreshContext() } }
            if new == .empty, model.loadError == nil { Task { await model.loadSelectedModel() } }
        }
        .toolbar {
            ToolbarItem(placement: .principal) { titleMenu }
        }
        .sheet(item: Binding(get: { model.pendingSearch }, set: { new in
            // Closed without choosing counts as a refusal: nothing is sent.
            if new == nil { model.pendingSearch?.answer(nil) }
            model.pendingSearch = new
        })) { pending in
            AssistantSearchApprovalSheet(pending: pending, provider: model.web.provider.title)
        }
        // Generating for a screen nobody sees would hold the only model.
        .onDisappear { session.stop() }
        .onChange(of: scenePhase) { _, new in
            if new != .active { wasGeneratingBeforeBackground = session.isGenerating }
            if new == .active && wasGeneratingBeforeBackground {
                wasGeneratingBeforeBackground = false
                session.continueGenerating()
            }
        }
    }

    private var startersList: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Ask a question, or start a check", systemImage: "sparkle.magnifyingglass")
                .font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
            ForEach(starters, id: \.self) { text in
                Button {
                    session.send(text)
                } label: {
                    Text(text)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 10)
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                .disabled(model.controller.state == .loading || model.controller.state == .empty)
            }
            if model.controller.state == .empty {
                Text(model.modelFiles.isEmpty ? "Download a model from the Assistant tab's menu first." : "The model is not loaded yet.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding(.top, 8)
    }

    /// The chat title and the model under it. Tapping opens a quick model picker.
    private var titleMenu: some View {
        VStack(spacing: 0) {
            Text(session.chat.title.isEmpty ? String(localized: "New chat") : session.chat.title)
                .font(.headline).lineLimit(1)
            Menu {
                Section("Model") {
                    Picker("Model", selection: Binding(
                        get: { model.selectedModel ?? "" },
                        set: { file in Task { await model.select(file) } }
                    )) {
                        ForEach(model.library.chatModels) { item in
                            Text(item.menuName).tag(item.fileName)
                        }
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "cpu")
                    Text(modelLabel)
                    Image(systemName: "chevron.down").font(.system(size: 8, weight: .bold))
                }
                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            .disabled(session.isGenerating)
        }
    }

    private var modelLabel: String {
        switch model.controller.state {
        case .loading: String(localized: "loading…")
        default: model.currentModelMenuName ?? String(localized: "no model")
        }
    }

    private var composer: some View {
        VStack(spacing: 6) {
            if editing != nil {
                HStack {
                    Label("Editing message", systemImage: "pencil").font(.footnote).foregroundStyle(.secondary)
                    Spacer()
                    Button("Cancel") { editing = nil; draft = "" }.font(.footnote)
                }
            }
            HStack(alignment: .bottom, spacing: 8) {
                TextField("Message", text: $draft, axis: .vertical)
                    .lineLimit(1...6)
                    .focused($composerFocused)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))

                if session.isGenerating {
                    Button("Stop", systemImage: "stop.circle.fill") { session.stop() }
                        .labelStyle(.iconOnly).font(.system(size: 32))
                } else {
                    Button("Send", systemImage: "arrow.up.circle.fill", action: submit)
                        .labelStyle(.iconOnly).font(.system(size: 32))
                        .disabled(!canSend)
                }
            }
        }
        .padding(.horizontal).padding(.vertical, 8)
        .background(.bar)
    }

    /// Puts a question another screen prepared (e.g. "decode this capture") to the model once it is loaded.
    /// With no model installed it waits in the message field instead.
    private func ask(_ question: String) async {
        await model.ensureStarted()
        while model.controller.state == .loading, !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(200))
        }
        session.modelName = model.currentModelName
        if model.controller.state == .empty {
            draft = question
        } else {
            session.send(question)
        }
    }

    private func submit() {
        let text = draft
        draft = ""
        if let editing {
            session.edit(editing.id, to: text)
            self.editing = nil
        } else {
            session.send(text)
        }
    }

    private func beginEditing(_ message: StoredMessage) {
        editing = message
        draft = message.content
        composerFocused = true
    }
}

private struct AssistantMessageRow: View {
    let message: StoredMessage
    /// What was found on the web for the question this answers.
    let sources: SearchPayload?
    /// What Spectra data was read for the question this answers.
    let appData: AppDataAttachment?
    /// Live checks the model ran itself before this answer, in the order they ran.
    let toolsUsed: [String]?
    let showModel: Bool
    let isLast: Bool
    let isGenerating: Bool
    let onEdit: () -> Void
    let onRegenerate: () -> Void
    let onContinue: () -> Void

    var body: some View {
        switch message.role {
        case .user:
            Text(message.content)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(.tint, in: RoundedRectangle(cornerRadius: 18))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.leading, 40)
                .textSelection(.enabled)
                .contextMenu {
                    Button("Copy", systemImage: "doc.on.doc") { UIPasteboard.general.string = message.content }
                    if !isGenerating { Button("Edit", systemImage: "pencil", action: onEdit) }
                }

        case .assistant:
            VStack(alignment: .leading, spacing: 6) {
                if message.content.isEmpty {
                    ProgressView().padding(.vertical, 4)
                } else {
                    AssistantMarkdownView(text: message.content)
                }
                if let appData, !appData.topics.isEmpty, !message.content.isEmpty {
                    Label("Read from Spectra: \(appData.topics.joined(separator: ", "))", systemImage: "sparkle.magnifyingglass")
                        .font(.caption2).foregroundStyle(.secondary)
                }
                if let toolsUsed, !toolsUsed.isEmpty, !message.content.isEmpty {
                    Label("Ran: \(toolsUsed.joined(separator: ", "))", systemImage: "bolt.horizontal")
                        .font(.caption2).foregroundStyle(.secondary)
                }
                if let sources, !message.content.isEmpty {
                    AssistantSourcesView(payload: sources)
                }
                if showModel, let name = message.modelName {
                    Label(name, systemImage: "cpu").font(.caption2).foregroundStyle(.tertiary)
                }
                if message.interrupted && !isGenerating && !message.content.isEmpty {
                    Label("Answer cut off", systemImage: "exclamationmark.circle")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if isLast && !isGenerating && !message.content.isEmpty {
                    HStack(spacing: 16) {
                        Button("Copy", systemImage: "doc.on.doc") { UIPasteboard.general.string = message.content }
                        if message.interrupted {
                            Button("Continue", systemImage: "arrow.forward", action: onContinue)
                        }
                        Button("Regenerate", systemImage: "arrow.clockwise", action: onRegenerate)
                    }
                    .labelStyle(.iconOnly).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contextMenu {
                Button("Copy", systemImage: "doc.on.doc") { UIPasteboard.general.string = message.content }
            }

        case .system:
            EmptyView()
        }
    }
}

/// Sources under an answer: first the ones something was taken from. "What the model read" shows the excerpts
/// themselves, so one can see what the answer rests on.
private struct AssistantSourcesView: View {
    let payload: SearchPayload
    @State private var showPassages = false

    private var ordered: [Int] {
        let used = payload.usedSourceIndices
        return used + payload.results.indices.filter { !used.contains($0) }
    }

    private var header: String {
        let queries = payload.allQueries.map { "“\($0)”" }.joined(separator: String(localized: ", then "))
        var text = String(localized: "Searched: \(queries) · \(payload.provider)")
        if let read = payload.pagesRead, read > 0 { text += " · " + String(localized: "pages read: \(read)") }
        return text
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Label(header, systemImage: "globe").font(.caption2).foregroundStyle(.secondary)

            ForEach(ordered, id: \.self) { index in
                let result = payload.results[index]
                let used = payload.usedSourceIndices.contains(index)
                Link(destination: result.url) {
                    Text("[\(index + 1)] \(result.title) — \(result.url.host() ?? "")")
                        .foregroundStyle(used ? Color.accentColor : Color.secondary)
                }
                .font(.caption2).lineLimit(1)
            }

            if let evidence = payload.evidence, !evidence.isEmpty {
                Button(showPassages ? String(localized: "Hide what was read") : String(localized: "What the model read (\(evidence.count))")) {
                    showPassages.toggle()
                }
                .font(.caption2)
                if showPassages {
                    ForEach(Array(evidence.enumerated()), id: \.offset) { _, passage in
                        Text("[\(passage.sourceIndex + 1)] \(passage.text)")
                            .font(.caption2).foregroundStyle(.secondary)
                            .padding(6).background(.quinary, in: RoundedRectangle(cornerRadius: 6))
                            .textSelection(.enabled)
                    }
                }
            }
        }
    }
}
