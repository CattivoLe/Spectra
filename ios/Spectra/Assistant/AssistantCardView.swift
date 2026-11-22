import SwiftUI
import LocalChatEngine
import LocalChatKit

/// A summary card: the app's own findings at the top, at once; under them what the model wrote, one block per
/// action; at the bottom only buttons — next steps chosen from the findings, "More detail" and "Deep analysis".
/// No text field. Mirrors AssistantCardScreen.kt.
struct AssistantCardView: View {
    let id: UUID

    @Environment(AssistantModel.self) private var assistant
    @Environment(\.dismiss) private var dismiss
    @State private var card: AssistantCard?
    @State private var folderName: String?
    @State private var fresh: UUID?
    @State private var nothingToSummarize = false
    @State private var showModels = false

    private var runner: AssistantCardRunner? { assistant.cards }

    var body: some View {
        Group {
            if let card {
                content(card)
            } else {
                ProgressView()
            }
        }
        .task(id: id) {
            guard let runner, let loaded = await runner.card(id) else {
                dismiss()
                return
            }
            // "Continue" in the Assistant tab's list: the stopped text is picked up as the card opens.
            card = runner.takeContinue(id) ? runner.resume(loaded) : loaded
            folderName = await assistant.folderName(of: id)
        }
        .navigationDestination(item: $fresh) { AssistantCardView(id: $0) }
        .sheet(item: Binding(get: { assistant.pendingSearch }, set: { value in
            // Closing the sheet without choosing sends nothing.
            if value == nil {
                assistant.pendingSearch?.answer(nil)
                assistant.pendingSearch = nil
            }
        })) { pending in
            AssistantSearchApprovalSheet(pending: pending, provider: assistant.web.provider.title)
        }
        .sheet(isPresented: $showModels) { AssistantModelsView().environment(assistant) }
        .alert("There is nothing to summarize for it now.", isPresented: $nothingToSummarize) { Button("OK") {} }
    }

    private func content(_ card: AssistantCard) -> some View {
        let facts = card.facts
        return List {
            Section("Findings") {
                ForEach(Array(facts.findings.enumerated()), id: \.offset) { _, finding in
                    FindingRow(finding: finding)
                }
            }
            ForEach(card.entries) { entry in
                if entry.isCheck {
                    Section { CheckRow(entry: entry) }
                } else {
                    Section {
                        EntryText(entry: entry, showModels: $showModels)
                        // Stopped by leaving or by another card: the same text can be picked up again.
                        if entry === card.stopped, card.live || card.subject != nil {
                            Button("Continue", systemImage: "play.fill") { self.card = runner?.resume(card) }
                        }
                    } header: {
                        Text(entry.label)
                    } footer: {
                        if let timing = timing(entry) { Text(timing) }
                    }
                }
            }
            if card.live {
                liveActions(card)
            } else if let subject = card.subject {
                Section {
                    Button("New summary", systemImage: "arrow.clockwise") {
                        Task {
                            if let created = await runner?.open(subject) { fresh = created } else { nothingToSummarize = true }
                        }
                    }
                } footer: {
                    Text("A saved summary shows what was found then. A new one reads the current data.")
                }
            }
        }
        .navigationTitle(facts.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(facts.title).font(.headline).lineLimit(1)
                    Text(subtitle(card)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            if card.busy {
                ToolbarItem(placement: .primaryAction) {
                    Button("Stop", systemImage: "stop.circle") { runner?.stop() }
                }
            }
        }
    }

    @ViewBuilder
    private func liveActions(_ card: AssistantCard) -> some View {
        // A step already run is not offered again: its result is above.
        let steps = card.openSteps
        if !steps.isEmpty {
            Section("Next steps") {
                ForEach(steps, id: \.call) { step in
                    Button(step.label) { runner?.runStep(card, step: step) }
                        .disabled(card.busy)
                }
            }
        }
        // An action that finished is not offered again until a check brings new data: it would say the same.
        let done = card.doneSinceCheck
        let offers = card.facts.awaitsCheck ? [] : [CardAction.details, .deep].filter { !done.contains($0) }
        if !offers.isEmpty {
            Section {
                ForEach(offers, id: \.self) { action in
                    Button(action.heading, systemImage: action == .deep ? "doc.text.magnifyingglass" : "text.alignleft") {
                        runner?.run(card, action: action)
                    }
                    .disabled(card.busy)
                }
            } header: {
                Text("Ask the model")
            } footer: {
                let parts = [offers.contains(.deep) ? String(localized: "Deep analysis reads everything around this subject and writes at length: it takes a few minutes.") : nil,
                             webHint(card)].compactMap { $0 }
                if !parts.isEmpty { Text(parts.joined(separator: " ")) }
            }
        }
    }

    private func webHint(_ card: AssistantCard) -> String? {
        guard let subject = card.subject, runner?.searchesWeb(subject) == true else { return nil }
        let web = assistant.web
        if !web.isEnabled {
            return String(localized: "With the internet on (Assistant tab → ⋯ → Internet), both also search the web for this device's model and known problems.")
        }
        if !web.isReady { return String(localized: "The web search is not set up: Assistant tab → ⋯ → Internet.") }
        return web.confirmEachSearch ? String(localized: "Both also search the web through \(web.provider.title); you see the query first.")
                                     : String(localized: "Both also search the web through \(web.provider.title).")
    }

    private func subtitle(_ card: AssistantCard) -> String {
        let made = card.live ? String(localized: "Summary")
                             : String(localized: "Saved \(card.createdAt.formatted(date: .abbreviated, time: .shortened))")
        return [made, folderName].compactMap { $0 }.joined(separator: " · ")
    }

    /// How long the text took, from the tap: shown small under it.
    private func timing(_ entry: CardEntry) -> String? {
        guard let total = entry.totalMs else { return nil }
        let seconds = { (ms: Int) in (Double(ms) / 1000).formatted(.number.precision(.fractionLength(0))) }
        if let first = entry.firstWordsMs {
            return String(localized: "First words in \(seconds(first)) s, done in \(seconds(total)) s")
        }
        return String(localized: "Done in \(seconds(total)) s")
    }
}

extension CardRisk {
    var color: Color {
        switch self {
        case .critical: .red
        case .warning: .orange
        case .info: .blue
        case .ok: .green
        }
    }

    var label: String {
        switch self {
        case .critical: String(localized: "Critical")
        case .warning: String(localized: "Attention")
        case .info: String(localized: "Note")
        case .ok: String(localized: "Fine")
        }
    }
}

private struct FindingRow: View {
    let finding: CardFinding

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Circle().fill(finding.risk.color).frame(width: 10, height: 10)
            Text(finding.text).font(.subheadline)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text("\(finding.risk.label): \(finding.text)"))
    }
}

private struct CheckRow: View {
    let entry: CardEntry

    var body: some View {
        HStack(spacing: 12) {
            switch entry.state {
            case .waiting, .running: ProgressView().controlSize(.small)
            case .done: Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            default: Image(systemName: "exclamationmark.circle").foregroundStyle(.red)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.label).font(.subheadline)
                let note = entry.state == .stopped ? String(localized: "Stopped") : entry.text
                if !note.isEmpty { Text(note).font(.caption).foregroundStyle(.secondary) }
            }
        }
    }
}

private struct EntryText: View {
    let entry: CardEntry
    @Binding var showModels: Bool
    @Environment(AssistantModel.self) private var assistant

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            WebLine(entry: entry)
            if let images = entry.web?.images, !images.isEmpty { ImageStrip(images: images) }
            if entry.state == .failed {
                Text(entry.text).font(.subheadline).foregroundStyle(.red)
                if assistant.assistantModelFile == nil {
                    Button("Model…", systemImage: "cpu") { showModels = true }.buttonStyle(.bordered)
                }
            } else if entry.webBusy {
                EmptyView()
            } else if entry.text.isEmpty && entry.active {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small)
                    Text(waiting).font(.caption).foregroundStyle(.secondary)
                }
            } else {
                AssistantMarkdownView(text: entry.text)
                if entry.state == .stopped { Text("Stopped").font(.caption).foregroundStyle(.secondary) }
            }
            if let web = entry.web, !web.results.isEmpty, !entry.active { WebSources(web: web) }
        }
        .padding(.vertical, 2)
    }

    private var waiting: String {
        switch assistant.controller.state {
        case .loading, .empty: String(localized: "Loading the model…")
        default: entry.state == .waiting ? String(localized: "Waiting for the model…") : String(localized: "Reading the findings…")
        }
    }
}

/// The web search's state above the text: running, declined, failed, or where the text's facts came from.
private struct WebLine: View {
    let entry: CardEntry

    var body: some View {
        if let text {
            HStack(spacing: 8) {
                if entry.webBusy { ProgressView().controlSize(.mini) } else { Image(systemName: "globe").foregroundStyle(.tint) }
                Text(text).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var text: String? {
        if let status = entry.webStatus { return status }
        if let web = entry.web, !web.results.isEmpty {
            return String(localized: "Read on the web: \(web.usedSourceIndices.count) sources via \(web.provider)")
        }
        return nil
    }
}

/// The pages the text drew on, by number as the text cites them; a tap opens one in the browser.
private struct WebSources: View {
    let web: SearchPayload

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Sources").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            ForEach(web.usedSourceIndices.filter { web.results.indices.contains($0) }, id: \.self) { index in
                let result = web.results[index]
                Link(destination: result.url) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("[\(index + 1)]").font(.caption.monospacedDigit())
                        VStack(alignment: .leading, spacing: 1) {
                            Text(result.title).font(.caption).lineLimit(2).foregroundStyle(.primary)
                            Text(result.url.host() ?? "").font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .padding(.top, 4)
    }
}

/// Pictures of the device a search brought, in a row above the text. A tap shows one larger.
struct ImageStrip: View {
    let images: [ImageResult]
    @State private var open: ImageResult?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(images, id: \.image) { image in
                    Button { open = image } label: {
                        AsyncImage(url: image.thumbnail) { phase in
                            if let picture = phase.image {
                                picture.resizable().scaledToFill()
                            } else {
                                Rectangle().fill(.fill.tertiary)
                            }
                        }
                        .frame(width: 96, height: 96)
                        .clipShape(.rect(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(image.title.isEmpty ? image.host : image.title))
                }
            }
        }
        .sheet(item: Binding(get: { open.map(IdentifiedImage.init) }, set: { open = $0?.image })) { item in
            ImageViewer(image: item.image)
        }
    }
}

private struct IdentifiedImage: Identifiable {
    let image: ImageResult
    var id: URL { image.image }
}

/// One picture large, with the page it is on. The full image comes from its own site, so it loads only here.
private struct ImageViewer: View {
    let image: ImageResult
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                AsyncImage(url: image.image) { phase in
                    if let picture = phase.image {
                        picture.resizable().scaledToFit()
                    } else if phase.error != nil {
                        AsyncImage(url: image.thumbnail) { $0.resizable().scaledToFit() } placeholder: { ProgressView() }
                    } else {
                        ProgressView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                if !image.title.isEmpty { Text(image.title).font(.footnote).multilineTextAlignment(.center) }
                Link(destination: image.page) { Label(image.host, systemImage: "safari") }.font(.footnote)
            }
            .padding()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
