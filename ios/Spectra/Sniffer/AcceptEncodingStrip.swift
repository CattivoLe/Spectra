import Foundation

/// Rewrites cleartext requests so the server sends an uncompressed body:
/// `Accept-Encoding` becomes `identity`. The bytes the phone sent are kept as-is
/// for the recording; only the copy forwarded to the server changes. Direct port
/// of the Android `core/AcceptEncodingStrip.kt`.
///
/// With `mapLocal`, a request a rule matches is not forwarded at all, body included: `onLocal` gets the response
/// to give the phone instead. Responses go out in order only while the phone waits for each answer before the
/// next request, as HTTP/1.1 clients do (pipelining is practically unused).
nonisolated final class AcceptEncodingStrip {
    private enum Mode { case headers, fixed, chunkSize, chunkData, chunkCRLF, chunkTrailers }

    private let host: String?
    private let mapLocal: MapLocal?
    private let onLocal: ([UInt8]) -> Void
    private var localSink: (([UInt8]) -> Void)?
    private let rewriteEncoding: Bool
    /// The body being read belongs to a request answered locally: it is dropped, not forwarded.
    private var dropping = false

    init(host: String? = nil, mapLocal: MapLocal? = nil, onLocal: @escaping ([UInt8]) -> Void = { _ in },
         rewriteEncoding: Bool = true) {
        self.host = host
        self.mapLocal = mapLocal
        self.onLocal = onLocal
        self.rewriteEncoding = rewriteEncoding
    }

    private var pending: [UInt8] = []
    private var mode: Mode = .headers
    private var fixedLeft = 0
    private var chunkLeft = 0
    private var passthrough = false

    /// `feed`, with the local answers made on the way handed to `onLocal` (instead of the one given at init).
    func feed(_ data: [UInt8], onLocal: @escaping ([UInt8]) -> Void) -> [[UInt8]] {
        localSink = onLocal
        defer { localSink = nil }
        return feed(data)
    }

    func feed(_ data: [UInt8]) -> [[UInt8]] {
        if data.isEmpty { return [] }
        if passthrough { return [data] }
        pending += data
        return drain(finish: false)
    }

    /// Forwards whatever is still buffered when the phone closes.
    func finish() -> [[UInt8]] {
        if passthrough { return [] }
        passthrough = true
        return drain(finish: true)
    }

    private func drain(finish: Bool) -> [[UInt8]] {
        let buf = pending
        pending = []
        var out: [[UInt8]] = []
        var offset = 0
        loop: while offset < buf.count {
            switch mode {
            case .headers:
                let end = indexOf(buf, from: offset, pattern: Self.headerEnd)
                if end < 0 && !couldBeRequest(buf, offset) {
                    // Not HTTP/1.1 any more (a WebSocket, another protocol): let it all through at once.
                    passthrough = true
                    out.append(Array(buf[offset...]))
                    offset = buf.count
                    break loop
                }
                if end < 0 {
                    if finish || buf.count - offset > Self.headerCap {
                        passthrough = true
                        out.append(Array(buf[offset...]))
                        offset = buf.count
                    }
                    break loop
                }
                let text = String(decoding: buf[offset..<end], as: Unicode.ASCII.self)
                let first = text.components(separatedBy: "\r\n").first ?? ""
                let method = first.components(separatedBy: " ").first ?? ""
                if !Self.methods.contains(method) {
                    passthrough = true
                    out.append(Array(buf[offset...]))
                    offset = buf.count
                    break loop
                }
                dropping = false
                let target = first.components(separatedBy: " ").dropFirst().first ?? ""
                if method != "CONNECT", let rule = mapLocal?.rule(host: host, target: target) {
                    let answer = MapLocal.response(rule, method: method)
                    if let localSink { localSink(answer) } else { onLocal(answer) }
                    offset = end + Self.headerEnd.count
                    mode = bodyMode(text)
                    dropping = mode != .headers
                    continue loop
                }
                out.append(Array(((rewriteEncoding ? rewrite(text) : text) + "\r\n\r\n").utf8))
                offset = end + Self.headerEnd.count
                if method == "CONNECT" || text.lowercased().contains("\r\nupgrade:") {
                    passthrough = true
                    if offset < buf.count { out.append(Array(buf[offset...])) }
                    offset = buf.count
                    break loop
                }
                mode = bodyMode(text)
            case .fixed:
                let take = min(fixedLeft, buf.count - offset)
                if take > 0 { keep(&out, Array(buf[offset..<offset + take])) }
                offset += take
                fixedLeft -= take
                if fixedLeft == 0 { mode = .headers } else { break loop }
            case .chunkSize:
                let end = indexOf(buf, from: offset, pattern: Self.lineEnd)
                if end < 0 { break loop }
                let line = String(decoding: buf[offset..<end], as: Unicode.ASCII.self)
                    .components(separatedBy: ";").first?.trimmingCharacters(in: .whitespaces) ?? ""
                keep(&out, Array(buf[offset..<end + Self.lineEnd.count]))
                offset = end + Self.lineEnd.count
                if let size = Int(line, radix: 16) {
                    if size == 0 { mode = .chunkTrailers } else { chunkLeft = size; mode = .chunkData }
                } else {
                    passthrough = true
                    if offset < buf.count { keep(&out, Array(buf[offset...])) }
                    offset = buf.count
                }
            case .chunkData:
                let take = min(chunkLeft, buf.count - offset)
                if take > 0 { keep(&out, Array(buf[offset..<offset + take])) }
                offset += take
                chunkLeft -= take
                if chunkLeft == 0 { mode = .chunkCRLF } else { break loop }
            case .chunkCRLF:
                if buf.count - offset < 2 { break loop }
                keep(&out, Array(buf[offset..<offset + 2]))
                offset += 2
                mode = .chunkSize
            case .chunkTrailers:
                if buf.count - offset >= 2, buf[offset] == 13, buf[offset + 1] == 10 {
                    keep(&out, Array(buf[offset..<offset + 2]))
                    offset += 2
                    mode = .headers
                } else {
                    let end = indexOf(buf, from: offset, pattern: Self.headerEnd)
                    if end < 0 { break loop }
                    keep(&out, Array(buf[offset..<end + Self.headerEnd.count]))
                    offset = end + Self.headerEnd.count
                    mode = .headers
                }
            }
        }
        if offset < buf.count { pending = Array(buf[offset...]) }
        return out
    }

    private func keep(_ out: inout [[UInt8]], _ bytes: [UInt8]) {
        if !dropping { out.append(bytes) }
    }

    /// True while the bytes from `offset` could still be the start of a request line.
    private func couldBeRequest(_ buf: [UInt8], _ offset: Int) -> Bool {
        let head = String(decoding: buf[offset..<min(buf.count, offset + 8)], as: UTF8.self)
        return Self.methods.contains { method in (method + " ").hasPrefix(head) || head.hasPrefix(method + " ") }
    }

    private func bodyMode(_ header: String) -> Mode {
        let lines = header.components(separatedBy: "\r\n").dropFirst()
        if lines.contains(where: { $0.lowercased().hasPrefix("transfer-encoding:") && $0.lowercased().contains("chunked") }) {
            return .chunkSize
        }
        if let lengthLine = lines.first(where: { $0.lowercased().hasPrefix("content-length:") }),
           let length = Int(lengthLine.drop(while: { $0 != ":" }).dropFirst().trimmingCharacters(in: .whitespaces)),
           length > 0 {
            fixedLeft = length
            return .fixed
        }
        return .headers
    }

    private func rewrite(_ header: String) -> String {
        header.components(separatedBy: "\r\n").map { line in
            line.lowercased().hasPrefix("accept-encoding:") ? "Accept-Encoding: identity" : line
        }.joined(separator: "\r\n")
    }

    private func indexOf(_ data: [UInt8], from start: Int, pattern: [UInt8]) -> Int {
        if pattern.isEmpty || data.count < pattern.count { return -1 }
        let last = data.count - pattern.count
        var index = start
        while index <= last {
            var matches = true
            for step in 0..<pattern.count where data[index + step] != pattern[step] { matches = false; break }
            if matches { return index }
            index += 1
        }
        return -1
    }

    private static let headerEnd: [UInt8] = [13, 10, 13, 10]
    private static let lineEnd: [UInt8] = [13, 10]
    private static let headerCap = 32 * 1024
    private static let methods: Set<String> = ["GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS", "CONNECT", "TRACE"]
}
