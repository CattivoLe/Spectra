import Foundation

struct MACAddress: Hashable, Codable, Sendable, CustomStringConvertible {
    var bytes: [UInt8]

    init(bytes: [UInt8]) {
        self.bytes = bytes
    }

    /// Parses "aa:bb:cc:dd:ee:ff". iOS drops leading zeros ("0:1a:…"), so each part may be one digit.
    init?(string: String) {
        let parts = string.split(whereSeparator: { $0 == ":" || $0 == "-" })
        let bytes = parts.compactMap { UInt8($0, radix: 16) }
        guard parts.count == 6, bytes.count == 6 else { return nil }
        self.bytes = bytes
    }

    var description: String {
        bytes.map { String(format: "%02X", $0) }.joined(separator: ":")
    }

    var oui: String {
        bytes.prefix(3).map { String(format: "%02X", $0) }.joined(separator: ":")
    }

    var isRandomized: Bool { (bytes.first ?? 0) & 0x02 != 0 }

    var manufacturer: String? { OUIDatabase.lookup(bytes: bytes) }
}

struct DiscoveredPort: Identifiable, Equatable, Codable, Sendable {
    var number: Int
    var service: String
    var id: Int { number }

    nonisolated static func serviceName(for port: Int) -> String {
        namesByPort[port] ?? "TCP"
    }

    private nonisolated static let namesByPort: [Int: String] = {
        var names: [Int: String] = [:]
        for entry in commonPorts { names[entry.port] = entry.name }
        return names
    }()

    nonisolated(unsafe) static let commonPorts: [(port: Int, name: String)] = [
        (21, "FTP"), (22, "SSH"), (23, "Telnet"), (25, "SMTP"), (53, "DNS"),
        (80, "HTTP"), (81, "HTTP"), (110, "POP3"), (111, "RPC"), (135, "MS RPC"),
        (139, "NetBIOS"), (143, "IMAP"), (443, "HTTPS"), (445, "SMB"), (515, "LPD"),
        (548, "AFP"), (554, "RTSP"), (631, "IPP"), (993, "IMAPS"), (1400, "Sonos"),
        (8554, "RTSP"), (1935, "RTMP"),
        (1883, "MQTT"), (2049, "NFS"), (3000, "Dev"), (3283, "Apple Remote"),
        (3306, "MySQL"), (3389, "RDP"), (3689, "DAAP"), (5000, "UPnP"), (5001, "HTTPS"),
        (5357, "WSD"), (5432, "PostgreSQL"), (5555, "ADB"), (5900, "VNC"),
        (6379, "Redis"), (6466, "Android TV"), (6668, "Tuya"), (7000, "AirPlay"),
        (7547, "TR-069"), (8000, "HTTP"), (8001, "HTTP"), (8008, "Chromecast"),
        (8009, "Chromecast"), (8080, "HTTP"), (8081, "HTTP"), (8123, "Home Assistant"),
        (8443, "HTTPS"), (8888, "HTTP"), (9000, "Web"), (9090, "Web"), (9100, "Printer"),
        (10000, "Webmin"), (32400, "Plex"), (49152, "UPnP"), (62078, "iPhone Sync"),
    ]
}

@Observable
final class LANDevice: Identifiable {
    var ipAddress: String
    var macAddress: MACAddress?
    var hostnames: [String] {
        didSet { refreshPinnedName() }
    }
    var openPorts: [DiscoveredPort]
    /// Bonjour services this host advertised, with the model string when the TXT record had one.
    var services: [ServiceAdvertisement]
    var portsScanned: Bool
    var isGateway: Bool
    var isLocalDevice: Bool
    /// Never seen on this network before this scan. Set by `KnownHosts` once
    /// the scan has learned the host's names; carried across rescans in the
    /// same session so the mark stays until the list is cleared.
    var isNew = false
    /// What the user called this host, and what they think of it. Filled from
    /// `DeviceLabels`; the scanner does not learn these.
    var customName: String?
    var trust: DeviceTrust = .unknown
    var note: String?
    var profile: LANProfile {
        didSet { refreshPinnedName() }
    }
    /// Set while a manual port scan of this host is in progress, 0...1.
    var portScanFraction: Double?
    /// When every TCP port of this host was last checked, not only the common list.
    var allPortsScannedAt: Date?
    var allPortsScanned: Bool { allPortsScannedAt != nil }
    var firstSeen: Date
    var lastSeen: Date

    /// Best name learned for this host. Later probes can improve it, never clear it.
    private(set) var pinnedName: String?
    private var pinnedRank = 0

    var id: String { ipAddress }

    var displayName: String {
        if let customName { return customName }
        if let pinnedName, !Self.isWeakHostname(pinnedName) { return pinnedName }
        if let model = identity.model { return model }
        if let maker = identity.manufacturer ?? manufacturer { return maker }
        if let pinnedName { return pinnedName }
        if identity.operatingSystem == "Android" { return "Android" }
        return ipAddress
    }

    /// A hostname worth showing. Blank, "." and the address itself are not:
    /// those used to replace a real name with an empty title.
    nonisolated static func usefulHostname(_ name: String, ip: String) -> String? {
        guard let shown = presentable(name), shown.caseInsensitiveCompare(ip) != .orderedSame else { return nil }
        return shown
    }

    /// Carries a name across a rescan when the new device has not learned a better one.
    func preservePinnedName(from other: LANDevice?) {
        guard let other, other.pinnedRank > pinnedRank else { return }
        pinnedName = other.pinnedName
        pinnedRank = other.pinnedRank
    }

    private func refreshPinnedName() {
        consider(profile.upnp?.friendlyName, rank: 3)
        consider(profile.windows?.computerName, rank: 2)
        for name in hostnames {
            consider(name, rank: 2)
        }
    }

    private func consider(_ raw: String?, rank: Int) {
        guard let raw, let shown = Self.presentable(raw) else { return }
        guard shown.caseInsensitiveCompare(ipAddress) != .orderedSame else { return }
        let quality = Self.isWeakHostname(shown) ? rank - 1 : rank
        guard quality > pinnedRank || (quality == pinnedRank && quality >= 2 && shown != pinnedName) else { return }
        pinnedName = shown
        pinnedRank = quality
    }

    /// "Android" and "android-1a2b…" are real DHCP names, but a worse title than a model.
    nonisolated static func isWeakHostname(_ name: String) -> Bool {
        if isOpaqueIdentifier(name) { return true }
        let folded = name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
            .replacingOccurrences(of: "-", with: " ")
            .replacingOccurrences(of: "_", with: " ")
        return folded == "android" || folded == "windows" || folded.hasPrefix("android ")
    }

    /// Long hex strings and UUIDs are identifiers, not names: a TV that publishes
    /// "1b067baf05912b622b215c7a…" reads better as its model or maker.
    nonisolated static func isOpaqueIdentifier(_ name: String) -> Bool {
        let compact = name.filter { $0 != "-" && $0 != "_" }
        return compact.count >= 12 && compact.allSatisfy(\.isHexDigit)
    }

    /// Strips a trailing `.local` and rejects blanks, placeholders and addresses.
    private nonisolated static func presentable(_ raw: String) -> String? {
        guard var shown = LANIdentity.presentableName(raw) else { return nil }
        if shown.hasSuffix(".local") {
            shown = String(shown.dropLast(6)).trimmingCharacters(in: .whitespacesAndNewlines)
            guard let again = LANIdentity.presentableName(shown) else { return nil }
            shown = again
        }
        return shown
    }

    /// Camera and IoT vendors, plus anything that actually speaks RTSP.
    var notable: NotableDevice? {
        NotableDevice.classify(
            manufacturer: manufacturer,
            oui: macAddress?.oui,
            upnpType: profile.upnp?.deviceType,
            hasRTSP: !profile.rtsp.isEmpty
        )
    }

    var manufacturer: String? {
        guard let mac = macAddress, !mac.isRandomized else { return nil }
        return mac.manufacturer ?? SuspiciousOUI.names[mac.oui.uppercased()]
    }

    var identity: HostIdentity {
        let upnp = profile.upnp
        // The SMB / NetBIOS name is often all a Mac or PC with file sharing gives away.
        let found = HostIdentity.recognize(
            names: hostnames + [profile.windows?.computerName].compactMap { $0 },
            services: services,
            ports: openPorts.map(\.number),
            vendor: manufacturer,
            upnpName: upnp?.friendlyName,
            upnpModel: upnp?.model,
            upnpMaker: upnp?.manufacturer,
            upnpType: upnp?.deviceType
        )
        guard isLocalDevice else { return found }
        return found.fillingBlanks(from: HostIdentity.thisDevice())
    }

    var deviceIcon: String {
        if isGateway { return "wifi.router" }
        if isLocalDevice { return "iphone" }
        switch identity.operatingSystem {
        case "iOS": return "iphone"
        case "iPadOS": return "ipad"
        case "macOS": return "laptopcomputer"
        case "tvOS", "Android TV", "Google TV": return "appletv"
        case "watchOS": return "applewatch"
        case "Android": return looksLikeMediaPlayer ? "appletv" : "smartphone"
        case "Tizen", "webOS": return "tv"
        case "Windows": return "desktopcomputer"
        default: break
        }
        switch notable {
        case .camera: return "video"
        case .iot: return "sensor"
        case nil: break
        }
        let name = displayName.lowercased()
        if name.contains("mac") || name.contains("imac") || name.contains("book") { return "laptopcomputer" }
        if name.contains("iphone") { return "iphone" }
        if name.contains("ipad") { return "ipad" }
        if Self.isMediaPlayerName(name) { return "appletv" }
        if name.contains("apple") || name.contains("homepod") { return "homepod" }
        if name.contains("tv") { return "tv" }
        if name.contains("echo") || name.contains("alexa") || name.contains("sonos") || name.contains("eversolo") { return "hifispeaker" }
        if name.contains("print") || name.contains("canon") || name.contains("epson") || name.contains("brother") { return "printer" }
        if name.contains("pi") || name.contains("esp") { return "cpu" }
        if name.contains("xbox") || name.contains("playstation") || name.contains("ps5") || name.contains("nintendo") { return "gamecontroller" }
        if let mfr = manufacturer?.lowercased() {
            if mfr == "apple" { return "apple.logo" }
            if mfr.contains("samsung") || mfr.contains("google") || mfr.contains("xiaomi") || mfr.contains("huawei") || mfr.contains("oneplus") { return "smartphone" }
            if mfr.contains("tp-link") || mfr.contains("netgear") || mfr.contains("asus") || mfr.contains("d-link") || mfr.contains("ubiquiti") || mfr.contains("mikrotik") { return "wifi.router" }
            if mfr.contains("intel") || mfr.contains("dell") || mfr.contains("lenovo") || mfr.contains("hp") { return "desktopcomputer" }
            if mfr.contains("espressif") { return "cpu" }
            if mfr.contains("sonos") || mfr.contains("bose") { return "hifispeaker" }
            if mfr.contains("canon") || mfr.contains("epson") || mfr.contains("brother") || mfr.contains("hp") { return "printer" }
            if mfr.contains("sony") || mfr.contains("lg") { return "tv" }
            if mfr.contains("raspberry") { return "cpu" }
            if mfr.contains("synology") || mfr.contains("qnap") { return "externaldrive" }
        }
        return "desktopcomputer"
    }

    /// Android TV boxes are often labelled just Android, which would draw a phone.
    private var looksLikeMediaPlayer: Bool {
        if openPorts.contains(where: { $0.number == 6466 }) { return true }
        if Self.isMediaPlayerName(displayName) { return true }
        let title = displayName.lowercased()
        if title.contains(" tv") || title.hasSuffix("tv") { return true }
        let servicesBlob = services.map { "\($0.type) \($0.name)" }.joined(separator: " ").lowercased()
        return servicesBlob.contains("androidtv") || servicesBlob.contains("googlecast") || servicesBlob.contains("bravia")
    }

    private static func isMediaPlayerName(_ name: String) -> Bool {
        let folded = name.lowercased()
        return folded.contains("android tv") || folded.contains("google tv") || folded.contains("androidtv")
            || folded.contains("bravia") || folded.contains("chromecast") || folded.contains("shield")
            || folded.contains("roku") || folded.contains("apple tv") || folded.contains("appletv")
            || folded.contains("fire tv") || folded.contains("firestick") || folded.contains("fire stick")
            || folded.contains("mi box") || folded.contains("mibox") || folded.contains("media player")
    }

    init(
        ipAddress: String,
        macAddress: MACAddress? = nil,
        hostnames: [String] = [],
        openPorts: [DiscoveredPort] = [],
        services: [ServiceAdvertisement] = [],
        portsScanned: Bool = false,
        isGateway: Bool = false,
        isLocalDevice: Bool = false,
        isNew: Bool = false,
        profile: LANProfile = LANProfile(),
        firstSeen: Date = .now,
        lastSeen: Date = .now
    ) {
        self.ipAddress = ipAddress
        self.macAddress = macAddress
        self.hostnames = hostnames
        self.openPorts = openPorts
        self.services = services
        self.portsScanned = portsScanned
        self.isGateway = isGateway
        self.isLocalDevice = isLocalDevice
        self.isNew = isNew
        self.profile = profile
        self.firstSeen = firstSeen
        self.lastSeen = lastSeen
        self.pinnedName = nil
        self.pinnedRank = 0
        refreshPinnedName()
    }
}
