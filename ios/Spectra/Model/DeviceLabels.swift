import Foundation
import Observation

/// What the owner thinks of a host.
nonisolated enum DeviceTrust: String, Codable, CaseIterable, Sendable {
    /// Nothing decided yet.
    case unknown
    /// Mine, or a guest I know: no need to look at it again.
    case trusted
    /// Something to keep an eye on.
    case suspicious

    var label: String {
        switch self {
        case .unknown: String(localized: "Not decided")
        case .trusted: String(localized: "Trusted")
        case .suspicious: String(localized: "Suspicious")
        }
    }
}

/// A name, a verdict and a note the user attached to a host.
nonisolated struct DeviceLabel: Codable, Equatable, Sendable {
    var name: String?
    var trust: DeviceTrust = .unknown
    var note: String?

    var isEmpty: Bool { name == nil && trust == .unknown && note == nil }

    /// Blank text counts as no text.
    func trimmed() -> DeviceLabel {
        func clean(_ text: String?) -> String? {
            let value = text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return value.isEmpty ? nil : value
        }
        return DeviceLabel(name: clean(name), trust: trust, note: clean(note))
    }
}

/// The user's labels for hosts, found again by the same fingerprints
/// `KnownHosts` uses: hardware address, announced names, and the address as
/// a last resort. Persisted across launches, on this device only.
@Observable
final class DeviceLabels {
    private(set) var labels: [String: DeviceLabel]
    private let defaults: UserDefaults
    private let storageKey = "deviceLabels"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode([String: DeviceLabel].self, from: data) {
            labels = decoded
        } else {
            labels = [:]
        }
    }

    /// The label for this host. A match on the address alone only counts
    /// when the address is all the host has: another device that took over
    /// the address must not inherit a "trusted".
    func label(for device: LANDevice) -> DeviceLabel? {
        let prints = device.fingerprints
        let onlyAddress = prints.allSatisfy { $0.hasPrefix("ip:") }
        for print in prints {
            if print.hasPrefix("ip:"), !onlyAddress { continue }
            if let label = labels[print] { return label }
        }
        return nil
    }

    /// Stores the label under every fingerprint of the host, replacing what
    /// those fingerprints had, and writes it onto the host.
    func set(_ label: DeviceLabel, for device: LANDevice) {
        let cleaned = label.trimmed()
        for print in device.fingerprints {
            labels[print] = cleaned.isEmpty ? nil : cleaned
        }
        persist()
        apply(to: device)
    }

    /// Copies the stored label, if any, onto the host.
    func apply(to device: LANDevice) {
        let label = label(for: device)
        device.customName = label?.name
        device.trust = label?.trust ?? .unknown
        device.note = label?.note
        if device.trust == .trusted { device.isNew = false }
    }

    /// Same host, rebuilt from History: give it the live labels too.
    func decorated(_ device: LANDevice) -> LANDevice {
        apply(to: device)
        return device
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(labels) else { return }
        defaults.set(data, forKey: storageKey)
    }
}
