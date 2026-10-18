import Foundation
import Testing
@testable import Spectra

@Suite(.serialized)
struct HistoryStoreTests {
    private func reading(_ bytes: [UInt8], rssi: Int = -60) -> AccessPointReading {
        AccessPointReading(
            bssid: BSSID(bytes: bytes),
            rssi: rssi,
            channel: 6,
            secondaryChannel: .none,
            auth: .wpa2PSK,
            pairwiseCipher: .ccmp,
            groupCipher: .ccmp,
            capabilities: [],
            countryCode: nil,
            ssidBytes: Data("Net".utf8)
        )
    }

    private func network(_ bytes: [UInt8], first: Date, last: Date, rssi: Int = -60) -> TrackedNetwork {
        TrackedNetwork(
            latest: reading(bytes, rssi: rssi),
            firstSeen: first,
            lastSeen: last,
            samples: [.init(date: last, rssi: rssi)]
        )
    }

    private func ble(_ id: UUID, first: Date, last: Date, rssi: Int = -50) -> SavedBLE {
        let device = BLEDevice(id: id, rssi: rssi, seen: first)
        device.lastSeen = last
        device.samples = [.init(id: 0, date: last, rssi: rssi)]
        return SavedBLE(device)
    }

    @Test func mergeNetworksUnionsAndKeepsEarliestFirstSeen() {
        let t0 = Date(timeIntervalSince1970: 1_000)
        let t1 = Date(timeIntervalSince1970: 2_000)
        let a = [network([0, 0, 0, 0, 0, 1], first: t0, last: t0)]
        let b = [
            network([0, 0, 0, 0, 0, 1], first: t1, last: t1, rssi: -40),
            network([0, 0, 0, 0, 0, 2], first: t1, last: t1),
        ]
        let merged = HistoryStore.mergeNetworks(a, b)
        #expect(merged.count == 2)
        let one = merged.first { $0.id == BSSID(bytes: [0, 0, 0, 0, 0, 1]) }
        #expect(one?.firstSeen == t0)
        #expect(one?.lastSeen == t1)
        #expect(one?.latest.rssi == -40) // newer reading wins
        #expect(one?.samples.count == 2)
    }

    @Test func mergeBluetoothUnionsSamplesAndSeen() {
        let id = UUID()
        let t0 = Date(timeIntervalSince1970: 1_000)
        let t1 = Date(timeIntervalSince1970: 2_000)
        let merged = HistoryStore.mergeBLE([ble(id, first: t0, last: t0)], [ble(id, first: t1, last: t1)])
        #expect(merged.count == 1)
        #expect(merged[0].firstSeen == t0)
        #expect(merged[0].lastSeen == t1)
        #expect(merged[0].samples.count == 2)
    }

    @Test func mergeLinesDedupesByTitle() {
        let existing = [HistoryLine(title: "A", detail: "", trailing: "")]
        let incoming = [
            HistoryLine(title: "A", detail: "x", trailing: ""),
            HistoryLine(title: "B", detail: "", trailing: ""),
        ]
        #expect(HistoryStore.mergeLines(existing, incoming).map(\.title) == ["A", "B"])
    }

    @Test func upsertMergesSameDaySamePlace() {
        let store = HistoryStore()
        store.clear()
        let t0 = Date()
        let id1 = UUID(), id2 = UUID()
        let a = BLEDevice(id: id1, rssi: -50, seen: t0)
        store.upsertBluetooth([a], source: "This iPhone")
        let b = BLEDevice(id: id1, rssi: -45, seen: t0)
        let c = BLEDevice(id: id2, rssi: -70, seen: t0)
        store.upsertBluetooth([b, c], source: "This iPhone")
        #expect(store.entries.count == 1)
        #expect(store.entries[0].bluetooth.count == 2)
        store.clear()
    }

    @Test func sensorCaptureDoesNotMergeIntoPhoneSession() {
        let store = HistoryStore()
        store.clear()
        store.upsertBluetooth([BLEDevice(id: UUID(), rssi: -50, seen: .now)], source: "This iPhone")
        let record = SensorProtocol.SavedRecord(
            kind: .bluetooth, captureID: 42, unix: 0, index: 0, total: 1,
            title: "Beacon", detail: "", trailing: "-60"
        )
        _ = store.ingest(record, source: "Cardputer")
        // The phone session and the sensor capture stay distinct entries.
        #expect(store.entries.count == 2)
        store.clear()
    }
}
