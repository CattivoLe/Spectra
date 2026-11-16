import Foundation

/// How much an open service should worry the owner of the network.
nonisolated enum RiskLevel: Int, Comparable, Sendable {
    /// Worth knowing, nothing to fix: SSH on a Mac, a printer's queue.
    case info
    /// Unencrypted or unnecessary, but not a way in by itself.
    case low
    /// A service that is attacked for its passwords, or that hands out data.
    case medium
    /// Clear-text logins, debug shells, services known for no password at all.
    case high

    static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }

    var label: String {
        switch self {
        case .high: String(localized: "High risk")
        case .medium: String(localized: "Needs attention")
        case .low: String(localized: "Low risk")
        case .info: String(localized: "Note")
        }
    }
}

/// One thing the port scan says about a host, with the ports behind it.
nonisolated struct RiskFinding: Identifiable, Equatable, Sendable {
    var level: RiskLevel
    var title: String
    var detail: String
    var ports: [Int]
    var id: String { title }
}

/// Reads an open-port list the way a security review would: which services
/// send passwords in the clear, which are known for shipping without one,
/// and which merely deserve a glance. Pure: nothing is probed or logged in.
nonisolated enum PortRisk {
    private struct Rule {
        var ports: Set<Int>
        var level: RiskLevel
        var title: String
        var detail: String
    }

    private static let rules: [Rule] = [
        Rule(ports: [23], level: .high,
             title: String(localized: "Telnet"),
             detail: String(localized: "Logins cross the network in clear text. Telnet is the first door botnets try, usually with factory passwords.")),
        Rule(ports: [21], level: .high,
             title: String(localized: "FTP"),
             detail: String(localized: "Passwords and files are sent unencrypted; anyone on the Wi-Fi can read them.")),
        Rule(ports: [7547], level: .high,
             title: String(localized: "TR-069 remote management"),
             detail: String(localized: "The port an internet provider uses to manage routers. Flaws in it have let worms in; it should not answer on the Wi-Fi.")),
        Rule(ports: [5555], level: .high,
             title: String(localized: "Android debugging (ADB)"),
             detail: String(localized: "Network debugging gives a shell on the device without a password. Turn it off when not in use.")),
        Rule(ports: [6379], level: .high,
             title: String(localized: "Redis"),
             detail: String(localized: "Redis accepts commands without a password unless it was configured with one.")),
        Rule(ports: [3389], level: .medium,
             title: String(localized: "Remote Desktop"),
             detail: String(localized: "RDP is constantly tried with guessed passwords. Make sure the account has a strong one.")),
        Rule(ports: [5900], level: .medium,
             title: String(localized: "VNC screen sharing"),
             detail: String(localized: "VNC often runs with a weak password or none, and its traffic is not encrypted.")),
        Rule(ports: [3283], level: .medium,
             title: String(localized: "Apple Remote Desktop"),
             detail: String(localized: "The Mac can be controlled from the network. Fine if you set it up; otherwise turn off Remote Management.")),
        Rule(ports: [139, 445], level: .medium,
             title: String(localized: "File sharing (SMB)"),
             detail: String(localized: "Shared folders are reachable from the Wi-Fi. Check that each share asks for a password.")),
        Rule(ports: [2049], level: .medium,
             title: String(localized: "NFS shares"),
             detail: String(localized: "NFS trusts the client's word about who is asking. Anyone on the network can usually mount an unrestricted export.")),
        Rule(ports: [1883], level: .medium,
             title: String(localized: "MQTT broker"),
             detail: String(localized: "Smart-home messages pass through here. Many brokers accept connections without a login, which lets anyone read sensors and send commands.")),
        Rule(ports: [3306, 5432], level: .medium,
             title: String(localized: "Database"),
             detail: String(localized: "A database answers on the network. It should listen on localhost only unless other machines need it.")),
        Rule(ports: [554, 8554], level: .medium,
             title: String(localized: "Video stream (RTSP)"),
             detail: String(localized: "The camera's stream is reachable from the Wi-Fi. Many cameras ship with a default password; change it.")),
        Rule(ports: [10000], level: .medium,
             title: String(localized: "Webmin"),
             detail: String(localized: "A server administration panel. Keep it updated and behind a strong password.")),
        Rule(ports: [111, 135], level: .low,
             title: String(localized: "RPC services"),
             detail: String(localized: "Legacy remote-procedure ports. Rarely needed on a home network and often a hint that other old services are on.")),
        Rule(ports: [1900, 5000, 49152], level: .low,
             title: String(localized: "UPnP"),
             detail: String(localized: "UPnP lets any device on the Wi-Fi reconfigure this one without a login. On a router it can open ports to the internet.")),
        Rule(ports: [25, 110, 143], level: .low,
             title: String(localized: "Unencrypted mail"),
             detail: String(localized: "Mail protocols without TLS. Passwords may cross the network in the clear.")),
        Rule(ports: [80, 81, 8000, 8001, 8008, 8080, 8081, 8888, 9000, 9090], level: .low,
             title: String(localized: "Web page without encryption"),
             detail: String(localized: "Anything typed into this page, like a password, crosses the Wi-Fi unencrypted.")),
        Rule(ports: [3000], level: .low,
             title: String(localized: "Development server"),
             detail: String(localized: "A developer tool is listening on the network. Such servers are not built to be reachable by others.")),
        Rule(ports: [9100, 515], level: .low,
             title: String(localized: "Open print queue"),
             detail: String(localized: "Anyone on the Wi-Fi can send print jobs.")),
        Rule(ports: [22], level: .info,
             title: String(localized: "SSH"),
             detail: String(localized: "Remote login is on. Safe with a strong password or keys; a factory password is not.")),
    ]

    /// The level of one open port, or nil when it is nothing to note.
    static func level(forPort port: Int) -> RiskLevel? {
        rules.first { $0.ports.contains(port) }?.level
    }

    /// What the host's open ports and probes say, worst first. Empty when the
    /// ports have not been scanned or none of them is of note.
    static func findings(for device: LANDevice) -> [RiskFinding] {
        guard device.portsScanned else { return [] }
        let open = Set(device.openPorts.map(\.number))
        var findings: [RiskFinding] = []

        let inbound = device.profile.inbound.filter(\.enabled)
        if !inbound.isEmpty {
            let list = inbound.map { "\($0.proto) \($0.externalPort)" }.formatted(.list(type: .and))
            findings.append(RiskFinding(
                level: .high,
                title: String(localized: "Reachable from the internet"),
                detail: String(localized: "The router forwards \(list) from the internet to this device. Anyone online can try those ports, not just people on the Wi-Fi."),
                ports: Array(Set(inbound.map(\.internalPort))).sorted()
            ))
        }
        if device.isGateway, let table = device.profile.portForwarding, table.supported {
            let active = table.mappings.filter(\.enabled)
            if !active.isEmpty {
                findings.append(RiskFinding(
                    level: .medium,
                    title: String(localized: "Port forwarding is on"),
                    detail: String(localized: "\(active.count) rules lead from the internet to devices on this network. Each one is a door; keep only the ones you set up yourself."),
                    ports: []
                ))
            }
            findings.append(RiskFinding(
                level: .low,
                title: String(localized: "UPnP port control"),
                detail: String(localized: "Any device on the Wi-Fi can ask the router to open a port to the internet, without a login. Turn UPnP off in the router unless a game console or similar needs it."),
                ports: []
            ))
        }

        if let windows = device.profile.windows, windows.guestSession, !windows.shares.isEmpty {
            findings.append(RiskFinding(
                level: .high,
                title: String(localized: "Shares open to guests"),
                detail: String(localized: "\(windows.shares.formatted(.list(type: .and))) accepted a connection without a password. Anyone on the Wi-Fi can read them."),
                ports: [445].filter(open.contains)
            ))
        }

        for rule in rules {
            let hit = rule.ports.intersection(open).sorted()
            guard !hit.isEmpty else { continue }
            // The shares finding above already covers SMB on that host.
            if rule.ports.contains(445), findings.contains(where: { $0.title == String(localized: "Shares open to guests") }) { continue }
            if rule.ports.contains(1900), findings.contains(where: { $0.title == String(localized: "UPnP port control") }) { continue }
            var finding = RiskFinding(level: rule.level, title: rule.title, detail: rule.detail, ports: hit)
            if device.isGateway, rule.ports.contains(80) {
                finding.title = String(localized: "Router admin page without encryption")
                finding.detail = String(localized: "The router's password crosses the Wi-Fi in the clear when you log in. Use the https address if the router offers one.")
            } else if device.isGateway, rule.ports.contains(23) {
                finding.detail = String(localized: "Telnet on a router is how most router botnets got in. Turn it off in the router's settings.")
            }
            findings.append(finding)
        }

        return findings.sorted { a, b in
            if a.level != b.level { return a.level > b.level }
            return (a.ports.first ?? 0) < (b.ports.first ?? 0)
        }
    }

    /// The worst level among the host's findings; nil when there is none.
    static func level(for device: LANDevice) -> RiskLevel? {
        findings(for: device).map(\.level).max()
    }
}

extension LANDevice {
    var riskFindings: [RiskFinding] { PortRisk.findings(for: self) }
    var riskLevel: RiskLevel? { PortRisk.level(for: self) }
    /// Medium or worse: the host gets a mark in the list and counts in the summary.
    var needsAttention: Bool { (riskLevel ?? .info) >= .medium }
}
