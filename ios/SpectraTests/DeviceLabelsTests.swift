import Foundation
import Testing
@testable import Spectra

struct DeviceLabelsTests {
    private func fresh() -> (DeviceLabels, UserDefaults) {
        let defaults = UserDefaults(suiteName: "labels-\(UUID().uuidString)")!
        return (DeviceLabels(defaults: defaults), defaults)
    }

    private func host(_ ip: String, mac: String? = nil, name: String? = nil) -> LANDevice {
        LANDevice(ipAddress: ip, macAddress: mac.flatMap(MACAddress.init(string:)), hostnames: name.map { [$0] } ?? [])
    }

    @Test func labelIsAppliedAndPersists() {
        let (labels, defaults) = fresh()
        let device = host("192.168.1.10", mac: "AA:BB:CC:00:00:01", name: "nas.local")
        device.isNew = true
        labels.set(DeviceLabel(name: "  Living room NAS ", trust: .trusted, note: ""), for: device)
        #expect(device.displayName == "Living room NAS")
        #expect(device.trust == .trusted)
        #expect(device.note == nil)
        #expect(!device.isNew, "a trusted device is not new")

        let again = DeviceLabels(defaults: defaults)
        let later = host("192.168.1.99", mac: "AA:BB:CC:00:00:01")
        again.apply(to: later)
        #expect(later.customName == "Living room NAS")
        #expect(later.trust == .trusted)
    }

    @Test func nameAloneFindsTheLabelWhenTheMACIsHidden() {
        let (labels, _) = fresh()
        labels.set(DeviceLabel(name: "Printer", trust: .trusted, note: nil), for: host("192.168.1.30", mac: "3C:D9:2B:01:02:03", name: "HP-Printer.local"))
        let hidden = host("192.168.1.31", name: "hp-printer")
        labels.apply(to: hidden)
        #expect(hidden.customName == "Printer")
    }

    @Test func addressOnlyMatchesOnlyAnAddressOnlyHost() {
        let (labels, _) = fresh()
        labels.set(DeviceLabel(name: "Mystery box", trust: .trusted, note: nil), for: host("192.168.1.80"))

        // Still nothing but the address: the label applies.
        let same = host("192.168.1.80")
        labels.apply(to: same)
        #expect(same.customName == "Mystery box")

        // A host with a hardware address of its own took the address: it must not inherit "trusted".
        let stranger = host("192.168.1.80", mac: "DE:AD:BE:EF:00:01")
        labels.apply(to: stranger)
        #expect(stranger.customName == nil)
        #expect(stranger.trust == .unknown)
    }

    @Test func clearingEveryFieldRemovesTheLabel() {
        let (labels, _) = fresh()
        let device = host("192.168.1.10", mac: "AA:BB:CC:00:00:01")
        labels.set(DeviceLabel(name: "TV", trust: .suspicious, note: "flickers"), for: device)
        #expect(labels.label(for: device) != nil)
        labels.set(DeviceLabel(name: " ", trust: .unknown, note: nil), for: device)
        #expect(labels.label(for: device) == nil)
        #expect(device.customName == nil)
        #expect(labels.labels.isEmpty)
    }

    @Test func savedHostsAreDecoratedOnTheWayOut() {
        let (labels, _) = fresh()
        let live = host("192.168.1.10", mac: "AA:BB:CC:00:00:01")
        labels.set(DeviceLabel(name: "Desk Mac", trust: .trusted, note: nil), for: live)
        let fromHistory = labels.decorated(SavedLAN(live).device())
        #expect(fromHistory.displayName == "Desk Mac")
    }
}
