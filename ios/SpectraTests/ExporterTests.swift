import Foundation
import Testing
@testable import Spectra

struct ExporterTests {
    private func ap(
        bssid: [UInt8], ssid: String, rssi: Int, channel: Int,
        auth: AuthMode, cipher: Cipher = .ccmp
    ) -> AccessPointReading {
        AccessPointReading(
            bssid: BSSID(bytes: bssid), rssi: rssi, channel: channel,
            secondaryChannel: .none, auth: auth, pairwiseCipher: cipher,
            groupCipher: cipher, capabilities: [.n], countryCode: nil,
            ssidBytes: Data(ssid.utf8)
        )
    }

    private func network(_ reading: AccessPointReading) -> TrackedNetwork {
        TrackedNetwork(latest: reading, firstSeen: .distantPast, lastSeen: .distantPast, samples: [])
    }

    private func locatedEntry() -> HistoryEntry {
        HistoryEntry(
            kind: .networks,
            savedAt: Date(timeIntervalSince1970: 1_700_000_000),
            source: "Sensor",
            networks: [
                network(ap(bssid: [0xAA, 0xBB, 0xCC, 0x11, 0x22, 0x33], ssid: "HomeNet", rssi: -55, channel: 6, auth: .wpa2PSK)),
                network(ap(bssid: [0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01], ssid: "Open Café", rssi: -70, channel: 11, auth: .open)),
            ],
            latitude: 55.751244,
            longitude: 37.618423
        )
    }

    @Test func wigleHasHeaderAndWifiRows() {
        let csv = Exporter.wigle(from: [locatedEntry()])
        let lines = csv.split(separator: "\n").map(String.init)
        #expect(lines[0].hasPrefix("WigleWifi-1.6"))
        #expect(lines[1] == "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type")
        #expect(lines.contains { $0.contains("AA:BB:CC:11:22:33") && $0.contains("HomeNet") && $0.contains("[WPA2-PSK-CCMP][ESS]") && $0.contains("WIFI") })
        #expect(lines.contains { $0.contains("55.7512440,37.6184230") })
    }

    @Test func wigleSkipsUnlocatedEntries() {
        var entry = locatedEntry()
        entry.latitude = nil
        entry.longitude = nil
        let csv = Exporter.wigle(from: [entry])
        // Header + column line only, no data rows.
        #expect(csv.split(separator: "\n").count == 2)
    }

    @Test func csvEscapesCommasAndListsEveryKind() {
        let csv = Exporter.csv(from: [locatedEntry()])
        #expect(csv.contains("kind,saved_at,source,latitude,longitude"))
        #expect(csv.contains("wifi,"))
        #expect(csv.contains("AA:BB:CC:11:22:33"))
        // "Open Café" has no comma, but a quoted SSID with a comma must survive.
        let comma = network(ap(bssid: [1, 2, 3, 4, 5, 6], ssid: "a,b", rssi: -40, channel: 1, auth: .open))
        let entry = HistoryEntry(kind: .networks, savedAt: Date(), source: "s", networks: [comma])
        #expect(Exporter.csv(from: [entry]).contains("\"a,b\""))
    }

    @Test func kmlPlacemarksOnlyForLocated() {
        var unlocated = locatedEntry()
        unlocated.latitude = nil
        unlocated.longitude = nil
        let kml = Exporter.kml(from: [locatedEntry(), unlocated])
        #expect(kml.contains("<kml"))
        // One placemark for the located entry; coordinates are lon,lat.
        #expect(kml.components(separatedBy: "<Placemark>").count - 1 == 1)
        #expect(kml.contains("37.6184230,55.7512440,0"))
    }
}
