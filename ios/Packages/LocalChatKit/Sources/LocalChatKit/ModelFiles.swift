import CryptoKit
import Foundation

public enum ModelFileError: LocalizedError {
    case notGGUF
    case checksumMismatch
    case notEnoughSpace(needed: Int64, available: Int64)
    case http(Int)

    public var errorDescription: String? {
        switch self {
        case .notGGUF: String(localized: "This is not a model file: GGUF format expected.")
        case .checksumMismatch: String(localized: "The checksum does not match: the file was damaged in download. Try again.")
        case .notEnoughSpace(let needed, let available):
            String(localized: "Not enough space: \(Self.size(needed)) needed, \(Self.size(available)) free.")
        case .http(let code): String(localized: "The server answered with error \(code).")
        }
    }

    static func size(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

public enum ModelFiles {
    /// SHA-256 файла. Читает кусками, поэтому память не растёт вместе с размером модели.
    public static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 4 * 1024 * 1024), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// Файл начинается с байтов «GGUF». Это не проверка целостности, а защита от случайно выбранного не того файла.
    public static func isGGUF(_ url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url), let head = try? handle.read(upToCount: 4) else { return false }
        try? handle.close()
        return head == Data("GGUF".utf8)
    }

    /// Свободное место, на которое можно рассчитывать для крупных загрузок.
    public static func availableSpace(at url: URL) -> Int64 {
        let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? 0
    }

    /// Модели не нужны в резервной копии iCloud: они большие и скачиваются заново.
    public static func excludeFromBackup(_ url: URL) {
        var url = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }

    /// Имя, которого ещё нет в папке: «модель.gguf», «модель 2.gguf», …
    public static func uniqueURL(for name: String, in directory: URL) -> URL {
        var candidate = directory.appending(path: name)
        guard FileManager.default.fileExists(atPath: candidate.path) else { return candidate }
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var number = 2
        repeat {
            candidate = directory.appending(path: "\(base) \(number).\(ext)")
            number += 1
        } while FileManager.default.fileExists(atPath: candidate.path)
        return candidate
    }
}
