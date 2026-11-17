import Foundation
import LocalChatEngine

/// Векторы смысла для вопроса и кусков. Разные модели ждут разные пометки перед текстом,
/// поэтому вопрос и кусок кодируются по-разному.
public protocol PassageEmbedder: Sendable {
    func embed(queries: [String]) async throws -> [[Float]]
    func embed(passages: [String]) async throws -> [[Float]]
}

/// Эмбеддинги через llama.cpp с пометками, которых ждёт конкретная модель.
public struct LlamaPassageEmbedder: PassageEmbedder {
    public enum Style: Sendable {
        /// multilingual-e5: «query: …» и «passage: …».
        case e5
        /// EmbeddingGemma: «task: search result | query: …» и «title: none | text: …».
        case embeddingGemma
        case plain

        public static func guess(fileName: String) -> Style {
            let name = fileName.lowercased()
            if name.contains("e5") { return .e5 }
            if name.contains("embeddinggemma") { return .embeddingGemma }
            return .plain
        }
    }

    private let engine: EmbeddingEngine
    private let style: Style

    public init(engine: EmbeddingEngine, style: Style) {
        self.engine = engine
        self.style = style
    }

    public func embed(queries: [String]) async throws -> [[Float]] {
        try await engine.embed(queries.map { query in
            switch style {
            case .e5: "query: \(query)"
            case .embeddingGemma: "task: search result | query: \(query)"
            case .plain: query
            }
        })
    }

    public func embed(passages: [String]) async throws -> [[Float]] {
        try await engine.embed(passages.map { passage in
            switch style {
            case .e5: "passage: \(passage)"
            case .embeddingGemma: "title: none | text: \(passage)"
            case .plain: passage
            }
        })
    }
}

/// Способ отбора кусков: по словам, по смыслу или оба сразу.
public enum RankingMethod: String, Sendable, CaseIterable {
    case words, meaning, hybrid
}

extension PassageSelector {
    /// Порядок кусков по убыванию полезности.
    /// Гибрид сливает два списка «по местам» (Reciprocal Rank Fusion): кусок, высоко стоящий хотя бы в одном
    /// из них, поднимается вверх, и не нужно подбирать, как сравнивать очки BM25 со сходством векторов.
    public static func order(
        _ passages: [Passage],
        for query: String,
        method: RankingMethod,
        queryVector: [Float]? = nil,
        passageVectors: [[Float]]? = nil
    ) -> [Int] {
        let wordScores = bm25(query: Array(Set(Terms.of(query))), documents: passages.map { Terms.of(($0.heading ?? "") + " " + $0.text) })
        let wordOrder = wordScores.indices.filter { wordScores[$0] > 0 }.sorted { wordScores[$0] > wordScores[$1] }

        guard method != .words, let queryVector, let passageVectors, passageVectors.count == passages.count else {
            return wordOrder
        }
        let similarity = passageVectors.map { zip($0, queryVector).reduce(Float(0)) { $0 + $1.0 * $1.1 } }
        let meaningOrder = similarity.indices.sorted { similarity[$0] > similarity[$1] }
        if method == .meaning { return meaningOrder }

        var fused = [Double](repeating: 0, count: passages.count)
        for (rank, index) in wordOrder.enumerated() { fused[index] += 1 / Double(60 + rank) }
        for (rank, index) in meaningOrder.enumerated() { fused[index] += 1 / Double(60 + rank) }
        return fused.indices.sorted { fused[$0] > fused[$1] }
    }

    /// Набирает куски в заданном порядке с ограничениями по объёму и числу кусков с одного сайта.
    public static func pack(_ passages: [Passage], order: [Int], budget: Int, perSource: Int = 2) -> [Passage] {
        var chosen: [Passage] = []
        var perSourceCount: [Int: Int] = [:]
        var used = 0
        for index in order {
            let passage = passages[index]
            guard perSourceCount[passage.sourceIndex, default: 0] < perSource else { continue }
            guard used + passage.text.count <= budget else { continue }
            if chosen.contains(where: { $0.text == passage.text }) { continue }
            chosen.append(passage)
            perSourceCount[passage.sourceIndex, default: 0] += 1
            used += passage.text.count
        }
        return chosen.sorted { ($0.sourceIndex, $0.position) < ($1.sourceIndex, $1.position) }
    }
}
