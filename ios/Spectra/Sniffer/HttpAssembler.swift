import Foundation
import Compression

/// Splits a cleartext HTTP byte stream into requests and responses. A body over its cap is read only up to the
/// cap so the next message still lines up, and the exchange says it was cut. Finished bodies go to `sink` once
/// Content-Encoding is undone (gzip, deflate and Brotli through the `Compression` framework); the exchanges keep
/// their times and sizes. Port of the Android `core/HttpExchange.kt` `HttpAssembler`.
nonisolated final class HttpAssembler {
    /// Takes a finished body: the exchange's index, whether it is the request's, the decoded bytes. True when kept.
    typealias Sink = (_ index: Int, _ request: Bool, _ body: [UInt8]) -> Bool

    private let sink: Sink
    private let clock: () -> Int64
    private let client = ByteCursor()
    private let server = ByteCursor()
    private var ready: [HttpExchange] = []
    private var step: Step = .requestHeaders
    private var open = false
    private var stopped = false
    private var method = ""
    private var path = ""
    private var target = ""
    private var version: String?
    private var requestHeaders: [HttpField] = []
    private var requestBody = BodyReader(plan: .none, limit: requestCap)
    private var status = 0
    private var statusText: String?
    private var responseHeaders: [HttpField] = []
    private var responseBody = BodyReader(plan: .none, limit: responseCap)
    private var parameters: [HttpField] = []
    private var requestCut = false
    private var requestStored = false
    private var requestHeaderBytes: Int64 = 0
    private var responseHeaderBytes: Int64 = 0
    /// When the oldest byte still waiting in each cursor arrived.
    private var clientMark: Int64?
    private var serverMark: Int64?
    private var startedAt: Int64?
    private var requestEndAt: Int64?
    private var responseStartAt: Int64?

    init(sink: @escaping Sink = { _, _, _ in false }, clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.sink = sink
        self.clock = clock
    }

    func exchanges() -> [HttpExchange] { ready }

    func client(_ data: [UInt8]) {
        if stopped || data.isEmpty { return }
        if client.available() == 0 { clientMark = clock() }
        client.append(data)
        pump()
    }

    func server(_ data: [UInt8]) {
        if stopped || data.isEmpty { return }
        if server.available() == 0 { serverMark = clock() }
        server.append(data)
        pump()
    }

    /// The server closed, so a response without a length ends here.
    func serverClosed() {
        if stopped { return }
        if step == .responseBody { responseBody.finishAtClose() }
        pump()
    }

    private enum Step { case requestHeaders, requestBody, responseHeaders, responseBody }

    private struct BodyResult {
        var stored = false
        var cut = false
        var wireBytes: Int64 = 0
    }

    private func pump() {
        while !stopped {
            switch step {
            case .requestHeaders:
                if client.overflow() { stopped = true; return }
                let mark = clientMark
                guard let header = client.takeHeaders() else { return }
                guard let parsed = parseRequest(header) else { stopped = true; return }
                if ready.count >= Self.maxExchanges { stopped = true; return }
                startedAt = mark ?? clock()
                clientMark = client.available() > 0 ? clock() : nil
                requestEndAt = nil
                responseStartAt = nil
                requestHeaderBytes = Int64(header.unicodeScalars.count + 4)
                responseHeaderBytes = 0
                method = parsed.method
                target = parsed.target
                version = parsed.version
                path = target.components(separatedBy: "?").first.flatMap { $0.isEmpty ? nil : $0 } ?? "/"
                requestHeaders = parsed.headers
                statusText = nil
                parameters = []
                requestCut = false
                requestStored = false
                requestBody = BodyReader(plan: Self.plan(requestHeaders, request: true, method: method, status: 0), limit: Self.requestCap)
                step = .requestBody
            case .requestBody:
                if !requestBody.pump(client) { return }
                requestEndAt = clock()
                if client.available() == 0 { clientMark = nil }
                let (decoded, decodeCut) = decodeBody(requestBody.bytes(), headers: requestHeaders, cap: Self.requestCap)
                requestCut = requestBody.cut || decodeCut
                var fields: [HttpField] = []
                if !requestCut && isForm(requestHeaders) { fields += formFields(decoded) }
                if !requestCut && isJSONType(requestHeaders) { fields += jsonFields(decoded) }
                parameters = Array(fields.prefix(Self.fieldCap))
                requestStored = !decoded.isEmpty && sink(ready.count, true, decoded)
                publish(response: false, body: nil)
                step = .responseHeaders
            case .responseHeaders:
                if server.overflow() { stopped = true; return }
                let mark = serverMark
                guard let header = server.takeHeaders() else { return }
                guard let parsed = parseStatus(header) else { stopped = true; return }
                if responseStartAt == nil { responseStartAt = mark ?? clock() }
                serverMark = server.available() > 0 ? clock() : nil
                responseHeaderBytes += Int64(header.unicodeScalars.count + 4)
                status = parsed.status
                statusText = parsed.text
                responseHeaders = parsed.headers
                if status >= 100 && status <= 199 && status != 101 { continue }
                if status == 101 || method == "CONNECT" {
                    publish(response: true, body: BodyResult(), endedAt: clock())
                    stopped = true
                    return
                }
                responseBody = BodyReader(plan: Self.plan(responseHeaders, request: false, method: method, status: status), limit: Self.responseCap)
                step = .responseBody
            case .responseBody:
                if !responseBody.pump(server) { return }
                let end = clock()
                if server.available() == 0 { serverMark = nil }
                let (decoded, decodeCut) = decodeBody(responseBody.bytes(), headers: responseHeaders, cap: Self.responseCap)
                let stored = !decoded.isEmpty && sink(max(0, ready.count - 1), false, decoded)
                publish(response: true,
                        body: BodyResult(stored: stored, cut: responseBody.cut || decodeCut, wireBytes: responseBody.consumed),
                        endedAt: end)
                step = .requestHeaders
            }
        }
    }

    private func publish(response: Bool, body: BodyResult?, endedAt: Int64? = nil) {
        let exchange = HttpExchange(
            method: method, path: path, query: queryFields(target),
            requestHeaders: requestHeaders, parameters: parameters,
            status: response ? status : nil,
            responseHeaders: response ? responseHeaders : [],
            responseJson: nil, responseCut: body?.cut ?? false, requestCut: requestCut,
            requestBodyStored: requestStored, responseBodyStored: body?.stored ?? false,
            httpVersion: version, target: target, statusText: response ? statusText : nil,
            startedAt: startedAt, requestEndAt: requestEndAt,
            responseStartAt: response ? responseStartAt : nil, endedAt: endedAt,
            requestHeaderBytes: requestHeaderBytes, requestBodyBytes: requestBody.consumed,
            responseHeaderBytes: response ? responseHeaderBytes : nil,
            responseBodyBytes: response ? body?.wireBytes : nil
        )
        if open { ready[ready.count - 1] = exchange } else { ready.append(exchange) }
        open = !response
    }

    private static func plan(_ headers: [HttpField], request: Bool, method: String, status: Int) -> BodyPlan {
        if !request && (method == "HEAD" || status == 204 || status == 304 || (status >= 100 && status <= 199)) { return .none }
        if headers.contains(where: { $0.name.lowercased() == "transfer-encoding" && $0.value.lowercased().contains("chunked") }) {
            return .chunked
        }
        if let lengthValue = headers.first(where: { $0.name.lowercased() == "content-length" })?.value.trimmingCharacters(in: .whitespaces),
           let length = Int(lengthValue), length >= 0 {
            return .fixed(length)
        }
        return request ? .none : .untilClose
    }

    private static let maxExchanges = 24
    /// The most of a request body that is read and kept.
    static let requestCap = 1024 * 1024
    /// The most of a response body that is read and kept, before and after it is decompressed.
    static let responseCap = 4 * 1024 * 1024
    private static let headerCap = 32 * 1024
    private static let fieldCap = 40

    // MARK: Byte cursor

    private final class ByteCursor {
        private var data: [UInt8] = []
        private var offset = 0

        func append(_ more: [UInt8]) {
            if offset > 8192 { data = Array(data[offset...]); offset = 0 }
            data += more
        }

        func available() -> Int { data.count - offset }

        func indexOf(_ pattern: [UInt8]) -> Int {
            let last = data.count - pattern.count
            var index = offset
            while index <= last {
                var matches = true
                for step in 0..<pattern.count where data[index + step] != pattern[step] { matches = false; break }
                if matches { return index - offset }
                index += 1
            }
            return -1
        }

        func overflow() -> Bool {
            let end = indexOf(headerEnd)
            return end > HttpAssembler.headerCap || (end < 0 && available() > HttpAssembler.headerCap)
        }

        func takeHeaders() -> String? {
            let end = indexOf(headerEnd)
            if end < 0 || end > HttpAssembler.headerCap { return nil }
            let text = latin1(Array(data[offset..<offset + end]))
            offset += end + headerEnd.count
            return text
        }

        func read(_ count: Int) -> [UInt8] {
            let out = Array(data[offset..<offset + count])
            offset += count
            return out
        }
    }

    // MARK: Body reader

    private enum BodyPlan { case none, chunked, untilClose, fixed(Int) }

    private final class BodyReader {
        private let plan: BodyPlan
        private let limit: Int
        private var kept: [UInt8] = []
        var cut = false
        private var done = false
        private var atClose = false
        private var fixedLeft: Int
        private var chunkLeft = 0
        private var phase: Chunk = .size
        /// Every byte the body took on the wire, chunk framing included.
        private(set) var consumed: Int64 = 0

        init(plan: BodyPlan, limit: Int) {
            self.plan = plan
            self.limit = limit
            if case let .fixed(length) = plan { fixedLeft = length } else { fixedLeft = 0 }
        }

        func bytes() -> [UInt8] { kept }
        func finishAtClose() { atClose = true }

        private func take(_ src: ByteCursor, _ count: Int) -> [UInt8] {
            consumed += Int64(count)
            return src.read(count)
        }

        func pump(_ src: ByteCursor) -> Bool {
            if done { return true }
            switch plan {
            case .none:
                done = true
            case .untilClose:
                if src.available() > 0 { keep(take(src, src.available())) }
                if atClose { done = true }
            case .fixed:
                while fixedLeft > 0 && src.available() > 0 {
                    let count = min(fixedLeft, src.available())
                    keep(take(src, count))
                    fixedLeft -= count
                }
                if fixedLeft == 0 { done = true }
            case .chunked:
                pumpChunks(src)
            }
            return done
        }

        private func pumpChunks(_ src: ByteCursor) {
            while !done && src.available() > 0 {
                switch phase {
                case .size:
                    let end = src.indexOf(lineEnd)
                    if end < 0 { return }
                    let line = latin1(take(src, end)).components(separatedBy: ";").first?.trimmingCharacters(in: .whitespaces) ?? ""
                    _ = take(src, 2)
                    guard let size = Int(line, radix: 16) else { done = true; return }
                    chunkLeft = size
                    phase = size == 0 ? .trailers : .data
                case .data:
                    if chunkLeft == 0 { phase = .after; continue }
                    let count = min(chunkLeft, src.available())
                    if count == 0 { return }
                    keep(take(src, count))
                    chunkLeft -= count
                    if chunkLeft == 0 { phase = .after }
                case .after:
                    if src.available() < 2 { return }
                    _ = take(src, 2)
                    phase = .size
                case .trailers:
                    if src.available() < 2 { return }
                    if src.indexOf(lineEnd) == 0 { _ = take(src, 2); done = true; return }
                    let end = src.indexOf(headerEnd)
                    if end < 0 {
                        if src.available() > HttpAssembler.headerCap { done = true }
                        return
                    }
                    _ = take(src, end + headerEnd.count)
                    done = true
                }
            }
        }

        private func keep(_ chunk: [UInt8]) {
            let room = limit - kept.count
            if room <= 0 { cut = true; return }
            if chunk.count <= room { kept += chunk } else { kept += chunk[0..<room]; cut = true }
        }

        private enum Chunk { case size, data, after, trailers }
    }
}

// MARK: - Free helpers (ported)

private nonisolated let headerEnd: [UInt8] = [13, 10, 13, 10]
private nonisolated let lineEnd: [UInt8] = [13, 10]
private nonisolated let httpMethods: Set<String> = ["GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS", "CONNECT", "TRACE"]

/// ISO-8859-1 decode: each byte becomes the scalar of the same value.
private nonisolated func latin1(_ bytes: [UInt8]) -> String {
    String(String.UnicodeScalarView(bytes.map { Unicode.Scalar($0) }))
}

private nonisolated struct RequestLine {
    let method: String
    let target: String
    let version: String?
    let headers: [HttpField]
}

private nonisolated func parseRequest(_ header: String) -> RequestLine? {
    let lines = header.components(separatedBy: "\r\n")
    let parts = (lines.first ?? "").components(separatedBy: " ")
    guard parts.count >= 2, httpMethods.contains(parts[0]) else { return nil }
    let version = parts.count > 2 && parts[2].hasPrefix("HTTP/") ? parts[2] : nil
    return RequestLine(method: parts[0], target: parts[1], version: version, headers: headerFields(Array(lines.dropFirst())))
}

private nonisolated func parseStatus(_ header: String) -> (status: Int, text: String?, headers: [HttpField])? {
    let lines = header.components(separatedBy: "\r\n")
    let parts = (lines.first ?? "").components(separatedBy: " ")
    guard parts.count >= 2, parts[0].hasPrefix("HTTP/"), let status = Int(parts[1]) else { return nil }
    let text = parts.dropFirst(2).joined(separator: " ").trimmingCharacters(in: .whitespaces)
    return (status, text.isEmpty ? nil : text, headerFields(Array(lines.dropFirst())))
}

private nonisolated func headerFields(_ lines: [String]) -> [HttpField] {
    lines.compactMap { line in
        guard let colon = line.firstIndex(of: ":"), colon != line.startIndex else { return nil }
        let name = String(line[..<colon]).trimmingCharacters(in: .whitespaces)
        let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
        return HttpField(name: name, value: value)
    }
}

private nonisolated func queryFields(_ target: String) -> [HttpField] {
    guard let q = target.range(of: "?") else { return [] }
    let query = String(target[target.index(after: q.lowerBound)...])
    if query.isEmpty { return [] }
    return query.components(separatedBy: "&").prefix(40).compactMap { pair in
        if pair.isEmpty { return nil }
        let name = pair.components(separatedBy: "=").first ?? ""
        let value = pair.range(of: "=").map { String(pair[pair.index(after: $0.lowerBound)...]) } ?? ""
        return HttpField(name: urlDecode(name), value: urlDecode(value))
    }
}

private nonisolated func formFields(_ body: [UInt8]) -> [HttpField] {
    if body.isEmpty { return [] }
    return String(decoding: body, as: UTF8.self).components(separatedBy: "&").prefix(40).compactMap { pair in
        if pair.isEmpty { return nil }
        let name = pair.components(separatedBy: "=").first ?? ""
        let value = pair.range(of: "=").map { String(pair[pair.index(after: $0.lowerBound)...]) } ?? ""
        return HttpField(name: urlDecode(name), value: urlDecode(value))
    }
}

private nonisolated func jsonFields(_ body: [UInt8]) -> [HttpField] {
    let text = String(decoding: body, as: UTF8.self).trimmingCharacters(in: .whitespaces)
    guard text.hasPrefix("{"), let data = text.data(using: .utf8),
          let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [] }
    return obj.prefix(40).compactMap { key, value in
        if value is [String: Any] || value is [Any] { return nil }
        if let s = value as? String { return HttpField(name: key, value: s) }
        if let n = value as? NSNumber { return HttpField(name: key, value: n.stringValue) }
        return HttpField(name: key, value: String(describing: value))
    }
}

private nonisolated func isForm(_ headers: [HttpField]) -> Bool {
    headers.contains { $0.name.lowercased() == "content-type" && $0.value.lowercased().contains("application/x-www-form-urlencoded") }
}

private nonisolated func isJSONType(_ headers: [HttpField]) -> Bool {
    headers.contains { $0.name.lowercased() == "content-type" && $0.value.lowercased().contains("json") }
}

/// Undoes Content-Encoding (gzip, deflate, Brotli), keeping at most `cap` bytes; the flag says the result was
/// cut. What a damaged or cut stream gave before it failed is kept. A body that cannot be decoded at all comes
/// back as it was.
nonisolated func decodeBody(_ body: [UInt8], headers: [HttpField], cap: Int) -> ([UInt8], Bool) {
    if body.isEmpty { return (body, false) }
    let encoding = headers.first { $0.name.lowercased() == "content-encoding" }?.value.lowercased() ?? ""
    let looksGzip = body.count > 2 && body[0] == 0x1f && body[1] == 0x8b
    var decoded: ([UInt8], Bool)?
    if encoding.contains("br") || encoding.contains("brotli") {
        decoded = streamDecode(body, algorithm: COMPRESSION_BROTLI, cap: cap)
    } else if encoding.contains("gzip") || looksGzip {
        decoded = gzipPayload(body).flatMap { streamDecode($0, algorithm: COMPRESSION_ZLIB, cap: cap) }
    } else if encoding.contains("deflate") {
        decoded = streamDecode(stripZlibHeader(body), algorithm: COMPRESSION_ZLIB, cap: cap)
            ?? streamDecode(body, algorithm: COMPRESSION_ZLIB, cap: cap)
    }
    if let decoded { return decoded }
    return body.count > cap ? (Array(body.prefix(cap)), true) : (body, false)
}

/// Text worth showing; binary (and undecodable) bodies are skipped.
nonisolated func readableText(_ body: [UInt8]) -> String? {
    if body.isEmpty { return nil }
    var text = String(decoding: body, as: UTF8.self)
    if text.first == "\u{FEFF}" { text.removeFirst() }
    text = text.trimmingCharacters(in: .whitespacesAndNewlines)
    if text.isEmpty { return nil }
    let sample = text.prefix(512)
    var bad = 0
    for ch in sample.unicodeScalars where ch == "\u{FFFD}" || (ch.value < 32 && ch != "\n" && ch != "\r" && ch != "\t") { bad += 1 }
    return bad > sample.unicodeScalars.count / 10 ? nil : text
}

/// Pretty-prints a JSON body; strips the common XSSI guard prefixes first.
nonisolated func prettyJSON(_ body: [UInt8]) -> (String, Bool)? {
    var text = String(decoding: body, as: UTF8.self)
    if text.first == "\u{FEFF}" { text.removeFirst() }
    text = text.trimmingCharacters(in: .whitespacesAndNewlines)
    if text.hasPrefix(")]}',") { text = String(text.dropFirst(5)).trimmingCharacters(in: .whitespacesAndNewlines) }
    if text.hasPrefix("while(1);") { text = String(text.dropFirst(9)).trimmingCharacters(in: .whitespacesAndNewlines) }
    guard text.hasPrefix("{") || text.hasPrefix("[") else { return nil }
    guard let data = text.data(using: .utf8),
          let obj = try? JSONSerialization.jsonObject(with: data),
          let pretty = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted]) else {
        return (text, false)
    }
    return (String(decoding: pretty, as: UTF8.self), false)
}

private nonisolated func urlDecode(_ text: String) -> String {
    text.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? text
}

// MARK: Compression helpers

/// The deflate stream inside a gzip member, past its header.
private nonisolated func gzipPayload(_ body: [UInt8]) -> [UInt8]? {
    guard body.count > 10, body[0] == 0x1f, body[1] == 0x8b, body[2] == 8 else { return nil }
    let flags = body[3]
    var index = 10
    if flags & 0x04 != 0 { // FEXTRA
        guard index + 2 <= body.count else { return nil }
        let xlen = Int(body[index]) | (Int(body[index + 1]) << 8)
        index += 2 + xlen
    }
    if flags & 0x08 != 0 { while index < body.count && body[index] != 0 { index += 1 }; index += 1 } // FNAME
    if flags & 0x10 != 0 { while index < body.count && body[index] != 0 { index += 1 }; index += 1 } // FCOMMENT
    if flags & 0x02 != 0 { index += 2 } // FHCRC
    guard index < body.count else { return nil }
    return Array(body[index...])
}

private nonisolated func stripZlibHeader(_ body: [UInt8]) -> [UInt8] {
    if body.count > 2, body[0] & 0x0f == 8, (Int(body[0]) << 8 | Int(body[1])) % 31 == 0 {
        return Array(body[2...])
    }
    return body
}

/// Decodes with a `compression_stream`, so the output can be capped and a truncated input still yields what it
/// holds. Nil when nothing at all came out. (`COMPRESSION_ZLIB` is raw DEFLATE.)
private nonisolated func streamDecode(_ input: [UInt8], algorithm: compression_algorithm, cap: Int) -> ([UInt8], Bool)? {
    if input.isEmpty { return nil }
    let chunk = 64 * 1024
    let dst = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
    defer { dst.deallocate() }
    var stream = compression_stream(dst_ptr: dst, dst_size: 0, src_ptr: UnsafePointer(dst), src_size: 0, state: nil)
    guard compression_stream_init(&stream, COMPRESSION_STREAM_DECODE, algorithm) == COMPRESSION_STATUS_OK else { return nil }
    defer { compression_stream_destroy(&stream) }
    var out: [UInt8] = []
    return input.withUnsafeBufferPointer { src -> ([UInt8], Bool)? in
        stream.src_ptr = src.baseAddress!
        stream.src_size = src.count
        var idle = 0
        while true {
            stream.dst_ptr = dst
            stream.dst_size = chunk
            let status = compression_stream_process(&stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
            let produced = chunk - stream.dst_size
            if produced > 0 {
                let room = cap - out.count
                if produced > room {
                    out.append(contentsOf: UnsafeBufferPointer(start: dst, count: room))
                    return (out, true)
                }
                out.append(contentsOf: UnsafeBufferPointer(start: dst, count: produced))
                idle = 0
            } else {
                idle += 1
            }
            switch status {
            case COMPRESSION_STATUS_END:
                return out.isEmpty ? nil : (out, false)
            case COMPRESSION_STATUS_OK:
                // Input used up without an end marker: the body was cut short.
                if idle > 1 || (produced == 0 && stream.src_size == 0) { return out.isEmpty ? nil : (out, true) }
            default:
                return out.isEmpty ? nil : (out, true)
            }
        }
    }
}
