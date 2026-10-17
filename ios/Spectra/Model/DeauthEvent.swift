import Foundation

/// A deauthentication or disassociation frame captured by the sensor.
/// A burst of these, especially to a broadcast address, indicates a deauth
/// attack — someone is kicking devices off the network.
nonisolated struct DeauthEvent: Identifiable, Equatable, Codable, Sendable {
    var source: BSSID
    var target: BSSID
    var bssid: BSSID
    var reason: UInt16
    var rssi: Int
    var channel: Int
    var count: Int

    var id: String { "\(source)-\(target)-\(bssid)-\(reason)" }

    var isBroadcast: Bool { target.bytes.allSatisfy { $0 == 0xFF } }

    var severity: Severity {
        if isBroadcast && count >= 3 { return .critical }
        if count >= 10 { return .critical }
        if isBroadcast || count >= 3 { return .warning }
        return .info
    }

    var reasonDescription: String {
        switch reason {
        case 1: "Unspecified"
        case 2: "Auth no longer valid"
        case 3: "Leaving / deassociated"
        case 4: "Inactivity"
        case 5: "AP full"
        case 6: "Class 2 from non-auth"
        case 7: "Class 3 from non-assoc"
        case 8: "Leaving / disassociated"
        case 9: "Not authenticated"
        case 15: "4-way handshake timeout"
        case 16: "Group key handshake timeout"
        default: "Code \(reason)"
        }
    }

    enum Severity: Comparable {
        case info, warning, critical
    }
}
