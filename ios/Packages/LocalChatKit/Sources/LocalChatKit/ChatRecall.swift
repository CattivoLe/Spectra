import Foundation
import GRDB

/// Something said in an earlier chat that bears on the question being asked now.
public struct Recollection: Sendable, Equatable, Identifiable {
    public var id: UUID
    public var chatTitle: String
    public var date: Date
    /// The answer that was given, shortened.
    public var text: String

    public init(id: UUID, chatTitle: String, date: Date, text: String) {
        self.id = id
        self.chatTitle = chatTitle
        self.date = date
        self.text = text
    }
}

extension ChatStore {
    /// Earlier answers that match the question, ranked by word overlap (BM25, the same ranking the web research
    /// uses on page excerpts). Deliberately not an embedding index: the chat database is small, the scoring is
    /// instant and needs no model loaded, and nothing has to be rebuilt when a chat is deleted.
    ///
    /// Only assistant replies are ranked — they are where the conclusions are — but each is scored on the
    /// question it answered as well as on itself, so "what was that camera?" finds the answer about the camera
    /// even when the answer never repeats the word.
    public func recall(
        _ question: String,
        excluding chatID: UUID?,
        limit: Int = 2,
        scanLimit: Int = 300,
        minimumScore: Double = 1.0
    ) async throws -> [Recollection] {
        let terms = Terms.of(question)
        guard terms.count >= 2 else { return [] }

        let rows = try await recentExchanges(excluding: chatID, limit: scanLimit)
        guard !rows.isEmpty else { return [] }

        let documents = rows.map { Terms.of($0.question + " " + $0.answer) }
        let scores = PassageSelector.bm25(query: terms, documents: documents)

        var chosen: [Recollection] = []
        var usedChats: Set<UUID> = []
        for index in scores.indices.sorted(by: { scores[$0] > scores[$1] }) {
            guard scores[index] >= minimumScore, chosen.count < limit else { break }
            let row = rows[index]
            guard !usedChats.contains(row.chatID) else { continue }
            usedChats.insert(row.chatID)
            chosen.append(Recollection(id: row.id, chatTitle: row.title, date: row.date, text: Self.shorten(row.answer)))
        }
        return chosen
    }

    private struct Exchange {
        var id: UUID
        var chatID: UUID
        var title: String
        var date: Date
        var question: String
        var answer: String
    }

    /// The most recent assistant replies, each paired with the user message right before it.
    private func recentExchanges(excluding chatID: UUID?, limit: Int) async throws -> [Exchange] {
        try await database.read { db in
            let sql = """
                SELECT a.id AS id, a.chatID AS chatID, c.title AS title, a.createdAt AS date,
                       COALESCE(q.content, '') AS question, a.content AS answer
                FROM message a
                JOIN chat c ON c.id = a.chatID
                LEFT JOIN message q ON q.chatID = a.chatID AND q.seq = a.seq - 1 AND q.role = 'user'
                WHERE a.role = 'assistant' AND LENGTH(a.content) > 40
                  \(chatID == nil ? "" : "AND a.chatID <> ?")
                ORDER BY a.createdAt DESC
                LIMIT \(limit)
                """
            let arguments: StatementArguments = chatID.map { [$0] } ?? []
            return try Row.fetchAll(db, sql: sql, arguments: arguments).map { row in
                Exchange(
                    id: row["id"], chatID: row["chatID"], title: row["title"],
                    date: row["date"], question: row["question"], answer: row["answer"]
                )
            }
        }
    }

    /// One answer cut to its first couple of sentences: enough to recognise the conclusion, not enough to
    /// crowd out the live data the question actually needs.
    static func shorten(_ text: String, limit: Int = 240) -> String {
        let flat = text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        guard flat.count > limit else { return flat }
        let cut = String(flat.prefix(limit))
        if let stop = cut.lastIndex(where: { ".!?".contains($0) }), cut.distance(from: cut.startIndex, to: stop) > limit / 2 {
            return String(cut[...stop])
        }
        return cut + "…"
    }
}
