import Foundation
import Observation

/// The hosts each network has shown before, so a scan can point out the ones
/// that were never there. Kept per network key and persisted across launches,
/// apart from History: clearing saved scans does not make every device new again.
///
/// iOS hides most hardware addresses, so a host is matched by any of several
/// fingerprints: its MAC when known, the names it gives out, and its address.
/// A later scan that loses the MAC still finds the host by name or address.
@Observable
final class KnownHosts {
    /// Fingerprints seen on one network, each with the first time it was seen.
    struct Network: Codable, Equatable {
        var firstSeen: [String: Date] = [:]
        var baselineAt: Date
    }

    /// What one scan found out about its devices.
    struct Verdict: Equatable {
        /// The first scan of this network only records what is there.
        var isBaseline: Bool
        var newIPs: [String]
    }

    private(set) var networks: [String: Network]
    private let defaults: UserDefaults
    private let storageKey = "knownHosts"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode([String: Network].self, from: data) {
            networks = decoded
        } else {
            networks = [:]
        }
    }

    func knows(network: String) -> Bool { networks[network] != nil }

    /// Flags the devices this network has never shown and remembers all of
    /// them. The gateway and this iPhone are never new.
    @discardableResult
    func classify(_ devices: [LANDevice], network: String, now: Date = .now) -> Verdict {
        let isBaseline = networks[network] == nil
        var known = networks[network] ?? Network(baselineAt: now)
        var newIPs: [String] = []

        for device in devices {
            let prints = device.fingerprints
            let seen = prints.contains { known.firstSeen[$0] != nil }
            if !isBaseline, !seen, !device.isGateway, !device.isLocalDevice {
                device.isNew = true
                newIPs.append(device.ipAddress)
            }
            // A host matched by one fingerprint teaches the others, so the
            // same device found later by its address alone is still known.
            let since = prints.compactMap { known.firstSeen[$0] }.min() ?? now
            for print in prints where known.firstSeen[print] == nil {
                known.firstSeen[print] = since
            }
        }

        networks[network] = known
        persist()
        return Verdict(isBaseline: isBaseline, newIPs: newIPs)
    }

    /// When this network first showed the device, by any of its fingerprints.
    func firstSeen(_ device: LANDevice, network: String) -> Date? {
        guard let known = networks[network] else { return nil }
        return device.fingerprints.compactMap { known.firstSeen[$0] }.min()
    }

    /// Drops everything learned about a network: its next scan is a baseline again.
    func forget(network: String) {
        networks[network] = nil
        persist()
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(networks) else { return }
        defaults.set(data, forKey: storageKey)
    }
}

extension LANDevice {
    /// Every way this host can be recognised again: hardware address, the
    /// names it announces, and its address. Weak names ("android") and bare
    /// identifiers are left out, since two devices can share them.
    var fingerprints: [String] {
        var prints: [String] = []
        if let mac = macAddress {
            prints.append("mac:\(mac.description)")
        }
        var names = hostnames
        if let name = profile.upnp?.friendlyName { names.append(name) }
        if let name = profile.windows?.computerName { names.append(name) }
        for raw in names {
            guard let name = Self.usefulHostname(raw, ip: ipAddress) else { continue }
            var folded = name.lowercased()
            if folded.hasSuffix(".local") { folded.removeLast(6) }
            guard !folded.isEmpty, !Self.isWeakHostname(folded) else { continue }
            let print = "name:\(folded)"
            if !prints.contains(print) { prints.append(print) }
        }
        prints.append("ip:\(ipAddress)")
        return prints
    }
}
