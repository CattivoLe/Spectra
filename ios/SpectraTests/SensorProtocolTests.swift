import Foundation
import Testing
@testable import Spectra

struct SensorProtocolTests {
    private let reading = AccessPointReading(
        bssid: BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x22, 0xA1]),
        rssi: -67,
        channel: 11,
        secondaryChannel: .below,
        auth: .wpa2WPA3PSK,
        pairwiseCipher: .ccmp,
        groupCipher: .ccmp,
        capabilities: [.b, .g, .n, .ax, .wps],
        countryCode: "RU",
        ssidBytes: Data("Кафе ☕".utf8)
    )

    @Test func accessPointRoundTrips() throws {
        let data = SensorProtocol.encode(.accessPoint(scanID: 513, reading: reading))
        #expect(data.count == 19 + reading.ssidBytes.count)
        let decoded = try #require(SensorProtocol.decode(data))
        #expect(decoded == .accessPoint(scanID: 513, reading: reading))
    }

    /// Bytes laid out by hand from docs/protocol.md, so a change to the
    /// encoder cannot hide a change to the decoder.
    @Test func decodesFirmwareLayout() throws {
        let bytes: [UInt8] = [
            0x11, 0x07, 0x00,                   // access point, scan 7
            0xAA, 0xBB, 0xCC, 0x01, 0x02, 0x03, // BSSID
            0xB5,                               // -75 dBm
            0x06, 0x00,                         // channel 6, no secondary
            0x00, 0x00, 0x00,                   // open, no ciphers
            0x03,                               // 11b + 11g
            0x00, 0x00,                         // no country
            0x00,                               // hidden
        ]
        guard case .accessPoint(let scanID, let ap) = SensorProtocol.decode(Data(bytes)) else {
            Issue.record("not decoded as an access point")
            return
        }
        #expect(scanID == 7)
        #expect(ap.bssid.description == "AA:BB:CC:01:02:03")
        #expect(ap.rssi == -75)
        #expect(ap.channel == 6)
        #expect(ap.auth == .open)
        #expect(ap.capabilities == [.b, .g])
        #expect(ap.countryCode == nil)
        #expect(ap.ssid == nil)
        #expect(ap.frequencyMHz == 2437)
    }

    @Test func scanFinishedRoundTrips() {
        let message = SensorProtocol.Message.scanFinished(scanID: 65535, count: 23, duration: .milliseconds(1640))
        #expect(SensorProtocol.decode(SensorProtocol.encode(message)) == message)
    }

    @Test(arguments: [
        Data(),
        Data([0x11, 0x01]),
        Data([0x42, 0x00, 0x00]),
        Data([0x11, 0, 0, 1, 2, 3, 4, 5, 6, 0xC0, 1, 0, 3, 4, 4, 0, 0, 0, 5, 0x41]),
    ])
    func rejectsMalformed(data: Data) {
        #expect(SensorProtocol.decode(data) == nil)
    }

    @Test func commands() {
        #expect(SensorProtocol.Command.scanOnce.data == Data([0x01]))
        #expect(SensorProtocol.Command.setInterval(seconds: 5).data == Data([0x02, 5]))
        #expect(SensorProtocol.Command.setInterval(seconds: 900).data == Data([0x02, 255]))
    }
}

struct NetworkStoreTests {
    private func reading(_ last: UInt8, rssi: Int) -> AccessPointReading {
        AccessPointReading(
            bssid: BSSID(bytes: [0, 0, 0, 0, 0, last]), rssi: rssi, channel: 1, secondaryChannel: .none,
            auth: .wpa2PSK, pairwiseCipher: .ccmp, groupCipher: .ccmp, capabilities: [.n],
            countryCode: nil, ssidBytes: Data("n\(last)".utf8)
        )
    }

    private func scan(_ store: NetworkStore, id: UInt16, _ readings: [AccessPointReading]) {
        store.handle(.scanStarted(scanID: id))
        for r in readings { store.handle(.accessPoint(scanID: id, reading: r)) }
        store.handle(.scanFinished(scanID: id, count: readings.count, duration: .seconds(1)))
    }

    @Test func networkGoesOutOfRangeAfterMissedScans() {
        let store = NetworkStore()
        scan(store, id: 1, [reading(1, rssi: -50), reading(2, rssi: -80)])
        for id in 2...UInt16(NetworkStore.scansBeforeOutOfRange) {
            scan(store, id: id, [reading(1, rssi: -52)])
            #expect(store.networks[BSSID(bytes: [0, 0, 0, 0, 0, 2])]?.isInRange == true)
        }
        scan(store, id: 99, [reading(1, rssi: -51)])
        #expect(store.networks[BSSID(bytes: [0, 0, 0, 0, 0, 2])]?.isInRange == false)

        let one = store.networks[BSSID(bytes: [0, 0, 0, 0, 0, 1])]
        #expect(one?.samples.count == NetworkStore.scansBeforeOutOfRange + 1)
        #expect(one?.strongestRSSI == -50)
        #expect(one?.weakestRSSI == -52)
    }

    @Test func comingBackResetsMisses() {
        let store = NetworkStore()
        scan(store, id: 1, [reading(1, rssi: -60)])
        scan(store, id: 2, [])
        scan(store, id: 3, [])
        scan(store, id: 4, [])
        #expect(store.networks.values.first?.isInRange == false)
        scan(store, id: 5, [reading(1, rssi: -61)])
        #expect(store.networks.values.first?.isInRange == true)
    }
}

struct ChannelAdvisorTests {
    private func on(_ channel: Int, rssi: Int) -> AccessPointReading {
        AccessPointReading(
            bssid: BSSID(bytes: [0, 0, 0, 0, 0, UInt8(channel)]), rssi: rssi, channel: channel,
            secondaryChannel: .none, auth: .wpa2PSK, pairwiseCipher: .ccmp, groupCipher: .ccmp,
            capabilities: [.n], countryCode: nil, ssidBytes: Data()
        )
    }

    @Test func avoidsTheLoudChannel() {
        let readings = [on(1, rssi: -40), on(6, rssi: -70), on(11, rssi: -85)]
        #expect(ChannelAdvisor.recommendation(from: readings) == 11)
    }

    @Test func neighbouringChannelsCountPartly() {
        // Channel 3 overlaps both 1 and 6, so neither is clean; 11 is.
        let readings = [on(3, rssi: -45)]
        #expect(ChannelAdvisor.congestion(on: 1, from: readings) > 0)
        #expect(ChannelAdvisor.congestion(on: 6, from: readings) > 0)
        #expect(ChannelAdvisor.congestion(on: 11, from: readings) == 0)
        #expect(ChannelAdvisor.recommendation(from: readings) == 11)
    }

    @Test func fortyMegahertzCoversMore() {
        var wide = on(6, rssi: -50)
        wide.secondaryChannel = .above
        #expect(wide.occupiedChannels == 4...12)
        #expect(ChannelAdvisor.congestion(on: 11, from: [wide]) > 0)
    }
}

struct LastScanTests {
    @Test func heldNetworkIsNotInLastScan() {
        let store = NetworkStore()
        let weak = AccessPointReading(
            bssid: BSSID(bytes: [0, 0, 0, 0, 0, 9]), rssi: -88, channel: 13, secondaryChannel: .none,
            auth: .wpa2PSK, pairwiseCipher: .ccmp, groupCipher: .ccmp, capabilities: [.n],
            countryCode: nil, ssidBytes: Data("weak".utf8)
        )
        store.handle(.scanStarted(scanID: 1))
        store.handle(.accessPoint(scanID: 1, reading: weak))
        store.handle(.scanFinished(scanID: 1, count: 1, duration: .seconds(1)))
        store.handle(.scanStarted(scanID: 2))
        store.handle(.scanFinished(scanID: 2, count: 0, duration: .seconds(1)))

        let network = store.networks[weak.bssid]
        #expect(network?.wasInLastScan == false)
        #expect(network?.isInRange == true)
    }
}
