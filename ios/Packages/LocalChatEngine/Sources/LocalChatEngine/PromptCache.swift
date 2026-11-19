import CryptoKit
import Foundation

/// The computed KV cache of a prompt's opening — the system message — kept on disk between chats and launches.
///
/// Every new chat starts with the same long system prompt and tool instructions; reading them takes the model
/// tens of seconds on a phone CPU. The engine computes them once, saves that state here, and the next new chat
/// loads the file instead. Only the naming and housekeeping live here: the engine does the llama.cpp calls.
public struct PromptCache: Sendable {
    /// llama.cpp version the files were written by: a state file is not guaranteed to load in another one.
    public static let llamaBuild = "b12359"
    /// Shorter openings are not worth a file: they are computed in well under a second.
    public static let minimumTokens = 64
    static let fileExtension = "kvstate"

    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    /// The folder the app's own cache lives in; the system may empty it when storage runs low, which is fine.
    public static var defaultDirectory: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("PromptCache", isDirectory: true)
    }

    /// What a saved state depends on. Any difference means the file cannot be reused.
    public struct Key: Sendable, Equatable {
        public var model: String
        public var modelBytes: UInt64
        public var contextLength: Int
        /// Where the KV cache lives (Metal or CPU): its layout can differ between them.
        public var backend: String
        public var prefix: [Int32]

        public init(model: String, modelBytes: UInt64, contextLength: Int, backend: String, prefix: [Int32]) {
            self.model = model
            self.modelBytes = modelBytes
            self.contextLength = contextLength
            self.backend = backend
            self.prefix = prefix
        }

        /// File name: a digest of everything above, so a changed prompt, model or setting simply misses.
        public var fileName: String {
            var hash = SHA256()
            hash.update(data: Data("\(model)\n\(modelBytes)\n\(contextLength)\n\(backend)\n\(PromptCache.llamaBuild)\n".utf8))
            prefix.withUnsafeBytes { hash.update(bufferPointer: $0) }
            let hex = hash.finalize().map { String(format: "%02x", $0) }.joined()
            return "system-\(hex.prefix(32)).\(PromptCache.fileExtension)"
        }
    }

    public func url(for key: Key) -> URL {
        directory.appendingPathComponent(key.fileName)
    }

    /// Where a new state is written before it replaces the file: a half-written file must never be loaded.
    public func temporaryURL(for key: Key) -> URL {
        directory.appendingPathComponent(key.fileName + ".partial")
    }

    /// Moves a freshly written state into place and deletes every other file here: states of an older prompt,
    /// another model or another llama.cpp are never read again, and each one is tens of megabytes.
    @discardableResult
    public func commit(_ key: Key) -> Bool {
        let manager = FileManager.default
        let target = url(for: key)
        do {
            if manager.fileExists(atPath: target.path) { try manager.removeItem(at: target) }
            try manager.moveItem(at: temporaryURL(for: key), to: target)
        } catch {
            try? manager.removeItem(at: temporaryURL(for: key))
            return false
        }
        prune(keeping: key.fileName)
        return true
    }

    /// Deletes every file in the folder except `name`.
    public func prune(keeping name: String?) {
        let manager = FileManager.default
        guard let files = try? manager.contentsOfDirectory(atPath: directory.path) else { return }
        for file in files where file != name {
            try? manager.removeItem(at: directory.appendingPathComponent(file))
        }
    }

    /// Removes one file, e.g. after it failed to load.
    public func discard(_ key: Key) {
        try? FileManager.default.removeItem(at: url(for: key))
    }

    public func prepareDirectory() -> Bool {
        (try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)) != nil
    }

    // MARK: - File format

    /// A file is this header, then llama.cpp's own state of sequence 0 (`llama_state_seq_get_data`):
    /// "SPKV", format version, token count, the tokens, payload size. llama.cpp's own `llama_state_seq_load_file`
    /// is not used: on a truncated file it aborts the process (GGML_ASSERT) instead of failing.
    static let magic: UInt32 = 0x564B_5053 // bytes "SPKV" when written little-endian
    static let formatVersion: UInt32 = 1

    public static func header(prefix: [Int32], payloadBytes: Int) -> Data {
        var data = Data()
        func append<T: FixedWidthInteger>(_ value: T) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        append(magic)
        append(formatVersion)
        append(UInt32(prefix.count))
        for token in prefix { append(token) }
        append(UInt64(payloadBytes))
        return data
    }

    /// Where llama.cpp's state lies in a saved file, or `nil` when the file is not a complete state of `prefix`
    /// (another prompt, another format, cut short).
    public static func payloadRange(in file: Data, prefix: [Int32]) -> Range<Int>? {
        let expected = header(prefix: prefix, payloadBytes: 0)
        let sizeOffset = expected.count - 8
        guard file.count > expected.count,
              file.prefix(sizeOffset).elementsEqual(expected.prefix(sizeOffset)) else { return nil }
        var size: UInt64 = 0
        for (shift, byte) in file.dropFirst(sizeOffset).prefix(8).enumerated() { size |= UInt64(byte) << (8 * UInt64(shift)) }
        guard size > 0, UInt64(file.count - expected.count) == size else { return nil }
        return expected.count..<file.count
    }

    /// Writes `header` and then `payloadBytes` that `fill` puts straight into a memory-mapped file: the state is
    /// tens of megabytes, and an extra copy of it in memory could get the app a memory warning.
    public static func writeMapped(to url: URL, header: Data, payloadBytes: Int, fill: (UnsafeMutablePointer<UInt8>) -> Bool) -> Bool {
        let total = header.count + payloadBytes
        let descriptor = open(url.path, O_CREAT | O_TRUNC | O_RDWR, 0o644)
        guard descriptor >= 0 else { return false }
        defer { close(descriptor) }
        guard ftruncate(descriptor, off_t(total)) == 0,
              let mapped = mmap(nil, total, PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0), mapped != MAP_FAILED
        else { return false }
        defer { munmap(mapped, total) }
        let bytes = mapped.assumingMemoryBound(to: UInt8.self)
        header.copyBytes(to: bytes, count: header.count)
        guard fill(bytes + header.count) else { return false }
        return msync(mapped, total, MS_SYNC) == 0
    }

    /// Where the system part of a prompt ends, in tokens: the shared start of the same system messages followed
    /// by two different questions. Found in tokens rather than text, so it never splits a token.
    public static func sharedPrefixLength(_ a: [Int32], _ b: [Int32]) -> Int {
        var index = 0
        let limit = min(a.count, b.count)
        while index < limit, a[index] == b[index] { index += 1 }
        return index
    }

    /// The prefix worth saving for this prompt, or `nil`: too short, or not actually how `prompt` starts
    /// (a template may tokenize the boundary differently once a real question follows).
    public static func usablePrefix(_ prefix: [Int32], of prompt: [Int32]) -> [Int32]? {
        guard prefix.count >= minimumTokens, prefix.count < prompt.count, prompt.starts(with: prefix) else { return nil }
        return prefix
    }
}
