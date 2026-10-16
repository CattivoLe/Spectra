import Foundation
import Testing
@testable import Spectra

struct BLEScannerTests {
    @Test func companyAndServiceNames() {
        #expect(BLENumbers.company(id: 0x004C) == "Apple, Inc.")
        #expect(BLENumbers.company(id: 0x00E0) == "Google")
        #expect(BLENumbers.company(id: 0x02E5) == "Espressif Systems (Shanghai) Co., Ltd.")
        #expect(BLENumbers.serviceName(uuid: "180F") == "Battery Service")
        #expect(BLENumbers.serviceName(uuid: "0000180d-0000-1000-8000-00805f9b34fb") == "Heart Rate")
        #expect(BLENumbers.serviceName(uuid: "FE2C") == "Fast Pair Service")
        #expect(BLENumbers.canonical("0000180F-0000-1000-8000-00805F9B34FB") == "180F")
    }

    @Test func airPodsProBattery() {
        let advertisement = BLEAdvertisement.parse(BLEFields(manufacturerData: Data([
            0x4C, 0x00, 0x07, 0x06, 0x01, 0x0E, 0x20, 0x55, 0x98, 0x70,
        ])))
        #expect(advertisement.companyName == "Apple, Inc.")
        guard case .proximity(let pairing) = advertisement.apple.first else {
            Issue.record("expected proximity pairing")
            return
        }
        #expect(pairing.modelID == 0x0E20)
        #expect(pairing.modelName == "AirPods Pro")
        #expect(pairing.leftBattery == 80)
        #expect(pairing.rightBattery == 90)
        #expect(pairing.caseBattery == 0)
        #expect(pairing.leftCharging)
        #expect(pairing.rightCharging)
        #expect(pairing.caseCharging)
    }

    @Test func missingBudIsNotABatteryReading() {
        let advertisement = BLEAdvertisement.parse(BLEFields(manufacturerData: Data([
            0x4C, 0x00, 0x07, 0x06, 0x01, 0x0E, 0x20, 0x55, 0x9F, 0x4F,
        ])))
        guard case .proximity(let pairing) = advertisement.apple.first else {
            Issue.record("expected proximity pairing")
            return
        }
        #expect(pairing.rightBattery == 90)
        #expect(pairing.leftBattery == nil)
        #expect(pairing.caseBattery == nil)
        #expect(pairing.caseCharging)
        #expect(!pairing.leftCharging)
        #expect(!pairing.rightCharging)
    }

    @Test func iBeacon() {
        let advertisement = BLEAdvertisement.parse(BLEFields(manufacturerData: Data([
            0x4C, 0x00, 0x02, 0x15,
            0xE2, 0xC5, 0x6D, 0xB5, 0xDF, 0xFB, 0x48, 0xD2,
            0xB0, 0x60, 0xD0, 0xF5, 0xA7, 0x10, 0x96, 0xE0,
            0x00, 0x01, 0x00, 0x02, 0xC5,
        ])))
        guard case .iBeacon(let uuid, let major, let minor, let power) = advertisement.apple.first else {
            Issue.record("expected an iBeacon")
            return
        }
        #expect(uuid == UUID(uuidString: "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"))
        #expect(major == 1)
        #expect(minor == 2)
        #expect(power == -59)
    }

    @Test func nearbyInfoAndHandoffShareOneAdvertisement() {
        let advertisement = BLEAdvertisement.parse(BLEFields(manufacturerData: Data([
            0x4C, 0x00,
            0x10, 0x01, 0x0B,
            0x0C, 0x01, 0x00,
        ])))
        #expect(advertisement.apple.count == 2)
        guard case .nearbyInfo(let activity) = advertisement.apple[0] else {
            Issue.record("expected Nearby Info")
            return
        }
        #expect(activity == .active)
        guard case .handoff = advertisement.apple[1] else {
            Issue.record("expected Handoff")
            return
        }
    }

    @Test func truncatedMessageIsDropped() {
        let advertisement = BLEAdvertisement.parse(BLEFields(manufacturerData: Data([
            0x4C, 0x00, 0x10, 0x05, 0x0B,
        ])))
        #expect(advertisement.companyID == 0x004C)
        #expect(advertisement.apple.isEmpty)
    }

    @Test func otherCompaniesStayUnparsed() {
        let advertisement = BLEAdvertisement.parse(BLEFields(manufacturerData: Data([0xE0, 0x00, 0xAA])))
        #expect(advertisement.companyName == "Google")
        #expect(advertisement.manufacturerPayload == Data([0xAA]))
        #expect(advertisement.apple.isEmpty)
        let reserved = BLEAdvertisement.parse(BLEFields(manufacturerData: Data([0xFF, 0xFF])))
        #expect(reserved.companyID == 0xFFFF)
        #expect(reserved.apple.isEmpty)
    }

    @Test func homeKitAndHeySiriNameTheProduct() {
        let light = BLEAdvertisement.parse(BLEFields(manufacturerData: Data([
            0x4C, 0x00, 0x06, 0x09,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x05, 0x00,
        ])))
        guard case .homeKit(let category) = light.apple.first else {
            Issue.record("expected HomeKit")
            return
        }
        #expect(category == "Lightbulb")

        let phone = BLEAdvertisement.parse(BLEFields(manufacturerData: Data([
            0x4C, 0x00, 0x08, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02,
        ])))
        guard case .heySiri(let device) = phone.apple.first else {
            Issue.record("expected Hey Siri")
            return
        }
        #expect(device == "iPhone")
    }

    @Test func servicesCollapseTheBluetoothBaseUUID() {
        let advertisement = BLEAdvertisement.parse(BLEFields(
            localName: "Kitchen",
            serviceUUIDs: ["180F", "0000180D-0000-1000-8000-00805F9B34FB"],
            overflowServiceUUIDs: ["180F"],
            serviceData: ["FE2C": Data([1, 2, 3])],
            txPower: -12,
            isConnectable: true
        ))
        #expect(advertisement.services.compactMap(\.name) == ["Battery Service", "Heart Rate"])
        #expect(advertisement.services.map(\.uuid) == ["180F", "180D"])
        #expect(advertisement.serviceData.first?.name == "Fast Pair Service")
        #expect(advertisement.txPower == -12)
        #expect(advertisement.isConnectable == true)
    }

    @Test func laterPacketsFillInTheDeviceWithoutDroppingTheName() {
        let device = BLEDevice(id: UUID(), rssi: -40, seen: .now)
        let first = BLEAdvertisement.parse(BLEFields(
            localName: "Kitchen",
            manufacturerData: Data([0x4C, 0x00, 0x10, 0x01, 0x0B]),
            serviceUUIDs: ["180F"]
        ))
        device.absorb(first, peripheralName: nil, rssi: -40, at: .now)
        let second = BLEAdvertisement.parse(BLEFields(
            manufacturerData: Data([0x4C, 0x00, 0x12, 0x01, 0x00]),
            serviceUUIDs: ["180D"]
        ))
        device.absorb(second, peripheralName: "Kitchen speaker", rssi: -41, at: .now.addingTimeInterval(0.1))

        #expect(device.displayName == "Kitchen")
        #expect(device.peripheralName == "Kitchen speaker")
        #expect(device.services.map(\.uuid) == ["180F", "180D"])
        #expect(device.apple.count == 2)
        #expect(device.samples.count == 1)
        #expect(device.rssi == -40)

        device.absorb(second, peripheralName: nil, rssi: 127, at: .now.addingTimeInterval(0.4))
        #expect(device.rssi == -40)
        #expect(device.packetCount == 3)
    }

    @Test func aNoisySignalDoesNotMoveTheRowUntilTheChangeHolds() {
        let device = BLEDevice(id: UUID(), rssi: -60, seen: .now)
        let advertisement = BLEAdvertisement.parse(BLEFields())
        var time = Date.now
        for rssi in [-59, -62, -57, -61, -58] {
            time = time.addingTimeInterval(0.3)
            device.absorb(advertisement, peripheralName: nil, rssi: rssi, at: time)
        }
        #expect(device.orderRSSI == -60)

        time = time.addingTimeInterval(0.3)
        device.absorb(advertisement, peripheralName: nil, rssi: -45, at: time)
        time = time.addingTimeInterval(0.3)
        device.absorb(advertisement, peripheralName: nil, rssi: -45, at: time)
        #expect(device.orderRSSI > -60)
    }

    @Test func findMyOutranksATransientAirDrop() {
        let device = BLEDevice(id: UUID(), rssi: -70, seen: .now)
        let both = BLEAdvertisement.parse(BLEFields(manufacturerData: Data([
            0x4C, 0x00,
            0x05, 0x01, 0x00,
            0x12, 0x01, 0x00,
        ])))
        device.absorb(both, peripheralName: nil, rssi: -70, at: .now)
        #expect(device.displayName == String(localized: "Find My"))
    }
}
