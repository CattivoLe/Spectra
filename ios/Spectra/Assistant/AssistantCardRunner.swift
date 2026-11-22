import Foundation
import LocalChatEngine
import LocalChatKit
import Observation
import OSLog
import UIKit

private let cardLog = Logger(subsystem: "cattivole.Spectra", category: "SpectraCard")

/// One block under a card's findings: a model text (summary, detail, deep analysis) or a check a button ran.
@MainActor
@Observable
final class CardEntry: Identifiable {
    enum State: Equatable { case waiting, running, done, stopped, failed }

    let id = UUID()
    let action: CardAction?
    let label: String
    var text = ""
    var state: State = .waiting
    /// From the tap to the first words, and to the end: what the card is measured by.
    var firstWordsMs: Int?
    var totalMs: Int?
    /// The chat message this text is saved as: continuing it rewrites that message instead of adding one.
    var savedMessage: UUID?
    /// What a web search brought for this text: sources (and pictures of the device) shown with it.
    var web: SearchPayload?
    /// The web search's line while it runs, or why it did not: «Searching: «…»», «Search failed».
    var webStatus: String?
    var webBusy = false

    init(action: CardAction?, label: String) {
        self.action = action
        self.label = label
    }

    var isCheck: Bool { action == nil }
    var active: Bool { state == .waiting || state == .running }
}

/// A summary card while the app runs: its facts, what ran on it, and whether it can still act (a saved one cannot).
@MainActor
@Observable
final class AssistantCard: Identifiable {
    let id: UUID
    let subject: CardSubject?
    var facts: CardFacts
    let createdAt: Date
    let live: Bool
    var entries: [CardEntry] = []
    /// The saved head message, rewritten when a check changes the findings.
    var headMessage: UUID?
    /// The last check a step button ran: every later model action reads its result.
    var lastCheck: CardCheck?
    /// Step calls already run on this card: their buttons are not offered again.
    var ranSteps: [String] = []

    init(id: UUID, subject: CardSubject?, facts: CardFacts, createdAt: Date, live: Bool) {
        self.id = id
        self.subject = subject
        self.facts = facts
        self.createdAt = createdAt
        self.live = live
    }

    /// The model actions that finished since the last check: offering them again would only repeat them.
    var doneSinceCheck: Set<CardAction> {
        let lastCheck = entries.lastIndex(where: \.isCheck) ?? -1
        return Set(entries.dropFirst(lastCheck + 1).filter { $0.state == .done }.compactMap(\.action))
    }

    var busy: Bool { entries.contains(where: \.active) }

    /// The model's last text, when it was cut short: what "Continue" picks up.
    var stopped: CardEntry? {
        guard let last = entries.last(where: { !$0.isCheck }), last.state == .stopped, !busy else { return nil }
        return last
    }

    /// The steps still worth offering: the ones not run on this card yet.
    var openSteps: [CardStep] { facts.steps.filter { !ranSteps.contains($0.call) } }
}

/// Runs summary cards: opens one for a ✨ button, puts each model action through the engine as a fresh short
/// generation with its own prompt, runs the step buttons' checks, and keeps every card as a chat in a folder so the
/// Assistant tab lists them. Mirrors AssistantCardRunner.kt.
@MainActor
@Observable
final class AssistantCardRunner {
    private let assistant: AssistantModel
    private let sources: AssistantSources
    private let actions: AssistantActions
    private var store: ChatStore { assistant.store }
    private var controller: EngineController { assistant.controller }

    @ObservationIgnored private var cards: [UUID: AssistantCard] = [:]
    /// Cards the list asked to continue: their screen picks the text up as soon as it opens.
    @ObservationIgnored private var resumeOnOpen: Set<UUID> = []
    @ObservationIgnored private var job: Task<Void, Never>?
    @ObservationIgnored private var running: CardEntry?

    static let imageCount = 6

    init(assistant: AssistantModel, sources: AssistantSources, actions: AssistantActions) {
        self.assistant = assistant
        self.sources = sources
        self.actions = actions
    }

    func continueOnOpen(_ id: UUID) { resumeOnOpen.insert(id) }

    /// Whether "More detail" on this subject would search the web: only a known product or a link's domain goes out.
    func searchesWeb(_ subject: CardSubject) -> Bool { CardWeb.lookup(subject, action: .details, sources: sources) != nil }

    func takeContinue(_ id: UUID) -> Bool { resumeOnOpen.remove(id) != nil }

    /// A card in memory whose last text was stopped: the Assistant tab offers to continue it.
    func isStopped(_ id: UUID) -> Bool { cards[id]?.stopped != nil }

    /// A new card about `subject`, its summary started at once. `nil` when there is nothing to summarize.
    func open(_ subject: CardSubject) async -> UUID? {
        guard let facts = sources.cardFacts(subject) else { return nil }
        // The model loads in its own task: the card shows its findings meanwhile.
        Task { await assistant.ensureStarted() }
        do {
            let chat = try await store.createChat(subject: CardSubjects.encode(subject), folder: await assistant.recordingFolder())
            try await store.rename(chat.id, to: facts.title)
            let head = try await store.append(.user, AssistantCards.render(title: facts.title, findings: facts.findings), to: chat.id)
            let card = AssistantCard(id: chat.id, subject: subject, facts: facts, createdAt: chat.createdAt, live: true)
            card.headMessage = head.id
            cards[chat.id] = card
            cardLog.info("card \(subject.kind.rawValue, privacy: .public): \(facts.findings.count) findings shown at once")
            // Nothing measured yet: the buttons are the whole card, there is nothing for the model to explain.
            if !facts.awaitsCheck { run(card, action: .summary) }
            return chat.id
        } catch {
            cardLog.error("card not saved: \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    /// The card in memory, or a saved one read back from the store (then it only shows what was written).
    func card(_ id: UUID) async -> AssistantCard? {
        if let card = cards[id] { return card }
        guard let chat = try? await store.chat(id), let messages = try? await store.messages(in: id),
              let head = messages.first(where: { $0.role == .user }),
              let parsed = AssistantCards.parse(head.content) else { return nil }
        let subject = CardSubjects.decode(chat.subject)
        let facts = CardFacts(subject: subject ?? .lan, title: parsed.title, findings: parsed.findings, steps: [], data: "", deepData: "")
        let card = AssistantCard(id: id, subject: subject, facts: facts, createdAt: chat.createdAt, live: false)
        card.headMessage = head.id
        for message in messages where message.role == .assistant {
            let (action, text) = Self.savedText(message.content)
            let entry = CardEntry(action: action, label: action?.heading ?? text)
            entry.text = action != nil ? text : ""
            entry.state = message.interrupted ? .stopped : .done
            entry.savedMessage = message.id
            entry.web = message.search
            card.entries.append(entry)
        }
        return card
    }

    /// Picks up the card's last text that was stopped — by leaving, by another card, or by the app closing: the same
    /// action runs again in its place. A saved card comes back to life first, reading its subject's current data.
    /// Returns the card now on screen.
    func resume(_ card: AssistantCard) -> AssistantCard {
        guard let stopped = card.stopped, let action = stopped.action else { return card }
        guard let live = card.live ? card : revive(card) else { return card }
        guard let index = live.entries.lastIndex(where: { !$0.isCheck }) else { return live }
        stop()
        let entry = CardEntry(action: action, label: action.heading)
        entry.savedMessage = stopped.savedMessage
        // What the web gave the stopped text is used again: nothing goes out a second time.
        entry.web = stopped.web
        live.entries[index] = entry
        job = Task { await generate(live, entry: entry, action: action) }
        return live
    }

    /// A saved card back to life: its subject's current data, its texts kept. `nil` for a card with no subject.
    func revive(_ saved: AssistantCard) -> AssistantCard? {
        if saved.live { return saved }
        guard let subject = saved.subject else { return nil }
        let live = AssistantCard(id: saved.id, subject: subject, facts: sources.cardFacts(subject) ?? saved.facts,
                                 createdAt: saved.createdAt, live: true)
        live.headMessage = saved.headMessage
        live.entries = saved.entries
        cards[saved.id] = live
        return live
    }

    /// Starts a model action on a card, stopping whatever card text is being written now.
    func run(_ card: AssistantCard, action: CardAction) {
        guard card.live else { return }
        stop()
        let entry = CardEntry(action: action, label: action.heading)
        card.entries.append(entry)
        job = Task { await generate(card, entry: entry, action: action) }
    }

    /// Runs a step button's check, refreshes the findings, then writes a new short summary.
    func runStep(_ card: AssistantCard, step: CardStep) {
        guard card.live else { return }
        stop()
        let entry = CardEntry(action: nil, label: step.label)
        entry.state = .running
        entry.text = AssistantActions.runningLabel(for: step.tool)
        card.entries.append(entry)
        card.ranSteps.append(step.call)
        job = Task {
            let result = await actions.perform(step.call)
            guard !Task.isCancelled else {
                entry.state = .stopped
                entry.text = ""
                return
            }
            entry.state = .done
            entry.text = ""
            guard cards[card.id] != nil, (try? await store.append(.assistant, "✓ \(step.label)", to: card.id)) != nil else { return }
            guard let subject = card.subject else { return }
            let fresh = sources.cardFacts(subject) ?? card.facts
            // The check's own words go to the model as their own block: a capture or a GATT read says more than
            // the refreshed findings, and the model is asked about the check, not for the summary again.
            card.lastCheck = CardCheck(title: AssistantActions.title(for: step.tool), result: result, tool: step.tool)
            card.facts = fresh
            if let head = card.headMessage {
                try? await store.update(head, content: AssistantCards.render(title: fresh.title, findings: fresh.findings))
            }
            let explained = CardEntry(action: .check, label: CardAction.check.heading)
            card.entries.append(explained)
            await generate(card, entry: explained, action: .check)
        }
    }

    /// Stops the card text or check under way; what was written so far stays.
    func stop() {
        guard let current = job else { return }
        job = nil
        if running != nil { controller.stop() }
        current.cancel()
    }

    private func milliseconds(since start: ContinuousClock.Instant) -> Int {
        let elapsed = ContinuousClock.now - start
        return Int(elapsed.components.seconds * 1000 + elapsed.components.attoseconds / 1_000_000_000_000_000)
    }

    private func generate(_ card: AssistantCard, entry: CardEntry, action: CardAction) async {
        let tapped = ContinuousClock.now
        entry.state = .waiting
        guard assistant.assistantModelFile != nil else {
            entry.state = .failed
            entry.text = String(localized: "The assistant's model is not downloaded yet: Assistant tab → ⋯ → Model.")
            return
        }
        // "More detail" and "Deep analysis" read the web first, when it is on: what the device is, known problems.
        if entry.web == nil {
            entry.web = await lookUp(card, entry: entry, action: action)
            if Task.isCancelled {
                entry.state = .stopped
                entry.webBusy = false
                return
            }
        }
        // The model may still be loading, or another card may be finishing its text: wait for the engine.
        Task { await assistant.ensureStarted() }
        while !Task.isCancelled {
            let state = controller.state
            if state == .ready || state == .unloadedForMemory || assistant.loadError != nil { break }
            try? await Task.sleep(for: .milliseconds(200))
        }
        if Task.isCancelled {
            // Stopped before the model was free: the block must not keep waiting (it would hold the buttons).
            entry.state = .stopped
            return
        }
        if let error = assistant.loadError, controller.state != .ready, controller.state != .unloadedForMemory {
            entry.state = .failed
            entry.text = String(localized: "The model did not load: \(error)")
            return
        }
        let messages = [
            ChatMessage(.system, CardPrompts.system(action, kind: card.facts.subject.kind, tool: card.lastCheck?.tool, web: entry.web != nil)),
            ChatMessage(.user, CardPrompts.user(card.facts, action: action, check: card.lastCheck,
                                                earlier: earlierSummary(card, current: entry), web: entry.web)),
        ]
        cardLog.info("card \(card.facts.subject.kind.rawValue, privacy: .public) \(action.rawValue, privacy: .public): system \(messages[0].content.count) chars, user \(messages[1].content.count) chars")
        entry.state = .running
        running = entry
        var text = ""
        var outcome = CardEntry.State.done
        var cut = false
        do {
            for try await event in controller.send(messages: messages, params: GenerationParams(maxTokens: action.maxTokens, temperature: 0.3)) {
                switch event {
                case .text(let piece):
                    text += piece
                    if entry.firstWordsMs == nil, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        entry.firstWordsMs = milliseconds(since: tapped)
                    }
                    entry.text = AssistantText.polish(text).trimmingCharacters(in: .whitespacesAndNewlines)
                case .finished(let stats):
                    cut = stats.stopReason == .maxTokens
                    if stats.stopReason == .cancelled { outcome = .stopped }
                    cardLog.info("card \(card.facts.subject.kind.rawValue, privacy: .public) \(action.rawValue, privacy: .public): prompt \(stats.promptTokens) tok (\(stats.reusedPromptTokens) reused), first token \(stats.timeToFirstTokenSeconds, format: .fixed(precision: 1)) s, \(stats.generatedTokens) tok in \(stats.generationSeconds, format: .fixed(precision: 1)) s, \(stats.stopReason.rawValue, privacy: .public)")
                }
            }
            if Task.isCancelled { outcome = .stopped }
        } catch {
            if Task.isCancelled {
                outcome = .stopped
            } else {
                outcome = .failed
                if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { text = error.localizedDescription }
            }
        }
        running = nil
        entry.totalMs = milliseconds(since: tapped)
        // Out of length: say so with an ellipsis rather than end mid-sentence as if that were all.
        let polished = AssistantText.polish(text).trimmingCharacters(in: .whitespacesAndNewlines)
        entry.text = cut && !polished.isEmpty ? polished + " …" : polished
        entry.state = outcome
        if outcome == .done, !entry.text.isEmpty { UINotificationFeedbackGenerator().notificationOccurred(.success) }
        cardLog.info("card \(card.facts.subject.kind.rawValue, privacy: .public) \(action.rawValue, privacy: .public): first words \(entry.firstWordsMs ?? -1) ms, done \(entry.totalMs ?? -1) ms")
        guard !entry.text.isEmpty, outcome != .failed else { return }
        await save(card, entry: entry, action: action, stopped: outcome == .stopped)
    }

    private func save(_ card: AssistantCard, entry: CardEntry, action: CardAction, stopped: Bool) async {
        // The card may have been deleted while the model wrote: then there is nothing to save it in.
        guard cards[card.id] != nil else { return }
        let content = "**\(action.heading)**\n\n\(entry.text)"
        do {
            if let earlier = entry.savedMessage {
                try await store.update(earlier, content: content, interrupted: stopped)
            } else {
                let saved = try await store.append(.assistant, content, to: card.id, modelName: assistant.currentModelName)
                entry.savedMessage = saved.id
                if stopped { try await store.update(saved.id, content: saved.content, interrupted: true) }
            }
            // The sources and pictures stay with the text, so a saved card shows them too.
            if let web = entry.web, let id = entry.savedMessage { try await store.setSearch(web, for: id) }
        } catch {
            cardLog.warning("card text not saved: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// The web search for a "More detail" or "Deep analysis" text: one query about what the subject is (its maker
    /// and model, never an address or a name), confirmed like any other search, written to the log and kept in the
    /// search cache; for a device also a few pictures. `nil` when the web is off, not set up, declined or useless.
    private func lookUp(_ card: AssistantCard, entry: CardEntry, action: CardAction) async -> SearchPayload? {
        let web = assistant.web
        guard let subject = card.subject, let lookup = CardWeb.lookup(subject, action: action, sources: sources), web.isEnabled else { return nil }
        let research: WebResearch
        do {
            research = try web.makeResearch(light: action == .details)
        } catch {
            entry.webStatus = String(localized: "The web search is not set up: Assistant tab → ⋯ → Internet.")
            return nil
        }
        let provider = research.provider
        let providerName = provider.name
        entry.webBusy = true
        defer { entry.webBusy = false }
        let reused = try? await store.cachedSearch(for: lookup.query, provider: providerName)
        var query = reused?.query ?? lookup.query
        if reused == nil, web.confirmEachSearch {
            entry.webStatus = String(localized: "Waiting for your OK to search the web…")
            guard let approved = await web.approve?(query)?.trimmingCharacters(in: .whitespacesAndNewlines), !approved.isEmpty else {
                entry.webStatus = String(localized: "Web search declined: the text uses only what the app measured.")
                return nil
            }
            query = approved
        }
        if Task.isCancelled { return nil }
        entry.webStatus = reused != nil ? String(localized: "Using an earlier search: «\(query)»")
                                        : String(localized: "Searching the web: «\(query)»…")
        let store = store
        var payload: SearchPayload
        do {
            payload = try await research.run(
                query: query, question: "\(card.facts.title). \(query)",
                initial: reused?.gathered,
                sent: { sent in try? await store.logSearch(query: sent, provider: providerName, succeeded: true) },
                fetched: { sent, gathered in try? await store.cacheSearch(query: sent, provider: providerName, gathered: gathered) }
            )
        } catch {
            if Task.isCancelled { return nil }
            try? await store.logSearch(query: query, provider: providerName, succeeded: false)
            entry.webStatus = String(localized: "The web search failed (\(Self.searchError(error))): the text uses only what the app measured.")
            return nil
        }
        // A picture of the device, when the service can find pictures and the model is known.
        var images: [ImageResult] = []
        if let pictureQuery = lookup.imageQuery, web.provider.supportsImages, !Task.isCancelled {
            // Approved with the text query when it is part of it; otherwise it is asked about on its own.
            var approved: String? = pictureQuery
            if web.confirmEachSearch, !query.localizedCaseInsensitiveContains(pictureQuery) {
                approved = await web.approve?(pictureQuery)?.trimmingCharacters(in: .whitespacesAndNewlines)
                if approved?.isEmpty == true { approved = nil }
            }
            if let approved {
                entry.webStatus = String(localized: "Looking for pictures: «\(approved)»…")
                do {
                    images = try await provider.searchImages(approved, limit: Self.imageCount)
                    try? await store.logSearch(query: approved, provider: providerName, succeeded: true)
                } catch {
                    try? await store.logSearch(query: approved, provider: providerName, succeeded: false)
                }
            }
        }
        if payload.results.isEmpty && images.isEmpty {
            entry.webStatus = String(localized: "Nothing found on the web for «\(query)».")
            return nil
        }
        entry.webStatus = nil
        cardLog.info("card web: \(payload.results.count) results, \(payload.evidence?.count ?? 0) passages, \(images.count) pictures from \(providerName, privacy: .public)")
        payload.images = images.isEmpty ? nil : images
        return payload
    }

    static func searchError(_ error: Error) -> String {
        switch error as? WebSearchError {
        case .offline: String(localized: "no connection")
        case .http(let code):
            switch code {
            case 401, 403: String(localized: "the service refused the key")
            case 429: String(localized: "too many requests")
            case 402: String(localized: "the service's credits ran out")
            default: "HTTP \(code)"
            }
        case .notConfigured: String(localized: "not set up")
        default: error.localizedDescription
        }
    }

    /// What the card already said in short (its summary and check notes), for a check's text not to repeat it.
    private func earlierSummary(_ card: AssistantCard, current: CardEntry) -> String {
        card.entries.filter { $0 !== current && ($0.action == .summary || $0.action == .check) && !$0.text.isEmpty }
            .map(\.text).joined(separator: "\n")
    }

    /// Forgets a deleted card.
    func forget(_ id: UUID) {
        // A card deleted while its text is being written: stop the model, its words have nowhere to go.
        if cards[id]?.busy == true { stop() }
        cards[id] = nil
    }

    func forgetAll() {
        stop()
        cards = [:]
    }

    /// A saved card text: its heading names the action. A check is saved as "✓ label".
    nonisolated static func savedText(_ content: String) -> (action: CardAction?, text: String) {
        // Either wording: the app language may have changed since the card was saved.
        for action in CardAction.allCases {
            for head in ["**\(action.heading)**", "**\(action.englishHeading)**"] where content.hasPrefix(head) {
                return (action, String(content.dropFirst(head.count)).trimmingCharacters(in: .whitespacesAndNewlines))
            }
        }
        let text = content.hasPrefix("✓") ? String(content.dropFirst()) : content
        return (nil, text.trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

/// Service words a small model lets slip into a card's text. Mirrors AssistantText.kt.
nonisolated enum AssistantText {
    private static let tag = #"`?</?(?:data|check|web)(?:\s[^>]*)?>`?"#
    private static let start = #"(?<![\p{L}\p{N}_])"#

    /// Phrases a small model builds around the tag it was given the data in, and what a person should read instead.
    private static let phrases: [(String, String)] = [
        (start + #"(?:по состоянию на|согласно|судя по|по данным|исходя из)\s+"# + tag, "по данным Spectra"),
        (start + #"(?:во|в)\s+"# + tag, "в данных Spectra"),
        (start + #"из\s+"# + tag, "из данных Spectra"),
        (start + #"(?:according to|based on|as of|per)\s+(?:the\s+)?"# + tag, "according to Spectra's data"),
        (start + #"(?:in|from)\s+(?:the\s+)?"# + tag, "in Spectra's data"),
    ]

    /// Takes the service tags out of a text: «По состоянию на <data>:» reads «По данным Spectra:». The model sees
    /// its data inside such tags and now and then names them; the person never saw them.
    static func polish(_ text: String) -> String {
        guard text.contains("<") else { return text }
        var result = text
        for (pattern, words) in phrases {
            guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else { continue }
            let matches = regex.matches(in: result, range: NSRange(result.startIndex..., in: result)).reversed()
            for match in matches {
                guard let range = Range(match.range, in: result) else { continue }
                let capital = result[range].first?.isUppercase == true
                result.replaceSubrange(range, with: capital ? words.prefix(1).uppercased() + words.dropFirst() : words)
            }
        }
        let russian = result.contains { ("а"..."я").contains($0) || ("А"..."Я").contains($0) }
        if let bare = try? NSRegularExpression(pattern: tag) {
            result = bare.stringByReplacingMatches(in: result, range: NSRange(result.startIndex..., in: result),
                                                   withTemplate: russian ? "данные Spectra" : "Spectra's data")
        }
        return result
    }
}
