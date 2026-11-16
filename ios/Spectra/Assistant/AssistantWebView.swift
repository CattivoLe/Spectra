import SwiftUI
import LocalChatKit

/// Internet access settings. Off by default: the assistant works on the phone alone.
struct AssistantWebView: View {
    @Environment(AssistantModel.self) private var model
    @State private var confirmEnable = false
    @State private var testing = false
    @State private var testResult: String?
    @State private var logCount = 0

    var body: some View {
        @Bindable var web = model.web

        Form {
            Section {
                Toggle("Allow web search", isOn: Binding(
                    get: { web.isEnabled },
                    set: { wantsOn in if wantsOn { confirmEnable = true } else { web.isEnabled = false } }
                ))
            } footer: {
                Text(web.isEnabled
                     ? "Search is switched on per chat with the globe button next to the message field."
                     : "Right now nothing leaves the phone except model downloads you start yourself.")
            }

            if web.isEnabled {
                Section {
                    Picker("Source", selection: $web.provider) {
                        ForEach(WebAccess.ProviderKind.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                    switch web.provider {
                    case .wikipedia:
                        Picker("Language", selection: $web.wikipediaLanguage) {
                            Text("Русский").tag("ru")
                            Text("English").tag("en")
                        }
                    case .searxng:
                        TextField("https://search.example.org", text: $web.searxngAddress)
                            .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    case .brave:
                        SecureField("API key", text: $web.braveKey)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                    }
                    if let problem = web.configurationProblem { Text(problem).font(.footnote).foregroundStyle(.orange) }
                } header: {
                    Text("Where to search")
                } footer: {
                    Text(web.provider.explanation)
                }

                Section("How to search") {
                    Toggle("Show the query before sending", isOn: $web.confirmEachSearch)
                    Stepper("Pages in the answer: \(web.resultCount)", value: $web.resultCount, in: 1...5)
                }

                Section {
                    Button(testing ? String(localized: "Checking…") : String(localized: "Test the connection"), systemImage: "network") { test() }
                        .disabled(testing || web.configurationProblem != nil)
                    if let testResult { Text(testResult).font(.footnote) }
                }
            }

            Section {
                Label("Only the short query text leaves the phone. Your chat, history and what Spectra found about your networks and devices are never sent. Results are mixed into the question on the phone.", systemImage: "lock.shield")
                    .font(.footnote).foregroundStyle(.secondary)
                Label("The search service sees your query and IP address, as with any search. If that matters, use your own SearXNG server.", systemImage: "eye")
                    .font(.footnote).foregroundStyle(.secondary)
                Label("Pages found are data from the internet: they may be inaccurate, and the model may retell them wrongly.", systemImage: "exclamationmark.triangle")
                    .font(.footnote).foregroundStyle(.secondary)
            } header: {
                Text("Good to know")
            }

            Section {
                NavigationLink("Query log (\(logCount))") { AssistantSearchLogView() }
            } footer: {
                Text("Everything that was sent to the internet is recorded here.")
            }
        }
        .navigationTitle("Internet")
        .navigationBarTitleDisplayMode(.inline)
        .task { logCount = (try? await model.store.searchLog().count) ?? 0 }
        .confirmationDialog("Allow web search?", isPresented: $confirmEnable, titleVisibility: .visible) {
            Button("Allow") { model.web.isEnabled = true }
        } message: {
            Text("When search is on in a chat, the text of your question is sent to the search service you choose. Everything else stays on the phone.")
        }
    }

    private func test() {
        testing = true
        testResult = nil
        Task {
            do {
                let provider = try model.web.makeProvider()
                let results = try await provider.search("Wi-Fi", limit: 1)
                testResult = results.isEmpty
                    ? String(localized: "The server answers, but found nothing.")
                    : String(localized: "Works: found “\(results[0].title)”.")
            } catch {
                testResult = error.localizedDescription
            }
            testing = false
        }
    }
}

struct AssistantWebSheet: View {
    @Environment(AssistantModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            AssistantWebView().environment(model)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

/// The log: what was sent, when and where.
struct AssistantSearchLogView: View {
    @Environment(AssistantModel.self) private var model
    @State private var entries: [SearchLogEntry] = []

    var body: some View {
        List {
            if entries.isEmpty { Text("No queries yet").foregroundStyle(.secondary) }
            ForEach(entries) { entry in
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.query)
                    HStack {
                        Text(entry.provider)
                        Text(entry.date, format: .dateTime.day().month().hour().minute())
                        if !entry.succeeded { Text("failed").foregroundStyle(.red) }
                    }
                    .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Query log")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Button("Clear", role: .destructive) {
                Task {
                    try? await model.store.clearSearchLog()
                    entries = []
                }
            }
            .disabled(entries.isEmpty)
        }
        .task { entries = (try? await model.store.searchLog()) ?? [] }
    }
}

/// Shown before a query goes out: exactly what will be sent, editable.
struct AssistantSearchApprovalSheet: View {
    let pending: PendingSearch
    let provider: String
    @State private var query: String
    @Environment(\.dismiss) private var dismiss

    init(pending: PendingSearch, provider: String) {
        self.pending = pending
        self.provider = provider
        _query = State(initialValue: pending.query)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Query", text: $query, axis: .vertical).lineLimit(1...4)
                } header: {
                    Text("Will be sent to “\(provider)”")
                } footer: {
                    Text("Only this text goes out. Edit it if it contains anything extra, such as names or addresses.")
                }
                Section {
                    Button("Search", systemImage: "magnifyingglass") { finish(query) }.disabled(query.trimmingCharacters(in: .whitespaces).isEmpty)
                    Button("Answer without the internet", role: .cancel) { finish(nil) }
                }
            }
            .navigationTitle("Web search")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium])
        .interactiveDismissDisabled()
    }

    private func finish(_ result: String?) {
        pending.answer(result)
        dismiss()
    }
}
