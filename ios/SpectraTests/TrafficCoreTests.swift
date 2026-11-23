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

    private func hex(_ text: String) -> [UInt8] {
        let clean = text.replacingOccurrences(of: " ", with: "")
        return stride(from: 0, to: clean.count, by: 2).map {
            let start = clean.index(clean.startIndex, offsetBy: $0)
            return UInt8(clean[start..<clean.index(start, offsetBy: 2)], radix: 16)!
        }
    }

    @Test func hpackDecodesTheRfcExamples() throws {
        let plain = HpackDecoder()
        let first = try plain.decode(hex("8286 8441 0f77 7777 2e65 7861 6d70 6c65 2e63 6f6d"))
        #expect(first.map(\.name) == [":method", ":scheme", ":path", ":authority"])
        #expect(first.last?.value == "www.example.com")
        #expect(try plain.decode(hex("8286 84be 5808 6e6f 2d63 6163 6865")).last?.value == "no-cache")
        let third = try plain.decode(hex("8287 85bf 400a 6375 7374 6f6d 2d6b 6579 0c63 7573 746f 6d2d 7661 6c75 65"))
        #expect(third.first { $0.name == "custom-key" }?.value == "custom-value")
        let huffman = HpackDecoder()
        #expect(try huffman.decode(hex("8286 8441 8cf1 e3c2 e5f2 3a6b a0ab 90f4 ff")).last?.value == "www.example.com")
        #expect(try huffman.decode(hex("8286 84be 5886 a8eb 1064 9cbf")).last?.value == "no-cache")
        #expect(try huffman.decode(hex("8287 85bf 4088 25a8 49e9 5ba9 7d7f 8925 a849 e95b b8e8 b4bf")).last?.value == "custom-value")
        let response = try HpackDecoder(maxSize: 256).decode(hex("4882 6402 5885 aec3 771a 4b61 96d0 7abe 9410 54d4 44a8 2005 9504 0b81 66e0 82a6 2d1b ff6e 919d 29ad 1718 63c7 8f0b 97c8 e9ae 82ae 43d3"))
        #expect(response.map(\.value) == ["302", "private", "Mon, 21 Oct 2013 20:13:21 GMT", "https://www.example.com"])
        #expect(throws: (any Error).self) { try HpackDecoder().decode([0xBE]) }
    }

    private func frame(_ type: UInt8, _ flags: UInt8, _ stream: Int, _ payload: [UInt8]) -> [UInt8] {
        [UInt8(payload.count >> 16 & 0xFF), UInt8(payload.count >> 8 & 0xFF), UInt8(payload.count & 0xFF), type, flags,
         UInt8(stream >> 24 & 0xFF), UInt8(stream >> 16 & 0xFF), UInt8(stream >> 8 & 0xFF), UInt8(stream & 0xFF)] + payload
    }

    /// A header block of literal fields without indexing: what any HPACK decoder reads.
    private func literals(_ fields: [(String, String)]) -> [UInt8] {
        fields.flatMap { [0, UInt8($0.0.utf8.count)] + Array($0.0.utf8) + [UInt8($0.1.utf8.count)] + Array($0.1.utf8) }
    }

    @Test func http2StreamsBecomeExchanges() {
        var now: Int64 = 100
        var bodies: [String: [UInt8]] = [:]
        let h2 = Http2Assembler(sink: { index, request, body in bodies["\(index)-\(request)"] = body; return true }, clock: { now })
        let settings = frame(4, 0, 0, [])
        let request = frame(1, 0x4, 1, [0x82, 0x87] + literals([(":path", "/api?x=1"), (":authority", "example.com"), ("content-type", "application/json")]))
        h2.client(Http2Assembler.preface + settings + request)
        now = 110
        h2.client(frame(0, 0x1, 1, bytes(#"{"a":1}"#)))
        h2.client(frame(1, 0x5, 3, literals([(":method", "GET"), (":path", "/b"), (":authority", "example.com")])))
        now = 150
        let response = settings + frame(1, 0x4, 1, literals([(":status", "200"), ("content-type", "application/json")]))
        h2.server(Array(response[0..<15]))
        h2.server(Array(response[15...]))
        now = 170
        h2.server(frame(0, 0x8, 1, [2] + bytes(#"{"ok":true}"#) + [0, 0]) + frame(1, 0x5, 1, literals([("grpc-status", "0")])))
        h2.server(frame(1, 0x5, 3, literals([(":status", "404")])))
        let list = h2.exchanges()
        #expect(list.count == 2)
        let e = list[0]
        #expect(e.method == "GET")
        #expect(e.path == "/api")
        #expect(e.target == "/api?x=1")
        #expect(e.httpVersion == "HTTP/2")
        #expect(e.status == 200)
        #expect(e.header("grpc-status", response: true) == "0")
        #expect(e.startedAt == 100 && e.requestEndAt == 110 && e.responseStartAt == 150 && e.endedAt == 170)
        #expect(e.requestBodyBytes == 7)
        #expect(e.responseBodyBytes == 11)
        #expect(e.requestBodyStored && e.responseBodyStored)
        #expect(e.parameters.first { $0.name == "a" }?.value == "1")
        #expect(bodies["0-false"] == bytes(#"{"ok":true}"#))
        #expect(list[1].status == 404)
        let flow = TrafficFlow(id: 1, proto: "TCP", source: "10.0.0.1", sourcePort: 1, destination: "1.2.3.4",
                               destinationPort: 443, host: "example.com", tls: true, startedAt: 0, lastAt: 0, exchanges: list)
        let curl = Curl.command(flow, e, body: Data(bytes(#"{"a":1}"#)))
        #expect(curl.contains("--http2"))
        #expect(curl.contains("'https://example.com/api?x=1'"))
        #expect(!curl.contains("':"))
        let broken = Http2Assembler()
        broken.client(bytes("GET / HTTP/1.1\r\n\r\n") + [UInt8](repeating: 0, count: 20))
        #expect(broken.exchanges().isEmpty)
    }

    private func wsFrame(_ opcode: UInt8, _ payload: [UInt8], fin: Bool = true, mask: Bool = false, rsv1: Bool = false) -> [UInt8] {
        var head: [UInt8] = [(fin ? 0x80 : 0) | (rsv1 ? 0x40 : 0) | opcode]
        let maskBit: UInt8 = mask ? 0x80 : 0
        if payload.count < 126 { head.append(maskBit | UInt8(payload.count)) } else {
            head += [maskBit | 126, UInt8(payload.count >> 8), UInt8(payload.count & 0xFF)]
        }
        guard mask else { return head + payload }
        let key: [UInt8] = [1, 2, 3, 4]
        return head + key + payload.enumerated().map { $0.element ^ key[$0.offset & 3] }
    }

    @Test func webSocketMessagesFollowTheUpgrade() throws {
        var messages: [String: [UInt8]] = [:]
        var now: Int64 = 0
        let a = HttpAssembler(sink: { _, _, _ in true },
                              messageSink: { index, number, body in messages["\(index)-\(number)"] = body; return true },
                              clock: { now })
        a.client(bytes("GET /chat HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"))
        now = 5
        a.server(bytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n") + wsFrame(9, bytes("p")))
        now = 10
        let parts = wsFrame(1, bytes("hello"), mask: true) + wsFrame(1, bytes("par"), fin: false, mask: true)
            + wsFrame(10, [], mask: true) + wsFrame(0, bytes("ts"), mask: true)
        a.client(Array(parts[0..<9]))
        a.client(Array(parts[9...]))
        // Two messages from one deflate stream (zlib, raw, sync flush, tail removed): the second refers to the first.
        a.server(wsFrame(1, [0x4a, 0xcb, 0x2c, 0x2a, 0x2e, 0x51, 0x48, 0xce, 0xcf, 0x2d, 0x28, 0x4a, 0x2d, 0x2e, 0x4e, 0x4d, 0x01, 0x00], rsv1: true))
        a.server(wsFrame(1, [0x4a, 0x43, 0xe3, 0x2b, 0x24, 0xa6, 0x27, 0x66, 0xe6, 0x01, 0x00], rsv1: true))
        a.server(wsFrame(2, [0, 1, 2]) + wsFrame(8, [0x03, 0xE8] + bytes("bye")))
        let exchange = a.exchanges()[0]
        #expect(exchange.status == 101)
        let list = exchange.messages
        #expect(list.map(\.kind) == ["ping", "text", "pong", "text", "text", "text", "binary", "close"])
        #expect(!list[0].fromClient)
        #expect(list[1].preview == "hello" && list[1].fromClient)
        #expect(list[3].preview == "parts")
        #expect(list[3].at == 10)
        #expect(list[4].preview == "first compressed" && list[4].compressed)
        #expect(list[5].preview == "first compressed again")
        #expect(list[7].preview == "1000 bye")
        #expect(messages["0-6"] == [0, 1, 2])
        #expect(messages["0-3"] == bytes("parts"))
        let again = try JSONDecoder().decode(HttpExchange.self, from: JSONEncoder().encode(exchange))
        #expect(again.messages == list)
        let flow = TrafficFlow(id: 1, proto: "TCP", source: "10.0.0.1", sourcePort: 1, destination: "1.2.3.4",
                               destinationPort: 80, host: "h", startedAt: 0, lastAt: 0, exchanges: [exchange])
        let har = try harLog(Har.build([flow], bodies: { _, _, _ in nil },
                                       messages: { _, index, number in messages["\(index)-\(number)"].map { Data($0) } }, version: "1"))
        let socket = try #require((har["entries"] as? [[String: Any]])?.first?["_webSocketMessages"] as? [[String: Any]])
        #expect(socket.count == 8)
        #expect(socket[1]["type"] as? String == "send")
        #expect(socket[1]["data"] as? String == "hello")
        #expect(socket[6]["data"] as? String == "AAEC")
    }

    @Test func clientHelloAlpnIsRead() {
        let name = bytes("example.com")
        let sni: [UInt8] = [0, 0, 0, UInt8(5 + name.count), 0, UInt8(3 + name.count), 0, 0, UInt8(name.count)] + name
        let protocols: [UInt8] = [2] + bytes("h2") + [8] + bytes("http/1.1")
        let alpn: [UInt8] = [0, 16, 0, UInt8(protocols.count + 2), 0, UInt8(protocols.count)] + protocols
        let extensions = sni + alpn
        var head = [UInt8](repeating: 0, count: 43)
        head[0] = 3; head[1] = 3; head[36] = 2; head[38] = 0x2f; head[39] = 1
        head[41] = UInt8(extensions.count >> 8); head[42] = UInt8(extensions.count & 0xFF)
        let body = head + extensions
        let handshake: [UInt8] = [1, 0, 0, UInt8(body.count)] + body
        let hello: [UInt8] = [22, 3, 3, UInt8(handshake.count >> 8), UInt8(handshake.count & 0xFF)] + handshake
        #expect(TrafficHints.tlsAlpn(hello) == ["h2", "http/1.1"])
        #expect(TrafficHints.tlsServerName(hello) == "example.com")
        #expect(TrafficHints.tlsAlpn(bytes("GET / HTTP/1.1")).isEmpty)
    }

    @Test func mapLocalAnswersMatchingRequestsInsteadOfTheServer() throws {
        let rule = MapRule(id: "r1", host: "*.example.com", path: "/api/user*", status: 201, contentType: "application/json", body: #"{"name":"local"}"#)
        let local = MapLocal([rule, MapRule(id: "off", enabled: false, host: "other.com")])
        #expect(local.covers(host: "api.example.com"))
        #expect(!local.covers(host: "other.com"))
        #expect(local.rule(host: "api.example.com", target: "/api/user/7?full=1") == rule)
        #expect(local.rule(host: "api.example.com", target: "/api/orders") == nil)
        #expect(MapRule(host: "example.com", path: "/a").matches(host: "example.com", target: "/a?x=1"))
        #expect(MapRule(host: "example.com").matches(host: "example.com", target: "/anything"))

        var answers: [[UInt8]] = []
        let strip = AcceptEncodingStrip(host: "api.example.com", mapLocal: local, rewriteEncoding: false)
        let mapped = "POST /api/user HTTP/1.1\r\nHost: api.example.com\r\nContent-Length: 5\r\n\r\nhello"
        let passed = "GET /api/orders HTTP/1.1\r\nHost: api.example.com\r\nAccept-Encoding: gzip\r\n\r\n"
        let forwarded = strip.feed(bytes(mapped + passed), onLocal: { answers.append($0) }).flatMap { $0 }
        #expect(String(decoding: forwarded, as: UTF8.self) == passed)
        let answer = String(decoding: try #require(answers.first), as: UTF8.self)
        #expect(answers.count == 1)
        #expect(answer.hasPrefix("HTTP/1.1 201 Created\r\n"))
        #expect(answer.contains("Content-Length: 16\r\n"))
        #expect(answer.contains("\(MapLocal.header): *.example.com/api/user*"))
        #expect(answer.hasSuffix(#"{"name":"local"}"#))
        let r = Recorder()
        r.client(bytes(mapped))
        r.server(answers[0])
        #expect(r.exchanges()[0].status == 201)
        #expect(r.exchanges()[0].header(MapLocal.header, response: true) != nil)
        #expect(String(decoding: MapLocal.response(rule, method: "HEAD"), as: UTF8.self).hasSuffix("\r\n\r\n"))
        let defaults = try #require(UserDefaults(suiteName: "spectra-tests-\(UUID().uuidString)"))
        MapRule.save([rule], defaults)
        #expect(MapRule.load(defaults) == [rule])
    }

    @Test func throttleBucketsPaceBytes() throws {
        let preset = ThrottleSettings.preset("3g")
        #expect(preset.downKbps == 780 && preset.enabled)
        let defaults = try #require(UserDefaults(suiteName: "spectra-tests-\(UUID().uuidString)"))
        #expect(!ThrottleSettings.load(defaults).enabled)
        preset.save(defaults)
        #expect(ThrottleSettings.load(defaults) == preset)
        let throttle = Throttle(ThrottleSettings(preset: ThrottleSettings.custom, downKbps: 800, upKbps: 0, latencyMs: 120))
        #expect(throttle.latencyMs == 120)
        // 800 kbit/s is 100 bytes a millisecond; the bucket holds 50 ms of that.
        #expect(throttle.down.take(100_000, now: 1_000) == 5_000)
        #expect(throttle.down.take(100, now: 1_000) == 0)
        #expect(throttle.down.take(100_000, now: 1_010) == 1_000)
        #expect(throttle.up.take(100_000, now: 1_010) == 100_000)
        #expect(Throttle(ThrottleSettings()).latencyMs == 0)
    }

    @Test func repeatRequestKeepsWhatTheServerNeeds() {
        let exchange = HttpExchange(
            method: "POST", path: "/api", query: [],
            requestHeaders: [
                HttpField(name: ":authority", value: "example.com"), HttpField(name: "Host", value: "example.com"),
                HttpField(name: "Content-Length", value: "3"), HttpField(name: "Authorization", value: "Bearer x"),
                HttpField(name: "Content-Encoding", value: "gzip"), HttpField(name: "Connection", value: "keep-alive"),
            ],
            parameters: [], status: 200, responseHeaders: [], target: "/api?x=1"
        )
        let flow = TrafficFlow(id: 1, proto: "TCP", source: "10.0.0.1", sourcePort: 1, destination: "1.2.3.4",
                               destinationPort: 443, host: "example.com", tls: true, startedAt: 0, lastAt: 0)
        let again = RepeatRequest.from(flow, exchange, body: Data("abc".utf8))
        #expect(again.url == "https://example.com/api?x=1")
        #expect(again.headers.map(\.name) == ["Authorization"])
        #expect(again.headerText == "Authorization: Bearer x")
        #expect(RepeatRequest.parseHeaders("X-A: 1 2\nnot a header\nY:\nHost: h") == [HttpField(name: "X-A", value: "1 2"), HttpField(name: "Y", value: "")])
        #expect(RepeatRequest.from(flow, exchange, body: Data()).body == nil)
    }

    @Test func acceptEncodingStripStepsAsideAfterAnUpgrade() {
        let strip = AcceptEncodingStrip()
        let frame: [UInt8] = [0x81, 0x82, 1, 2, 3, 4, 0x69 ^ 1, 0x69 ^ 2]
        let out = strip.feed(bytes("GET /chat HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\n\r\n") + frame).flatMap { $0 }
        #expect(Array(out.suffix(frame.count)) == frame)
        #expect(strip.feed(frame).flatMap { $0 } == frame)
        let other = AcceptEncodingStrip()
        #expect(other.feed([0x16, 3, 3, 0, 5]).flatMap { $0 } == [0x16, 3, 3, 0, 5])
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
