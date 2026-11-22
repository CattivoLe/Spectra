import Foundation

/// A request to send again, as recorded or as edited on the Repeat screen. Port of Android `RepeatRequest`.
nonisolated struct RepeatRequest: Equatable, Sendable {
    var method: String
    var url: String
    var headers: [HttpField]
    var body: Data?

    /// Headers the client sets itself, or that would contradict the body sent.
    private static let skipped: Set<String> = ["host", "content-length", "connection", "transfer-encoding", "keep-alive", "upgrade", "te"]

    static func from(_ flow: TrafficFlow, _ exchange: HttpExchange, body: Data?) -> RepeatRequest {
        let kept = body.flatMap { $0.isEmpty ? nil : $0 }
        return RepeatRequest(
            method: exchange.method,
            url: requestURL(flow, exchange),
            headers: exchange.requestHeaders.filter { field in
                let name = field.name.lowercased()
                return !name.hasPrefix(":") && !skipped.contains(name) && !(kept != nil && name == "content-encoding")
            },
            body: kept
        )
    }

    /// Headers as editable text, one `Name: value` a line.
    var headerText: String { headers.map { "\($0.name): \($0.value)" }.joined(separator: "\n") }

    /// Reads `Name: value` lines back; lines without a colon are left out.
    static func parseHeaders(_ text: String) -> [HttpField] {
        text.components(separatedBy: .newlines).compactMap { line -> HttpField? in
            guard let colon = line.firstIndex(of: ":"), colon != line.startIndex else { return nil }
            let name = line[..<colon].trimmingCharacters(in: .whitespaces)
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            return skipped.contains(name.lowercased()) ? nil : HttpField(name: name, value: value)
        }
    }
}

/// What came back when a request was sent again.
nonisolated struct RepeatResult: Sendable {
    var status: Int?
    var headers: [HttpField]
    var body: [UInt8]
    var cut: Bool
    var durationMs: Int64
    var error: String?
}

/// Sends a recorded or edited request again, from this app. While a recording runs, the request goes through it
/// like any other and shows up in the list.
nonisolated enum RequestRepeater {
    static func send(_ request: RepeatRequest) async -> RepeatResult {
        let started = Date()
        func elapsed() -> Int64 { Int64(Date().timeIntervalSince(started) * 1000) }
        guard let url = URL(string: request.url) else {
            return RepeatResult(status: nil, headers: [], body: [], cut: false, durationMs: 0, error: String(localized: "Not a URL"))
        }
        var urlRequest = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: 30)
        urlRequest.httpMethod = request.method
        for field in request.headers { urlRequest.addValue(field.value, forHTTPHeaderField: field.name) }
        if let body = request.body, request.method != "GET", request.method != "HEAD" { urlRequest.httpBody = body }
        let session = URLSession(configuration: .ephemeral)
        defer { session.finishTasksAndInvalidate() }
        do {
            let (data, response) = try await session.data(for: urlRequest, delegate: RepeatNoRedirects())
            let http = response as? HTTPURLResponse
            let headers = (http?.allHeaderFields ?? [:]).map { HttpField(name: "\($0.key)", value: "\($0.value)") }
                .sorted { $0.name.lowercased() < $1.name.lowercased() }
            var bytes = [UInt8](data)
            let cut = bytes.count > HttpAssembler.responseCap
            if cut { bytes = Array(bytes.prefix(HttpAssembler.responseCap)) }
            return RepeatResult(status: http?.statusCode, headers: headers, body: bytes, cut: cut, durationMs: elapsed(), error: nil)
        } catch {
            return RepeatResult(status: nil, headers: [], body: [], cut: false, durationMs: elapsed(), error: error.localizedDescription)
        }
    }
}

/// Shows a redirect as it came instead of following it, as the recording would.
private nonisolated final class RepeatNoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
