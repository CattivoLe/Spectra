import Foundation
import GRDB
import LocalChatEngine

public enum ChatStoreError: LocalizedError {
    case encryptionMismatch
    /// The chat was deleted (say, while the model was still writing into it): there is nothing to add to.
    case chatMissing

    public var errorDescription: String? {
        switch self {
        case .encryptionMismatch:
            String(localized: "Could not move the chats into the encrypted database: the record counts differ. The previous database was left untouched.")
        case .chatMissing:
            "The chat no longer exists."
        }
    }
}

/// Хранилище диалогов на SQLite. Всё лежит на устройстве, в облако ничего не уходит.
public final class ChatStore: Sendable {
    /// Internal rather than private so `ChatRecall` can query across chats from its own file.
    let database: DatabaseQueue

    /// `url == nil` — база в памяти (для тестов). Если задан `keyProvider`, файл базы шифруется (SQLCipher, AES-256);
    /// старая незашифрованная база при этом переносится в зашифрованную автоматически.
    public init(url: URL? = nil, keyProvider: (any DatabaseKeyProvider)? = nil) throws {
        let hexKey = try keyProvider?.key().map { String(format: "%02x", $0) }.joined()

        var configuration = Configuration()
        if let hexKey {
            // Сырой ключ без растяжения: он и так случаен.
            configuration.prepareDatabase { db in try db.execute(sql: "PRAGMA key = \"x'\(hexKey)'\"") }
        }

        if let url {
            let directory = url.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            if let hexKey, Self.isPlaintextDatabase(at: url) {
                try Self.encryptInPlace(url, hexKey: hexKey)
            }
            database = try DatabaseQueue(path: url.path, configuration: configuration)
        } else {
            database = try DatabaseQueue(configuration: configuration)
        }
        try Self.migrator.migrate(database)
        if let url { Self.protectFiles(at: url) }
    }

    // MARK: - Шифрование файла

    /// Обычная база SQLite начинается с видимой строки «SQLite format 3»; зашифрованная выглядит как случайные байты.
    public static func isPlaintextDatabase(at url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url), let head = try? handle.read(upToCount: 16) else { return false }
        try? handle.close()
        return head == Data("SQLite format 3\0".utf8)
    }

    /// Переносит данные из открытой базы в зашифрованную копию, сверяет число записей и только потом подменяет файл.
    /// Если что-то пошло не так, исходный файл остаётся нетронутым.
    private static func encryptInPlace(_ url: URL, hexKey: String) throws {
        let temporary = url.deletingLastPathComponent().appending(path: url.lastPathComponent + ".encrypting")
        for suffix in ["", "-wal", "-shm", "-journal"] {
            try? FileManager.default.removeItem(atPath: temporary.path + suffix)
        }

        let plain = try DatabaseQueue(path: url.path)
        let counts: (chats: Int, messages: Int) = try plain.writeWithoutTransaction { db in
            try db.execute(sql: "ATTACH DATABASE ? AS encrypted KEY \"x'\(hexKey)'\"", arguments: [temporary.path])
            try db.execute(sql: "SELECT sqlcipher_export('encrypted')")
            let chats = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM chat") ?? 0
            let messages = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM message") ?? 0
            let exportedChats = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM encrypted.chat") ?? -1
            let exportedMessages = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM encrypted.message") ?? -1
            try db.execute(sql: "DETACH DATABASE encrypted")
            guard (chats, messages) == (exportedChats, exportedMessages) else {
                throw ChatStoreError.encryptionMismatch
            }
            return (chats, messages)
        }
        _ = counts

        // Новая база должна открываться ключом, прежде чем заменит старую.
        var check = Configuration()
        check.prepareDatabase { db in try db.execute(sql: "PRAGMA key = \"x'\(hexKey)'\"") }
        let verified = try DatabaseQueue(path: temporary.path, configuration: check)
        _ = try verified.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM message") }

        _ = try FileManager.default.replaceItemAt(url, withItemAt: temporary)
        for suffix in ["-wal", "-shm", "-journal"] {
            try? FileManager.default.removeItem(atPath: url.path + suffix)
        }
    }

    /// Диалоги не нужны в резервной копии iCloud: ключ всё равно остаётся на этом устройстве, и в копии они были бы бесполезны.
    private static func protectFiles(at url: URL) {
        for suffix in ["", "-wal", "-shm"] {
            var file = URL(fileURLWithPath: url.path + suffix)
            guard FileManager.default.fileExists(atPath: file.path) else { continue }
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? file.setResourceValues(values)
            #if os(iOS)
            try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: file.path)
            #endif
        }
    }

    /// Удаляет все диалоги и сжимает файл, чтобы удалённое не лежало в свободных страницах базы.
    public func deleteEverything() async throws {
        try await database.write { db in
            try db.execute(sql: "DELETE FROM message")
            try db.execute(sql: "DELETE FROM chat")
            try db.execute(sql: "DELETE FROM folder")
            try db.execute(sql: "DELETE FROM websearch_log")
            try db.execute(sql: "DELETE FROM websearch_cache")
        }
        try await database.vacuum()
    }

    private static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()
        migrator.registerMigration("v1") { db in
            try db.create(table: "chat") { table in
                table.primaryKey("id", .blob)
                table.column("title", .text).notNull()
                table.column("createdAt", .datetime).notNull()
                table.column("updatedAt", .datetime).notNull()
                table.column("modelName", .text)
                // No longer read: search follows the one switch in the web settings. Kept so existing databases need no migration.
                table.column("webSearch", .boolean).notNull().defaults(to: false)
                // No longer read: app data and live checks are always on. Kept so existing databases need no migration.
                table.column("appData", .boolean).notNull().defaults(to: true)
            }
            try db.create(table: "message") { table in
                table.primaryKey("id", .blob)
                table.column("chatID", .blob).notNull().indexed().references("chat", onDelete: .cascade)
                table.column("seq", .integer).notNull()
                table.column("role", .text).notNull()
                table.column("content", .text).notNull()
                table.column("createdAt", .datetime).notNull()
                table.column("interrupted", .boolean).notNull().defaults(to: false)
                table.column("modelName", .text)
                table.column("search", .text)
                table.uniqueKey(["chatID", "seq"])
            }
            try db.create(table: "websearch_log") { table in
                table.autoIncrementedPrimaryKey("id")
                table.column("date", .datetime).notNull()
                table.column("provider", .text).notNull()
                table.column("query", .text).notNull()
                table.column("succeeded", .boolean).notNull()
            }
        }
        migrator.registerMigration("v2-search-cache") { db in
            try db.create(table: "websearch_cache") { table in
                table.autoIncrementedPrimaryKey("id")
                table.column("date", .datetime).notNull().indexed()
                table.column("provider", .text).notNull()
                table.column("query", .text).notNull()
                table.column("terms", .text).notNull()
                table.column("gathered", .blob).notNull()
            }
        }
        migrator.registerMigration("v3-folders-and-subjects") { db in
            try db.create(table: "folder") { table in
                table.primaryKey("id", .blob)
                table.column("name", .text).notNull()
                table.column("createdAt", .datetime).notNull()
            }
            try db.alter(table: "chat") { table in
                table.add(column: "folderID", .blob).references("folder", onDelete: .setNull)
                // What a summary card is about (`spectra-card:…`); chats of earlier versions have none.
                table.add(column: "subject", .text)
            }
        }
        return migrator
    }

    // MARK: - Диалоги

    public func createChat(subject: String? = nil, folder: UUID? = nil) async throws -> Chat {
        let now = Date()
        let chat = Chat(id: UUID(), title: "", createdAt: now, updatedAt: now, modelName: nil, subject: subject, folderID: folder)
        try await database.write { try chat.insert($0) }
        return chat
    }

    public func chat(_ id: UUID) async throws -> Chat? {
        try await database.read { try Chat.fetchOne($0, id: id) }
    }

    // MARK: - Folders

    public func folders() async throws -> [ChatFolder] {
        try await database.read { try ChatFolder.order(sql: "name COLLATE NOCASE").fetchAll($0) }
    }

    /// The folders, again on every change.
    public func observeFolders() -> AsyncThrowingStream<[ChatFolder], Error> {
        observe { try ChatFolder.order(sql: "name COLLATE NOCASE").fetchAll($0) }
    }

    @discardableResult
    public func createFolder(name: String) async throws -> ChatFolder {
        let folder = ChatFolder(id: UUID(), name: name.trimmingCharacters(in: .whitespacesAndNewlines), createdAt: Date())
        try await database.write { try folder.insert($0) }
        return folder
    }

    public func renameFolder(_ id: UUID, to name: String) async throws {
        try await database.write { db in
            try db.execute(sql: "UPDATE folder SET name = ? WHERE id = ?", arguments: [name.trimmingCharacters(in: .whitespacesAndNewlines), id])
        }
    }

    /// Deletes the folder only: its chats stay, outside any folder.
    public func deleteFolder(_ id: UUID) async throws {
        try await database.write { db in
            try db.execute(sql: "UPDATE chat SET folderID = NULL WHERE folderID = ?", arguments: [id])
            _ = try ChatFolder.deleteOne(db, id: id)
        }
    }

    public func moveChat(_ chatID: UUID, to folderID: UUID?) async throws {
        try await database.write { db in
            try db.execute(sql: "UPDATE chat SET folderID = ? WHERE id = ?", arguments: [folderID, chatID])
        }
    }

    // MARK: - Поиск в интернете

    public func setSearch(_ payload: SearchPayload?, for messageID: UUID) async throws {
        try await database.write { db in
            guard var message = try StoredMessage.fetchOne(db, id: messageID) else { return }
            message.search = payload
            try message.update(db)
        }
    }

    public func logSearch(query: String, provider: String, succeeded: Bool) async throws {
        try await database.write { db in
            var entry = SearchLogEntry(id: nil, date: Date(), provider: provider, query: query, succeeded: succeeded)
            try entry.insert(db)
        }
    }

    public func searchLog(limit: Int = 200) async throws -> [SearchLogEntry] {
        try await database.read { try SearchLogEntry.order(Column("date").desc).limit(limit).fetchAll($0) }
    }

    /// Removes one entry; the rest of the log stays.
    public func deleteSearchLogEntry(id: Int64) async throws {
        try await database.write { _ = try SearchLogEntry.deleteOne($0, key: id) }
    }

    public func clearSearchLog() async throws {
        try await database.write { _ = try SearchLogEntry.deleteAll($0) }
        try await database.vacuum()
    }

    public func chats() async throws -> [Chat] {
        try await database.read { try Chat.order(Column("updatedAt").desc).fetchAll($0) }
    }

    /// Поток списка диалогов: приходит заново при любом изменении.
    public func observeChats() -> AsyncThrowingStream<[Chat], Error> {
        observe { try Chat.order(Column("updatedAt").desc).fetchAll($0) }
    }

    private func observe<Value: Sendable>(_ fetch: @escaping @Sendable (Database) throws -> Value) -> AsyncThrowingStream<Value, Error> {
        let observation = ValueObservation.tracking(fetch)
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for try await value in observation.values(in: database) { continuation.yield(value) }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    public func rename(_ chatID: UUID, to title: String) async throws {
        try await database.write { db in
            try db.execute(sql: "UPDATE chat SET title = ? WHERE id = ?", arguments: [title, chatID])
        }
    }

    public func deleteChat(_ chatID: UUID) async throws {
        try await database.write { db in _ = try Chat.deleteOne(db, id: chatID) }
    }

    // MARK: - Сообщения

    public func messages(in chatID: UUID) async throws -> [StoredMessage] {
        try await database.read { db in
            try StoredMessage.filter(Column("chatID") == chatID).order(Column("seq")).fetchAll(db)
        }
    }

    /// Добавляет сообщение в конец диалога. Заголовок пустого диалога берётся из первой реплики пользователя.
    @discardableResult
    public func append(_ role: ChatMessage.Role, _ content: String, to chatID: UUID, modelName: String? = nil) async throws -> StoredMessage {
        try await database.write { db in
            // Deleted while the model was writing into it: a defined error instead of a foreign-key failure.
            guard try Chat.exists(db, id: chatID) else { throw ChatStoreError.chatMissing }
            let last = try Int.fetchOne(db, sql: "SELECT MAX(seq) FROM message WHERE chatID = ?", arguments: [chatID]) ?? 0
            let message = StoredMessage(id: UUID(), chatID: chatID, seq: last + 1, role: role,
                                        content: content, createdAt: Date(), interrupted: false, modelName: modelName, search: nil)
            try message.insert(db)
            try db.execute(sql: "UPDATE chat SET updatedAt = ? WHERE id = ?", arguments: [message.createdAt, chatID])
            if let modelName {
                try db.execute(sql: "UPDATE chat SET modelName = ? WHERE id = ?", arguments: [modelName, chatID])
            }
            if role == .user, let chat = try Chat.fetchOne(db, id: chatID), chat.title.isEmpty {
                try db.execute(sql: "UPDATE chat SET title = ? WHERE id = ?", arguments: [Self.title(from: content), chatID])
            }
            return message
        }
    }

    public func update(_ messageID: UUID, content: String, interrupted: Bool = false) async throws {
        try await database.write { db in
            try db.execute(sql: "UPDATE message SET content = ?, interrupted = ? WHERE id = ?",
                           arguments: [content, interrupted, messageID])
        }
    }

    /// Удаляет сообщения диалога, начиная с номера `seq` включительно.
    public func deleteMessages(in chatID: UUID, fromSeq seq: Int) async throws {
        try await database.write { db in
            try db.execute(sql: "DELETE FROM message WHERE chatID = ? AND seq >= ?", arguments: [chatID, seq])
        }
    }

    /// Room for two lines in the list: the subject of a ✨ summary sits past its first 40 characters.
    public static let titleLength = 90

    public static func title(from text: String) -> String {
        let line = text.split(whereSeparator: \.isNewline).first.map(String.init) ?? text
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        return trimmed.count > titleLength ? String(trimmed.prefix(titleLength)) + "…" : trimmed
    }
}
