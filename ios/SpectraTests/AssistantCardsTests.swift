import Foundation
import LocalChatKit
import Testing
@testable import Spectra

/// Summary cards: findings and buttons built by the app's own rules, how a card is saved, and its narrow prompts.
/// Ported from AssistantCardsTest.kt. The simulator may run in Russian: texts are compared through
/// `String(localized:)`, never as English literals.
struct AssistantCardsTests {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    var router: AssistantSnapshot.Host {
        AssistantSnapshot.Host(ip: "192.168.1.1", mac: "AA:BB:CC:00:00:01", name: "Keenetic", maker: "Keenetic",
                               ports: ["23 Telnet", "80 HTTP"], portsScanned: true, risks: ["Telnet (High risk)"], isGateway: true,
                               portRisks: [.init(title: "Telnet: clear-text login", high: true)])
    }
    var camera: AssistantSnapshot.Host { AssistantSnapshot.Host(ip: "192.168.1.64", name: "IPC", maker: "Hikvision", isNew: true) }
    var phone: AssistantSnapshot.Host { AssistantSnapshot.Host(ip: "192.168.1.20", name: "This phone", isThisPhone: true) }

    func snapshot(hosts: [AssistantSnapshot.Host] = [], link: AssistantSnapshot.Link? = nil, label: String? = nil) -> AssistantSnapshot {
        var s = AssistantSnapshot()
        s.hosts = hosts
        s.link = link
        s.networkLabel = label
        return s
    }

    func calls(_ steps: [CardStep]) -> [String] { steps.map(\.call) }

    /// Every step must be a call the actions can run as it is.
    func expectRunnable(_ steps: [CardStep]) {
        for step in steps {
            #expect(AssistantActions.Name(rawValue: step.tool) != nil, "\(step.call)")
            if step.call.hasPrefix("{") {
                #expect((try? JSONSerialization.jsonObject(with: Data(step.call.utf8))) != nil, "\(step.call)")
            }
            #expect(!step.label.isEmpty && !step.label.hasSuffix("?"), "\(step.label)")
        }
    }

    // MARK: - LAN

    @Test func aHostWithARiskyPortLeadsItsCard() {
        let facts = AssistantCards.host(snapshot(hosts: [router, camera]), ip: "192.168.1.1", now: now)
        #expect(facts.findings.first?.risk == .critical)
        #expect(facts.findings.first?.text.contains("Telnet") == true)
        #expect(facts.findings.contains { $0.text == String(localized: "This is the router: everything else reaches the internet through it.") })
        // Already scanned: the next scan goes deeper, and the router's forwarding table is worth a look.
        #expect(calls(facts.steps) == [#"{"tool": "scan_ports", "host": "192.168.1.1", "text": "all"}"#, "check_port_forwarding"])
        expectRunnable(facts.steps)
        // Only this host goes to the short summary; the neighbours wait for the deep analysis.
        #expect(facts.data.contains("192.168.1.1"))
        #expect(!facts.data.contains("192.168.1.64"))
        #expect(facts.deepData.contains("192.168.1.64"))
    }

    @Test func aHostWithEveryPortCheckedIsNotOfferedTheSameScan() {
        var fryer = camera
        fryer.isNew = false
        fryer.portsScanned = true
        fryer.allPortsScanned = true
        fryer.ports = ["54321 miIO (UDP)"]
        let facts = AssistantCards.host(snapshot(hosts: [fryer]), ip: fryer.ip, now: now)
        #expect(!facts.steps.contains { $0.tool == "scan_ports" })
        #expect(facts.findings.contains { $0.text == String(localized: "All 65535 ports checked. Open: \("54321 miIO (UDP)") — nothing risky among them.") })
        fryer.ports = []
        let quiet = AssistantCards.host(snapshot(hosts: [fryer]), ip: fryer.ip, now: now)
        #expect(quiet.findings.contains { $0.text == String(localized: "All 65535 ports checked: none is open.") })
        #expect(quiet.steps.isEmpty)
    }

    @Test func anUnscannedHostOffersAPortScan() {
        let facts = AssistantCards.host(snapshot(hosts: [router, camera]), ip: "192.168.1.64", now: now)
        #expect(facts.findings.first?.risk == .warning) // New on the network.
        #expect(facts.findings.contains { $0.risk == .info && $0.text == String(localized: "Ports have not been checked yet.") })
        #expect(calls(facts.steps) == [#"{"tool": "scan_ports", "host": "192.168.1.64"}"#])
        #expect(facts.title == "IPC · Hikvision · 192.168.1.64")
    }

    @Test func aSuspiciousMarkIsCriticalAndAQuietHostIsFine() {
        var marked = camera
        marked.trust = "suspicious"
        marked.isNew = false
        #expect(AssistantCards.host(snapshot(hosts: [marked]), ip: marked.ip, now: now).findings.first?.risk == .critical)
        var quiet = camera
        quiet.isNew = false
        quiet.portsScanned = true
        quiet.trust = "trusted"
        #expect(AssistantCards.worst(AssistantCards.host(snapshot(hosts: [quiet]), ip: quiet.ip, now: now).findings) == .ok)
    }

    @Test func aHostThatIsGoneAsksForANewScan() {
        let facts = AssistantCards.host(snapshot(hosts: [router]), ip: "192.168.1.99", now: now)
        #expect(facts.findings.map(\.risk) == [.info])
        #expect(calls(facts.steps) == ["scan_lan"])
    }

    @Test func theNetworkCardRanksEveryHostAndTheConnection() {
        var link = AssistantSnapshot.Link(checkedAt: now)
        link.intercepted = true
        link.exposedPorts = [8080]
        let facts = AssistantCards.lan(snapshot(hosts: [camera, router, phone], link: link, label: "Home"), now: now)
        let risks = facts.findings.map(\.risk)
        #expect(risks == risks.sorted())
        #expect(facts.findings.filter { $0.risk == .critical }.count == 3) // Interception, open port, Telnet.
        #expect(facts.findings.contains { $0.risk == .warning && $0.text.hasPrefix("IPC (192.168.1.64)") })
        #expect(!facts.findings.contains { $0.text.contains("This phone") })
        #expect(facts.findings.contains { $0.text == String(localized: "Devices on \("Home"): \(3).") })
        #expect(facts.steps.count <= 3)
        #expect(calls(facts.steps).contains("check_port_forwarding"))
        expectRunnable(facts.steps)
    }

    @Test func anUnscannedNetworkStartsWithAScan() {
        let facts = AssistantCards.lan(AssistantSnapshot(), now: now)
        #expect(calls(facts.steps) == ["scan_lan", "check_connection"])
        #expect(facts.findings.map(\.text) == [String(localized: "The local network has not been scanned yet.")])
    }

    @Test func theForwardingCheckBecomesAFindingAndIsNotOfferedAgain() {
        #expect(calls(AssistantCards.host(snapshot(hosts: [router]), ip: router.ip, now: now).steps).contains("check_port_forwarding"))

        var open = router
        open.forwarding = .init(supported: true, rules: ["TCP 8080 → 192.168.1.64:80"])
        let after = AssistantCards.host(snapshot(hosts: [open]), ip: router.ip, now: now)
        #expect(after.findings.contains { $0.risk == .critical && $0.text.contains("TCP 8080 → 192.168.1.64:80") })
        #expect(!calls(after.steps).contains("check_port_forwarding"))
        let lan = AssistantCards.lan(snapshot(hosts: [open, camera]), now: now)
        #expect(!calls(lan.steps).contains("check_port_forwarding"))
        #expect(lan.findings.contains { $0.text.contains("8080") })

        var closed = router
        closed.forwarding = .init(supported: true, rules: [])
        #expect(AssistantCards.host(snapshot(hosts: [closed]), ip: router.ip, now: now).findings
            .contains { $0.risk == .ok && $0.text == String(localized: "The router forwards no ports from the internet.") })
        var off = router
        off.forwarding = .init(supported: false, rules: [])
        #expect(AssistantCards.host(snapshot(hosts: [off]), ip: router.ip, now: now).findings
            .contains { $0.risk == .ok && $0.text == String(localized: "The router does not let devices open ports over UPnP: the safer setting.") })
    }

    // MARK: - Wi‑Fi

    var home: AssistantSnapshot.Wifi {
        AssistantSnapshot.Wifi(name: "Home", bssid: "AA:BB:CC:00:00:10", rssi: -50, channel: 6, band: "2.4 GHz", security: "WPA2", isCurrent: true)
    }

    @Test func encryptionIsRanked() {
        #expect(AssistantCards.securityLevel("Open") == .critical)
        #expect(AssistantCards.securityLevel("WEP") == .critical)
        #expect(AssistantCards.securityLevel("WPA") == .warning)
        #expect(AssistantCards.securityLevel("WPA/WPA2") == .warning)
        #expect(AssistantCards.securityLevel("WPA2") == .ok)
        #expect(AssistantCards.securityLevel("WPA3") == .ok)
        #expect(AssistantCards.securityLevel("Other") == .info)
    }

    @Test func aNetworkCardTakesOnlyItsOwnThreats() {
        let twin = AssistantSnapshot.Finding(severity: "critical", title: "Possible evil twin", detail: "\"Home\" is broadcast by 2 access points",
                                             bssids: ["aa:bb:cc:00:00:10"])
        let elsewhere = AssistantSnapshot.Finding(severity: "warning", title: "Steady data stream", detail: "\"Cafe\" moves data", bssids: ["11:22:33:44:55:66"])
        let crowd = (1...4).map { AssistantSnapshot.Wifi(name: "N\($0)", bssid: "00:00:00:00:00:0\($0)", rssi: -70, channel: 6, band: "2.4 GHz", security: "WPA2") }
        var s = AssistantSnapshot()
        s.wifi = [home] + crowd
        s.findings = [twin, elsewhere]
        s.sensorConnected = true
        let facts = AssistantCards.wifi(s, bssid: home.bssid, now: now)
        #expect(facts.findings.first?.risk == .critical)
        #expect(facts.findings.first?.text.hasPrefix("Possible evil twin") == true)
        #expect(!facts.findings.contains { $0.text.contains("Cafe") })
        #expect(facts.findings.contains { $0.risk == .ok && $0.text == String(localized: "Encryption: \("WPA2").") })
        #expect(facts.findings.contains { $0.text == String(localized: "Channel \(6) is crowded: \(5) networks share it.") })
        #expect(facts.steps.first?.call == #"{"tool": "capture_pcap", "bssid": "AA:BB:CC:00:00:10"}"#)
        expectRunnable(facts.steps)
        #expect(!facts.data.contains("Cafe"))
    }

    @Test func anOpenNetworkWithoutTheSensorCannotBeCaptured() {
        var open = home
        open.security = "Open"
        var s = AssistantSnapshot()
        s.wifi = [open]
        let facts = AssistantCards.wifi(s, bssid: open.bssid, now: now)
        #expect(facts.findings.first?.risk == .critical)
        #expect(facts.findings.contains { $0.text == String(localized: "Without the sensor: no clients or deauth frames are seen.") })
        #expect(calls(facts.steps) == ["scan_wifi"])
    }

    // MARK: - Bluetooth

    static let tagID = "6F2D7A4E-0000-4000-8000-000000000001"
    static let speakerID = "6F2D7A4E-0000-4000-8000-000000000002"
    var tag: AssistantSnapshot.Bluetooth {
        AssistantSnapshot.Bluetooth(name: "AirTag", company: "Apple", rssi: -48, minutesNearby: 25, tracker: "Find My tag",
                                    trackerLevel: "alert", away: true, id: Self.tagID)
    }
    var speaker: AssistantSnapshot.Bluetooth {
        AssistantSnapshot.Bluetooth(name: "Momentum 3", company: "Sennheiser", rssi: -62, id: Self.speakerID)
    }

    @Test func aTrackerAwayFromItsOwnerIsCritical() {
        var s = AssistantSnapshot()
        s.bluetooth = [tag, speaker]
        let facts = AssistantCards.bluetooth(s, id: Self.tagID.lowercased(), now: now)
        #expect(facts.findings.first?.risk == .critical)
        #expect(facts.findings.first?.text.contains("25") == true)
        #expect(facts.findings.contains { $0.text.contains(String(localized: "very close, within a couple of metres")) })
        #expect(!facts.data.contains("Momentum"))
    }

    @Test func aMakerNameEndsWithOneFullStop() {
        var apple = tag
        apple.company = "Apple, Inc."
        apple.trackerLevel = "watch"
        var s = AssistantSnapshot()
        s.bluetooth = [apple]
        let facts = AssistantCards.bluetooth(s, id: Self.tagID, now: now)
        #expect(facts.findings.first?.risk == .warning)
        #expect(!facts.findings.contains { $0.text.contains("..") })
    }

    @Test func aDeviceIsOfferedForAReadUntilItIsRead() {
        var s = AssistantSnapshot()
        s.bluetooth = [speaker]
        let before = AssistantCards.bluetooth(s, id: Self.speakerID, now: now)
        #expect(before.steps.first?.call == #"{"tool": "connect_bluetooth", "text": "Momentum 3"}"#)
        expectRunnable(before.steps)
        s.gatt = "GATT of Momentum 3: Battery 80%"
        s.gattDeviceID = Self.speakerID
        s.gattItems = [.init(service: "Battery Service", name: "Battery Level", value: "80%", writable: false, vendor: false)]
        let after = AssistantCards.bluetooth(s, id: Self.speakerID, now: now)
        #expect(calls(after.steps) == ["scan_bluetooth"])
        #expect(after.findings.contains { $0.risk == .ok })
        #expect(after.data.contains("Battery 80%"))
        // Another device's read never rides along.
        s.bluetooth = [speaker, tag]
        #expect(!AssistantCards.bluetooth(s, id: Self.tagID, now: now).data.contains("Battery"))
    }

    @Test func aGattReadIsTakenApartItemByItem() {
        let items: [AssistantSnapshot.GattItem] = [
            .init(service: "74EC2172-0BAD-4D01-8F77-997B2BE0722A", name: "4F860002-943B-49EF-BED4-2F730304427A", value: nil, writable: true, vendor: true),
            .init(service: "74EC2172-0BAD-4D01-8F77-997B2BE0722A", name: "4F860003-943B-49EF-BED4-2F730304427A", value: "0x0102", writable: false, vendor: true),
            .init(service: "Device Information", name: "Model Number String", value: "A2187", writable: false, vendor: false),
            .init(service: "Battery Service", name: "Battery Level", value: "60%", writable: false, vendor: false),
        ]
        let found = AssistantCards.gattFindings(items)
        #expect(found.first?.risk == .warning)
        // Standard values first: they say what the device is.
        #expect(found.filter { $0.risk == .info }.map(\.text) == ["Model Number String: A2187", "Battery Level: 60%",
                                                                  "4F860003-943B-49EF-BED4-2F730304427A: 0x0102"])
        #expect(found.last?.text == String(localized: "Read over GATT: \(3) services, \(4) characteristics; nothing was written."))
        #expect(AssistantCards.gattFindings([]).map(\.risk) == [.info])
    }

    // MARK: - Traffic and captures

    func flow(_ id: Int64, host: String, tls: Bool, request: String? = nil) -> TrafficFlow {
        TrafficFlow(id: id, proto: "TCP", source: "10.0.0.2", sourcePort: 40000, destination: "93.184.216.34", destinationPort: tls ? 443 : 80,
                    host: host, request: request, app: "Browser", tls: tls, sent: 500, received: 5000, startedAt: 0, lastAt: 1000, closed: true)
    }

    @Test func plainHttpIsAWarning() {
        let facts = AssistantCards.traffic([flow(1, host: "example.com", tls: false, request: "GET /"), flow(2, host: "api.example.org", tls: true)],
                                           name: "Current recording", digest: "digest")
        #expect(facts.findings.first?.risk == .warning)
        #expect(facts.findings.first?.text.contains("example.com") == true)
        #expect(!facts.findings.contains { $0.risk == .ok })
        #expect(facts.steps.isEmpty)
        #expect(AssistantCards.traffic([flow(2, host: "api.example.org", tls: true)], name: "x", digest: "d").findings.contains { $0.risk == .ok })
        #expect(AssistantCards.traffic([], name: "x", digest: "d").findings.map(\.risk) == [.info])
    }

    func capture(_ apps: [NamedCount], packets: Int = 500) -> CaptureAnalysis {
        CaptureAnalysis(format: "pcap",
                        summary: CaptureSummary(packetCount: packets, capturedBytes: 1000, wireBytes: 1000, start: Date(timeIntervalSince1970: 0),
                                                end: Date(timeIntervalSince1970: 6), linkTypes: ["802.11 radiotap"], protocols: [], apps: apps,
                                                talkers: [Talker(endpoint: "AA:BB:CC:00:00:10", packets: 300, bytes: 600)], conversations: []),
                        rows: [], rowsTruncated: false)
    }

    @Test func aDeauthBurstInACaptureIsCritical() {
        #expect(AssistantCards.capture(capture([.init(name: "beacon", count: 400), .init(name: "deauth", count: 40)]), name: "Home", digest: "d")
            .findings.first?.risk == .critical)
        #expect(AssistantCards.capture(capture([.init(name: "deauth", count: 2)]), name: "Home", digest: "d").findings.first?.risk == .warning)
        let quiet = AssistantCards.capture(capture([.init(name: "beacon", count: 400)]), name: "Home", digest: "d")
        #expect(quiet.findings.last?.risk == .ok)
        #expect(quiet.findings.contains { $0.text == String(localized: "\(500) packets over \("6") s, \(1) addresses.") })
        #expect(AssistantCards.capture(capture([], packets: 0), name: "Empty", digest: "d").findings.map(\.risk) == [.info])
    }

    // MARK: - Ranking, saving, prompts

    @Test func findingsAreRankedDedupedAndCapped() {
        let many = (1...12).map { CardFinding(risk: $0 % 3 == 0 ? .critical : .info, text: "finding \($0)") } + [CardFinding(risk: .info, text: "finding 1")]
        let ranked = AssistantCards.ranked(many)
        #expect(ranked.count == AssistantCards.maxFindings)
        #expect(ranked.prefix(4).map(\.text) == ["finding 3", "finding 6", "finding 9", "finding 12"])
        #expect(Set(ranked.map(\.text)).count == ranked.count)
    }

    @Test func aSavedCardReadsBackTheSame() throws {
        let findings = [CardFinding(risk: .critical, text: "Telnet: clear-text login"), CardFinding(risk: .ok, text: "WPA2")]
        let parsed = try #require(AssistantCards.parse(AssistantCards.render(title: "Keenetic · 192.168.1.1", findings: findings)))
        #expect(parsed.title == "Keenetic · 192.168.1.1")
        #expect(parsed.findings == findings)
        #expect(AssistantCards.parse("Just a question from an old chat") == nil)
        let subjects: [CardSubject] = [.lan, .host(ip: "192.168.1.1"), .bluetooth(id: Self.speakerID), .wifi(bssid: "AA:BB:CC:00:00:10"),
                                       .traffic, .capture, .internet, .link("https://example.com/a:b")]
        for subject in subjects {
            let encoded = CardSubjects.encode(subject)
            #expect(CardSubjects.isCard(encoded))
            #expect(CardSubjects.decode(encoded) == subject)
        }
        #expect(!CardSubjects.isCard(""))
        #expect(!CardSubjects.isCard(nil))
        #expect(CardSubjects.decode("You are a helpful assistant") == nil)
    }

    @Test func savedTextsKeepTheirActionInEitherLanguage() {
        #expect(AssistantCardRunner.savedText("**Deep analysis**\n\nLong text") == (.deep, "Long text"))
        let local = "**\(CardAction.details.heading)**\n\nТекст"
        #expect(AssistantCardRunner.savedText(local) == (.details, "Текст"))
        #expect(AssistantCardRunner.savedText("**In plain words**\n\nWords") == (.summary, "Words"))
        #expect(AssistantCardRunner.savedText("✓ Сканировать порты") == (nil, "Сканировать порты"))
    }

    @Test func everyActionAndSubjectGetsItsOwnShortPrompt() {
        let english = Locale(identifier: "en")
        let kinds: [CardSubject.Kind] = [.lan, .host, .bluetooth, .wifi, .traffic, .capture, .internet, .link]
        let prompts = CardAction.allCases.filter { $0 != .check }.flatMap { action in kinds.map { CardPrompts.system(action, kind: $0, locale: english) } }
        #expect(Set(prompts).count == prompts.count)
        // After a check the prompt follows the check, not the subject: each kind of check gets its own.
        let checks = ["check_port_forwarding", "connect_bluetooth", "scan_ports", "capture_pcap"]
            .map { CardPrompts.system(.check, kind: .host, locale: english, tool: $0) }
        #expect(Set(checks).count == checks.count)
        #expect(prompts.map(\.count).max()! < 2200)
        #expect(CardPrompts.system(.summary, kind: .host, locale: Locale(identifier: "ru")).hasSuffix("Answer in Russian."))
        #expect(CardAction.summary.maxTokens <= 160)
        #expect(CardAction.deep.maxTokens > CardAction.details.maxTokens)
    }

    @Test func afterACheckTheModelIsAskedAboutTheCheckNotTheSummaryAgain() {
        let facts = AssistantCards.host(snapshot(hosts: [router]), ip: router.ip, now: now)
        let check = CardCheck(title: "port forwarding check", result: "The router forwards no ports from the internet right now.", tool: "check_port_forwarding")
        let text = CardPrompts.user(facts, action: .check, check: check, earlier: "The router has Telnet open.")
        #expect(text.contains("do not repeat any of it):\nThe router has Telnet open."))
        #expect(text.contains("<check name=\"port forwarding check\">\nThe router forwards no ports"))
        #expect(text.hasSuffix("</check>\n\nWhat did this check show?"))
        #expect(!text.contains("Findings of the app"))
        #expect(!text.contains("<data>"))
        #expect(!text.contains("Telnet: clear-text login"))
        let english = Locale(identifier: "en")
        let system = CardPrompts.system(.check, kind: .host, locale: english, tool: check.tool)
        #expect(system.contains("Never retell the summary"))
        #expect(system.contains("which ports it forwards"))
        #expect(!system.contains("what kind of device it most likely is"))
        let gatt = CardPrompts.system(.check, kind: .bluetooth, locale: english, tool: "connect_bluetooth")
        #expect(gatt.contains("read its GATT services"))
        #expect(!gatt.contains("someone else's tracker"))
        // Later actions still read the check, but never get the earlier summary to echo.
        let details = CardPrompts.user(facts, action: .details, check: check, earlier: "The router has Telnet open.")
        #expect(details.contains("<check"))
        #expect(!details.contains("Telnet open."))
        #expect(!CardPrompts.user(facts, action: .summary).contains("<check"))
        #expect(AssistantCardRunner.savedText("**What the check showed**\n\nNothing forwarded.") == (.check, "Nothing forwarded."))
    }

    @Test func theDeepAnalysisReadsTheWiderData() {
        let facts = AssistantCards.host(snapshot(hosts: [router, camera]), ip: "192.168.1.64", now: now)
        let short = CardPrompts.user(facts, action: .summary)
        let deep = CardPrompts.user(facts, action: .deep)
        #expect(short.contains("[info] " + String(localized: "Ports have not been checked yet.")))
        #expect(!short.contains("192.168.1.1 "))
        #expect(deep.contains("192.168.1.1"))
        #expect(short.contains("<data>") && short.contains("</data>"))
    }

    @Test func serviceTagsAreTakenOut() {
        #expect(AssistantText.polish("По состоянию на <data>: всё хорошо") == "По данным Spectra: всё хорошо")
        #expect(AssistantText.polish("Как видно в <check>, порт закрыт") == "Как видно в данных Spectra, порт закрыт")
        #expect(AssistantText.polish("According to the <data>, fine") == "According to Spectra's data, fine")
        #expect(AssistantText.polish("Plain text") == "Plain text")
    }
}

/// The dossier is gone: its file is deleted at start, and nothing else in the folder is touched.
@MainActor
struct DossierRemovalTests {
    @Test func theOldDossierFileIsDeleted() throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "dossier-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let dossier = dir.appending(path: "assistant_memory.json")
        let chats = dir.appending(path: "assistant.sqlite")
        try Data("{}".utf8).write(to: dossier)
        try Data("db".utf8).write(to: chats)
        AssistantModel.removeDossier(in: dir)
        #expect(!FileManager.default.fileExists(atPath: dossier.path))
        #expect(FileManager.default.fileExists(atPath: chats.path))
        AssistantModel.removeDossier(in: dir)
    }
}
