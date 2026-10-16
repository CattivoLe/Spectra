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

    init(emit: @escaping (Data) -> Void) {
        self.emit = emit
    }

    func scan() {
        scanID &+= 1
        emit(SensorProtocol.encode(.scanStarted(scanID: scanID)))
        var sent = 0
        for index in networks.indices {
            networks[index].drift()
            guard let reading = networks[index].reading else { continue }
            emit(SensorProtocol.encode(.accessPoint(scanID: scanID, reading: reading)))
            sent += 1
        }
        emit(SensorProtocol.encode(.scanFinished(scanID: scanID, count: sent, duration: .milliseconds(1640))))
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
}
