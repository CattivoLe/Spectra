import Foundation

/// A stand-in for the Cardputer: produces the same wire messages from a fixed
/// set of made-up networks whose signal drifts, so the app can be tried and
/// developed without the hardware (and in the simulator, which has no
/// Bluetooth).
final class DemoSensor {
    var interval = 5 {
        didSet { schedule() }
    }

    private let emit: (Data) -> Void
    private var timer: Timer?
    private var scanID: UInt16 = 0
    private var networks = DemoSensor.makeNetworks()
    private let demoClients = DemoSensor.makeClients()

    init(emit: @escaping (Data) -> Void) {
        self.emit = emit
    }

    func scan() {
        scanID &+= 1
        emit(SensorProtocol.encode(.scanStarted(scanID: scanID)))
        var apSent = 0
        for index in networks.indices {
            networks[index].drift()
            guard let reading = networks[index].reading else { continue }
            emit(SensorProtocol.encode(.accessPoint(scanID: scanID, reading: reading)))
            apSent += 1
        }
        // Emit clients: each one has a chance of being seen this scan.
        var staSent = 0
        for client in demoClients {
            guard Double.random(in: 0...1) > 0.15 else { continue }
            var drifted = client
            drifted.rssi += Int.random(in: -4...4)
            emit(SensorProtocol.encode(.station(scanID: scanID, client: drifted)))
            staSent += 1
        }
        emit(SensorProtocol.encode(.scanFinished(
            scanID: scanID, count: apSent,
            duration: .milliseconds(4800),
            stationCount: staSent
        )))
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private func schedule() {
        stop()
        guard interval > 0 else { return }
        timer = Timer.scheduledTimer(withTimeInterval: TimeInterval(interval), repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.scan() }
        }
    }

    private struct DemoNetwork {
        var base: AccessPointReading
        var centre: Int
        var current: Double
        /// Chance of being missed in a scan, as weak networks often are.
        var flakiness: Double

        var reading: AccessPointReading? {
            guard Double.random(in: 0...1) >= flakiness else { return nil }
            var reading = base
            reading.rssi = Int(current.rounded())
            return reading
        }

        mutating func drift() {
            current += Double.random(in: -3...3)
            current += (Double(centre) - current) * 0.3
        }
    }

    private static func makeNetworks() -> [DemoNetwork] {
        let specs: [(String, Int, SecondaryChannel, AuthMode, Capabilities, Int)] = [
            ("Home", 6, .none, .wpa2WPA3PSK, [.b, .g, .n, .ax], -42),
            ("Home-Guest", 6, .none, .wpa2PSK, [.b, .g, .n, .ax], -44),
            ("Keenetic-4120", 1, .above, .wpa2PSK, [.b, .g, .n], -61),
            ("TP-Link_2.4GHz_7F21", 11, .none, .wpaWPA2PSK, [.b, .g, .n, .wps], -67),
            ("MGTS_GPON_8812", 3, .none, .wpa2PSK, [.b, .g, .n], -72),
            ("Coffee Corner", 9, .below, .open, [.b, .g, .n], -76),
            ("DIRECT-5B-HP OfficeJet", 6, .none, .wpa2PSK, [.g, .n], -79),
            ("", 11, .none, .wpa2PSK, [.b, .g, .n], -81),
            ("iPhone 15", 1, .none, .wpa3PSK, [.b, .g, .n, .ax], -83),
            ("Rostelecom_A2", 13, .none, .wep, [.b, .g], -88),
        ]
        return specs.enumerated().map { index, spec in
            let (ssid, channel, secondary, auth, caps, rssi) = spec
            let isHotspot = ssid.hasPrefix("iPhone") || ssid.hasPrefix("DIRECT")
            let bssid = BSSID(bytes: [
                isHotspot ? 0x7A : 0x50, 0xC7, 0xBF, 0x1E, UInt8(index * 17), UInt8(0xA0 + index),
            ])
            let cipher: Cipher = auth == .open ? .none : auth == .wep ? Cipher(rawValue: 2) : .ccmp
            let base = AccessPointReading(
                bssid: bssid,
                rssi: rssi,
                channel: channel,
                secondaryChannel: secondary,
                auth: auth,
                pairwiseCipher: cipher,
                groupCipher: auth == .wpaWPA2PSK ? .tkip : cipher,
                capabilities: caps,
                countryCode: isHotspot ? nil : "RU",
                ssidBytes: Data(ssid.utf8)
            )
            let flakiness = rssi < -80 ? 0.35 : rssi < -70 ? 0.1 : 0
            return DemoNetwork(base: base, centre: rssi, current: Double(rssi), flakiness: flakiness)
        }
    }

    /// Made-up client devices associated with the demo APs, plus a few probing ones.
    private static func makeClients() -> [WiFiClient] {
        // BSSIDs of the demo APs, for reference.
        let homeBSSID = BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x00, 0xA0])
        let guestBSSID = BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x11, 0xA1])
        let keeneticBSSID = BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x22, 0xA2])
        let tplinkBSSID = BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x33, 0xA3])
        let coffeeBSSID = BSSID(bytes: [0x50, 0xC7, 0xBF, 0x1E, 0x55, 0xA5])

        return [
            // Connected to Home
            WiFiClient(mac: BSSID(bytes: [0xF0, 0x18, 0x98, 0x01, 0x02, 0x03]), apBSSID: homeBSSID, rssi: -38, isAssociated: true),  // Apple device
            WiFiClient(mac: BSSID(bytes: [0x3C, 0x22, 0xFB, 0x04, 0x05, 0x06]), apBSSID: homeBSSID, rssi: -51, isAssociated: true),  // Apple device
            WiFiClient(mac: BSSID(bytes: [0x48, 0xD6, 0xD5, 0x07, 0x08, 0x09]), apBSSID: homeBSSID, rssi: -62, isAssociated: true),  // Google
            WiFiClient(mac: BSSID(bytes: [0x08, 0x08, 0xC2, 0x0A, 0x0B, 0x0C]), apBSSID: homeBSSID, rssi: -55, isAssociated: true),  // Samsung
            // Connected to Home-Guest
            WiFiClient(mac: BSSID(bytes: [0x7A, 0x12, 0x34, 0x0D, 0x0E, 0x0F]), apBSSID: guestBSSID, rssi: -48, isAssociated: true),  // randomised
            // Connected to Keenetic
            WiFiClient(mac: BSSID(bytes: [0x30, 0xAE, 0xA4, 0x10, 0x11, 0x12]), apBSSID: keeneticBSSID, rssi: -65, isAssociated: true),  // Espressif
            WiFiClient(mac: BSSID(bytes: [0xB8, 0x27, 0xEB, 0x13, 0x14, 0x15]), apBSSID: keeneticBSSID, rssi: -71, isAssociated: true),  // Raspberry Pi
            // Connected to TP-Link
            WiFiClient(mac: BSSID(bytes: [0x68, 0x54, 0xFD, 0x16, 0x17, 0x18]), apBSSID: tplinkBSSID, rssi: -70, isAssociated: true),  // Amazon
            // Connected to Coffee Corner
            WiFiClient(mac: BSSID(bytes: [0x7E, 0xAA, 0xBB, 0x19, 0x1A, 0x1B]), apBSSID: coffeeBSSID, rssi: -78, isAssociated: true),  // randomised
            WiFiClient(mac: BSSID(bytes: [0x7E, 0xCC, 0xDD, 0x1C, 0x1D, 0x1E]), apBSSID: coffeeBSSID, rssi: -80, isAssociated: true),  // randomised
            // Probing (not associated with any AP)
            WiFiClient(mac: BSSID(bytes: [0x7A, 0x55, 0x66, 0x1F, 0x20, 0x21]), apBSSID: nil, rssi: -74, isAssociated: false),
            WiFiClient(mac: BSSID(bytes: [0x7E, 0x77, 0x88, 0x22, 0x23, 0x24]), apBSSID: nil, rssi: -82, isAssociated: false),
        ]
    }
}
