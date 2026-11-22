import Foundation
import Testing
@testable import Spectra

/// The data the summary cards read (Android 1.1.0's AssistantDigest/AssistantSources): the new link lines, the
/// user's sites, traceroutes, full port scans, and the card-only step tools.
@MainActor
struct CardDataTests {
    static let now = Date(timeIntervalSince1970: 1_800_000_000)

    static var healthy: AssistantSnapshot.Link {
        var link = AssistantSnapshot.Link(checkedAt: now.addingTimeInterval(-300))
        link.status = "online"
        link.gatewayMs = 4
        link.dnsMs = 21
        link.webMs = 140
        link.publicIP = "203.0.113.7"
        link.provider = "Example Telecom"
        link.country = "RS"
        link.downMbps = 92.4
        link.upMbps = 41
        link.latencyMs = 18
        link.jitterMs = 3
        link.intercepted = false
        link.resolverLies = false
        link.access = "full"
        link.gatewayOk = true
        link.dnsOk = true
        link.webOk = true
        link.resolverName = "Example DNS"
        link.resolverCountry = "RS"
        link.tlsReached = 4
        link.exposureChecked = true
        link.bufferbloat = "A"
        link.via = "wifi"
        return link
    }

    @Test func theConnectionSectionCarriesTheNewChecks() {
        var link = Self.healthy
        link.via = "vpn"
        link.warp = true
        link.blocked = ["x.com"]
        var snapshot = AssistantSnapshot()
        snapshot.link = link
        snapshot.sites = [.init(host: "ya.ru", ok: true, millis: 40)]
        let text = AssistantDigest.section(.link, snapshot: snapshot, now: Self.now)
        #expect(text.contains("Checked through the VPN"))
        #expect(text.contains("DNS resolver the servers see: Example DNS · RS"))
        #expect(text.contains("HTTPS: genuine certificates on 4 reference sites"))
        #expect(text.contains("No usual port of the public address answers"))
        #expect(text.contains("Often-blocked services that did not open: x.com"))
        #expect(text.contains("The user's own sites, 1 tried:\n- Open: ya.ru (40 ms)\n- Do not open: none"))
        #expect(text.contains("Cloudflare WARP"))
        #expect(text.contains("grade A"))
    }

    @Test func sitesAreListedEvenWithoutAConnectionCheck() {
        var snapshot = AssistantSnapshot()
        snapshot.sites = [.init(host: "a.example", ok: false), .init(host: "b.example", ok: false)]
        let text = AssistantDigest.section(.link, snapshot: snapshot)
        #expect(text.contains("not run yet"))
        #expect(text.contains("- Open: none\n- Do not open: a.example, b.example"))
    }

    @Test func aTraceIsShortForTheSummaryAndWholeForTheDeepAnalysis() {
        let route = AssistantSnapshot.Route(target: "example.org", address: "93.184.215.14", hops: [
            .init(address: "192.168.1.1", millis: 3),
            .init(address: "10.10.0.1", millis: 12),
            .init(address: "198.51.100.9", millis: 130, place: "Frankfurt, DE · Example Transit"),
            .init(address: nil, millis: nil),
            .init(address: nil, millis: nil),
        ])
        #expect(!route.reached)
        #expect(route.silentTail == 2)
        let slow = AssistantDigest.slowestHop(route.hops)
        #expect(slow?.index == 2 && slow?.jump == 118)
        let compact = AssistantDigest.routeLines(route, compact: true).joined(separator: "\n")
        #expect(compact.contains("Traceroute to example.org (93.184.215.14): did not reach it; the last 2 hops did not answer"))
        #expect(compact.contains("- Places along the way: Frankfurt, DE · Example Transit"))
        #expect(compact.contains("- The delay grows most at hop 3 (198.51.100.9): +118 ms"))
        #expect(!compact.contains("4. *"))
        let full = AssistantDigest.routeLines(route).joined(separator: "\n")
        #expect(full.contains("3. 198.51.100.9 · 130 ms · Frankfurt, DE · Example Transit"))
        #expect(full.contains("4. *"))

        let arrived = AssistantSnapshot.Route(target: "1.1.1.1", address: "1.1.1.1",
                                              hops: [.init(address: "192.168.1.1", millis: 2), .init(address: nil, millis: nil), .init(address: "1.1.1.1", millis: 9)])
        #expect(arrived.reached)
        #expect(AssistantDigest.slowestHop(arrived.hops) == nil)
    }

    @Test func aFullPortScanIsSaid() {
        var snapshot = AssistantSnapshot()
        snapshot.hosts = [AssistantSnapshot.Host(ip: "192.168.1.1", name: "Router", portsScanned: true, allPortsScanned: true)]
        #expect(AssistantDigest.section(.lan, snapshot: snapshot).contains("no open ports (all 65535 ports checked)"))
    }

    @Test func aNamedNetworkGoesFirst() {
        var snapshot = AssistantSnapshot()
        snapshot.wifi = (0..<20).map { AssistantSnapshot.Wifi(name: "Net \($0)", bssid: String(format: "AA:BB:CC:00:00:%02X", $0), rssi: -30 - $0,
                                                              channel: 6, band: "2.4", security: "WPA2") }
        let text = AssistantDigest.section(.wifi, snapshot: snapshot, focus: "aa:bb:cc:00:00:13")
        let lines = text.split(separator: "\n")
        #expect(lines[1].contains("Net 19"))
    }

    @Test func nothingMeasuredMakesNoLinkSection() {
        let store = NetworkStore()
        let history = HistoryStore()
        let probe = LinkProbe()
        let speed = SpeedTester()
        let sources = AssistantSources(store: store, lan: LANScanner(), ble: BLEScanner(), history: history,
                                       link: SensorLink(store: store, history: history), probe: probe, joiner: WiFiJoiner(),
                                       speed: speed, captures: CaptureShelf())
        #expect(sources.snapshot().link == nil)
        #expect(sources.snapshot().route == nil)
    }

    @Test func stepToolsHaveWordsAndCheckTheirArguments() async {
        for name in ["trace_route", "check_sites", "check_site", "expand_redirects"] {
            #expect(AssistantActions.title(for: name) != name)
            #expect(!AssistantActions.runningLabel(for: name).isEmpty)
        }
        #expect(AssistantActions.button(for: "scan_lan") == String(localized: "Scan the local network?"))
        #expect(AssistantActions.button(for: "scan_ports") == nil)

        let store = NetworkStore()
        let history = HistoryStore()
        let sources = AssistantSources(store: store, lan: LANScanner(), ble: BLEScanner(), history: history,
                                       link: SensorLink(store: store, history: history), probe: LinkProbe(), joiner: WiFiJoiner(),
                                       speed: SpeedTester(), captures: CaptureShelf(),
                                       resources: ResourceWatch(defaults: UserDefaults(suiteName: "sites-\(UUID().uuidString)")!))
        let actions = AssistantActions(sources: sources)
        #expect(await actions.perform(#"{"tool": "check_site", "text": "mailto:a@b.c"}"#).contains("Missing or invalid"))
        #expect(await actions.perform(#"{"tool": "expand_redirects"}"#).contains("Missing or invalid"))
        #expect(await actions.perform("not json {").contains("Unknown tool"))
        #expect(await actions.perform("{broken").contains("Bad call"))
    }

    @Test func checkResultsReadPlainly() {
        let none = RedirectTrace(hops: [], final: URL(string: "https://a.example")!, stop: .failed("timeout"))
        let text = InternetCards.redirectText(none)
        #expect(text.contains("could not be requested at all"))
        #expect(!text.contains("Final address"))

        let moved = RedirectTrace(hops: [.init(url: URL(string: "https://bit.ly/x")!, status: 301),
                                         .init(url: URL(string: "https://shop.example/")!, status: 200)],
                                  final: URL(string: "https://shop.example/")!, stop: nil)
        let movedText = InternetCards.redirectText(moved)
        #expect(movedText.contains("Redirects: 1, followed by their headers only"))
        #expect(movedText.contains("Final address: https://shop.example/"))
    }

    @Test func theRegistryIsAskedAboutTheRightName() throws {
        let plain = try #require(LinkInspector.inspect("https://login.example.com/x"))
        #expect(LinkShelf.registryName(plain) == "example.com")
        let ip = try #require(LinkInspector.inspect("http://203.0.113.5/login"))
        #expect(LinkShelf.registryName(ip) == nil)
        #expect(LinkShelf.isWeb(plain))
    }

    @Test func theShelfKeepsTheLatestLinks() {
        let shelf = LinkShelf()
        for index in 0..<10 {
            shelf.keep(redirects: RedirectTrace(hops: [], final: URL(string: "https://a.example")!, stop: nil), for: "https://\(index).example")
        }
        #expect(shelf.entry("https://0.example").redirects == nil)
        #expect(shelf.entry("https://9.example").redirects != nil)
    }
}
