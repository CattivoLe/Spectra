import NetworkExtension
import Foundation
import os

/// Notice level on purpose: debug and info messages are not kept in the phone's log archive, and these are
/// what a `log collect` after a bad session has to show.
private let tunnelLog = Logger(subsystem: "cattivole.Spectra", category: "tunnel")

/// The packet-tunnel extension: captures this phone's egress and hands every IP
/// packet to `TrafficProxy`, which replays it off-tunnel and writes replies back.
/// The live flow snapshot is published into the shared App Group for the app's
/// Traffic screen. Mirrors the Android `platform/SnifferVpnService.kt`.
///
/// This file belongs to the SpectraTunnel app-extension target. The shared
/// sniffer core (TrafficProxy, IPCodec, TLSBridge, LocalCertificateAuthority, …)
/// must be a member of that target too (see the setup notes).
final class PacketTunnelProvider: NEPacketTunnelProvider {
    static let appGroup = "group.cattivole.Spectra"
    private static let tlsKey = "traffic_decrypt_tls"
    /// Why the tunnel stopped itself, for the app to show (cleared on every start).
    static let stopReasonKey = "traffic_stop_reason"

    private var proxy: TrafficProxy?
    private var lastPublish = Date.distantPast
    private var watchdog: DispatchSourceTimer?
    private var strainedChecks = 0
    private var healthTicks = 0
    private var toldDecryptOff = false
    /// The "Decrypt TLS" switch, read from the App Group on the watchdog's tick rather than on every packet:
    /// each read is a round trip to the preferences daemon, and the loop asks for it per packet.
    private let decryptFlag = OSAllocatedUnfairLock(initialState: false)
    /// Which hosts are decrypted, read with the switch on the watchdog's tick.
    private let hostFilter = OSAllocatedUnfairLock(initialState: TlsHostFilter())
    /// Written when decryption switched itself off for the run, for the app to explain.
    static let tlsNoticeKey = "traffic_tls_notice"

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        UserDefaults(suiteName: Self.appGroup)?.removeObject(forKey: Self.stopReasonKey)
        UserDefaults(suiteName: Self.appGroup)?.removeObject(forKey: Self.tlsNoticeKey)
        let decrypt = decryptEnabled()
        decryptFlag.withLock { $0 = decrypt }
        let filter = TlsHostFilter.load(UserDefaults(suiteName: Self.appGroup))
        hostFilter.withLock { $0 = filter }
        // A new run starts with no bodies: what is left belongs to the previous recording.
        let bodies = container().map { TrafficBodies(dir: $0.appendingPathComponent(PacketTunnelBodies.folder, isDirectory: true)) }
        bodies?.clear()
        if let dir = bodies?.dir {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            LocalCertificateAuthority.excludeFromBackup(dir)
        }
        // Not a loopback address: the remote address is where the system expects the VPN server to be, and
        // nothing of the phone's own loopback traffic may be steered by it.
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "198.18.0.254")
        let ipv4 = NEIPv4Settings(addresses: ["198.18.0.1"], subnetMasks: ["255.255.255.255"])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        // The local network, link-local and multicast stay off the tunnel: printers, AirPlay, the router and
        // the LAN tab's own scans talk to them directly, and the recording is about what leaves the phone.
        ipv4.excludedRoutes = [
            NEIPv4Route(destinationAddress: "10.0.0.0", subnetMask: "255.0.0.0"),
            NEIPv4Route(destinationAddress: "172.16.0.0", subnetMask: "255.240.0.0"),
            NEIPv4Route(destinationAddress: "192.168.0.0", subnetMask: "255.255.0.0"),
            NEIPv4Route(destinationAddress: "169.254.0.0", subnetMask: "255.255.0.0"),
            NEIPv4Route(destinationAddress: "224.0.0.0", subnetMask: "240.0.0.0"),
        ]
        settings.ipv4Settings = ipv4
        let ipv6 = NEIPv6Settings(addresses: ["fd00:111:222::1"], networkPrefixLengths: [128])
        ipv6.includedRoutes = [NEIPv6Route.default()]
        ipv6.excludedRoutes = [
            NEIPv6Route(destinationAddress: "fe80::", networkPrefixLength: 10),
            NEIPv6Route(destinationAddress: "ff00::", networkPrefixLength: 8),
        ]
        settings.ipv6Settings = ipv6
        settings.dnsSettings = NEDNSSettings(servers: ["1.1.1.1", "8.8.8.8"])
        settings.mtu = 1500

        setTunnelNetworkSettings(settings) { [weak self] error in
            guard let self else { return }
            if let error {
                tunnelLog.error("settings failed: \(String(describing: error), privacy: .public)")
                completionHandler(error)
                return
            }
            tunnelLog.notice("tunnel started, decrypt \(decrypt)")

            let proxy = TrafficProxy(
                writer: { [weak self] packet, family in
                    self?.packetFlow.writePackets([packet], withProtocols: [NSNumber(value: family)])
                },
                decryptTLS: { [decryptFlag = self.decryptFlag] in decryptFlag.withLock { $0 } },
                certificate: { self.loadCA() },
                hostFilter: { [hostFilter = self.hostFilter] in hostFilter.withLock { $0 } },
                bodies: bodies,
                onFlows: { [weak self] flows in self?.publish(flows) }
            )
            self.proxy = proxy
            self.readLoop()
            self.startWatchdog()
            completionHandler(nil)
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        tunnelLog.notice("tunnel stopping, reason \(reason.rawValue)")
        watchdog?.cancel()
        watchdog = nil
        proxy?.stop()
        proxy = nil
        completionHandler()
    }

    /// Every connection on the phone goes through this extension, so a proxy that stops keeping up takes the
    /// whole phone's network down with it. Rather than that, the tunnel gives up: it stops itself, the phone
    /// is back on its own network at once, and the app says why. Checked every 2 s: the backlog stuck at its
    /// cap, or packets waiting while the loop has taken none for 10 s.
    private func startWatchdog() {
        let timer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        timer.schedule(deadline: .now() + 2, repeating: 2)
        timer.setEventHandler { [weak self] in
            guard let self, let proxy = self.proxy else { return }
            // Never waits on the loop: everything comes from the lock the loop updates.
            let state = proxy.backlogState
            let now = Date()
            let decrypt = self.decryptEnabled()
            self.decryptFlag.withLock { $0 = decrypt }
            let filter = TlsHostFilter.load(UserDefaults(suiteName: Self.appGroup))
            self.hostFilter.withLock { $0 = filter }
            if state.decryptOff && !self.toldDecryptOff {
                self.toldDecryptOff = true
                UserDefaults(suiteName: Self.appGroup)?.set("untrusted", forKey: Self.tlsNoticeKey)
            }
            let drainAgo = Int(now.timeIntervalSince(state.lastDrain))
            let beatAgo = Int(now.timeIntervalSince(state.lastBeat))
            let phaseAgo = Int(now.timeIntervalSince(state.phaseSince))
            self.healthTicks += 1
            if self.healthTicks % 5 == 0 {
                tunnelLog.notice("health links=\(state.links) pending=\(state.pending) drainAgo=\(drainAgo)s beatAgo=\(beatAgo)s phase=\(state.phase, privacy: .public) for \(phaseAgo)s decrypt=\(decrypt) off=\(state.decryptOff) mem=\(Self.memoryFootprintMB())MB")
            }
            let stalled = (state.pending > 0 && drainAgo > 10) || beatAgo > 15
            let strained = state.pending >= TrafficProxy.maxBacklog / 2
            self.strainedChecks = strained ? self.strainedChecks + 1 : 0
            guard stalled || self.strainedChecks >= 5 else { return }
            tunnelLog.error("stuck: pending=\(state.pending) drainAgo=\(drainAgo)s beatAgo=\(beatAgo)s phase=\(state.phase, privacy: .public) for \(phaseAgo)s")
            self.giveUp(stalled ? "stalled" : "overloaded")
        }
        watchdog = timer
        timer.resume()
    }

    /// `reason` is a code the app turns into words: "stalled" or "overloaded".
    private func giveUp(_ reason: String) {
        tunnelLog.error("giving up: \(reason, privacy: .public)")
        watchdog?.cancel()
        watchdog = nil
        UserDefaults(suiteName: Self.appGroup)?.set(reason, forKey: Self.stopReasonKey)
        cancelTunnelWithError(NSError(domain: "cattivole.Spectra.SpectraTunnel", code: 1,
                                      userInfo: [NSLocalizedDescriptionKey: reason]))
    }

    private func readLoop() {
        packetFlow.readPackets { [weak self] packets, _ in
            guard let self, let proxy = self.proxy else { return }
            for packet in packets { proxy.handle(packet) }
            self.readLoop()
        }
    }

    /// This process's physical footprint, as jetsam counts it (packet tunnels are killed at a few dozen MB).
    private static func memoryFootprintMB() -> Int {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? Int(info.phys_footprint / 1_048_576) : -1
    }

    // MARK: Shared App Group

    private func decryptEnabled() -> Bool {
        UserDefaults(suiteName: Self.appGroup)?.bool(forKey: Self.tlsKey) ?? false
    }

    private func loadCA() -> LocalCertificateAuthority? {
        guard let dir = container()?.appendingPathComponent("ca", isDirectory: true) else { return nil }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return try? LocalCertificateAuthority.open(dir, guard: LocalCertificateAuthority.deviceGuard(appGroup: Self.appGroup))
    }

    private func publish(_ flows: [TrafficFlow]) {
        let now = Date()
        guard now.timeIntervalSince(lastPublish) >= 0.2 else { return }
        lastPublish = now
        guard let url = container()?.appendingPathComponent("live.json"),
              let data = try? JSONEncoder().encode(flows) else { return }
        try? data.write(to: url, options: .atomic)
        // An atomic write makes a new file, so the flag is set again each time:
        // live flows can hold decrypted HTTP and stay out of backups.
        LocalCertificateAuthority.excludeFromBackup(url)
    }

    private func container() -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup)
    }
}
