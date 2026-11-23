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
        let message = SensorProtocol.Message.scanFinished(scanID: 65535, count: 23, duration: .milliseconds(1640), stationCount: 7, probeCount: 12)
        #expect(SensorProtocol.decode(SensorProtocol.encode(message)) == message)
    }

    /// Firmware 0.11 says how many client records it sent; before that the field is not there.
    @Test func scanFinishedCarriesTheClientCount() throws {
        let data = SensorProtocol.encode(.scanFinished(scanID: 1, count: 2, duration: .milliseconds(3), stationCount: 4, probeCount: 5, clientCount: 6))
        #expect([UInt8](data) == [0x12, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0])
        #expect(SensorProtocol.decode(data) == .scanFinished(scanID: 1, count: 2, duration: .milliseconds(3), stationCount: 4, probeCount: 5, clientCount: 6))
        guard case .scanFinished(_, _, _, _, _, let older) = SensorProtocol.decode(data.prefix(11)) else {
            Issue.record("not decoded as scanFinished")
            return
        }
        #expect(older == nil)
    }

    /// The same bytes as in the firmware's `test_client_traffic_layout`.
    @Test func clientTrafficLayout() {
        let bytes: [UInt8] = [
            0x25, 0x01, 0x02,
            0x44, 0x47, 0xCC, 0x01, 0x02, 0x03,
            0x50, 0xC7, 0xBF, 0x1E, 0x00, 0xA0,
            0x06, 0xCE,
            0xE2, 0x04,
            0xB0, 0x30, 0x01, 0x00, 0x3C, 0x00,
            0xA0, 0x0F, 0x00, 0x00, 0x28, 0x00,
            0x0D,
        ]
        let message = SensorProtocol.Message.clientTraffic(scanID: 0x0201, traffic: ClientTraffic(
            mac: BSSID(bytes: [0x44, 0x47, 0xCC, 1, 2, 3]), bssid: BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0, 0xA0]),
            channel: 6, rssi: -50, listenMs: 1250, upBytes: 78_000, upFrames: 60, downBytes: 4_000, downFrames: 40, busySlots: 13
        ))
        #expect([UInt8](SensorProtocol.encode(message)) == bytes)
        #expect(SensorProtocol.decode(Data(bytes)) == message)
        #expect(SensorProtocol.decode(Data(bytes.dropLast())) == nil)
    }

    @Test func scanFinishedV1HasZeroStations() throws {
        // v1 messages are only 7 bytes, no station count.
        let bytes: [UInt8] = [0x12, 0xFF, 0xFF, 0x05, 0x00, 0x68, 0x06]
        guard case .scanFinished(_, _, _, let staCount, let probeCount, let clientCount) = SensorProtocol.decode(Data(bytes)) else {
            Issue.record("not decoded as scanFinished")
            return
        }
        #expect(staCount == 0)
        #expect(probeCount == 0)
        #expect(clientCount == nil)
    }

    @Test func stationRoundTrips() throws {
        let client = WiFiClient(
            mac: BSSID(bytes: [0xAA, 0xBB, 0xCC, 0x01, 0x02, 0x03]),
            apBSSID: BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x22, 0xA1]),
            rssi: -58,
            isAssociated: true
        )
        let message = SensorProtocol.Message.station(scanID: 42, client: client)
        let data = SensorProtocol.encode(message)
        #expect(data.count == 17)
        let decoded = try #require(SensorProtocol.decode(data))
        #expect(decoded == message)
    }

    @Test func probingStationHasNilBSSID() throws {
        let client = WiFiClient(
            mac: BSSID(bytes: [0x7A, 0x12, 0x34, 0x56, 0x78, 0x9A]),
            apBSSID: nil,
            rssi: -74,
            isAssociated: false
        )
        let data = SensorProtocol.encode(.station(scanID: 1, client: client))
        guard case .station(_, let decoded) = SensorProtocol.decode(data) else {
            Issue.record("not decoded as station")
            return
        }
        #expect(decoded.apBSSID == nil)
        #expect(!decoded.isAssociated)
    }

    @Test func probeReqRoundTrips() throws {
        let probe = ProbeRequest(
            mac: BSSID(bytes: [0x7A, 0x12, 0x34, 0x56, 0x78, 0x9A]),
            rssi: -68,
            ssid: "Sheraton_WiFi",
            channel: 6
        )
        let data = SensorProtocol.encode(.probeReq(scanID: 42, probe: probe))
        #expect(data.count == 12 + 13)
        let decoded = try #require(SensorProtocol.decode(data))
        #expect(decoded == .probeReq(scanID: 42, probe: probe))
    }

    @Test func broadcastProbeHasNilSSID() throws {
        let probe = ProbeRequest(mac: BSSID(bytes: [0x7A, 0xAA, 0xBB, 0x01, 0x02, 0x03]), rssi: -74, ssid: nil, channel: 11)
        let data = SensorProtocol.encode(.probeReq(scanID: 1, probe: probe))
        guard case .probeReq(_, let decoded) = SensorProtocol.decode(data) else {
            Issue.record("not decoded as probeReq")
            return
        }
        #expect(decoded.ssid == nil)
    }

    @Test func scanFinishedV2HasZeroProbes() throws {
        // v2 messages are 9 bytes (no probe count).
        let bytes: [UInt8] = [0x12, 0x01, 0x00, 0x03, 0x00, 0xE8, 0x03, 0x02, 0x00]
        guard case .scanFinished(_, _, _, _, let probeCount, _) = SensorProtocol.decode(Data(bytes)) else {
            Issue.record("not decoded as scanFinished")
            return
        }
        #expect(probeCount == 0)
    }

    @Test func deauthRoundTrips() throws {
        let event = DeauthEvent(
            source: BSSID(bytes: [0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01]),
            target: BSSID(bytes: [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]),
            bssid: BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x22, 0xA1]),
            reason: 7,
            rssi: -45,
            channel: 6,
            count: 24
        )
        let data = SensorProtocol.encode(.deauth(scanID: 99, event: event))
        #expect(data.count == 27)
        let decoded = try #require(SensorProtocol.decode(data))
        #expect(decoded == .deauth(scanID: 99, event: event))
    }

    @Test func ssidRevealRoundTrips() throws {
        let bssid = BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x77, 0xA7])
        let message = SensorProtocol.Message.ssidReveal(scanID: 5, bssid: bssid, rssi: -76, ssid: "HiddenCam_Stream")
        let data = SensorProtocol.encode(message)
        #expect(data.count == 11 + 16)
        let decoded = try #require(SensorProtocol.decode(data))
        #expect(decoded == message)
    }

    @Test func vendorIERoundTrips() throws {
        let bssid = BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x33, 0xA3])
        let message = SensorProtocol.Message.vendorIE(
            scanID: 4, bssid: bssid, deviceName: "Archer C6", manufacturer: "TP-Link"
        )
        let data = SensorProtocol.encode(message)
        #expect(data.count == 11 + 9 + 7)
        let decoded = try #require(SensorProtocol.decode(data))
        #expect(decoded == message)
    }

    @Test func vendorIERejectsWhenBothNamesAreEmpty() {
        let bytes: [UInt8] = [0x18, 0x01, 0x00, 0x50, 0xC7, 0xBF, 0x1E, 0x00, 0xA0, 0x00, 0x00]
        #expect(SensorProtocol.decode(Data(bytes)) == nil)
    }

    @Test func batteryRoundTrips() throws {
        let message = SensorProtocol.Message.battery(scanID: 8, level: 85, isCharging: false)
        let data = SensorProtocol.encode(message)
        #expect(data.count == 5)
        #expect(SensorProtocol.decode(data) == message)
        let charging = SensorProtocol.Message.battery(scanID: 8, level: 140, isCharging: true)
        guard case .battery(_, let level, let isCharging) = SensorProtocol.decode(SensorProtocol.encode(charging)) else {
            Issue.record("not a battery message")
            return
        }
        #expect(level == 100)
        #expect(isCharging)
    }

    @Test func infoDecodesBattery() throws {
        let json = #"{"proto":6,"fw":"0.7.0","chip":"ESP32-S3","bands":["2.4"],"interval":5,"ota":true,"bat":85,"chg":false}"#
        let info = try JSONDecoder().decode(SensorInfo.self, from: Data(json.utf8))
        #expect(info.batteryLevel == 85)
        #expect(info.isCharging == false)
    }

    /// The C5 says it has an LED and no speaker, so the phone offers just the LED; older firmware says nothing.
    @Test func infoDecodesAlertOutputs() throws {
        let c5 = #"{"proto":9,"fw":"0.10.1","name":"ESP32-C5","chip":"ESP32-C5","bands":["2.4","5"],"interval":30,"alerts":3,"outputs":2,"watch":0}"#
        #expect(try JSONDecoder().decode(SensorInfo.self, from: Data(c5.utf8)).outputs == 2)
        let old = #"{"proto":9,"fw":"0.10.0","chip":"ESP32-S3","bands":["2.4"],"interval":30,"alerts":3,"watch":0}"#
        #expect(try JSONDecoder().decode(SensorInfo.self, from: Data(old.utf8)).outputs == nil)
    }

    @Test func trafficRoundTrips() throws {
        let bssid = BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x00, 0xA0])
        let message = SensorProtocol.Message.traffic(scanID: 10, bssid: bssid, bytes: 185_000, packets: 312)
        let data = SensorProtocol.encode(message)
        #expect(data.count == 15)
        let decoded = try #require(SensorProtocol.decode(data))
        #expect(decoded == message)
    }

    @Test func trafficLargeValue() throws {
        let bssid = BSSID(bytes: [0xAA, 0xBB, 0xCC, 0x01, 0x02, 0x03])
        let message = SensorProtocol.Message.traffic(scanID: 1, bssid: bssid, bytes: 4_000_000_000, packets: 50000)
        let decoded = try #require(SensorProtocol.decode(SensorProtocol.encode(message)))
        #expect(decoded == message)
    }

    @Test func ssidRevealEmptyRejects() {
        let bytes: [UInt8] = [0x16, 0x01, 0x00, 0x50, 0xC7, 0xBF, 0x1E, 0x77, 0xA7, 0xB4, 0x00]
        #expect(SensorProtocol.decode(Data(bytes)) == nil)
    }

    @Test func deauthBroadcastSeverity() {
        let event = DeauthEvent(
            source: BSSID(bytes: [0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01]),
            target: BSSID(bytes: [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]),
            bssid: BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x22, 0xA1]),
            reason: 7, rssi: -45, channel: 6, count: 5
        )
        #expect(event.isBroadcast)
        #expect(event.severity == .critical)
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
        #expect(SensorProtocol.Command.rememberWifi(ssid: "Cafe", password: "abc").data == Data([0x05, 4, 0x43, 0x61, 0x66, 0x65, 3, 0x61, 0x62, 0x63]))
        #expect(SensorProtocol.Command.forgetWifi(ssid: "Cafe").data == Data([0x06, 4, 0x43, 0x61, 0x66, 0x65]))
        #expect(SensorProtocol.Command.setPeer(name: "Pix").data == Data([0x07, 0x50, 0x69, 0x78]))
        #expect(SensorProtocol.Command.ackKnown.data == Data([0x08]))
        #expect(SensorProtocol.Command.resetKnown.data == Data([0x09]))
        #expect(SensorProtocol.Command.startRawCapture(channel: 6, bssid: nil).data
            == Data([0x0A, 6, 0, 0, 0, 0, 0, 0, 0]))
        #expect(SensorProtocol.Command.startRawCapture(channel: 11, bssid: BSSID(bytes: [0xAA, 0xBB, 0xCC, 0x01, 0x02, 0x03])).data
            == Data([0x0A, 11, 1, 0xAA, 0xBB, 0xCC, 0x01, 0x02, 0x03]))
        #expect(SensorProtocol.Command.stopRawCapture.data == Data([0x0B]))
        #expect(SensorProtocol.Command.setAlerts(sound: true, light: true).data == Data([0x0C, 0x03]))
        #expect(SensorProtocol.Command.setAlerts(sound: false, light: true).data == Data([0x0C, 0x02]))
        #expect(SensorProtocol.Command.setAlerts(sound: false, light: false).data == Data([0x0C, 0x00]))
        #expect(SensorProtocol.Command.setWatch(addresses: [BSSID(bytes: [0xAA, 0xBB, 0xCC, 0x01, 0x02, 0x03])]).data
            == Data([0x0D, 1, 0xAA, 0xBB, 0xCC, 0x01, 0x02, 0x03]))
        #expect(SensorProtocol.Command.setWatch(addresses: []).data == Data([0x0D, 0]))
        #expect(SensorProtocol.Command.address(fromWatchID: "AA:BB:CC:01:02:03")?.bytes == [0xAA, 0xBB, 0xCC, 0x01, 0x02, 0x03])
        #expect(SensorProtocol.Command.address(fromWatchID: "8D3E2F4A-1B2C-4D5E-9F00-112233445566") == nil)
        let uploaded = SensorProtocol.Message.knownWifi(index: 0, total: 1, ssid: "Cafe", password: "abc")
        #expect(SensorProtocol.decode(SensorProtocol.encode(uploaded)) == uploaded)
    }

    @Test func rawFrameFragmentRoundTrips() throws {
        let fragment = SensorProtocol.RawFrameFragment(
            seq: 258, fragmentIndex: 0, fragmentCount: 1,
            timestampMicros: 123_456_789, channel: 6, rssi: -58,
            originalLength: 4, payload: [0x80, 0x00, 0x11, 0x22]
        )
        let decoded = try #require(SensorProtocol.decode(SensorProtocol.encode(.rawFrame(fragment))))
        #expect(decoded == .rawFrame(fragment))
    }

    @Test func rawFrameContinuationCarriesNoMetadata() throws {
        let tail = SensorProtocol.RawFrameFragment(
            seq: 7, fragmentIndex: 1, fragmentCount: 2,
            timestampMicros: 0, channel: 0, rssi: 0, originalLength: 0,
            payload: [0xDE, 0xAD, 0xBE, 0xEF]
        )
        let decoded = try #require(SensorProtocol.decode(SensorProtocol.encode(.rawFrame(tail))))
        #expect(decoded == .rawFrame(tail))
    }

    @Test func rawFrameAssemblerStitchesFragments() throws {
        var assembler = RawFrameAssembler()
        let whole: [UInt8] = Array(0..<120).map { UInt8($0) }
        let head = SensorProtocol.RawFrameFragment(
            seq: 42, fragmentIndex: 0, fragmentCount: 2,
            timestampMicros: 9_000, channel: 3, rssi: -70,
            originalLength: whole.count, payload: Array(whole[0..<80])
        )
        let tail = SensorProtocol.RawFrameFragment(
            seq: 42, fragmentIndex: 1, fragmentCount: 2,
            timestampMicros: 0, channel: 0, rssi: 0, originalLength: 0,
            payload: Array(whole[80...])
        )
        let partial = assembler.ingest(head)
        #expect(partial == nil)
        let finished = assembler.ingest(tail)
        let completed = try #require(finished)
        #expect(completed.bytes == whole)
        #expect(completed.channel == 3)
        #expect(completed.rssi == -70)
        #expect(completed.originalLength == 120)
        #expect(assembler.dropped == 0)
    }

    @Test func rawFrameAssemblerDropsBrokenFrame() {
        var assembler = RawFrameAssembler()
        let head = SensorProtocol.RawFrameFragment(
            seq: 1, fragmentIndex: 0, fragmentCount: 2,
            timestampMicros: 0, channel: 1, rssi: -40, originalLength: 8, payload: [1, 2, 3, 4]
        )
        _ = assembler.ingest(head)
        // A fragment from a different frame arrives before the tail.
        let stray = SensorProtocol.RawFrameFragment(
            seq: 2, fragmentIndex: 1, fragmentCount: 2,
            timestampMicros: 0, channel: 0, rssi: 0, originalLength: 0, payload: [9]
        )
        let result = assembler.ingest(stray)
        #expect(result == nil)
        #expect(assembler.dropped == 1)
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
        store.handle(.scanFinished(scanID: id, count: readings.count, duration: .seconds(1), stationCount: 0, probeCount: 0))
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

    @Test func networkLeavesTheListAfterEnoughMisses() {
        let store = NetworkStore()
        scan(store, id: 1, [reading(1, rssi: -60), reading(2, rssi: -70)])
        for id in 2 ..< UInt16(NetworkStore.scansBeforeRemoval + 1) {
            scan(store, id: id, [reading(1, rssi: -60)])
            #expect(store.networks[BSSID(bytes: [0, 0, 0, 0, 0, 2])] != nil)
        }
        scan(store, id: 99, [reading(1, rssi: -60)])
        #expect(store.networks[BSSID(bytes: [0, 0, 0, 0, 0, 2])] == nil)
        #expect(store.networks.count == 1)
    }

    @Test func trafficAppliedOnScanFinished() {
        let store = NetworkStore()
        let bssid = BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x00, 0xA0])
        let ap = AccessPointReading(
            bssid: bssid, rssi: -50, channel: 6, secondaryChannel: .none,
            auth: .wpa2PSK, pairwiseCipher: .ccmp, groupCipher: .ccmp,
            capabilities: [.n], countryCode: nil, ssidBytes: Data("Home".utf8)
        )
        store.handle(.scanStarted(scanID: 1))
        store.handle(.accessPoint(scanID: 1, reading: ap))
        store.handle(.traffic(scanID: 1, bssid: bssid, bytes: 185_000, packets: 312))
        #expect(store.networks[bssid]?.traffic == nil)
        store.handle(.scanFinished(scanID: 1, count: 1, duration: .seconds(3), stationCount: 0, probeCount: 0))
        #expect(store.networks[bssid]?.traffic?.bytes == 185_000)
        #expect(store.networks[bssid]?.traffic?.packets == 312)
    }

    private func traffic(_ mac: [UInt8], up: UInt32 = 78_000, frames: Int = 60, down: UInt32 = 4_000, busy: Int = 13) -> ClientTraffic {
        ClientTraffic(mac: BSSID(bytes: mac), bssid: BSSID(bytes: [0, 0, 0, 0, 0, 1]), channel: 6, rssi: -50, listenMs: 1250,
                      upBytes: up, upFrames: frames, downBytes: down, downFrames: 40, busySlots: busy)
    }

    /// One sweep of a sensor that counts by client.
    private func sweep(_ store: NetworkStore, id: UInt16, apBytes: UInt32 = 0, _ traffic: [ClientTraffic]) {
        let bssid = BSSID(bytes: [0, 0, 0, 0, 0, 1])
        store.handle(.scanStarted(scanID: id))
        store.handle(.accessPoint(scanID: id, reading: reading(1, rssi: -50)))
        store.handle(.traffic(scanID: id, bssid: bssid, bytes: apBytes, packets: 1))
        for item in traffic { store.handle(.clientTraffic(scanID: id, traffic: item)) }
        store.handle(.scanFinished(scanID: id, count: 1, duration: .seconds(1), stationCount: 0, probeCount: 0, clientCount: traffic.count))
    }

    @Test func aClientThatKeepsSendingIsNamed() throws {
        let store = NetworkStore()
        let camera = traffic([0x44, 0x19, 0xB6, 1, 2, 3])
        sweep(store, id: 1, [camera])
        sweep(store, id: 2, [camera])
        #expect(store.steadySenders.isEmpty)
        sweep(store, id: 3, [camera])
        #expect(store.steadySenders.map(\.id) == [camera.mac])

        let findings = Threats.assess(networks: Array(store.networks.values), deauths: [], senders: store.steadySenders) {
            $0 == camera.mac ? "Front door" : nil
        }
        let finding = try #require(findings.first)
        #expect(findings.count == 1)
        #expect(finding.sender == camera)
        #expect(finding.networks == [camera.bssid])
        #expect(finding.detail.contains("Front door") && finding.detail.contains("44:19:B6:01:02:03") && finding.detail.contains("n1"))

        // One sweep without it changes nothing, two do.
        sweep(store, id: 4, [camera])
        sweep(store, id: 5, [])
        #expect(store.steadySenders.count == 1)
        sweep(store, id: 6, [])
        #expect(store.steadySenders.isEmpty)
    }

    /// A film someone watches fills the access point's counter and nobody's uplink.
    @Test func aBusyNetworkIsNotAStreamWhenClientsAreCounted() {
        let store = NetworkStore()
        for id in 1 ... 4 { sweep(store, id: UInt16(id), apBytes: 500_000, [traffic([2, 2, 2, 2, 2, 2], up: 12_000, frames: 120, down: 400_000)]) }
        #expect(Threats.assess(networks: Array(store.networks.values), deauths: [], senders: store.steadySenders).isEmpty)
        #expect(store.clientTraffic.count == 1)
    }

    /// An older sensor sends no client records: the access point is judged, as before.
    @Test func anOlderSensorStillFlagsTheNetwork() {
        let store = NetworkStore()
        let bssid = BSSID(bytes: [0, 0, 0, 0, 0, 1])
        for id in UInt16(1) ... 3 {
            store.handle(.scanStarted(scanID: id))
            store.handle(.accessPoint(scanID: id, reading: reading(1, rssi: -50)))
            store.handle(.traffic(scanID: id, bssid: bssid, bytes: 31_000, packets: 40))
            store.handle(.scanFinished(scanID: id, count: 1, duration: .seconds(1), stationCount: 0, probeCount: 0))
        }
        let finding = Threats.assess(networks: Array(store.networks.values), deauths: [], senders: store.steadySenders).first
        #expect(finding?.kind == .streaming && finding?.sender == nil)
    }

    @Test func revealUpdatesHiddenNetwork() {
        let store = NetworkStore()
        let bssid = BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x77, 0xA7])
        let hidden = AccessPointReading(
            bssid: bssid, rssi: -81, channel: 11, secondaryChannel: .none,
            auth: .wpa2PSK, pairwiseCipher: .ccmp, groupCipher: .ccmp,
            capabilities: [.b, .g, .n], countryCode: nil, ssidBytes: Data()
        )
        store.handle(.scanStarted(scanID: 1))
        store.handle(.accessPoint(scanID: 1, reading: hidden))
        #expect(store.networks[bssid]?.latest.ssid == nil)

        store.handle(.ssidReveal(scanID: 1, bssid: bssid, rssi: -76, ssid: "SecretNet"))
        store.handle(.vendorIE(scanID: 1, bssid: bssid, deviceName: "Cam", manufacturer: "Hikvision"))
        #expect(store.networks[bssid]?.revealedSSID == "SecretNet")
        #expect(store.networks[bssid]?.vendorInfo == VendorDeviceInfo(deviceName: "Cam", manufacturer: "Hikvision"))

        store.handle(.scanFinished(scanID: 1, count: 1, duration: .seconds(1), stationCount: 0, probeCount: 0))
        #expect(store.networks[bssid]?.revealedSSID == "SecretNet")
    }
}

struct OUIDatabaseTests {
    @Test func disputedPrefixesKeepOneOwner() {
        #expect(OUIDatabase.lookup("A4:5E:60") == "Apple")
        #expect(OUIDatabase.lookup("78:31:C1") == "Apple")
        #expect(OUIDatabase.lookup("64:B5:C6") == "Nintendo")
        #expect(OUIDatabase.lookup("8C:EC:4B") == "Dell")
        #expect(OUIDatabase.lookup("00:24:6C") == "Aruba")
        #expect(OUIDatabase.lookup("00:17:D5") == "Samsung")
        #expect(OUIDatabase.lookup("08:08:C2") == "Samsung")
        #expect(OUIDatabase.lookup("F0:18:98") == "Apple")
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

    @Test func fiveGHzRecommendationAvoidsTheOccupiedChannel() {
        let best = ChannelAdvisor.recommendationFiveGHz(from: [on(36, rssi: -38)])
        #expect(ChannelAdvisor.fiveGHzCandidates.contains(best))
        #expect(best != 36)
    }
}

struct LoRaDeviceFlagTests {
    private func device(flags: UInt8) -> SensorProtocol.LoRaDevice {
        SensorProtocol.LoRaDevice(freqKHz: 868_100, spreadingFactor: 7, rssi: -100, snr: 2, flags: flags, id: 0x1234_5678)
    }

    @Test func decodesDownlinkAndConfirmed() {
        let d = device(flags: 0x01 | 0x08 | 0x10)
        #expect(d.lorawan)
        #expect(d.downlink)
        #expect(d.confirmed)
        #expect(!d.join)
        #expect(d.title.hasPrefix("↓"))
        #expect(d.detail.contains("confirmed"))
    }

    @Test func plainUplinkHasNoExtraFlags() {
        let d = device(flags: 0x01)
        #expect(!d.downlink)
        #expect(!d.confirmed)
        #expect(d.title.hasPrefix("↑"))
    }

    @Test func privateSyncWordIsShown() {
        #expect(device(flags: 0x01 | 0x02).privateNetwork)
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
        store.handle(.scanFinished(scanID: 1, count: 1, duration: .seconds(1), stationCount: 0, probeCount: 0))
        store.handle(.scanStarted(scanID: 2))
        store.handle(.scanFinished(scanID: 2, count: 0, duration: .seconds(1), stationCount: 0, probeCount: 0))

        let network = store.networks[weak.bssid]
        #expect(network?.wasInLastScan == false)
        #expect(network?.isInRange == true)
    }
}
