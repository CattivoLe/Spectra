import Foundation
import Observation
import LocalChatEngine

/// Сколько контекста модели занято диалогом: основа «градусника» на экране.
public struct ContextUsage: Equatable, Sendable {
    /// Токенов в промпте со всей историей.
    public var used: Int
    /// Размер контекста загруженной модели.
    public var total: Int
    /// Сколько старых сообщений уже не помещаются и не доходят до модели.
    public var droppedMessages: Int

    public var fraction: Double { total > 0 ? min(1, Double(used) / Double(total)) : 0 }
    public var isOverflowing: Bool { used > total || droppedMessages > 0 }
}

/// Один открытый диалог: отправка, остановка, повторный ответ и правка вопроса.
/// Всё, что видно на экране, берётся отсюда; на диск сообщения пишутся по ходу ответа.
@MainActor
@Observable
public final class ChatSession {
    public private(set) var chat: Chat
    public private(set) var messages: [StoredMessage] = []
    public private(set) var isGenerating = false
    public private(set) var errorText: String?
    /// Идёт поиск в интернете перед ответом.
    public private(set) var isSearching = false
    /// Сообщение о том, что поиск не состоялся: ответ при этом всё равно даётся, но без интернета.
    public private(set) var searchNotice: String?
    /// A tool call is awaiting its result, e.g. "Scanning Wi‑Fi…". `nil` the rest of the time.
    public private(set) var toolStatus: String?

    public var params: GenerationParams

    /// Название загруженной модели: подписывает ответы и сам диалог. Задаёт приложение.
    public var modelName: String?
    /// Заполнение контекста. `nil`, пока модель не загружена.
    public private(set) var context: ContextUsage?
    private var lastDropped = 0

    /// Gathers what the app knows that is relevant to a question. Set by the app; `nil` means the assistant has no app data.
    public var appDataProvider: (@MainActor (_ question: String) async -> AppDataAttachment?)?
    /// What was attached to each question of this session, by message id. Kept in memory only: it is recomputed on "Regenerate".
    public private(set) var attachments: [UUID: AppDataAttachment] = [:]

    /// Live actions the model may call mid-answer. `nil` means it can only read the conversation, nothing else.
    public var tools: ToolRegistry?
    /// Which tools ran for each assistant answer, by that message's id. In memory only, like `attachments`.
    public private(set) var toolRuns: [UUID: [String]] = [:]

    private let store: ChatStore
    private let controller: EngineController
    private let web: WebAccess?
    private var task: Task<Void, Never>?

    /// Who the assistant is and how it behaves. Supplied by the app.
    private let systemPrompt: String

    public init(
        chat: Chat,
        store: ChatStore,
        controller: EngineController,
        systemPrompt: String = "",
        web: WebAccess? = nil,
        params: GenerationParams = GenerationParams(maxTokens: 1024, temperature: 0.7, thinking: false)
    ) {
        self.chat = chat
        self.store = store
        self.controller = controller
        self.web = web
        self.systemPrompt = systemPrompt
        self.params = params
    }

    public func load() async {
        do {
            messages = try await store.messages(in: chat.id)
        } catch {
            errorText = error.localizedDescription
        }
        await refreshContext()
    }

    /// Пересчитывает заполнение контекста. Зовётся после загрузки диалога, ответа и смены модели.
    public func refreshContext() async {
        guard !isGenerating, let total = controller.info?.contextLength else {
            if controller.state == .empty { context = nil }
            return
        }
        let history = await buildHistory(for: messages)
        guard let used = await controller.countTokens(messages: history) else { return }
        context = ContextUsage(used: used, total: Int(total), droppedMessages: lastDropped)
    }

    /// Everything that goes to the model: the system prompt and the conversation.
    private func buildHistory(for messages: [StoredMessage]) async -> [ChatMessage] {
        let system = systemPrompt
        var history = messages.map(\.asChatMessage)
        // What was found goes to the model inside the last question, not the system prompt: the start of the prompt
        // does not change, so the cache of earlier turns keeps working.
        if let last = messages.last, last.role == .user, !history.isEmpty {
            var question = last.search?.promptText(for: last.content) ?? last.content
            if let attachment = attachments[last.id] {
                question = attachment.text + "\n\n" + question
            }
            history[history.count - 1].content = question
        }
        return (system.isEmpty ? [] : [ChatMessage(.system, system)]) + history
    }

    // MARK: - Действия

    public func send(_ text: String) {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isGenerating else { return }
        start {
            let message = try await self.store.append(.user, text, to: self.chat.id)
            self.messages.append(message)
            if self.messages.count == 1 { await self.refreshChat() }
        }
    }

    /// Просит модель ответить заново. Если последним стоит ответ, он заменяется новым.
    public func regenerate() {
        guard !isGenerating, let last = messages.last else { return }
        start {
            if last.role == .assistant {
                try await self.store.deleteMessages(in: self.chat.id, fromSeq: last.seq)
                self.messages.removeLast()
            }
            guard self.messages.last?.role == .user else { return }
        }
    }

    /// Меняет текст вопроса. Всё, что было после него, удаляется, и модель отвечает заново.
    public func edit(_ messageID: UUID, to text: String) {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !isGenerating, !text.isEmpty,
              let index = messages.firstIndex(where: { $0.id == messageID }),
              messages[index].role == .user else { return }
        start {
            try await self.store.deleteMessages(in: self.chat.id, fromSeq: self.messages[index].seq + 1)
            try await self.store.update(messageID, content: text)
            try await self.store.setSearch(nil, for: messageID)
            self.messages.removeSubrange((index + 1)...)
            self.messages[index].content = text
            self.messages[index].search = nil
            if index == 0 {
                try await self.store.rename(self.chat.id, to: ChatStore.title(from: text))
                await self.refreshChat()
            }
        }
    }

    public func stop() {
        controller.stop()
        if isSearching || toolStatus != nil { task?.cancel() }
    }

    /// Lets the assistant read (or not) what the app has found, for this chat.
    public func setAppData(_ enabled: Bool) async {
        chat.appData = enabled
        if !enabled { attachments = [:] }
        try? await store.setAppData(enabled, for: chat.id)
    }

    /// Включает или выключает поиск в интернете для этого диалога.
    public func setWebSearch(_ enabled: Bool) async {
        chat.webSearch = enabled
        try? await store.setWebSearch(enabled, for: chat.id)
    }

    /// Ждёт окончания текущего действия. Нужен тестам и командам, которые идут одна за другой.
    public func waitUntilIdle() async {
        await task?.value
    }

    // MARK: - Внутреннее

    private func start(_ prepare: @escaping @MainActor () async throws -> Void) {
        isGenerating = true
        errorText = nil
        task = Task { @MainActor in
            do {
                searchNotice = nil
                try await prepare()
                guard messages.last?.role == .user else {
                    isGenerating = false
                    await refreshContext()
                    return
                }
                try await searchIfNeeded()
                await attachAppData()
                try await generate()
            } catch is CancellationError {
                // Пользователь остановил поиск: это не ошибка.
            } catch {
                errorText = error.localizedDescription
            }
            isGenerating = false
            await refreshContext()
        }
    }

    /// Поисковый запрос из вопроса: если в тексте есть вопрос, берётся последний, иначе первое предложение.
    /// Пользователь всё равно видит запрос и может поправить его до отправки.
    static func searchQuery(from text: String) -> String {
        let flat = text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        var chosen = flat

        if let mark = flat.lastIndex(of: "?") {
            let head = flat[..<mark]
            chosen = head.split(whereSeparator: { ".!".contains($0) }).last.map(String.init) ?? String(head)
        } else if let first = flat.split(whereSeparator: { ".!".contains($0) }).first {
            chosen = String(first)
        }
        chosen = chosen.trimmingCharacters(in: .whitespaces)
        return chosen.count > 150 ? String(chosen.prefix(150)) : chosen
    }

    /// Ищет в интернете, если это разрешено в настройках и включено для диалога.
    /// Любая неудача — не повод не отвечать: модель ответит без интернета, а причина будет показана.
    private func searchIfNeeded() async throws {
        guard let web, web.isEnabled, chat.webSearch,
              let index = messages.indices.last, messages[index].role == .user, messages[index].search == nil else { return }

        var query = Self.searchQuery(from: messages[index].content)
        if web.confirmEachSearch {
            // Без окна подтверждения наружу ничего не уходит.
            guard let approved = await web.approve?(query) else {
                searchNotice = String(localized: "Search cancelled: answering without the internet.")
                return
            }
            query = approved
        }
        query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }

        isSearching = true
        defer { isSearching = false }

        let provider: any SearchProvider
        do {
            provider = try web.makeProvider()
        } catch {
            searchNotice = String(localized: "\(error.localizedDescription) Answering without the internet.")
            return
        }

        do {
            let results = try await provider.search(query, limit: web.resultCount)
            try? await store.logSearch(query: query, provider: provider.name, succeeded: true)
            guard !results.isEmpty else {
                searchNotice = String(localized: "Nothing found for «\(query)». Answering without the internet.")
                return
            }
            let payload = SearchPayload(query: query, provider: provider.name, results: results)
            try await store.setSearch(payload, for: messages[index].id)
            messages[index].search = payload
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch {
            try? await store.logSearch(query: query, provider: provider.name, succeeded: false)
            searchNotice = String(localized: "Search failed: \(error.localizedDescription) Answering without the internet.")
        }
    }

    /// Puts the app's own facts in front of the last question, when the chat allows it and the app has any that fit.
    private func attachAppData() async {
        guard chat.appData, let provider = appDataProvider,
              let last = messages.last, last.role == .user else { return }
        attachments[last.id] = await provider(last.content)
    }

    private func refreshChat() async {
        if let updated = try? await store.chats().first(where: { $0.id == chat.id }) { chat = updated }
    }

    private func generate() async throws {
        var assistant = try await store.append(.assistant, "", to: chat.id, modelName: modelName)
        messages.append(assistant)
        chat.modelName = modelName ?? chat.modelName
        let index = messages.count - 1

        var history = await buildHistory(for: Array(messages.dropLast()))
        var toolsUsed: [String] = []
        var lastSave = ContinuousClock.now
        var failure: Error?
        var finalText = ""
        var finalStopReason: StopReason?
        var stepsLeft = tools?.maxSteps ?? 0

        toolLoop: while true {
            var text = ""
            var stopReason: StopReason?

            do {
                // Каждый раз новое зерно: иначе «Ещё раз» дословно повторял бы прежний ответ.
                var attempt = params
                attempt.seed = .random(in: .min ... .max)
                if let tools, chat.appData, stepsLeft > 0 {
                    attempt.grammar = tools.grammar
                    attempt.grammarTriggers = [tools.callTag]
                }
                for try await event in controller.send(messages: history, params: attempt) {
                    switch event {
                    case .text(let piece):
                        text += piece
                        messages[index].content = text
                        // Раз в секунду сбрасываем написанное на диск: если приложение убьют, ответ не пропадёт целиком.
                        if ContinuousClock.now - lastSave > .seconds(1) {
                            try? await store.update(assistant.id, content: text, interrupted: true)
                            lastSave = .now
                        }
                    case .finished(let stats):
                        stopReason = stats.stopReason
                        lastDropped = stats.droppedMessages
                    }
                }
            } catch {
                failure = error
                break toolLoop
            }

            if let tools, chat.appData, stepsLeft > 0, let call = Self.parseToolCall(text, tag: tools.callTag) {
                stepsLeft -= 1
                toolsUsed.append(call.name)
                toolStatus = tools.runningLabel(call.name)
                messages[index].content = toolStatus ?? text
                history.append(ChatMessage(.assistant, text))
                let result = await tools.run(call.name, call.argsJSON)
                toolStatus = nil
                if Task.isCancelled { break toolLoop }
                history.append(ChatMessage(.user, Self.toolResultPrompt(name: call.name, result: result)))
                continue toolLoop
            }

            // Any stray tag — the toggle was off, or the JSON after it never closed — is noise, not an answer.
            if let tools, let tagRange = text.range(of: tools.callTag) {
                let stripped = text[..<tagRange.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines)
                text = stripped.isEmpty
                    ? String(localized: "I tried to run a check but could not finish it. Could you ask again?")
                    : stripped
            }

            // `text` may have just been edited above (the stray-tag strip); the bubble must match what is kept.
            messages[index].content = text
            finalText = text
            finalStopReason = stopReason
            break toolLoop
        }

        if finalText.isEmpty {
            // Ничего не получено: пустое сообщение не оставляем.
            try? await store.deleteMessages(in: chat.id, fromSeq: assistant.seq)
            messages.removeLast()
        } else {
            assistant.content = finalText
            let complete = finalStopReason == .endOfTurn
            messages[index].interrupted = !complete
            if !toolsUsed.isEmpty { toolRuns[assistant.id] = toolsUsed }
            try await store.update(assistant.id, content: finalText, interrupted: !complete)
        }
        if let failure { throw failure }
    }
}
