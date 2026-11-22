import Foundation
import Observation
#if os(iOS)
import UIKit
#endif

/// Хозяин движка в приложении: следит за тем, чтобы модель не мешала системе.
///
/// - На iOS GPU нельзя использовать, когда приложение не на экране: генерация останавливается,
///   как только оно перестаёт быть активным. Уже полученный текст остаётся у вызывающего.
/// - При предупреждении о нехватке памяти модель выгружается, а при следующем запросе
///   загружается снова: пользователь это заметит только по паузе в пару секунд.
@MainActor
@Observable
public final class EngineController {
    public enum State: Equatable, Sendable {
        case empty
        case loading
        case ready
        case generating
        /// Модель выгружена из-за нехватки памяти и будет загружена заново при следующем запросе.
        case unloadedForMemory
    }

    public private(set) var state: State = .empty
    public private(set) var info: ModelInfo?

    private let engine: any InferenceEngine
    private var modelURL: URL?
    private var config = EngineConfig()
    private var generation: Task<Void, Never>?
    private var observers: [Task<Void, Never>] = []

    public init(engine: any InferenceEngine = LlamaEngine()) {
        self.engine = engine
        observeSystemEvents()
    }

    /// Загружает модель. Прежняя выгружается.
    public func load(modelAt url: URL, config: EngineConfig = EngineConfig()) async throws {
        stop()
        state = .loading
        modelURL = url
        self.config = config
        do {
            info = try await engine.load(modelAt: url, config: config)
            state = .ready
        } catch {
            modelURL = nil
            info = nil
            state = .empty
            throw error
        }
    }

    public func unload() async {
        stop()
        await engine.unload()
        modelURL = nil
        info = nil
        state = .empty
    }

    /// Запускает генерацию. Если модель была выгружена из-за памяти, сначала загружает её снова.
    /// Прервать можно через `stop()` или отменой чтения потока.
    public func send(messages: [ChatMessage], params: GenerationParams = GenerationParams()) -> AsyncThrowingStream<GenerationEvent, Error> {
        AsyncThrowingStream { continuation in
            guard state != .generating, state != .loading else {
                continuation.finish(throwing: EngineError.busy)
                return
            }
            guard modelURL != nil else {
                continuation.finish(throwing: EngineError.noModelLoaded)
                return
            }

            // Состояние меняется сразу, а не внутри задачи: иначе два запроса подряд оба прошли бы проверку выше.
            let needsReload = state == .unloadedForMemory
            state = needsReload ? .loading : .generating

            let task = Task { @MainActor [weak self] in
                guard let self else { return }
                defer {
                    if self.state == .generating { self.state = .ready }
                    continuation.finish()
                }
                do {
                    if needsReload, let url = self.modelURL {
                        self.info = try await self.engine.load(modelAt: url, config: self.config)
                    }
                    self.state = .generating
                    for try await event in self.engine.generate(messages: messages, params: params) {
                        continuation.yield(event)
                    }
                } catch {
                    if self.state == .loading { self.state = .unloadedForMemory }
                    continuation.finish(throwing: error)
                }
            }
            generation = task
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Останавливает текущую генерацию. Движок заканчивает на ближайшем токене.
    public func stop() {
        generation?.cancel()
        generation = nil
    }

    /// Приложение уходит с экрана.
    public func handleResignActive() {
        stop()
    }

    /// Система просит освободить память.
    public func handleMemoryWarning() async {
        guard state != .empty, state != .loading else { return }
        let running = generation
        stop()
        // Дожидаемся, пока генерация действительно закончится: выгружать модель под работающим вычислением нельзя.
        await running?.value
        await engine.unload()
        info = nil
        state = .unloadedForMemory
    }

    private func observeSystemEvents() {
        #if os(iOS)
        observers = [
            Task { [weak self] in
                for await _ in NotificationCenter.default.notifications(named: UIApplication.willResignActiveNotification) {
                    self?.handleResignActive()
                }
            },
            Task { [weak self] in
                for await _ in NotificationCenter.default.notifications(named: UIApplication.didReceiveMemoryWarningNotification) {
                    await self?.handleMemoryWarning()
                }
            },
        ]
        #endif
    }
}
