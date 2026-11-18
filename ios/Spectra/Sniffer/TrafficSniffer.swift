import Foundation
import NetworkExtension
import Observation

/// App-side control + state for the traffic sniffer. Installs and starts the
/// packet-tunnel extension, mirrors the live flow list the extension publishes
/// into the shared App Group, and keeps saved sessions. Mirrors the role of the
/// Android `platform/TrafficSniffer.kt` + `SpectraModel.traffic`.
///
/// The extension writes the live snapshot to the App Group container; the app
/// polls it while the Traffic screen is open. State that must survive and be
/// shared across the two processes lives in the group container, never in memory.
@MainActor @Observable
final class TrafficSniffer {
    static let appGroup = "group.cattivole.Spectra"
    static let tunnelBundleID = "cattivole.Spectra.SpectraTunnel"

    private(set) var running = false
    private(set) var flows: [TrafficFlow] = []
    private(set) var saved: [TrafficSession] = []
    /// The saved session this recording went into, so saving again updates it instead of adding a copy.
    /// A new recording, or clearing the list, starts a new one.
    private(set) var currentSessionID: String?
    /// What the list held when it was last saved: nothing new since then means there is nothing to save.
    private var savedMark: SaveMark?
    private struct SaveMark: Equatable {
        var count: Int
        var lastAt: Int64
        var bytes: Int64
        init(_ flows: [TrafficFlow]) {
            count = flows.count
            lastAt = flows.map(\.lastAt).max() ?? 0
            bytes = flows.reduce(0) { $0 + $1.sent + $1.received }
        }
    }

    /// The list on screen is already saved as it is.
    var isCurrentSaved: Bool { !flows.isEmpty && savedMark == SaveMark(flows) }
    private(set) var startedAt: Int64 = 0
    private(set) var error: String?
    private(set) var caFingerprint: String?
    var decryptTLS: Bool {
        didSet { defaults?.set(decryptTLS, forKey: Self.tlsKey) }
    }

    private var manager: NETunnelProviderManager?
    private var pollTask: Task<Void, Never>?
    private var statusObserver: NSObjectProtocol?
    private let defaults = UserDefaults(suiteName: TrafficSniffer.appGroup)
    private static let tlsKey = "traffic_decrypt_tls"
    /// Written by the tunnel when it stops itself (see `PacketTunnelProvider.giveUp`).
    private static let stopReasonKey = "traffic_stop_reason"
    /// Written by the tunnel when it stopped decrypting for the run (see `TrafficProxy.mitmVerdict`).
    private static let tlsNoticeKey = "traffic_tls_notice"
    private static let keepSessions = 30

    init() {
        decryptTLS = UserDefaults(suiteName: TrafficSniffer.appGroup)?.bool(forKey: Self.tlsKey) ?? false
        loadSaved()
        refreshCA()
        // The tunnel outlives the app: after a relaunch, pick up one that is still recording, so it can be
        // stopped from here rather than only from Settings.
        Task { await attachToExistingTunnel() }
    }

    /// Follows the system's own VPN state, which is the truth: the tunnel may stop itself, be turned off in
    /// Settings, or still be running from before the app was relaunched.
    private func attachToExistingTunnel() async {
        guard let manager = try? await NETunnelProviderManager.loadAllFromPreferences().first else { return }
        observe(manager)
    }

    private func observe(_ manager: NETunnelProviderManager) {
        self.manager = manager
        if let statusObserver { NotificationCenter.default.removeObserver(statusObserver) }
        statusObserver = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange, object: manager.connection, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.syncStatus() }
        }
        syncStatus()
    }

    private func syncStatus() {
        guard let status = manager?.connection.status else { return }
        switch status {
        case .connected, .connecting, .reasserting:
            if !running {
                running = true
                if startedAt == 0 { startedAt = Int64(Date().timeIntervalSince1970 * 1000) }
            }
            if pollTask == nil { startPolling() }
        case .disconnected, .invalid:
            guard running else { return }
            running = false
            pollTask?.cancel()
            pollTask = nil
            readLiveSnapshot()
            if let reason = defaults?.string(forKey: Self.stopReasonKey) {
                error = reason == "stalled"
                    ? String(localized: "Recording stopped by itself: the recorder stopped responding, and the phone would have been left without a network.")
                    : String(localized: "Recording stopped by itself: there was more traffic than the recorder could handle, and the phone would have been left without a network.")
                defaults?.removeObject(forKey: Self.stopReasonKey)
            }
        case .disconnecting:
            break
        @unknown default:
            break
        }
    }

    // MARK: VPN lifecycle

    /// Installs the tunnel configuration if needed and starts capturing.
    func start() async {
        error = nil
        do {
            let manager = try await loadOrCreateManager()
            observe(manager)
            try manager.connection.startVPNTunnel()
            currentSessionID = nil
            savedMark = nil
            running = true
            startedAt = Int64(Date().timeIntervalSince1970 * 1000)
            if pollTask == nil { startPolling() }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func stop() {
        manager?.connection.stopVPNTunnel()
        running = false
        pollTask?.cancel()
        pollTask = nil
    }

    /// Empties the live list. While recording, the extension keeps publishing,
    /// so the list refills from new traffic; most useful once stopped.
    func clear() {
        flows = []
        currentSessionID = nil
        savedMark = nil
        if let url = containerURL()?.appendingPathComponent("live.json") {
            try? FileManager.default.removeItem(at: url)
        }
    }

    private func loadOrCreateManager() async throws -> NETunnelProviderManager {
        let existing = try await NETunnelProviderManager.loadAllFromPreferences()
        let manager = existing.first ?? NETunnelProviderManager()
        let proto = (manager.protocolConfiguration as? NETunnelProviderProtocol) ?? NETunnelProviderProtocol()
        proto.providerBundleIdentifier = Self.tunnelBundleID
        proto.serverAddress = "Spectra"
        manager.protocolConfiguration = proto
        manager.localizedDescription = "Spectra Traffic"
        manager.isEnabled = true
        try await manager.saveToPreferences()
        try await manager.loadFromPreferences()
        return manager
    }

    // MARK: Live snapshot (polled from the App Group)

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                self?.readLiveSnapshot()
                try? await Task.sleep(for: .milliseconds(400))
            }
        }
    }

    private func readLiveSnapshot() {
        if defaults?.string(forKey: Self.tlsNoticeKey) != nil {
            defaults?.removeObject(forKey: Self.tlsNoticeKey)
            error = String(localized: "Apps keep refusing Spectra's certificate, and none has accepted it yet: it is probably not trusted. Connections that refused go through unread.")
        }
        guard let url = containerURL()?.appendingPathComponent("live.json"),
              let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode([TrafficFlow].self, from: data) else { return }
        flows = decoded
    }

    // MARK: Saved sessions

    /// Saves the list on screen. Saving the same recording again updates its session rather than adding an
    /// identical one; returns whether anything was written.
    @discardableResult
    func saveCurrent() -> Bool {
        guard !flows.isEmpty, !isCurrentSaved, let dir = sessionsDir() else { return false }
        let id = currentSessionID ?? UUID().uuidString
        let session = TrafficSession(
            id: id,
            startedAt: startedAt > 0 ? startedAt : (flows.map(\.startedAt).min() ?? 0),
            stoppedAt: Int64(Date().timeIntervalSince1970 * 1000),
            flows: flows
        )
        do {
            try session.encoded().write(to: dir.appendingPathComponent("\(id).json"), options: .atomic)
        } catch {
            self.error = error.localizedDescription
            return false
        }
        let all = [session] + saved.filter { $0.id != id }
        saved = Array(all.prefix(Self.keepSessions))
        // Whatever no longer fits goes from disk too (the old code computed this after trimming, so never did).
        for old in all.dropFirst(Self.keepSessions) {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent("\(old.id).json"))
        }
        currentSessionID = id
        savedMark = SaveMark(flows)
        return true
    }

    func delete(_ session: TrafficSession) {
        if let dir = sessionsDir() {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent("\(session.id).json"))
        }
        saved.removeAll { $0.id == session.id }
        if session.id == currentSessionID {
            currentSessionID = nil
            savedMark = nil
        }
    }

    private func loadSaved() {
        guard let dir = sessionsDir(),
              let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        saved = files.filter { $0.pathExtension == "json" }
            .compactMap { try? Data(contentsOf: $0) }
            .compactMap { TrafficSession.decode($0) }
            .sorted { $0.stoppedAt > $1.stoppedAt }
            .prefix(Self.keepSessions).map { $0 }
    }

    // MARK: CA (for the TLS-decrypt install flow)

    private func refreshCA() {
        guard let dir = caDir() else { return }
        caFingerprint = (try? LocalCertificateAuthority.open(dir))?.fingerprint()
    }

    /// The CA certificate in DER, to export for the user to install + trust.
    func caCertificateDER() -> Data? {
        guard let dir = caDir() else { return nil }
        return (try? LocalCertificateAuthority.open(dir)).map { Data($0.certificateDER) }
    }

    // MARK: Group container paths

    private func containerURL() -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup)
    }

    private func sessionsDir() -> URL? {
        guard let base = containerURL()?.appendingPathComponent("traffic", isDirectory: true) else { return nil }
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    private func caDir() -> URL? {
        guard let base = containerURL()?.appendingPathComponent("ca", isDirectory: true) else { return nil }
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }
}
