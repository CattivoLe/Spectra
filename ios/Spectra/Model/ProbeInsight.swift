import Foundation

/// What a device's probe requests reveal about it. A probe names a network the
/// device has joined before, so the list is a small fingerprint of where it has
/// been — and any name it probes for, it will auto-join if an access point
/// claims it. Pure, for tests.
enum ProbeInsight {
    /// Probing for this many distinct networks makes a device easy to single out.
    static let trackableThreshold = 3

    struct Result: Equatable {
        /// Distinct named networks the device is looking for.
        var rememberedCount: Int
        /// Remembered networks that are also on the air here, sorted.
        var presentHere: [String]
        /// Remembered networks present here as an OPEN access point: the device
        /// will auto-join, and the name is trivial to impersonate.
        var autoJoinOpen: [String]
        /// Enough remembered networks to fingerprint the device across places.
        var trackable: Bool
    }

    static func assess(ssids: [String], networksInRange: [AccessPointReading]) -> Result {
        let remembered = Set(ssids.filter { !$0.isEmpty })
        var present: [String] = []
        var autoJoin: [String] = []
        for ssid in remembered {
            let matches = networksInRange.filter {
                $0.ssid?.caseInsensitiveCompare(ssid) == .orderedSame
            }
            guard !matches.isEmpty else { continue }
            present.append(ssid)
            if matches.contains(where: { $0.auth == .open }) {
                autoJoin.append(ssid)
            }
        }
        return Result(
            rememberedCount: remembered.count,
            presentHere: present.sorted(),
            autoJoinOpen: autoJoin.sorted(),
            trackable: remembered.count >= trackableThreshold
        )
    }
}
