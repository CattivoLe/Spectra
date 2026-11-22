import Foundation
import Network

/// Whether iOS lets the app reach the local network.
///
/// iOS has no API that reports this. Until the person answers the "find devices
/// on your local network" prompt, every packet to the subnet is dropped, so a
/// scan run meanwhile finds only this iPhone and whatever the ARP cache already
/// held (usually the router). The check publishes a throwaway Bonjour service
/// and browses for it: finding it means access is granted; the browser waiting
/// with a policy error means it was refused. While the prompt is on screen
/// neither happens, so the check waits for the answer.
nonisolated enum LocalNetworkAccess: Sendable, Equatable {
    case granted
    case denied
    /// No answer within the time allowed. The scan goes ahead anyway.
    case undetermined

    /// Listed in `NSBonjourServices` (Config/Spectra-Info.plist); not scanned for.
    static let serviceType = "_spectra._tcp"

    /// `kDNSServiceErr_PolicyDenied` from <dns_sd.h>: the person said no to local network access.
    static let policyDenied = DNSServiceErrorType(-65570)

    static func check(timeout: Duration = .seconds(60)) async -> LocalNetworkAccess {
        let probe = AccessProbe()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                probe.start(timeout: timeout, continuation: continuation)
            }
        } onCancel: {
            probe.finish(.undetermined)
        }
    }

    /// What a browser error says about access: refused, or nothing yet.
    static func verdict(for error: NWError) -> LocalNetworkAccess? {
        if case .dns(let code) = error, code == policyDenied { return .denied }
        return nil
    }
}

/// One publish-and-browse round. All state lives on `queue`.
nonisolated private final class AccessProbe: @unchecked Sendable {
    private let queue = DispatchQueue(label: "Spectra.LocalNetworkAccess")
    private var listener: NWListener?
    private var browser: NWBrowser?
    private var continuation: CheckedContinuation<LocalNetworkAccess, Never>?
    private var done = false

    func start(timeout: Duration, continuation: CheckedContinuation<LocalNetworkAccess, Never>) {
        queue.async { [self] in
            guard !done else {
                continuation.resume(returning: .undetermined)
                return
            }
            self.continuation = continuation
            let name = "Spectra-\(UUID().uuidString.prefix(8))"
            let parameters = NWParameters.tcp
            parameters.includePeerToPeer = false

            do {
                let listener = try NWListener(using: parameters)
                listener.service = NWListener.Service(name: name, type: LocalNetworkAccess.serviceType)
                listener.newConnectionHandler = { $0.cancel() }
                listener.stateUpdateHandler = { [weak self] state in
                    if case .failed = state { self?.finish(.undetermined) }
                }
                self.listener = listener
                listener.start(queue: queue)
            } catch {
                finishOnQueue(.undetermined)
                return
            }

            let browser = NWBrowser(for: .bonjour(type: LocalNetworkAccess.serviceType, domain: nil), using: parameters)
            browser.stateUpdateHandler = { [weak self] state in
                switch state {
                case .waiting(let error), .failed(let error):
                    if let verdict = LocalNetworkAccess.verdict(for: error) { self?.finish(verdict) }
                default:
                    break
                }
            }
            browser.browseResultsChangedHandler = { [weak self] results, _ in
                let found = results.contains { result in
                    if case .service(let found, _, _, _) = result.endpoint { return found == name }
                    return false
                }
                if found { self?.finish(.granted) }
            }
            self.browser = browser
            browser.start(queue: queue)

            let seconds = Double(timeout.components.seconds) + Double(timeout.components.attoseconds) / 1e18
            queue.asyncAfter(deadline: .now() + seconds) { [weak self] in self?.finishOnQueue(.undetermined) }
        }
    }

    func finish(_ result: LocalNetworkAccess) {
        queue.async { [self] in finishOnQueue(result) }
    }

    private func finishOnQueue(_ result: LocalNetworkAccess) {
        guard !done else { return }
        done = true
        listener?.cancel()
        browser?.cancel()
        listener = nil
        browser = nil
        continuation?.resume(returning: result)
        continuation = nil
    }
}
