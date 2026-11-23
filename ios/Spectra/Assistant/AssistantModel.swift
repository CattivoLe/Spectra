import Foundation
import Observation
import UIKit
import LocalChatEngine
import LocalChatKit

/// Shared state of the Assistant tab: the chat store, the on-device engine and the model library.
@MainActor
@Observable
final class AssistantModel {
    let store: ChatStore
    let controller = EngineController()
    let library: ModelLibrary
    let lock = AppLock()
    let web = WebAccess()
    /// A query waiting for the user's OK before it goes to the internet.
    var pendingSearch: PendingSearch?
    /// The checks a summary card's step buttons run. Set once by the app.
    var actions: AssistantActions?
    /// The ✨ summary cards: findings at once, the model's plain words under them, buttons instead of a text field.
    /// Set once by the app, with the sources it reads.
    var cards: AssistantCardRunner?
    private let keyProvider = KeychainKeyProvider()

    static weak var shared: AssistantModel?
    /// The system may wake the app before the model exists: its request is parked here.
    static var pendingBackgroundCompletion: (() -> Void)?

    /// Name of the selected model, for display.
    var currentModelName: String? {
        library.installed.first { $0.fileName == selectedModel }?.displayName
    }

    /// The same, short enough for one line under the chat title.
    var currentModelMenuName: String? {
        library.installed.first { $0.fileName == selectedModel }?.menuName
    }

    /// The one model the assistant runs (chosen by comparing small models on Spectra's own tasks): the catalog's only
    /// chat model.
    var assistantCatalogModel: CatalogModel? { library.catalog.first { !$0.isEmbedding } }

    /// That model's file, once it is downloaded.
    var assistantModelFile: InstalledModel? {
        guard let wanted = assistantCatalogModel else { return nil }
        return library.installed.first { $0.catalog?.id == wanted.id }
    }

    /// Model files from before the assistant settled on one model: nothing loads them, they only take space.
    var otherModels: [InstalledModel] { library.installed.filter { $0.catalog == nil } }

    /// The assistant's model file, if it is downloaded: one at most.
    var modelFiles: [String] { assistantModelFile.map { [$0.fileName] } ?? [] }
    private(set) var loadError: String?
    /// When the database cannot be opened the tab runs on a temporary in-memory one and says so.
    private(set) var storageWarning: String?

    var selectedModel: String? {
        didSet {
            guard selectedModel != oldValue else { return }
            UserDefaults.standard.set(selectedModel, forKey: Self.selectedModelKey)
        }
    }

    static let selectedModelKey = "assistant.selectedModel"
    private let modelsDirectory: URL

    init() {
        modelsDirectory = URL.applicationSupportDirectory.appending(path: "Spectra/Models")
        try? FileManager.default.createDirectory(at: modelsDirectory, withIntermediateDirectories: true)
        let catalogData = Bundle.main.url(forResource: "AssistantModels", withExtension: "json").flatMap { try? Data(contentsOf: $0) }
        let catalog = (try? ModelCatalog.decode(catalogData ?? Data())) ?? []
        library = ModelLibrary(directory: modelsDirectory, catalog: catalog)
        library.wifiOnly = UserDefaults.standard.object(forKey: "assistant.wifiOnly") as? Bool ?? true

        let databaseURL = URL.applicationSupportDirectory.appending(path: "Spectra/assistant.sqlite")
        do {
            store = try ChatStore(url: databaseURL, keyProvider: keyProvider)
        } catch {
            // Last resort: the database file is damaged or unreachable.
            store = (try? ChatStore()) ?? { fatalError("Could not even create a temporary store") }()
            storageWarning = String(localized: "Could not open the saved summaries: \(error.localizedDescription). New ones will not be saved.")
        }
        selectedModel = UserDefaults.standard.string(forKey: Self.selectedModelKey)

        web.approve = { [weak self] query in await self?.requestSearchApproval(query) }
        library.backgroundEventsCompletion = Self.pendingBackgroundCompletion
        Self.pendingBackgroundCompletion = nil
        Self.shared = self
        Self.removeDossier()
        Task { await removeOldChats() }
        library.onInstalledChanged = { [weak self] in
            guard let self else { return }
            Task {
                self.refreshModels()
                if self.controller.state == .empty { await self.loadSelectedModel() }
            }
        }
    }

    func start() async {
        refreshModels()
        await loadSelectedModel()
    }

    /// The dossier the chat used to keep: its notes and the baselines of every device ever seen. Nothing reads it
    /// since the assistant became summary cards, so the file goes at start (it was rewritten on every autosave tick).
    static func removeDossier(in directory: URL = URL.applicationSupportDirectory.appending(path: "Spectra")) {
        try? FileManager.default.removeItem(at: directory.appending(path: "assistant_memory.json"))
    }

    private static let oldChatsRemovedKey = "assistant.oldChatsRemoved"

    /// The assistant was a chat before it became summary cards. Those chats are erased once, with whatever they
    /// searched: nothing shows them any more. Summaries and folders stay.
    func removeOldChats() async {
        guard !UserDefaults.standard.bool(forKey: Self.oldChatsRemovedKey) else { return }
        do {
            let old = try await store.chats().filter { !CardSubjects.isCard($0.subject) }
            for chat in old { try await store.deleteChat(chat.id) }
            if !old.isEmpty { try await store.vacuum() }
            UserDefaults.standard.set(true, forKey: Self.oldChatsRemovedKey)
        } catch {
            // Tried again at the next start.
        }
    }

    private var startup: Task<Void, Never>?
    /// Loads the model the first time anything needs the assistant: its tab, or a summary opened from another screen.
    /// The load runs in a task of its own: a caller that goes away (a screen closed mid-load) does not cancel it,
    /// and every caller waits for the same load instead of finding the engine busy.
    func ensureStarted() async {
        if startup == nil { startup = Task { await start() } }
        await startup?.value
    }

    func refreshModels() {
        library.refreshInstalled()
        updateEmbedder()
        // Always the assistant's own model: a model picked before there was only one is never loaded again.
        let wanted = assistantModelFile?.fileName
        if selectedModel != wanted { selectedModel = wanted }
    }

    /// The model for search by meaning joins the web search as soon as it is installed and leaves when it is deleted.
    /// It is loaded into memory only at the first search.
    private var embedderFile: String?
    private func updateEmbedder() {
        let file = library.embeddingModel?.fileName
        guard file != embedderFile else { return }
        embedderFile = file
        web.embedder = file.map { LazyEmbedder(modelAt: modelsDirectory.appending(path: $0)) }
    }

    /// Shows "Send this query to the internet?" and waits. Closing the sheet without choosing sends nothing.
    private func requestSearchApproval(_ query: String) async -> String? {
        await withCheckedContinuation { continuation in
            pendingSearch = PendingSearch(query: query, resume: continuation)
        }
    }

    /// Erases every summary and folder. Models and settings stay.
    func eraseChats() async throws {
        cards?.forgetAll()
        try await store.deleteEverything()
    }

    func deleteChat(_ id: UUID) async throws {
        cards?.forget(id)
        try await store.deleteChat(id)
    }

    // MARK: - Folders

    private static let summariesFolderKey = "assistant.summariesFolder"
    private static let recordingFolderKey = "assistant.recordingFolder"

    /// The folder every summary card is saved in unless the user chose another. Made on first use.
    func summariesFolder() async -> UUID? {
        if let saved = UserDefaults.standard.string(forKey: Self.summariesFolderKey).flatMap(UUID.init(uuidString:)),
           (try? await store.folders())?.contains(where: { $0.id == saved }) == true {
            return saved
        }
        guard let folder = try? await store.createFolder(name: String(localized: "Summaries")) else { return nil }
        UserDefaults.standard.set(folder.id.uuidString, forKey: Self.summariesFolderKey)
        return folder.id
    }

    /// The folder new summaries go to: the last one the user made or chose, until another is made or chosen.
    /// `nil` means the Summaries folder.
    private(set) var recordingFolderID: UUID? = UserDefaults.standard.string(forKey: recordingFolderKey).flatMap(UUID.init(uuidString:))

    func record(into folder: UUID?) {
        recordingFolderID = folder
        UserDefaults.standard.set(folder?.uuidString, forKey: Self.recordingFolderKey)
    }

    /// Where the next summary is saved: the recording folder while it exists, else Summaries.
    func recordingFolder() async -> UUID? {
        if let id = recordingFolderID {
            if (try? await store.folders())?.contains(where: { $0.id == id }) == true { return id }
            record(into: nil)
        }
        return await summariesFolder()
    }

    /// A new folder that new summaries are saved in from now on.
    @discardableResult
    func startFolder(named name: String) async -> UUID? {
        guard let folder = try? await store.createFolder(name: name) else { return nil }
        record(into: folder.id)
        return folder.id
    }

    /// The name of the folder a summary is in, for its screen's subtitle.
    func folderName(of chat: UUID) async -> String? {
        guard let folder = try? await store.chat(chat)?.folderID else { return nil }
        return (try? await store.folders())?.first { $0.id == folder }?.name
    }

    func setWifiOnly(_ value: Bool) {
        library.wifiOnly = value
        UserDefaults.standard.set(value, forKey: "assistant.wifiOnly")
    }

    /// Deletes a model file. If it is loaded, it is unloaded first.
    func delete(_ file: InstalledModel) async throws {
        if file.fileName == selectedModel {
            await controller.unload()
            selectedModel = nil
        }
        try library.delete(file)
        if controller.state == .empty { await loadSelectedModel() }
    }

    func loadSelectedModel() async {
        guard let file = selectedModel else { return }
        loadError = nil
        do {
            // 8192, as in LocalChat: app data, web excerpts and check results all ride in the question, and long chats
            // fit without trimming. The cost is KV-cache memory, measured on a Mac: +74 MB for Gemma 4 E2B,
            // +129 MB for Qwen 3.5 4B, +224 MB for Gemma 4 E4B.
            try await controller.load(modelAt: modelsDirectory.appending(path: file), config: EngineConfig(contextLength: 8192))
        } catch {
            loadError = error.localizedDescription
        }
    }
}

/// A request to go to the internet. It can be answered once.
@MainActor
final class PendingSearch: Identifiable {
    let id = UUID()
    let query: String
    private var resume: CheckedContinuation<String?, Never>?

    init(query: String, resume: CheckedContinuation<String?, Never>) {
        self.query = query
        self.resume = resume
    }

    func answer(_ edited: String?) {
        resume?.resume(returning: edited)
        resume = nil
    }
}

/// The system wakes the app when a background model download finishes: tell it the event was handled.
final class SpectraAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        if let model = AssistantModel.shared {
            model.library.backgroundEventsCompletion = completionHandler
        } else {
            AssistantModel.pendingBackgroundCompletion = completionHandler
        }
    }
}
