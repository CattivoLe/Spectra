import Foundation
import GRDB
import LocalChatEngine

public enum ChatStoreError: LocalizedError {
    case encryptionMismatch

    public var errorDescription: String? {
        String(localized: "Could not move the chats into the encrypted database: the record counts differ. The previous database was left untouched.")
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
            try db.execute(sql: "DELETE FROM websearch_log")
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
        return migrator
    }

    // MARK: - Диалоги

    public func createChat() async throws -> Chat {
        let now = Date()
        let chat = Chat(id: UUID(), title: "", createdAt: now, updatedAt: now, modelName: nil)
        try await database.write { try chat.insert($0) }
        return chat
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

    public func clearSearchLog() async throws {
        try await database.write { _ = try SearchLogEntry.deleteAll($0) }
        try await database.vacuum()
    }

    public func chats() async throws -> [Chat] {
        try await database.read { try Chat.order(Column("updatedAt").desc).fetchAll($0) }
    }

    /// Поток списка диалогов: приходит заново при любом изменении.
    public func observeChats() -> AsyncThrowingStream<[Chat], Error> {
        let observation = ValueObservation.tracking { try Chat.order(Column("updatedAt").desc).fetchAll($0) }
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

    static func title(from text: String) -> String {
        let line = text.split(whereSeparator: \.isNewline).first.map(String.init) ?? text
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        return trimmed.count > 40 ? String(trimmed.prefix(40)) + "…" : trimmed
    }
}
