import Foundation

/// A Bluetooth tag that can report its position through someone else's
/// phones, and how worried its presence should make you.
nonisolated struct TrackerAssessment: Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        case findMy
        case findMyDevice
        case tile
        case smartTag
        case chipolo
        case other(String)

        var label: String {
            switch self {
            case .findMy: String(localized: "Find My tag")
            case .findMyDevice: String(localized: "Find My Device tag")
            case .tile: "Tile"
            case .smartTag: "Samsung SmartTag"
            case .chipolo: "Chipolo"
            case .other(let name): name
            }
        }
    }

    enum Level: Int, Comparable, Sendable {
        /// Near its owner, or not nearby: nothing to do.
        case info
        /// Away from its owner and nearby, but not for long yet.
        case watch
        /// Away from its owner and continuously near you for a while.
        case alert

        static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }
    }

    var kind: Kind
    /// True when the tag says it has lost its owner; nil when it cannot say.
    var separated: Bool?
    var batteryLabel: String?
    /// How long the tag has been continuously nearby.
    var presentFor: TimeInterval
    var level: Level

    var ownerLabel: String? {
        switch separated {
        case true: String(localized: "Away from its owner")
        case false: String(localized: "Near its owner")
        case nil: nil
        }
    }
}

/// Spots trackers among the advertisements and judges whether one may be
/// travelling with the user. Pure, for tests.
///
/// The judgement is deliberately simple: a tag that has lost its owner and
/// has stayed within earshot for `followWindow` deserves a look. A phone
/// cannot tell a tag in your bag from one in the bag next to you, so the
/// advice is to move and see whether it follows.
nonisolated enum TrackerDetector {
    static let followWindow: TimeInterval = 10 * 60

    static func assess(_ device: BLEDevice, now: Date) -> TrackerAssessment? {
        guard let kind = kind(of: device) else { return nil }
        let separated: Bool?
        var battery: String?
        switch kind {
        case .findMy:
            separated = device.findMyStatus?.separated
            battery = device.findMyStatus?.batteryLabel
        default:
            separated = nil
        }
        let presentFor = device.presentFor(at: now)
        let nearby = device.isNearby(at: now)
        let level: TrackerAssessment.Level
        if !nearby || separated == false {
            level = .info
        } else if presentFor >= followWindow {
            level = .alert
        } else {
            level = .watch
        }
        return TrackerAssessment(kind: kind, separated: separated, batteryLabel: battery, presentFor: presentFor, level: level)
    }

    /// Which kind of tracker the advertisement comes from, if any. Apple
    /// devices that are not tags also send Find My when lost, so a device
    /// with a name or an audio or Siri identity is left out.
    static func kind(of device: BLEDevice) -> TrackerAssessment.Kind? {
        for item in device.serviceData {
            switch BLENumbers.canonical(item.uuid) {
            case "FEED", "FEEC": return .tile
            case "FD5A": return .smartTag
            case "FE2C":
                let bytes = [UInt8](item.data)
                if bytes.first == 0x40, bytes.count >= 21 { return .findMyDevice }
            default: break
            }
        }
        if device.companyID == 0x067C { return .tile }
        if device.companyID == 0x08C3 { return .chipolo }
        if let company = device.companyName?.lowercased() {
            if company.contains("chipolo") { return .chipolo }
            if company.contains("pebblebee") { return .other("Pebblebee") }
            if company.contains("tile") && company.contains("inc") { return .tile }
        }
        // An iPhone or Mac in Lost Mode sends Find My too, next to its other
        // Apple messages; a tag sends Find My and nothing else.
        let onlyFindMy = device.apple.allSatisfy { if case .findMy = $0 { true } else { false } }
        if device.advertisesFindMy, onlyFindMy, !device.hasOwnName {
            return .findMy
        }
        return nil
    }

    /// The trackers in a list, most worrying first, then strongest first.
    static func trackers(in devices: [BLEDevice], now: Date) -> [(device: BLEDevice, assessment: TrackerAssessment)] {
        devices.compactMap { device in
            assess(device, now: now).map { (device, $0) }
        }
        .sorted { a, b in
            if a.assessment.level != b.assessment.level { return a.assessment.level > b.assessment.level }
            return a.device.rssi > b.device.rssi
        }
    }
}
