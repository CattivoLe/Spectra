import Foundation
import Testing
@testable import Spectra

struct TrafficSecretsTests {
    @Test func fieldsAreKnownByTheirNames() {
        let cases: [(String, TrafficSecrets.Kind?)] = [
            ("password", .password), ("user_password", .password), ("pwd", .password), ("otp", .password),
            ("access_token", .token), ("X-Auth-Token", .token), ("refreshToken", .token), ("jwt", .token),
            ("api_key", .key), ("X-Api-Key", .key), ("client_secret", .key), ("sid", .session), ("JSESSIONID", .session),
            ("cardNumber", .card),
            // Called tokens, open nothing.
            ("csrf_token", nil), ("X-CSRF-Token", nil), ("nextPageToken", nil), ("deviceToken", nil), ("continuation_token", nil),
            ("page", nil), ("passage", nil), ("monkey", nil), ("q", nil), ("", nil),
        ]
        for (name, kind) in cases { #expect(TrafficSecrets.kind(ofName: name) == kind, "\(name)") }
    }

    @Test func valuesAreKnownByTheirLook() {
        #expect(TrafficSecrets.kind(ofValue: "x " + TrafficSamples.jwt) == .token)
        #expect(TrafficSecrets.kind(ofValue: "AIza" + String(repeating: "a", count: 35)) == .key)
        #expect(TrafficSecrets.kind(ofValue: "AKIAIOSFODNN7EXAMPLE") == .key)
        #expect(TrafficSecrets.kind(ofValue: "-----BEGIN RSA PRIVATE KEY-----") == .privateKey)
        #expect(TrafficSecrets.kind(ofValue: "just a sentence about a token") == nil)
        #expect(TrafficSecrets.isEmail("a.b@example.com") && !TrafficSecrets.isEmail("a@b") && !TrafficSecrets.isEmail("@example.com"))
    }

    @Test func flagsAndBlanksAreNotSecrets() {
        #expect(!TrafficSecrets.plausible("true", as: .password))
        #expect(!TrafficSecrets.plausible("", as: .token))
        #expect(!TrafficSecrets.plausible("null", as: .key))
        #expect(TrafficSecrets.plausible("hunter22", as: .password))
        #expect(TrafficSecrets.plausible("4111 1111 1111 1111", as: .card) && !TrafficSecrets.plausible("4111111111111112", as: .card))
    }

    @Test func everyPlaceOfARequestIsSearched() {
        let exchange = TrafficSamples.exchange(
            "POST", "/login/\(TrafficSamples.jwt)?access_token=abcdef123456&mail=ivan@example.com&page=2",
            request: [("Authorization", "Basic dXNlcjpwYXNz"), ("Cookie", "sid=1"), ("X-Api-Key", "0123456789"),
                      ("Referer", "https://x.example/?token=0123456789"), (":path", "/login?access_token=abcdef123456")],
            response: [("Location", "https://y.example/?session_id=0123456789")], parameters: [("password", "hunter22"), ("remember", "true")]
        )
        let found = Set(TrafficSecrets.inExchange(exchange))
        #expect(found == [
            .init(kind: .token, place: .path), .init(kind: .token, place: .query("access_token")), .init(kind: .email, place: .query("mail")),
            .init(kind: .password, place: .requestHeader("Authorization")), .init(kind: .session, place: .requestHeader("Cookie")),
            .init(kind: .key, place: .requestHeader("X-Api-Key")), .init(kind: .token, place: .requestHeader("Referer")),
            .init(kind: .token, place: .responseHeader("Location")), .init(kind: .password, place: .requestBody("password")),
        ])
    }

    @Test func bodiesAreSearchedByFieldAndByLook() {
        let json = Data(#"{"user":{"password":"hunter22","name":"x"},"list":[{"api_key":"0123456789"}],"csrf_token":"0123456789"}"#.utf8)
        #expect(Set(TrafficSecrets.inBody(json, contentType: "application/json", request: true)) == [
            .init(kind: .password, place: .requestBody("password")), .init(kind: .key, place: .requestBody("api_key")),
        ])
        #expect(TrafficSecrets.inBody(Data("a=1&pwd=hunter22".utf8), contentType: "application/x-www-form-urlencoded", request: true)
            == [.init(kind: .password, place: .requestBody("pwd"))])
        #expect(TrafficSecrets.inBody(Data("key: -----BEGIN PRIVATE KEY-----".utf8), contentType: "text/plain", request: false)
            == [.init(kind: .privateKey, place: .responseBody(""))])
        #expect(TrafficSecrets.inBody(json, contentType: "image/png", request: false).isEmpty)
        #expect(TrafficSecrets.inBody(Data([0xff, 0xfe, 0x00]), contentType: nil, request: false).isEmpty)
    }

    @Test func aNameThatIsASecretItselfIsNotShown() {
        #expect(TrafficSecrets.safeName(TrafficSamples.jwt) == "{id}")
        #expect(TrafficSecrets.safeName("550e8400-e29b-41d4-a716-446655440000") == "{id}")
        #expect(TrafficSecrets.safeName("ivan@example.com") == "{id}")
        #expect(TrafficSecrets.safeName("<script>") == "‹script›")
        let exchange = TrafficSamples.exchange("GET", "/a?\(TrafficSamples.jwt)")
        #expect(TrafficSecrets.inExchange(exchange) == [.init(kind: .token, place: .query("{id}"))])
    }
}

struct TrackerHostsTests {
    @Test func aServiceCoversItsSubdomainsOnly() {
        #expect(TrackerHosts.service(of: "app-measurement.com")?.name == "Google Analytics for Firebase")
        #expect(TrackerHosts.service(of: "region1.app-measurement.com:443")?.kind == .analytics)
        #expect(TrackerHosts.service(of: "o123.ingest.sentry.io")?.kind == .crash)
        #expect(TrackerHosts.service(of: "notsentry.io") == nil)
        #expect(TrackerHosts.service(of: "example.com") == nil)
        #expect(TrackerHosts.service(of: "localhost") == nil)
    }
}

struct TrafficAuditTests {
    private func hits(_ flows: TrafficFlow...) -> [RequestHit] { RequestHit.all(flows) }
    private func issues(_ hits: [RequestHit], bodies: TrafficAudit.Bodies? = nil) -> [TrafficIssue] { TrafficAudit.issues(TrafficAudit.scan(hits, bodies: bodies)) }
    private func kinds(_ flow: TrafficFlow, bodies: TrafficAudit.Bodies? = nil) -> [TrafficIssue.Kind: CardRisk] {
        Dictionary(uniqueKeysWithValues: issues(RequestHit.all([flow]), bodies: bodies).map { ($0.kind, $0.risk) })
    }

    @Test func aLargeRecordingIsSearchedInReasonableTime() {
        let headers = (0..<15).map { ("X-Header-\($0)", "an ordinary value of some forty characters, \($0)") }
        let exchanges = (0..<5000).map { TrafficSamples.exchange("GET", "/v1/users/\($0)?page=2&sort=name", request: headers, response: headers) }
        var flow = TrafficSamples.flow()
        flow.exchanges = exchanges
        let started = ContinuousClock.now
        let found = TrafficAudit.scan(RequestHit.all([flow])).flatMap { $0.secrets }
        #expect(found.isEmpty)
        // Generous, for a debug build: it runs once per card, off the main thread.
        #expect(ContinuousClock.now - started < .seconds(30))
    }

    @Test func anOrdinaryRequestHasNoIssues() {
        let exchange = TrafficSamples.exchange("POST", "/v1/me?page=2", request: [("Authorization", "Bearer 0123456789"), ("Cookie", "sid=1")],
                                               response: [("Content-Type", "application/json"), ("Cache-Control", "no-store"), ("Set-Cookie", "sid=2; Secure; HttpOnly; SameSite=Lax")])
        #expect(issues(hits(TrafficSamples.flow(exchange))).isEmpty, "signing in over TLS is how it is done")
    }

    @Test func secretsOverPlainHttpAreCritical() {
        let flow = TrafficSamples.flow(tls: false, TrafficSamples.exchange("POST", "/login", parameters: [("password", "hunter22")]))
        #expect(kinds(flow) == [.secretUnencrypted: .critical])
        #expect(kinds(TrafficSamples.flow(tls: false, TrafficSamples.exchange("GET", "/news"))) == [.cleartext: .warning])
        #expect(kinds(TrafficSamples.flow(tls: false, TrafficSamples.exchange("GET", "/generate_204", host: "connectivitycheck.gstatic.com"))) == [.cleartext: .info])
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange("GET", "/a?api_key=0123456789"))) == [.secretInAddress: .warning])
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange("GET", "/a?to=ivan@example.com"))) == [.personal: .info])
    }

    @Test func aPrivateKeyInAnAnswerIsFound() {
        var exchange = TrafficSamples.exchange("GET", "/config", response: [("Content-Type", "text/plain")])
        exchange.responseBodyStored = true
        let found = kinds(TrafficSamples.flow(exchange)) { _, request in request ? nil : Data("-----BEGIN EC PRIVATE KEY-----\nabc".utf8) }
        #expect(found[.privateKey] == .warning)
    }

    @Test func errorsAreCountedPerEndpoint() throws {
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange(status: 503))) == [.serverError: .warning])
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange(status: 401))) == [.clientError: .warning])
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange(status: nil, took: nil))) == [.noAnswer: .warning])
        #expect(kinds(TrafficSamples.flow(closed: false, TrafficSamples.exchange(status: nil, took: nil))).isEmpty, "still under way")
        var flow = TrafficSamples.flow()
        flow.exchanges = (0..<8).map { TrafficSamples.exchange("POST", "/a", status: 200, at: Int64($0) * 100_000) } + [TrafficSamples.exchange("POST", "/a", status: 429), TrafficSamples.exchange("POST", "/a", status: 429)]
        let issue = try #require(issues(RequestHit.all([flow])).first)
        #expect(issue.kind == .clientError && issue.risk == .warning)
        #expect(issue.text == String(localized: "Errors: \(2) of \(10) requests (\(20)%): \("429 ×2")."))
    }

    @Test func slownessIsJudgedByTheUsualTime() {
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange("POST", "/a", took: 2500, wait: 2400))) == [.slow: .warning])
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange("POST", "/a", took: 1500))).isEmpty)
        let usual = TrafficSamples.flow(TrafficSamples.exchange("POST", "/a", took: 100), TrafficSamples.exchange("POST", "/a", took: 120), TrafficSamples.exchange("POST", "/a", took: 9000))
        #expect(kinds(usual) == [.slow: .info])
        let always = TrafficSamples.flow(TrafficSamples.exchange("POST", "/a", took: 1100), TrafficSamples.exchange("POST", "/a", took: 1200))
        #expect(kinds(always) == [.slow: .warning])
        let socket = TrafficSamples.flow(TrafficSamples.exchange("GET", "/ws", status: 101, took: 900_000))
        #expect(kinds(socket).isEmpty)
    }

    @Test func sizeCompressionAndCachingAreTheServersDoing() {
        let json = [("Content-Type", "application/json; charset=utf-8"), ("ETag", "1")]
        let asks = [("Accept-Encoding", "gzip, br")]
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange("POST", "/a", request: asks, response: json, size: 3_000_000))) == [.large: .warning, .uncompressed: .info])
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange("POST", "/a", response: json, size: 200_000))).isEmpty, "the app did not ask for compression")
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange("POST", "/a", request: asks, response: json + [("Content-Encoding", "br")], size: 200_000))).isEmpty)
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange("GET", "/a.png", response: [("Content-Type", "image/png")], size: 2_000_000))) == [.large: .info, .uncached: .info])
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange("GET", "/v.mp4", response: [("Content-Type", "video/mp4")], size: 90_000_000))).isEmpty)
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange("GET", "/me", response: [("Cache-Control", "public, max-age=60"), ("Set-Cookie", "a=1; Secure")]))) == [.sharedCache: .warning])
        // The phone's own answer says nothing about the server.
        let mapped = TrafficSamples.exchange("GET", "/a.css", request: asks, response: [("Content-Type", "text/css"), (MapLocal.header, "x")], took: 9000, size: 3_000_000)
        #expect(kinds(TrafficSamples.flow(mapped)) == [.mapped: .info])
    }

    @Test func needlessRepeatsAreCountedInAWindow() {
        func flow(_ times: [Int64], _ method: String = "GET") -> TrafficFlow {
            var flow = TrafficSamples.flow()
            flow.exchanges = times.map { TrafficSamples.exchange(method, "/a", response: [("ETag", "1")], at: $0) }
            return flow
        }
        #expect(kinds(flow([0, 1000, 2000])) == [.repeated: .info])
        #expect(kinds(flow([0, 1000, 2000, 3000, 4000])) == [.repeated: .warning])
        #expect(kinds(flow([0, 20_000, 40_000])).isEmpty, "polling twice a minute is no storm")
        #expect(kinds(flow([0, 1000, 2000], "POST")).isEmpty, "two POSTs may carry different bodies")
        #expect(TrafficAudit.repeats(RequestHit.all([flow([0, 1000, 60_000])])) == (3, 2))
    }

    @Test func cookieFlagsMatterMostForTheOnesThatSignIn() {
        func cookies(_ value: String) -> [TrafficAudit.WeakCookie] {
            TrafficAudit.weakCookies(RequestHit(flow: TrafficSamples.flow(TrafficSamples.exchange(response: [("Set-Cookie", value)])), index: 0))
        }
        #expect(cookies("sid=abc; Path=/") == [.init(name: "sid", missing: ["Secure", "HttpOnly"], signsIn: true)])
        #expect(cookies("theme=dark") == [.init(name: "theme", missing: ["Secure"], signsIn: false)])
        #expect(cookies("theme=dark; secure").isEmpty)
        #expect(cookies("csrftoken=abc; Secure").isEmpty, "a page's script has to read it")
        #expect(cookies("sid=; Max-Age=0").isEmpty && cookies("sid=x; expires=Thu, 01 Jan 1970 00:00:00 GMT").isEmpty, "that deletes it")
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange(response: [("Set-Cookie", "sid=abc")]))) == [.cookie: .warning])
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange(response: [("Set-Cookie", "theme=dark")]))) == [.cookie: .info])
    }

    @Test func trackersAndDowngradesAreNamed() throws {
        let issue = try #require(issues(hits(TrafficSamples.flow(host: nil, TrafficSamples.exchange("POST", "/a", host: "region1.app-measurement.com")))).first)
        #expect(issue.kind == .tracker && issue.risk == .info && issue.text.contains("Google Analytics for Firebase"))
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange(status: 301, response: [("Location", "http://example.com/")]))) == [.downgrade: .warning])
        #expect(kinds(TrafficSamples.flow(TrafficSamples.exchange(status: 301, response: [("Location", "https://example.com/")]))).isEmpty)
    }
}

@MainActor
struct TrafficCardsTests {
    /// Every value a request below carries that must not get out of it.
    nonisolated static let values = ["hunter22", "s3cr3tvalue", TrafficSamples.jwt, "ivan@example.com", "dXNlcjpwYXNz", "cookie-value", "body-value", "4111111111111111"]

    nonisolated static func leaky(tls: Bool) -> TrafficFlow {
        var exchange = TrafficSamples.exchange(
            "POST", "/u/ivan@example.com/\(TrafficSamples.jwt)?access_token=s3cr3tvalue&\(TrafficSamples.jwt)=1&mail=ivan@example.com",
            status: 500, request: [("Authorization", "Basic dXNlcjpwYXNz"), ("Cookie", "sid=cookie-value"), ("User-Agent", "Shop/1.2 (ivan@example.com)")],
            response: [("Set-Cookie", "sid=cookie-value"), ("Content-Type", "application/json")],
            parameters: [("password", "hunter22"), ("cardnumber", "4111111111111111")], took: 3000
        )
        exchange.requestBodyStored = true
        exchange.responseBodyStored = true
        return TrafficSamples.flow(tls: tls, exchange)
    }

    private func context(_ flows: [TrafficFlow], session: String? = nil, recording: Bool = false) -> TrafficCards.Context {
        TrafficCards.Context(session: session, flows: flows, bodies: { _, _ in Data(#"{"password":"body-value","api_key":"body-value"}"#.utf8) },
                             recording: recording)
    }

    /// Everything a card keeps, shows or hands the model.
    private func everything(_ facts: CardFacts) -> String {
        let prompts = CardAction.allCases.map { CardPrompts.user(facts, action: $0) }
        return ([facts.title, CardSubjects.encode(facts.subject), facts.data, facts.deepData, AssistantCards.render(title: facts.title, findings: facts.findings)]
            + facts.steps.flatMap { [$0.call, $0.label] } + prompts).joined(separator: "\n")
    }

    @Test(arguments: [true, false]) func noValueLeavesTheRequest(tls: Bool) throws {
        let flow = Self.leaky(tls: tls)
        let key = Endpoint.of(flow, flow.exchanges[0]).key
        let cards = [TrafficCards.request(flow, 0, context([flow])), TrafficCards.endpoint(key, context([flow])), TrafficCards.connection(flow, context([flow])),
                     AssistantCards.traffic([flow], name: "x", digest: TrafficDigest.text([flow], name: "x", origin: "y"))]
        for facts in cards {
            let facts = try #require(facts)
            let text = everything(facts)
            for value in Self.values { #expect(!text.contains(value), "\(value) in the \(facts.subject.kind) card") }
            #expect(facts.findings.contains { $0.risk == (tls ? .warning : .critical) })
        }
        #expect(key == "POST api.example.com/u/{id}/{id}")
    }

    @Test func nothingOfRecordedTrafficIsSearchedOnTheWeb() {
        let sources = AssistantSources(
            store: NetworkStore(), lan: LANScanner(), ble: BLEScanner(), history: HistoryStore(),
            link: SensorLink(store: NetworkStore(), history: HistoryStore()), probe: LinkProbe(), joiner: WiFiJoiner(), speed: SpeedTester(),
            captures: CaptureShelf()
        )
        let ref = TrafficRef(session: nil, flow: Self.leaky(tls: true), index: 0)
        for subject in [CardSubject.traffic, .request(ref), .connection(ref), .endpoint(session: nil, key: "GET example.com/")] {
            for action in CardAction.allCases { #expect(CardWeb.lookup(subject, action: action, sources: sources) == nil) }
        }
    }

    @Test func aRequestsCardSaysWhatItIsAndOffersWhatHelps() throws {
        let failing = TrafficSamples.flow(TrafficSamples.exchange("GET", "/v1/users/7", status: 500))
        let facts = try #require(TrafficCards.request(failing, 0, context([failing])))
        #expect(facts.title == "GET api.example.com/v1/users/{id}")
        #expect(facts.findings.first?.risk == .warning)
        #expect(facts.steps.map(\.tool) == ["repeat_request", "open_repeat", "open_map_local"])
        #expect(facts.steps[0].text.flatMap(TrafficRef.init) == TrafficRef(session: nil, flow: failing, index: 0))
        #expect(facts.data.contains("Answer: status 500 (an error)"))
        #expect(facts.data.contains("URL parameters: none") && facts.data.contains("Secrets: none found"))
        #expect(facts.data.contains("Cookies set with missing protection: none"))
        #expect(!facts.awaitsCheck)
        // A request that changes something is never sent by a button alone.
        let post = TrafficSamples.flow(TrafficSamples.exchange("POST", "/v1/pay", status: 500))
        #expect(try #require(TrafficCards.request(post, 0, context([post]))).steps.map(\.tool) == ["open_repeat", "open_map_local"])
        // Nor is plain HTTP, which the system does not let the app send, nor anything through the recorder.
        let plain = TrafficSamples.flow(tls: false, TrafficSamples.exchange("GET", "/v1/users/7", status: 500))
        #expect(try #require(TrafficCards.request(plain, 0, context([plain]))).steps.map(\.tool) == ["open_repeat", "check_site"])
        #expect(try #require(TrafficCards.request(failing, 0, context([failing], recording: true))).steps.map(\.tool) == ["open_repeat", "open_map_local"])
        // All in order: the site check, and nothing that would go through the recorder while it is on.
        let fine = TrafficSamples.flow(TrafficSamples.exchange("GET", "/"))
        let good = try #require(TrafficCards.request(fine, 0, context([fine])))
        #expect(good.findings.last?.risk == .ok && !good.findings.contains { $0.risk == .warning })
        #expect(good.steps.map(\.tool) == ["open_repeat", "check_site"])
        #expect(try #require(TrafficCards.request(fine, 0, context([fine], recording: true))).steps.map(\.tool) == ["open_repeat"])
        #expect(TrafficCards.request(fine, 9, context([fine])) == nil)
    }

    @Test func theSiteCheckIsOnlyForPublicNames() throws {
        for host in ["192.168.1.1", "router.local", "localhost", "example.com:8443"] {
            let flow = TrafficSamples.flow(host: host, TrafficSamples.exchange("GET", "/", host: host))
            #expect(try #require(TrafficCards.request(flow, 0, context([flow]))).steps.map(\.tool) == ["open_repeat"], "\(host)")
        }
    }

    @Test func anEndpointsCardCountsItsRequests() throws {
        var flow = TrafficSamples.flow(refused: false)
        flow.exchanges = (0..<9).map { TrafficSamples.exchange("GET", "/v1/users/\($0)", at: Int64($0) * 1000) } + [TrafficSamples.exchange("GET", "/v1/users/9", status: 404, at: 9000)]
        let other = TrafficSamples.flow(id: 2, TrafficSamples.exchange("GET", "/v1/ads"))
        let facts = try #require(TrafficCards.endpoint("GET api.example.com/v1/users/{id}", context([flow, other], session: "S")))
        #expect(facts.findings.first?.text == String(localized: "Errors: \(1) of \(10) requests (\(10)%): \("404 ×1")."))
        #expect(facts.subject == .endpoint(session: "S", key: "GET api.example.com/v1/users/{id}"))
        // The button repeats the one that failed.
        #expect(facts.steps[0].text.flatMap(TrafficRef.init) == TrafficRef(session: "S", flow: flow, index: 9))
        #expect(facts.data.contains("Answers by status: 200 ×9, 404 ×1"))
        #expect(!facts.data.contains("/v1/ads") && facts.deepData.contains("GET api.example.com/v1/ads ×1"))
        #expect(TrafficCards.endpoint("GET nowhere/", context([flow])) == nil)
        // Every request at once: the numbers stay within the model's budget.
        var big = TrafficSamples.flow()
        big.exchanges = (0..<400).map { TrafficSamples.exchange("GET", "/v1/users/\($0)?p\($0)=1", response: [("Set-Cookie", "session\($0)=1")]) }
        let large = try #require(TrafficCards.endpoint("GET api.example.com/v1/users/{id}", context([big])))
        #expect(large.data.count <= TrafficCards.budget + 10 && large.findings.count <= AssistantCards.maxFindings)
    }

    @Test func aConnectionsCardFollowsWhatWasRead() {
        let refused = TrafficSamples.flow(host: "bank.example", refused: true)
        let facts = TrafficCards.connection(refused, context([refused]))
        #expect(facts.steps.map(\.tool) == ["open_tls_hosts", "check_site"])
        #expect(!facts.findings.contains { $0.risk < .info }, "refusing is the app protecting itself")
        #expect(facts.data.contains("refused Spectra's certificate") && facts.data.contains("Requests read in it: none"))
        let multiplexed = TrafficSamples.flow(
            TrafficSamples.exchange("GET", "/a/1", version: "HTTP/2"), TrafficSamples.exchange("GET", "/a/2", version: "HTTP/2"), TrafficSamples.exchange("GET", "/b", host: "cdn.example.com", version: "HTTP/2"))
        let read = TrafficCards.connection(multiplexed, context([multiplexed]))
        #expect(read.findings.contains { $0.text == String(localized: "Requests in it: \(3), to \(2) endpoints.") })
        #expect(read.data.contains("GET api.example.com/a/{id} ×2"))
        let dns = TrafficFlow(id: 4, proto: "UDP", source: "a", sourcePort: 1, destination: "1.1.1.1", destinationPort: 53, startedAt: 0, lastAt: 0)
        #expect(TrafficCards.connection(dns, context([dns])).findings.first?.risk == .info)
    }

    @Test func aCardFindsItsRequestAgainAfterTheRecordingWasSaved() {
        let flow = TrafficSamples.flow(id: 7, TrafficSamples.exchange())
        var other = TrafficSamples.flow(id: 7, TrafficSamples.exchange("GET", "/other"))
        other = TrafficFlow(id: 7, proto: "TCP", source: other.source, sourcePort: 1, destination: other.destination, destinationPort: 443, startedAt: 99, lastAt: 99, exchanges: other.exchanges)
        let ref = TrafficRef(session: nil, flow: flow, index: 0)
        #expect(TrafficRef(ref.text) == ref)
        var library = TrafficLibrary()
        library.live = { [flow] }
        #expect(library.find(ref)?.session == nil && library.find(ref)?.flow.id == 7)
        // A new recording has a connection with the same number: not the one the card is about.
        library.live = { [other] }
        #expect(library.find(ref) == nil)
        library.saved = { [TrafficSession(id: "S", startedAt: 0, stoppedAt: 1, flows: [flow])] }
        #expect(library.find(ref)?.session == "S")
    }

    @Test func subjectsReadBackTheSame() {
        let ref = TrafficRef(session: "0A1B", flow: TrafficSamples.flow(id: 3), index: 5)
        for subject in [CardSubject.request(ref), .connection(TrafficRef(session: nil, flow: TrafficSamples.flow(id: 3))),
                        .endpoint(session: nil, key: "GET [::1]:8080/a|b/{id}"), .endpoint(session: "0A1B", key: "POST example.com/")] {
            #expect(CardSubjects.decode(CardSubjects.encode(subject)) == subject)
        }
        for broken in ["request:", "request:|1|2|", "request:x|y|z|0", "endpoint:", "endpoint:|", "connection:1"] {
            #expect(CardSubjects.decode(CardSubjects.prefix + broken) == nil, "\(broken)")
        }
    }

    @Test func preparingACardLeavesItReady() async throws {
        let shelf = CaptureShelf()
        let flow = Self.leaky(tls: true)
        shelf.library.live = { [flow] }
        let sources = AssistantSources(
            store: NetworkStore(), lan: LANScanner(), ble: BLEScanner(), history: HistoryStore(),
            link: SensorLink(store: NetworkStore(), history: HistoryStore()), probe: LinkProbe(), joiner: WiFiJoiner(), speed: SpeedTester(), captures: shelf
        )
        let subject = CardSubject.request(TrafficRef(session: nil, flow: flow, index: 0))
        #expect(sources.cardFacts(subject)?.title == "POST api.example.com/u/{id}/{id}", "without it, from the recording alone")
        await sources.prepare(subject)
        #expect(shelf.prepared(subject) == sources.cardFacts(subject))
        #expect(sources.cardFacts(.endpoint(session: "gone", key: "GET x/")) == nil)
    }

    @Test func theRepeatedRequestIsComparedByNumbers() {
        let exchange = TrafficSamples.exchange("GET", "/a?token=s3cr3tvalue", status: 500, took: 3000)
        let again = RepeatResult(status: 200, headers: [HttpField(name: "Set-Cookie", value: "cookie-value")], body: [UInt8]("body-value".utf8), cut: false, durationMs: 90, error: nil)
        let text = TrafficCards.compare(exchange, again)
        #expect(text.contains("Recorded: status 500, 3000 ms") && text.contains("status 200, 90 ms") && text.contains("Status: changed"))
        for value in Self.values { #expect(!text.contains(value)) }
        #expect(TrafficCards.compare(exchange, RepeatResult(status: 401, headers: [], body: [], cut: false, durationMs: 1, error: nil)).contains("expired"))
        #expect(TrafficCards.compare(exchange, RepeatResult(status: nil, headers: [], body: [], cut: false, durationMs: 1, error: "timed out")).hasSuffix("no answer."))
    }

    @Test func theWholeRecordingIsSummedUpByEndpointWithinItsBudget() {
        var flow = TrafficSamples.flow()
        flow.exchanges = (0..<40).map { TrafficSamples.exchange("GET", "/v1/users/\($0)", at: Int64($0) * 60_000) }
            + [TrafficSamples.exchange("POST", "/pay", status: 500), TrafficSamples.exchange("GET", "/slow?access_token=s3cr3tvalue", took: 4000)]
        let many = [flow] + (10..<60).map { TrafficSamples.flow(id: Int64($0), host: "server-\($0).a-rather-long-name.example.com") }
        let text = TrafficDigest.text(many, name: "x", origin: "y")
        #expect(text.contains("Most requested: GET api.example.com/v1/users/{id} ×40"))
        #expect(text.contains("With errors: POST api.example.com/pay 1 of 1."))
        #expect(text.contains("GET api.example.com/slow 4000 ms"))
        #expect(text.contains("access token in the URL parameter `access_token`") && !text.contains("s3cr3tvalue"))
        #expect(text.contains("Known analytics and advertising services contacted: none."))
        #expect(text.count <= TrafficDigest.budget && text.contains("more."), "the list of servers gives way")
        let calm = TrafficDigest.text([TrafficSamples.flow(TrafficSamples.exchange("POST", "/a"))], name: "x", origin: "y")
        #expect(calm.contains("With errors: none.") && calm.contains("Slow (usually a second or more): none."))
        let facts = AssistantCards.traffic(many, name: "x", digest: text)
        let shown = facts.findings.map(\.text)
        #expect(shown.contains(String(localized: "Fails most often: \("POST api.example.com/pay"), errors \(1) of \(1).")))
        #expect(shown.contains { $0.contains("GET api.example.com/slow") } && shown.contains { $0.contains("v1/users/{id}") })
    }
}
