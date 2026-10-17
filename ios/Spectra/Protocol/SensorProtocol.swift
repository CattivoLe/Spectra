import CoreBluetooth
import Foundation

/// The BLE contract with the Cardputer firmware. docs/protocol.md is the
/// source of truth; keep the two in step.
nonisolated enum SensorProtocol {
    static let version = 7

    static let service = CBUUID(string: "53500001-8F2D-4B6A-9C1E-737065637472")
    static let info = CBUUID(string: "53500002-8F2D-4B6A-9C1E-737065637472")
    static let control = CBUUID(string: "53500003-8F2D-4B6A-9C1E-737065637472")
    static let results = CBUUID(string: "53500004-8F2D-4B6A-9C1E-737065637472")
    static let firmware = CBUUID(string: "53500005-8F2D-4B6A-9C1E-737065637472")

    enum Command {
        case scanOnce
        case setInterval(seconds: Int)
        case ackSaved(captureID: UInt32)
        case scanLoRa

        var data: Data {
            switch self {
            case .scanOnce:
                Data([0x01])
            case .setInterval(let seconds):
                Data([0x02, UInt8(clamping: seconds)])
            case .scanLoRa:
                Data([0x04])
            case .ackSaved(let captureID):
                Data([
                    0x03,
                    UInt8(captureID & 0xFF),
                    UInt8((captureID >> 8) & 0xFF),
                    UInt8((captureID >> 16) & 0xFF),
                    UInt8((captureID >> 24) & 0xFF),
                ])
            }
        }
    }

    /// One row of a capture the Cardputer stored and is uploading.
    struct SavedRecord: Equatable {
        enum Kind: UInt8 {
            case networks = 1
            case lan = 2
            case bluetooth = 3
            case lora = 4
        }

        var kind: Kind
        var captureID: UInt32
        var unix: UInt32
        var index: Int
        var total: Int
        var title: String
        var detail: String
        var trailing: String
    }

    enum Message: Equatable {
        case scanStarted(scanID: UInt16)
        case accessPoint(scanID: UInt16, reading: AccessPointReading)
        case scanFinished(scanID: UInt16, count: Int, duration: Duration, stationCount: Int, probeCount: Int)
        case station(scanID: UInt16, client: WiFiClient)
        case probeReq(scanID: UInt16, probe: ProbeRequest)
        case deauth(scanID: UInt16, event: DeauthEvent)
        case ssidReveal(scanID: UInt16, bssid: BSSID, rssi: Int, ssid: String)
        case traffic(scanID: UInt16, bssid: BSSID, bytes: UInt32, packets: Int)
        case vendorIE(scanID: UInt16, bssid: BSSID, deviceName: String, manufacturer: String)
        case battery(scanID: UInt16, level: Int, isCharging: Bool)
        case lora(scanID: UInt16, device: LoRaDevice)
        case loraScan(scanID: UInt16, started: Bool, count: Int, durationMs: Int)
        case loraProgress(scanID: UInt16, freqKHz: UInt32, spreadingFactor: Int, step: Int, total: Int)
        case savedPosition(captureID: UInt32, latitude: Double, longitude: Double)
        case gps(valid: Bool, latitude: Double, longitude: Double, satellites: Int)
        case saved(SavedRecord)
    }

    struct LoRaDevice: Equatable {
        var freqKHz: UInt32
        var spreadingFactor: Int
        var rssi: Int
        var snr: Int
        var flags: UInt8
        var id: UInt32

        var lorawan: Bool { flags & 0x01 != 0 }
        var join: Bool { flags & 0x04 != 0 }

        var title: String {
            let hex = String(format: "%08X", id)
            if join { return "Join \(hex)" }
            if lorawan { return "DevAddr \(hex)" }
            return "Packet \(hex)"
        }

        var detail: String {
            String(format: "%.3f MHz · SF%d · SNR %d dB", Double(freqKHz) / 1000, spreadingFactor, snr)
        }
    }

    enum MessageType: UInt8 {
        case scanStarted = 0x10
        case accessPoint = 0x11
        case scanFinished = 0x12
        case station = 0x13
        case probeReq = 0x14
        case deauth = 0x15
        case ssidReveal = 0x16
        case traffic = 0x17
        case vendorIE = 0x18
        case battery = 0x19
        case savedWifi = 0x1A
        case savedBle = 0x1B
        case savedLan = 0x1C
        case savedGps = 0x1D
        case loraDevice = 0x1E
        case loraScan = 0x1F
        case savedLora = 0x20
        case gps = 0x21
        case loraProgress = 0x22
    }

    /// Decodes one Results notification, or returns nil for anything malformed
    /// or from a newer protocol the app does not know.
    static func decode(_ data: Data) -> Message? {
        let bytes = [UInt8](data)
        guard let first = bytes.first else { return nil }
        if first == MessageType.savedWifi.rawValue
            || first == MessageType.savedBle.rawValue
            || first == MessageType.savedLan.rawValue
            || first == MessageType.savedLora.rawValue {
            return decodeSaved(bytes)
        }
        if first == MessageType.savedGps.rawValue {
            return decodeSavedGps(bytes)
        }
        if first == MessageType.gps.rawValue {
            return decodeGps(bytes)
        }
        guard let type = MessageType(rawValue: first), bytes.count >= 3 else {
            return nil
        }
        let scanID = u16(bytes, 1)

        switch type {
        case .scanStarted:
            return .scanStarted(scanID: scanID)

        case .scanFinished:
            guard bytes.count >= 7 else { return nil }
            // v2 adds station count at offset 7; v3 adds probe count at offset 9.
            let staCount = bytes.count >= 9 ? Int(u16(bytes, 7)) : 0
            let probeCount = bytes.count >= 11 ? Int(u16(bytes, 9)) : 0
            return .scanFinished(
                scanID: scanID,
                count: Int(u16(bytes, 3)),
                duration: .milliseconds(Int(u16(bytes, 5))),
                stationCount: staCount,
                probeCount: probeCount
            )

        case .accessPoint:
            guard bytes.count >= 19 else { return nil }
            let ssidLength = Int(bytes[18])
            guard ssidLength <= 32, bytes.count >= 19 + ssidLength else { return nil }

            let country: String? = if bytes[16] != 0, bytes[17] != 0 {
                String(bytes: bytes[16...17], encoding: .ascii)
            } else {
                nil
            }
            let reading = AccessPointReading(
                bssid: BSSID(bytes: Array(bytes[3..<9])),
                rssi: Int(Int8(bitPattern: bytes[9])),
                channel: Int(bytes[10]),
                secondaryChannel: SecondaryChannel(rawValue: bytes[11]) ?? .none,
                auth: AuthMode(rawValue: bytes[12]),
                pairwiseCipher: Cipher(rawValue: bytes[13]),
                groupCipher: Cipher(rawValue: bytes[14]),
                capabilities: Capabilities(rawValue: bytes[15]),
                countryCode: country,
                ssidBytes: Data(bytes[19..<(19 + ssidLength)])
            )
            return .accessPoint(scanID: scanID, reading: reading)

        case .station:
            guard bytes.count >= 17 else { return nil }
            let clientMAC = BSSID(bytes: Array(bytes[3..<9]))
            let apBSSID = BSSID(bytes: Array(bytes[9..<15]))
            let rssi = Int(Int8(bitPattern: bytes[15]))
            let associated = (bytes[16] & 0x01) != 0
            let zeroBSSID = apBSSID.bytes.allSatisfy { $0 == 0 }
            let client = WiFiClient(
                mac: clientMAC,
                apBSSID: zeroBSSID ? nil : apBSSID,
                rssi: rssi,
                isAssociated: associated
            )
            return .station(scanID: scanID, client: client)

        case .probeReq:
            guard bytes.count >= 12 else { return nil }
            let mac = BSSID(bytes: Array(bytes[3..<9]))
            let rssi = Int(Int8(bitPattern: bytes[9]))
            let channel = Int(bytes[10])
            let ssidLen = Int(bytes[11])
            guard ssidLen <= 32, bytes.count >= 12 + ssidLen else { return nil }
            let ssid: String? = ssidLen > 0
                ? String(bytes: bytes[12..<(12 + ssidLen)], encoding: .utf8)
                : nil
            let probe = ProbeRequest(mac: mac, rssi: rssi, ssid: ssid, channel: channel)
            return .probeReq(scanID: scanID, probe: probe)

        case .deauth:
            guard bytes.count >= 27 else { return nil }
            let event = DeauthEvent(
                source: BSSID(bytes: Array(bytes[3..<9])),
                target: BSSID(bytes: Array(bytes[9..<15])),
                bssid: BSSID(bytes: Array(bytes[15..<21])),
                reason: u16(bytes, 21),
                rssi: Int(Int8(bitPattern: bytes[23])),
                channel: Int(bytes[24]),
                count: Int(u16(bytes, 25))
            )
            return .deauth(scanID: scanID, event: event)

        case .ssidReveal:
            guard bytes.count >= 11 else { return nil }
            let bssid = BSSID(bytes: Array(bytes[3..<9]))
            let rssi = Int(Int8(bitPattern: bytes[9]))
            let ssidLen = Int(bytes[10])
            guard ssidLen > 0, ssidLen <= 32, bytes.count >= 11 + ssidLen else { return nil }
            let ssid = String(bytes: bytes[11..<(11 + ssidLen)], encoding: .utf8) ?? ""
            return .ssidReveal(scanID: scanID, bssid: bssid, rssi: rssi, ssid: ssid)

        case .traffic:
            guard bytes.count >= 15 else { return nil }
            let bssid = BSSID(bytes: Array(bytes[3..<9]))
            let totalBytes = UInt32(bytes[9])
                | UInt32(bytes[10]) << 8
                | UInt32(bytes[11]) << 16
                | UInt32(bytes[12]) << 24
            let packets = Int(u16(bytes, 13))
            return .traffic(scanID: scanID, bssid: bssid, bytes: totalBytes, packets: packets)

        case .vendorIE:
            guard bytes.count >= 11 else { return nil }
            let bssid = BSSID(bytes: Array(bytes[3..<9]))
            let dnLen = Int(bytes[9])
            guard dnLen <= 32, bytes.count >= 11 + dnLen else { return nil }
            let deviceName = dnLen > 0
                ? String(bytes: bytes[10..<(10 + dnLen)], encoding: .utf8) ?? ""
                : ""
            let mfOff = 10 + dnLen
            let mfLen = Int(bytes[mfOff])
            guard mfLen <= 32, bytes.count >= mfOff + 1 + mfLen else { return nil }
            let manufacturer = mfLen > 0
                ? String(bytes: bytes[(mfOff + 1)..<(mfOff + 1 + mfLen)], encoding: .utf8) ?? ""
                : ""
            guard !deviceName.isEmpty || !manufacturer.isEmpty else { return nil }
            return .vendorIE(scanID: scanID, bssid: bssid, deviceName: deviceName, manufacturer: manufacturer)

        case .battery:
            guard bytes.count >= 5 else { return nil }
            let level = Int(bytes[3])
            let charging = (bytes[4] & 0x01) != 0
            return .battery(scanID: scanID, level: min(level, 100), isCharging: charging)

        case .loraDevice:
            guard bytes.count >= 16 else { return nil }
            return .lora(scanID: scanID, device: LoRaDevice(
                freqKHz: u32(bytes, 3),
                spreadingFactor: Int(bytes[7]),
                rssi: i16(bytes, 8),
                snr: Int(Int8(bitPattern: bytes[10])),
                flags: bytes[11],
                id: u32(bytes, 12)
            ))

        case .loraScan:
            guard bytes.count >= 8 else { return nil }
            return .loraScan(
                scanID: scanID,
                started: bytes[3] == 1,
                count: Int(u16(bytes, 4)),
                durationMs: Int(u16(bytes, 6))
            )

        case .loraProgress:
            guard bytes.count >= 12 else { return nil }
            return .loraProgress(
                scanID: scanID,
                freqKHz: u32(bytes, 3),
                spreadingFactor: Int(bytes[7]),
                step: Int(u16(bytes, 8)),
                total: Int(u16(bytes, 10))
            )

        case .savedWifi, .savedBle, .savedLan, .savedLora:
            return decodeSaved(bytes)

        case .savedGps, .gps:
            return nil
        }
    }

    private static func decodeSavedGps(_ bytes: [UInt8]) -> Message? {
        guard bytes.count >= 18 else { return nil }
        return .savedPosition(
            captureID: u32(bytes, 1),
            latitude: e7(bytes, 9),
            longitude: e7(bytes, 13)
        )
    }

    private static func decodeGps(_ bytes: [UInt8]) -> Message? {
        guard bytes.count >= 11 else { return nil }
        let valid = bytes[10] & 0x01 != 0
        return .gps(
            valid: valid,
            latitude: valid ? e7(bytes, 1) : 0,
            longitude: valid ? e7(bytes, 5) : 0,
            satellites: Int(bytes[9])
        )
    }

    /// The firmware's encoding, used by the demo sensor and the tests.
    static func encode(_ message: Message) -> Data {
        var bytes: [UInt8] = []
        func put16(_ value: UInt16) {
            bytes.append(UInt8(value & 0xFF))
            bytes.append(UInt8(value >> 8))
        }
        func put32(_ value: UInt32) {
            bytes.append(UInt8(value & 0xFF))
            bytes.append(UInt8((value >> 8) & 0xFF))
            bytes.append(UInt8((value >> 16) & 0xFF))
            bytes.append(UInt8((value >> 24) & 0xFF))
        }

        switch message {
        case .scanStarted(let scanID):
            bytes.append(MessageType.scanStarted.rawValue)
            put16(scanID)

        case .scanFinished(let scanID, let count, let duration, let stationCount, let probeCount):
            bytes.append(MessageType.scanFinished.rawValue)
            put16(scanID)
            put16(UInt16(clamping: count))
            put16(UInt16(clamping: Int(duration / .milliseconds(1))))
            put16(UInt16(clamping: stationCount))
            put16(UInt16(clamping: probeCount))

        case .accessPoint(let scanID, let reading):
            bytes.append(MessageType.accessPoint.rawValue)
            put16(scanID)
            bytes.append(contentsOf: reading.bssid.bytes)
            bytes.append(UInt8(bitPattern: Int8(clamping: reading.rssi)))
            bytes.append(UInt8(clamping: reading.channel))
            bytes.append(reading.secondaryChannel.rawValue)
            bytes.append(reading.auth.rawValue)
            bytes.append(reading.pairwiseCipher.rawValue)
            bytes.append(reading.groupCipher.rawValue)
            bytes.append(reading.capabilities.rawValue)
            let country = Array((reading.countryCode ?? "").utf8.prefix(2))
            bytes.append(contentsOf: country.count == 2 ? country : [0, 0])
            let ssid = reading.ssidBytes.prefix(32)
            bytes.append(UInt8(ssid.count))
            bytes.append(contentsOf: ssid)

        case .station(let scanID, let client):
            bytes.append(MessageType.station.rawValue)
            put16(scanID)
            bytes.append(contentsOf: client.mac.bytes)
            bytes.append(contentsOf: (client.apBSSID ?? BSSID(bytes: [0, 0, 0, 0, 0, 0])).bytes)
            bytes.append(UInt8(bitPattern: Int8(clamping: client.rssi)))
            bytes.append(client.isAssociated ? 0x01 : 0x00)

        case .probeReq(let scanID, let probe):
            bytes.append(MessageType.probeReq.rawValue)
            put16(scanID)
            bytes.append(contentsOf: probe.mac.bytes)
            bytes.append(UInt8(bitPattern: Int8(clamping: probe.rssi)))
            bytes.append(UInt8(clamping: probe.channel))
            let ssidBytes = Array((probe.ssid ?? "").utf8.prefix(32))
            bytes.append(UInt8(ssidBytes.count))
            bytes.append(contentsOf: ssidBytes)

        case .deauth(let scanID, let event):
            bytes.append(MessageType.deauth.rawValue)
            put16(scanID)
            bytes.append(contentsOf: event.source.bytes)
            bytes.append(contentsOf: event.target.bytes)
            bytes.append(contentsOf: event.bssid.bytes)
            put16(event.reason)
            bytes.append(UInt8(bitPattern: Int8(clamping: event.rssi)))
            bytes.append(UInt8(clamping: event.channel))
            put16(UInt16(clamping: event.count))

        case .ssidReveal(let scanID, let bssid, let rssi, let ssid):
            bytes.append(MessageType.ssidReveal.rawValue)
            put16(scanID)
            bytes.append(contentsOf: bssid.bytes)
            bytes.append(UInt8(bitPattern: Int8(clamping: rssi)))
            let ssidBytes = Array(ssid.utf8.prefix(32))
            bytes.append(UInt8(ssidBytes.count))
            bytes.append(contentsOf: ssidBytes)

        case .traffic(let scanID, let bssid, let totalBytes, let packets):
            bytes.append(MessageType.traffic.rawValue)
            put16(scanID)
            bytes.append(contentsOf: bssid.bytes)
            bytes.append(UInt8(totalBytes & 0xFF))
            bytes.append(UInt8((totalBytes >> 8) & 0xFF))
            bytes.append(UInt8((totalBytes >> 16) & 0xFF))
            bytes.append(UInt8((totalBytes >> 24) & 0xFF))
            put16(UInt16(clamping: packets))

        case .vendorIE(let scanID, let bssid, let deviceName, let manufacturer):
            bytes.append(MessageType.vendorIE.rawValue)
            put16(scanID)
            bytes.append(contentsOf: bssid.bytes)
            let dnBytes = Array(deviceName.utf8.prefix(32))
            bytes.append(UInt8(dnBytes.count))
            bytes.append(contentsOf: dnBytes)
            let mfBytes = Array(manufacturer.utf8.prefix(32))
            bytes.append(UInt8(mfBytes.count))
            bytes.append(contentsOf: mfBytes)

        case .battery(let scanID, let level, let isCharging):
            bytes.append(MessageType.battery.rawValue)
            put16(scanID)
            bytes.append(UInt8(clamping: level))
            bytes.append(isCharging ? 0x01 : 0x00)

        case .lora(let scanID, let device):
            bytes.append(MessageType.loraDevice.rawValue)
            put16(scanID)
            put32(device.freqKHz)
            bytes.append(UInt8(clamping: device.spreadingFactor))
            put16(UInt16(bitPattern: Int16(clamping: device.rssi)))
            bytes.append(UInt8(bitPattern: Int8(clamping: device.snr)))
            bytes.append(device.flags)
            put32(device.id)

        case .loraScan(let scanID, let started, let count, let durationMs):
            bytes.append(MessageType.loraScan.rawValue)
            put16(scanID)
            bytes.append(started ? 1 : 2)
            put16(UInt16(clamping: count))
            put16(UInt16(clamping: durationMs))

        case .loraProgress(let scanID, let freqKHz, let spreadingFactor, let step, let total):
            bytes.append(MessageType.loraProgress.rawValue)
            put16(scanID)
            put32(freqKHz)
            bytes.append(UInt8(clamping: spreadingFactor))
            put16(UInt16(clamping: step))
            put16(UInt16(clamping: total))

        case .savedPosition(let captureID, let latitude, let longitude):
            bytes.append(MessageType.savedGps.rawValue)
            put32(captureID)
            put32(0)
            put32(UInt32(bitPattern: Int32(clamping: Int(latitude * 1e7))))
            put32(UInt32(bitPattern: Int32(clamping: Int(longitude * 1e7))))
            bytes.append(0)

        case .gps(let valid, let latitude, let longitude, let satellites):
            bytes.append(MessageType.gps.rawValue)
            put32(valid ? UInt32(bitPattern: Int32(clamping: Int(latitude * 1e7))) : 0)
            put32(valid ? UInt32(bitPattern: Int32(clamping: Int(longitude * 1e7))) : 0)
            bytes.append(UInt8(clamping: satellites))
            bytes.append(valid ? 1 : 0)

        case .saved:
            // The phone never sends a stored capture. The arm exists so the
            // switch stays exhaustive.
            break
        }
        return Data(bytes)
    }

    private static func decodeSaved(_ bytes: [UInt8]) -> Message? {
        guard bytes.count >= 13, let kind = SavedRecord.Kind(rawValue: kindOf(bytes[0])) else { return nil }
        let captureID = u32(bytes, 1)
        let unix = u32(bytes, 5)
        let index = Int(u16(bytes, 9))
        let total = Int(u16(bytes, 11))
        guard total > 0, index < total else { return nil }
        let title: String
        let detail: String
        let trailing: String
        switch bytes[0] {
        case MessageType.savedWifi.rawValue:
            guard bytes.count >= 23 else { return nil }
            let ssidLen = Int(bytes[22])
            guard ssidLen <= 32, bytes.count >= 23 + ssidLen else { return nil }
            let ssid = ssidLen > 0 ? String(bytes: bytes[23..<(23 + ssidLen)], encoding: .utf8) ?? "" : ""
            title = ssid.isEmpty ? "Hidden network" : ssid
            let mac = BSSID(bytes: Array(bytes[13..<19]))
            detail = "\(mac.description) · ch \(bytes[20]) · \(AuthMode(rawValue: bytes[21]).name)"
            trailing = "\(Int(Int8(bitPattern: bytes[19])))"
        case MessageType.savedBle.rawValue:
            guard bytes.count >= 22 else { return nil }
            let nameLen = Int(bytes[21])
            guard nameLen <= 20, bytes.count >= 22 + nameLen else { return nil }
            let name = nameLen > 0 ? String(bytes: bytes[22..<(22 + nameLen)], encoding: .utf8) ?? "" : ""
            title = name.isEmpty ? "Unknown device" : name
            detail = BSSID(bytes: Array(bytes[13..<19])).description
            trailing = "\(Int(Int8(bitPattern: bytes[20])))"
        case MessageType.savedLan.rawValue:
            guard bytes.count >= 18 else { return nil }
            let nameLen = Int(bytes[17])
            guard nameLen <= 24, bytes.count >= 18 + nameLen else { return nil }
            let ip = "\(bytes[13]).\(bytes[14]).\(bytes[15]).\(bytes[16])"
            let name = nameLen > 0 ? String(bytes: bytes[18..<(18 + nameLen)], encoding: .utf8) ?? "" : ""
            title = name.isEmpty ? ip : name
            detail = ip
            trailing = ""
        case MessageType.savedLora.rawValue:
            guard bytes.count >= 26 else { return nil }
            let device = LoRaDevice(
                freqKHz: u32(bytes, 13),
                spreadingFactor: Int(bytes[17]),
                rssi: i16(bytes, 18),
                snr: Int(Int8(bitPattern: bytes[20])),
                flags: bytes[21],
                id: u32(bytes, 22)
            )
            title = device.title
            detail = device.detail
            trailing = "\(device.rssi)"
        default:
            return nil
        }
        return .saved(SavedRecord(
            kind: kind, captureID: captureID, unix: unix, index: index, total: total,
            title: title, detail: detail, trailing: trailing
        ))
    }

    private static func kindOf(_ type: UInt8) -> UInt8 {
        switch type {
        case MessageType.savedWifi.rawValue: 1
        case MessageType.savedLan.rawValue: 2
        case MessageType.savedBle.rawValue: 3
        case MessageType.savedLora.rawValue: 4
        default: 0
        }
    }

    private static func i16(_ bytes: [UInt8], _ offset: Int) -> Int {
        let value = Int(u16(bytes, offset))
        return value >= 0x8000 ? value - 0x10000 : value
    }

    private static func e7(_ bytes: [UInt8], _ offset: Int) -> Double {
        Double(Int32(bitPattern: u32(bytes, offset))) / 1e7
    }

    private static func u16(_ bytes: [UInt8], _ offset: Int) -> UInt16 {
        UInt16(bytes[offset]) | UInt16(bytes[offset + 1]) << 8
    }

    private static func u32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        UInt32(bytes[offset])
            | UInt32(bytes[offset + 1]) << 8
            | UInt32(bytes[offset + 2]) << 16
            | UInt32(bytes[offset + 3]) << 24
    }
}

/// What the firmware reports in the Info characteristic.
/// `ota` is absent on firmware that cannot take an update from the app.
nonisolated struct SensorInfo: Equatable {
    var proto: Int
    var fw: String
    var chip: String
    var bands: [String]
    var interval: Int
    var ota: Bool
    var batteryLevel: Int?
    var isCharging: Bool?
    var lora: Bool
    var latitude: Double?
    var longitude: Double?
    var satellites: Int?

    init(proto: Int, fw: String, chip: String, bands: [String], interval: Int, ota: Bool = false,
         batteryLevel: Int? = nil, isCharging: Bool? = nil, lora: Bool = false,
         latitude: Double? = nil, longitude: Double? = nil, satellites: Int? = nil) {
        self.proto = proto
        self.fw = fw
        self.chip = chip
        self.bands = bands
        self.interval = interval
        self.ota = ota
        self.batteryLevel = batteryLevel
        self.isCharging = isCharging
        self.lora = lora
        self.latitude = latitude
        self.longitude = longitude
        self.satellites = satellites
    }
}

extension SensorInfo: Decodable {
    private enum CodingKeys: String, CodingKey {
        case proto, fw, chip, bands, interval, ota, bat, chg, lora, lat, lon, sats
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        proto = try container.decode(Int.self, forKey: .proto)
        fw = try container.decode(String.self, forKey: .fw)
        chip = try container.decode(String.self, forKey: .chip)
        bands = try container.decode([String].self, forKey: .bands)
        interval = try container.decode(Int.self, forKey: .interval)
        ota = try container.decodeIfPresent(Bool.self, forKey: .ota) ?? false
        batteryLevel = try container.decodeIfPresent(Int.self, forKey: .bat)
        isCharging = try container.decodeIfPresent(Bool.self, forKey: .chg)
        lora = try container.decodeIfPresent(Bool.self, forKey: .lora) ?? false
        if let lat = try container.decodeIfPresent(Int.self, forKey: .lat),
           let lon = try container.decodeIfPresent(Int.self, forKey: .lon) {
            latitude = Double(lat) / 1e7
            longitude = Double(lon) / 1e7
        } else {
            latitude = nil
            longitude = nil
        }
        satellites = try container.decodeIfPresent(Int.self, forKey: .sats)
    }
}
