import Foundation
import GRDB

/// What one web search brought back (the results and the pages read), kept so that a similar query soon after is
/// answered from it instead of going online again. The assistant often asks the same thing in slightly different
/// words; a second trip costs time and tells the search service the same thing twice.
public enum SearchCache {
    /// How long a search stays reusable. News and prices move, so not longer than a day.
    public static let lifetime: TimeInterval = 24 * 3600
    /// Entries kept at most; the oldest go first.
    public static let limit = 200

    /// Two queries are "the same search" when their meaningful words (stop words dropped, endings cut — the same
    /// `Terms` the passage ranking uses) mostly coincide: a Jaccard overlap of at least 0.7, or one query being the
    /// other plus a single extra word. Order, case, punctuation and word endings do not matter.
    public static func isSimilar(_ a: String, _ b: String) -> Bool {
        similarity(Set(Terms.of(a)), Set(Terms.of(b)))
    }

    static func similarity(_ a: Set<String>, _ b: Set<String>) -> Bool {
        guard !a.isEmpty, !b.isEmpty else { return false }
        if a == b { return true }
        let shared = a.intersection(b).count
        let overlap = Double(shared) / Double(a.union(b).count)
        if overlap >= 0.7 { return true }
        let (small, large) = a.count <= b.count ? (a, b) : (b, a)
        return small.count >= 2 && small.isSubset(of: large) && large.count - small.count == 1
    }

    static func key(_ query: String) -> String {
        Set(Terms.of(query)).sorted().joined(separator: " ")
    }
}

/// A row of the cache table.
struct CachedSearch: Codable, FetchableRecord, PersistableRecord {
    static let databaseTableName = "websearch_cache"

    var id: Int64?
    var date: Date
    var provider: String
    var query: String
    /// `SearchCache.key(query)`, the words compared.
    var terms: String
    /// `WebResearch.Gathered` as JSON.
    var gathered: Data

    mutating func didInsert(_ inserted: InsertionSuccess) { id = inserted.rowID }
}

/// A reusable earlier search.
public struct SearchCacheHit: Sendable {
    public var query: String
    public var date: Date
    public var gathered: WebResearch.Gathered
}

extension ChatStore {
    /// The freshest earlier search by the same provider whose query is similar to `query`, if one is still fresh.
    public func cachedSearch(for query: String, provider: String, now: Date = Date()) async throws -> SearchCacheHit? {
        let wanted = Set(Terms.of(query))
        guard !wanted.isEmpty else { return nil }
        let rows = try await database.read { db in
            try CachedSearch
                .filter(Column("provider") == provider && Column("date") > now.addingTimeInterval(-SearchCache.lifetime))
                .order(Column("date").desc)
                .fetchAll(db)
        }
        for row in rows {
            let terms = Set(row.terms.split(separator: " ").map(String.init))
            guard SearchCache.similarity(wanted, terms),
                  let gathered = try? JSONDecoder().decode(WebResearch.Gathered.self, from: row.gathered),
                  !gathered.results.isEmpty else { continue }
            return SearchCacheHit(query: row.query, date: row.date, gathered: gathered)
        }
        return nil
    }

    /// Keeps what a search brought back. Replaces an earlier entry with the same words; drops expired ones and
    /// whatever exceeds `SearchCache.limit`.
    public func cacheSearch(query: String, provider: String, gathered: WebResearch.Gathered, now: Date = Date()) async throws {
        guard !gathered.results.isEmpty, let data = try? JSONEncoder().encode(gathered) else { return }
        let key = SearchCache.key(query)
        guard !key.isEmpty else { return }
        try await database.write { db in
            _ = try CachedSearch.filter(Column("provider") == provider && Column("terms") == key).deleteAll(db)
            var row = CachedSearch(id: nil, date: now, provider: provider, query: query, terms: key, gathered: data)
            try row.insert(db)
            _ = try CachedSearch.filter(Column("date") <= now.addingTimeInterval(-SearchCache.lifetime)).deleteAll(db)
            let surplus = try CachedSearch.fetchCount(db) - SearchCache.limit
            if surplus > 0 {
                let old = try CachedSearch.order(Column("date").asc).limit(surplus).fetchAll(db).compactMap(\.id)
                _ = try CachedSearch.deleteAll(db, keys: old)
            }
        }
    }

    public func searchCacheCount() async throws -> Int {
        try await database.read { try CachedSearch.fetchCount($0) }
    }

    public func clearSearchCache() async throws {
        try await database.write { _ = try CachedSearch.deleteAll($0) }
        try await database.vacuum()
    }
}
