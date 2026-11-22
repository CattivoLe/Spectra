import Foundation
import GRDB
import LocalChatEngine

/// A saved summary (or a chat of an earlier version). Spectra keeps what the summary is about in `subject`.
public struct Chat: Codable, Identifiable, Hashable, Sendable, FetchableRecord, PersistableRecord {
    public static let databaseTableName = "chat"

    public var id: UUID
    public var title: String
    public var createdAt: Date
    public var updatedAt: Date
    /// The model the chat was last held with.
    public var modelName: String?
    /// What a summary card is about, encoded by the app (`spectra-card:…`); `nil` for chats of earlier versions.
    public var subject: String?
    /// The folder the chat was put in; `nil` — not in any.
    public var folderID: UUID?

    public init(id: UUID, title: String, createdAt: Date, updatedAt: Date, modelName: String? = nil, subject: String? = nil, folderID: UUID? = nil) {
        self.id = id
        self.title = title
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.modelName = modelName
        self.subject = subject
        self.folderID = folderID
    }
}

/// A folder of summaries, made by the user.
public struct ChatFolder: Codable, Identifiable, Hashable, Sendable, FetchableRecord, PersistableRecord {
    public static let databaseTableName = "folder"

    public var id: UUID
    public var name: String
    public var createdAt: Date

    public init(id: UUID, name: String, createdAt: Date) {
        self.id = id
        self.name = name
        self.createdAt = createdAt
    }
}

/// One message of a conversation.
public struct StoredMessage: Codable, Identifiable, Hashable, Sendable, FetchableRecord, PersistableRecord {
    public static let databaseTableName = "message"

    public var id: UUID
    public var chatID: UUID
    /// Position in the chat: messages are ordered and trimmed by it.
    public var seq: Int
    public var role: ChatMessage.Role
    public var content: String
    public var createdAt: Date
    /// The answer was cut off by the user, by leaving the screen, or by running out of memory.
    public var interrupted: Bool
    /// Which model wrote the answer.
    public var modelName: String?
    /// What was found on the web for this question (user messages). Kept so "Regenerate" does not go online again.
    public var search: SearchPayload?

    public var asChatMessage: ChatMessage { ChatMessage(role, content) }
}

/// Facts from the host app (Wi-Fi scan, LAN, Bluetooth, history, connection check) that go in front of a question.
/// The assistant reads them from the phone; nothing here ever leaves the device.
public struct AppDataAttachment: Equatable, Sendable {
    /// Short names of what was attached, shown under the answer ("Wi‑Fi", "LAN").
    public var topics: [String]
    /// The text placed ahead of the question.
    public var text: String

    public init(topics: [String], text: String) {
        self.topics = topics
        self.text = text
    }
}

/// A log entry: what exactly was sent to the internet and where. Visible in settings.
public struct SearchLogEntry: Codable, Identifiable, Hashable, Sendable, FetchableRecord, PersistableRecord {
    public static let databaseTableName = "websearch_log"

    public var id: Int64?
    public var date: Date
    public var provider: String
    public var query: String
    public var succeeded: Bool

    public mutating func didInsert(_ inserted: InsertionSuccess) { id = inserted.rowID }
}
