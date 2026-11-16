import Foundation
import Testing
@testable import Spectra

struct TrackerDetectorTests {
    private let start = Date(timeIntervalSince1970: 1_700_000_000)

    /// Apple manufacturer data: company 0x004C, then type 0x12 and a payload.
    private func findMyFields(separated: Bool, battery: UInt8 = 0) -> BLEFields {
        var bytes: [UInt8] = [0x4C, 0x00, 0x12]
        if separated {
            bytes.append(0x19)
            bytes.append(battery << 6)
            bytes.append(contentsOf: [UInt8](repeating: 0xAB, count: 24))
        } else {
            bytes.append(0x02)
            bytes.append(battery << 6)
            bytes.append(0x00)
        }
        return BLEFields(manufacturerData: Data(bytes))
    }

    private func device(_ fields: BLEFields, heardFor seconds: TimeInterval, every step: TimeInterval = 10) -> BLEDevice {
        let device = BLEDevice(id: UUID(), rssi: -60, seen: start)
        let advertisement = BLEAdvertisement.parse(fields)
        var t: TimeInterval = 0
        while t <= seconds {
            device.absorb(advertisement, peripheralName: nil, rssi: -60, at: start.addingTimeInterval(t))
            t += step
        }
        return device
    }

    @Test func findMyStatusReadsSeparationAndBattery() {
        let separated = BLEAdvertisement.parse(findMyFields(separated: true, battery: 2))
        guard case .findMy(let status)? = separated.apple.first else { Issue.record("no Find My message"); return }
        #expect(status?.separated == true)
        #expect(status?.battery == 2)
        #expect(status?.summary.contains("low") == true)

        let nearby = BLEAdvertisement.parse(findMyFields(separated: false))
        guard case .findMy(let near)? = nearby.apple.first else { Issue.record("no Find My message"); return }
        #expect(near?.separated == false)
    }

    @Test func aTagNearItsOwnerIsOnlyInformation() {
        let tag = device(findMyFields(separated: false), heardFor: 20 * 60)
        let verdict = TrackerDetector.assess(tag, now: start.addingTimeInterval(20 * 60))
        #expect(verdict?.kind == .findMy)
        #expect(verdict?.separated == false)
        #expect(verdict?.level == .info)
    }

    @Test func aSeparatedTagBecomesAnAlertAfterTheWindow() {
        let tag = device(findMyFields(separated: true), heardFor: 5 * 60)
        let early = TrackerDetector.assess(tag, now: start.addingTimeInterval(5 * 60))
        #expect(early?.level == .watch)
        #expect(early?.presentFor == TimeInterval(300))

        let longer = device(findMyFields(separated: true), heardFor: 12 * 60)
        let late = TrackerDetector.assess(longer, now: start.addingTimeInterval(12 * 60))
        #expect(late?.level == .alert)
    }

    @Test func aSilenceResetsThePresenceClock() {
        let tag = BLEDevice(id: UUID(), rssi: -60, seen: start)
        let advertisement = BLEAdvertisement.parse(findMyFields(separated: true))
        tag.absorb(advertisement, peripheralName: nil, rssi: -60, at: start)
        tag.absorb(advertisement, peripheralName: nil, rssi: -60, at: start.addingTimeInterval(8 * 60))
        // Eight quiet minutes: it left and came back, so the clock starts again.
        #expect(tag.presentFor(at: start.addingTimeInterval(8 * 60)) == 0)
        tag.absorb(advertisement, peripheralName: nil, rssi: -60, at: start.addingTimeInterval(9 * 60))
        #expect(tag.presentFor(at: start.addingTimeInterval(9 * 60)) == 60)
    }

    @Test func outOfRangeTagsAreNotAlerts() {
        let tag = device(findMyFields(separated: true), heardFor: 15 * 60)
        let later = start.addingTimeInterval(15 * 60 + BLEDevice.nearbyWindow + 5)
        #expect(TrackerDetector.assess(tag, now: later)?.level == .info)
    }

    @Test func applePhonesInLostModeAreNotTags() {
        // Find My next to a Nearby Info message: an iPhone, not an AirTag.
        var bytes: [UInt8] = [0x4C, 0x00, 0x10, 0x05, 0x01, 0x02, 0x03, 0x04, 0x05]
        bytes.append(contentsOf: [0x12, 0x02, 0x00, 0x00])
        let phone = device(BLEFields(manufacturerData: Data(bytes)), heardFor: 60)
        #expect(TrackerDetector.kind(of: phone) == nil)

        let named = device(BLEFields(localName: "Alex's AirPods", manufacturerData: Data([0x4C, 0x00, 0x12, 0x02, 0x00, 0x00])), heardFor: 60)
        #expect(TrackerDetector.kind(of: named) == nil)
    }

    @Test func otherTrackerFamiliesAreRecognised() {
        let tile = device(BLEFields(serviceData: ["FEED": Data([0x01])]), heardFor: 60)
        #expect(TrackerDetector.kind(of: tile) == .tile)
        let smartTag = device(BLEFields(serviceData: ["FD5A": Data([0x10, 0x00])]), heardFor: 60)
        #expect(TrackerDetector.kind(of: smartTag) == .smartTag)
        let fmdn = device(BLEFields(serviceData: ["FE2C": Data([0x40] + [UInt8](repeating: 0x11, count: 20) + [0x00])]), heardFor: 60)
        #expect(TrackerDetector.kind(of: fmdn) == .findMyDevice)
        let chipolo = device(BLEFields(manufacturerData: Data([0xC3, 0x08, 0x01])), heardFor: 60)
        #expect(TrackerDetector.kind(of: chipolo) == .chipolo)
        // Separation is unknown for these, so a long stay still counts.
        let longTile = device(BLEFields(serviceData: ["FEED": Data([0x01])]), heardFor: 11 * 60)
        #expect(TrackerDetector.assess(longTile, now: start.addingTimeInterval(11 * 60))?.level == .alert)
    }

    @Test func beaconDecodeNamesTheNewFamilies() {
        #expect(BeaconDecode.decode(uuid: "FD5A", data: Data([0x10]))?.title == "Samsung SmartTag")
        let frame = BeaconDecode.decode(uuid: "FE2C", data: Data([0x40] + [UInt8](repeating: 0x11, count: 20) + [0x00]))
        #expect(frame?.title.contains("Find My Device") == true)
        #expect(frame?.detail != nil)
    }

    @Test func findMyStatusSurvivesHistory() throws {
        let message = AppleAdvertisement.findMy(FindMyStatus(separated: true, battery: 1))
        let data = try JSONEncoder().encode([message])
        let back = try JSONDecoder().decode([AppleAdvertisement].self, from: data)
        #expect(back == [message])
        // Older files carry only the kind.
        let old = try JSONDecoder().decode([AppleAdvertisement].self, from: Data(#"[{"kind":"findmy"}]"#.utf8))
        #expect(old == [.findMy(nil)])
    }
}
