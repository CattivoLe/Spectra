import Foundation

/// Reads a stored body: the flow, the exchange's index, and whether it is the request's.
typealias BodySource = (_ flow: TrafficFlow, _ index: Int, _ request: Bool) -> Data?

/// The URL an exchange went to, rebuilt from the connection and its Host header.
nonisolated func requestURL(_ flow: TrafficFlow, _ exchange: HttpExchange) -> String {
    let target = exchange.target ?? {
        let query = exchange.query.map { "\(percent($0.name))=\(percent($0.value))" }.joined(separator: "&")
        return query.isEmpty ? exchange.path : "\(exchange.path)?\(query)"
    }()
    if target.hasPrefix("http://") || target.hasPrefix("https://") { return target }
    let scheme = flow.tls ? "https" : "http"
    let authority: String
    if let host = exchange.header("Host", response: false)?.trimmingCharacters(in: .whitespaces), !host.isEmpty {
        authority = host
    } else {
        let name = (flow.host?.isEmpty == false ? flow.host! : nil)
            ?? (flow.destination.contains(":") ? "[\(flow.destination)]" : flow.destination)
        let usual = flow.tls ? 443 : 80
        authority = flow.destinationPort == usual ? name : "\(name):\(flow.destinationPort)"
    }
    return "\(scheme)://\(authority)\(target.hasPrefix("/") ? target : "/" + target)"
}

private nonisolated func percent(_ text: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return text.addingPercentEncoding(withAllowedCharacters: allowed) ?? text
}

/// HTTP Archive 1.2, the format Charles, browsers and proxies exchange recordings in. Port of Android `Har`.
nonisolated enum Har {
    static func build(_ flows: [TrafficFlow], bodies: BodySource, version: String) -> String {
        let ordered = flows.flatMap { flow in flow.exchanges.indices.map { (flow, $0) } }
            .sorted { ($0.0.exchanges[$0.1].startedAt ?? $0.0.startedAt) < ($1.0.exchanges[$1.1].startedAt ?? $1.0.startedAt) }
        let log: [String: Any] = [
            "version": "1.2",
            "creator": ["name": "Spectra", "version": version],
            "entries": ordered.map { entry($0.0, $0.1, bodies) },
        ]
        let data = (try? JSONSerialization.data(withJSONObject: ["log": log], options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    private static func entry(_ flow: TrafficFlow, _ index: Int, _ bodies: BodySource) -> [String: Any] {
        let exchange = flow.exchanges[index]
        let start = exchange.startedAt ?? flow.startedAt
        let send = span(exchange.startedAt, exchange.requestEndAt)
        let wait = span(exchange.requestEndAt, exchange.responseStartAt)
        let receive = span(exchange.responseStartAt, exchange.endedAt)
        return [
            "startedDateTime": isoTime(start),
            "time": exchange.durationMs ?? (send + wait + receive),
            "request": request(flow, index, exchange, bodies),
            "response": response(flow, index, exchange, bodies),
            "cache": [String: Any](),
            "timings": ["blocked": -1, "dns": -1, "connect": -1, "ssl": -1, "send": send, "wait": wait, "receive": receive],
            "serverIPAddress": flow.destination,
            "connection": String(flow.sourcePort),
        ]
    }

    private static func request(_ flow: TrafficFlow, _ index: Int, _ exchange: HttpExchange, _ bodies: BodySource) -> [String: Any] {
        var object: [String: Any] = [
            "method": exchange.method,
            "url": requestURL(flow, exchange),
            "httpVersion": exchange.httpVersion ?? "HTTP/1.1",
            "cookies": [Any](),
            "headers": fields(exchange.requestHeaders),
            "queryString": fields(exchange.query),
            "headersSize": exchange.requestHeaderBytes ?? -1,
            "bodySize": exchange.requestBodyBytes ?? -1,
        ]
        if exchange.requestBodyStored, let body = bodies(flow, index, true), !body.isEmpty {
            var post: [String: Any] = ["mimeType": exchange.header("Content-Type", response: false) ?? ""]
            putText(&post, body)
            if !exchange.parameters.isEmpty,
               exchange.header("Content-Type", response: false)?.lowercased().contains("form-urlencoded") == true {
                post["params"] = fields(exchange.parameters)
            }
            object["postData"] = post
        }
        return object
    }

    private static func response(_ flow: TrafficFlow, _ index: Int, _ exchange: HttpExchange, _ bodies: BodySource) -> [String: Any] {
        var content: [String: Any] = ["mimeType": exchange.header("Content-Type", response: true) ?? ""]
        if exchange.responseBodyStored, let body = bodies(flow, index, false) {
            content["size"] = body.count
            if !body.isEmpty { putText(&content, body) }
            if let wire = exchange.responseBodyBytes { content["compression"] = Int64(body.count) - wire }
        } else if let json = exchange.responseJson {
            content["size"] = json.utf8.count
            content["text"] = json
        } else {
            content["size"] = 0
        }
        if exchange.responseCut { content["comment"] = "Spectra kept only the start of this body." }
        return [
            "status": exchange.status ?? 0,
            "statusText": exchange.statusText ?? "",
            "httpVersion": exchange.httpVersion ?? "HTTP/1.1",
            "cookies": [Any](),
            "headers": fields(exchange.responseHeaders),
            "content": content,
            "redirectURL": exchange.header("Location", response: true) ?? "",
            "headersSize": exchange.responseHeaderBytes ?? -1,
            "bodySize": exchange.responseBodyBytes ?? -1,
        ]
    }

    /// Text when the bytes are UTF-8 a person can read, base64 otherwise.
    private static func putText(_ object: inout [String: Any], _ body: Data) {
        if let text = utf8Text([UInt8](body)) {
            object["text"] = text
        } else {
            object["text"] = body.base64EncodedString()
            object["encoding"] = "base64"
        }
    }

    private static func fields(_ list: [HttpField]) -> [[String: String]] {
        list.map { ["name": $0.name, "value": $0.value] }
    }

    private static func span(_ from: Int64?, _ to: Int64?) -> Int64 {
        guard let from, let to else { return 0 }
        return max(0, to - from)
    }

    static func isoTime(_ millis: Int64) -> String {
        let format = ISO8601DateFormatter()
        format.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        format.timeZone = .current
        return format.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }
}

/// The request as a curl command, to run it again from a terminal. Port of Android `Curl`.
nonisolated enum Curl {
    static func command(_ flow: TrafficFlow, _ exchange: HttpExchange, body: Data?) -> String {
        var parts = ["curl"]
        let hasBody = !(body?.isEmpty ?? true)
        let usual = (exchange.method == "GET" && !hasBody) || (exchange.method == "POST" && hasBody)
        if !usual { parts.append("-X \(exchange.method)") }
        parts.append(quote(requestURL(flow, exchange)))
        var compressed = false
        for field in exchange.requestHeaders {
            let name = field.name.lowercased()
            if name == "host" || name == "content-length" { continue }
            if name == "accept-encoding" { compressed = true; continue }
            if name == "content-encoding" && hasBody { continue }
            parts.append("-H \(quote("\(field.name): \(field.value)"))")
        }
        if compressed { parts.append("--compressed") }
        if hasBody, let body { parts.append("--data-binary \(quoteBytes([UInt8](body)))") }
        return parts.joined(separator: " \\\n  ")
    }

    /// Single quotes keep everything literal; a quote inside closes, escapes and reopens.
    static func quote(_ text: String) -> String {
        "'" + text.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    private static func quoteBytes(_ body: [UInt8]) -> String {
        if let text = utf8Text(body),
           !text.unicodeScalars.contains(where: { $0.value < 32 && $0 != "\n" && $0 != "\r" && $0 != "\t" }) {
            return quote(text)
        }
        var out = "$'"
        for c in body {
            switch c {
            case UInt8(ascii: "'"): out += "\\'"
            case UInt8(ascii: "\\"): out += "\\\\"
            case 32...126: out.append(Character(Unicode.Scalar(c)))
            default: out += String(format: "\\x%02x", c)
            }
        }
        return out + "'"
    }
}

/// The bytes as text when they are valid UTF-8 and not mostly control characters.
private nonisolated func utf8Text(_ body: [UInt8]) -> String? {
    guard let text = String(bytes: body, encoding: .utf8) else { return nil }
    let sample = text.unicodeScalars.prefix(512)
    let bad = sample.filter { $0.value < 32 && $0 != "\n" && $0 != "\r" && $0 != "\t" }.count
    return bad > sample.count / 10 ? nil : text
}
