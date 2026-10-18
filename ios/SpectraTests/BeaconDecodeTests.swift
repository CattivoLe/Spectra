import Foundation
import Testing
@testable import Spectra

struct BeaconDecodeTests {
    @Test func eddystoneUID() {
        let data = Data([
            0x00, 0xEE, // frame + tx power (-18 dBm)
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, // namespace
            0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, // instance
        ])
        let info = BeaconDecode.decode(uuid: "FEAA", data: data)
        #expect(info?.title == "Eddystone-UID")
        #expect(info?.detail?.contains("0102030405060708090A") == true)
        #expect(info?.detail?.contains("0B0C0D0E0F10") == true)
        #expect(info?.detail?.contains("-18 dBm") == true)
    }

    @Test func eddystoneURL() {
        // https://www. + "example" + .com/
        let data = Data([0x10, 0xEE, 0x01]) + Data("example".utf8) + Data([0x00])
        let info = BeaconDecode.decode(uuid: "FEAA", data: data)
        #expect(info?.title == "Eddystone-URL")
        #expect(info?.detail?.hasPrefix("https://www.example.com/") == true)
    }

    @Test func eddystoneTLM() {
        let data = Data([
            0x20, 0x00,
            0x0B, 0xB8,             // 3000 mV
            0x19, 0x00,             // 25.0 °C (0x1900 / 256)
            0x00, 0x00, 0x04, 0x00, // 1024 advertisements
            0x00, 0x00, 0x00, 0x64, // 100 -> 10.0 s uptime
        ])
        let info = BeaconDecode.decode(uuid: "FEAA", data: data)
        #expect(info?.title == "Eddystone-TLM")
        #expect(info?.detail?.contains("3000 mV") == true)
        #expect(info?.detail?.contains("25.0 °C") == true)
        #expect(info?.detail?.contains("1024 adv") == true)
    }

    @Test func fastPairModel() {
        let info = BeaconDecode.decode(uuid: "FE2C", data: Data([0xAA, 0xBB, 0xCC]))
        #expect(info?.title == "Fast Pair")
        #expect(info?.detail == "model 0xAABBCC")
    }

    @Test func exposureAndTile() {
        #expect(BeaconDecode.decode(uuid: "FD6F", data: Data([0x01]))?.title == "Exposure Notification")
        #expect(BeaconDecode.decode(uuid: "FEED", data: Data([0x01]))?.title == "Tile")
    }

    @Test func unknownIsNil() {
        #expect(BeaconDecode.decode(uuid: "1234", data: Data([0x01])) == nil)
    }

    @Test func gattBattery() {
        #expect(GATTValue.decode(uuid: "2A19", bytes: Data([0x55])) == "85%")
    }

    @Test func gattAppearance() {
        #expect(GATTValue.decode(uuid: "2A01", bytes: Data([0x40, 0x00])) == "Phone")
    }

    @Test func gattManufacturerName() {
        #expect(GATTValue.decode(uuid: "2A29", bytes: Data("Acme".utf8)) == "Acme")
    }

    @Test func gattPnpID() {
        // source USB-IF, VID 0x004C (Apple), PID 0x1234, v1.2.3
        let value = Data([0x02, 0x4C, 0x00, 0x34, 0x12, 0x23, 0x01])
        let decoded = GATTValue.decode(uuid: "2A50", bytes: value)
        #expect(decoded?.contains("USB-IF") == true)
        #expect(decoded?.contains("Apple") == true)
        #expect(decoded?.contains("v1.2.3") == true)
    }

    @Test func gattUnknownVendorIsNil() {
        #expect(GATTValue.decode(uuid: "0000FFF1-0000-1000-8000-00805F9B34FB", bytes: Data([0x01])) == nil)
    }
}
