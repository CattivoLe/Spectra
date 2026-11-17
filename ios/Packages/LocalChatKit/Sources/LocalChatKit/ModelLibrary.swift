import Foundation
import Observation

public struct InstalledModel: Identifiable, Hashable, Sendable {
    public var fileName: String
    public var sizeBytes: Int64
    /// Запись каталога, если файл совпадает по имени с одной из них.
    public var catalog: CatalogModel?

    public var id: String { fileName }
    /// The catalog's name, or one read off the file name. For lists, where a row has room for it.
    public var displayName: String { catalog?.name ?? Self.readableName(fileName: fileName) }
    /// What fits on one line of a menu, which wraps anything much past 16 characters.
    public var menuName: String {
        catalog?.shortName ?? catalog.map(\.name) ?? Self.readableName(fileName: fileName, maxLength: Self.menuNameLength)
    }

    public static let menuNameLength = 16
    /// An embedding model: it must not appear among the models one can chat with.
    public var isEmbedding: Bool { catalog?.isEmbedding ?? fileName.lowercased().contains("embedding") }

    /// "Llama-3.2-3B-Instruct-abliterated.Q4_K_M.gguf" → "Llama 3.2 3B abliterated": no extension, no
    /// quantization, no "instruct"/"it"/"chat"/"GGUF" tags, words split on dashes and underscores (dots stay:
    /// "3.2", "Qwen3.5"), and at most `maxLength` characters, cut between words with an ellipsis.
    public static func readableName(fileName: String, maxLength: Int = 26) -> String {
        var stem = fileName
        if stem.lowercased().hasSuffix(".gguf") { stem.removeLast(5) }
        let quantization = /(?i)[._-](i?q\d\w*|f16|bf16|f32|fp16)$/
        while let match = stem.firstMatch(of: quantization) { stem.removeSubrange(match.range) }
        let dropped: Set<String> = ["instruct", "it", "chat", "gguf"]
        let words = stem.split(whereSeparator: { $0 == "-" || $0 == "_" || $0 == " " })
            .map(String.init)
            .filter { !dropped.contains($0.lowercased()) }
        let name = words.joined(separator: " ")
        guard !name.isEmpty else { return fileName }
        guard name.count > maxLength else { return name }
        var kept = ""
        for word in words {
            let next = kept.isEmpty ? word : kept + " " + word
            if next.count + 1 > maxLength { break }
            kept = next
        }
        // A first word too long on its own is cut mid-word rather than dropped.
        if kept.isEmpty { kept = String(name.prefix(maxLength - 1)) }
        return kept + "…"
    }
}

public enum DownloadState: Equatable, Sendable {
    case downloading(received: Int64, total: Int64)
    case paused(received: Int64)
    /// Файл скачан, считается контрольная сумма.
    case verifying
    case failed(String)
}

/// Модели на устройстве: что установлено, что скачивается, импорт из «Файлов» и удаление.
///
/// Загрузка идёт через фоновую сессию: система продолжает её, пока приложение свёрнуто или даже закрыто,
/// а при обрыве связи докачивает с того же места. Каждый скачанный файл сверяется с контрольной суммой
/// из каталога и только потом становится доступным.
@MainActor
@Observable
public final class ModelLibrary {
    public let catalog: [CatalogModel]
    public private(set) var installed: [InstalledModel] = []
    public private(set) var downloads: [String: DownloadState] = [:]
    /// Имя файла, который сейчас копируется из «Файлов».
    public private(set) var importing: String?

    /// Скачивать только по Wi-Fi. Действует на загрузки, запущенные после изменения.
    public var wifiOnly = true
    /// Вызывается, когда набор установленных моделей изменился.
    public var onInstalledChanged: (() -> Void)?
    /// Система просит сообщить, что события фоновой сессии обработаны (см. AppDelegate).
    public var backgroundEventsCompletion: (() -> Void)?

    public let directory: URL
    private let staging: URL
    private let session: URLSession
    private let relay = Relay()
    private let freeSpace: @Sendable (URL) -> Int64

    private enum Stop { case pause, cancel }
    private var userStopped: [String: Stop] = [:]
    private var resumeData: [String: Data] = [:]

    public static func backgroundConfiguration(identifier: String = "cattivole.Spectra.assistant.models") -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.background(withIdentifier: identifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.waitsForConnectivity = true
        return configuration
    }

    public init(
        directory: URL,
        catalog: [CatalogModel],
        configuration: URLSessionConfiguration = ModelLibrary.backgroundConfiguration(),
        freeSpace: @escaping @Sendable (URL) -> Int64 = ModelFiles.availableSpace
    ) {
        self.directory = directory
        self.catalog = catalog
        self.staging = directory.appending(path: ".downloads")
        self.freeSpace = freeSpace
        try? FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        ModelFiles.excludeFromBackup(staging)

        let delegate = DownloadDelegate(staging: staging, relay: relay)
        self.session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        relay.library = self

        refreshInstalled()
        Task { await reattach() }
    }

    // MARK: - Установленные

    public func refreshInstalled() {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        installed = names.filter { $0.lowercased().hasSuffix(".gguf") }.sorted().map { name in
            // Models put in the folder past the app (through Files or from a computer) must not go to iCloud either.
            ModelFiles.excludeFromBackup(directory.appending(path: name))
            let size = (try? FileManager.default.attributesOfItem(atPath: directory.appending(path: name).path)[.size] as? Int64) ?? 0
            return InstalledModel(fileName: name, sizeBytes: size ?? 0, catalog: catalog.first { $0.fileName == name })
        }
    }

    /// Models one can chat with.
    public var chatModels: [InstalledModel] { installed.filter { !$0.isEmbedding } }
    /// The installed model for search by meaning, if any.
    public var embeddingModel: InstalledModel? { installed.first { $0.isEmbedding } }

    public func isInstalled(_ model: CatalogModel) -> Bool {
        installed.contains { $0.fileName == model.fileName }
    }

    public func delete(_ file: InstalledModel) throws {
        try FileManager.default.removeItem(at: directory.appending(path: file.fileName))
        refreshInstalled()
        onInstalledChanged?()
    }

    /// Копирует выбранный в «Файлах» файл в папку моделей. Возвращает имя установленного файла.
    @discardableResult
    public func importFile(from source: URL) async throws -> String {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }

        guard ModelFiles.isGGUF(source) else { throw ModelFileError.notGGUF }
        let size = (try? source.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        let available = freeSpace(directory)
        guard available >= size + 100_000_000 else { throw ModelFileError.notEnoughSpace(needed: size, available: available) }

        let target = ModelFiles.uniqueURL(for: source.lastPathComponent, in: directory)
        importing = target.lastPathComponent
        defer { importing = nil }

        try await Task.detached {
            try FileManager.default.copyItem(at: source, to: target)
        }.value
        ModelFiles.excludeFromBackup(target)
        refreshInstalled()
        onInstalledChanged?()
        return target.lastPathComponent
    }

    // MARK: - Загрузка

    /// Начинает загрузку или продолжает приостановленную.
    public func download(_ model: CatalogModel) {
        switch downloads[model.id] {
        case .downloading, .verifying: return
        default: break
        }

        let received: Int64
        if case .paused(let bytes) = downloads[model.id] { received = bytes } else { received = 0 }
        let needed = model.sizeBytes - received
        let available = freeSpace(directory)
        guard available >= needed + 100_000_000 else {
            downloads[model.id] = .failed(ModelFileError.notEnoughSpace(needed: needed, available: available).localizedDescription)
            return
        }

        userStopped[model.id] = nil
        let task: URLSessionDownloadTask
        if let data = resumeData[model.id] ?? (try? Data(contentsOf: resumeFile(model.id))) {
            task = session.downloadTask(withResumeData: data)
        } else {
            var request = URLRequest(url: model.url)
            request.allowsCellularAccess = !wifiOnly
            task = session.downloadTask(with: request)
        }
        task.taskDescription = model.id
        downloads[model.id] = .downloading(received: received, total: model.sizeBytes)
        task.resume()
    }

    /// Останавливает загрузку, сохранив скачанное: потом её можно продолжить.
    public func pause(_ model: CatalogModel) {
        guard case .downloading(let received, _) = downloads[model.id] else { return }
        userStopped[model.id] = .pause
        downloads[model.id] = .paused(received: received)
        let id = model.id
        Task {
            for task in await session.allTasks where task.taskDescription == id {
                (task as? URLSessionDownloadTask)?.cancel(byProducingResumeData: { [weak self] data in
                    guard let data else { return }
                    Task { @MainActor in self?.storeResumeData(data, for: id) }
                })
            }
        }
    }

    /// Прекращает загрузку и стирает всё скачанное.
    public func cancel(_ model: CatalogModel) {
        userStopped[model.id] = .cancel
        let id = model.id
        Task { for task in await session.allTasks where task.taskDescription == id { task.cancel() } }
        discardPartialFiles(for: id)
        downloads[id] = nil
    }

    // MARK: - События загрузчика

    fileprivate func didProgress(_ id: String, written: Int64, total: Int64) {
        guard userStopped[id] == nil else { return }
        let expected = total > 0 ? total : (catalog.first { $0.id == id }?.sizeBytes ?? 0)
        downloads[id] = .downloading(received: written, total: expected)
    }

    fileprivate func didStage(_ id: String, file: URL) {
        downloads[id] = .verifying
        Task { await verify(id, staged: file) }
    }

    fileprivate func didFail(_ id: String, message: String, wasCancelled: Bool, resumeData data: Data?) {
        if let data { storeResumeData(data, for: id) }
        switch userStopped[id] {
        case .pause:
            return
        case .cancel:
            userStopped[id] = nil
            downloads[id] = nil
        case nil:
            downloads[id] = .failed(wasCancelled ? String(localized: "The system interrupted the download.") : message)
        }
    }

    fileprivate func backgroundEventsFinished() {
        backgroundEventsCompletion?()
        backgroundEventsCompletion = nil
    }

    // MARK: - Проверка и установка

    private func verify(_ id: String, staged: URL) async {
        guard let model = catalog.first(where: { $0.id == id }) else { return }
        let expected = model.sha256
        let matches = await Task.detached { (try? ModelFiles.sha256(of: staged)) == expected }.value

        guard matches else {
            try? FileManager.default.removeItem(at: staged)
            discardPartialFiles(for: id)
            downloads[id] = .failed(ModelFileError.checksumMismatch.localizedDescription)
            return
        }

        let target = directory.appending(path: model.fileName)
        do {
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: staged, to: target)
            ModelFiles.excludeFromBackup(target)
            discardPartialFiles(for: id)
            downloads[id] = nil
            refreshInstalled()
            onInstalledChanged?()
        } catch {
            downloads[id] = .failed(error.localizedDescription)
        }
    }

    /// После запуска приложения: подхватывает загрузки, которые система вела без нас, и дорабатывает прерванное.
    private func reattach() async {
        let tasks = await session.allTasks
        for task in tasks {
            guard let id = task.taskDescription, let model = catalog.first(where: { $0.id == id }) else { continue }
            let total = task.countOfBytesExpectedToReceive > 0 ? task.countOfBytesExpectedToReceive : model.sizeBytes
            downloads[id] = .downloading(received: task.countOfBytesReceived, total: total)
        }
        for model in catalog where downloads[model.id] == nil {
            let staged = staging.appending(path: "\(model.id).part")
            if FileManager.default.fileExists(atPath: staged.path) {
                // Файл скачан целиком, но приложение закрыли до проверки.
                downloads[model.id] = .verifying
                await verify(model.id, staged: staged)
            } else if FileManager.default.fileExists(atPath: resumeFile(model.id).path) {
                downloads[model.id] = .paused(received: 0)
            }
        }
    }

    // MARK: - Служебное

    private func resumeFile(_ id: String) -> URL { staging.appending(path: "\(id).resume") }

    private func storeResumeData(_ data: Data, for id: String) {
        resumeData[id] = data
        try? data.write(to: resumeFile(id), options: .atomic)
    }

    private func discardPartialFiles(for id: String) {
        resumeData[id] = nil
        try? FileManager.default.removeItem(at: resumeFile(id))
        try? FileManager.default.removeItem(at: staging.appending(path: "\(id).part"))
    }
}

// MARK: - Загрузчик

/// Мост от делегата сессии (он живёт на системных очередях) к главному потоку.
private final class Relay: @unchecked Sendable {
    weak var library: ModelLibrary?
}

private final class DownloadDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let staging: URL
    private let relay: Relay

    init(staging: URL, relay: Relay) {
        self.staging = staging
        self.relay = relay
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription else { return }
        let relay = relay
        Task { @MainActor in relay.library?.didProgress(id, written: totalBytesWritten, total: totalBytesExpectedToWrite) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = downloadTask.taskDescription else { return }
        let relay = relay

        if let http = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            let message = ModelFileError.http(http.statusCode).localizedDescription
            Task { @MainActor in relay.library?.didFail(id, message: message, wasCancelled: false, resumeData: nil) }
            return
        }

        // Временный файл удаляется сразу после возврата из этого метода: переносим его здесь же.
        let target = staging.appending(path: "\(id).part")
        do {
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: location, to: target)
            Task { @MainActor in relay.library?.didStage(id, file: target) }
        } catch {
            let message = error.localizedDescription
            Task { @MainActor in relay.library?.didFail(id, message: message, wasCancelled: false, resumeData: nil) }
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, let id = task.taskDescription else { return }
        let nsError = error as NSError
        let data = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data
        let cancelled = nsError.code == NSURLErrorCancelled
        let message = error.localizedDescription
        let relay = relay
        Task { @MainActor in relay.library?.didFail(id, message: message, wasCancelled: cancelled, resumeData: data) }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let relay = relay
        Task { @MainActor in relay.library?.backgroundEventsFinished() }
    }
}
