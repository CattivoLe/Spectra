import Foundation
import llama

/// Векторы смысла для текстов: маленькая отдельная модель эмбеддингов (GGUF) через llama.cpp.
/// Похожие по смыслу тексты дают близкие векторы, даже если общих слов у них нет.
/// Векторы нормированы, поэтому сходство — просто скалярное произведение.
public final class EmbeddingEngine: @unchecked Sendable {
    private let queue = DispatchQueue(label: "Spectra.EmbeddingEngine", qos: .userInitiated)

    // Только на `queue`.
    private var model: OpaquePointer?
    private var context: OpaquePointer?
    private var vocab: OpaquePointer?
    private var encoderOnly = false

    /// Сколько токенов вмещает один пакет и сколько текстов в нём может быть.
    private static let batchTokens: Int32 = 2048
    private static let maxSequences: Int32 = 8
    /// Длиннее этого текст обрезается: модели эмбеддингов обучены на коротких кусках.
    private static let maxTokensPerText = 512

    public private(set) var dimension = 0

    public init() {}

    deinit {
        if let context { llama_free(context) }
        if let model { llama_model_free(model) }
    }

    public func load(modelAt url: URL, useGPU: Bool = true) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            queue.async {
                continuation.resume(with: Result { try self.loadSync(url: url, useGPU: useGPU) })
            }
        }
    }

    public func unload() async {
        await withCheckedContinuation { continuation in
            queue.async {
                if let context = self.context { llama_free(context) }
                if let model = self.model { llama_model_free(model) }
                self.context = nil
                self.model = nil
                self.vocab = nil
                continuation.resume()
            }
        }
    }

    public func embed(_ texts: [String]) async throws -> [[Float]] {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                continuation.resume(with: Result { try self.embedSync(texts) })
            }
        }
    }

    // MARK: - Внутреннее

    private func loadSync(url: URL, useGPU: Bool) throws {
        LlamaEngine.ensureBackend()
        if let context { llama_free(context) }
        if let model { llama_model_free(model) }

        var modelParams = llama_model_default_params()
        modelParams.n_gpu_layers = useGPU ? -1 : 0
        guard let model = llama_model_load_from_file(url.path, modelParams) else {
            throw EngineError.modelLoadFailed(url.lastPathComponent)
        }

        var contextParams = llama_context_default_params()
        contextParams.n_ctx = UInt32(Self.batchTokens)
        // Для моделей с двунаправленным вниманием весь текст должен попасть в один физический пакет.
        contextParams.n_batch = UInt32(Self.batchTokens)
        contextParams.n_ubatch = UInt32(Self.batchTokens)
        contextParams.n_seq_max = UInt32(Self.maxSequences)
        contextParams.embeddings = true
        contextParams.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED

        guard let context = llama_init_from_model(model, contextParams) else {
            llama_model_free(model)
            throw EngineError.contextCreationFailed
        }
        self.model = model
        self.context = context
        self.vocab = llama_model_get_vocab(model)
        self.encoderOnly = llama_model_has_encoder(model) && !llama_model_has_decoder(model)
        self.dimension = Int(llama_model_n_embd(model))
    }

    private func embedSync(_ texts: [String]) throws -> [[Float]] {
        guard let context, let vocab else { throw EngineError.noModelLoaded }
        let tokenized = try texts.map { try tokens(for: $0, vocab: vocab) }

        var results = [[Float]](repeating: [], count: texts.count)
        var start = 0
        while start < tokenized.count {
            // Набираем пакет: сколько влезет текстов по числу токенов и последовательностей.
            var end = start
            var total = 0
            while end < tokenized.count, end - start < Int(Self.maxSequences), total + tokenized[end].count <= Int(Self.batchTokens) {
                total += tokenized[end].count
                end += 1
            }

            var batch = llama_batch_init(Int32(total), 0, 1)
            defer { llama_batch_free(batch) }
            var position = 0
            for (sequence, index) in (start..<end).enumerated() {
                for (offset, token) in tokenized[index].enumerated() {
                    batch.token[position] = token
                    batch.pos[position] = Int32(offset)
                    batch.n_seq_id[position] = 1
                    batch.seq_id[position]![0] = Int32(sequence)
                    batch.logits[position] = 1
                    position += 1
                }
            }
            batch.n_tokens = Int32(total)

            if let memory = llama_get_memory(context) { llama_memory_clear(memory, true) }
            let code = encoderOnly ? llama_encode(context, batch) : llama_decode(context, batch)
            guard code == 0 else { throw EngineError.decodeFailed(code: code) }

            for (sequence, index) in (start..<end).enumerated() {
                guard let pointer = llama_get_embeddings_seq(context, Int32(sequence)) else { throw EngineError.decodeFailed(code: -2) }
                results[index] = Self.normalized(Array(UnsafeBufferPointer(start: pointer, count: dimension)))
            }
            start = end
        }
        return results
    }

    private func tokens(for text: String, vocab: OpaquePointer) throws -> [llama_token] {
        let bytes = Int32(text.utf8.count)
        var buffer = [llama_token](repeating: 0, count: Int(bytes) + 8)
        var count = llama_tokenize(vocab, text, bytes, &buffer, Int32(buffer.count), true, false)
        if count < 0, count != Int32.min {
            buffer = [llama_token](repeating: 0, count: Int(-count))
            count = llama_tokenize(vocab, text, bytes, &buffer, Int32(buffer.count), true, false)
        }
        guard count > 0 else { throw EngineError.tokenizationFailed }
        return Array(buffer.prefix(min(Int(count), Self.maxTokensPerText)))
    }

    static func normalized(_ vector: [Float]) -> [Float] {
        let norm = vector.reduce(0) { $0 + $1 * $1 }.squareRoot()
        return norm > 0 ? vector.map { $0 / norm } : vector
    }
}
