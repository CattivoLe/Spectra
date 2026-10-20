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
        if let bytes { net.traffic = TrafficSnapshot(bytes: bytes, packets: 10) }
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

    @Test func heavyTrafficIsFlagged() {
        let quiet = ap([1, 0, 0, 0, 0, 1], ssid: "Idle", auth: .wpa2PSK, bytes: 500)
        let busy = ap([2, 0, 0, 0, 0, 2], ssid: "Camera", auth: .wpa2PSK, bytes: 250_000)
        let findings = Threats.assess(networks: [quiet, busy], deauths: [])
        let streaming = findings.filter { $0.kind == .streaming }
        #expect(streaming.count == 1)
        #expect(streaming.first?.detail.contains("Camera") == true)
    }
}
