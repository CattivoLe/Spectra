import Foundation
import Testing
@testable import Spectra

/// Each tab keeps its own history. Ported from HistoryAreaTest.kt.
struct HistoryAreaTests {
    let check = SavedConnection(viaVpn: false, snapshot: LinkSnapshot(checkedAt: Date(timeIntervalSince1970: 1_000), status: .online))
    let host = SavedLAN(LANDevice(ipAddress: "192.168.1.20", hostnames: ["printer"]))

    func network() -> TrackedNetwork {
        let reading = AccessPointReading(
            bssid: BSSID(bytes: [0, 0, 0, 0, 0, 1]), rssi: -60, channel: 6, secondaryChannel: .none,
            auth: .wpa2PSK, pairwiseCipher: .ccmp, groupCipher: .ccmp, capabilities: [], countryCode: nil,
            ssidBytes: Data("Net".utf8)
        )
        return TrackedNetwork(latest: reading, firstSeen: .now, lastSeen: .now, samples: [])
    }

    func lanSession(devices: [SavedLAN]? = nil, checks: [SavedConnection]? = nil) -> HistoryEntry {
        var entry = HistoryEntry(
            kind: .lan, savedAt: Date(timeIntervalSince1970: 42), source: "This iPhone",
            placeKey: "lan:home", placeLabel: "Home", lan: devices ?? [host], connections: checks ?? [check],
            latitude: 1, longitude: 2
        )
        entry.id = UUID(uuidString: "00000000-0000-0000-0000-0000000000AA")!
        return entry
    }

    @Test func aLanSessionShowsItsDevicesOnLanAndItsChecksOnInternet() throws {
        let session = lanSession()
        #expect(session.holds(.lan))
        #expect(session.holds(.internet))
        #expect(!session.holds(.wifi))
        #expect(!session.holds(.bluetooth))

        let lan = try #require(session.only(.lan))
        #expect(lan.lan == [host])
        #expect(lan.connections.isEmpty)
        #expect(lan.shownKinds == ["lan"])
        #expect(lan.itemCount == 1)

        let internet = try #require(session.only(.internet))
        #expect(internet.lan.isEmpty)
        #expect(internet.connections == [check])
        #expect(internet.rowTitle == String(localized: "Internet"))
        // Same session underneath: the same id, place and fix, so opening and deleting find it.
        #expect(internet.id == session.id)
        #expect(internet.placeLabel == "Home")
        #expect(internet.latitude == 1)
    }

    @Test func aSessionWithNothingOfTheAreaIsLeftOut() {
        let devicesOnly = lanSession(checks: [])
        #expect(devicesOnly.only(.internet) == nil)
        #expect(devicesOnly.only(.wifi) == nil)
        let bluetooth = HistoryEntry(kind: .bluetooth, savedAt: .now, source: "This iPhone",
                                     bluetooth: [SavedBLE(BLEDevice(id: UUID(), rssi: -60, seen: .now))])
        let wifi = HistoryEntry(kind: .networks, savedAt: .now, source: "This iPhone", networks: [network()])
        let all = [devicesOnly, lanSession(), bluetooth, wifi]
        #expect(all.forArea(.lan).map(\.id) == [devicesOnly.id, devicesOnly.id])
        #expect(all.forArea(.internet).map(\.id) == [devicesOnly.id])
        #expect(all.forArea(.bluetooth).map(\.id) == [bluetooth.id])
        #expect(all.forArea(.wifi).map(\.id) == [wifi.id])
    }

    @Test func sensorLinesBelongToTheTabOfTheirKind() throws {
        let lines = [HistoryLine(title: "Net", detail: "ch 6", trailing: "-60")]
        let lora = HistoryEntry(kind: .lora, savedAt: .now, source: "Cardputer", captureID: 7, lines: lines)
        #expect(lora.holds(.wifi))
        #expect(try #require(lora.only(.wifi)).lines == lines)
        #expect(lora.only(.lan) == nil)
        let ble = HistoryEntry(kind: .bluetooth, savedAt: .now, source: "Cardputer", captureID: 8, lines: lines)
        #expect(ble.holds(.bluetooth))
        #expect(!ble.holds(.wifi))
    }

    @Test func deletingFromOneTabKeepsTheOtherTabsPart() throws {
        let session = lanSession()
        let noChecks = try #require(session.without(.internet))
        #expect(noChecks.lan == [host])
        #expect(noChecks.connections.isEmpty)
        #expect(noChecks.id == session.id)
        let noDevices = try #require(session.without(.lan))
        #expect(noDevices.lan.isEmpty)
        #expect(noDevices.connections == [check])
        // Removing the last part removes the session.
        #expect(noDevices.without(.internet) == nil)
        // Nothing of that tab: the session is handed back untouched.
        #expect(noChecks.without(.internet) == noChecks)
        #expect(noChecks.without(.bluetooth) == noChecks)
    }
}
