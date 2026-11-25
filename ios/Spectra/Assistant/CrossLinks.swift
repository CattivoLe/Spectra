import Foundation

/*
 * Ties between the app's screens. Each scanner sees one side of a device: the LAN scan its address and ports, the
 * sensor its radio link, Bluetooth its name, the router's table the ports opened to it, the recorder the phone's
 * traffic to it. A card about one of them reads the others here, so "the camera" is one thing on all of them.
 *
 * A tie is made by hardware address or IP address. By name only when the name is the device's own and exactly one
 * device on each side carries it, and then the tie says "probably". Pure rules over the snapshot, checked by
 * CrossLinksTests. Mirrors CrossLinks.kt.
 */

/// One thing another screen knows about a card's subject.
nonisolated struct CrossLink: Equatable, Sendable {
    /// What the card shows; `nil` when only the model reads the tie.
    var finding: CardFinding?
    /// The tie in English, for the model.
    var line: String
}

nonisolated enum CrossLinks {
    /// The most ties a card lists as findings; the model reads them all.
    static let maxFindings = 4
    /// A first sighting older than this is worth telling: the device is not new here.
    static let knownAfter: TimeInterval = 24 * 60 * 60

    static let heading = "Ties to the app's other screens (matched by hardware address, IP address or name; \"probably\" marks a match by name alone):"

    /// The ties a card shows, most serious first.
    static func findings(_ links: [CrossLink]) -> [CardFinding] {
        let all = links.compactMap(\.finding)
        return Array(all.enumerated().sorted { ($0.element.risk, $0.offset) < ($1.element.risk, $1.offset) }.map(\.element).prefix(maxFindings))
    }

    /// The ties as the model reads them, or nothing.
    static func text(_ links: [CrossLink]) -> String {
        links.isEmpty ? "" : ([heading] + links.map { "- " + $0.line }).joined(separator: "\n")
    }

    /// The same, ready to follow a card's own data.
    static func block(_ links: [CrossLink]) -> String {
        links.isEmpty ? "" : "\n\n" + text(links)
    }

    // MARK: - One device of the local network

    static func host(_ s: AssistantSnapshot, _ host: AssistantSnapshot.Host, now: Date = .now) -> [CrossLink] {
        var links: [CrossLink] = []
        if let mac = host.mac, !host.isGateway {
            if let (ap, station) = station(s, mac: mac) {
                let name = label(ap)
                links.append(.init(
                    finding: .init(risk: .info, text: String(localized: "On Wi‑Fi “\(name)”, channel \(ap.channel); the sensor hears it at \(station.rssi) dBm.")),
                    line: "Wi-Fi: connected to \"\(name)\" (\(ap.bssid), channel \(ap.channel), \(ap.security)); the sensor hears it at \(station.rssi) dBm"))
            }
            if let sender = s.senders.first(where: { same($0.mac, mac) }) {
                links.append(.init(
                    finding: .init(risk: .warning, text: String(localized: "Sends data over Wi‑Fi the whole time (\(Bitrate.text(bytesPerSecond: sender.bytesPerSecond)) up), as a camera or microphone would.")),
                    line: "Sensor: it sends data over Wi-Fi the whole time, about \(kbit(sender.bytesPerSecond)) kbit/s up, as a camera or microphone would"))
            }
        }
        let targets = host.isGateway ? [] : forwardTargets(s).filter { $0.client == host.ip }
        if !targets.isEmpty {
            let list = targets.prefix(4).map { "\($0.proto) \($0.externalPort) → \($0.internalPort)" }.joined(separator: ", ")
            links.append(.init(
                finding: .init(risk: .critical, text: String(localized: "The router forwards ports from the internet to this device: \(list).")),
                line: "Router: forwards from the internet to this device (outside port → its port): \(list)"))
            let open = exposed(s).filter { port in targets.contains { $0.externalPort == port } }
            if !open.isEmpty {
                let ports = open.map(String.init).joined(separator: ", ")
                links.append(.init(
                    finding: .init(risk: .critical, text: String(localized: "Open from the internet right now: \(ports) — the router forwards it to this device.")),
                    line: "Connection check: port \(ports) of the public address answered from the internet, and the router forwards it to this device"))
            }
        }
        if let twin = bluetoothTwin(s, of: host) {
            let name = AssistantDigest.clean(twin.name, limit: 30)
            links.append(.init(
                finding: .init(risk: .info, text: String(localized: "Also heard over Bluetooth as “\(name)” (\(twin.rssi) dBm): probably the same device.")),
                line: "Bluetooth: probably the same device as \"\(name)\", heard at \(twin.rssi) dBm\(twin.tracker.map { ", a \(AssistantDigest.clean($0))" } ?? "")"))
        }
        if let peer = s.localPeers.first(where: { $0.address == host.ip }) {
            links.append(.init(
                finding: .init(risk: .info, text: peer.cleartext
                               ? String(localized: "The phone talked to it in the recorded traffic: \(peer.connections) connections, some unencrypted.")
                               : String(localized: "The phone talked to it in the recorded traffic: \(peer.connections) connections.")),
                line: "Recorded traffic of the phone: \(peer.connections) connections to this device\(peer.cleartext ? ", some as plain HTTP" : "")"))
        }
        if let talker = talker(s, [host.mac, host.ip]) {
            let name = AssistantDigest.clean(s.captureName ?? "", limit: 30)
            links.append(.init(
                finding: .init(risk: .info, text: String(localized: "In the last capture (\(name)): \(talker.packets) packets from or to it.")),
                line: "Last capture (\(name)): \(talker.packets) packets from or to this device"))
        }
        if !host.isNew, let since = known(host.firstSeen, now) { links.append(firstSeen(since)) }
        return links
    }

    // MARK: - The whole local network

    static func lan(_ s: AssistantSnapshot, now: Date = .now) -> [CrossLink] {
        var links: [CrossLink] = []
        let open = exposed(s)
        for target in forwardTargets(s).prefix(6) {
            let rule = "\(target.proto) \(target.externalPort)"
            let answers = open.contains(target.externalPort)
            let note = answers ? "; the connection check found this port answering from the internet" : ""
            if let host = s.hosts.first(where: { $0.ip == target.client }) {
                links.append(.init(
                    finding: answers ? .init(risk: .critical, text: String(localized: "\(who(host)): open from the internet right now on port \(String(target.externalPort)).")) : nil,
                    line: "Router: forwards \(rule) from the internet to \(who(host)), its port \(target.internalPort)\(note)"))
            } else {
                links.append(.init(
                    finding: .init(risk: .info, text: String(localized: "The router forwards port \(String(target.externalPort)) to \(target.client), which is not on the network now.")),
                    line: "Router: forwards \(rule) from the internet to \(target.client), which is not in the current host list\(note)"))
            }
        }
        for sender in s.senders {
            guard let host = s.hosts.first(where: { $0.mac.map { same($0, sender.mac) } == true }), !host.isGateway else { continue }
            links.append(.init(
                finding: .init(risk: .warning, text: String(localized: "\(who(host)) sends data over Wi‑Fi the whole time, as a camera or microphone would.")),
                line: "Sensor: \(who(host)) sends data over Wi-Fi the whole time, about \(kbit(sender.bytesPerSecond)) kbit/s up, as a camera or microphone would"))
        }
        if let quiet = quietStations(s) {
            let list = quiet.stations.prefix(5).map(\.mac).joined(separator: ", ")
            links.append(.init(
                finding: .init(risk: .info, text: String(localized: "The sensor hears \(quiet.extra) more devices on your Wi‑Fi than the network scan found: asleep, or kept apart from the phone.")),
                line: "Sensor: at least \(quiet.extra) devices are connected to the user's Wi-Fi that the LAN scan did not find (asleep, or isolated from the phone); stations with no matching host: \(list)"))
        }
        // Where each host is on the air, and its Bluetooth side: for the model only, the card would drown in them.
        var onAir = 0
        for host in s.hosts where !host.isGateway && onAir < 8 {
            guard let mac = host.mac, let (ap, station) = station(s, mac: mac) else { continue }
            onAir += 1
            links.append(.init(finding: nil, line: "Wi-Fi: \(who(host)) is connected to \"\(label(ap))\" (channel \(ap.channel)), \(station.rssi) dBm at the sensor"))
        }
        var twins = 0
        for host in s.hosts where twins < 4 {
            guard let twin = bluetoothTwin(s, of: host) else { continue }
            twins += 1
            links.append(.init(finding: nil, line: "Bluetooth: \(who(host)) is probably the same device as \"\(AssistantDigest.clean(twin.name, limit: 30))\", heard at \(twin.rssi) dBm"))
        }
        return links
    }

    // MARK: - One Wi‑Fi network

    static func wifi(_ s: AssistantSnapshot, _ ap: AssistantSnapshot.Wifi, now: Date = .now) -> [CrossLink] {
        var links: [CrossLink] = []
        if ap.isCurrent {
            if s.hosts.isEmpty {
                links.append(.init(finding: .init(risk: .info, text: String(localized: "Your phone is on this network.")),
                                   line: "Phone: connected to this network; its local network has not been scanned"))
            } else {
                links.append(.init(
                    finding: .init(risk: .info, text: String(localized: "Your phone is on this network: the network scan found \(s.hosts.count) devices on it.")),
                    line: "Local network: the phone is connected to this network; the LAN scan found \(s.hosts.count) devices on it"))
            }
            let risky = s.hosts.filter { !$0.isThisPhone && (!$0.portRisks.isEmpty || $0.trust == "suspicious") }
            if !risky.isEmpty {
                let names = risky.prefix(3).map(who).joined(separator: ", ") + (risky.count > 3 ? " +\(risky.count - 3)" : "")
                let detail = risky.prefix(4).map { host in
                    let why = host.portRisks.prefix(2).map { AssistantDigest.clean($0.title, limit: 60) } + (host.trust == "suspicious" ? ["marked suspicious by the user"] : [])
                    return "\(who(host)) — \(why.joined(separator: "; "))"
                }.joined(separator: " | ")
                links.append(.init(
                    finding: .init(risk: .warning, text: String(localized: "Devices on it that need attention: \(names).")),
                    line: "Local network: devices on this network with risky open ports or marked suspicious: \(detail)"))
            }
            if let link = s.link, onWifi(link) { links += connection(link) }
        }
        if let gateway = s.hosts.first(where: \.isGateway), let mac = gateway.mac, near(mac, ap.bssid) {
            links.append(.init(
                finding: .init(risk: .info, text: String(localized: "Most likely your router's access point: \(who(gateway)).")),
                line: "Local network: this access point is most likely the router \(who(gateway)): their hardware addresses differ only in the last part"))
        }
        let stations = ap.stations.compactMap { station in s.hosts.first { $0.mac.map { same($0, station.mac) } == true } }.filter { !$0.isGateway }
        if !stations.isEmpty {
            let names = stations.prefix(4).map(who).joined(separator: ", ") + (stations.count > 4 ? " +\(stations.count - 4)" : "")
            links.append(.init(
                finding: .init(risk: .info, text: String(localized: "Devices of your network on it: \(names).")),
                line: "Local network: devices connected to this access point that the LAN scan knows: \(stations.prefix(8).map(who).joined(separator: ", "))"))
        }
        if let talker = talker(s, [ap.bssid]) {
            let name = AssistantDigest.clean(s.captureName ?? "", limit: 30)
            links.append(.init(
                finding: .init(risk: .info, text: String(localized: "The last capture (\(name)) holds \(talker.packets) packets of this access point.")),
                line: "Last capture (\(name)): \(talker.packets) packets from or to this access point"))
        }
        if let twin = bluetoothTwin(s, ofNetwork: ap) {
            let name = AssistantDigest.clean(twin.name, limit: 30)
            links.append(.init(
                finding: .init(risk: .info, text: String(localized: "A Bluetooth device with the same name is nearby (“\(name)”, \(twin.rssi) dBm): probably the one that broadcasts this network.")),
                line: "Bluetooth: a device named \"\(name)\" is heard at \(twin.rssi) dBm: probably the device that broadcasts this network"))
        }
        if let since = known(ap.firstSeen, now) { links.append(firstSeen(since)) }
        return links
    }

    /// What the connection check found on the network the phone is on.
    private static func connection(_ link: AssistantSnapshot.Link) -> [CrossLink] {
        var links: [CrossLink] = []
        if link.status == "captive" {
            links.append(.init(finding: .init(risk: .warning, text: String(localized: "The network asks for a sign-in page before it lets traffic out.")),
                               line: "Connection check on this network: it asks for a sign-in page before it lets traffic out"))
        }
        if link.intercepted == true {
            links.append(.init(finding: .init(risk: .critical, text: String(localized: "HTTPS certificates look re-signed on this connection: possible interception.")),
                               line: "Connection check on this network: HTTPS certificates are re-signed on the way (possible interception)"))
        }
        if link.resolverLies == true {
            links.append(.init(finding: .init(risk: .warning, text: String(localized: "The DNS resolver answers for names that do not exist.")),
                               line: "Connection check on this network: the DNS resolver answers for names that do not exist"))
        }
        if !link.exposedPorts.isEmpty {
            let ports = link.exposedPorts.prefix(6).map(String.init).joined(separator: ", ")
            links.append(.init(finding: .init(risk: .critical, text: String(localized: "Ports reachable from the internet: \(ports).")),
                               line: "Connection check on this network: ports of its public address reachable from the internet: \(ports)"))
        }
        if link.status == "online", link.intercepted == false, link.resolverLies != true, link.exposedPorts.isEmpty {
            links.append(.init(finding: .init(risk: .ok, text: String(localized: "The connection check on it: the internet works, HTTPS is not intercepted.")),
                               line: "Connection check on this network: the internet works and HTTPS certificates are genuine"))
        }
        return links
    }

    // MARK: - One Bluetooth device

    static func bluetooth(_ s: AssistantSnapshot, _ device: AssistantSnapshot.Bluetooth, now: Date = .now) -> [CrossLink] {
        var links: [CrossLink] = []
        if let host = lanTwin(s, of: device) {
            let ports = host.portsScanned ? (host.ports.isEmpty ? "; no open ports found" : "; open ports: " + host.ports.prefix(6).joined(separator: ", ")) : ""
            if let risk = host.portRisks.first {
                let title = AssistantDigest.clean(risk.title, limit: 60)
                links.append(.init(
                    finding: .init(risk: .warning, text: String(localized: "Probably the same device as \(who(host)) on your local network, and it has a risky open port there: \(title).")),
                    line: "Local network: probably the same device as \(who(host))\(ports); risky there: \(title)"))
            } else {
                links.append(.init(
                    finding: .init(risk: .info, text: String(localized: "Probably the same device as \(who(host)) on your local network.")),
                    line: "Local network: probably the same device as \(who(host))\(host.maker.map { ", maker \(AssistantDigest.clean($0))" } ?? "")\(ports)"))
            }
        }
        if let ap = wifiTwin(s, of: device) {
            links.append(.init(
                finding: .init(risk: .info, text: String(localized: "A Wi‑Fi network with the same name is on air (\(ap.security)): probably this device's own access point.")),
                line: "Wi-Fi: a network with the same name is on air (\(ap.bssid), channel \(ap.channel), \(ap.security)): probably this device's own access point"))
        }
        if let since = known(device.firstSeen, now) { links.append(firstSeen(since)) }
        return links
    }

    // MARK: - The internet connection

    /// Where the ports that answer from the internet lead: the router's table names the device behind each.
    static func internet(_ s: AssistantSnapshot) -> [CrossLink] {
        let open = exposed(s)
        guard !open.isEmpty else { return [] }
        let targets = forwardTargets(s)
        let tableRead = s.hosts.first(where: \.isGateway)?.forwarding != nil
        var links: [CrossLink] = []
        for port in open.prefix(4) {
            if let target = targets.first(where: { $0.externalPort == port }) {
                let name = s.hosts.first { $0.ip == target.client }.map(who) ?? target.client
                links.append(.init(
                    finding: .init(risk: .critical, text: String(localized: "Port \(String(port)) open from the internet leads to \(name).")),
                    line: "Router: port \(port) that answers from the internet is forwarded to \(name), its port \(target.internalPort)"))
            } else if tableRead {
                links.append(.init(
                    finding: .init(risk: .info, text: String(localized: "Port \(String(port)) answers from the internet but is not in the router's UPnP table: the router itself answers, or the port is forwarded by hand in its settings.")),
                    line: "Router: port \(port) answers from the internet but is not in the router's UPnP table: the router itself answers, or the port is forwarded by hand in its settings"))
            } else {
                links.append(.init(finding: nil, line: "Router: port \(port) answers from the internet; the router's forwarding table has not been read, so the device behind it is unknown"))
            }
        }
        return links
    }

    /// Ports answer from outside and the router is known, but its table has not been asked: that check names the device.
    static func asksForwarding(_ s: AssistantSnapshot) -> Bool {
        guard !exposed(s).isEmpty, let gateway = s.hosts.first(where: \.isGateway) else { return false }
        return gateway.forwarding == nil
    }

    // MARK: - Matching

    static func same(_ a: String, _ b: String) -> Bool { a.caseInsensitiveCompare(b) == .orderedSame }

    /// The access point a device is connected to, by its hardware address.
    static func station(_ s: AssistantSnapshot, mac: String) -> (AssistantSnapshot.Wifi, AssistantSnapshot.Station)? {
        for ap in s.wifi {
            if let station = ap.stations.first(where: { same($0.mac, mac) }) { return (ap, station) }
        }
        return nil
    }

    static func forwardTargets(_ s: AssistantSnapshot) -> [AssistantSnapshot.ForwardTarget] {
        s.hosts.first(where: \.isGateway)?.forwarding?.targets ?? []
    }

    /// The check went out over Wi‑Fi: its public address is this network's, not the mobile operator's or a VPN's.
    static func onWifi(_ link: AssistantSnapshot.Link) -> Bool { link.via == nil || link.via == "wifi" }

    /// Ports of this network's public address that answered from the internet.
    static func exposed(_ s: AssistantSnapshot) -> [Int] {
        guard let link = s.link, onWifi(link) else { return [] }
        return link.exposedPorts
    }

    static func talker(_ s: AssistantSnapshot, _ addresses: [String?]) -> AssistantSnapshot.CaptureTalker? {
        let wanted = addresses.compactMap { $0 }
        return s.captureTalkers.first { talker in wanted.contains { same($0, talker.address) } }
    }

    /// Stations on the phone's own Wi‑Fi that match no host of the LAN scan, and how many of them cannot be the hosts
    /// the scan found without a hardware address (the phone itself is one). `nil` when nothing is left over.
    static func quietStations(_ s: AssistantSnapshot) -> (extra: Int, stations: [AssistantSnapshot.Station])? {
        guard !s.lanScanning else { return nil }
        let macs = Set(s.hosts.compactMap { $0.mac?.lowercased() })
        guard !macs.isEmpty else { return nil }
        let mine = s.wifi.filter { $0.isCurrent || (!$0.name.isEmpty && $0.name == s.wifiName) }
        var seen: Set<String> = []
        let unknown = mine.flatMap(\.stations).filter { seen.insert($0.mac.lowercased()).inserted && !macs.contains($0.mac.lowercased()) }
        let extra = unknown.count - s.hosts.filter { $0.mac == nil }.count
        return extra > 0 ? (extra, unknown) : nil
    }

    /// Two hardware addresses of one box: a router's access points differ from its LAN side in the last byte, and
    /// its extra networks carry the "made up locally" bit in the first.
    static func near(_ a: String, _ b: String) -> Bool {
        guard let x = bytes(a), let y = bytes(b) else { return false }
        return (x[0] & ~0x02) == (y[0] & ~0x02) && x[1...4] == y[1...4]
    }

    private static func bytes(_ text: String) -> [UInt8]? {
        let parts = text.split(separator: ":").compactMap { UInt8($0, radix: 16) }
        return parts.count == 6 ? parts : nil
    }

    /// Names every phone or laptop of a kind carries: no tie can rest on them.
    private static let generic: Set<String> = [
        "iphone", "ipad", "android", "macbook", "macbookpro", "macbookair", "imac", "airpods", "airpodspro", "applewatch",
        "appletv", "homepod", "galaxy", "pixel", "printer", "unknown", "device", "localhost", "router", "espressif",
    ]

    /// A name without case, spaces and punctuation: "Momentum-3" and "MOMENTUM 3" are one.
    static func nameKey(_ name: String) -> String { name.lowercased().filter { $0.isLetter || $0.isNumber } }

    static func namesMatch(_ a: String, _ b: String) -> Bool {
        let x = nameKey(a), y = nameKey(b)
        guard x.count >= 4, y.count >= 4, !generic.contains(x), !generic.contains(y) else { return false }
        if x == y { return true }
        let (short, long) = x.count <= y.count ? (x, y) : (y, x)
        return short.count >= 6 && long.contains(short)
    }

    /// The host has a name of its own: not its address, not just its maker.
    private static func named(_ host: AssistantSnapshot.Host) -> Bool {
        !host.isThisPhone && host.name != host.ip && nameKey(host.name) != nameKey(host.maker ?? "")
    }

    private static func best(_ devices: [AssistantSnapshot.Bluetooth]) -> AssistantSnapshot.Bluetooth? {
        devices.max { ($0.nearby ? 1 : 0, $0.rssi) < ($1.nearby ? 1 : 0, $1.rssi) }
    }

    /// The Bluetooth device that carries the host's name, when one name on each side points to the other alone.
    static func bluetoothTwin(_ s: AssistantSnapshot, of host: AssistantSnapshot.Host) -> AssistantSnapshot.Bluetooth? {
        guard named(host) else { return nil }
        let found = s.bluetooth.filter { $0.ownName && namesMatch($0.name, host.name) }
        guard let twin = best(found), Set(found.map { nameKey($0.name) }).count == 1 else { return nil }
        return s.hosts.filter { named($0) && namesMatch($0.name, twin.name) }.count == 1 ? twin : nil
    }

    static func lanTwin(_ s: AssistantSnapshot, of device: AssistantSnapshot.Bluetooth) -> AssistantSnapshot.Host? {
        guard device.ownName else { return nil }
        let found = s.hosts.filter { named($0) && namesMatch($0.name, device.name) }
        guard found.count == 1, let host = found.first else { return nil }
        return bluetoothTwin(s, of: host) != nil ? host : nil
    }

    /// The network in range named exactly as the Bluetooth device: a printer's or a camera's own access point.
    static func wifiTwin(_ s: AssistantSnapshot, of device: AssistantSnapshot.Bluetooth) -> AssistantSnapshot.Wifi? {
        guard device.ownName else { return nil }
        return s.wifi.filter { $0.inRange && !$0.isHidden && sameName($0.name, device.name) }.max { $0.rssi < $1.rssi }
    }

    static func bluetoothTwin(_ s: AssistantSnapshot, ofNetwork ap: AssistantSnapshot.Wifi) -> AssistantSnapshot.Bluetooth? {
        guard !ap.isHidden else { return nil }
        return best(s.bluetooth.filter { $0.ownName && sameName($0.name, ap.name) })
    }

    private static func sameName(_ a: String, _ b: String) -> Bool {
        let x = nameKey(a)
        return x.count >= 4 && !generic.contains(x) && x == nameKey(b)
    }

    // MARK: - Words

    static func who(_ host: AssistantSnapshot.Host) -> String {
        host.name == host.ip ? host.ip : "\(AssistantDigest.clean(host.name, limit: 30)) (\(host.ip))"
    }

    private static func label(_ ap: AssistantSnapshot.Wifi) -> String {
        ap.isHidden ? ap.bssid : AssistantDigest.clean(ap.name, limit: 30)
    }

    private static func kbit(_ bytesPerSecond: Int) -> Int { bytesPerSecond * 8 / 1000 }

    private static func known(_ date: Date?, _ now: Date) -> Date? {
        guard let date, now.timeIntervalSince(date) >= knownAfter else { return nil }
        return date
    }

    private static func firstSeen(_ date: Date) -> CrossLink {
        let day = date.formatted(.dateTime.day().month().year())
        return CrossLink(finding: .init(risk: .info, text: String(localized: "First seen on \(day).")),
                         line: "History: the app first met it on \(AssistantDigest.clock(date).prefix(10))")
    }
}
