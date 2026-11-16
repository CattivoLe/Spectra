import Foundation

/// A probe request captured in promiscuous mode: a device broadcasting which
/// SSID it wants to join. One device can emit multiple probes for different
/// SSIDs, revealing its connection history.
nonisolated struct ProbeRequest: Identifiable, Equatable, Sendable {
    var mac: BSSID
    var rssi: Int
    /// The SSID the device is looking for. Nil for broadcast (wildcard) probes.
    var ssid: String?
    var channel: Int

    var id: String { "\(mac)-\(ssid ?? "")" }

    var manufacturer: String? {
        mac.isLocallyAdministered ? nil : OUIDatabase.lookup(bytes: mac.bytes)
    }

    var displayName: String {
        manufacturer ?? mac.description
    }
}

/// Probe requests grouped by the device that sent them.
struct ProbingDevice: Identifiable, Equatable, Codable {
    var mac: BSSID
    var rssi: Int
    /// SSIDs the device is looking for (empty strings filtered out).
    var ssids: [String]
    var channel: Int

    var id: BSSID { mac }

    var manufacturer: String? {
        mac.isLocallyAdministered ? nil : OUIDatabase.lookup(bytes: mac.bytes)
    }

    var displayName: String {
        manufacturer ?? mac.description
    }
}
