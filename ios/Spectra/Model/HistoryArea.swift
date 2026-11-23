import Foundation

/// The tab a saved record belongs to. Each tab keeps its own history: a session shows there only the records of
/// that tab, and deleting from a tab removes only those.
///
/// A LAN session stores its devices and the connection checks made on that network together. The devices are the
/// LAN tab's, the checks the Internet tab's; the file keeps them as one session. Mirrors HistoryArea.kt.
enum HistoryArea: String, CaseIterable, Hashable, Identifiable {
    case wifi, lan, internet, bluetooth

    var id: String { rawValue }

    /// The tab a session's sensor lines belong to, by the session's kind.
    static func of(_ kind: HistoryEntry.Kind) -> HistoryArea {
        switch kind {
        case .lan: .lan
        case .bluetooth: .bluetooth
        case .networks, .lora: .wifi
        }
    }

    /// The tab's name, under the History title.
    var title: String {
        switch self {
        case .wifi: "Wi‑Fi"
        case .lan: "LAN"
        case .internet: String(localized: "Internet")
        case .bluetooth: "Bluetooth"
        }
    }

    var systemImage: String {
        switch self {
        case .wifi: "wifi"
        case .lan: "network"
        case .internet: "globe"
        case .bluetooth: "wave.3.right"
        }
    }

    /// What the empty history of a tab says will appear in it.
    var emptyHint: String {
        switch self {
        case .wifi: String(localized: "Scans are saved here automatically. The Cardputer's side button saves its own scan, and it arrives here when the phone connects.")
        case .lan: String(localized: "Device scans of each network are saved here automatically.")
        case .internet: String(localized: "Connection checks are saved here automatically, by network.")
        case .bluetooth: String(localized: "Bluetooth scans are saved here automatically.")
        }
    }
}

extension HistoryEntry {
    /// True when this session holds anything shown on `area`'s tab.
    func holds(_ area: HistoryArea) -> Bool {
        let records = switch area {
        case .wifi: !networks.isEmpty || !probes.isEmpty || !deauths.isEmpty
        case .lan: !lan.isEmpty
        case .internet: !connections.isEmpty
        case .bluetooth: !bluetooth.isEmpty
        }
        return records || (!lines.isEmpty && HistoryArea.of(kind) == area)
    }

    /// The session as `area`'s tab shows it: only that tab's records. `nil` when there are none.
    func only(_ area: HistoryArea) -> HistoryEntry? {
        guard holds(area) else { return nil }
        return rebuilt { $0 == area }
    }

    /// The session without `area`'s records. The same value when it has none, `nil` when nothing is left.
    func without(_ area: HistoryArea) -> HistoryEntry? {
        guard holds(area) else { return self }
        let rest = rebuilt { $0 != area }
        return HistoryArea.allCases.contains { rest.holds($0) } ? rest : nil
    }

    /// The kinds of records this session actually holds.
    var shownKinds: [String] {
        var found: [String] = []
        if !networks.isEmpty || !probes.isEmpty || !deauths.isEmpty { found.append("networks") }
        if !lan.isEmpty { found.append("lan") }
        if !connections.isEmpty { found.append("internet") }
        if !bluetooth.isEmpty { found.append("bluetooth") }
        if kind == .lora { found.append("lora") }
        return found.isEmpty ? [kind.rawValue] : found
    }

    /// The icon of the first kind of records.
    var rowSystemImage: String {
        let first = shownKinds.first ?? kind.rawValue
        return first == "internet" ? HistoryArea.internet.systemImage : HistoryEntry.Kind(rawValue: first)?.systemImage ?? kind.systemImage
    }

    private func rebuilt(keep: (HistoryArea) -> Bool) -> HistoryEntry {
        var copy = self
        if !keep(HistoryArea.of(kind)) { copy.lines = [] }
        if !keep(.wifi) {
            copy.networks = []
            copy.probes = []
            copy.deauths = []
        }
        if !keep(.lan) { copy.lan = [] }
        if !keep(.bluetooth) { copy.bluetooth = [] }
        if !keep(.internet) { copy.connections = [] }
        return copy
    }
}

extension Array where Element == HistoryEntry {
    /// The sessions as `area`'s tab lists them.
    func forArea(_ area: HistoryArea) -> [HistoryEntry] { compactMap { $0.only(area) } }
}
