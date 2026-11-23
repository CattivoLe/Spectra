import Foundation
import NetworkExtension
import Testing
@testable import LocalChatKit
@testable import Spectra

@MainActor
struct SightingsTests {
    private let early = Date(timeIntervalSince1970: 1_000)
    private let late = Date(timeIntervalSince1970: 900_000)
    private let bssid = BSSID(bytes: [1, 2, 3, 4, 5, 6])

    private func network(_ date: Date) -> TrackedNetwork {
        TrackedNetwork(
            latest: AccessPointReading(
                bssid: bssid, rssi: -60, channel: 6, secondaryChannel: .none, auth: .wpa2PSK,
                pairwiseCipher: .ccmp, groupCipher: .ccmp, capabilities: [], countryCode: nil, ssidBytes: Data("Net".utf8)
            ),
            firstSeen: date, lastSeen: date, samples: []
        )
    }

    private func host(_ ip: String, names: [String] = [], mac: [UInt8]? = nil, seen: Date) -> LANDevice {
        LANDevice(ipAddress: ip, macAddress: mac.map(MACAddress.init(bytes:)), hostnames: names, firstSeen: seen, lastSeen: seen)
    }

    private func lan(_ device: LANDevice, place: String, from: String? = nil) -> HistoryEntry {
        var entry = HistoryEntry(kind: .lan, savedAt: late, source: "This iPhone", placeKey: place, lan: [SavedLAN(device)])
        entry.importedFrom = from
        return entry
    }

    @Test func aNetworkKeepsTheEarliestDateOfAllSessions() {
        let old = HistoryEntry(kind: .networks, savedAt: early, source: "This iPhone", networks: [network(early)])
        let new = HistoryEntry(kind: .networks, savedAt: late, source: "This iPhone", networks: [network(late)])
        let sightings = Sightings([new, old])
        #expect(sightings.first(bssid) == early)
        #expect(new.dated(by: sightings).networks.first?.firstSeen == early)
        #expect(sightings.first(BSSID(bytes: [9, 9, 9, 9, 9, 9])) == nil)
    }

    @Test func aHostIsKnownByNameOnAnyNetworkAndByAddressOnItsOwn() {
        let sightings = Sightings([
            lan(host("192.168.1.5", names: ["living-room-tv.local"], seen: early), place: "lan:net:192.168.1.0/24"),
            lan(host("192.168.1.7", seen: early), place: "lan:net:192.168.1.0/24"),
        ])
        // The name follows the host to a new address, and to the network's other key.
        #expect(sightings.first(host("192.168.1.80", names: ["living-room-tv.local"], seen: late), networks: ["lan:ssid:Home"]) == early)
        // Without a name the address counts, on a network saved under one of the keys.
        #expect(sightings.first(host("192.168.1.7", seen: late), networks: ["lan:ssid:Home", "lan:net:192.168.1.0/24"]) == early)
        #expect(sightings.first(host("192.168.1.7", seen: late), networks: ["lan:ssid:Cafe"]) == nil)
    }

    @Test func anotherPhonesSessionsDoNotCount() {
        let imported = lan(host("192.168.1.5", mac: [2, 0, 0, 0, 0, 1], seen: early), place: "lan:ssid:Home", from: "Pixel")
        let sightings = Sightings([imported])
        #expect(sightings.first(host("192.168.1.5", mac: [2, 0, 0, 0, 0, 1], seen: late), networks: ["lan:ssid:Home"]) == nil)
        let dated = imported.dated(by: Sightings([lan(host("192.168.1.5", mac: [2, 0, 0, 0, 0, 1], seen: .distantPast), place: "lan:ssid:Home")]))
        #expect(dated.lan.first?.firstSeen == early)
    }

    @Test func theLiveListStartsFromTheEarlierDate() {
        let store = NetworkStore()
        store.earlier = { [early] _ in early }
        store.handle(.accessPoint(scanID: 1, reading: network(late).latest))
        #expect(store.networks[bssid]?.firstSeen == early)
    }
}

struct OpenWiFiTests {
    @Test func openOnlyWhenNothingSaysOtherwise() {
        let open = WiFiInfo.Network(ssid: "Home", bssid: nil, isSecure: false)
        #expect(open.isOpen(savedPassword: nil, heard: []))
        #expect(open.isOpen(savedPassword: "", heard: [.none]))
        #expect(!open.isOpen(savedPassword: "secret", heard: []))
        #expect(!open.isOpen(savedPassword: nil, heard: [.fair]))
        #expect(!WiFiInfo.Network(ssid: "Home", bssid: nil, isSecure: true).isOpen(savedPassword: nil, heard: []))
    }

    @Test func onlyAnOpenSecurityTypeIsOpen() {
        #expect(!WiFiInfo.isSecure(.open))
        for type in [NEHotspotNetworkSecurityType.WEP, .personal, .enterprise, .unknown] {
            #expect(WiFiInfo.isSecure(type))
        }
    }
}

@MainActor
struct JoinErrorTests {
    private func error(_ code: NEHotspotConfigurationError) -> NSError {
        NSError(domain: NEHotspotConfigurationErrorDomain, code: code.rawValue)
    }

    /// These end the attempt; after the others iOS may still join, so the phone's network is watched.
    @Test func onlyCertainRefusalsAreFinal() {
        for code in [NEHotspotConfigurationError.userDenied, .invalidWPAPassphrase, .invalidSSID] {
            #expect(WiFiJoiner.isFinal(error(code)))
        }
        for code in [NEHotspotConfigurationError.internal, .unknown, .pending, .alreadyAssociated] {
            #expect(!WiFiJoiner.isFinal(error(code)))
        }
        #expect(!WiFiJoiner.isFinal(NSError(domain: NSURLErrorDomain, code: 7)))
    }
}

struct TrafficFilterTests {
    private func exchange(_ method: String, _ path: String, status: Int?) -> HttpExchange {
        HttpExchange(method: method, path: path, query: [], requestHeaders: [], parameters: [], status: status, responseHeaders: [])
    }

    private func flow(_ proto: String, port: Int, tls: Bool = false, closed: Bool = true, _ exchanges: [HttpExchange] = []) -> TrafficFlow {
        TrafficFlow(id: 1, proto: proto, source: "10.0.0.1", sourcePort: 5000, destination: "1.1.1.1", destinationPort: port,
                    host: "api.example.com", tls: tls, startedAt: 0, lastAt: 0, closed: closed, exchanges: exchanges)
    }

    @Test func methodsStatusAndTextNarrowTheRequests() {
        let get = exchange("GET", "/users", status: 200), post = exchange("post", "/login", status: 401)
        let both = flow("TCP", port: 443, tls: true, [get, post])
        var filter = RequestFilter()
        #expect(filter.passes(get, of: both) && filter.passes(post, of: both) && !filter.isNarrowing)
        filter.methods = ["POST"]
        #expect(!filter.passes(get, of: both) && filter.passes(post, of: both))
        filter = RequestFilter(failedOnly: true)
        #expect(!filter.passes(get, of: both) && filter.passes(post, of: both))
        filter = RequestFilter(query: "USERS")
        #expect(filter.passes(get, of: both) && !filter.passes(post, of: both))
    }

    @Test func aRequestStillOnItsWayHasNotFailed() {
        let waiting = exchange("GET", "/", status: nil)
        #expect(!RequestFilter.failed(waiting, of: flow("TCP", port: 443, closed: false, [waiting])))
        #expect(RequestFilter.failed(waiting, of: flow("TCP", port: 443, closed: true, [waiting])))
    }

    @Test func commonMethodsAlwaysHaveAChip() {
        #expect(RequestFilter.methods(in: []) == RequestFilter.commonMethods)
        let options = flow("TCP", port: 443, [exchange("OPTIONS", "/", status: 204), exchange("GET", "/", status: 200)])
        #expect(RequestFilter.methods(in: [options]) == RequestFilter.commonMethods + ["OPTIONS"])
    }

    /// Almost all TCP is TLS or HTTP, and almost all UDP is DNS: the transport filters used to leave those out and
    /// show nothing.
    @Test func transportFiltersTakeInWhatRunsOverThem() {
        let web = flow("TCP", port: 443, tls: true), read = flow("TCP", port: 443, tls: true, [exchange("GET", "/", status: 200)])
        let dns = flow("UDP", port: 53), raw = flow("TCP", port: 5222)
        #expect([web, read, dns, raw].filter(ConnectionFilter.tcp.passes).count == 3)
        #expect([web, read, dns, raw].filter(ConnectionFilter.udp.passes) == [dns])
        #expect([web, read, dns, raw].filter(ConnectionFilter.tls.passes) == [web])
        #expect([web, read, dns, raw].filter(ConnectionFilter.http.passes) == [read])
        #expect([web, read, dns, raw].filter { ConnectionFilter.passes(nil, $0) } == [web, read, raw])
    }
}

struct JSONSyntaxTests {
    private func pieces(_ text: String) -> [String] {
        JSONSyntax.spans(text).map { "\($0.kind):\((text as NSString).substring(with: $0.range))" }
    }

    @Test func tellsKeysStringsNumbersAndLiteralsApart() {
        #expect(pieces(#"{"a" : "b", "n": -1.5e3, "t": true, "z": null}"#) == [
            #"key:"a""#, #"string:"b""#, #"key:"n""#, "number:-1.5e3", #"key:"t""#, "literal:true", #"key:"z""#, "literal:null",
        ])
    }

    @Test func escapesAndWideCharactersKeepTheirPlaces() {
        #expect(pieces(#"{"q\"uote": "😀 \\", "дом": 1}"#) == [#"key:"q\"uote""#, #"string:"😀 \\""#, #"key:"дом""#, "number:1"])
    }

    /// A body cut at the cap ends anywhere, also right after a backslash.
    @Test func aCutBodyIsColouredAsFarAsItGoes() {
        #expect(pieces(#"{"a": "unfinis"#) == [#"key:"a""#, #"string:"unfinis"#])
        #expect(pieces(#"["x\"#) == [#"string:"x\"#])
        #expect(pieces("").isEmpty)
    }

    @Test func onlyTextThatStartsLikeJSONIsColoured() {
        #expect(JSONSyntax.looksLikeJSON("\n  [1]"))
        #expect(!JSONSyntax.looksLikeJSON("<html>{}</html>"))
        #expect(!JSONSyntax.looksLikeJSON("   "))
    }
}

struct PastSearchTests {
    private func kept(_ id: Int64, _ query: String, at date: Date) throws -> CachedSearch {
        let found = WebResearch.Gathered(results: [SearchResult(title: query, url: URL(string: "https://example.com")!, snippet: "")],
                                         passages: [], pagesRead: 0)
        return CachedSearch(id: id, date: date, provider: "Brave", query: query, terms: SearchCache.key(query),
                            gathered: try JSONEncoder().encode(found))
    }

    @Test func aSearchAndWhatItFoundAreOneRow() throws {
        let now = Date()
        let log = [
            SearchLogEntry(id: 3, date: now, provider: "Brave", query: "acme router", succeeded: true),
            SearchLogEntry(id: 2, date: now.addingTimeInterval(-60), provider: "Brave", query: "other thing", succeeded: false),
            SearchLogEntry(id: 1, date: now.addingTimeInterval(-90_000), provider: "Brave", query: "acme router", succeeded: true),
        ]
        let rows = PastSearch.joined(log: log, kept: [try kept(7, "acme router", at: now), try kept(8, "lost log row", at: now.addingTimeInterval(-30))])
        #expect(rows.map(\.query) == ["acme router", "lost log row", "other thing", "acme router"])
        #expect(rows.map(\.results.count) == [1, 1, 0, 0])
        #expect(rows.map(\.succeeded) == [true, true, false, true])
        #expect(Set(rows.map(\.id)).count == rows.count)
    }
}
