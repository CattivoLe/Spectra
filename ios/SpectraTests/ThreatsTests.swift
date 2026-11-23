import Foundation
import Testing
@testable import Spectra

struct ThreatsTests {
    private func ap(_ bssid: [UInt8], ssid: String, auth: AuthMode, bytes: UInt32? = nil) -> TrackedNetwork {
        let reading = AccessPointReading(
            bssid: BSSID(bytes: bssid), rssi: -50, channel: 6, secondaryChannel: .none,
            auth: auth, pairwiseCipher: .ccmp, groupCipher: .ccmp, capabilities: [.n],
            countryCode: nil, ssidBytes: Data(ssid.utf8)
        )
        var net = TrackedNetwork(latest: reading, firstSeen: .distantPast, lastSeen: .distantPast, samples: [])
        if let bytes {
            for _ in 0 ..< Threats.streamingScans { net.noteTraffic(TrafficSnapshot(bytes: bytes, packets: 10)) }
        }
        return net
    }

    @Test func evilTwinWhenSecurityDiffers() {
        let networks = [
            ap([0xAA, 0, 0, 0, 0, 0x01], ssid: "CoffeeWiFi", auth: .wpa2PSK),
            ap([0xBB, 0, 0, 0, 0, 0x02], ssid: "CoffeeWiFi", auth: .open),
        ]
        let findings = Threats.assess(networks: networks, deauths: [])
        let twin = findings.first { $0.kind == .evilTwin }
        #expect(twin != nil)
        #expect(twin?.severity == .critical)
    }

    @Test func evilTwinGoesAwayWhenACopyIsOutOfRange() {
        var gone = ap([0xBB, 0, 0, 0, 0, 0x02], ssid: "CoffeeWiFi", auth: .open)
        gone.missedScans = NetworkStore.scansBeforeOutOfRange
        let networks = [ap([0xAA, 0, 0, 0, 0, 0x01], ssid: "CoffeeWiFi", auth: .wpa2PSK), gone]
        #expect(Threats.assess(networks: networks, deauths: []).contains { $0.kind == .evilTwin } == false)
    }

    @Test func evilTwinListsItsAccessPoints() {
        let networks = [
            ap([0xAA, 0, 0, 0, 0, 0x01], ssid: "CoffeeWiFi", auth: .wpa2PSK),
            ap([0xBB, 0, 0, 0, 0, 0x02], ssid: "CoffeeWiFi", auth: .open),
        ]
        let twin = Threats.assess(networks: networks, deauths: []).first { $0.kind == .evilTwin }
        #expect(Set(twin?.networks ?? []) == Set(networks.map(\.id)))
    }

    @Test func sameSsidSameVendorAndAuthIsNotFlagged() {
        // A normal roaming/mesh network: same SSID, same OUI, same security.
        let networks = [
            ap([0xAA, 0xBB, 0xCC, 0, 0, 0x01], ssid: "HomeMesh", auth: .wpa2PSK),
            ap([0xAA, 0xBB, 0xCC, 0, 0, 0x02], ssid: "HomeMesh", auth: .wpa2PSK),
        ]
        #expect(Threats.assess(networks: networks, deauths: []).contains { $0.kind == .evilTwin } == false)
    }

    @Test func broadcastDeauthIsCritical() {
        let event = DeauthEvent(
            source: BSSID(bytes: [1, 2, 3, 4, 5, 6]),
            target: BSSID(bytes: [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]),
            bssid: BSSID(bytes: [1, 2, 3, 4, 5, 6]), reason: 7, rssi: -40, channel: 6, count: 8
        )
        let findings = Threats.assess(networks: [], deauths: [event])
        #expect(findings.first?.kind == .deauthAttack)
        #expect(findings.first?.severity == .critical)
    }

    /// A camera at about a megabit: 31 KB in the quarter of a second the sensor spends on its channel.
    @Test func aCameraIsFlaggedOnceItKeepsGoing() {
        var camera = ap([2, 0, 0, 0, 0, 2], ssid: "Camera", auth: .wpa2PSK)
        let feed = TrafficSnapshot(bytes: 31_000, packets: 40)
        func flagged() -> Bool { !Threats.assess(networks: [camera], deauths: []).isEmpty }
        for _ in 1 ..< Threats.streamingScans {
            camera.noteTraffic(feed)
            #expect(!flagged())
        }
        camera.noteTraffic(feed)
        #expect(flagged())
        // One sweep that missed it changes nothing; a feed that stopped goes away.
        camera.noteTraffic(feed)
        camera.noteTraffic(nil)
        #expect(flagged())
        camera.noteTraffic(nil)
        #expect(!flagged())
    }

    @Test func aSingleBusySweepIsNotAStream() {
        var network = ap([3, 0, 0, 0, 0, 3], ssid: "Browsing", auth: .wpa2PSK)
        network.noteTraffic(TrafficSnapshot(bytes: 900_000, packets: 700))
        network.noteTraffic(TrafficSnapshot(bytes: 2_000, packets: 4))
        #expect(Threats.assess(networks: [network], deauths: []).isEmpty)
    }

    @Test func theBitrateIsOverTheTimeOnTheChannel() {
        #expect(TrafficSnapshot(bytes: 31_250, packets: 40).estimatedBitrate == String(localized: "\(1.0, specifier: "%.1f") Mbit/s"))
    }

    private func client(_ up: UInt32, frames: Int, down: UInt32, busy: Int, listenMs: Int = 1250) -> ClientTraffic {
        ClientTraffic(mac: BSSID(bytes: [1, 2, 3, 4, 5, 6]), bssid: BSSID(bytes: [6, 5, 4, 3, 2, 1]), channel: 6, rssi: -50, listenMs: listenMs,
                      upBytes: up, upFrames: frames, downBytes: down, downFrames: 1, busySlots: busy)
    }

    /// The same cases as `test_streaming_rule` in the firmware.
    @Test func theRuleForOneClient() {
        #expect(client(78_000, frames: 60, down: 4_000, busy: 13).looksLikeStream)  // a camera
        #expect(client(15_000, frames: 60, down: 0, busy: 13).looksLikeStream)  // a G.711 microphone
        #expect(!client(9_900, frames: 12, down: 0, busy: 13).looksLikeStream)  // under the bar
        #expect(!client(100_000, frames: 1000, down: 0, busy: 13).looksLikeStream)  // acknowledgements of a download
        #expect(!client(150_000, frames: 150, down: 140_000, busy: 13).looksLikeStream)  // a video call
        #expect(!client(300_000, frames: 220, down: 2_000, busy: 4).looksLikeStream)  // a photo going up
        #expect(!client(0, frames: 0, down: 0, busy: 0, listenMs: 0).looksLikeStream)
    }

    @Test func aSenderIsCalledByWhatIsKnown() {
        var camera = client(78_000, frames: 60, down: 0, busy: 13)
        camera.mac = BSSID(bytes: [0x02, 1, 2, 3, 4, 5])
        #expect(Threats.senderName(camera, lanName: { _ in nil }) == "02:01:02:03:04:05")
        #expect(Threats.senderName(camera, lanName: { _ in "Front door" }) == "Front door (02:01:02:03:04:05)")
        camera.mac = BSSID(bytes: [0xB8, 0x27, 0xEB, 3, 4, 5])
        #expect(camera.maker.map { Threats.senderName(camera, lanName: { _ in nil }) == "\($0) (B8:27:EB:03:04:05)" } == true)
    }

    @Test func historySavedWithoutTheCountStillOpens() throws {
        var old = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(ap([1, 0, 0, 0, 0, 1], ssid: "Old", auth: .wpa2PSK, bytes: 500))) as? [String: Any])
        old["steadyScans"] = nil
        let network = try JSONDecoder().decode(TrackedNetwork.self, from: JSONSerialization.data(withJSONObject: old))
        #expect(network.steadyScans == nil && !network.movesDataSteadily)
    }

    @Test func heavyTrafficIsFlagged() {
        let quiet = ap([1, 0, 0, 0, 0, 1], ssid: "Idle", auth: .wpa2PSK, bytes: 500)
        let busy = ap([2, 0, 0, 0, 0, 2], ssid: "Camera", auth: .wpa2PSK, bytes: 250_000)
        let findings = Threats.assess(networks: [quiet, busy], deauths: [])
        let streaming = findings.filter { $0.kind == .streaming }
        #expect(streaming.count == 1)
        #expect(streaming.first?.detail.contains("Camera") == true)
    }
}
