import Foundation
import Testing
@testable import Spectra

struct WatchListTests {
    private func store() -> WatchList {
        let defaults = UserDefaults(suiteName: "watch-test-\(UUID().uuidString)")!
        return WatchList(defaults: defaults)
    }

    private func network(_ bssid: [UInt8], rssi: Int, missed: Int = 0) -> TrackedNetwork {
        let reading = AccessPointReading(
            bssid: BSSID(bytes: bssid), rssi: rssi, channel: 6, secondaryChannel: .none,
            auth: .wpa2PSK, pairwiseCipher: .ccmp, groupCipher: .ccmp, capabilities: [.n],
            countryCode: nil, ssidBytes: Data("Net".utf8)
        )
        var net = TrackedNetwork(latest: reading, firstSeen: .distantPast, lastSeen: .distantPast, samples: [])
        net.missedScans = missed
        return net
    }

    @Test func toggleAddsAndRemovesAndPersists() {
        let defaults = UserDefaults(suiteName: "watch-persist-\(UUID().uuidString)")!
        let list = WatchList(defaults: defaults)
        #expect(list.isWatched("AA:BB") == false)
        list.toggle("AA:BB")
        #expect(list.isWatched("AA:BB"))
        // A fresh instance over the same store sees it.
        #expect(WatchList(defaults: defaults).isWatched("AA:BB"))
        list.toggle("AA:BB")
        #expect(list.isWatched("AA:BB") == false)
    }

    @Test func presentReturnsOnlyWatchedInRange() {
        let watched = network([0xAA, 0, 0, 0, 0, 1], rssi: -50)
        let watchedGone = network([0xBB, 0, 0, 0, 0, 2], rssi: -80, missed: 9)
        let other = network([0xCC, 0, 0, 0, 0, 3], rssi: -40)
        let ids: Set<String> = [watched.latest.bssid.description, watchedGone.latest.bssid.description]
        let present = WatchList.present(ids, in: [watched, watchedGone, other])
        #expect(present.count == 1)
        #expect(present.first?.latest.bssid.description == watched.latest.bssid.description)
    }
}
