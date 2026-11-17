import Foundation
import LocalChatEngine

/// Эмбеддер, который загружает модель только при первом поиске: пока интернет не нужен, память не занята.
public actor LazyEmbedder: PassageEmbedder {
    private let url: URL
    private let style: LlamaPassageEmbedder.Style
    private var loaded: LlamaPassageEmbedder?
    private var engine: EmbeddingEngine?

    public init(modelAt url: URL) {
        self.url = url
        self.style = .guess(fileName: url.lastPathComponent)
    }

    public func embed(queries: [String]) async throws -> [[Float]] {
        try await prepared().embed(queries: queries)
    }

    public func embed(passages: [String]) async throws -> [[Float]] {
        try await prepared().embed(passages: passages)
    }

    /// Освобождает память; при следующем поиске модель загрузится снова.
    public func unload() async {
        await engine?.unload()
        engine = nil
        loaded = nil
    }

    private func prepared() async throws -> LlamaPassageEmbedder {
        if let loaded { return loaded }
        let engine = EmbeddingEngine()
        try await engine.load(modelAt: url)
        let embedder = LlamaPassageEmbedder(engine: engine, style: style)
        self.engine = engine
        self.loaded = embedder
        return embedder
    }
}
