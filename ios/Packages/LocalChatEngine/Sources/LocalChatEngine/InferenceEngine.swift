import Foundation

/// Одно сообщение диалога в нейтральном виде, не привязанном к движку.
public struct ChatMessage: Sendable, Codable, Hashable {
    public enum Role: String, Sendable, Codable {
        case system, user, assistant
    }

    public var role: Role
    public var content: String

    public init(_ role: Role, _ content: String) {
        self.role = role
        self.content = content
    }
}

/// Параметры загрузки модели.
public struct EngineConfig: Sendable, Codable, Equatable {
    /// Размер контекста в токенах. От него зависит память под KV-кэш.
    public var contextLength: UInt32 = 4096
    /// true — считать на GPU (Metal), false — на CPU.
    public var useGPU: Bool = true
    /// Число потоков CPU. 0 — взять число производительных ядер.
    public var threads: Int32 = 0

    public init(contextLength: UInt32 = 4096, useGPU: Bool = true, threads: Int32 = 0) {
        self.contextLength = contextLength
        self.useGPU = useGPU
        self.threads = threads
    }
}

/// Параметры одной генерации.
public struct GenerationParams: Sendable, Codable, Equatable {
    public var maxTokens: Int = 512
    public var temperature: Float = 0.7
    public var topP: Float = 0.9
    public var topK: Int32 = 40
    public var seed: UInt32 = 42
    /// Разрешить модели «думать вслух» перед ответом, если она это умеет.
    public var thinking: Bool = false
    /// A GBNF grammar that the output must switch to once one of `grammarTriggers` appears in the stream.
    /// `nil` (or empty triggers) generates exactly as before: the grammar costs nothing unless it fires.
    public var grammar: String?
    /// Literal substrings that arm `grammar`, scanned for from the start of this generation's own output.
    public var grammarTriggers: [String] = []

    public init(maxTokens: Int = 512, temperature: Float = 0.7, topP: Float = 0.9, topK: Int32 = 40, seed: UInt32 = 42,
                thinking: Bool = false, grammar: String? = nil, grammarTriggers: [String] = []) {
        self.grammar = grammar
        self.grammarTriggers = grammarTriggers
        self.maxTokens = maxTokens
        self.temperature = temperature
        self.topP = topP
        self.topK = topK
        self.seed = seed
        self.thinking = thinking
    }
}

/// Что известно о загруженной модели.
public struct ModelInfo: Sendable, Codable, Equatable {
    public var description: String
    public var parameterCount: UInt64
    public var fileSizeBytes: UInt64
    public var trainedContextLength: Int32
    public var contextLength: UInt32
    public var threads: Int32
    public var loadSeconds: Double
    /// Каким шаблоном чата форматируются сообщения.
    public var chatFormat: String

    public init(description: String, parameterCount: UInt64, fileSizeBytes: UInt64, trainedContextLength: Int32,
                contextLength: UInt32, threads: Int32, loadSeconds: Double, chatFormat: String) {
        self.description = description
        self.parameterCount = parameterCount
        self.fileSizeBytes = fileSizeBytes
        self.trainedContextLength = trainedContextLength
        self.contextLength = contextLength
        self.threads = threads
        self.loadSeconds = loadSeconds
        self.chatFormat = chatFormat
    }
}

public enum StopReason: String, Sendable, Codable {
    case endOfTurn, maxTokens, contextFull, cancelled
}

/// Итог одной генерации.
public struct GenerationStats: Sendable, Codable, Equatable {
    /// Длина промпта целиком, в токенах.
    public var promptTokens: Int
    /// Сколько из них уже лежало в кэше от прошлой реплики и не пересчитывалось.
    public var reusedPromptTokens: Int
    /// Сколько самых старых сообщений пришлось выбросить, чтобы история влезла в контекст.
    public var droppedMessages: Int
    public var generatedTokens: Int
    /// Время от запроса до первого токена ответа: обработка промпта.
    public var timeToFirstTokenSeconds: Double
    /// Время генерации после первого токена.
    public var generationSeconds: Double
    public var stopReason: StopReason

    public init(promptTokens: Int, reusedPromptTokens: Int = 0, droppedMessages: Int = 0, generatedTokens: Int,
                timeToFirstTokenSeconds: Double, generationSeconds: Double, stopReason: StopReason) {
        self.promptTokens = promptTokens
        self.reusedPromptTokens = reusedPromptTokens
        self.droppedMessages = droppedMessages
        self.generatedTokens = generatedTokens
        self.timeToFirstTokenSeconds = timeToFirstTokenSeconds
        self.generationSeconds = generationSeconds
        self.stopReason = stopReason
    }

    public var promptTokensPerSecond: Double {
        timeToFirstTokenSeconds > 0 ? Double(promptTokens - reusedPromptTokens) / timeToFirstTokenSeconds : 0
    }

    public var tokensPerSecond: Double {
        generationSeconds > 0 ? Double(generatedTokens) / generationSeconds : 0
    }
}

public enum GenerationEvent: Sendable {
    case text(String)
    case finished(GenerationStats)
}

public enum EngineError: Error, LocalizedError {
    case modelLoadFailed(String)
    case contextCreationFailed
    case noModelLoaded
    case tokenizationFailed
    case promptTooLong(tokens: Int, context: Int)
    case decodeFailed(code: Int32)
    case busy

    public var errorDescription: String? {
        switch self {
        case .modelLoadFailed(let path): String(localized: "Could not load the model: \(path)")
        case .contextCreationFailed: String(localized: "Could not create the context: probably out of memory")
        case .noModelLoaded: String(localized: "No model is loaded")
        case .tokenizationFailed: String(localized: "Could not split the text into tokens")
        case .promptTooLong(let tokens, let context): String(localized: "The prompt (\(tokens) tokens) does not fit the context (\(context))")
        case .decodeFailed(let code): String(localized: "Computation error, code \(code)")
        case .busy: String(localized: "The model is already answering another request")
        }
    }
}

/// Граница между приложением и конкретным движком инференса.
/// За ней сейчас llama.cpp; сюда же встанут Foundation Models и, если понадобится, MLX.
public protocol InferenceEngine: Sendable {
    func load(modelAt url: URL, config: EngineConfig) async throws -> ModelInfo
    func unload() async
    func generate(messages: [ChatMessage], params: GenerationParams) -> AsyncThrowingStream<GenerationEvent, Error>
    /// Сколько токенов займёт этот диалог в контексте модели, без обрезки старого. `nil`, если модель не загружена
    /// или движок не умеет считать. По нему рисуется «градусник» заполнения контекста.
    func countTokens(messages: [ChatMessage], thinking: Bool) async -> Int?
}

extension InferenceEngine {
    public func countTokens(messages: [ChatMessage], thinking: Bool) async -> Int? { nil }
}
