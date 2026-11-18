import Foundation
import Testing
@testable import Spectra

/// The assistant reading History: what is inside the saved sessions, not just their dates.
@MainActor
struct HistoryDigestTests {
    private static let day = 86_400.0
    private static let start = Date(timeIntervalSince1970: 1_800_000_000)

    private func host(_ ip: String, mac: String, name: String, ports: [Int] = []) -> SavedLAN {
        let device = LANDevice(ipAddress: ip, macAddress: MACAddress(string: mac), hostnames: [name])
        device.openPorts = ports.map { DiscoveredPort(number: $0, service: DiscoveredPort.serviceName(for: $0)) }
        device.portsScanned = !ports.isEmpty
        return SavedLAN(device)
    }

    private func buds(rssi: Int) -> SavedBLE {
        let device = BLEDevice(id: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!, rssi: rssi, seen: Self.start)
        device.absorb(BLEAdvertisement.parse(BLEFields(localName: "Galaxy Buds2 Pro")), peripheralName: nil, rssi: rssi, at: Self.start)
        return SavedBLE(device)
    }

    private var entries: [HistoryEntry] {
        [
            HistoryEntry(kind: .lan, savedAt: Self.start, source: "This iPhone", placeLabel: "Home",
                         lan: [host("192.168.1.10", mac: "AA:BB:CC:00:00:10", name: "nas.local", ports: [22, 445]),
                               host("192.168.1.1", mac: "AA:BB:CC:00:00:01", name: "router.local")]),
            HistoryEntry(kind: .lan, savedAt: Self.start.addingTimeInterval(Self.day), source: "This iPhone", placeLabel: "Home",
                         lan: [host("192.168.1.10", mac: "AA:BB:CC:00:00:10", name: "nas.local", ports: [22, 445, 8080])]),
            HistoryEntry(kind: .bluetooth, savedAt: Self.start.addingTimeInterval(2 * Self.day), source: "This iPhone",
                         placeLabel: "Café", bluetooth: [buds(rssi: -61)]),
        ]
    }

    @Test func aNamedThingIsFoundAcrossSessions() {
        let text = HistoryDigest.text(entries, query: "Видел ли я раньше nas.local в истории?")
        #expect(text.contains("192.168.1.10"))
        #expect(text.contains("in 2 sessions"))
        // The latest record wins: the port opened on the second day is there.
        #expect(text.contains("8080"))
        #expect(!text.contains("192.168.1.1 "), "only what the question names")
    }

    @Test func addressesAndMakersAreSearchable() {
        #expect(HistoryDigest.text(entries, query: "aa:bb:cc:00:00:01").contains("192.168.1.1 "))
        #expect(HistoryDigest.text(entries, query: "router.local").contains("AA:BB:CC:00:00:01"))
        #expect(HistoryDigest.text(entries, query: "когда были Galaxy Buds?").contains("Café"))
    }

    @Test func aGeneralQuestionGetsAnOverview() {
        let text = HistoryDigest.text(entries, query: "что было в истории?")
        #expect(text.contains("3 saved sessions"))
        #expect(text.contains("Seen again and again"))
        #expect(text.contains("192.168.1.10"))
        let bluetoothOnly = HistoryDigest.text(entries, query: "какие блютуз устройства были раньше?")
        #expect(bluetoothOnly.contains("Galaxy Buds2 Pro"))
        #expect(!bluetoothOnly.contains("192.168.1.10"))
    }

    @Test func emptyHistorySaysSo() {
        #expect(HistoryDigest.text([], query: "история").contains("History is empty"))
    }

    /// The app reads History for a question about earlier scans itself, before the model speaks.
    @Test func historyQuestionsReadHistoryFirst() throws {
        let calls = AssistantActions.preflightCalls(for: "Было ли это устройство в истории раньше?", devices: [BLEDevice]())
        let first = try #require(calls.first)
        let object = try #require(try JSONSerialization.jsonObject(with: Data(first.utf8)) as? [String: String])
        #expect(object["tool"] == "search_history")
        #expect(object["text"]?.contains("раньше") == true)
        #expect(AssistantActions.preflightCalls(for: "просканируй блютуз", devices: [BLEDevice]()) == ["scan_bluetooth"])
        #expect(AssistantActions.grammar.contains("search_history"))
        #expect(AssistantActions.instructions.contains("search_history"))
    }
}
