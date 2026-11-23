import Foundation

/// Live security findings computed from the latest Wi-Fi scan: a deauth attack,
/// a possible evil twin (one network name on several access points), or a
/// device that keeps sending (a camera or microphone streaming). Pure, so the
/// rules can be checked by tests.
enum Threats {
    /// Bytes heard for one AP in a sweep that look like a stream: about 320 kbit/s over the time the sensor spends on
    /// its channel. The bar used to be 120 000, meant for three seconds of listening; the sensor stays a quarter of a
    /// second on a channel, so only a feed of 4 Mbit/s and more was ever noticed.
    static let streamingByteThreshold: UInt32 = 10_000
    /// Sweeps it has to keep up. One busy sweep is a page being opened; a camera is there in every one.
    nonisolated static let streamingScans = 3
    /// How many sweeps are remembered, so a feed that stops is dropped within a few of them.
    nonisolated static let streamingScansKept = 4

    // The rule for one client, used when the sensor counts by client (firmware 0.11+). Same numbers as `Stream.h`,
    // where each is explained. Not calibrated on a real camera yet.
    nonisolated static let clientMinBytesPerSecond = 8_000
    nonisolated static let clientMinFrameBytes = 200
    nonisolated static let clientUpOverDown = 2
    nonisolated static let clientBusyPercent = 60

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
        /// The device that keeps sending, when the sensor can tell which one it is.
        var sender: ClientTraffic?
    }

    /// Networks that dropped out of range no longer count, so a finding goes
    /// away once later scans stop hearing what raised it.
    /// `lanName` gives the name a LAN scan knows a device by.
    static func assess(networks: [TrackedNetwork], deauths: [DeauthEvent], senders: [TrackedSender] = [],
                       lanName: (BSSID) -> String? = { _ in nil }) -> [Finding] {
        let present = networks.filter(\.isInRange)
        var findings: [Finding] = []
        findings += deauthFindings(deauths)
        findings += evilTwinFindings(present)
        findings += senderFindings(senders, networks: networks, lanName: lanName)
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

    private static func senderFindings(_ senders: [TrackedSender], networks: [TrackedNetwork], lanName: (BSSID) -> String?) -> [Finding] {
        senders.filter(\.isSteady).map { sender in
            let traffic = sender.traffic
            let network = networks.first { $0.id == traffic.bssid }
            let name = network.flatMap { $0.revealedSSID ?? $0.latest.ssid } ?? traffic.bssid.description
            return Finding(
                id: "sender-\(traffic.mac)",
                kind: .streaming,
                severity: .warning,
                title: String(localized: "A device keeps sending data"),
                detail: String(
                    format: String(localized: "%@ sends about %@ through %@ without pausing — it may be a camera or a microphone."),
                    senderName(traffic, lanName: lanName), Bitrate.text(bytesPerSecond: traffic.upBytesPerSecond), name
                ),
                networks: [traffic.bssid],
                sender: traffic
            )
        }
    }

    /// "Front door (Hikvision, 44:19:B6:10:20:30)", with the parts that are known.
    static func senderName(_ traffic: ClientTraffic, lanName: (BSSID) -> String?) -> String {
        let address = traffic.mac.description
        let named = lanName(traffic.mac)
        guard let title = named ?? traffic.maker else { return address }
        let rest = [named == nil || traffic.maker == named ? nil : traffic.maker, address].compactMap { $0 }
        return "\(title) (\(rest.joined(separator: ", ")))"
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
