import Foundation

/// Live security findings computed from the latest Wi-Fi scan: a deauth attack,
/// a possible evil twin (one network name on several access points), or a
/// device generating steady traffic (a camera or microphone streaming). Pure,
/// so the rules can be checked by tests.
enum Threats {
    /// Bytes heard for one AP in a sweep that look like a stream: about 320 kbit/s over the time the sensor spends on
    /// its channel. The bar used to be 120 000, meant for three seconds of listening; the sensor stays a quarter of a
    /// second on a channel, so only a feed of 4 Mbit/s and more was ever noticed.
    static let streamingByteThreshold: UInt32 = 10_000
    /// Sweeps it has to keep up. One busy sweep is a page being opened; a camera is there in every one.
    static let streamingScans = 3
    /// How many sweeps are remembered, so a feed that stops is dropped within a few of them.
    static let streamingScansKept = 4

    enum Severity: Int, Comparable {
        case warning, critical
        static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }
    }

    enum Kind: String { case deauthAttack, evilTwin, streaming }

    struct Finding: Identifiable, Equatable {
        var id: String
        var kind: Kind
        var severity: Severity
        var title: String
        var detail: String
        /// The access points the finding is about, strongest first.
        var networks: [BSSID] = []
        /// The frames behind a deauth finding.
        var deauth: DeauthEvent?
    }

    /// Networks that dropped out of range no longer count, so a finding goes
    /// away once later scans stop hearing what raised it.
    static func assess(networks: [TrackedNetwork], deauths: [DeauthEvent]) -> [Finding] {
        let present = networks.filter(\.isInRange)
        var findings: [Finding] = []
        findings += deauthFindings(deauths)
        findings += evilTwinFindings(present)
        findings += streamingFindings(present)
        return findings.sorted { $0.severity > $1.severity }
    }

    private static func deauthFindings(_ deauths: [DeauthEvent]) -> [Finding] {
        deauths.filter { $0.severity >= .warning }.map { event in
            Finding(
                id: "deauth-\(event.id)",
                kind: .deauthAttack,
                severity: event.severity == .critical ? .critical : .warning,
                title: event.isBroadcast ? String(localized: "Deauth attack") : String(localized: "Deauth frames"),
                detail: String(
                    format: String(localized: "%d frames on channel %d — devices are being forced off %@."),
                    event.count, event.channel, event.isBroadcast ? String(localized: "the network") : event.bssid.description
                ),
                networks: [event.bssid],
                deauth: event
            )
        }
    }

    /// One SSID on two or more BSSIDs is normal for mesh and enterprise Wi-Fi,
    /// so only flag it when the copies differ in security or in maker — the
    /// shape of an impostor standing up a lookalike network.
    private static func evilTwinFindings(_ networks: [TrackedNetwork]) -> [Finding] {
        let named = networks.compactMap { network -> (String, TrackedNetwork)? in
            guard let ssid = network.revealedSSID ?? network.latest.ssid, !ssid.isEmpty else { return nil }
            return (ssid, network)
        }
        let groups = Dictionary(grouping: named, by: { $0.0 })
        var findings: [Finding] = []
        for (ssid, pairs) in groups where pairs.count >= 2 {
            let members = pairs.map { $0.1 }
            let auths = Set(members.map { $0.latest.auth })
            let ouis = Set(members.map { $0.latest.bssid.oui })
            guard auths.count > 1 || ouis.count > 1 else { continue }
            findings.append(Finding(
                id: "twin-\(ssid)",
                kind: .evilTwin,
                severity: auths.count > 1 ? .critical : .warning,
                title: String(localized: "Possible evil twin"),
                detail: String(
                    format: String(localized: "“%@” is on %d access points with %@."),
                    ssid, members.count,
                    auths.count > 1 ? String(localized: "different security") : String(localized: "different makers")
                ),
                networks: members.sorted { $0.latest.rssi > $1.latest.rssi }.map(\.id)
            ))
        }
        return findings
    }

    private static func streamingFindings(_ networks: [TrackedNetwork]) -> [Finding] {
        networks.compactMap { network in
            guard network.movesDataSteadily else { return nil }
            let name = network.revealedSSID ?? network.latest.ssid ?? network.latest.bssid.description
            return Finding(
                id: "stream-\(network.latest.bssid)",
                kind: .streaming,
                severity: .warning,
                title: String(localized: "Steady traffic"),
                detail: String(
                    format: String(localized: "%@ is moving data continuously — a camera or microphone may be streaming."),
                    name
                ),
                networks: [network.id]
            )
        }
    }
}
