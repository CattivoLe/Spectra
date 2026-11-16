import Foundation
import Testing
@testable import Spectra

struct KnownHostsTests {
    private let network = "lan:net:192.168.1.0/24"

    private func fresh() -> KnownHosts {
        KnownHosts(defaults: UserDefaults(suiteName: "known-hosts-\(UUID().uuidString)")!)
    }

    private func host(_ ip: String, mac: String? = nil, name: String? = nil, gateway: Bool = false, local: Bool = false) -> LANDevice {
        LANDevice(
            ipAddress: ip,
            macAddress: mac.flatMap(MACAddress.init(string:)),
            hostnames: name.map { [$0] } ?? [],
            isGateway: gateway,
            isLocalDevice: local
        )
    }

    @Test func firstScanIsABaselineAndMarksNothing() {
        let known = fresh()
        let devices = [host("192.168.1.1", gateway: true), host("192.168.1.10", mac: "AA:BB:CC:00:00:01"), host("192.168.1.20")]
        let verdict = known.classify(devices, network: network)
        #expect(verdict.isBaseline)
        #expect(verdict.newIPs.isEmpty)
        #expect(devices.allSatisfy { !$0.isNew })
        #expect(known.knows(network: network))
    }

    @Test func aHostTheNetworkNeverShowedIsNew() {
        let known = fresh()
        known.classify([host("192.168.1.1", gateway: true), host("192.168.1.10", mac: "AA:BB:CC:00:00:01")], network: network)

        let stranger = host("192.168.1.77", mac: "DE:AD:BE:EF:00:01")
        let old = host("192.168.1.10", mac: "AA:BB:CC:00:00:01")
        let verdict = known.classify([host("192.168.1.1", gateway: true), old, stranger], network: network)
        #expect(!verdict.isBaseline)
        #expect(verdict.newIPs == ["192.168.1.77"])
        #expect(stranger.isNew)
        #expect(!old.isNew)

        // Known from now on.
        let again = host("192.168.1.77", mac: "DE:AD:BE:EF:00:01")
        #expect(known.classify([again], network: network).newIPs.isEmpty)
        #expect(!again.isNew)
    }

    @Test func gatewayAndThisPhoneAreNeverNew() {
        let known = fresh()
        known.classify([host("192.168.1.10")], network: network)
        let gateway = host("192.168.1.1", gateway: true)
        let me = host("192.168.1.42", local: true)
        let verdict = known.classify([gateway, me], network: network)
        #expect(verdict.newIPs.isEmpty)
        #expect(!gateway.isNew && !me.isNew)
    }

    @Test func matchesByMACWhenTheAddressChanged() {
        let known = fresh()
        known.classify([host("192.168.1.10", mac: "AA:BB:CC:00:00:01")], network: network)
        let moved = host("192.168.1.99", mac: "AA:BB:CC:00:00:01")
        #expect(known.classify([moved], network: network).newIPs.isEmpty)
    }

    @Test func matchesByNameWhenTheMACIsHidden() {
        let known = fresh()
        known.classify([host("192.168.1.10", mac: "AA:BB:CC:00:00:01", name: "MacBook-Pro.local")], network: network)
        let hidden = host("192.168.1.99", name: "macbook-pro")
        #expect(known.classify([hidden], network: network).newIPs.isEmpty)
        // Matching by name taught the new address too.
        let byAddress = host("192.168.1.99")
        #expect(known.classify([byAddress], network: network).newIPs.isEmpty)
    }

    @Test func weakNamesDoNotIdentifyAHost() {
        let known = fresh()
        known.classify([host("192.168.1.10", name: "android")], network: network)
        let another = host("192.168.1.11", name: "Android")
        #expect(known.classify([another], network: network).newIPs == ["192.168.1.11"])
        #expect(!another.fingerprints.contains("name:android"))
    }

    @Test func networksAreKeptApart() {
        let known = fresh()
        known.classify([host("192.168.1.10", mac: "AA:BB:CC:00:00:01")], network: network)
        let elsewhere = known.classify([host("192.168.1.10", mac: "AA:BB:CC:00:00:01")], network: "lan:net:10.0.0.0/24")
        #expect(elsewhere.isBaseline)
    }

    @Test func firstSeenIsTheEarliestOfTheFingerprintsAndPersists() {
        let defaults = UserDefaults(suiteName: "known-hosts-\(UUID().uuidString)")!
        let known = KnownHosts(defaults: defaults)
        let day1 = Date(timeIntervalSince1970: 1_700_000_000)
        let day2 = day1.addingTimeInterval(86_400)
        known.classify([host("192.168.1.10", mac: "AA:BB:CC:00:00:01")], network: network, now: day1)
        known.classify([host("192.168.1.10", mac: "AA:BB:CC:00:00:01", name: "nas")], network: network, now: day2)

        let reloaded = KnownHosts(defaults: defaults)
        #expect(reloaded.firstSeen(host("192.168.1.50", name: "NAS"), network: network) == day1)
        #expect(reloaded.firstSeen(host("192.168.1.60"), network: network) == nil)
    }

    @Test func forgettingMakesTheNextScanABaseline() {
        let known = fresh()
        known.classify([host("192.168.1.10")], network: network)
        known.forget(network: network)
        #expect(!known.knows(network: network))
        let verdict = known.classify([host("192.168.1.10"), host("192.168.1.11")], network: network)
        #expect(verdict.isBaseline)
    }

    @Test func savedHistoryKeepsTheNewMarkAndReadsOldFiles() throws {
        let device = host("192.168.1.77", mac: "DE:AD:BE:EF:00:01")
        device.isNew = true
        let saved = SavedLAN(device)
        let data = try JSONEncoder().encode(saved)
        let back = try JSONDecoder().decode(SavedLAN.self, from: data)
        #expect(back.isNew)
        #expect(back.device().isNew)

        // A file written before the mark existed has no such key.
        var object = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        object["isNew"] = nil
        let old = try JSONDecoder().decode(SavedLAN.self, from: JSONSerialization.data(withJSONObject: object))
        #expect(!old.isNew)
    }

    @Test func mergingSessionsKeepsAHostNewOnceItWasNew() {
        let early = SavedLAN(host("192.168.1.77"))
        let late = host("192.168.1.77")
        late.isNew = true
        let merged = HistoryStore.mergeLAN([early], [SavedLAN(late)])
        #expect(merged.first?.isNew == true)
        // And the other way round: the later save without the mark does not clear it.
        let mergedBack = HistoryStore.mergeLAN([SavedLAN(late)], [SavedLAN(host("192.168.1.77"))])
        #expect(mergedBack.first?.isNew == true)
        let entry = HistoryEntry(kind: .lan, savedAt: .now, source: "Test", lan: merged)
        #expect(entry.newHostCount == 1)
    }
}
