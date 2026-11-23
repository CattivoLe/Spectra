import Foundation

/// When each network, host and Bluetooth device was first met, across every session in History.
///
/// A list starts empty on each launch, so "first seen" counted from there was always today. The dates come from
/// here instead, and go on into the new session, so they outlive the old ones History drops. Sessions imported
/// from another phone do not count: that phone met those devices, not this one.
struct Sightings {
    private var wifi: [BSSID: Date] = [:]
    private var bluetooth: [UUID: Date] = [:]
    /// Hosts by anything that names them: `mac:…`, `name:…`, and the address within one network, `<network>|ip:…`.
    private var hosts: [String: Date] = [:]

    init() {}

    init(_ entries: [HistoryEntry]) {
        for entry in entries { note(entry) }
    }

    mutating func note(_ entry: HistoryEntry) {
        guard entry.importedFrom == nil else { return }
        for network in entry.networks { wifi[network.id] = min(wifi[network.id] ?? .distantFuture, network.firstSeen) }
        for device in entry.bluetooth { bluetooth[device.id] = min(bluetooth[device.id] ?? .distantFuture, device.firstSeen) }
        for host in entry.lan {
            for key in Self.keys(host.device(), networks: [entry.placeKey].compactMap { $0 }) {
                hosts[key] = min(hosts[key] ?? .distantFuture, host.firstSeen)
            }
        }
    }

    func first(_ bssid: BSSID) -> Date? { wifi[bssid] }

    func first(bluetooth id: UUID) -> Date? { bluetooth[id] }

    /// The host by its hardware address or a name of its own on any network, or by its address on `networks`: the
    /// keys one network may have been saved under.
    func first(_ device: LANDevice, networks: [String]) -> Date? {
        Self.keys(device, networks: networks).compactMap { hosts[$0] }.min()
    }

    /// The phone itself is on every network, so it is dated per network, by its address there.
    private static func keys(_ device: LANDevice, networks: [String]) -> [String] {
        let own = device.isLocalDevice ? [] : device.fingerprints.filter { !$0.hasPrefix("ip:") }
        return own + networks.filter { !$0.isEmpty }.map { "\($0)|ip:\(device.ipAddress)" }
    }
}

extension HistoryEntry {
    /// The session with each record's first sighting moved back to the earliest one known.
    func dated(by sightings: Sightings) -> HistoryEntry {
        guard importedFrom == nil else { return self }
        var copy = self
        for index in copy.networks.indices {
            if let date = sightings.first(copy.networks[index].id) {
                copy.networks[index].firstSeen = min(copy.networks[index].firstSeen, date)
            }
        }
        for index in copy.bluetooth.indices {
            if let date = sightings.first(bluetooth: copy.bluetooth[index].id) {
                copy.bluetooth[index].firstSeen = min(copy.bluetooth[index].firstSeen, date)
            }
        }
        for index in copy.lan.indices {
            if let date = sightings.first(copy.lan[index].device(), networks: [copy.placeKey].compactMap { $0 }) {
                copy.lan[index].firstSeen = min(copy.lan[index].firstSeen, date)
            }
        }
        return copy
    }
}
