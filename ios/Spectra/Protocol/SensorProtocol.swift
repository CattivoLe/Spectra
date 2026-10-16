import CoreBluetooth
import Foundation

/// The BLE contract with the Cardputer firmware. docs/protocol.md is the
/// source of truth; keep the two in step.
nonisolated enum SensorProtocol {
    static let version = 4

    static let service = CBUUID(string: "53500001-8F2D-4B6A-9C1E-737065637472")
    static let info = CBUUID(string: "53500002-8F2D-4B6A-9C1E-737065637472")
    static let control = CBUUID(string: "53500003-8F2D-4B6A-9C1E-737065637472")
    static let results = CBUUID(string: "53500004-8F2D-4B6A-9C1E-737065637472")

    enum Command {
        case scanOnce
        case setInterval(seconds: Int)

        var data: Data {
            switch self {
            case .scanOnce:
                Data([0x01])
            case .setInterval(let seconds):
                Data([0x02, UInt8(clamping: seconds)])
            }
        }
    }

    enum Message: Equatable {
        case scanStarted(scanID: UInt16)
        case accessPoint(scanID: UInt16, reading: AccessPointReading)
        case scanFinished(scanID: UInt16, count: Int, duration: Duration, stationCount: Int, probeCount: Int)
        case station(scanID: UInt16, client: WiFiClient)
        case probeReq(scanID: UInt16, probe: ProbeRequest)
        case deauth(scanID: UInt16, event: DeauthEvent)
        case ssidReveal(scanID: UInt16, bssid: BSSID, rssi: Int, ssid: String)
    }

    enum MessageType: UInt8 {
        case scanStarted = 0x10
        case accessPoint = 0x11
        case scanFinished = 0x12
        case station = 0x13
        case probeReq = 0x14
        case deauth = 0x15
        case ssidReveal = 0x16
    }

    /// Decodes one Results notification, or returns nil for anything malformed
    /// or from a newer protocol the app does not know.
    static func decode(_ data: Data) -> Message? {
        let bytes = [UInt8](data)
        guard let first = bytes.first, let type = MessageType(rawValue: first), bytes.count >= 3 else {
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
        }
    }

    /// The firmware's encoding, used by the demo sensor and the tests.
    static func encode(_ message: Message) -> Data {
        var bytes: [UInt8] = []
        func put16(_ value: UInt16) {
            bytes.append(UInt8(value & 0xFF))
            bytes.append(UInt8(value >> 8))
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
        }
        return Data(bytes)
    }

    private static func u16(_ bytes: [UInt8], _ offset: Int) -> UInt16 {
        UInt16(bytes[offset]) | UInt16(bytes[offset + 1]) << 8
    }
}

/// What the firmware reports in the Info characteristic.
nonisolated struct SensorInfo: Decodable, Equatable {
    var proto: Int
    var fw: String
    var chip: String
    var bands: [String]
    var interval: Int
}
