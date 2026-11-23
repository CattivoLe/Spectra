import NetworkExtension
import Observation

/// Joins the iPhone itself to a Wi-Fi network with `NEHotspotConfiguration`.
///
/// This is the phone's own radio, not the sensor's "Join": the Cardputer joins
/// over the BLE protocol (`rememberWifi`), while this configures iOS directly.
///
/// What `apply` answers is not whether the phone is on the network: it returns
/// without an error once the configuration is accepted, even when iOS then shows
/// its own "Unable to join" alert, and it can fail although iOS joins a moment
/// later. So neither answer is shown as it is: the phone's current network is
/// watched for a while, and only that says connected or not. Once the phone is
/// seen on the network, `onJoined` gets the password that worked.
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
    /// The access point the phone is associated with, when iOS reports it.
    private(set) var currentBSSID: BSSID?

    /// Called with the SSID and password once the phone is seen on a network it was asked to join.
    @ObservationIgnored var onJoined: ((String, String) -> Void)?

    /// True when the phone is on `ssid`, as iOS reports it.
    func isOn(_ ssid: String) -> Bool { currentSSID == ssid }

    /// True when `network` is the access point the phone is on: by BSSID when
    /// iOS reports one, otherwise by name.
    func isCurrent(_ network: TrackedNetwork) -> Bool {
        if let currentBSSID { return currentBSSID == network.id }
        guard let currentSSID, let name = network.revealedSSID ?? network.latest.ssid else { return false }
        return currentSSID == name
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

        var refusal: NSError?
        do {
            try await NEHotspotConfigurationManager.shared.apply(config)
        } catch {
            let error = error as NSError
            if Self.isFinal(error) {
                status = .failed(ssid: ssid, message: Self.describe(error))
                return
            }
            if !Self.isAlreadyAssociated(error) { refusal = error }
        }
        // Association lags the accepted configuration, and iOS may retry on its own after saying it could not join.
        for _ in 0 ..< 15 {
            await refreshCurrent()
            if currentSSID == ssid { break }
            try? await Task.sleep(for: .seconds(1))
        }
        // A join of another network was started meanwhile: its status is the one to show.
        guard status == .joining(ssid: ssid) else { return }
        if currentSSID == ssid {
            status = .joined(ssid: ssid)
            // An open network is kept too, with no password: the Saved networks list shows what the phone joined.
            onJoined?(ssid, passphrase ?? "")
        } else {
            status = .failed(ssid: ssid, message: refusal.map(Self.describe)
                ?? String(localized: "The iPhone did not join. The password may be wrong, or the network too weak."))
        }
    }

    /// The failure to show for `ssid`: none once the phone is on it after all.
    func failure(for ssid: String) -> String? {
        guard currentSSID != ssid, case .failed(let failed, let message) = status, failed == ssid else { return nil }
        return message
    }

    func forget(ssid: String) {
        NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: ssid)
        if currentSSID == ssid {
            currentSSID = nil
            currentBSSID = nil
        }
        if case .joined(let joined) = status, joined == ssid { status = .idle }
    }

    func refreshCurrent() async {
        let current = await Self.fetchCurrent()
        currentSSID = current?.ssid
        currentBSSID = current.flatMap { BSSID(string: $0.bssid) }
    }

    private static func fetchCurrent() async -> (ssid: String, bssid: String)? {
        await withCheckedContinuation { continuation in
            NEHotspotNetwork.fetchCurrent { network in
                continuation.resume(returning: network.map { ($0.ssid, $0.bssid) })
            }
        }
    }

    /// Errors after which iOS certainly will not join by itself: the person declined, or the password cannot be one.
    static func isFinal(_ error: NSError) -> Bool {
        guard error.domain == NEHotspotConfigurationErrorDomain,
              let code = NEHotspotConfigurationError(rawValue: error.code) else { return false }
        switch code {
        case .userDenied, .invalid, .invalidSSID, .invalidSSIDPrefix, .invalidWPAPassphrase, .invalidWEPPassphrase,
             .invalidEAPSettings, .invalidHS20Settings, .invalidHS20DomainName, .applicationIsNotInForeground:
            return true
        default:
            return false
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
