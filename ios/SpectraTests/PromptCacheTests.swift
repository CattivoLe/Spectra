import Foundation
import LocalChatEngine
import Testing

/// The saved system prompt (`PromptCache`): naming, housekeeping and the file layout — everything except llama.cpp
/// itself, which needs a real model.
struct PromptCacheTests {
    private let prefix: [Int32] = (1...100).map { Int32($0) }
    private var key: PromptCache.Key {
        PromptCache.Key(model: "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf", modelBytes: 2_147_023_008, contextLength: 8192,
                        backend: "metal", prefix: prefix)
    }

    private func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("prompt-cache-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @Test func theSameInputsNameTheSameFile() {
        let again = PromptCache.Key(model: key.model, modelBytes: key.modelBytes, contextLength: key.contextLength,
                                    backend: key.backend, prefix: Array(prefix))
        #expect(key.fileName == again.fileName)
        #expect(key.fileName.wholeMatch(of: /system-[0-9a-f]{32}\.kvstate/) != nil)
    }

    @Test func anyChangeMissesTheOldFile() {
        var changed: [PromptCache.Key] = []
        var other = key; other.model = "LFM3-1.7B-Q4_K_M.gguf"; changed.append(other)
        other = key; other.modelBytes += 1; changed.append(other)
        other = key; other.contextLength = 4096; changed.append(other)
        other = key; other.backend = "cpu"; changed.append(other)
        other = key; other.prefix[50] = 7; changed.append(other)
        other = key; other.prefix.removeLast(); changed.append(other)
        let original = key.fileName
        for candidate in changed { #expect(candidate.fileName != original) }
        #expect(Set(changed.map(\.fileName)).count == changed.count)
    }

    @Test func commitReplacesTheFileAndDeletesStaleOnes() throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let cache = PromptCache(directory: directory)
        let stale = directory.appendingPathComponent("system-00000000000000000000000000000000.kvstate")
        let leftover = directory.appendingPathComponent("system-11111111111111111111111111111111.kvstate.partial")
        try Data("old prompt".utf8).write(to: stale)
        try Data("cut short".utf8).write(to: leftover)
        try Data("state".utf8).write(to: cache.temporaryURL(for: key))

        #expect(cache.commit(key))
        #expect(try String(contentsOf: cache.url(for: key), encoding: .utf8) == "state")
        #expect(try FileManager.default.contentsOfDirectory(atPath: directory.path) == [key.fileName])

        try Data("newer".utf8).write(to: cache.temporaryURL(for: key))
        #expect(cache.commit(key))
        #expect(try String(contentsOf: cache.url(for: key), encoding: .utf8) == "newer")

        cache.discard(key)
        #expect(try FileManager.default.contentsOfDirectory(atPath: directory.path).isEmpty)
    }

    @Test func commitWithoutAWrittenStateFails() throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let cache = PromptCache(directory: directory)
        #expect(!cache.commit(key))
        #expect(!FileManager.default.fileExists(atPath: cache.url(for: key).path))
    }

    @Test func aWrittenFileReadsBackOnlyWhenWholeAndForTheSamePrompt() throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("state")
        let payload = (0..<5000).map { UInt8($0 % 251) }
        let header = PromptCache.header(prefix: prefix, payloadBytes: payload.count)
        let written = PromptCache.writeMapped(to: url, header: header, payloadBytes: payload.count) { buffer in
            buffer.update(from: payload, count: payload.count)
            return true
        }
        #expect(written)

        let file = try Data(contentsOf: url, options: .alwaysMapped)
        let range = try #require(PromptCache.payloadRange(in: file, prefix: prefix))
        #expect(Array(file[range]) == payload)

        // Same layout as android/engine/src/main/cpp/prompt_state.h: "SPKV", version 1, token count.
        #expect(Array(file.prefix(4)) == Array("SPKV".utf8))
        #expect(Array(file[4..<12]) == [1, 0, 0, 0, 100, 0, 0, 0])

        var otherPrompt = prefix
        otherPrompt[3] = 99
        #expect(PromptCache.payloadRange(in: file, prefix: otherPrompt) == nil)
        #expect(PromptCache.payloadRange(in: file, prefix: Array(prefix.dropLast())) == nil)
        #expect(PromptCache.payloadRange(in: file.prefix(file.count - 1), prefix: prefix) == nil)
        #expect(PromptCache.payloadRange(in: file + Data([0]), prefix: prefix) == nil)
        #expect(PromptCache.payloadRange(in: Data("garbage".utf8), prefix: prefix) == nil)
        #expect(PromptCache.payloadRange(in: header, prefix: prefix) == nil)
    }

    @Test func aFailedFillLeavesNothingUsable() throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("state")
        let written = PromptCache.writeMapped(to: url, header: PromptCache.header(prefix: prefix, payloadBytes: 10), payloadBytes: 10) { _ in false }
        #expect(!written)
    }

    @Test func theSystemPartEndsWhereTwoQuestionsDiffer() {
        #expect(PromptCache.sharedPrefixLength(prefix + [500, 501], prefix + [600, 501]) == prefix.count)
        #expect(PromptCache.sharedPrefixLength([], prefix) == 0)
        #expect(PromptCache.sharedPrefixLength([1, 2], [1, 2]) == 2)
    }

    @Test func onlyAPrefixOfThePromptIsUsed() {
        let prompt = prefix + [500, 501, 502]
        #expect(PromptCache.usablePrefix(prefix, of: prompt) == prefix)
        // Too short to be worth a file.
        #expect(PromptCache.usablePrefix(Array(prefix.prefix(PromptCache.minimumTokens - 1)), of: prompt) == nil)
        // The whole prompt: nothing would be left to compute the next token from.
        #expect(PromptCache.usablePrefix(prefix, of: prefix) == nil)
        // The real question tokenized the boundary differently.
        var differently = prompt
        differently[prefix.count - 1] = 9999
        #expect(PromptCache.usablePrefix(prefix, of: differently) == nil)
    }
}
