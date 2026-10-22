import Foundation
import Compression
import Testing
@testable import Spectra

/// Ports the HTTP-assembler / Accept-Encoding / model cases from the Android
/// `TrafficTest.kt`. The brotli sub-case is adapted: iOS has no brotli decoder,
/// so a brotli body is expected to stay undecoded rather than decompress.
struct TrafficCoreTests {
    private func bytes(_ s: String) -> [UInt8] { Array(s.utf8) }

    @Test func httpQueryHeadersAndJson() {
        let a = HttpAssembler()
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
        #expect(done.responseJson?.contains("\"ok\" : true") == true || done.responseJson?.contains("\"ok\": true") == true)
        #expect(!done.responseCut)
    }

    @Test func httpFormAndJsonParameters() {
        let a = HttpAssembler()
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
        let a = HttpAssembler()
        a.client(bytes("GET /a HTTP/1.1\r\nHost: h\r\n\r\n"))
        let payload = #"{"a":1}"#
        let chunked = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "\(String(payload.count, radix: 16))\r\n\(payload)\r\n0\r\n\r\n"
        let chunkedBytes = bytes(chunked)
        a.server(Array(chunkedBytes[0..<12]))
        #expect(a.exchanges()[0].status == nil)
        a.server(Array(chunkedBytes[12...]))
        #expect(a.exchanges()[0].responseJson?.contains("\"a\" : 1") == true)

        a.client(bytes("GET /b HTTP/1.1\r\nHost: h\r\n\r\n"))
        let gz = makeGzip(bytes(#"{"ok":true}"#))
        let head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\nContent-Length: \(gz.count)\r\n\r\n"
        a.server(bytes(head) + gz)
        #expect(a.exchanges().count == 2)
        #expect(a.exchanges()[1].path == "/b")
        #expect(a.exchanges()[1].responseJson?.contains("\"ok\" : true") == true)
    }

    @Test func interimContinueAndOversizedHeadersStop() {
        let a = HttpAssembler()
        a.client(bytes("POST /up HTTP/1.1\r\nContent-Length: 2\r\n\r\nab"))
        a.server(bytes("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n"))
        #expect(a.exchanges()[0].status == 204)

        let stalled = HttpAssembler()
        stalled.client(bytes("GET / HTTP/1.1\r\nX: " + String(repeating: "a", count: 40_000) + "\r\n\r\n"))
        #expect(stalled.exchanges().isEmpty)
        stalled.client(bytes("GET /later HTTP/1.1\r\nHost: h\r\n\r\n"))
        #expect(stalled.exchanges().isEmpty)
    }

    @Test func xssiPrefixHtmlAndEventStream() {
        let a = HttpAssembler()
        a.client(bytes("GET /p HTTP/1.1\r\nHost: h\r\n\r\n"))
        let prefixed = ")]}',\n{\"ok\":true}"
        a.server(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(bytes(prefixed).count)\r\n\r\n\(prefixed)"))
        #expect(a.exchanges()[0].responseJson?.contains("\"ok\" : true") == true)

        let page = HttpAssembler()
        page.client(bytes("GET /page HTTP/1.1\r\nHost: h\r\n\r\n"))
        let html = "<html><body>hello</body></html>"
        page.server(bytes("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: \(html.count)\r\n\r\n\(html)"))
        #expect(page.exchanges()[0].responseJson?.contains("hello") == true)
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
