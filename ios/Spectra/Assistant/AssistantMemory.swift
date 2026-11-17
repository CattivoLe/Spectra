import Foundation
import Observation

/// What the assistant keeps between chats: notes it wrote down, and what each device has looked like over time.
///
/// Notes carry an optional subject — an address, a BSSID, a device name — and only the notes whose subject is
/// in front of the user right now travel with a question. Without that every note rode along with every
/// question, so the longer the app was used the more of the data budget the dossier ate.
@MainActor
@Observable
final class AssistantMemory {
    struct Note: Codable, Identifiable, Equatable, Sendable {
        var id: UUID
        var text: String
        var createdAt: Date
        /// Normalised address or name this note is about. `nil` means it is about the user in general.
        var subject: String?
    }

    /// What one device has been, across every time it was seen.
    struct Baseline: Codable, Equatable, Sendable {
        enum Kind: String, Codable, Sendable { case host, wifi, bluetooth }

        var subject: String
        var label: String
        var kind: Kind
        var firstSeen: Date
        var lastSeen: Date
        var sightings: Int
        /// Every port ever seen open on it.
        var ports: [Int] = []
        var rssiStrongest: Int?
        var rssiWeakest: Int?

        /// Seen often enough, and for long enough, to call anything "usual" for it.
        var isEstablished: Bool { sightings >= 3 && lastSeen.timeIntervalSince(firstSeen) >= 3600 }
    }

    /// One device as it looks right now, on its way into a baseline.
    struct Sighting: Sendable {
        var subject: String
        var label: String
        var kind: Baseline.Kind
        var rssi: Int?
        var ports: [Int] = []
        /// False while the ports of this device have not been probed, so an empty list is "unknown", not "none".
        var portsKnown = false
    }

    private(set) var notes: [Note] = []
    private(set) var baselines: [String: Baseline] = [:]
    private let url: URL

    /// How many notes are kept at all. Past this the oldest general notes go first: a note about a device is
    /// worth more than another sentence about the user in general.
    static let noteLimit = 120

    init(url: URL = URL.applicationSupportDirectory.appending(path: "Spectra/assistant_memory.json")) {
        self.url = url
        load()
    }

    // MARK: - Notes

    func add(_ text: String, subject: String? = nil) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let key = Self.normalize(subject)
        guard !notes.contains(where: { $0.text == trimmed && $0.subject == key }) else { return }
        notes.append(Note(id: UUID(), text: trimmed, createdAt: Date(), subject: key))
        if notes.count > Self.noteLimit {
            // Drop the oldest unattached note; if they are all attached, the oldest of those.
            let victim = notes.firstIndex { $0.subject == nil } ?? 0
            notes.remove(at: victim)
        }
        save()
    }

    func remove(id: UUID) {
        notes.removeAll { $0.id == id }
        save()
    }

    func remove(matching text: String) -> Bool {
        let lowered = text.lowercased()
        let before = notes.count
        notes.removeAll { $0.text.lowercased().contains(lowered) || $0.subject == Self.normalize(text) }
        if notes.count != before { save(); return true }
        return false
    }

    func removeAll() {
        notes.removeAll()
        baselines.removeAll()
        save()
    }

    // MARK: - Baselines

    /// Folds what is on screen now into the long-term picture. Called often and cheap on purpose: it only
    /// touches devices whose entry actually changed.
    func observe(_ sightings: [Sighting], now: Date = Date()) {
        guard !sightings.isEmpty else { return }
        var changed = false
        for sighting in sightings {
            let key = Self.normalize(sighting.subject)
            guard let key else { continue }
            var entry = baselines[key] ?? Baseline(
                subject: key, label: sighting.label, kind: sighting.kind,
                firstSeen: now, lastSeen: now, sightings: 0
            )
            // A sighting within the same few minutes is the same visit, not a new one.
            if entry.sightings == 0 || now.timeIntervalSince(entry.lastSeen) > 300 {
                entry.sightings += 1
                changed = true
            }
            if entry.label != sighting.label, !sighting.label.isEmpty {
                entry.label = sighting.label
                changed = true
            }
            entry.lastSeen = now
            if let rssi = sighting.rssi {
                if rssi > (entry.rssiStrongest ?? Int.min) { entry.rssiStrongest = rssi; changed = true }
                if rssi < (entry.rssiWeakest ?? Int.max) { entry.rssiWeakest = rssi; changed = true }
            }
            if sighting.portsKnown {
                let merged = Set(entry.ports).union(sighting.ports).sorted()
                if merged != entry.ports { entry.ports = merged; changed = true }
            }
            baselines[key] = entry
        }
        if changed { save() }
    }

    /// What is unusual about this device compared with every earlier time it was seen. Empty when there is no
    /// baseline worth comparing against — a device seen once proves nothing.
    func departures(for sighting: Sighting, now: Date = Date()) -> [String] {
        guard let key = Self.normalize(sighting.subject), let base = baselines[key], base.isEstablished else { return [] }
        var found: [String] = []
        if sighting.portsKnown {
            let fresh = Set(sighting.ports).subtracting(base.ports).sorted()
            if !fresh.isEmpty {
                found.append("ports not seen on it before: " + fresh.map(String.init).joined(separator: ", "))
            }
        }
        if now.timeIntervalSince(base.lastSeen) > 7 * 24 * 3600 {
            found.append("not seen for over a week before now")
        }
        return found
    }

    // MARK: - What travels with a question

    /// The dossier as the model sees it: notes about what is in front of the user, the general ones, and what
    /// the app has learned about the devices named. `subjects` are the addresses and names in the snapshot and
    /// in the question, already lower-cased.
    func digest(subjects: Set<String>, budget: Int = 700, now: Date = Date()) -> String? {
        let relevant = notes.filter { note in
            guard let subject = note.subject else { return true }
            return subjects.contains(subject) || subjects.contains { $0.contains(subject) || subject.contains($0) }
        }
        let established = baselines.values
            .filter { $0.isEstablished && subjects.contains($0.subject) }
            .sorted { $0.sightings > $1.sightings }

        guard !relevant.isEmpty || !established.isEmpty else { return nil }

        var lines = ["Dossier (what you noted in earlier chats, and what Spectra has seen before):"]
        // Newest last, so a note written minutes ago is the one that survives the budget cut below.
        for note in relevant.sorted(by: { $0.createdAt < $1.createdAt }).suffix(20) {
            lines.append("- " + (note.subject.map { "[\($0)] " } ?? "") + note.text)
        }
        for base in established.prefix(6) {
            var parts = ["\(base.label) (\(base.subject))"]
            parts.append("seen \(base.sightings)× since \(Self.day(base.firstSeen))")
            if !base.ports.isEmpty { parts.append("usual ports: " + base.ports.prefix(8).map(String.init).joined(separator: ", ")) }
            if let strong = base.rssiStrongest, let weak = base.rssiWeakest, strong != weak {
                parts.append("signal \(weak)…\(strong) dBm")
            }
            lines.append("- usual for " + parts.joined(separator: " · "))
        }

        var text = lines.joined(separator: "\n")
        if text.count > budget {
            text = String(text.prefix(budget)) + "\n…(dossier trimmed)"
        }
        return text
    }

    /// Lower-cased and stripped of the separators that differ between an address written by hand and one the
    /// app prints, so `AA:BB:CC` and `aa-bb-cc` are one subject.
    nonisolated static func normalize(_ subject: String?) -> String? {
        guard let subject else { return nil }
        let flat = subject.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return flat.isEmpty ? nil : flat
    }

    private static func day(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    // MARK: - Persistence

    private struct Stored: Codable {
        var notes: [Note]
        var baselines: [String: Baseline]
    }

    private func load() {
        guard let data = try? Data(contentsOf: url) else { return }
        // Dates are written as ISO-8601; reading them with the default strategy expects a number and throws,
        // which is why the dossier used to come back empty after every restart.
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        if let decoded = try? decoder.decode(Stored.self, from: data) {
            notes = decoded.notes
            baselines = decoded.baselines
        } else if let legacy = try? decoder.decode([Note].self, from: data) {
            // The first shape this file had: a bare list of notes, none of them attached to anything.
            notes = legacy
        }
    }

    private func save() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(Stored(notes: notes, baselines: baselines)) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: .atomic)
    }
}
