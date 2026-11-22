import NetworkExtension
import Observation

/// Joins the iPhone itself to a Wi-Fi network with `NEHotspotConfiguration`.
///
/// This is the phone's own radio, not the sensor's "Join": the Cardputer joins
/// over the BLE protocol (`rememberWifi`), while this configures iOS directly.
///
/// `apply` can return before the phone has actually associated (the network
/// may be out of range, or the user may answer the system prompt later), so a
/// success here means the configuration was accepted, not that the link is up.
/// `currentSSID` reflects what the phone is actually on. Once the phone is
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

    /// True once the phone is, or has just been, put on `ssid`.
    func isOn(_ ssid: String) -> Bool {
        if currentSSID == ssid { return true }
        if case .joined(let joined) = status, joined == ssid { return true }
        return false
    }

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

        do {
            try await NEHotspotConfigurationManager.shared.apply(config)
            status = .joined(ssid: ssid)
        } catch let error as NSError where Self.isAlreadyAssociated(error) {
            status = .joined(ssid: ssid)
        } catch {
            status = .failed(ssid: ssid, message: Self.describe(error as NSError))
            return
        }
        // Association lags the accepted configuration by a moment.
        for _ in 0 ..< 8 {
            await refreshCurrent()
            if currentSSID == ssid { break }
            try? await Task.sleep(for: .seconds(1))
        }
        // An open network is kept too, with no password: the Saved networks list shows what the phone joined.
        if currentSSID == ssid {
            onJoined?(ssid, passphrase ?? "")
        }
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
