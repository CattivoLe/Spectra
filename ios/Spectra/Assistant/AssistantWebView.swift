import SwiftUI
import LocalChatKit

/// Internet access settings. Off by default: the assistant works on the phone alone.
struct AssistantWebView: View {
    @Environment(AssistantModel.self) private var model
    @State private var confirmEnable = false
    @State private var testing = false
    @State private var testResult: String?
    @State private var searchCount = 0

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
                     ? "“More detail” and “Deep analysis” on a summary search the web for the device's maker and model, or a link's site. To stop it, turn this switch off."
                     : "Right now nothing leaves the phone except model downloads you start yourself.")
            }

            if web.isEnabled {
                Section {
                    ForEach(WebAccess.ProviderKind.allCases, id: \.self) { kind in
                        Button {
                            web.provider = kind
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(kind.title).foregroundStyle(.primary)
                                    Text(kind.summary).font(.footnote).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if web.provider == kind { Image(systemName: "checkmark").foregroundStyle(.tint) }
                            }
                        }
                    }
                } header: {
                    Text("Where to search")
                }

                Section {
                    switch web.provider {
                    case .searxng:
                        TextField("https://search.example.org", text: $web.searxngAddress)
                            .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    default:
                        let kind = web.provider
                        SecureField("API key", text: Binding(get: { web.key(kind) }, set: { web.setKey(kind, $0) }))
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                    }
                    Picker("Language", selection: $web.searchLanguage) {
                        Text("Русский").tag("ru")
                        Text("English").tag("en")
                        Text("Automatic").tag("auto")
                    }
                    if let problem = web.configurationProblem { Text(problem).font(.footnote).foregroundStyle(.orange) }
                } header: {
                    Text(web.provider.title)
                } footer: {
                    Text(web.provider.explanation)
                }

                Section {
                    Toggle("Show the query before sending", isOn: $web.confirmEachSearch)
                    Toggle("Read pages in full", isOn: $web.readPages)
                    if web.readPages {
                        if model.library.embeddingModel != nil {
                            Toggle("Pick by meaning", isOn: $web.useMeaning)
                        } else {
                            Label("To pick by meaning, download \u{201c}EmbeddingGemma\u{201d} under Models (330 MB)", systemImage: "sparkle.magnifyingglass")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                        Stepper("Pages to read: \(web.pagesToRead)", value: $web.pagesToRead, in: 1...5)
                    } else {
                        Stepper("Result snippets: \(web.resultCount)", value: $web.resultCount, in: 1...5)
                    }
                } header: {
                    Text("How to search")
                } footer: {
                    Text(web.readPages
                         ? "The phone downloads the top pages, takes their main text and gives the model only the parts that fit the question. That finds exact numbers and dates, but the answer starts a few seconds later, and the sites see the phone's address, as when you open a page."
                         : "The model gets only the short snippets from the results: faster, but they often lack exact numbers.")
                }

                Section {
                    Button(testing ? String(localized: "Checking…") : String(localized: "Test the connection"), systemImage: "network") { test() }
                        .disabled(testing || web.configurationProblem != nil)
                    if let testResult { Text(testResult).font(.footnote) }
                }
            }

            Section {
                Label("Only the short query leaves the phone: a device's maker and model or a link's site. Addresses, network names, names you gave your devices and what Spectra found are never sent.", systemImage: "lock.shield")
                    .font(.footnote).foregroundStyle(.secondary)
                Label("The search service sees your query and IP address, as with any search. If that matters, use your own SearXNG server.", systemImage: "eye")
                    .font(.footnote).foregroundStyle(.secondary)
                Label("Pages found are data from the internet: they may be inaccurate, and the model may retell them wrongly.", systemImage: "exclamationmark.triangle")
                    .font(.footnote).foregroundStyle(.secondary)
            } header: {
                Text("Good to know")
            }

            Section {
                NavigationLink("Searches (\(searchCount))") { AssistantSearchesView() }
            } footer: {
                Text("Everything that was sent to the internet, and what it found.")
            }
        }
        .navigationTitle("Internet")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            searchCount = (try? await model.store.searches().count) ?? 0
        }
        .confirmationDialog("Allow web search?", isPresented: $confirmEnable, titleVisibility: .visible) {
            Button("Allow") { model.web.isEnabled = true }
        } message: {
            Text("A summary's “More detail” and “Deep analysis” then send a short query (a device's maker and model, or a link's site) to the search service you choose. Everything else stays on the phone.")
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
                    : String(localized: "Works: found \u{201c}\(results[0].title)\u{201d}.")
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

/// Every search: what was sent, when and where, and what it found while that is kept for reuse.
struct AssistantSearchesView: View {
    @Environment(AssistantModel.self) private var model
    @State private var searches: [PastSearch] = []
    @State private var confirmClear = false

    var body: some View {
        List {
            if !searches.isEmpty {
                Section {
                    ForEach(searches) { search in
                        if search.results.isEmpty {
                            row(search)
                        } else {
                            NavigationLink { AssistantSearchView(search: search) } label: { row(search) }
                        }
                    }
                    .onDelete(perform: delete)
                } footer: {
                    Text("What a search found is kept for 24 hours: a query worded almost the same is answered from it, and nothing is sent again. The pages are kept on the phone, encrypted with the summaries.")
                }
            }
        }
        .overlay {
            if searches.isEmpty {
                ContentUnavailableView("No queries yet", systemImage: "magnifyingglass")
            }
        }
        .navigationTitle("Searches")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Button("Clear", role: .destructive) { confirmClear = true }
                .disabled(searches.isEmpty)
        }
        .confirmationDialog("Forget all searches?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Forget", role: .destructive) {
                searches = []
                Task { try? await model.store.clearSearches() }
            }
        } message: {
            Text("The list and what the searches found are erased. Summaries stay.")
        }
        .task { searches = (try? await model.store.searches()) ?? [] }
    }

    private func row(_ search: PastSearch) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(search.query)
            HStack(spacing: 4) {
                Text(search.date, format: .dateTime.day().month().hour().minute())
                Text(verbatim: "· \(search.provider)")
                if !search.succeeded {
                    Text("· failed").foregroundStyle(.red)
                } else if !search.results.isEmpty {
                    Text("· found: \(search.results.count)")
                }
            }
            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
    }

    private func delete(_ offsets: IndexSet) {
        let gone = offsets.map { searches[$0] }
        searches.remove(atOffsets: offsets)
        Task {
            for search in gone { try? await model.store.deleteSearch(search) }
        }
    }
}

/// What one search found, while it is kept.
private struct AssistantSearchView: View {
    let search: PastSearch

    var body: some View {
        List {
            Section {
                ForEach(search.results, id: \.self) { result in
                    Link(destination: result.url) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(result.title).foregroundStyle(.tint)
                            Text(verbatim: result.url.host() ?? result.url.absoluteString)
                                .font(.caption).foregroundStyle(.secondary)
                            if !result.snippet.isEmpty {
                                Text(result.snippet).font(.footnote).foregroundStyle(.primary).lineLimit(4)
                            }
                        }
                    }
                }
            } header: {
                Text(search.query).textCase(nil)
            } footer: {
                if let until = search.keptUntil {
                    Text("Reused for similar queries until \(until.formatted(date: .abbreviated, time: .shortened)).")
                }
            }
        }
        .navigationTitle("Found")
        .navigationBarTitleDisplayMode(.inline)
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
                    Text("Will be sent to \u{201c}\(provider)\u{201d}")
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
