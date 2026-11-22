import Foundation
import LocalChatKit
import Testing
@testable import Spectra

/// The Internet and link summary cards: findings, their order, the step buttons, and what the model is given.
/// Ported from InternetCardsTest.kt; texts compared through `String(localized:)`.
@MainActor
struct InternetCardsTests {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    var healthy: AssistantSnapshot.Link {
        var link = CardDataTests.healthy
        link.checkedAt = now.addingTimeInterval(-300)
        return link
    }

    func snapshot(link: AssistantSnapshot.Link? = nil, sites: [AssistantSnapshot.SiteReach] = [], route: AssistantSnapshot.Route? = nil) -> AssistantSnapshot {
        var s = AssistantSnapshot()
        s.link = link
        s.sites = sites
        s.route = route
        return s
    }

    func calls(_ steps: [CardStep]) -> [String] { steps.map(\.call) }
    func trace(_ host: String) -> String { "{\"tool\": \"trace_route\", \"host\": \"\(host)\"}" }

    func expectRunnable(_ steps: [CardStep]) {
        for step in steps {
            #expect(AssistantActions.Name(rawValue: step.tool) != nil, "\(step.call)")
            #expect(!step.label.isEmpty && !step.label.hasSuffix("?"), "\(step.label)")
        }
    }

    // MARK: - Internet

    @Test func withNothingMeasuredTheCardOnlyOffersChecks() {
        let facts = InternetCards.internet(AssistantSnapshot(), now: now)
        #expect(facts.awaitsCheck)
        #expect(facts.findings.allSatisfy { $0.risk == .info })
        #expect(calls(facts.steps) == ["check_connection", "speed_test", "check_sites"])
        #expect(facts.steps.first?.label == String(localized: "Check the connection"))
        expectRunnable(facts.steps)
        // Through the snapshot as well: the ✨ on any internet screen opens this card.
        #expect(AssistantCards.facts(.internet, snapshot: AssistantSnapshot(), now: now) == facts)
    }

    @Test func aHealthyConnectionReadsGreenAndOffersOnlyRefreshes() {
        let facts = InternetCards.internet(snapshot(link: healthy), now: now)
        #expect(!facts.awaitsCheck)
        #expect(!facts.findings.contains { $0.risk == .critical || $0.risk == .warning })
        #expect(facts.findings.contains { $0.risk == .ok && $0.text == String(localized: "HTTPS certificates are genuine: nothing re-signs them on the way.") })
        #expect(facts.findings.contains { $0.text.contains("203.0.113.7") && $0.text.contains("Example Telecom") })
        // Nothing wrong: no trace and no speed test; the sites were never tried, and the check can be run again.
        #expect(calls(facts.steps) == ["check_sites", "check_connection"])
        #expect(facts.steps.last?.label == String(localized: "Check the connection again"))
        #expect(facts.title == String(localized: "Internet") + " · Example Telecom")
        expectRunnable(facts.steps)
    }

    @Test func interceptionAndOpenPortsComeFirst() {
        var link = healthy
        link.intercepted = true
        link.exposedPorts = [22, 3389]
        link.resolverLies = true
        let facts = InternetCards.internet(snapshot(link: link), now: now)
        #expect(facts.findings[0].risk == .critical)
        #expect(facts.findings[0].text == String(localized: "HTTPS certificates look re-signed on this connection: possible interception."))
        #expect(facts.findings[1].text.contains("22, 3389"))
        #expect(facts.findings.contains { $0.risk == .warning && $0.text == String(localized: "The DNS resolver answers for names that do not exist.") })
        #expect(facts.data.contains("re-signed on the way"))
        #expect(facts.data.contains("reachable from the internet: 22, 3389"))
    }

    @Test func aSiteThatDoesNotOpenGetsATraceToIt() {
        var link = healthy
        link.access = "filtered"
        link.blocked = ["www.youtube.com", "www.instagram.com"]
        let sites = [AssistantSnapshot.SiteReach(host: "youtube.com", ok: false), .init(host: "github.com", ok: true, millis: 90)]
        let facts = InternetCards.internet(snapshot(link: link, sites: sites), now: now)
        let warnings = facts.findings.filter { $0.risk == .warning }.map(\.text)
        #expect(warnings.contains { $0.contains("www.youtube.com, www.instagram.com") })
        #expect(warnings.contains(String(localized: "Your sites that do not open: \("youtube.com").")))
        #expect(facts.findings.contains { $0.risk == .ok && $0.text.contains("github.com") })
        #expect(calls(facts.steps) == [trace("youtube.com"), "check_sites", "check_connection"])
        #expect(facts.steps[0].label == String(localized: "Trace the route to \("youtube.com")"))
        #expect(facts.steps[1].label == String(localized: "Check your sites again"))
        #expect(facts.data.contains("- Do not open: youtube.com"))
        #expect(facts.data.contains("- Open: github.com (90 ms)"))
        expectRunnable(facts.steps)
    }

    @Test func noInternetOffersACheckAgainAndATraceOut() {
        var link = AssistantSnapshot.Link(checkedAt: now.addingTimeInterval(-60))
        link.status = "lan"
        link.gatewayMs = 3
        link.gatewayOk = true
        link.dnsOk = false
        link.webOk = false
        let facts = InternetCards.internet(snapshot(link: link), now: now)
        #expect(facts.findings.first?.risk == .critical)
        #expect(facts.findings.first?.text == String(localized: "The router answers, but the internet does not."))
        #expect(calls(facts.steps) == ["check_connection", trace("1.1.1.1"), "speed_test"])
        #expect(facts.data.contains("Did not answer: DNS, web"))
    }

    @Test func aSlowLineAsksForAnotherSpeedTest() {
        var link = healthy
        link.downMbps = 3.2
        link.upMbps = 0.8
        link.latencyMs = 220
        link.jitterMs = 45
        link.bufferbloat = "F"
        let facts = InternetCards.internet(snapshot(link: link, sites: [.init(host: "ya.ru", ok: true)]), now: now)
        let warnings = facts.findings.filter { $0.risk == .warning }.map(\.text)
        #expect(warnings.contains(String(localized: "Slow download: \(InternetCards.mbps(3.2)) Mbit/s: video and calls may stutter.")))
        #expect(warnings.contains { $0.contains("220") })
        #expect(warnings.contains { $0.contains("45") })
        #expect(warnings.contains { $0.contains("F") })
        // High latency: where it grows is worth a trace; then the speed again.
        #expect(calls(facts.steps) == [trace("1.1.1.1"), "speed_test", "check_connection"])
        #expect(facts.steps[1].label == String(localized: "Measure the speed again"))
        #expect(facts.data.contains("grade F"))
    }

    @Test func aDownloadSampleAloneOffersTheFullSpeedTest() {
        var sample = healthy
        sample.upMbps = nil
        sample.bufferbloat = nil
        sample.jitterMs = nil
        let facts = InternetCards.internet(snapshot(link: sample, sites: [.init(host: "ya.ru", ok: true)]), now: now)
        #expect(calls(facts.steps) == ["speed_test", "check_connection"])
        #expect(facts.steps.first?.label == String(localized: "Measure the speed fully"))
        #expect(facts.findings.contains { $0.text == String(localized: "Speed: \(InternetCards.mbps(92.4)) Mbit/s down.") })
    }

    @Test func anOldCheckIsOfferedAgainFirst() {
        var old = healthy
        old.checkedAt = now.addingTimeInterval(-3 * 3600)
        let facts = InternetCards.internet(snapshot(link: old), now: now)
        #expect(facts.findings.contains { $0.text == String(localized: "Checked \(String(localized: "\(3) h")) ago: the results may be out of date.") })
        #expect(facts.steps.first?.call == "check_connection")
    }

    @Test func onlyASpeedTestStillMakesACard() {
        var speed = AssistantSnapshot.Link(checkedAt: now.addingTimeInterval(-60))
        speed.downMbps = 55
        speed.upMbps = 12
        speed.latencyMs = 25
        let facts = InternetCards.internet(snapshot(link: speed), now: now)
        #expect(!facts.awaitsCheck)
        #expect(facts.findings.contains { $0.text == String(localized: "Speed: \(InternetCards.mbps(55)) Mbit/s down, \(InternetCards.mbps(12)) up.") })
        #expect(facts.findings.contains { $0.text.hasPrefix(String(localized: "The connection has not been checked yet: DNS, the provider, HTTPS interception and blocked services are unknown.")) })
        #expect(facts.steps.first?.call == "check_connection")
        #expect(!calls(facts.steps).contains("speed_test"))
    }

    @Test func aTraceThatStopsIsReadAndOfferedAgain() {
        let route = AssistantSnapshot.Route(target: "example.org", address: "93.184.215.14", hops: [
            .init(address: "192.168.1.1", millis: 3), .init(address: "10.10.0.1", millis: 12),
            .init(address: "198.51.100.9", millis: 130, place: "Frankfurt, DE · Example Transit"),
            .init(address: nil, millis: nil), .init(address: nil, millis: nil),
        ])
        let facts = InternetCards.internet(snapshot(link: healthy, route: route), now: now)
        #expect(facts.findings.contains { $0.text == String(localized: "The trace to \("example.org") stops after \(3) hops: the rest do not answer, more often a firewall than a break.") })
        #expect(facts.findings.contains { $0.text == String(localized: "The delay grows most at hop \(3) (\("198.51.100.9 · Frankfurt, DE · Example Transit")): +\(118) ms.") })
        #expect(calls(facts.steps).contains(trace("example.org")))
        #expect(facts.steps.first { $0.tool == "trace_route" }?.label == String(localized: "Trace \("example.org") again"))
        #expect(facts.data.contains("- Places along the way: Frankfurt, DE · Example Transit"))
        #expect(!facts.data.contains("4. *"))
        #expect(facts.deepData.contains("4. *"))
    }

    @Test func aTraceThatArrivesIsNotOfferedAgain() {
        let route = AssistantSnapshot.Route(target: "1.1.1.1", address: "1.1.1.1",
                                            hops: [.init(address: "192.168.1.1", millis: 2), .init(address: nil, millis: nil), .init(address: "1.1.1.1", millis: 9)])
        let facts = InternetCards.internet(snapshot(link: healthy, route: route), now: now)
        #expect(facts.findings.contains { $0.risk == .ok && $0.text == String(localized: "The path to \("1.1.1.1") takes \(3) hops: it answers.") })
        #expect(!facts.steps.contains { $0.tool == "trace_route" })
    }

    // MARK: - A link

    @Test func aPhishingLinkLeadsWithTheVerdictAndOffersItsChecks() {
        let facts = InternetCards.link("Срочно: https://appleid.apple.com.verify-login.xyz/signin", site: nil, redirects: nil, now: now)
        #expect(facts.subject == .link("https://appleid.apple.com.verify-login.xyz/signin"))
        #expect(facts.title == "verify-login.xyz")
        #expect(facts.findings.first?.risk == .critical)
        #expect(facts.findings.first?.text == String(localized: "Looks like phishing: do not open it and do not enter anything on it."))
        #expect(calls(facts.steps) == [
            #"{"tool": "check_site", "text": "https://appleid.apple.com.verify-login.xyz/signin"}"#,
            #"{"tool": "expand_redirects", "text": "https://appleid.apple.com.verify-login.xyz/signin"}"#,
        ])
        expectRunnable(facts.steps)
        #expect(!facts.awaitsCheck)
        #expect(facts.data.contains("The app's verdict on the address: dangerous"))
        #expect(facts.data.contains("Certificate and domain registry: not checked yet."))
    }

    @Test func aYoungDomainAndARedirectElsewhereAreFindings() {
        let link = "https://shop.example.com/sale"
        let day: TimeInterval = 86_400
        let certificate = CertificateReport(summary: CertificateSummary(subject: "shop.example.com", names: ["shop.example.com"], issuerOrganization: "Let's Encrypt",
                                                                        issuerCommonName: nil, notBefore: now.addingTimeInterval(-2 * day),
                                                                        notAfter: now.addingTimeInterval(88 * day)), trusted: true)
        let site = SiteReport(host: "shop.example.com", certificate: certificate, certificateError: nil,
                              domain: .found(DomainReport(domain: "example.com", registered: now.addingTimeInterval(-6 * day), expires: nil, registrar: "Some Registrar")))
        let redirects = RedirectTrace(hops: [.init(url: URL(string: link)!, status: 302), .init(url: URL(string: "https://cheap-deals.top/x")!, status: 200)],
                                      final: URL(string: "https://cheap-deals.top/x")!, stop: nil)
        let facts = InternetCards.link(link, site: site, redirects: redirects, now: now)
        let texts = facts.findings.map(\.text)
        #expect(facts.findings.first?.risk == .critical)
        #expect(texts.contains(String(localized: "Redirects once and ends at \("cheap-deals.top").")))
        #expect(texts.contains(String(localized: "It leads to another site: \("cheap-deals.top").")))
        // Both checks are in: no more buttons, no "not checked yet".
        #expect(facts.steps.isEmpty)
        #expect(!texts.contains(String(localized: "The certificate and the domain's age have not been checked yet.")))
        #expect(facts.data.contains("registered") && facts.data.contains("6 days ago") && facts.data.contains("Some Registrar"))
        #expect(facts.data.contains("issued 2 days ago"))
        #expect(facts.data.contains("Redirects: 1, followed by their headers only:"))
        #expect(facts.data.contains("2. https://cheap-deals.top/x → HTTP 200"))
        #expect(facts.data.contains("Final address: https://cheap-deals.top/x"))
    }

    @Test func aMailtoOrABareTextHasNoWebChecks() {
        let none = InternetCards.link("привет, как дела", site: nil, redirects: nil, now: now)
        #expect(none.awaitsCheck)
        #expect(none.steps.isEmpty)
        let page = URL(string: "https://www.apple.com/iphone/")!
        let clean = InternetCards.link(page.absoluteString, site: nil, redirects: RedirectTrace(hops: [.init(url: page, status: 200)], final: page, stop: nil), now: now)
        #expect(clean.findings.contains { $0.risk == .ok && $0.text == String(localized: "The address itself shows no warning signs.") })
        #expect(clean.findings.contains { $0.text == String(localized: "No redirects: the link opens where it says.") })
        #expect(clean.steps.map(\.tool) == ["check_site"])
    }

    @Test func theShelfKeepsWhatEitherSideFound() throws {
        let shelf = LinkShelf()
        let text = "Look: https://appleid.apple.com.verify-login.xyz/signin now"
        let link = try #require(LinkInspector.inspect(text)).link
        #expect(LinkInspector.inspect(link)?.link == link)
        #expect(shelf.facts(text, now: now).steps.count == 2)
        shelf.keep(redirects: RedirectTrace(hops: [.init(url: URL(string: link)!, status: 404)], final: URL(string: link)!, stop: nil), for: link)
        #expect(shelf.facts(text, now: now).steps.map(\.tool) == ["check_site"])
        for index in 0..<LinkShelf.keep {
            shelf.keep(site: SiteReport(host: "site\(index).example", certificate: nil, certificateError: "refused", domain: .skipped),
                       for: "https://site\(index).example/")
        }
        #expect(shelf.entry(link).redirects == nil)
        #expect(shelf.entry("https://site7.example/").site != nil)
    }

    @Test func theRegistryIsAskedAboutTheRightName() throws {
        #expect(LinkShelf.registryName(try #require(LinkInspector.inspect("https://appleid.apple.com.verify-login.xyz/"))) == "verify-login.xyz")
        #expect(LinkShelf.registryName(try #require(LinkInspector.inspect("http://192.168.1.1/admin"))) == nil)
        #expect(LinkShelf.start(try #require(LinkInspector.inspect("http://paypa1.com/login")))?.absoluteString == "http://paypa1.com/login")
    }

    // MARK: - Prompts

    @Test func aCheckOnTheInternetCardIsAskedAboutTheCheckAlone() {
        let facts = InternetCards.internet(snapshot(link: healthy), now: now)
        let system = CardPrompts.system(.check, kind: .internet, locale: Locale(identifier: "ru"), tool: "trace_route")
        #expect(system.contains("traced the path"))
        #expect(system.contains("Answer in Russian."))
        #expect(!system.contains("The subject is the user's internet connection"))
        let user = CardPrompts.user(facts, action: .check, check: CardCheck(title: "traceroute", result: "Traceroute to 1.1.1.1: reached in 7 hops", tool: "trace_route"),
                                    earlier: "Интернет работает, всё в порядке.")
        #expect(user.contains("<check name=\"traceroute\">"))
        #expect(user.contains("do not repeat"))
        #expect(!user.contains("Findings of the app"))
        #expect(!user.contains("<data>"))
        #expect(CardPrompts.system(.summary, kind: .link, locale: Locale(identifier: "en")).contains("one link the user may open"))
        for tool in ["speed_test", "check_sites", "check_site", "expand_redirects", "check_connection"] {
            #expect(CardPrompts.system(.check, kind: .internet, tool: tool).contains("This check"), "\(tool)")
        }
    }
}

/// "More detail" with the web: what may go out as a query, and what the model reads back. Ported from CardWebTest.kt.
struct CardWebTests {
    @Test func namesPeopleGaveTheirDevicesAreNotSent() {
        #expect(CardWeb.personal("Alex's AirPods") == nil)
        #expect(CardWeb.personal("Alex’s iPhone") == nil)
        #expect(CardWeb.personal("iPhone Anna") == nil)
        #expect(CardWeb.personal("MacBook (Alex)") == nil)
        #expect(CardWeb.personal("1b067baf05912b622b215c7a") == nil)
        #expect(CardWeb.personal("Momentum 3") == "Momentum 3")
        #expect(CardWeb.personal("Galaxy Buds2 Pro") == "Galaxy Buds2 Pro")
        #expect(CardWeb.personal("iPhone 15 Pro") == "iPhone 15 Pro")
    }

    @Test func aProductNeedsAMakerOrAName() {
        #expect(CardWeb.product(maker: nil, name: " ") == nil)
        #expect(CardWeb.product(maker: "Xiaomi", name: "xiaomi") == CardWeb.Product(maker: "Xiaomi", name: nil))
        // Seen on the user's Samsung TV: the UPnP model carries the DLNA stack's name and version.
        #expect(CardWeb.product(maker: "Samsung", name: "UE55U8092FUXXH AllShare1.0") == CardWeb.Product(maker: "Samsung", name: "UE55U8092FUXXH"))
        #expect(CardWeb.product(maker: "Apple", name: "iPhone 15 Pro")?.name == "iPhone 15 Pro")
    }

    @Test func onlyTheProductOrTheDomainGoesOut() {
        let tv = CardWeb.device(CardWeb.Product(maker: "Samsung", name: "UE55U8092FUXXH"), action: .details, russian: true)
        #expect(tv == WebLookup(query: "Samsung UE55U8092FUXXH характеристики безопасность", imageQuery: "Samsung UE55U8092FUXXH"))
        // A maker alone shows its logo, not the device: no picture then.
        let router = CardWeb.device(CardWeb.Product(maker: "Keenetic", name: nil), action: .deep, russian: false, router: true)
        #expect(router == WebLookup(query: "Keenetic router vulnerabilities firmware", imageQuery: nil))
        #expect(CardWeb.linkLookup("https://www.verify-login.xyz/a?token=secret", russian: false) == WebLookup(query: "verify-login.xyz scam reviews", imageQuery: nil))
    }

    var facts: CardFacts {
        CardFacts(subject: .host(ip: "192.168.1.95"), title: "Xiaomi · 192.168.1.95", findings: [.init(risk: .ok, text: "Only UDP 54321 is open.")],
                  steps: [], data: "data", deepData: "deep data")
    }

    @Test func theModelReadsTheWebAsNumberedSources() {
        let web = SearchPayload(query: "Xiaomi careli.fryer.maf02 specs security", provider: "Tavily", results: [
            SearchResult(title: "Mi Smart Air Fryer", url: URL(string: "https://www.mi.com/fryer")!, snippet: "Smart air fryer"),
            SearchResult(title: "Unused page", url: URL(string: "https://example.com")!, snippet: "nothing"),
        ], evidence: [Passage(sourceIndex: 0, text: "The Mi Smart Air Fryer 3.5L is controlled from the Mi Home app.", position: 0)])
        let user = CardPrompts.user(facts, action: .details, web: web)
        #expect(user.contains("<web>"))
        #expect(user.contains("[1] Mi Smart Air Fryer (www.mi.com)"))
        #expect(!user.contains("Unused page"))
        let english = Locale(identifier: "en")
        #expect(CardPrompts.system(.details, kind: .host, locale: english, web: true).contains("<web>"))
        #expect(!CardPrompts.system(.details, kind: .host, locale: english).contains("<web>"))
        #expect(!CardPrompts.user(facts, action: .details).contains("<web>"))
    }
}
