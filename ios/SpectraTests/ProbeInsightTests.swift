import Foundation
import Testing
@testable import Spectra

struct ProbeInsightTests {
    private func ap(_ ssid: String, auth: AuthMode) -> AccessPointReading {
        AccessPointReading(
            bssid: BSSID(bytes: [0, 0, 0, 0, 0, 1]), rssi: -50, channel: 6, secondaryChannel: .none,
            auth: auth, pairwiseCipher: .ccmp, groupCipher: .ccmp, capabilities: [.n],
            countryCode: nil, ssidBytes: Data(ssid.utf8)
        )
    }

    @Test func countsDistinctRememberedNetworks() {
        let result = ProbeInsight.assess(ssids: ["Home", "Home", "Work", ""], networksInRange: [])
        #expect(result.rememberedCount == 2)
        #expect(result.presentHere.isEmpty)
        #expect(result.trackable == false)
    }

    @Test func flagsPresentAndAutoJoinOpen() {
        let probes = ["CoffeeShop", "Home", "AirportFree"]
        let inRange = [ap("coffeeshop", auth: .wpa2PSK), ap("AirportFree", auth: .open)]
        let result = ProbeInsight.assess(ssids: probes, networksInRange: inRange)
        #expect(result.presentHere == ["AirportFree", "CoffeeShop"])
        #expect(result.autoJoinOpen == ["AirportFree"])
    }

    @Test func manyNetworksAreTrackable() {
        let result = ProbeInsight.assess(ssids: ["A", "B", "C", "D"], networksInRange: [])
        #expect(result.trackable)
    }
}
