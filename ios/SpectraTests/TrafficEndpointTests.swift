import Foundation
import Testing
@testable import Spectra

/// Requests and connections for the tests of recorded traffic.
nonisolated enum TrafficSamples {
    static func exchange(_ method: String = "GET", _ target: String = "/", host: String? = "api.example.com", status: Int? = 200,
                         request: [(String, String)] = [], response: [(String, String)] = [], parameters: [(String, String)] = [],
                         at start: Int64? = 1000, took: Int64? = 100, wait: Int64? = nil, size: Int64 = 2000, version: String = "HTTP/1.1",
                         messages: [WsMessage] = []) -> HttpExchange {
        func fields(_ list: [(String, String)]) -> [HttpField] { list.map { HttpField(name: $0.0, value: $0.1) } }
        let named = host.map { [version == "HTTP/2" ? (":authority", $0) : ("Host", $0)] } ?? []
        var made = HttpExchange(
            method: method, path: target.components(separatedBy: "?")[0], query: queryFields(target),
            requestHeaders: fields(named + request), parameters: fields(parameters), status: status, responseHeaders: fields(response),
            httpVersion: version, target: target, startedAt: start, requestEndAt: start,
            responseStartAt: start.flatMap { start in (wait ?? took).map { start + $0 } }, endedAt: start.flatMap { start in took.map { start + $0 } },
            requestHeaderBytes: 300, requestBodyBytes: 0, responseHeaderBytes: 200, responseBodyBytes: size
        )
        made.messages = messages
        return made
    }

    static func flow(id: Int64 = 1, host: String? = "api.example.com", tls: Bool = true, closed: Bool = true, refused: Bool = false,
                     _ exchanges: HttpExchange...) -> TrafficFlow {
        TrafficFlow(id: id, proto: "TCP", source: "10.0.0.2", sourcePort: 40000, destination: "93.184.216.34", destinationPort: tls ? 443 : 80,
                    host: host, tls: tls, sent: 500, received: 5000, startedAt: 1000 + id, lastAt: 9000, closed: closed,
                    exchanges: exchanges, refused: refused)
    }

    /// Looks like a JSON Web Token; signs nothing.
    static let jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLW5vdC1yZWFs"
}

struct EndpointTests {
    /// The same table is in Android's `EndpointTest`: the rule is one.
    static let paths: [(String, String)] = [
        ("/", "/"), ("", "/"), ("/v1", "/v1"), ("/v1/users", "/v1/users"), ("/v1/users/", "/v1/users"),
        // Numbers.
        ("/v1/users/42", "/v1/users/{id}"), ("/v1/users/42?full=1", "/v1/users/{id}"), ("/users/42/posts/7", "/users/{id}/posts/{id}"),
        ("/items/-1", "/items/{id}"), ("/items/12,15,18", "/items/{id}"), ("/events/2026-11-23", "/events/{id}"),
        ("/geo/55.7558,37.6173", "/geo/{id}"), ("/call/+79161234567", "/call/{id}"), ("/items/42;v=2", "/items/{id}"),
        // Versions are not somebody's number.
        ("/2/tweets/1460323737035677698", "/2/tweets/{id}"), ("/api/3/movie/popular", "/api/3/movie/popular"), ("/api/2.0/list", "/api/2.0/list"),
        ("/v2.1/users/5", "/v2.1/users/{id}"), ("/api/10", "/api/{id}"), ("/rest/v1.2.3/a", "/rest/v1.2.3/a"),
        // UUIDs, hex.
        ("/o/550e8400-e29b-41d4-a716-446655440000", "/o/{id}"), ("/o/550E8400-E29B-41D4-A716-446655440000/items", "/o/{id}/items"),
        ("/doc/507f1f77bcf86cd799439011", "/doc/{id}"), ("/blob/0x9f86d081884c7d65", "/blob/{id}"), ("/commit/9f86d08", "/commit/9f86d08"),
        ("/feedface", "/feedface"), ("/accede", "/accede"),
        // Tokens, and words that only look long.
        ("/t/dGhpcyBpcyBhIHRlc3Q5OTg3", "/t/{id}"), ("/s/3kTMd8fJ2nQpX7vL9wZb", "/s/{id}"), ("/verify/" + TrafficSamples.jwt, "/verify/{id}"),
        ("/how-to-build-a-house-in-2024", "/how-to-build-a-house-in-2024"), ("/getUserByIdAndNameV2Async", "/getUserByIdAndNameV2Async"),
        ("/Nexus6P-Android8-API26-build", "/Nexus6P-Android8-API26-build"), ("/iphone-15-pro-256gb-black", "/iphone-15-pro-256gb-black"),
        ("/internationalization", "/internationalization"), ("/com.example.application.release", "/com.example.application.release"),
        // A word and what changes; files.
        ("/user-550e8400-e29b-41d4-a716-446655440000", "/user-{id}"), ("/order_100482", "/order_{id}"), ("/python-3", "/python-3"),
        ("/img/12345.jpg", "/img/{id}.jpg"), ("/img/9f86d081884c7d659a2f.png", "/img/{id}.png"), ("/js/app-9f86d081884c.js", "/js/app-{id}.js"),
        ("/js/jquery-3.7.1.min.js", "/js/jquery-3.7.1.min.js"), ("/index.html", "/index.html"),
        // What must not be shown.
        ("/u/ivan.petrov@example.com/mail", "/u/{id}/mail"), ("/u/ivan%40example.com", "/u/{id}"),
        ("/bot123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw/getMe", "/{id}/getMe"),
        // Names in other alphabets stay readable.
        ("/%D0%BD%D0%BE%D0%B2%D0%BE%D1%81%D1%82%D0%B8/15", "/новости/{id}"), ("/a%2Fb/1", "/a%2Fb/{id}"),
    ]

    @Test(arguments: paths) func pathsAreTemplated(path: String, expected: String) {
        #expect(Endpoint.template(path) == expected)
    }

    @Test func theServerIsTheOneTheRequestNames() {
        func key(_ exchange: HttpExchange, _ flow: TrafficFlow) -> String { Endpoint.of(flow, exchange).key }
        let flow = TrafficSamples.flow()
        #expect(key(TrafficSamples.exchange("get", "/a/1", host: "API.Example.com:443"), flow) == "GET api.example.com/a/{id}")
        #expect(key(TrafficSamples.exchange("GET", "/a", host: "example.com:8443"), flow) == "GET example.com:8443/a")
        // One HTTP/2 connection carries several names.
        #expect(key(TrafficSamples.exchange("GET", "/a", host: "cdn.example.com", version: "HTTP/2"), flow) == "GET cdn.example.com/a")
        #expect(key(TrafficSamples.exchange("GET", "/a", host: nil), flow) == "GET api.example.com/a")
        #expect(key(TrafficSamples.exchange("GET", "/a", host: nil), TrafficSamples.flow(host: nil)) == "GET 93.184.216.34/a")
        #expect(key(TrafficSamples.exchange("GET", "http://proxy.example.com/b/7?x=1", host: "other.example"), flow) == "GET proxy.example.com/b/{id}")
        #expect(key(TrafficSamples.exchange("CONNECT", "example.com:443", host: nil), flow) == "CONNECT example.com")
        #expect(key(TrafficSamples.exchange("GET", "/", host: "[::1]:80"), flow) == "GET [::1]/")
        // The query never takes part.
        #expect(key(TrafficSamples.exchange("GET", "/a?token=abc"), flow) == key(TrafficSamples.exchange("GET", "/a?token=xyz"), flow))
        #expect(key(TrafficSamples.exchange("POST", "/a"), flow) != key(TrafficSamples.exchange("GET", "/a"), flow))
    }

    @Test func aGroupCountsErrorsTimesAndSize() throws {
        let flow = TrafficSamples.flow(
            TrafficSamples.exchange("GET", "/u/1", status: 200, at: 1000, took: 100),
            TrafficSamples.exchange("GET", "/u/2", status: 500, at: 2000, took: 300),
            TrafficSamples.exchange("GET", "/u/3", status: 200, at: 3000, took: 900),
            TrafficSamples.exchange("GET", "/u/4", status: nil, at: 4000, took: nil),
            TrafficSamples.exchange("POST", "/u", status: 200, at: 500, took: 50)
        )
        let groups = EndpointGroup.build([flow])
        #expect(groups.map(\.id) == ["GET api.example.com/u/{id}", "POST api.example.com/u"])
        let group = try #require(groups.first)
        #expect(group.hits.map(\.index) == [3, 2, 1, 0], "newest first")
        #expect(group.errors == 2, "a 500, and no answer on a closed connection")
        #expect(group.typicalMs == 300 && group.worstMs == 900)
        #expect(group.bytes == 4 * 2500)
        #expect(group.lastAt == 4000 && group.firstAt == 1000)
        #expect(group.statuses.map { "\($0.status)x\($0.count)" } == ["200x2", "0x1", "500x1"])
        #expect(EndpointGroup.find(group.id, in: [flow])?.hits.count == 4)
        #expect(EndpointGroup.find("GET nowhere/", in: [flow]) == nil)
    }

    @Test func theFilterWorksInsideGroups() throws {
        let flow = TrafficSamples.flow(
            TrafficSamples.exchange("GET", "/u/1", status: 200), TrafficSamples.exchange("GET", "/u/2", status: 404),
            TrafficSamples.exchange("GET", "/ok", status: 200), TrafficSamples.exchange("POST", "/u", status: 201)
        )
        let failed = EndpointGroup.build([flow], filter: RequestFilter(failedOnly: true))
        let group = try #require(failed.first)
        #expect(failed.count == 1 && group.shown.count == 1 && group.hits.count == 2, "the numbers stay the endpoint's own")
        #expect(group.shown.first?.exchange.status == 404)
        #expect(EndpointGroup.build([flow], filter: RequestFilter(methods: ["POST"])).map(\.id) == ["POST api.example.com/u"])
        #expect(EndpointGroup.build([flow], filter: RequestFilter(query: "/u/2")).map(\.shown.count) == [1])
        #expect(EndpointGroup.build([flow], filter: RequestFilter(query: "nothing")).isEmpty)
    }

    @Test func aWebSocketIsNotSlowAndAStreamOnHTTP2IsStillARequest() throws {
        let messages = [WsMessage(fromClient: true, opcode: 1, at: 1, size: 5, preview: "hello", stored: false)]
        let socket = TrafficSamples.flow(id: 1, TrafficSamples.exchange("GET", "/ws", status: 101, took: 600_000, messages: messages))
        let group = try #require(EndpointGroup.build([socket]).first)
        #expect(group.webSocket && group.typicalMs == nil && group.worstMs == nil)
        let events = TrafficSamples.flow(id: 2, TrafficSamples.exchange("GET", "/events", response: [("Content-Type", "text/event-stream")], took: 90_000))
        #expect(EndpointGroup.build([events]).first?.worstMs == nil)
        // Many requests in one connection, and a stream whose headers have not come yet (an empty method).
        var h2 = TrafficSamples.flow(id: 3, closed: false)
        h2.exchanges = (1...50).map { TrafficSamples.exchange("GET", "/p/\($0)", at: Int64($0), version: "HTTP/2") }
        h2.exchanges.append(HttpExchange(method: "", path: "", query: [], requestHeaders: [], parameters: [], status: nil, responseHeaders: []))
        let groups = EndpointGroup.build([h2])
        #expect(groups.count == 1 && groups[0].hits.count == 50 && groups[0].http2 && groups[0].errors == 0)
    }

    @Test func aLargeRecordingIsGroupedQuickly() {
        let flows = (0..<200).map { id in
            var flow = TrafficSamples.flow(id: Int64(id))
            flow.exchanges = (0..<50).map { TrafficSamples.exchange("GET", "/v1/users/\(id * 50 + $0)/posts", at: Int64($0)) }
            return flow
        }
        _ = EndpointGroup.build(flows)
        let started = ContinuousClock.now
        let groups = EndpointGroup.build(flows)
        #expect(groups.count == 1 && groups[0].hits.count == 10_000)
        // Generous: a live list asks for this two or three times a second, off the main thread.
        #expect(ContinuousClock.now - started < .seconds(5))
    }
}
