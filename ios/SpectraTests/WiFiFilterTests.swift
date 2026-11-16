import Foundation
import Testing
@testable import Spectra

struct WiFiFilterTests {
    private func network(_ ssid: String?, auth: AuthMode, channel: Int = 6, missed: Int = 0, last: UInt8 = 1) -> TrackedNetwork {
        let reading = AccessPointReading(
            bssid: BSSID(bytes: [0x44, 0x19, 0xB6, 0, 0, last]), rssi: -50, channel: channel,
            secondaryChannel: .none, auth: auth, pairwiseCipher: .ccmp, groupCipher: .ccmp,
            capabilities: [], countryCode: nil, ssidBytes: Data((ssid ?? "").utf8)
        )
        return TrackedNetwork(latest: reading, firstSeen: .now, lastSeen: .now, samples: [], missedScans: missed)
    }

    @Test func openKeepsOnlyNetworksWithoutAPassword() {
        let filter = WiFiFilter(openOnly: true)
        #expect(filter.passes(network("Cafe", auth: .open)))
        #expect(!filter.passes(network("Home", auth: .wpa2PSK)))
    }

    @Test func outOfRangeIsHiddenUntilAsked() {
        let gone = network("Gone", auth: .wpa2PSK, missed: NetworkStore.scansBeforeOutOfRange)
        #expect(!WiFiFilter().passes(gone))
        #expect(WiFiFilter(showOutOfRange: true).passes(gone))
    }

    @Test func bandAndKnownNarrowTheList() {
        let five = network("Five", auth: .wpa2PSK, channel: 36)
        let two = network("Two", auth: .wpa2PSK, channel: 1)
        #expect(WiFiFilter(band: .fiveGHz).passes(five))
        #expect(!WiFiFilter(band: .fiveGHz).passes(two))
        let known = WiFiFilter(knownOnly: true, knownSSIDs: ["Two"])
        #expect(known.passes(two))
        #expect(!known.passes(five))
    }

    @Test func searchMatchesNameAddressAndMaker() {
        let home = network("Home", auth: .wpa2PSK)
        #expect(WiFiFilter(query: "hom").passes(home))
        #expect(WiFiFilter(query: "44:19").passes(home))
        #expect(WiFiFilter(query: "hikvision").passes(home))
        #expect(!WiFiFilter(query: "office").passes(home))
    }

    @Test func probingDevicesLeaveWhenTheFilterCannotApply() {
        let device = ProbingDevice(mac: BSSID(bytes: [0x44, 0x19, 0xB6, 0, 0, 2]), rssi: -60, ssids: ["Home"], channel: 6)
        #expect(WiFiFilter().passes(device))
        #expect(!WiFiFilter(openOnly: true).passes(device))
        #expect(!WiFiFilter(band: .fiveGHz).passes(device))
        #expect(WiFiFilter(query: "home").passes(device))
    }
}
