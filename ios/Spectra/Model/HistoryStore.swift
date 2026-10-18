import Foundation
import Observation

struct HistoryLine: Identifiable, Codable, Equatable {
    var id = UUID()
    var title: String
    var detail: String
    var trailing: String
}

struct HistoryEntry: Identifiable, Codable, Equatable {
    enum Kind: String, Codable, CaseIterable {
        case networks, lan, bluetooth, lora

        var title: String {
            switch self {
            case .networks: "Wi‑Fi"
            case .lan: "LAN"
            case .bluetooth: "Bluetooth"
            case .lora: "LoRa"
            }
        }

        var systemImage: String {
            switch self {
            case .networks: "wifi"
            case .lan: "network"
            case .bluetooth: "wave.3.right"
            case .lora: "antenna.radiowaves.left.and.right"
            }
        }
    }

    var id = UUID()
    var kind: Kind
    var savedAt: Date
    var source: String
    /// Set when the Cardputer stored the capture. The same id is not imported twice.
    var captureID: UInt32?
    /// Stable key grouping scans into one session: a geo bucket, or the LAN
    /// network. Entries with the same (day + kind + placeKey) are merged.
    var placeKey: String?
    /// Human label for the place, shown in the by-place view.
    var placeLabel: String?
    /// Summary rows from the Cardputer. A phone save stores the records themselves.
    var lines: [HistoryLine] = []
    var networks: [TrackedNetwork] = []
    var probes: [ProbingDevice] = []
    var deauths: [DeauthEvent] = []
    var lan: [SavedLAN] = []
    var bluetooth: [SavedBLE] = []
    /// Where this scan was saved. The phone's own fix, or the cap's fix on a sensor capture.
    var latitude: Double?
    var longitude: Double?

    var itemCount: Int {
        switch kind {
        case .networks:
            let found = networks.count + probes.count + deauths.count
            return found == 0 ? lines.count : found
        case .lan:
            return lan.isEmpty ? lines.count : lan.count
        case .bluetooth:
            return bluetooth.isEmpty ? lines.count : bluetooth.count
        case .lora:
            return lines.count
        }
    }

    init(
        kind: Kind,
        savedAt: Date,
        source: String,
        captureID: UInt32? = nil,
        placeKey: String? = nil,
        placeLabel: String? = nil,
        lines: [HistoryLine] = [],
        networks: [TrackedNetwork] = [],
        probes: [ProbingDevice] = [],
        deauths: [DeauthEvent] = [],
        lan: [SavedLAN] = [],
        bluetooth: [SavedBLE] = [],
        latitude: Double? = nil,
        longitude: Double? = nil
    ) {
        self.kind = kind
        self.savedAt = savedAt
        self.source = source
        self.captureID = captureID
        self.placeKey = placeKey
        self.placeLabel = placeLabel
        self.lines = lines
        self.networks = networks
        self.probes = probes
        self.deauths = deauths
        self.lan = lan
        self.bluetooth = bluetooth
        self.latitude = latitude
        self.longitude = longitude
    }

    private enum CodingKeys: String, CodingKey {
        case id, kind, savedAt, source, captureID, placeKey, placeLabel,
             lines, networks, probes, deauths, lan, bluetooth, latitude, longitude
    }

    func encode(to encoder: Encoder) throws {
        var box = encoder.container(keyedBy: CodingKeys.self)
        try box.encode(id, forKey: .id)
        try box.encode(kind, forKey: .kind)
        try box.encode(savedAt, forKey: .savedAt)
        try box.encode(source, forKey: .source)
        try box.encodeIfPresent(captureID, forKey: .captureID)
        try box.encodeIfPresent(placeKey, forKey: .placeKey)
        try box.encodeIfPresent(placeLabel, forKey: .placeLabel)
        try box.encode(lines, forKey: .lines)
        try box.encode(networks, forKey: .networks)
        try box.encode(probes, forKey: .probes)
        try box.encode(deauths, forKey: .deauths)
        try box.encode(lan, forKey: .lan)
        try box.encode(bluetooth, forKey: .bluetooth)
        try box.encodeIfPresent(latitude, forKey: .latitude)
        try box.encodeIfPresent(longitude, forKey: .longitude)
    }

    /// Older files only have `lines`. Missing keys stay empty.
    init(from decoder: Decoder) throws {
        let box = try decoder.container(keyedBy: CodingKeys.self)
        id = try box.decode(UUID.self, forKey: .id)
        kind = try box.decode(Kind.self, forKey: .kind)
        savedAt = try box.decode(Date.self, forKey: .savedAt)
        source = try box.decode(String.self, forKey: .source)
        captureID = try box.decodeIfPresent(UInt32.self, forKey: .captureID)
        placeKey = try box.decodeIfPresent(String.self, forKey: .placeKey)
        placeLabel = try box.decodeIfPresent(String.self, forKey: .placeLabel)
        lines = try box.decodeIfPresent([HistoryLine].self, forKey: .lines) ?? []
        networks = try box.decodeIfPresent([TrackedNetwork].self, forKey: .networks) ?? []
        probes = try box.decodeIfPresent([ProbingDevice].self, forKey: .probes) ?? []
        deauths = try box.decodeIfPresent([DeauthEvent].self, forKey: .deauths) ?? []
        lan = try box.decodeIfPresent([SavedLAN].self, forKey: .lan) ?? []
        bluetooth = try box.decodeIfPresent([SavedBLE].self, forKey: .bluetooth) ?? []
        latitude = try box.decodeIfPresent(Double.self, forKey: .latitude)
        longitude = try box.decodeIfPresent(Double.self, forKey: .longitude)
    }
}

/// A LAN host as it was when the list was saved. Rebuilt into a `LANDevice` to open the same card.
struct SavedLAN: Codable, Equatable, Identifiable {
    var ipAddress: String
    var macAddress: MACAddress?
    var hostnames: [String]
    var openPorts: [DiscoveredPort]
    var services: [ServiceAdvertisement]
    var portsScanned: Bool
    var isGateway: Bool
    var isLocalDevice: Bool
    var profile: LANProfile
    var firstSeen: Date
    var lastSeen: Date

    var id: String { ipAddress }

    init(_ device: LANDevice) {
        ipAddress = device.ipAddress
        macAddress = device.macAddress
        hostnames = device.hostnames
        openPorts = device.openPorts
        services = device.services
        portsScanned = device.portsScanned
        isGateway = device.isGateway
        isLocalDevice = device.isLocalDevice
        profile = device.profile
        firstSeen = device.firstSeen
        lastSeen = device.lastSeen
    }

    func device() -> LANDevice {
        LANDevice(
            ipAddress: ipAddress,
            macAddress: macAddress,
            hostnames: hostnames,
            openPorts: openPorts,
            services: services,
            portsScanned: portsScanned,
            isGateway: isGateway,
            isLocalDevice: isLocalDevice,
            profile: profile,
            firstSeen: firstSeen,
            lastSeen: lastSeen
        )
    }
}

/// A Bluetooth device as it was when the list was saved.
struct SavedBLE: Codable, Equatable, Identifiable {
    struct Sample: Codable, Equatable {
        var date: Date
        var rssi: Int
    }

    var id: UUID
    var localName: String?
    var peripheralName: String?
    var companyID: UInt16?
    var companyName: String?
    var manufacturerPayload: Data
    var services: [BLEServiceRef]
    var solicitedServices: [BLEServiceRef]
    var serviceData: [BLEServiceData]
    var txPower: Int?
    var isConnectable: Bool?
    var apple: [AppleAdvertisement]
    var rssi: Int
    var samples: [Sample]
    var packetCount: Int
    var firstSeen: Date
    var lastSeen: Date

    init(_ device: BLEDevice) {
        id = device.id
        localName = device.localName
        peripheralName = device.peripheralName
        companyID = device.companyID
        companyName = device.companyName
        manufacturerPayload = device.manufacturerPayload
        services = device.services
        solicitedServices = device.solicitedServices
        serviceData = device.serviceData
        txPower = device.txPower
        isConnectable = device.isConnectable
        apple = device.apple
        rssi = device.rssi
        samples = device.samples.map { Sample(date: $0.date, rssi: $0.rssi) }
        packetCount = device.packetCount
        firstSeen = device.firstSeen
        lastSeen = device.lastSeen
    }

    func device() -> BLEDevice {
        let restored = BLEDevice(id: id, rssi: rssi, seen: firstSeen)
        restored.localName = localName
        restored.peripheralName = peripheralName
        restored.companyID = companyID
        restored.companyName = companyName
        restored.manufacturerPayload = manufacturerPayload
        restored.services = services
        restored.solicitedServices = solicitedServices
        restored.serviceData = serviceData
        restored.txPower = txPower
        restored.isConnectable = isConnectable
        restored.apple = apple
        restored.samples = samples.enumerated().map { BLEDevice.Sample(id: $0.offset, date: $0.element.date, rssi: $0.element.rssi) }
        restored.packetCount = packetCount
        restored.lastSeen = lastSeen
        return restored
    }
}

/// What was just saved, for the confirmation the app shows. Phone-side scans
/// autosave silently; only a sensor capture raises this.
struct SaveNotice: Equatable {
    let id = UUID()
    var kind: HistoryEntry.Kind
    var count: Int
    var fromSensor: Bool
}

/// Saved scans from the phone and from the Cardputer's side button.
///
/// One entry collects a whole session: scans of the same kind, on the same day,
/// in the same place are merged into it rather than piling up as duplicates. A
/// record (network, host, device) is folded in by identity, so `firstSeen`
/// keeps the earliest sighting and `lastSeen` the most recent.
@Observable
final class HistoryStore {
    private(set) var entries: [HistoryEntry] = []
    /// The latest sensor capture, for the confirmation toast. Phone autosaves
    /// are silent.
    private(set) var lastSave: SaveNotice?
    /// Supplies the phone's current coordinate for a phone-side save. Set by the
    /// app from `LocationService`; nil when location is unavailable or denied.
    var locationProvider: (() -> (latitude: Double, longitude: Double)?)?

    private struct Partial {
        var kind: HistoryEntry.Kind
        var unix: UInt32
        var total: Int
        var lines: [Int: HistoryLine] = [:]
        var latitude: Double?
        var longitude: Double?
    }

    private var pendingFix: [UInt32: (latitude: Double, longitude: Double)] = [:]

    private var partials: [UInt32: Partial] = [:]
    private let file: URL

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        file = base.appendingPathComponent("spectra-history.json")
        if let data = try? Data(contentsOf: file),
           let decoded = try? JSONDecoder().decode([HistoryEntry].self, from: data) {
            entries = decoded
        }
    }

    // MARK: Phone-side autosave (upsert)

    func upsertNetworks(_ store: NetworkStore, source: String) {
        let networks = store.networks.values.sorted { a, b in
            if a.isInRange != b.isInRange { return a.isInRange }
            return a.latest.rssi > b.latest.rssi
        }
        let probes = store.probingDevices
        let deauths = store.deauthEvents
        guard !networks.isEmpty || !probes.isEmpty || !deauths.isEmpty else { return }
        let here = locationProvider?()
        upsert(
            kind: .networks, source: source,
            placeKey: Self.geoBucket(here), placeLabel: Self.geoLabel(here),
            networks: networks, probes: probes, deauths: deauths,
            latitude: here?.latitude, longitude: here?.longitude
        )
    }

    func upsertLAN(_ devices: [LANDevice], networkKey: String?, networkLabel: String?, source: String) {
        guard !devices.isEmpty else { return }
        let saved = devices.map { SavedLAN($0) }
        let here = locationProvider?()
        upsert(
            kind: .lan, source: source,
            placeKey: networkKey ?? Self.geoBucket(here),
            placeLabel: networkLabel ?? Self.geoLabel(here),
            lan: saved, latitude: here?.latitude, longitude: here?.longitude
        )
    }

    func upsertBluetooth(_ devices: [BLEDevice], source: String) {
        let rows = devices.sorted { $0.rssi > $1.rssi }
        guard !rows.isEmpty else { return }
        let saved = rows.map { SavedBLE($0) }
        let here = locationProvider?()
        upsert(
            kind: .bluetooth, source: source,
            placeKey: Self.geoBucket(here), placeLabel: Self.geoLabel(here),
            bluetooth: saved, latitude: here?.latitude, longitude: here?.longitude
        )
    }

    /// ~55 m geo bucket (0.0005°). Empty when there is no fix, so fixless scans
    /// of a kind on a day still merge together.
    static func geoBucket(_ location: (latitude: Double, longitude: Double)?) -> String {
        guard let location else { return "" }
        let scale = 2000.0 // 1 / 0.0005
        let lat = Int((location.latitude * scale).rounded())
        let lon = Int((location.longitude * scale).rounded())
        return "geo:\(lat)_\(lon)"
    }

    static func geoLabel(_ location: (latitude: Double, longitude: Double)?) -> String? {
        guard let location else { return nil }
        return String(format: "%.4f, %.4f", location.latitude, location.longitude)
    }

    /// The cap's fix for a capture. It can arrive before the rows, and again
    /// on a later connection after the capture is already stored.
    func notePosition(captureID: UInt32, source: String, latitude: Double, longitude: Double) {
        pendingFix[captureID] = (latitude, longitude)
        if var partial = partials[captureID] {
            partial.latitude = latitude
            partial.longitude = longitude
            partials[captureID] = partial
        }
        guard let index = entries.firstIndex(where: { $0.captureID == captureID && $0.source == source }) else {
            return
        }
        entries[index].latitude = latitude
        entries[index].longitude = longitude
        persist()
    }

    /// Folds one record from the sensor. Returns true once the capture is stored
    /// (or was already stored) and the sensor may delete it.
    func ingest(_ record: SensorProtocol.SavedRecord, source: String) -> Bool {
        if entries.contains(where: { $0.captureID == record.captureID && $0.source == source }) {
            return true
        }
        guard let kind = HistoryEntry.Kind(record.kind),
              record.total > 0, record.index >= 0, record.index < record.total else {
            return false
        }
        var partial = partials[record.captureID] ?? Partial(kind: kind, unix: record.unix, total: record.total)
        if partial.latitude == nil, let fix = pendingFix[record.captureID] {
            partial.latitude = fix.latitude
            partial.longitude = fix.longitude
        }
        partial.total = record.total
        partial.lines[record.index] = HistoryLine(
            title: record.title, detail: record.detail, trailing: record.trailing
        )
        guard partial.lines.count >= partial.total else {
            partials[record.captureID] = partial
            return false
        }
        partials[record.captureID] = nil
        pendingFix[record.captureID] = nil
        let ordered = (0..<partial.total).compactMap { partial.lines[$0] }
        let when = partial.unix == 0 ? Date() : Date(timeIntervalSince1970: TimeInterval(partial.unix))
        let here = partial.latitude.flatMap { lat in partial.longitude.map { (lat, $0) } }
        let entry = HistoryEntry(
            kind: partial.kind, savedAt: when, source: source, captureID: record.captureID,
            placeKey: Self.geoBucket(here), placeLabel: Self.geoLabel(here), lines: ordered,
            latitude: partial.latitude, longitude: partial.longitude
        )
        guard entry.itemCount > 0 else { return true }
        lastSave = SaveNotice(kind: partial.kind, count: entry.itemCount, fromSensor: true)
        entries.insert(entry, at: 0)
        trim()
        persist()
        return true
    }

    func delete(_ id: UUID) {
        entries.removeAll { $0.id == id }
        persist()
    }

    func clear() {
        entries = []
        partials = [:]
        pendingFix = [:]
        persist()
    }

    // MARK: - Session upsert

    private func upsert(
        kind: HistoryEntry.Kind,
        source: String,
        placeKey: String,
        placeLabel: String?,
        lines: [HistoryLine] = [],
        networks: [TrackedNetwork] = [],
        probes: [ProbingDevice] = [],
        deauths: [DeauthEvent] = [],
        lan: [SavedLAN] = [],
        bluetooth: [SavedBLE] = [],
        latitude: Double? = nil,
        longitude: Double? = nil
    ) {
        let now = Date()
        // Merge into today's session for the same kind and place (sensor
        // captures keep their own entries, matched by captureID).
        if let index = entries.firstIndex(where: {
            $0.kind == kind && $0.captureID == nil && ($0.placeKey ?? "") == placeKey
                && Calendar.current.isDate($0.savedAt, inSameDayAs: now)
        }) {
            var entry = entries[index]
            entry.savedAt = now
            entry.source = source
            if placeLabel != nil { entry.placeLabel = placeLabel }
            if let latitude, let longitude {
                entry.latitude = latitude
                entry.longitude = longitude
            }
            entry.networks = Self.mergeNetworks(entry.networks, networks)
            entry.probes = Self.mergeByID(entry.probes, probes)
            entry.deauths = Self.mergeByID(entry.deauths, deauths)
            entry.lan = Self.mergeLAN(entry.lan, lan)
            entry.bluetooth = Self.mergeBLE(entry.bluetooth, bluetooth)
            entry.lines = Self.mergeLines(entry.lines, lines)
            entries.remove(at: index)
            entries.insert(entry, at: 0)
            persist()
            return
        }
        let entry = HistoryEntry(
            kind: kind, savedAt: now, source: source,
            placeKey: placeKey, placeLabel: placeLabel,
            lines: lines, networks: networks, probes: probes, deauths: deauths,
            lan: lan, bluetooth: bluetooth, latitude: latitude, longitude: longitude
        )
        guard entry.itemCount > 0 else { return }
        entries.insert(entry, at: 0)
        trim()
        persist()
    }

    private func trim() {
        if entries.count > 100 {
            entries.removeLast(entries.count - 100)
        }
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        try? data.write(to: file, options: .atomic)
    }

    // MARK: - Merge helpers (union by identity, min firstSeen / max lastSeen)

    private static func mergeByID<T: Identifiable>(_ existing: [T], _ incoming: [T]) -> [T] where T.ID: Hashable {
        var result = existing
        var index = Dictionary(existing.enumerated().map { ($0.element.id, $0.offset) }, uniquingKeysWith: { a, _ in a })
        for item in incoming {
            if let at = index[item.id] {
                result[at] = item
            } else {
                index[item.id] = result.count
                result.append(item)
            }
        }
        return result
    }

    static func mergeNetworks(_ existing: [TrackedNetwork], _ incoming: [TrackedNetwork]) -> [TrackedNetwork] {
        var byID = Dictionary(uniqueKeysWithValues: existing.map { ($0.id, $0) })
        for new in incoming {
            guard var old = byID[new.id] else { byID[new.id] = new; continue }
            // Keep whichever reading is more recent as the representative one.
            if new.lastSeen >= old.lastSeen {
                old.latest = new.latest
                old.clients = new.clients.isEmpty ? old.clients : new.clients
                old.missedScans = new.missedScans
                old.traffic = new.traffic ?? old.traffic
            }
            old.firstSeen = min(old.firstSeen, new.firstSeen)
            old.lastSeen = max(old.lastSeen, new.lastSeen)
            old.revealedSSID = new.revealedSSID ?? old.revealedSSID
            old.vendorInfo = new.vendorInfo ?? old.vendorInfo
            old.samples = mergeSamples(old.samples, new.samples, limit: NetworkStore.samplesKept)
            byID[new.id] = old
        }
        return byID.values.sorted { a, b in
            if a.isInRange != b.isInRange { return a.isInRange }
            return a.latest.rssi > b.latest.rssi
        }
    }

    static func mergeLAN(_ existing: [SavedLAN], _ incoming: [SavedLAN]) -> [SavedLAN] {
        var byIP = Dictionary(uniqueKeysWithValues: existing.map { ($0.ipAddress, $0) })
        for new in incoming {
            guard var old = byIP[new.ipAddress] else { byIP[new.ipAddress] = new; continue }
            let newer = new.lastSeen >= old.lastSeen ? new : old
            var merged = newer
            merged.firstSeen = min(old.firstSeen, new.firstSeen)
            merged.lastSeen = max(old.lastSeen, new.lastSeen)
            merged.hostnames = newer.hostnames.isEmpty ? (old.hostnames.isEmpty ? new.hostnames : old.hostnames) : newer.hostnames
            merged.openPorts = newer.openPorts.isEmpty ? (old.openPorts.isEmpty ? new.openPorts : old.openPorts) : newer.openPorts
            merged.services = newer.services.isEmpty ? (old.services.isEmpty ? new.services : old.services) : newer.services
            merged.macAddress = newer.macAddress ?? old.macAddress ?? new.macAddress
            byIP[new.ipAddress] = merged
        }
        return byIP.values.sorted { a, b in
            if a.isLocalDevice != b.isLocalDevice { return a.isLocalDevice }
            if a.isGateway != b.isGateway { return a.isGateway }
            return (parseIP(a.ipAddress) ?? 0) < (parseIP(b.ipAddress) ?? 0)
        }
    }

    static func mergeBLE(_ existing: [SavedBLE], _ incoming: [SavedBLE]) -> [SavedBLE] {
        var byID = Dictionary(uniqueKeysWithValues: existing.map { ($0.id, $0) })
        for new in incoming {
            guard let old = byID[new.id] else { byID[new.id] = new; continue }
            var merged = new.lastSeen >= old.lastSeen ? new : old
            merged.firstSeen = min(old.firstSeen, new.firstSeen)
            merged.lastSeen = max(old.lastSeen, new.lastSeen)
            merged.packetCount = old.packetCount + new.packetCount
            merged.samples = mergeBLESamples(old.samples, new.samples, limit: 120)
            byID[new.id] = merged
        }
        return byID.values.sorted { $0.rssi > $1.rssi }
    }

    static func mergeLines(_ existing: [HistoryLine], _ incoming: [HistoryLine]) -> [HistoryLine] {
        var result = existing
        for line in incoming where !result.contains(where: { $0.title == line.title }) {
            result.append(line)
        }
        return result
    }

    private static func mergeSamples(_ a: [TrackedNetwork.Sample], _ b: [TrackedNetwork.Sample], limit: Int) -> [TrackedNetwork.Sample] {
        var seen = Set(a.map(\.date))
        var merged = a
        for sample in b where seen.insert(sample.date).inserted { merged.append(sample) }
        merged.sort { $0.date < $1.date }
        if merged.count > limit { merged.removeFirst(merged.count - limit) }
        return merged
    }

    private static func mergeBLESamples(_ a: [SavedBLE.Sample], _ b: [SavedBLE.Sample], limit: Int) -> [SavedBLE.Sample] {
        var seen = Set(a.map(\.date))
        var merged = a
        for sample in b where seen.insert(sample.date).inserted { merged.append(sample) }
        merged.sort { $0.date < $1.date }
        if merged.count > limit { merged.removeFirst(merged.count - limit) }
        return merged
    }
}

private extension HistoryEntry.Kind {
    init?(_ kind: SensorProtocol.SavedRecord.Kind) {
        switch kind {
        case .networks: self = .networks
        case .lan: self = .lan
        case .bluetooth: self = .bluetooth
        case .lora: self = .lora
        }
    }
}
