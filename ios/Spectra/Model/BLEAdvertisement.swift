import Foundation

/// What CoreBluetooth reported for one advertisement, already pulled out of
/// the dictionary so the decode can be tested without a radio.
nonisolated struct BLEFields: Equatable, Sendable {
    var localName: String?
    var manufacturerData: Data?
    var serviceUUIDs: [String] = []
    var overflowServiceUUIDs: [String] = []
    var solicitedServiceUUIDs: [String] = []
    var serviceData: [String: Data] = [:]
    var txPower: Int?
    var isConnectable: Bool?
}

nonisolated struct BLEServiceRef: Equatable, Sendable, Identifiable {
    var uuid: String
    var name: String?
    var id: String { uuid }
}

nonisolated struct BLEServiceData: Equatable, Sendable, Identifiable {
    var uuid: String
    var name: String?
    var data: Data
    var id: String { uuid }
}

/// One Apple continuity message. Layout follows the furiousMAC dissector:
/// company id `0x004C`, then type, length, and that many payload bytes.
/// Several messages are concatenated in one advertisement.
nonisolated enum AppleAdvertisement: Equatable, Sendable {
    case airPrint
    case iBeacon(uuid: UUID, major: UInt16, minor: UInt16, measuredPower: Int)
    case airDrop
    case homeKit(category: String?)
    case proximity(ProximityPairing)
    case heySiri(device: String?)
    case airPlayTarget
    case airPlaySource
    case autoUnlock
    case handoff
    case wifiSettings
    case instantHotspot
    case nearbyAction(NearbyAction?)
    case nearbyInfo(NearbyActivity?)
    case findMy

    /// A name worth showing in place of the company, when the packet has no
    /// local name. Background chatter (Nearby, Handoff) is left out: almost
    /// every Apple device emits it.
    var distinctiveName: String? {
        switch self {
        case .airPrint: String(localized: "AirPrint")
        case .iBeacon: String(localized: "iBeacon")
        case .airDrop: String(localized: "AirDrop")
        case .homeKit(let category): category ?? String(localized: "HomeKit")
        case .proximity(let pairing): pairing.modelName ?? String(localized: "Apple audio")
        case .heySiri(let device): device ?? String(localized: "Hey Siri")
        case .airPlayTarget, .airPlaySource: String(localized: "AirPlay")
        case .findMy: String(localized: "Find My")
        case .autoUnlock, .handoff, .wifiSettings, .instantHotspot, .nearbyAction, .nearbyInfo:
            nil
        }
    }

    /// Lower is a better name. Transient messages stay below the ones that
    /// identify the product, so a phone that briefly advertises AirDrop does
    /// not rename itself.
    var nameRank: Int? {
        switch self {
        case .proximity, .heySiri: 0
        case .homeKit: 1
        case .airPlayTarget, .airPlaySource: 2
        case .findMy: 3
        case .iBeacon: 4
        case .airPrint: 5
        case .airDrop: 6
        case .autoUnlock, .handoff, .wifiSettings, .instantHotspot, .nearbyAction, .nearbyInfo:
            nil
        }
    }

    var kindID: String {
        switch self {
        case .airPrint: "airprint"
        case .iBeacon: "ibeacon"
        case .airDrop: "airdrop"
        case .homeKit: "homekit"
        case .proximity: "proximity"
        case .heySiri: "siri"
        case .airPlayTarget: "airplay-target"
        case .airPlaySource: "airplay-source"
        case .autoUnlock: "unlock"
        case .handoff: "handoff"
        case .wifiSettings: "wifi"
        case .instantHotspot: "hotspot"
        case .nearbyAction: "action"
        case .nearbyInfo: "info"
        case .findMy: "findmy"
        }
    }

    var label: String {
        switch self {
        case .airPrint: String(localized: "AirPrint")
        case .iBeacon: String(localized: "iBeacon")
        case .airDrop: String(localized: "AirDrop")
        case .homeKit(let category): category ?? String(localized: "HomeKit")
        case .proximity(let pairing): pairing.modelLabel
        case .heySiri(let device): device ?? String(localized: "Hey Siri")
        case .airPlayTarget: String(localized: "AirPlay target")
        case .airPlaySource: String(localized: "AirPlay source")
        case .autoUnlock: String(localized: "Auto Unlock")
        case .handoff: String(localized: "Handoff")
        case .wifiSettings: String(localized: "Wi-Fi settings")
        case .instantHotspot: String(localized: "Instant Hotspot")
        case .nearbyAction(let action): action?.label ?? String(localized: "Nearby")
        case .nearbyInfo(let activity): activity?.label ?? String(localized: "Nearby")
        case .findMy: String(localized: "Find My")
        }
    }

    var detail: String? {
        switch self {
        case .proximity(let pairing): pairing.batterySummary
        case .iBeacon(let uuid, let major, let minor, let power):
            "\(uuid.uuidString) · \(major) · \(minor) · \(String(localized: "\(power) dBm"))"
        case .homeKit(let category): category == nil ? nil : String(localized: "HomeKit")
        default: nil
        }
    }
}

/// How busy a nearby Apple device says it is. The code is the low nibble of
/// the first Nearby Info status byte.
nonisolated enum NearbyActivity: UInt8, Equatable, Sendable {
    case reportingOff = 1
    case locked = 3
    case audio = 5
    case screenChange = 7
    case video = 9
    case watchNotifications = 10
    case active = 11
    case driving = 13
    case call = 14

    var label: String {
        switch self {
        case .reportingOff: String(localized: "Activity off")
        case .locked: String(localized: "Locked")
        case .audio: String(localized: "Audio playing")
        case .screenChange: String(localized: "Screen changing")
        case .video: String(localized: "Video playing")
        case .watchNotifications: String(localized: "Notifying a Watch")
        case .active: String(localized: "In use")
        case .driving: String(localized: "Driving")
        case .call: String(localized: "On a call")
        }
    }
}

/// Why a device sent a Nearby Action. Values that are only useful as
/// protocol noise are left unmapped, and the row falls back to "Nearby".
nonisolated enum NearbyAction: UInt8, Equatable, Sendable {
    case appleTVSetup = 0x01
    case mobileBackup = 0x04
    case watchSetup = 0x05
    case appleTVPair = 0x06
    case internetRelay = 0x07
    case wifiPassword = 0x08
    case iosSetup = 0x09
    case repair = 0x0A
    case speakerSetup = 0x0B
    case applePay = 0x0C
    case wholeHomeAudio = 0x0D
    case developerPairing = 0x0E
    case answeredCall = 0x0F
    case endedCall = 0x10
    case companionLink = 0x14
    case remoteManagement = 0x15
    case remoteDisplay = 0x17

    var label: String {
        switch self {
        case .appleTVSetup: String(localized: "Apple TV setup")
        case .mobileBackup: String(localized: "Mobile backup")
        case .watchSetup: String(localized: "Watch setup")
        case .appleTVPair: String(localized: "Apple TV pairing")
        case .internetRelay: String(localized: "Internet relay")
        case .wifiPassword: String(localized: "Wi-Fi password")
        case .iosSetup: String(localized: "iOS setup")
        case .repair: String(localized: "Repair")
        case .speakerSetup: String(localized: "Speaker setup")
        case .applePay: String(localized: "Apple Pay")
        case .wholeHomeAudio: String(localized: "Whole-home audio")
        case .developerPairing: String(localized: "Developer pairing")
        case .answeredCall: String(localized: "Call answered")
        case .endedCall: String(localized: "Call ended")
        case .companionLink: String(localized: "Companion Link")
        case .remoteManagement: String(localized: "Remote management")
        case .remoteDisplay: String(localized: "Remote display")
        }
    }
}

/// AirPods and Beats proximity pairing. Battery nibbles are steps of 10%.
/// `0xF` means that part is not in the packet.
nonisolated struct ProximityPairing: Equatable, Sendable {
    var modelID: UInt16
    var modelName: String?
    var leftBattery: Int?
    var rightBattery: Int?
    var caseBattery: Int?
    var leftCharging: Bool
    var rightCharging: Bool
    var caseCharging: Bool

    var modelLabel: String {
        let hex = String(format: "0x%04X", modelID)
        if let modelName { return "\(modelName) (\(hex))" }
        return hex
    }

    var batterySummary: String? {
        var parts: [String] = []
        if let leftBattery {
            parts.append(Self.part(String(localized: "Left"), leftBattery, leftCharging))
        }
        if let rightBattery {
            parts.append(Self.part(String(localized: "Right"), rightBattery, rightCharging))
        }
        if let caseBattery {
            parts.append(Self.part(String(localized: "Case"), caseBattery, caseCharging))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private static func part(_ label: String, _ percent: Int, _ charging: Bool) -> String {
        if charging {
            "\(label) \(percent)% (\(String(localized: "charging")))"
        } else {
            "\(label) \(percent)%"
        }
    }
}

nonisolated struct BLEAdvertisement: Equatable, Sendable {
    var localName: String?
    var companyID: UInt16?
    var companyName: String?
    var manufacturerPayload: Data
    var services: [BLEServiceRef]
    var solicitedServices: [BLEServiceRef]
    var serviceData: [BLEServiceData]
    var txPower: Int?
    var isConnectable: Bool?
    var apple: [AppleAdvertisement]

    static func parse(_ fields: BLEFields) -> BLEAdvertisement {
        var companyID: UInt16?
        var payload = Data()
        if let data = fields.manufacturerData, data.count >= 2 {
            companyID = UInt16(data[data.startIndex]) | (UInt16(data[data.startIndex + 1]) << 8)
            payload = Data(data.dropFirst(2))
        }

        var seen: Set<String> = []
        var services: [BLEServiceRef] = []
        for uuid in fields.serviceUUIDs + fields.overflowServiceUUIDs {
            let key = BLENumbers.canonical(uuid)
            guard seen.insert(key).inserted else { continue }
            services.append(BLEServiceRef(uuid: BLENumbers.displayUUID(uuid), name: BLENumbers.serviceName(uuid: uuid)))
        }

        let solicited = fields.solicitedServiceUUIDs.map { uuid in
            BLEServiceRef(uuid: BLENumbers.displayUUID(uuid), name: BLENumbers.serviceName(uuid: uuid))
        }
        let serviceData = fields.serviceData
            .map { uuid, data in
                BLEServiceData(uuid: BLENumbers.displayUUID(uuid), name: BLENumbers.serviceName(uuid: uuid), data: data)
            }
            .sorted { $0.uuid < $1.uuid }

        let apple = companyID == Self.appleCompany ? parseApple(payload) : []

        return BLEAdvertisement(
            localName: fields.localName,
            companyID: companyID,
            companyName: companyID.flatMap { BLENumbers.company(id: $0) },
            manufacturerPayload: payload,
            services: services,
            solicitedServices: solicited,
            serviceData: serviceData,
            txPower: fields.txPower,
            isConnectable: fields.isConnectable,
            apple: apple
        )
    }

    static let appleCompany: UInt16 = 0x004C

    /// Walks type/length/value records. A length that runs past the buffer
    /// ends the walk, so a truncated packet cannot invent a message.
    private static func parseApple(_ payload: Data) -> [AppleAdvertisement] {
        let bytes = [UInt8](payload)
        var index = 0
        var messages: [AppleAdvertisement] = []
        while index + 2 <= bytes.count {
            let type = bytes[index]
            let length = Int(bytes[index + 1])
            let start = index + 2
            guard start + length <= bytes.count else { break }
            let body = Data(bytes[start..<(start + length)])
            if let message = appleMessage(type: type, payload: body) {
                messages.append(message)
            }
            index = start + length
        }
        return messages
    }

    private static func appleMessage(type: UInt8, payload: Data) -> AppleAdvertisement? {
        let bytes = [UInt8](payload)
        switch type {
        case 0x02 where bytes.count == 21:
            guard let uuid = uuid(from: Data(bytes[0..<16])) else { return nil }
            let major = UInt16(bytes[16]) << 8 | UInt16(bytes[17])
            let minor = UInt16(bytes[18]) << 8 | UInt16(bytes[19])
            let power = Int(Int8(bitPattern: bytes[20]))
            return .iBeacon(uuid: uuid, major: major, minor: minor, measuredPower: power)
        case 0x03: return .airPrint
        case 0x05: return .airDrop
        case 0x06:
            let category: String?
            if bytes.count >= 9 {
                let code = UInt16(bytes[7]) << 8 | UInt16(bytes[8])
                category = homeKitCategories[code]
            } else {
                category = nil
            }
            return .homeKit(category: category)
        case 0x07: return proximity(bytes).map { .proximity($0) }
        case 0x08:
            let device: String?
            if bytes.count >= 6 {
                let code = UInt16(bytes[4]) << 8 | UInt16(bytes[5])
                device = siriDevices[code]
            } else {
                device = nil
            }
            return .heySiri(device: device)
        case 0x09: return .airPlayTarget
        case 0x0A: return .airPlaySource
        case 0x0B: return .autoUnlock
        case 0x0C: return .handoff
        case 0x0D: return .wifiSettings
        case 0x0E: return .instantHotspot
        case 0x0F:
            let action = bytes.count >= 2 ? NearbyAction(rawValue: bytes[1]) : nil
            return .nearbyAction(action)
        case 0x10:
            let activity = bytes.isEmpty ? nil : NearbyActivity(rawValue: bytes[0] & 0x0F)
            return .nearbyInfo(activity)
        case 0x12: return .findMy
        default: return nil
        }
    }

    /// Model is big-endian at payload bytes 1...2. Battery is one byte of
    /// nibbles (high = right, low = left); the next byte holds charging flags
    /// in bits 6...4 and the case level in the low nibble.
    private static func proximity(_ bytes: [UInt8]) -> ProximityPairing? {
        guard bytes.count >= 3, bytes[0] == 0x01 else { return nil }
        let model = UInt16(bytes[1]) << 8 | UInt16(bytes[2])
        var left: Int?
        var right: Int?
        var box: Int?
        var leftCharging = false
        var rightCharging = false
        var caseCharging = false
        if bytes.count >= 5 {
            right = percent(bytes[4] >> 4)
            left = percent(bytes[4] & 0x0F)
        }
        if bytes.count >= 6 {
            caseCharging = bytes[5] & 0x40 != 0
            rightCharging = bytes[5] & 0x20 != 0
            leftCharging = bytes[5] & 0x10 != 0
            box = percent(bytes[5] & 0x0F)
        }
        return ProximityPairing(
            modelID: model,
            modelName: audioModels[model],
            leftBattery: left,
            rightBattery: right,
            caseBattery: box,
            leftCharging: leftCharging,
            rightCharging: rightCharging,
            caseCharging: caseCharging
        )
    }

    /// A nibble of 0...10 is a percentage in steps of 10. 15 means absent.
    private static func percent(_ nibble: UInt8) -> Int? {
        guard nibble <= 10 else { return nil }
        return Int(nibble) * 10
    }

    private static func uuid(from data: Data) -> UUID? {
        guard data.count == 16 else { return nil }
        let raw = data.withUnsafeBytes { $0.load(as: uuid_t.self) }
        return UUID(uuid: raw)
    }

    /// Model field as a big-endian UInt16, matching the continuity dissector.
    private static let audioModels: [UInt16: String] = [
        0x0220: "AirPods",
        0x0320: "Powerbeats 3",
        0x0520: "BeatsX",
        0x0620: "Beats Solo 3",
        0x0A20: "AirPods Max",
        0x0B20: "Powerbeats Pro",
        0x0C20: "Beats Solo Pro",
        0x0E20: "AirPods Pro",
        0x0F20: "AirPods (2nd generation)",
        0x1020: "Beats Flex",
        0x1120: "Beats Studio Buds",
        0x1220: "Beats Fit Pro",
        0x1320: "AirPods (3rd generation)",
        0x1420: "AirPods Pro (2nd generation)",
    ]

    private static let siriDevices: [UInt16: String] = [
        0x0002: "iPhone",
        0x0003: "iPad",
        0x0007: "HomePod",
        0x0009: "MacBook",
        0x000A: "Watch",
    ]

    private static let homeKitCategories: [UInt16: String] = [
        0x0100: "Other",
        0x0200: "Bridge",
        0x0300: "Fan",
        0x0400: "Garage Door Opener",
        0x0500: "Lightbulb",
        0x0600: "Door Lock",
        0x0700: "Outlet",
        0x0800: "Switch",
        0x0900: "Thermostat",
        0x0A00: "Sensor",
        0x0B00: "Security System",
        0x0C00: "Door",
        0x0D00: "Window",
        0x0E00: "Window Covering",
        0x0F00: "Programmable Switch",
        0x1000: "Range Extender",
        0x1100: "IP Camera",
        0x1200: "Video Doorbell",
        0x1300: "Air Purifier",
        0x1400: "Heater",
        0x1500: "Air Conditioner",
        0x1600: "Humidifier",
        0x1700: "Dehumidifier",
        0x1C00: "Sprinklers",
        0x1D00: "Faucets",
        0x1E00: "Shower Systems",
    ]
}

nonisolated func bleHex(_ data: Data) -> String {
    data.map { String(format: "%02X", $0) }.joined(separator: " ")
}
