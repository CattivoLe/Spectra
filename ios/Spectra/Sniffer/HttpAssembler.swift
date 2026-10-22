import Foundation
import Compression

/// Splits a cleartext HTTP byte stream into requests and responses. A body over
/// `bodyCap` is kept only up to the cap so the next message still lines up.
/// Direct port of the Android `core/HttpExchange.kt` `HttpAssembler`.
/// Brotli bodies are not decoded on iOS (no system decoder); gzip/deflate use
/// the `Compression` framework.
nonisolated final class HttpAssembler {
    private let client = ByteCursor()
    private let server = ByteCursor()
    private var ready: [HttpExchange] = []
    private var step: Step = .requestHeaders
    private var open = false
    private var stopped = false
    private var method = ""
    private var path = ""
    private var target = ""
    private var requestHeaders: [HttpField] = []
    private var requestBody = BodyReader(plan: .none, limit: bodyCap)
    private var status = 0
    private var responseHeaders: [HttpField] = []
    private var responseBody = BodyReader(plan: .none, limit: bodyCap)

    func exchanges() -> [HttpExchange] { ready }

    func client(_ data: [UInt8]) {
        if stopped || data.isEmpty { return }
        client.append(data)
        pump()
    }

    func server(_ data: [UInt8]) {
        if stopped || data.isEmpty { return }
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

    private func pump() {
        while !stopped {
            switch step {
            case .requestHeaders:
                if client.overflow() { stopped = true; return }
                guard let header = client.takeHeaders() else { return }
                guard let parsed = parseRequest(header) else { stopped = true; return }
                if ready.count >= Self.maxExchanges { stopped = true; return }
                method = parsed.0
                target = parsed.1
                path = target.components(separatedBy: "?").first.flatMap { $0.isEmpty ? nil : $0 } ?? "/"
                requestHeaders = parsed.2
                requestBody = BodyReader(plan: Self.plan(requestHeaders, request: true, method: method, status: 0), limit: Self.bodyCap)
                step = .requestBody
            case .requestBody:
                if !requestBody.pump(client) { return }
                publish(status: nil, responseHeaders: [], body: nil, cut: false)
                step = .responseHeaders
            case .responseHeaders:
                if server.overflow() { stopped = true; return }
                guard let header = server.takeHeaders() else { return }
                guard let parsed = parseStatus(header) else { stopped = true; return }
                status = parsed.0
                responseHeaders = parsed.1
                if status >= 100 && status <= 199 && status != 101 { continue }
                if status == 101 || method == "CONNECT" {
                    publish(status: status, responseHeaders: responseHeaders, body: nil, cut: false, complete: true)
                    stopped = true
                    return
                }
                responseBody = BodyReader(plan: Self.plan(responseHeaders, request: false, method: method, status: status), limit: Self.bodyCap)
                step = .responseBody
            case .responseBody:
                if !responseBody.pump(server) { return }
                let decoded = inflate(responseBody.bytes(), headers: responseHeaders)
                let json = decoded.flatMap { prettyJSON($0) }
                let shown = json?.0 ?? decoded.flatMap { readableText($0) }
                publish(status: status, responseHeaders: responseHeaders, body: shown, cut: responseBody.cut || (json?.1 ?? false), complete: true)
                step = .requestHeaders
            }
        }
    }

    private func publish(status: Int?, responseHeaders: [HttpField], body: String?, cut: Bool, complete: Bool = false) {
        let raw = requestBody.cut ? [] : (inflate(requestBody.bytes(), headers: requestHeaders) ?? [])
        var parameters: [HttpField] = []
        if isForm(requestHeaders) { parameters += formFields(raw) }
        if isJSONType(requestHeaders) { parameters += jsonFields(raw) }
        parameters = Array(parameters.prefix(Self.fieldCap))
        let exchange = HttpExchange(
            method: method, path: path, query: queryFields(target),
            requestHeaders: requestHeaders, parameters: parameters, status: status,
            responseHeaders: responseHeaders, responseJson: body, responseCut: cut
        )
        if open { ready[ready.count - 1] = exchange } else { ready.append(exchange) }
        open = !complete
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
    static let bodyCap = 4 * 1024 * 1024
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

        init(plan: BodyPlan, limit: Int) {
            self.plan = plan
            self.limit = limit
            if case let .fixed(length) = plan { fixedLeft = length } else { fixedLeft = 0 }
        }

        func bytes() -> [UInt8] { kept }
        func finishAtClose() { atClose = true }

        func pump(_ src: ByteCursor) -> Bool {
            if done { return true }
            switch plan {
            case .none:
                done = true
            case .untilClose:
                keep(src.read(src.available()))
                if atClose { done = true }
            case .fixed:
                while fixedLeft > 0 && src.available() > 0 {
                    let count = min(fixedLeft, src.available())
                    keep(src.read(count))
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
                    let line = latin1(src.read(end)).components(separatedBy: ";").first?.trimmingCharacters(in: .whitespaces) ?? ""
                    _ = src.read(2)
                    guard let size = Int(line, radix: 16) else { done = true; return }
                    chunkLeft = size
                    phase = size == 0 ? .trailers : .data
                case .data:
                    if chunkLeft == 0 { phase = .after; continue }
                    let count = min(chunkLeft, src.available())
                    if count == 0 { return }
                    keep(src.read(count))
                    chunkLeft -= count
                    if chunkLeft == 0 { phase = .after }
                case .after:
                    if src.available() < 2 { return }
                    _ = src.read(2)
                    phase = .size
                case .trailers:
                    if src.available() < 2 { return }
                    if src.indexOf(lineEnd) == 0 { _ = src.read(2); done = true; return }
                    let end = src.indexOf(headerEnd)
                    if end < 0 {
                        if src.available() > HttpAssembler.headerCap { done = true }
                        return
                    }
                    _ = src.read(end + headerEnd.count)
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

private let headerEnd: [UInt8] = [13, 10, 13, 10]
private let lineEnd: [UInt8] = [13, 10]
private let httpMethods: Set<String> = ["GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS", "CONNECT", "TRACE"]

/// ISO-8859-1 decode: each byte becomes the scalar of the same value.
private func latin1(_ bytes: [UInt8]) -> String {
    String(String.UnicodeScalarView(bytes.map { Unicode.Scalar($0) }))
}

private func parseRequest(_ header: String) -> (String, String, [HttpField])? {
    let lines = header.components(separatedBy: "\r\n")
    let parts = (lines.first ?? "").components(separatedBy: " ")
    guard parts.count >= 2, httpMethods.contains(parts[0]) else { return nil }
    return (parts[0], parts[1], headerFields(Array(lines.dropFirst())))
}

private func parseStatus(_ header: String) -> (Int, [HttpField])? {
    let lines = header.components(separatedBy: "\r\n")
    let parts = (lines.first ?? "").components(separatedBy: " ")
    guard parts.count >= 2, parts[0].hasPrefix("HTTP/"), let status = Int(parts[1]) else { return nil }
    return (status, headerFields(Array(lines.dropFirst())))
}

private func headerFields(_ lines: [String]) -> [HttpField] {
    lines.compactMap { line in
        guard let colon = line.firstIndex(of: ":"), colon != line.startIndex else { return nil }
        let name = String(line[..<colon]).trimmingCharacters(in: .whitespaces)
        let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
        return HttpField(name: name, value: value)
    }
}

private func queryFields(_ target: String) -> [HttpField] {
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

private func formFields(_ body: [UInt8]) -> [HttpField] {
    if body.isEmpty { return [] }
    return String(decoding: body, as: UTF8.self).components(separatedBy: "&").prefix(40).compactMap { pair in
        if pair.isEmpty { return nil }
        let name = pair.components(separatedBy: "=").first ?? ""
        let value = pair.range(of: "=").map { String(pair[pair.index(after: $0.lowerBound)...]) } ?? ""
        return HttpField(name: urlDecode(name), value: urlDecode(value))
    }
}

private func jsonFields(_ body: [UInt8]) -> [HttpField] {
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

private func isForm(_ headers: [HttpField]) -> Bool {
    headers.contains { $0.name.lowercased() == "content-type" && $0.value.lowercased().contains("application/x-www-form-urlencoded") }
}

private func isJSONType(_ headers: [HttpField]) -> Bool {
    headers.contains { $0.name.lowercased() == "content-type" && $0.value.lowercased().contains("json") }
}

/// Decompresses the body per Content-Encoding. gzip/deflate via `Compression`;
/// brotli is not supported on iOS and is left as-is.
private func inflate(_ body: [UInt8], headers: [HttpField]) -> [UInt8]? {
    if body.isEmpty { return body }
    let encoding = headers.first { $0.name.lowercased() == "content-encoding" }?.value.lowercased() ?? ""
    let looksGzip = body.count > 2 && body[0] == 0x1f && body[1] == 0x8b
    if encoding.contains("br") || encoding.contains("brotli") { return body } // unsupported decoder
    if encoding.contains("gzip") || looksGzip { return gunzip(body) ?? body }
    if encoding.contains("deflate") { return rawInflate(stripZlibHeader(body)) ?? rawInflate(body) ?? body }
    return body
}

/// Text worth showing; binary (and undecodable) bodies are skipped.
private func readableText(_ body: [UInt8]) -> String? {
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
private func prettyJSON(_ body: [UInt8]) -> (String, Bool)? {
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

private func urlDecode(_ text: String) -> String {
    text.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? text
}

// MARK: Compression helpers

private func gunzip(_ body: [UInt8]) -> [UInt8]? {
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
    return rawInflate(Array(body[index...]))
}

private func stripZlibHeader(_ body: [UInt8]) -> [UInt8] {
    if body.count > 2, body[0] & 0x0f == 8, (Int(body[0]) << 8 | Int(body[1])) % 31 == 0 {
        return Array(body[2...])
    }
    return body
}

/// Raw DEFLATE inflate via the Compression framework, growing the output buffer
/// until it fits (bodies are capped upstream).
private func rawInflate(_ data: [UInt8]) -> [UInt8]? {
    if data.isEmpty { return nil }
    var capacity = max(data.count * 4, 16 * 1024)
    for _ in 0..<8 {
        var dst = [UInt8](repeating: 0, count: capacity)
        let written = data.withUnsafeBufferPointer { src in
            dst.withUnsafeMutableBufferPointer { out in
                compression_decode_buffer(out.baseAddress!, out.count, src.baseAddress!, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        if written == 0 { return nil }
        if written < capacity { return Array(dst[0..<written]) }
        capacity *= 2
    }
    return nil
}
