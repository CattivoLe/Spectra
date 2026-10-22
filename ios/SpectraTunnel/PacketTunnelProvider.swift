import NetworkExtension
import Foundation

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

    private var proxy: TrafficProxy?
    private var lastPublish = Date.distantPast

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        let ipv4 = NEIPv4Settings(addresses: ["10.111.222.1"], subnetMasks: ["255.255.255.255"])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = ipv4
        let ipv6 = NEIPv6Settings(addresses: ["fd00:111:222::1"], networkPrefixLengths: [128])
        ipv6.includedRoutes = [NEIPv6Route.default()]
        settings.ipv6Settings = ipv6
        settings.dnsSettings = NEDNSSettings(servers: ["1.1.1.1", "8.8.8.8"])
        settings.mtu = 1500

        setTunnelNetworkSettings(settings) { [weak self] error in
            guard let self else { return }
            if let error { completionHandler(error); return }

            let proxy = TrafficProxy(
                writer: { [weak self] packet, family in
                    self?.packetFlow.writePackets([packet], withProtocols: [NSNumber(value: family)])
                },
                decryptTLS: { self.decryptEnabled() },
                certificate: { self.loadCA() },
                onFlows: { [weak self] flows in self?.publish(flows) }
            )
            self.proxy = proxy
            self.readLoop()
            completionHandler(nil)
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        proxy?.stop()
        proxy = nil
        completionHandler()
    }

    private func readLoop() {
        packetFlow.readPackets { [weak self] packets, _ in
            guard let self, let proxy = self.proxy else { return }
            for packet in packets { proxy.handle(packet) }
            self.readLoop()
        }
    }

    // MARK: Shared App Group

    private func decryptEnabled() -> Bool {
        UserDefaults(suiteName: Self.appGroup)?.bool(forKey: Self.tlsKey) ?? false
    }

    private func loadCA() -> LocalCertificateAuthority? {
        guard let dir = container()?.appendingPathComponent("ca", isDirectory: true) else { return nil }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return try? LocalCertificateAuthority.open(dir)
    }

    private func publish(_ flows: [TrafficFlow]) {
        let now = Date()
        guard now.timeIntervalSince(lastPublish) >= 0.2 else { return }
        lastPublish = now
        guard let url = container()?.appendingPathComponent("live.json"),
              let data = try? JSONEncoder().encode(flows) else { return }
        try? data.write(to: url, options: .atomic)
    }

    private func container() -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup)
    }
}
