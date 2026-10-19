import NetworkExtension
import Observation

/// Joins the iPhone itself to a Wi-Fi network with `NEHotspotConfiguration`.
///
/// This is the phone's own radio, not the sensor's "Join": the Cardputer joins
/// over the BLE protocol (`rememberWifi`), while this configures iOS directly.
///
/// iOS owns the passphrase once it is applied — Spectra does not store it or read
/// it back. `apply` can return before the phone has actually associated (the
/// network may be out of range, or the user may answer the system prompt later),
/// so a success here means the configuration was accepted, not that the link is up.
/// `currentSSID` reflects what the phone is actually on.
@Observable
final class WiFiJoiner {
    enum Status: Equatable {
        case idle
        case joining(ssid: String)
        case joined(ssid: String)
        case failed(ssid: String, message: String)
    }

    private(set) var status: Status = .idle
    /// The SSID the phone is currently joined to, as iOS reports it. Nil when it
    /// is on no network, or on one the app is not allowed to see.
    private(set) var currentSSID: String?

    /// True once the phone is, or has just been, put on `ssid`.
    func isOn(_ ssid: String) -> Bool {
        if currentSSID == ssid { return true }
        if case .joined(let joined) = status, joined == ssid { return true }
        return false
    }

    func join(ssid: String, passphrase: String?, isWEP: Bool, hidden: Bool) async {
        status = .joining(ssid: ssid)
        let config: NEHotspotConfiguration
        if let passphrase, !passphrase.isEmpty {
            config = NEHotspotConfiguration(ssid: ssid, passphrase: passphrase, isWEP: isWEP)
        } else {
            config = NEHotspotConfiguration(ssid: ssid)
        }
        config.hidden = hidden
        config.joinOnce = false

        do {
            try await NEHotspotConfigurationManager.shared.apply(config)
            status = .joined(ssid: ssid)
        } catch let error as NSError where Self.isAlreadyAssociated(error) {
            status = .joined(ssid: ssid)
        } catch {
            status = .failed(ssid: ssid, message: Self.describe(error as NSError))
            return
        }
        await refreshCurrent()
    }

    func forget(ssid: String) {
        NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: ssid)
        if currentSSID == ssid { currentSSID = nil }
        if case .joined(let joined) = status, joined == ssid { status = .idle }
    }

    func refreshCurrent() async {
        currentSSID = await Self.fetchCurrentSSID()
    }

    private static func fetchCurrentSSID() async -> String? {
        await withCheckedContinuation { continuation in
            NEHotspotNetwork.fetchCurrent { network in
                continuation.resume(returning: network?.ssid)
            }
        }
    }

    private static func isAlreadyAssociated(_ error: NSError) -> Bool {
        error.domain == NEHotspotConfigurationErrorDomain
            && error.code == NEHotspotConfigurationError.alreadyAssociated.rawValue
    }

    private static func describe(_ error: NSError) -> String {
        guard error.domain == NEHotspotConfigurationErrorDomain,
              let code = NEHotspotConfigurationError(rawValue: error.code) else {
            return error.localizedDescription
        }
        switch code {
        case .alreadyAssociated:
            return String(localized: "Already connected to this network.")
        case .userDenied:
            return String(localized: "You declined the Wi-Fi join prompt.")
        case .invalidWPAPassphrase, .invalidWEPPassphrase:
            return String(localized: "That password is not valid for this network.")
        default:
            return error.localizedDescription
        }
    }
}
