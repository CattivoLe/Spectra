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
    static let tunnelBundleID = "cattivole.Spectra.Tunnel"

    private(set) var running = false
    private(set) var flows: [TrafficFlow] = []
    private(set) var saved: [TrafficSession] = []
    private(set) var startedAt: Int64 = 0
    private(set) var error: String?
    private(set) var caFingerprint: String?
    var decryptTLS: Bool {
        didSet { defaults?.set(decryptTLS, forKey: Self.tlsKey) }
    }

    private var manager: NETunnelProviderManager?
    private var pollTask: Task<Void, Never>?
    private let defaults = UserDefaults(suiteName: TrafficSniffer.appGroup)
    private static let tlsKey = "traffic_decrypt_tls"
    private static let keepSessions = 30

    init() {
        decryptTLS = UserDefaults(suiteName: TrafficSniffer.appGroup)?.bool(forKey: Self.tlsKey) ?? false
        loadSaved()
        refreshCA()
    }

    // MARK: VPN lifecycle

    /// Installs the tunnel configuration if needed and starts capturing.
    func start() async {
        error = nil
        do {
            let manager = try await loadOrCreateManager()
            self.manager = manager
            try manager.connection.startVPNTunnel()
            running = true
            startedAt = Int64(Date().timeIntervalSince1970 * 1000)
            startPolling()
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
        guard let url = containerURL()?.appendingPathComponent("live.json"),
              let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode([TrafficFlow].self, from: data) else { return }
        flows = decoded
    }

    // MARK: Saved sessions

    func saveCurrent() {
        guard !flows.isEmpty else { return }
        let session = TrafficSession(
            id: UUID().uuidString,
            startedAt: startedAt > 0 ? startedAt : (flows.map(\.startedAt).min() ?? 0),
            stoppedAt: Int64(Date().timeIntervalSince1970 * 1000),
            flows: flows
        )
        guard let dir = sessionsDir() else { return }
        try? session.encoded().write(to: dir.appendingPathComponent("\(session.id).json"))
        saved = ([session] + saved).prefix(Self.keepSessions).map { $0 }
        for old in saved.dropFirst(Self.keepSessions) {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent("\(old.id).json"))
        }
    }

    func delete(_ session: TrafficSession) {
        if let dir = sessionsDir() {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent("\(session.id).json"))
        }
        saved.removeAll { $0.id == session.id }
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
