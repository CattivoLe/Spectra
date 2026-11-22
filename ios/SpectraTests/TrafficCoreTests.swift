import Foundation
import Compression
import Testing
@testable import Spectra

/// Ports the HTTP-assembler / Accept-Encoding / model / HAR / cURL / host-pattern cases from the Android
/// `TrafficTest.kt`. Bodies leave the assembler through its sink, so `Recorder` keeps them for the checks.
struct TrafficCoreTests {
    private func bytes(_ s: String) -> [UInt8] { Array(s.utf8) }

    @Test func httpQueryHeadersAndJson() {
        let a = Recorder()
        a.client(bytes("GET /search?q=hello+world&n=2 HTTP/1.1\r\nHost: example.com\r\nAccept: application/json\r\n\r\n"))
        let open = a.exchanges()
        #expect(open.count == 1)
        #expect(open[0].method == "GET")
        #expect(open[0].path == "/search")
        #expect(open[0].query.first { $0.name == "q" }?.value == "hello world")
        #expect(open[0].requestHeaders.first { $0.name == "Host" }?.value == "example.com")
        #expect(open[0].status == nil)
        let body = #"{"ok":true,"n":2}"#
        a.server(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(bytes(body).count)\r\n\r\n\(body)"))
        let done = a.exchanges()[0]
        #expect(done.status == 200)
        #expect(a.responseText(0)?.contains("\"ok\" : true") == true || a.responseText(0)?.contains("\"ok\": true") == true)
        #expect(!done.responseCut)
    }

    @Test func httpFormAndJsonParameters() {
        let a = Recorder()
        let form = "user=a%20b&n=1"
        a.client(bytes("POST /login HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: \(form.count)\r\n\r\n\(form)"))
        a.server(bytes("HTTP/1.1 204 No Content\r\n\r\n"))
        let posted = a.exchanges()[0]
        #expect(posted.parameters.first { $0.name == "user" }?.value == "a b")
        #expect(posted.status == 204)
        let json = #"{"id":7,"name":"ada","nested":{"x":1}}"#
        a.client(bytes("POST /api HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: \(bytes(json).count)\r\n\r\n\(json)"))
        a.server(bytes("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"))
        let created = a.exchanges()[1]
        #expect(created.parameters.first { $0.name == "id" }?.value == "7")
        #expect(created.parameters.first { $0.name == "name" }?.value == "ada")
        #expect(created.parameters.allSatisfy { $0.name != "nested" })
    }

    @Test func chunkedJsonThenNextRequestAndGzip() {
        let a = Recorder()
        a.client(bytes("GET /a HTTP/1.1\r\nHost: h\r\n\r\n"))
        let payload = #"{"a":1}"#
        let chunked = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "\(String(payload.count, radix: 16))\r\n\(payload)\r\n0\r\n\r\n"
        let chunkedBytes = bytes(chunked)
        a.server(Array(chunkedBytes[0..<12]))
        #expect(a.exchanges()[0].status == nil)
        a.server(Array(chunkedBytes[12...]))
        #expect(a.responseText(0)?.contains("\"a\" : 1") == true)

        a.client(bytes("GET /b HTTP/1.1\r\nHost: h\r\n\r\n"))
        let gz = makeGzip(bytes(#"{"ok":true}"#))
        let head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\nContent-Length: \(gz.count)\r\n\r\n"
        a.server(bytes(head) + gz)
        #expect(a.exchanges().count == 2)
        #expect(a.exchanges()[1].path == "/b")
        #expect(a.responseText(1)?.contains("\"ok\" : true") == true)
    }

    @Test func interimContinueAndOversizedHeadersStop() {
        let a = Recorder()
        a.client(bytes("POST /up HTTP/1.1\r\nContent-Length: 2\r\n\r\nab"))
        a.server(bytes("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n"))
        #expect(a.exchanges()[0].status == 204)

        let stalled = Recorder()
        stalled.client(bytes("GET / HTTP/1.1\r\nX: " + String(repeating: "a", count: 40_000) + "\r\n\r\n"))
        #expect(stalled.exchanges().isEmpty)
        stalled.client(bytes("GET /later HTTP/1.1\r\nHost: h\r\n\r\n"))
        #expect(stalled.exchanges().isEmpty)
    }

    @Test func xssiPrefixHtmlAndEventStream() {
        let a = Recorder()
        a.client(bytes("GET /p HTTP/1.1\r\nHost: h\r\n\r\n"))
        let prefixed = ")]}',\n{\"ok\":true}"
        a.server(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(bytes(prefixed).count)\r\n\r\n\(prefixed)"))
        #expect(a.responseText(0)?.contains("\"ok\" : true") == true)

        let page = Recorder()
        page.client(bytes("GET /page HTTP/1.1\r\nHost: h\r\n\r\n"))
        let html = "<html><body>hello</body></html>"
        page.server(bytes("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: \(html.count)\r\n\r\n\(html)"))
        #expect(page.responseText(0)?.contains("hello") == true)
    }

    @Test func acceptEncodingIsRewrittenButBodyKept() {
        let strip = AcceptEncodingStrip()
        let body = #"{"ok":true}"#
        let request = "POST /api HTTP/1.1\r\nHost: example.com\r\nAccept-Encoding: gzip, deflate, br\r\nContent-Length: \(body.count)\r\n\r\n\(body)"
        var out = ""
        for piece in request.chunked(5) {
            for chunk in strip.feed(bytes(piece)) {
                out += String(String.UnicodeScalarView(chunk.map { Unicode.Scalar($0) }))
            }
        }
        #expect(out.contains("Accept-Encoding: identity"))
        #expect(!out.contains("br"))
        #expect(out.hasSuffix(body))
    }

    @Test func sessionRoundTrips() {
        let exchange = HttpExchange(
            method: "GET", path: "/index", query: [HttpField(name: "q", value: "1")],
            requestHeaders: [HttpField(name: "Host", value: "example.com")],
            parameters: [], status: 200,
            responseHeaders: [HttpField(name: "Content-Type", value: "application/json")],
            responseJson: "{\n  \"ok\": true\n}", responseCut: false
        )
        let flow = TrafficFlow(
            id: 4, proto: "TCP", source: "10.111.222.1", sourcePort: 9,
            destination: "1.2.3.4", destinationPort: 443, host: "example.com",
            request: "GET /index", app: "Chrome", tls: false, sent: 20, received: 80,
            startedAt: 10, lastAt: 30, closed: true, exchanges: [exchange]
        )
        #expect(flow.kind == .http)
        var plain = flow; plain.exchanges = []; plain.request = nil; plain.tls = true
        #expect(plain.kind == .tls)
        let session = TrafficSession(id: "abc", startedAt: 10, stoppedAt: 40, flows: [flow])
        let decoded = TrafficSession.decode(session.encoded())
        #expect(decoded != nil)
        #expect(decoded?.id == "abc")
        #expect(decoded?.flows.count == 1)
        #expect(decoded?.flows[0] == flow)
    }

    @Test func bodiesTimesAndSizesAreRecorded() {
        let r = Recorder()
        r.now = 1_000
        let form = "user=ada&pin=1"
        let head = "POST /login?next=%2Fhome HTTP/1.1\r\nHost: example.com\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: \(form.count)\r\n\r\n"
        r.client(bytes(head + form))
        r.now = 1_120
        let png: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0]
        let reply = bytes("HTTP/1.1 201 Created\r\nContent-Type: image/png\r\nContent-Length: \(png.count)\r\n\r\n")
        r.server(Array(reply[0..<10]))
        r.now = 1_300
        r.server(Array(reply[10...]) + png)
        let e = r.exchanges()[0]
        #expect(e.startedAt == 1_000)
        #expect(e.requestEndAt == 1_000)
        #expect(e.responseStartAt == 1_120)
        #expect(e.endedAt == 1_300)
        #expect(e.waitMs == 120)
        #expect(e.durationMs == 300)
        #expect(e.requestHeaderBytes == Int64(head.utf8.count))
        #expect(e.requestBodyBytes == Int64(form.count))
        #expect(e.requestSize == Int64(head.utf8.count + form.count))
        #expect(e.responseSize == Int64(reply.count + png.count))
        #expect(e.httpVersion == "HTTP/1.1")
        #expect(e.statusText == "Created")
        #expect(e.target == "/login?next=%2Fhome")
        #expect(e.requestBodyStored)
        #expect(e.responseBodyStored)
        #expect(r.bodies["0-true"] == bytes(form))
        #expect(r.bodies["0-false"] == png)
        if case .image = BodyPreview.of(png, contentType: "image/png") {} else { Issue.record("PNG is not an image") }
        #expect(e.parameters.first { $0.name == "user" }?.value == "ada")
    }

    @Test func rawRequestBodiesAndCutsAreKept() {
        let r = Recorder()
        let blob = (0..<300).map { UInt8($0 & 0xFF) }
        r.client(bytes("PUT /blob HTTP/1.1\r\nHost: h\r\nContent-Type: application/octet-stream\r\nContent-Length: \(blob.count)\r\n\r\n") + blob)
        r.server(bytes("HTTP/1.1 204 No Content\r\n\r\n"))
        #expect(r.bodies["0-true"] == blob)
        guard case let .binary(size, hex) = BodyPreview.of(blob, contentType: "application/octet-stream") else {
            Issue.record("bytes are not binary"); return
        }
        #expect(size == 300)
        #expect(hex.hasPrefix("00000000  00 01 02 03"))
        #expect(!r.exchanges()[0].responseBodyStored)

        let big = Recorder()
        let huge = [UInt8](repeating: UInt8(ascii: "a"), count: HttpAssembler.requestCap + 10)
        big.client(bytes("POST /up HTTP/1.1\r\nHost: h\r\nContent-Length: \(huge.count)\r\n\r\n") + huge)
        let cut = big.exchanges()[0]
        #expect(cut.requestCut)
        #expect(cut.requestBodyBytes == Int64(huge.count))
        #expect(big.bodies["0-true"]?.count == HttpAssembler.requestCap)

        guard case let .json(text) = BodyPreview.of(bytes(#"{"a":[1,2]}"#), contentType: "application/json") else {
            Issue.record("JSON not recognised"); return
        }
        #expect(text.contains("\"a\""))
    }

    @Test func brotliBodiesAreDecoded() {
        let r = Recorder()
        r.client(bytes("GET /b HTTP/1.1\r\nHost: h\r\n\r\n"))
        let compressed: [UInt8] = [0x0f, 0x05, 0x80, 0x7b, 0x22, 0x6f, 0x6b, 0x22, 0x3a, 0x74, 0x72, 0x75, 0x65, 0x7d, 0x03]
        r.server(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: br\r\nContent-Length: \(compressed.count)\r\n\r\n") + compressed)
        #expect(r.bodies["0-false"] == bytes(#"{"ok":true}"#))
        #expect(r.exchanges()[0].responseBodyBytes == Int64(compressed.count))
        let (decoded, cut) = decodeBody(compressed, headers: [HttpField(name: "Content-Encoding", value: "br")], cap: 4)
        #expect(decoded == bytes(#"{"ok"#))
        #expect(cut)
    }

    @Test func chunkedSizeCountsFramingAndUnkeptBodiesAreFlagged() {
        let a = HttpAssembler(sink: { _, _, _ in false })
        a.client(bytes("GET /c HTTP/1.1\r\nHost: h\r\n\r\n"))
        let body = "5\r\nhello\r\n0\r\n\r\n"
        a.server(bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + body))
        #expect(a.exchanges()[0].responseBodyBytes == Int64(body.utf8.count))
        #expect(!a.exchanges()[0].responseBodyStored)
    }

    @Test func oldSessionsStillOpen() throws {
        let legacy = #"""
        {"id":"s","startedAt":1,"stoppedAt":2,"flows":[{"id":3,"proto":"TCP","source":"10.0.0.1","sourcePort":5,
        "destination":"1.2.3.4","destinationPort":80,"host":"h","request":"GET /","tls":false,"sent":1,"received":2,
        "startedAt":1,"lastAt":2,"closed":true,"refused":false,"exchanges":[{"method":"GET","path":"/","query":[],
        "requestHeaders":[{"name":"Host","value":"h"}],"parameters":[],"status":200,"responseHeaders":[],
        "responseJson":"{\n  \"ok\": true\n}","responseCut":false}]}]}
        """#
        let session = try #require(TrafficSession.decode(Data(legacy.utf8)))
        let e = session.flows[0].exchanges[0]
        #expect(e.status == 200)
        #expect(e.responseJson?.contains("ok") == true)
        #expect(e.startedAt == nil)
        #expect(e.requestSize == nil)
        #expect(e.durationMs == nil)
        #expect(!e.responseBodyStored)
        var newer = e
        newer.startedAt = 5
        newer.endedAt = 9
        newer.target = "/?a=1"
        let again = try JSONDecoder().decode(HttpExchange.self, from: JSONEncoder().encode(newer))
        #expect(again.durationMs == 4)
        #expect(again.target == "/?a=1")
        let har = try harLog(Har.build(session.flows, bodies: { _, _, _ in nil }, version: "1"))
        let entry = try #require((har["entries"] as? [[String: Any]])?.first)
        let content = try #require((entry["response"] as? [String: Any])?["content"] as? [String: Any])
        #expect((content["text"] as? String)?.contains("ok") == true)
    }

    @Test func harDescribesEveryExchange() throws {
        let get = HttpExchange(
            method: "GET", path: "/a", query: [HttpField(name: "q", value: "1 2")],
            requestHeaders: [HttpField(name: "Host", value: "example.com"), HttpField(name: "Accept", value: "*/*")],
            parameters: [], status: 200, responseHeaders: [HttpField(name: "Content-Type", value: "image/png")],
            responseBodyStored: true, httpVersion: "HTTP/1.1", target: "/a?q=1%202", statusText: "OK",
            startedAt: 1_700_000_000_000, requestEndAt: 1_700_000_000_010, responseStartAt: 1_700_000_000_050,
            endedAt: 1_700_000_000_080, requestHeaderBytes: 40, requestBodyBytes: 0, responseHeaderBytes: 60, responseBodyBytes: 3
        )
        let post = HttpExchange(
            method: "POST", path: "/b", query: [], requestHeaders: [HttpField(name: "Content-Type", value: "application/json")],
            parameters: [], status: nil, responseHeaders: [], requestBodyStored: true, target: "/b",
            startedAt: 1_700_000_000_100, requestEndAt: 1_700_000_000_110, requestHeaderBytes: 40, requestBodyBytes: 7
        )
        let flow = TrafficFlow(id: 9, proto: "TCP", source: "10.0.0.1", sourcePort: 50000, destination: "1.2.3.4",
                               destinationPort: 443, host: "example.com", tls: true, startedAt: 1, lastAt: 2,
                               closed: true, exchanges: [get, post])
        let stored: [String: Data] = ["0-false": Data([1, 2, 3]), "1-true": Data(#"{"x":1}"#.utf8)]
        let har = try harLog(Har.build([flow], bodies: { _, index, request in stored["\(index)-\(request)"] }, version: "1.1.0"))
        #expect(har["version"] as? String == "1.2")
        #expect((har["creator"] as? [String: Any])?["name"] as? String == "Spectra")
        let entries = try #require(har["entries"] as? [[String: Any]])
        #expect(entries.count == 2)
        let first = entries[0]
        #expect(first["time"] as? Int == 80)
        #expect((first["startedDateTime"] as? String)?.contains("T") == true)
        let request = try #require(first["request"] as? [String: Any])
        #expect(request["url"] as? String == "https://example.com/a?q=1%202")
        #expect((request["queryString"] as? [[String: String]])?.first?["value"] == "1 2")
        let timings = try #require(first["timings"] as? [String: Any])
        #expect(timings["send"] as? Int == 10)
        #expect(timings["wait"] as? Int == 40)
        #expect(timings["receive"] as? Int == 30)
        let response = try #require(first["response"] as? [String: Any])
        #expect(response["status"] as? Int == 200)
        #expect(response["statusText"] as? String == "OK")
        let content = try #require(response["content"] as? [String: Any])
        #expect(content["encoding"] as? String == "base64")
        #expect(content["text"] as? String == "AQID")
        #expect(content["mimeType"] as? String == "image/png")
        let second = entries[1]
        #expect(((second["request"] as? [String: Any])?["postData"] as? [String: Any])?["text"] as? String == #"{"x":1}"#)
        #expect((second["response"] as? [String: Any])?["status"] as? Int == 0)
    }

    @Test func curlRebuildsTheRequest() {
        let exchange = HttpExchange(
            method: "POST", path: "/api", query: [],
            requestHeaders: [
                HttpField(name: "Host", value: "example.com:8443"),
                HttpField(name: "Content-Type", value: "application/json"),
                HttpField(name: "Content-Length", value: "13"),
                HttpField(name: "Accept-Encoding", value: "gzip"),
                HttpField(name: "X-Note", value: "it's"),
            ],
            parameters: [], status: 200, responseHeaders: [], target: "/api?x=1"
        )
        let flow = TrafficFlow(id: 1, proto: "TCP", source: "10.0.0.1", sourcePort: 1, destination: "1.2.3.4",
                               destinationPort: 8443, host: "example.com", tls: true, startedAt: 0, lastAt: 0)
        let command = Curl.command(flow, exchange, body: Data(#"{"a":"b'c"}"#.utf8))
        #expect(command.hasPrefix("curl \\\n  'https://example.com:8443/api?x=1'"))
        #expect(!command.contains("-X POST"))
        #expect(!command.contains("Content-Length"))
        #expect(!command.contains("Host:"))
        #expect(command.contains("--compressed"))
        #expect(command.contains(#"-H 'X-Note: it'\''s'"#))
        #expect(command.contains(#"--data-binary '{"a":"b'\''c"}'"#))
        let plain = TrafficFlow(id: 1, proto: "TCP", source: "10.0.0.1", sourcePort: 1, destination: "1.2.3.4",
                                destinationPort: 80, tls: false, startedAt: 0, lastAt: 0)
        let delete = HttpExchange(method: "DELETE", path: "/x", query: [], requestHeaders: [], parameters: [],
                                  status: nil, responseHeaders: [], target: "/x")
        #expect(Curl.command(plain, delete, body: nil) == "curl \\\n  -X DELETE \\\n  'http://1.2.3.4/x'")
        let binary = HttpExchange(method: "POST", path: "/api", query: [], requestHeaders: [], parameters: [],
                                  status: nil, responseHeaders: [], target: "/api")
        #expect(Curl.command(flow, binary, body: Data([0, UInt8(ascii: "a"), 0x27])).hasSuffix(#"--data-binary $'\x00a\''"#))
    }

    @Test func hostPatternsMatchLikeCharles() {
        #expect(TlsHostFilter.matches("*.example.com", "api.example.com"))
        #expect(TlsHostFilter.matches("*.example.com", "a.b.example.com"))
        #expect(TlsHostFilter.matches("*.example.com", "example.com"))
        #expect(!TlsHostFilter.matches("*.example.com", "badexample.com"))
        #expect(TlsHostFilter.matches("EXAMPLE.com:443", "example.com"))
        #expect(TlsHostFilter.matches("api?.example.com", "api2.example.com"))
        #expect(TlsHostFilter.matches("*google*", "www.google.ru"))
        #expect(!TlsHostFilter.matches("example.com", "www.example.com"))
        #expect(TlsHostFilter.normalize("  ") == nil)
        #expect(TlsHostFilter.normalize("bad host") == nil)
        #expect(TlsHostFilter.normalize("https://Example.com/path") == "example.com")

        let all = TlsHostFilter()
        #expect(all.allows("x.com"))
        #expect(all.allows(nil))
        let only = TlsHostFilter(mode: .only, only: ["*.example.com"], except: ["pinned.example.com"])
        #expect(only.allows("api.example.com"))
        #expect(!only.allows("pinned.example.com"))
        #expect(!only.allows("other.com"))
        #expect(!only.allows(nil))
        let except = TlsHostFilter(mode: .except, except: ["*.apple.com"])
        #expect(!except.allows("gs.apple.com"))
        #expect(except.allows("example.com"))
        #expect(except.allows(nil))
        let excluded = all.excluding("Pinned.App.com")
        #expect(excluded.mode == .except)
        #expect(excluded.except == ["pinned.app.com"])
        #expect(!excluded.allows("pinned.app.com"))
        #expect(excluded.excluding("pinned.app.com") == excluded)
        #expect(only.excluding("api.example.com").mode == .only)
    }

    @Test func hostFilterTravelsThroughTheAppGroupDefaults() throws {
        let defaults = try #require(UserDefaults(suiteName: "spectra-tests-\(UUID().uuidString)"))
        #expect(TlsHostFilter.load(defaults) == TlsHostFilter())
        let filter = TlsHostFilter(mode: .only, only: ["*.a.com"], except: ["b.a.com"])
        filter.save(defaults)
        #expect(TlsHostFilter.load(defaults) == filter)
    }

    @Test func bodyFolderKeepsBudgetAndCopies() throws {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent("spectra-bodies-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = TrafficBodies(dir: base.appendingPathComponent("live"))
        #expect(store.reserve(10))
        store.write(flowID: 3, index: 0, request: false, body: Data("hello".utf8))
        #expect(store.read(flowID: 3, index: 0, request: false) == Data("hello".utf8))
        #expect(store.read(flowID: 3, index: 0, request: true) == nil)
        #expect(!store.reserve(Int(TrafficBodies.budget)))
        let saved = base.appendingPathComponent("s.bodies")
        store.copy(to: saved)
        #expect(TrafficBodies(dir: saved).read(flowID: 3, index: 0, request: false) == Data("hello".utf8))
        store.clear()
        #expect(store.read(flowID: 3, index: 0, request: false) == nil)
        #expect(TrafficBodies(dir: saved).read(flowID: 3, index: 0, request: false) == Data("hello".utf8))
        #expect(store.reserve(10))
    }

    private func harLog(_ text: String) throws -> [String: Any] {
        let object = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]
        return try #require(object?["log"] as? [String: Any])
    }

    /// gzip header + raw DEFLATE of `payload` (no trailer — the inflater ignores it).
    private func makeGzip(_ payload: [UInt8]) -> [UInt8] {
        var deflated = [UInt8](repeating: 0, count: max(payload.count * 2, 64))
        let n = payload.withUnsafeBufferPointer { src in
            deflated.withUnsafeMutableBufferPointer { dst in
                compression_encode_buffer(dst.baseAddress!, dst.count, src.baseAddress!, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        let header: [UInt8] = [0x1f, 0x8b, 0x08, 0, 0, 0, 0, 0, 0, 0xff]
        return header + Array(deflated[0..<n])
    }
}

/// An assembler that keeps every body it is handed, on a clock the test moves.
private final class Recorder {
    var now: Int64 = 0
    var bodies: [String: [UInt8]] = [:]
    private lazy var assembler = HttpAssembler(sink: { [unowned self] index, request, body in
        self.bodies["\(index)-\(request)"] = body
        return true
    }, clock: { [unowned self] in self.now })

    func client(_ data: [UInt8]) { assembler.client(data) }
    func server(_ data: [UInt8]) { assembler.server(data) }
    func exchanges() -> [HttpExchange] { assembler.exchanges() }

    /// The response body as the screen shows text: formatted JSON or plain text.
    func responseText(_ index: Int) -> String? {
        guard let body = bodies["\(index)-false"] else { return nil }
        switch BodyPreview.of(body, contentType: nil) {
        case .json(let text), .text(let text): return text
        default: return nil
        }
    }
}

private extension String {
    /// Splits into substrings of at most `size` characters.
    func chunked(_ size: Int) -> [String] {
        var result: [String] = []
        var index = startIndex
        while index < endIndex {
            let next = self.index(index, offsetBy: size, limitedBy: endIndex) ?? endIndex
            result.append(String(self[index..<next]))
            index = next
        }
        return result
    }
}
