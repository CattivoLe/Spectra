import Foundation
import LocalChatKit

/// What Spectra knows right now, flattened to plain values the assistant can read. Built from the live scanners by
/// `AssistantSources`; kept free of them so the rules below can be checked by tests.
nonisolated struct AssistantSnapshot: Equatable, Sendable {
    struct Wifi: Equatable, Sendable {
        var name: String
        var bssid: String
        var rssi: Int
        var channel: Int
        var band: String
        var security: String
        var maker: String?
        var isCurrent = false
        var isHidden = false
        var inRange = true
        var clients = 0
        var streaming = false
    }

    struct Finding: Equatable, Sendable {
        var severity: String
        var title: String
        var detail: String
        /// The access points the finding is about.
        var bssids: [String] = []
    }

    /// One characteristic of the last GATT read. `vendor`: in a service the Bluetooth catalog does not name, i.e.
    /// the maker's own.
    struct GattItem: Equatable, Sendable {
        var service: String
        var name: String
        var value: String?
        var writable: Bool
        var vendor: Bool
    }

    /// The router's answer to a port-forwarding check: `rules` as "TCP 8080 → 192.168.1.50:80".
    struct Forwarding: Equatable, Sendable {
        var supported: Bool
        var rules: [String]
    }

    struct PortRiskItem: Equatable, Sendable {
        var title: String
        var high: Bool
    }

    /// One of the user's own sites (Internet → Resources), as it answered last.
    struct SiteReach: Equatable, Sendable {
        var host: String
        var ok: Bool
        var millis: Int?
    }

    /// `place`: "Frankfurt, DE · Cloudflare" when the hop is public and placed.
    struct RouteHop: Equatable, Sendable {
        var address: String?
        var millis: Int?
        var place: String?
    }

    /// The last traceroute from the Traceroute screen. `failure` is the app's sentence when it did not run.
    struct Route: Equatable, Sendable {
        var target: String
        var address: String?
        var hops: [RouteHop] = []
        var failure: String?
        var running = false

        /// The last hop answered from the target's own address.
        var reached: Bool { address != nil && hops.last(where: { $0.address != nil })?.address == address }

        /// Hops at the end that never answered: a firewall dropping probes, more often than a break.
        var silentTail: Int { hops.reversed().prefix { $0.address == nil }.count }
    }

    struct Host: Equatable, Sendable {
        var ip: String
        var mac: String?
        var name: String
        var maker: String?
        var system: String?
        var ports: [String] = []
        var portsScanned = false
        /// All 65535 TCP ports were checked, not only the common list.
        var allPortsScanned = false
        var risks: [String] = []
        var isGateway = false
        var isThisPhone = false
        var isNew = false
        var trust = "unknown"
        /// The user's own note about the host.
        var note: String?
        /// `risks` with their weight: what the summary card ranks by.
        var portRisks: [PortRiskItem] = []
        /// The router's forwarding table once a check read it; `nil` before that. Gateway only.
        var forwarding: Forwarding?
    }

    struct Bluetooth: Equatable, Sendable {
        var name: String
        var company: String?
        var rssi: Int
        var nearby = true
        var minutesNearby = 0
        var tracker: String?
        /// "info", "watch" or "alert" for trackers.
        var trackerLevel: String?
        var away: Bool?
        /// The system's identifier of the device: what a Bluetooth summary is about.
        var id = ""
    }

    struct Session: Equatable, Sendable {
        var kind: String
        var date: Date
        var source: String
        var place: String?
        var count: Int
    }

    struct Link: Equatable, Sendable {
        var checkedAt: Date
        var status: String?
        var detail: String?
        var gatewayMs: Int?
        var dnsMs: Int?
        var webMs: Int?
        var publicIP: String?
        var provider: String?
        var country: String?
        var downMbps: Double?
        var upMbps: Double?
        var latencyMs: Int?
        var jitterMs: Int?
        var intercepted: Bool?
        var resolverLies: Bool?
        var exposedPorts: [Int] = []
        var access: String?
        /// Whether the router, the name lookup and a web page answered at all; `nil` when not asked.
        var gatewayOk: Bool?
        var dnsOk: Bool?
        var webOk: Bool?
        /// Who really answers the name lookups: the resolver the authoritative servers see.
        var resolverName: String?
        var resolverCountry: String?
        /// Often-blocked services that did not open (access check).
        var blocked: [String] = []
        /// How many reference sites the TLS check could reach; `nil` when it did not run.
        var tlsReached: Int?
        /// The ports reachable from outside were asked about (an empty `exposedPorts` then means none).
        var exposureChecked = false
        /// Bufferbloat grade A–F: how much latency grows under a full load.
        var bufferbloat: String?
        var speedFailed = false
        /// "wifi", "cellular" or "vpn": which way the check went out.
        var via: String?
        /// Traffic left through Cloudflare WARP.
        var warp = false
    }

    var wifiName: String?
    var networkLabel: String?
    var sensorConnected = false
    var sensorName: String?
    var lastWifiScan: Date?
    var wifi: [Wifi] = []
    var findings: [Finding] = []
    var deauthFrames = 0
    var probingDevices = 0
    var hosts: [Host] = []
    var lanScanning = false
    var bluetooth: [Bluetooth] = []
    var sessions: [Session] = []
    var link: Link?
    /// The traffic capture last taken or opened, already written up (see `CaptureDigest`). `nil` when there is none.
    var capture: String?
    /// The last GATT read of a Bluetooth device, already written up (see `GATTDigest`). `nil` when none was made.
    var gatt: String?
    /// Which device `gatt` was read from (`Bluetooth.id`).
    var gattDeviceID: String?
    /// The same read, one entry per characteristic: what the summary card turns into findings.
    var gattItems: [GattItem] = []
    /// The user's own sites that were checked, with their last answer.
    var sites: [SiteReach] = []
    /// The last traceroute, when one was run.
    var route: Route?
    /// Persistent dossier notes saved by the assistant across chats.
    var memory: String?
}

/// The parts of the app's data a summary card reads.
nonisolated enum AssistantTopic: String, CaseIterable, Sendable {
    case wifi, threats, lan, bluetooth, history, link, capture
}

/// Writes the app's data up compactly for the model: one section per topic, sorted by importance rather than dumped
/// whole, since a small model has a short memory. Mirrors AssistantDigest.kt.
nonisolated enum AssistantDigest {
    /// One topic's section rendered from a fresh snapshot: what a card and a step's result read. `focus` is a
    /// lower-cased BSSID: that network goes first.
    static func section(_ topic: AssistantTopic, snapshot: AssistantSnapshot, now: Date = Date(), focus: String = "") -> String {
        let lines: [String] = switch topic {
        case .wifi: wifiSection(snapshot, focus: focus)
        case .threats: threatSection(snapshot)
        case .lan: lanSection(snapshot)
        case .bluetooth: bluetoothSection(snapshot)
        case .history: historySection(snapshot, now: now)
        case .link: linkSection(snapshot, now: now)
        case .capture: captureSection(snapshot)
        }
        return lines.joined(separator: "\n")
    }

    // MARK: - Sections

    /// `focus` is a lower-cased BSSID: that network goes first, so a card about one network in a crowded place still
    /// shows it, with its address, rather than cutting it off below the strongest.
    private static func wifiSection(_ s: AssistantSnapshot, focus: String = "") -> [String] {
        let present = s.wifi.filter(\.inRange)
        guard !present.isEmpty else { return ["Wi-Fi networks: none heard yet (needs the Cardputer sensor)."] }
        func named(_ ap: AssistantSnapshot.Wifi) -> Bool { !focus.isEmpty && focus.contains(ap.bssid.lowercased()) }
        let ranked = present.sorted {
            if named($0) != named($1) { return named($0) }
            if $0.isCurrent != $1.isCurrent { return $0.isCurrent }
            if ($0.security == "Open") != ($1.security == "Open") { return $0.security == "Open" }
            return $0.rssi > $1.rssi
        }
        let limit = 14
        var lines = ["Wi-Fi networks in range (\(present.count)), strongest first, name · channel · signal · security · maker:"]
        for ap in ranked.prefix(limit) {
            var parts = [ap.isHidden ? "(hidden)" : "\"\(clean(ap.name))\"", "\(ap.band) ch\(ap.channel)", "\(ap.rssi) dBm", ap.security]
            if let maker = ap.maker { parts.append(clean(maker)) }
            if ap.isCurrent { parts.append("CURRENT") }
            if ap.clients > 0 { parts.append("\(ap.clients) clients") }
            if ap.streaming { parts.append("steady traffic") }
            lines.append("- " + parts.joined(separator: " · ") + " · \(ap.bssid)")
        }
        if ranked.count > limit { lines.append("- …and \(ranked.count - limit) more, weaker.") }
        let crowded = Dictionary(grouping: present, by: \.channel).filter { $0.value.count >= 4 }
        if let worst = crowded.max(by: { $0.value.count < $1.value.count }) {
            lines.append("Busiest channel: \(worst.key) with \(worst.value.count) networks.")
        }
        return lines
    }

    private static func threatSection(_ s: AssistantSnapshot) -> [String] {
        var lines: [String] = []
        for finding in s.findings.prefix(6) {
            lines.append("- [\(finding.severity)] \(clean(finding.title)): \(clean(finding.detail, limit: 160))")
        }
        if s.deauthFrames > 0 { lines.append("- \(s.deauthFrames) deauthentication frames seen in the latest Wi-Fi scan.") }
        let trackers = s.bluetooth.filter { $0.tracker != nil && $0.trackerLevel != "info" }
        for tracker in trackers.prefix(3) {
            lines.append("- [\(tracker.trackerLevel ?? "watch")] \(clean(tracker.tracker ?? "tracker")) nearby for \(tracker.minutesNearby) min\(tracker.away == true ? ", away from its owner" : "").")
        }
        let hostsAtRisk = s.hosts.filter { !$0.risks.isEmpty }
        for host in hostsAtRisk.prefix(4) {
            lines.append("- LAN \(host.ip) (\(clean(host.name))): \(host.risks.prefix(3).map { clean($0, limit: 60) }.joined(separator: "; ")).")
        }
        for host in s.hosts.filter({ $0.trust == "suspicious" }).prefix(3) {
            lines.append("- LAN \(host.ip) (\(clean(host.name))) was marked suspicious by the user.")
        }
        if let link = s.link {
            if link.intercepted == true { lines.append("- HTTPS certificates look re-signed on this connection (possible interception).") }
            if link.resolverLies == true { lines.append("- The DNS resolver answers for names that do not exist.") }
            if !link.exposedPorts.isEmpty { lines.append("- Ports reachable from the internet: \(link.exposedPorts.prefix(8).map(String.init).joined(separator: ", ")).") }
        }
        if lines.isEmpty {
            return ["Findings: none right now (no deauth, evil-twin, tracker or risky-port alerts in the data)."]
        }
        return ["Findings the app raised, most serious first:"] + lines
    }

    private static func lanSection(_ s: AssistantSnapshot) -> [String] {
        guard !s.hosts.isEmpty else { return ["LAN hosts: none found yet (run a LAN scan)."] }
        func weight(_ h: AssistantSnapshot.Host) -> Int {
            (h.trust == "suspicious" ? 8 : 0) + (h.risks.isEmpty ? 0 : 4) + (h.isNew ? 2 : 0) + (h.isGateway ? 1 : 0)
        }
        let ranked = s.hosts.sorted { weight($0) > weight($1) }
        let limit = 16
        var lines = ["Hosts on \(clean(s.networkLabel ?? "the local network")) (\(s.hosts.count)\(s.lanScanning ? ", scan still running" : "")):"]
        for host in ranked.prefix(limit) {
            var parts = [host.ip]
            if let mac = host.mac { parts.append(mac) }
            parts.append(clean(host.name))
            if let maker = host.maker, !host.name.contains(maker) { parts.append(clean(maker)) }
            if let system = host.system { parts.append(clean(system)) }
            if host.isGateway { parts.append("GATEWAY") }
            if host.isThisPhone { parts.append("THIS PHONE") }
            if host.isNew { parts.append("NEW") }
            if host.trust != "unknown" { parts.append(host.trust) }
            if host.portsScanned {
                let scope = host.allPortsScanned ? " (all 65535 ports checked)" : ""
                parts.append((host.ports.isEmpty ? "no open ports" : "open: " + host.ports.prefix(8).joined(separator: ",")) + scope)
            }
            if let note = host.note, !note.isEmpty { parts.append("note: \(clean(note, limit: 50))") }
            lines.append("- " + parts.joined(separator: " · "))
        }
        if ranked.count > limit { lines.append("- …and \(ranked.count - limit) more.") }
        return lines
    }

    private static func bluetoothSection(_ s: AssistantSnapshot) -> [String] {
        // The last GATT read goes first: when it exists, it is usually what the question is about, and the list
        // below it is what the 3600-character cut may trim.
        let gatt = s.gatt.map { [$0] } ?? []
        guard !s.bluetooth.isEmpty else { return gatt + ["Bluetooth devices: none heard yet."] }
        let ranked = s.bluetooth.sorted {
            if ($0.tracker != nil) != ($1.tracker != nil) { return $0.tracker != nil }
            if $0.nearby != $1.nearby { return $0.nearby }
            return $0.rssi > $1.rssi
        }
        let limit = 12
        var lines = gatt + ["Bluetooth devices (\(s.bluetooth.count), \(s.bluetooth.filter(\.nearby).count) nearby), strongest first:"]
        for device in ranked.prefix(limit) {
            var parts = [clean(device.name)]
            if let company = device.company, !device.name.contains(company) { parts.append(clean(company)) }
            parts.append("\(device.rssi) dBm")
            if !device.nearby { parts.append("out of range") }
            if device.minutesNearby >= 5 { parts.append("\(device.minutesNearby) min nearby") }
            if let tracker = device.tracker { parts.append("TRACKER: \(clean(tracker))" + (device.away == true ? " away from owner" : "")) }
            lines.append("- " + parts.joined(separator: " · "))
        }
        if ranked.count > limit { lines.append("- …and \(ranked.count - limit) more.") }
        return lines
    }

    private static func historySection(_ s: AssistantSnapshot, now: Date) -> [String] {
        guard !s.sessions.isEmpty else { return ["History: nothing saved yet."] }
        var lines = ["Saved sessions, newest first:"]
        for session in s.sessions.sorted(by: { $0.date > $1.date }).prefix(8) {
            let place = session.place.map { " · \(clean($0, limit: 40))" } ?? ""
            lines.append("- \(clock(session.date)) · \(session.kind) · \(session.count) items · from \(clean(session.source, limit: 30))\(place)")
        }
        return lines
    }

    private static func captureSection(_ s: AssistantSnapshot) -> [String] {
        guard let capture = s.capture else {
            return ["Traffic capture: none yet. The user can take one from a Wi‑Fi network's card (Capture traffic), open a .pcap "
                    + "under Wi‑Fi → ⋯ → Decode pcap, or record the phone's own traffic on the Internet tab → Traffic."]
        }
        return [capture]
    }

    private static func linkSection(_ s: AssistantSnapshot, now: Date) -> [String] {
        guard let link = s.link else { return ["Connection check: not run yet (Internet tab → Connection)."] + siteLines(s.sites) }
        var lines = ["Latest connection check, \(clock(link.checkedAt)):"]
        switch link.via {
        case "vpn": lines.append("- Checked through the VPN: the public address and provider are the VPN's")
        case "cellular": lines.append("- Checked over the mobile network, not Wi‑Fi")
        default: break
        }
        if let status = link.status { lines.append("- Status: \(status)\(link.detail.map { " — \(clean($0, limit: 120))" } ?? "")") }
        var latency: [String] = []
        if let ms = link.gatewayMs { latency.append("gateway \(ms) ms") }
        if let ms = link.dnsMs { latency.append("DNS \(ms) ms") }
        if let ms = link.webMs { latency.append("web \(ms) ms") }
        if !latency.isEmpty { lines.append("- Round trips: " + latency.joined(separator: ", ")) }
        if let ip = link.publicIP {
            lines.append("- Public address \(ip)\(link.provider.map { " · \(clean($0, limit: 40))" } ?? "")\(link.country.map { " · \($0)" } ?? "")")
        }
        if let down = link.downMbps {
            var speed = "- Speed: \(format(down)) Mbit/s down"
            if let up = link.upMbps { speed += ", \(format(up)) Mbit/s up" }
            if let ms = link.latencyMs { speed += ", latency \(ms) ms" }
            if let jitter = link.jitterMs { speed += ", jitter \(jitter) ms" }
            lines.append(speed)
        }
        if let grade = link.bufferbloat {
            lines.append("- Latency under load: grade \(grade) (A is best, F means calls stutter while something downloads)")
        }
        if link.speedFailed, link.downMbps == nil { lines.append("- Speed test: did not finish") }
        let failed = [link.gatewayOk == false ? "router" : nil, link.dnsOk == false ? "DNS" : nil, link.webOk == false ? "web" : nil].compactMap { $0 }
        if !failed.isEmpty { lines.append("- Did not answer: " + failed.joined(separator: ", ")) }
        if let name = link.resolverName {
            lines.append("- DNS resolver the servers see: \(clean(name, limit: 40))\(link.resolverCountry.map { " · \($0)" } ?? "")")
        }
        if link.resolverLies == true {
            lines.append("- The DNS resolver answers for names that do not exist (redirects typos to its own page or filters)")
        }
        switch link.intercepted {
        case true?: lines.append("- HTTPS: certificates are re-signed on the way — interception by a proxy, an antivirus or an attacker")
        case false?: lines.append("- HTTPS: genuine certificates\(link.tlsReached.map { " on \($0) reference sites" } ?? ""), nothing re-signs them")
        case nil: break
        }
        if !link.exposedPorts.isEmpty {
            lines.append("- Ports of the public address reachable from the internet: \(link.exposedPorts.map(String.init).joined(separator: ", "))")
        } else if link.exposureChecked {
            lines.append("- No usual port of the public address answers from the internet")
        }
        if let access = link.access { lines.append("- Internet access: \(access)") }
        if !link.blocked.isEmpty {
            lines.append("- Often-blocked services that did not open: \(link.blocked.map { clean($0, limit: 40) }.joined(separator: ", "))")
        }
        if link.warp { lines.append("- Traffic leaves through Cloudflare WARP") }
        return lines + siteLines(s.sites)
    }

    /// The user's own sites, as they answered last. Two plain lists with an explicit "none": read as one run-on line
    /// ("a opens; b opens; …") a small model took the last site for one that failed.
    static func siteLines(_ sites: [AssistantSnapshot.SiteReach]) -> [String] {
        guard !sites.isEmpty else { return [] }
        let open = sites.filter(\.ok).map { site in clean(site.host, limit: 40) + (site.millis.map { " (\($0) ms)" } ?? "") }
        let closed = sites.filter { !$0.ok }.map { clean($0.host, limit: 40) }
        return [
            "The user's own sites, \(sites.count) tried:",
            "- Open: " + (open.isEmpty ? "none" : open.joined(separator: ", ")),
            "- Do not open: " + (closed.isEmpty ? "none" : closed.joined(separator: ", ")),
        ]
    }

    /// A delay that grows this much between two answering hops is where the path slows down.
    static let hopJumpMs = 80

    /// The hop where the delay jumps the most over the previous answering hop, when the jump is large.
    static func slowestHop(_ hops: [AssistantSnapshot.RouteHop]) -> (index: Int, jump: Int)? {
        var previous: Int?
        var best: (index: Int, jump: Int)?
        for (index, hop) in hops.enumerated() {
            guard let ms = hop.millis else { continue }
            if let before = previous {
                let jump = ms - before
                if jump >= hopJumpMs, jump > (best?.jump ?? 0) { best = (index, jump) }
            }
            previous = ms
        }
        return best
    }

    /// A traceroute, hop by hop: what a "trace" check returns and the deep analysis reads. `compact` keeps the
    /// outcome and the places along the way only: every hop costs the short summary seconds of reading.
    static func routeLines(_ route: AssistantSnapshot.Route, compact: Bool = false) -> [String] {
        let target = clean(route.target, limit: 60) + (route.address.flatMap { $0 == route.target ? nil : " (\($0))" } ?? "")
        guard !route.hops.isEmpty else {
            return ["Traceroute to \(target): " + (route.failure ?? (route.running ? "still running" : "no hops recorded"))]
        }
        let outcome = route.reached ? "reached in \(route.hops.count) hops"
            : route.silentTail > 0 ? "did not reach it; the last \(route.silentTail) hops did not answer (often a firewall dropping probes)"
            : "did not reach it"
        var lines = ["Traceroute to \(target): \(outcome)"]
        if let failure = route.failure { lines.append("- Note: \(failure)") }
        if compact {
            var places: [String] = []
            for place in route.hops.compactMap({ $0.place.map { clean($0, limit: 50) } }) where !places.contains(place) { places.append(place) }
            if !places.isEmpty { lines.append("- Places along the way: " + places.joined(separator: " → ")) }
            if let last = route.hops.last(where: { $0.millis != nil }), let ms = last.millis {
                lines.append("- Last answering hop: \(last.address ?? "?") · \(ms) ms")
            }
            if let slow = slowestHop(route.hops) {
                lines.append("- The delay grows most at hop \(slow.index + 1) (\(route.hops[slow.index].address ?? "?")): +\(slow.jump) ms")
            }
            return lines
        }
        for (index, hop) in route.hops.enumerated() {
            let parts = [hop.address ?? "*", hop.millis.map { "\($0) ms" }, hop.place.map { clean($0, limit: 50) }].compactMap { $0 }
            lines.append("\(index + 1). " + parts.joined(separator: " · "))
        }
        return lines
    }

    // MARK: - Helpers

    /// Names come from the air and from the network: a hostile one can carry instructions. Flatten it to one short line.
    static func clean(_ text: String, limit: Int = 40) -> String {
        let flat = text.unicodeScalars.map { $0.properties.generalCategory == .control || $0.properties.generalCategory == .format ? " " : String($0) }.joined()
        let single = flat.split(whereSeparator: \.isWhitespace).joined(separator: " ")
            .replacingOccurrences(of: "<", with: "‹").replacingOccurrences(of: ">", with: "›")
        return single.count > limit ? String(single.prefix(limit)) + "…" : single
    }

    private static func format(_ value: Double) -> String {
        value >= 100 ? String(Int(value.rounded())) : String(format: "%.1f", value)
    }

    static func clock(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: date)
    }
}
