import Foundation
import Synchronization
import llama

/// Движок на llama.cpp (модели GGUF, Metal или CPU).
///
/// Все вызовы llama.cpp идут через одну последовательную очередь: контекст не потокобезопасен,
/// а вычисления блокирующие, поэтому им не место в общем пуле Swift Concurrency.
public final class LlamaEngine: InferenceEngine, @unchecked Sendable {
    private let queue = DispatchQueue(label: "LocalChat.LlamaEngine", qos: .userInitiated)

    // Состояние ниже читается и меняется только на `queue`.
    private var model: OpaquePointer?
    private var context: OpaquePointer?
    private var vocab: OpaquePointer?
    private var template: String?
    private var chatTemplate: ChatTemplate?
    private var fallbackFormat: ChatFormat?
    private var bosText = ""
    /// Токены, которые сейчас лежат в KV-кэше контекста, по порядку.
    /// Следующий запрос досчитывает только то, что отличается от этого префикса.
    private var cachedTokens: [llama_token] = []
    /// Сообщения, уже выброшенные из начала текущего диалога. Пока диалог тот же, выбрасываем ровно их:
    /// граница не «ползёт» с каждой репликой, и кэш после обрезки продолжает работать.
    private var droppedHistory: [ChatMessage] = []

    private static let batchSize: Int32 = 512

    private static let backend: Void = {
        // По умолчанию llama.cpp пишет в stderr подробный журнал загрузки; оставляем только ошибки.
        // Ставится до инициализации: Metal-бэкенд пишет в журнал уже при ней.
        llama_log_set({ level, text, _ in
            guard let text, level == GGML_LOG_LEVEL_ERROR else { return }
            fputs(text, stderr)
        }, nil)
        llama_backend_init()
    }()

    public init() {}

    deinit {
        if let context { llama_free(context) }
        if let model { llama_model_free(model) }
    }

    // MARK: - InferenceEngine

    public func load(modelAt url: URL, config: EngineConfig) async throws -> ModelInfo {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                continuation.resume(with: Result { try self.loadSync(url: url, config: config) })
            }
        }
    }

    public func unload() async {
        await withCheckedContinuation { continuation in
            queue.async {
                self.unloadSync()
                continuation.resume()
            }
        }
    }

    public func generate(messages: [ChatMessage], params: GenerationParams) -> AsyncThrowingStream<GenerationEvent, Error> {
        AsyncThrowingStream { continuation in
            let cancelled = CancelFlag()
            continuation.onTermination = { _ in cancelled.set() }
            queue.async {
                do {
                    let stats = try self.generateSync(messages: messages, params: params, cancelled: cancelled) {
                        continuation.yield(.text($0))
                    }
                    continuation.yield(.finished(stats))
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
        }
    }

    public func countTokens(messages: [ChatMessage], thinking: Bool) async -> Int? {
        await withCheckedContinuation { continuation in
            queue.async {
                guard let vocab = self.vocab,
                      let prompt = try? self.render(messages, thinking: thinking),
                      let tokens = try? self.tokenize(prompt, vocab: vocab) else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: tokens.count)
            }
        }
    }

    /// Шаблон чата из метаданных загруженной модели — для диагностики.
    public func chatTemplateSource() async -> String? {
        await withCheckedContinuation { continuation in
            queue.async { continuation.resume(returning: self.template) }
        }
    }

    // MARK: - Загрузка

    private func loadSync(url: URL, config: EngineConfig) throws -> ModelInfo {
        _ = Self.backend
        unloadSync()

        let clock = ContinuousClock()
        let start = clock.now

        var modelParams = llama_model_default_params()
        modelParams.n_gpu_layers = config.useGPU ? -1 : 0

        guard let model = llama_model_load_from_file(url.path, modelParams) else {
            throw EngineError.modelLoadFailed(url.lastPathComponent)
        }

        let threads = config.threads > 0 ? config.threads : Self.performanceCoreCount
        var contextParams = llama_context_default_params()
        contextParams.n_ctx = config.contextLength
        contextParams.n_batch = UInt32(Self.batchSize)
        contextParams.n_threads = threads
        contextParams.n_threads_batch = threads

        guard let context = llama_init_from_model(model, contextParams) else {
            llama_model_free(model)
            throw EngineError.contextCreationFailed
        }

        self.model = model
        self.context = context
        self.vocab = llama_model_get_vocab(model)
        self.template = llama_model_chat_template(model, nil).map { String(cString: $0) }

        let vocab = llama_model_get_vocab(model)!
        bosText = Self.tokenText(llama_vocab_bos(vocab), vocab: vocab)
        let eosText = Self.tokenText(llama_vocab_eos(vocab), vocab: vocab)
        cachedTokens = []
        droppedHistory = []
        chatTemplate = nil
        fallbackFormat = nil
        if let source = template {
            chatTemplate = try? ChatTemplate(source: source, bosToken: bosText, eosToken: eosText)
            // Шаблон должен не только разобраться, но и отработать на простом диалоге.
            if (try? chatTemplate?.render([ChatMessage(.user, "ping")], enableThinking: false)) == nil {
                chatTemplate = nil
            }
        }
        if chatTemplate == nil { fallbackFormat = ChatFormat.detect(template: template) }

        var description = [CChar](repeating: 0, count: 256)
        llama_model_desc(model, &description, description.count)

        let fileSize = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { UInt64($0) } ?? 0
        let elapsed = clock.now - start

        return ModelInfo(
            description: description.withUnsafeBufferPointer { String(cString: $0.baseAddress!) },
            parameterCount: llama_model_n_params(model),
            fileSizeBytes: fileSize,
            trainedContextLength: llama_model_n_ctx_train(model),
            contextLength: llama_n_ctx(context),
            threads: threads,
            loadSeconds: elapsed.seconds,
            chatFormat: chatTemplate != nil ? "template from the model (Jinja)" : "fallback: \((fallbackFormat ?? .plain).rawValue)"
        )
    }

    private func unloadSync() {
        if let context { llama_free(context) }
        if let model { llama_model_free(model) }
        context = nil
        model = nil
        vocab = nil
        template = nil
        chatTemplate = nil
        fallbackFormat = nil
        cachedTokens = []
        droppedHistory = []
    }

    // MARK: - Генерация

    private func generateSync(
        messages: [ChatMessage],
        params: GenerationParams,
        cancelled: CancelFlag,
        emit: (String) -> Void
    ) throws -> GenerationStats {
        guard let context, let vocab else { throw EngineError.noModelLoaded }

        let contextLength = Int(llama_n_ctx(context))
        let (tokens, dropped) = try preparePrompt(messages, params: params, contextLength: contextLength, vocab: vocab)

        let sampler = makeSampler(params)
        defer { llama_sampler_free(sampler) }

        let clock = ContinuousClock()
        let start = clock.now

        // Всё, что совпадает с уже посчитанным началом, пересчитывать не нужно.
        // Хотя бы один токен досчитываем всегда: без него не будет данных для выбора следующего.
        var reused = min(Self.commonPrefix(cachedTokens, tokens), tokens.count - 1)
        let memory = llama_get_memory(context)
        if reused <= 0 {
            reused = 0
            llama_memory_clear(memory, true)
        } else if reused < cachedTokens.count, !llama_memory_seq_rm(memory, 0, Int32(reused), -1) {
            // Часть моделей (с рекуррентными слоями) не умеет отбрасывать хвост кэша: считаем заново.
            reused = 0
            llama_memory_clear(memory, true)
        }
        cachedTokens = Array(tokens[..<reused])

        var offset = reused
        while offset < tokens.count {
            if cancelled.isSet {
                return GenerationStats(promptTokens: tokens.count, reusedPromptTokens: reused, droppedMessages: dropped,
                                       generatedTokens: 0, timeToFirstTokenSeconds: (clock.now - start).seconds,
                                       generationSeconds: 0, stopReason: .cancelled)
            }
            let count = min(Int(Self.batchSize), tokens.count - offset)
            let code = tokens.withUnsafeBufferPointer { buffer in
                llama_decode(context, llama_batch_get_one(UnsafeMutablePointer(mutating: buffer.baseAddress! + offset), Int32(count)))
            }
            guard code == 0 else {
                resetCache()
                throw EngineError.decodeFailed(code: code)
            }
            offset += count
            cachedTokens = Array(tokens[..<offset])
        }

        var generated = 0
        var firstTokenAt: ContinuousClock.Instant?
        var pendingBytes: [UInt8] = []
        var stopReason = StopReason.maxTokens

        while generated < params.maxTokens {
            if cancelled.isSet {
                stopReason = .cancelled
                break
            }

            var token = llama_sampler_sample(sampler, context, -1)
            if firstTokenAt == nil { firstTokenAt = clock.now }

            if llama_vocab_is_eog(vocab, token) {
                stopReason = .endOfTurn
                break
            }

            generated += 1
            pendingBytes += piece(for: token, vocab: vocab)
            if let text = Self.drainValidUTF8(&pendingBytes) { emit(text) }

            if tokens.count + generated >= contextLength {
                stopReason = .contextFull
                break
            }

            let code = llama_decode(context, llama_batch_get_one(&token, 1))
            guard code == 0 else {
                resetCache()
                throw EngineError.decodeFailed(code: code)
            }
            cachedTokens.append(token)
        }

        if !pendingBytes.isEmpty { emit(String(decoding: pendingBytes, as: UTF8.self)) }

        let end = clock.now
        let first = firstTokenAt ?? end
        return GenerationStats(
            promptTokens: tokens.count,
            reusedPromptTokens: reused,
            droppedMessages: dropped,
            generatedTokens: generated,
            timeToFirstTokenSeconds: (first - start).seconds,
            generationSeconds: (end - first).seconds,
            stopReason: stopReason
        )
    }

    private func makeSampler(_ params: GenerationParams) -> UnsafeMutablePointer<llama_sampler> {
        let chain = llama_sampler_chain_init(llama_sampler_chain_default_params())!
        // The grammar goes first in the chain: it masks every token the structure forbids before the samplers
        // below pick among what is left. It does nothing until one of the trigger strings shows up in the output.
        if let grammarSampler = makeGrammarSampler(params) {
            llama_sampler_chain_add(chain, grammarSampler)
        }
        if params.temperature <= 0 {
            llama_sampler_chain_add(chain, llama_sampler_init_greedy())
        } else {
            llama_sampler_chain_add(chain, llama_sampler_init_top_k(params.topK))
            llama_sampler_chain_add(chain, llama_sampler_init_top_p(params.topP, 1))
            llama_sampler_chain_add(chain, llama_sampler_init_temp(params.temperature))
            llama_sampler_chain_add(chain, llama_sampler_init_dist(params.seed))
        }
        return chain
    }

    /// `nil` when there is no grammar to apply, or when it fails to parse (logged, not fatal: the model answers
    /// in plain text instead of a tool call it could never have gotten right anyway).
    private func makeGrammarSampler(_ params: GenerationParams) -> UnsafeMutablePointer<llama_sampler>? {
        guard let grammar = params.grammar, !grammar.isEmpty, !params.grammarTriggers.isEmpty, let vocab else { return nil }
        let owned: [UnsafeMutablePointer<CChar>?] = params.grammarTriggers.map { strdup($0) }
        defer { for pointer in owned { free(pointer) } }
        var pointers: [UnsafePointer<CChar>?] = owned.map { $0.map { UnsafePointer($0) } }
        let sampler = pointers.withUnsafeMutableBufferPointer { buffer in
            grammar.withCString { grammarCString in
                llama_sampler_init_grammar_lazy_patterns(vocab, grammarCString, "root", buffer.baseAddress, buffer.count, nil, 0)
            }
        }
        if sampler == nil {
            FileHandle.standardError.write(Data("LlamaEngine: failed to parse tool-call grammar, ignoring it\n".utf8))
        }
        return sampler
    }

    // MARK: - Текст и токены

    private func resetCache() {
        cachedTokens = []
        if let context { llama_memory_clear(llama_get_memory(context), true) }
    }

    private func render(_ messages: [ChatMessage], thinking: Bool) throws -> String {
        if let chatTemplate { return try chatTemplate.render(messages, enableThinking: thinking) }
        return (fallbackFormat ?? .plain).render(messages)
    }

    /// Собирает промпт и, если он не помещается в контекст, выбрасывает самые старые реплики.
    ///
    /// Системные сообщения в начале и последний вопрос остаются всегда. Старое убирается целыми
    /// обменами «вопрос — ответ» и сразу с запасом (до 60 % бюджета): иначе начало промпта менялось бы
    /// на каждой реплике, и кэш никогда не переиспользовался бы.
    private func preparePrompt(
        _ messages: [ChatMessage],
        params: GenerationParams,
        contextLength: Int,
        vocab: OpaquePointer
    ) throws -> (tokens: [llama_token], dropped: Int) {
        let reserve = min(params.maxTokens, contextLength / 2)
        let budget = contextLength - reserve - 8

        func tokens(for messages: [ChatMessage]) throws -> [llama_token] {
            try tokenize(try render(messages, thinking: params.thinking), vocab: vocab)
        }

        let systemCount = messages.prefix { $0.role == .system }.count
        let system = Array(messages[..<systemCount])
        var rest = Array(messages[systemCount...])

        // Тот же диалог, что и в прошлый раз: сразу начинаем с уже выброшенного начала.
        if !droppedHistory.isEmpty, rest.starts(with: droppedHistory), rest.count > droppedHistory.count {
            rest.removeFirst(droppedHistory.count)
        } else {
            droppedHistory = []
        }

        var result = try tokens(for: system + rest)
        if result.count > budget {
            let target = budget * 6 / 10
            while result.count > target {
                // Первый обмен: от первой реплики до следующего вопроса пользователя.
                guard let next = rest.dropFirst().firstIndex(where: { $0.role == .user }) else { break }
                droppedHistory += rest[..<next]
                rest.removeSubrange(..<next)
                result = try tokens(for: system + rest)
            }
        }

        guard result.count <= budget else {
            throw EngineError.promptTooLong(tokens: result.count, context: contextLength)
        }
        return (result, droppedHistory.count)
    }

    private static func commonPrefix(_ a: [llama_token], _ b: [llama_token]) -> Int {
        var index = 0
        let limit = min(a.count, b.count)
        while index < limit, a[index] == b[index] { index += 1 }
        return index
    }

    private static func tokenText(_ token: llama_token, vocab: OpaquePointer) -> String {
        guard token >= 0, let text = llama_vocab_get_text(vocab, token) else { return "" }
        return String(cString: text)
    }

    private func tokenize(_ text: String, vocab: OpaquePointer) throws -> [llama_token] {
        // Шаблон сам ставит служебный токен начала текста; второй раз добавлять его нельзя.
        let addSpecial = bosText.isEmpty || !text.hasPrefix(bosText)
        let byteCount = Int32(text.utf8.count)
        var tokens = [llama_token](repeating: 0, count: Int(byteCount) + 16)
        var count = llama_tokenize(vocab, text, byteCount, &tokens, Int32(tokens.count), addSpecial, true)
        if count < 0, count != Int32.min {
            tokens = [llama_token](repeating: 0, count: Int(-count))
            count = llama_tokenize(vocab, text, byteCount, &tokens, Int32(tokens.count), addSpecial, true)
        }
        guard count >= 0 else { throw EngineError.tokenizationFailed }
        return Array(tokens.prefix(Int(count)))
    }

    private func piece(for token: llama_token, vocab: OpaquePointer) -> [UInt8] {
        var buffer = [CChar](repeating: 0, count: 64)
        var length = llama_token_to_piece(vocab, token, &buffer, Int32(buffer.count), 0, false)
        if length < 0 {
            buffer = [CChar](repeating: 0, count: Int(-length))
            length = llama_token_to_piece(vocab, token, &buffer, Int32(buffer.count), 0, false)
        }
        guard length > 0 else { return [] }
        return buffer.prefix(Int(length)).map { UInt8(bitPattern: $0) }
    }

    /// Токен может оборваться посреди многобайтового символа (кириллица — два байта, эмодзи — четыре).
    /// Отдаём наружу только целые символы, хвост ждёт следующего токена.
    private static func drainValidUTF8(_ bytes: inout [UInt8]) -> String? {
        for trailing in 0...min(3, bytes.count) {
            let end = bytes.count - trailing
            guard end > 0 else { return nil }
            if let text = String(validating: bytes[..<end], as: UTF8.self) {
                bytes.removeFirst(end)
                return text
            }
        }
        // Больше трёх «висящих» байт — это уже не оборванный символ, а мусор: отдаём как есть.
        let text = String(decoding: bytes, as: UTF8.self)
        bytes.removeAll()
        return text
    }

    private static var performanceCoreCount: Int32 {
        var count: Int32 = 0
        var size = MemoryLayout<Int32>.size
        if sysctlbyname("hw.perflevel0.physicalcpu", &count, &size, nil, 0) == 0, count > 0 {
            return count
        }
        return Int32(max(1, ProcessInfo.processInfo.activeProcessorCount / 2))
    }
}

private final class CancelFlag: Sendable {
    private let flag = Atomic<Bool>(false)
    var isSet: Bool { flag.load(ordering: .relaxed) }
    func set() { flag.store(true, ordering: .relaxed) }
}

extension Duration {
    public var seconds: Double {
        Double(components.seconds) + Double(components.attoseconds) / 1e18
    }
}
