import CoreLocation
import Foundation
import NetworkExtension
import Observation

/// The name of the joined Wi-Fi, when the user lets the app read it. iOS ties
/// the network name to location access, so this is opt-in: without it the
/// scanner tells networks apart by the router's address alone.
@Observable
final class WiFiInfo: NSObject, CLLocationManagerDelegate {
    struct Network: Equatable {
        var ssid: String
        var bssid: String?
        /// False on a network without a password.
        var isSecure: Bool
    }

    private(set) var current: Network?
    private(set) var authorization: CLAuthorizationStatus
    /// The user asked for the Wi-Fi name to be used. Persisted.
    private(set) var enabled: Bool

    private let manager = CLLocationManager()
    private let defaults: UserDefaults
    private let storageKey = "useWiFiName"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        enabled = defaults.bool(forKey: storageKey)
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
    }

    var isAuthorized: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
    }

    /// Turned on, but iOS will not hand over the name until location access is granted.
    var needsLocationAccess: Bool {
        enabled && (authorization == .denied || authorization == .restricted)
    }

    /// Turns the feature on and asks for location access the first time.
    func enable() {
        enabled = true
        defaults.set(true, forKey: storageKey)
        if authorization == .notDetermined {
            manager.requestWhenInUseAuthorization()
        } else {
            Task { await refresh() }
        }
    }

    func disable() {
        enabled = false
        defaults.set(false, forKey: storageKey)
        current = nil
    }

    func setEnabled(_ on: Bool) {
        if on { enable() } else { disable() }
    }

    /// Reads the current network. Nil when off, not allowed, or not on Wi-Fi.
    func refresh() async {
        guard enabled, isAuthorized else {
            current = nil
            return
        }
        #if targetEnvironment(simulator)
        // The simulator has no Wi-Fi to ask about; an open demo network lets
        // the warning be seen.
        current = Network(ssid: "Home Wi-Fi", bssid: nil, isSecure: false)
        #else
        current = await withCheckedContinuation { (continuation: CheckedContinuation<Network?, Never>) in
            NEHotspotNetwork.fetchCurrent { network in
                continuation.resume(returning: network.map {
                    Network(ssid: $0.ssid, bssid: $0.bssid.isEmpty ? nil : $0.bssid,
                            isSecure: Self.isSecure($0.securityType))
                })
            }
        }
        #endif
    }

    /// `NEHotspotNetwork.isSecure` is filled in only for a hotspot helper; from
    /// `fetchCurrent` it is always false, so every network looked open. The
    /// security type is what `fetchCurrent` really reports. Unknown counts as
    /// secure: no warning without proof.
    nonisolated static func isSecure(_ type: NEHotspotNetworkSecurityType) -> Bool {
        type != .open
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            authorization = status
            await refresh()
        }
    }
}
