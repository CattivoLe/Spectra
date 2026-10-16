import Foundation

/// A Wi-Fi client (station) seen through promiscuous-mode frame capture on the
/// Cardputer. A client is either associated with a specific AP (identified by
/// its BSSID) or just sending probe requests looking for networks.
nonisolated struct WiFiClient: Identifiable, Equatable, Sendable {
    /// The client device's MAC address.
    var mac: BSSID
    /// The BSSID of the AP this client is associated with, or nil when the
    /// client was only seen probing (sending probe requests).
    var apBSSID: BSSID?
    /// Signal strength in dBm. When the same client is seen multiple times,
    /// this is the strongest reading.
    var rssi: Int
    /// True when the client is known to be associated (connected) to an AP,
    /// as opposed to just sending probe requests nearby.
    var isAssociated: Bool

    var id: BSSID { mac }

    /// The OUI manufacturer, when the MAC is not locally administered.
    var manufacturer: String? {
        mac.isLocallyAdministered ? nil : OUIDatabase.lookup(mac.oui)
    }

    /// A short display name: manufacturer when available, MAC otherwise.
    var displayName: String {
        manufacturer ?? mac.description
    }
}
