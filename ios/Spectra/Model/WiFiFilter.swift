import Foundation

/// What the Wi-Fi list is narrowed to. Mirrors `passesWifiFilter` in the
/// Android `WifiModels.kt`: keep the two in step.
nonisolated struct WiFiFilter: Equatable, Sendable {
    var showOutOfRange = false
    var openOnly = false
    var knownOnly = false
    var band: Band?
    var query = ""
    /// Names of the networks a password is stored for.
    var knownSSIDs: Set<String> = []

    /// True when a chip is on, so an empty list is the filter's doing.
    var isNarrowing: Bool { openOnly || knownOnly || band != nil }

    func passes(_ network: TrackedNetwork) -> Bool {
        if !showOutOfRange && !network.isInRange { return false }
        if openOnly && network.latest.auth.strength != .none { return false }
        if knownOnly && !network.hasStoredPassword(in: knownSSIDs) { return false }
        if let band, network.latest.band != band { return false }
        let needle = query.trimmingCharacters(in: .whitespaces)
        if needle.isEmpty { return true }
        return (network.latest.ssid ?? "").localizedStandardContains(needle)
            || network.revealedSSID?.localizedStandardContains(needle) == true
            || network.id.description.localizedStandardContains(needle)
            || network.maker?.localizedStandardContains(needle) == true
    }

    /// A probing device is looking for a network, so the Open and Known
    /// filters hide the section.
    func passes(_ device: ProbingDevice) -> Bool {
        if openOnly || knownOnly { return false }
        if band == .fiveGHz, device.channel <= 14 { return false }
        if band == .twoPointFourGHz, device.channel > 14 { return false }
        let needle = query.trimmingCharacters(in: .whitespaces)
        if needle.isEmpty { return true }
        return device.mac.description.localizedStandardContains(needle)
            || device.manufacturer?.localizedStandardContains(needle) == true
            || device.ssids.contains { $0.localizedStandardContains(needle) }
    }
}

extension TrackedNetwork {
    nonisolated func hasStoredPassword(in ssids: Set<String>) -> Bool {
        guard let name = revealedSSID ?? latest.ssid else { return false }
        return ssids.contains(name)
    }

    /// The maker the access point names itself, or the one its address belongs to.
    nonisolated var maker: String? {
        if let named = vendorInfo?.manufacturer, !named.isEmpty { return named }
        return latest.bssid.isLocallyAdministered ? nil : OUIDatabase.lookup(bytes: latest.bssid.bytes)
    }
}
