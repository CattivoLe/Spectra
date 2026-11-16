import CoreBluetooth
import Foundation
import Observation

/// One Bluetooth LE peripheral, followed for as long as it keeps advertising.
@Observable
final class BLEDevice: Identifiable {
    struct Sample: Identifiable, Equatable {
        var id: Int
        var date: Date
        var rssi: Int
    }

    /// Quiet for longer than this and the row dims. Long enough for a slow
    /// beacon, short enough that a device which left does not stay "nearby".
    static let nearbyWindow: TimeInterval = 20
    /// Quiet for this long while the scan runs and the row leaves the list.
    /// History keeps it: the list is saved more often than this.
    static let removalWindow: TimeInterval = 120

    let id: UUID
    var localName: String?
    var peripheralName: String?
    var companyID: UInt16?
    var companyName: String?
    var manufacturerPayload = Data()
    var services: [BLEServiceRef] = []
    var solicitedServices: [BLEServiceRef] = []
    var serviceData: [BLEServiceData] = []
    var txPower: Int?
    var isConnectable: Bool?
    var apple: [AppleAdvertisement] = []
    var rssi: Int
    /// Signal used to order the list. It follows the live value only after a
    /// real change, so a few dB of noise does not shuffle the rows.
    private(set) var orderRSSI: Int
    private var smoothedRSSI: Double
    var samples: [Sample] = []
    var packetCount = 0
    let firstSeen: Date
    var lastSeen: Date
    /// Start of the current unbroken stretch of advertisements. A silence
    /// longer than `presenceGap` starts a new one, so "near you for 20 min"
    /// means it really stayed, not that it came back.
    private(set) var presentSince: Date
    static let presenceGap: TimeInterval = 90

    private var sampleCounter = 0

    init(id: UUID, rssi: Int, seen: Date) {
        self.id = id
        self.rssi = rssi
        self.orderRSSI = rssi
        self.smoothedRSSI = Double(rssi)
        self.firstSeen = seen
        self.lastSeen = seen
        self.presentSince = seen
    }

    /// How long the device has been continuously nearby.
    func presentFor(at now: Date) -> TimeInterval {
        max(0, min(now, lastSeen).timeIntervalSince(presentSince))
    }

    /// The Find My status, when the device advertises Offline Finding.
    var findMyStatus: FindMyStatus? {
        for case .findMy(let status) in apple { return status }
        return nil
    }

    var isSpectraSensor: Bool {
        let sensor = SensorProtocol.service.uuidString
        if services.contains(where: { $0.uuid.caseInsensitiveCompare(sensor) == .orderedSame }) {
            return true
        }
        let name = localName ?? peripheralName ?? ""
        return name.hasPrefix("Spectra-")
    }

    var advertisesFindMy: Bool {
        apple.contains { if case .findMy = $0 { true } else { false } }
    }

    func isNearby(at now: Date) -> Bool {
        now.timeIntervalSince(lastSeen) < Self.nearbyWindow
    }

    var hasOwnName: Bool {
        if let localName, !localName.isEmpty { return true }
        if let peripheralName, !peripheralName.isEmpty { return true }
        if proximity?.modelName != nil { return true }
        if heySiriDevice != nil { return true }
        return false
    }

    var displayName: String {
        if let name = cleaned(localName) { return name }
        if let name = cleaned(peripheralName) { return name }
        if let pairing = proximity {
            return pairing.modelName ?? String(localized: "Apple audio")
        }
        if let heySiriDevice { return heySiriDevice }
        if let named = apple
            .compactMap({ message -> (Int, String)? in
                guard let rank = message.nameRank, let name = message.distinctiveName else { return nil }
                return (rank, name)
            })
            .min(by: { $0.0 < $1.0 })?.1 {
            return named
        }
        if let companyName { return companyName }
        return String(localized: "Unknown device")
    }

    var subtitle: String {
        var parts: [String] = []
        if let companyName, companyName != displayName {
            parts.append(companyName)
        }
        if let battery = proximity?.batterySummary {
            parts.append(battery)
        } else if let activity = nearbyActivity?.label {
            parts.append(activity)
        } else if let action = nearbyAction?.label {
            parts.append(action)
        } else if let service = services.compactMap(\.name).first, service != displayName {
            parts.append(service)
        }
        if parts.isEmpty {
            parts.append(String(id.uuidString.prefix(8)))
        }
        return parts.joined(separator: " · ")
    }

    var systemImage: String {
        if isSpectraSensor { return "antenna.radiowaves.left.and.right" }
        if let pairing = proximity {
            let name = pairing.modelName ?? ""
            if name.hasPrefix("Beats") || name.hasPrefix("Powerbeats") { return "headphones" }
            if name.contains("Max") { return "airpodsmax" }
            if name.contains("Pro") { return "airpodspro" }
            return "airpods"
        }
        if let device = heySiriDevice {
            switch device {
            case "iPhone": return "iphone"
            case "iPad": return "ipad"
            case "Watch": return "applewatch"
            case "MacBook": return "laptopcomputer"
            case "HomePod": return "homepod"
            default: return "apple.logo"
            }
        }
        if apple.contains(where: { if case .homeKit = $0 { true } else { false } }) { return "homekit" }
        if apple.contains(where: { if case .airPlayTarget = $0 { true } else if case .airPlaySource = $0 { true } else { false } }) {
            return "appletv"
        }
        if apple.contains(where: { if case .findMy = $0 { true } else { false } }) { return "location" }
        if apple.contains(where: { if case .airPrint = $0 { true } else { false } }) { return "printer" }
        for service in services {
            switch BLENumbers.canonical(service.uuid) {
            case "180D": return "heart"
            case "1812": return "keyboard"
            case "1814": return "figure.run"
            case "1816": return "figure.outdoor.cycle"
            case "180F": return "battery.100"
            case "FE2C": return "headphones"
            default: continue
            }
        }
        if companyID == BLEAdvertisement.appleCompany { return "apple.logo" }
        return "wave.3.right"
    }

    var strongestRSSI: Int { samples.map(\.rssi).max() ?? rssi }
    var weakestRSSI: Int { samples.map(\.rssi).min() ?? rssi }
    var averageRSSI: Int {
        samples.isEmpty ? rssi : samples.map(\.rssi).reduce(0, +) / samples.count
    }

    /// Folds another advertisement into this device. Identity fields are
    /// kept across packets, because one advertisement often carries only
    /// the name and the next only the services. Signal samples are limited
    /// to a few a second so a chatty peripheral does not redraw the list
    /// on every packet.
    func absorb(_ advertisement: BLEAdvertisement, peripheralName: String?, rssi: Int, at now: Date) {
        packetCount += 1
        if now.timeIntervalSince(lastSeen) > Self.presenceGap { presentSince = now }
        if let name = cleaned(advertisement.localName) { localName = name }
        if let name = cleaned(peripheralName) { self.peripheralName = name }
        if let companyID = advertisement.companyID {
            self.companyID = companyID
            companyName = advertisement.companyName
        }
        if !advertisement.manufacturerPayload.isEmpty {
            manufacturerPayload = advertisement.manufacturerPayload
        }
        if !advertisement.apple.isEmpty {
            // Messages rotate across packets. Keep each kind we have seen and
            // refresh it when it shows up again, instead of letting the name
            // flicker to whatever this one packet happened to carry.
            var merged = apple
            for message in advertisement.apple {
                if let index = merged.firstIndex(where: { $0.kindID == message.kindID }) {
                    merged[index] = message
                } else {
                    merged.append(message)
                }
            }
            apple = merged
        }
        for service in advertisement.services where !services.contains(where: { $0.uuid == service.uuid }) {
            services.append(service)
        }
        for service in advertisement.solicitedServices where !solicitedServices.contains(where: { $0.uuid == service.uuid }) {
            solicitedServices.append(service)
        }
        for item in advertisement.serviceData {
            if let index = serviceData.firstIndex(where: { $0.uuid == item.uuid }) {
                serviceData[index] = item
            } else {
                serviceData.append(item)
            }
        }
        if let txPower = advertisement.txPower { self.txPower = txPower }
        if let isConnectable = advertisement.isConnectable { self.isConnectable = isConnectable }

        let due = samples.last.map { now.timeIntervalSince($0.date) >= 0.25 } ?? true
        guard due else { return }
        if rssi != 127 {
            self.rssi = rssi
            smoothedRSSI = smoothedRSSI * 0.5 + Double(rssi) * 0.5
            let smoothed = Int(smoothedRSSI.rounded())
            if abs(smoothed - orderRSSI) >= 8 {
                orderRSSI = smoothed
            }
            sampleCounter += 1
            samples.append(Sample(id: sampleCounter, date: now, rssi: rssi))
            if samples.count > 60 {
                samples.removeFirst(samples.count - 60)
            }
        }
        lastSeen = now
    }

    private var proximity: ProximityPairing? {
        for case .proximity(let pairing) in apple { return pairing }
        return nil
    }

    private var heySiriDevice: String? {
        for case .heySiri(let device) in apple { return device }
        return nil
    }

    private var nearbyActivity: NearbyActivity? {
        for case .nearbyInfo(let activity) in apple { return activity }
        return nil
    }

    private var nearbyAction: NearbyAction? {
        for case .nearbyAction(let action) in apple { return action }
        return nil
    }

    private func cleaned(_ name: String?) -> String? {
        guard let name = name?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { return nil }
        return name
    }
}
