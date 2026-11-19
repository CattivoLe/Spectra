import Foundation

nonisolated struct RedirectTrace: Hashable, Sendable {
    struct Hop: Hashable, Sendable {
        var url: URL
        var status: Int
    }

    enum Stop: Hashable, Sendable {
        case tooManyHops
        case failed(String)
        case leftTheWeb(String)
    }

    /// Every response, in the order they came.
    var hops: [Hop]
    /// Where the chain ends: the page that answered without a redirect.
    var final: URL
    /// Why the chain ended early, when it did.
    var stop: Stop?
}

/// Follows a link's HTTP redirects one hop at a time, reading the headers only.
/// No cookies, no cache and no page body, but each server along the way still
/// sees the request: the caller asks the person first.
nonisolated enum RedirectTracer {
    static func trace(_ start: URL, maxHops: Int = 10) async -> RedirectTrace {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieAcceptPolicy = .never
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 10
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }

        var current = start
        var hops: [RedirectTrace.Hop] = []
        for _ in 0..<maxHops {
            do {
                let (bytes, response) = try await session.bytes(for: URLRequest(url: current), delegate: NoRedirects.shared)
                bytes.task.cancel()
                guard let http = response as? HTTPURLResponse else {
                    return RedirectTrace(hops: hops, final: current, stop: nil)
                }
                hops.append(.init(url: current, status: http.statusCode))
                guard (300...399).contains(http.statusCode),
                      let location = http.value(forHTTPHeaderField: "Location"),
                      let next = URL(string: location, relativeTo: current)?.absoluteURL else {
                    return RedirectTrace(hops: hops, final: current, stop: nil)
                }
                guard let scheme = next.scheme?.lowercased(), scheme == "http" || scheme == "https" else {
                    return RedirectTrace(hops: hops, final: next, stop: .leftTheWeb(next.scheme ?? ""))
                }
                current = next
            } catch {
                return RedirectTrace(hops: hops, final: current, stop: .failed(error.localizedDescription))
            }
        }
        return RedirectTrace(hops: hops, final: current, stop: .tooManyHops)
    }
}

/// Hands each 3xx back to the tracer instead of following it.
private nonisolated final class NoRedirects: NSObject, URLSessionTaskDelegate, Sendable {
    static let shared = NoRedirects()

    // The completion-handler form, not the `async` one: Swift 6.4.2 (Xcode 27.2) crashes generating the
    // Objective-C thunk for the async variant of this delegate method. Same effect: nil means "do not follow".
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}
