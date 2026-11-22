import Foundation
import os

/// HTTP bodies of one recording, one file per body: `<flow>-<exchange>-req.bin` and `-res.bin`. The tunnel
/// writes the live recording's into the App Group; saving copies that folder next to the saved session, as
/// `<session>.bodies`. Port of Android `core/TrafficBodies.kt`.
nonisolated final class TrafficBodies: Sendable {
    let dir: URL
    private let used = OSAllocatedUnfairLock<Int64?>(initialState: nil)

    /// The most body bytes one recording keeps.
    static let budget: Int64 = 64 * 1024 * 1024

    init(dir: URL) { self.dir = dir }

    func url(flowID: Int64, index: Int, request: Bool) -> URL {
        dir.appendingPathComponent("\(flowID)-\(index)-\(request ? "req" : "res").bin")
    }

    func read(flowID: Int64, index: Int, request: Bool) -> Data? {
        try? Data(contentsOf: url(flowID: flowID, index: index, request: request))
    }

    /// Claims room for `size` bytes in the budget. False once a recording has kept `budget` bytes of bodies:
    /// later ones are not written.
    func reserve(_ size: Int) -> Bool {
        if used.withLock({ $0 }) == nil {
            let start = folderBytes()
            used.withLock { if $0 == nil { $0 = start } }
        }
        return used.withLock { value in
            let now = value ?? 0
            if now + Int64(size) > Self.budget { return false }
            value = now + Int64(size)
            return true
        }
    }

    func write(flowID: Int64, index: Int, request: Bool, body: Data) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? body.write(to: url(flowID: flowID, index: index, request: request))
    }

    /// Replaces `target` with a copy of this folder. Files are hard-linked where the disk allows, so saving a
    /// large recording is instant; a body file is never changed once written, so the folders cannot drift.
    func copy(to target: URL) {
        let fm = FileManager.default
        try? fm.removeItem(at: target)
        guard let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        try? fm.createDirectory(at: target, withIntermediateDirectories: true)
        for file in files {
            let copy = target.appendingPathComponent(file.lastPathComponent)
            if (try? fm.linkItem(at: file, to: copy)) == nil { try? fm.copyItem(at: file, to: copy) }
        }
    }

    func clear() {
        try? FileManager.default.removeItem(at: dir)
        used.withLock { $0 = 0 }
    }

    private func folderBytes() -> Int64 {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return files.reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }
}

/// Where the tunnel writes the live recording's bodies, inside the App Group. The app reads and copies them.
nonisolated enum PacketTunnelBodies {
    static let folder = "traffic-live-bodies"
}

/// How a body is best shown. Port of Android `BodyView`.
nonisolated enum BodyView: Equatable, Sendable {
    case empty
    case image(Data)
    case json(String)
    case text(String)
    case binary(size: Int, hex: String)
}

nonisolated enum BodyPreview {
    static let hexLimit = 4096

    /// Reads `body` as an image, JSON, text or bytes, going by `contentType` and then by the bytes themselves.
    static func of(_ body: [UInt8], contentType: String?) -> BodyView {
        if body.isEmpty { return .empty }
        let type = contentType?.lowercased() ?? ""
        if isImage(body, contentType: type) { return .image(Data(body)) }
        let looksJSON = type.contains("json") || type.isEmpty || type.contains("javascript") || type.contains("text/plain")
        if looksJSON, let pretty = prettyJSON(body) { return .json(pretty.0) }
        if let text = readableText(body) { return .text(text) }
        return .binary(size: body.count, hex: hexDump(body, limit: hexLimit))
    }

    /// True for raster images a phone can draw. SVG is text and is shown as text.
    static func isImage(_ body: [UInt8], contentType: String?) -> Bool {
        let type = contentType?.lowercased() ?? ""
        if type.hasPrefix("image/") && !type.contains("svg") { return true }
        func at(_ i: Int) -> Int { i < body.count ? Int(body[i]) : -1 }
        if at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 { return true }
        if at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF { return true }
        if at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 { return true }
        if at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 && at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 { return true }
        if at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70, body.count >= 12 {
            let brand = String(decoding: body[8..<12], as: UTF8.self)
            return ["avif", "heic", "heix", "mif1"].contains(brand)
        }
        return false
    }

    /// `00000000  48 65 6c 6c 6f  |Hello|`, sixteen bytes a line, at most `limit` bytes.
    static func hexDump(_ body: [UInt8], limit: Int = hexLimit) -> String {
        let end = min(body.count, limit)
        var lines: [String] = []
        var offset = 0
        while offset < end {
            let count = min(16, end - offset)
            var line = String(format: "%08x  ", offset)
            for i in 0..<16 {
                line += i < count ? String(format: "%02x ", body[offset + i]) : "   "
                if i == 7 { line += " " }
            }
            line += " |"
            for i in 0..<count {
                let c = body[offset + i]
                line.append((32...126).contains(c) ? Character(Unicode.Scalar(c)) : ".")
            }
            line += "|"
            lines.append(line)
            offset += count
        }
        return lines.joined(separator: "\n")
    }
}
