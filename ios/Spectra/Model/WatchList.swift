import Foundation
import Observation

/// Identifiers the user is watching for — a BSSID or a Bluetooth device — so
/// the app can flag them the moment they are back on the air. Kept as opaque
/// strings (the BSSID text, or the peripheral id) and persisted across launches.
@Observable
final class WatchList {
    private(set) var ids: Set<String>
    private let defaults: UserDefaults
    private let storageKey = "watchList"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        ids = Set(defaults.stringArray(forKey: storageKey) ?? [])
    }

    func isWatched(_ id: String) -> Bool { ids.contains(id) }

    func toggle(_ id: String) {
        if ids.contains(id) { ids.remove(id) } else { ids.insert(id) }
        defaults.set(Array(ids), forKey: storageKey)
    }

    /// The watched networks that are in range right now, strongest first.
    static func present(_ ids: Set<String>, in networks: [TrackedNetwork]) -> [TrackedNetwork] {
        networks
            .filter { $0.isInRange && ids.contains($0.latest.bssid.description) }
            .sorted { $0.latest.rssi > $1.latest.rssi }
    }

    /// The watched Bluetooth devices heard recently, strongest first.
    static func present(_ ids: Set<String>, in devices: [BLEDevice], now: Date) -> [BLEDevice] {
        devices
            .filter { $0.isNearby(at: now) && ids.contains($0.id.uuidString) }
            .sorted { $0.rssi > $1.rssi }
    }
}
