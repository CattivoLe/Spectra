import Foundation
import Testing
@testable import Spectra

struct HistoryDocumentTests {
    /// Written the way the Android app's `HistoryStore.encodeDocument` writes it.
    private let android = """
    {"from":"Pixel 8","entries":[
      {"id":"6f1c2a9e-0d4b-4e43-9a51-2f6c1d0e7b10","kind":"networks","savedAt":1760950800000,"source":"Pixel 8",
       "lines":[],"latitude":55.75,"longitude":37.61,
       "networks":[{"reading":{"bssid":"AA:BB:CC:00:11:22","rssi":-48,"channel":36,"secondary":0,"auth":3,
         "pairwise":4,"group":4,"capabilities":2,"ssid":"436166c3a9","band":"FIVE","width":80,"visibility":"NAMED"},
         "firstSeen":1760950700000,"lastSeen":1760950800000,"samples":[[1760950800000,-48]],"missedScans":0,
         "clients":[{"mac":"02:00:00:00:00:01","ap":"AA:BB:CC:00:11:22","rssi":-60,"associated":true}],
         "trafficBytes":1200,"trafficPackets":9,"source":"PHONE"},
        {"reading":{"bssid":"AA:BB:CC:00:11:33","channel":5,"band":"SIX","ssid":""},"source":"PHONE"}]},
      {"id":"lan-1","kind":"lan","savedAt":1760950900000,"source":"Pixel 8","lines":[],"placeKey":"wifi:Home","placeLabel":"Home",
       "lan":[{"ip":"192.168.1.1","mac":"10:20:30:40:50:60","hostnames":["router.lan"],"ports":[[80,"http"]],
         "services":[],"portsScanned":true,"gateway":true,"local":false,"profile":{"banners":[],"rtsp":[]},
         "firstSeen":1760950850000,"lastSeen":1760950900000}],
       "connections":[{"checkedAt":1760950880000,"viaVpn":false,"status":"NO_NETWORK","speedFailed":false,
         "gateway":{"name":"192.168.1.1","ok":true,"millis":3},"regions":[],"abroad":[],"abroadNote":"BUSY",
         "remoteTraces":[],"traceNote":"NONE"}]},
      {"id":"ble-1","kind":"bluetooth","savedAt":1760951000000,"source":"Pixel 8","lines":[],"importedFrom":"Galaxy",
       "bluetooth":[{"address":"F0:0D:00:00:00:01","kind":"Random","transport":"LE","localName":"Tag",
         "companyId":76,"payload":"1005","services":["180F"],"solicited":[],"serviceData":{},"rssi":-70,
         "samples":[[1760951000000,-70]],"packets":4,"firstSeen":1760950990000,"lastSeen":1760951000000}]}
    ]}
    """

    @Test func readsAnAndroidFile() throws {
        let (from, entries) = try #require(HistoryDocument.decode(Data(android.utf8)))
        #expect(from == "Pixel 8")
        #expect(entries.map(\.kind) == [.networks, .lan, .bluetooth])

        let wifi = entries[0]
        #expect(wifi.id == UUID(uuidString: "6f1c2a9e-0d4b-4e43-9a51-2f6c1d0e7b10"))
        #expect(wifi.savedAt == Date(timeIntervalSince1970: 1_760_950_800))
        #expect(wifi.latitude == 55.75)
        #expect(wifi.networks.count == 1, "the 6 GHz network is left out")
        let network = try #require(wifi.networks.first)
        #expect(network.latest.ssid == "Café")
        #expect(network.latest.channel == 36)
        #expect(network.clients.first?.isAssociated == true)
        #expect(network.traffic?.bytes == 1200)

        let lan = entries[1]
        #expect(lan.placeLabel == "Home")
        #expect(lan.lan.first?.isGateway == true)
        #expect(lan.lan.first?.openPorts.first?.number == 80)
        #expect(lan.connections.first?.snapshot.status == .noNetwork)
        #expect(lan.connections.first?.snapshot.gateway?.millis == 3)
        #expect(lan.connections.first?.snapshot.abroadNote == .busy)

        let ble = entries[2]
        #expect(ble.importedFrom == "Galaxy")
        #expect(ble.bluetooth.first?.localName == "Tag")
        #expect(ble.bluetooth.first?.companyID == 76)
        #expect(ble.bluetooth.first?.packetCount == 4)
        let again = try #require(HistoryDocument.decode(Data(android.utf8)))
        #expect(again.entries[1].id == lan.id, "a non-UUID id maps to the same UUID every time")
        #expect(again.entries[2].bluetooth.first?.id == ble.bluetooth.first?.id)
    }

    @Test func roundTripsAnIPhoneSession() throws {
        let reading = AccessPointReading(
            bssid: BSSID(bytes: [0xAA, 0, 0, 0, 0, 0x01]), rssi: -55, channel: 11, secondaryChannel: .below,
            auth: .wpa2PSK, pairwiseCipher: .ccmp, groupCipher: .ccmp, capabilities: [.n],
            countryCode: "RU", ssidBytes: Data("Office".utf8)
        )
        let network = TrackedNetwork(
            latest: reading, firstSeen: Date(timeIntervalSince1970: 1_000), lastSeen: Date(timeIntervalSince1970: 2_000),
            samples: [.init(date: Date(timeIntervalSince1970: 2_000), rssi: -55)]
        )
        var connection = LinkSnapshot()
        connection.checkedAt = Date(timeIntervalSince1970: 3_000)
        connection.status = .online
        connection.external = ExternalAddress(ip: "203.0.113.5", countryCode: "NL", warp: true)
        let entry = HistoryEntry(
            kind: .networks, savedAt: Date(timeIntervalSince1970: 4_000), source: "iPhone",
            captureID: 42, placeKey: "geo:1_2", networks: [network],
            connections: [SavedConnection(viaVpn: true, snapshot: connection)]
        )

        let file = HistoryDocument.file([entry], from: "iPhone")
        #expect(file.name.hasPrefix("spectra-") && file.name.hasSuffix(".json"))
        let (from, entries) = try #require(HistoryDocument.decode(Data(file.contents.utf8)))
        #expect(from == "iPhone")
        let back = try #require(entries.first)
        #expect(back.id == entry.id)
        #expect(back.savedAt == entry.savedAt)
        #expect(back.captureID == 42)
        #expect(back.networks.first?.latest == reading)
        #expect(back.networks.first?.firstSeen == network.firstSeen)
        #expect(back.connections.first?.viaVpn == true)
        #expect(back.connections.first?.snapshot.external?.warp == true)
    }

    @Test func rejectsOtherFiles() {
        #expect(HistoryDocument.decode(Data("{\"type\":\"FeatureCollection\"}".utf8)) == nil)
        #expect(HistoryDocument.decode(Data("not json".utf8)) == nil)
    }
}
