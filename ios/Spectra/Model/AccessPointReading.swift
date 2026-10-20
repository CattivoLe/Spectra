import Foundation

/// One sighting of an access point, exactly as the sensor reported it.
nonisolated struct AccessPointReading: Equatable, Codable, Sendable {
    var bssid: BSSID
    var rssi: Int
    var channel: Int
    var secondaryChannel: SecondaryChannel
    var auth: AuthMode
    var pairwiseCipher: Cipher
    var groupCipher: Cipher
    var capabilities: Capabilities
    var countryCode: String?
    var ssidBytes: Data

    /// The network name, or nil for a hidden network. Names are raw bytes on
    /// the air; the rare one that is not UTF-8 is shown with replacement marks.
    var ssid: String? {
        guard !ssidBytes.isEmpty, ssidBytes.contains(where: { $0 != 0 }) else { return nil }
        return String(decoding: ssidBytes, as: UTF8.self)
    }

    var band: Band { channel > 14 ? .fiveGHz : .twoPointFourGHz }

    /// Centre frequency of the primary channel.
    var frequencyMHz: Int {
        switch channel {
        case 14: 2484
        case 1...13: 2407 + channel * 5
        default: 5000 + channel * 5
        }
    }

    var channelWidthMHz: Int { secondaryChannel == .none ? 20 : 40 }

    /// The channels this network's signal covers, for drawing overlap.
    var occupiedChannels: ClosedRange<Double> {
        let centre: Double = switch secondaryChannel {
        case .none: Double(channel)
        case .above: Double(channel) + 2
        case .below: Double(channel) - 2
        }
        let halfWidth = secondaryChannel == .none ? 2.0 : 4.0
        return (centre - halfWidth)...(centre + halfWidth)
    }
}

nonisolated struct BSSID: Hashable, Codable, Sendable, CustomStringConvertible {
    var bytes: [UInt8]

    init(bytes: [UInt8]) {
        self.bytes = bytes
    }

    /// Parses "aa:bb:cc:dd:ee:ff". iOS drops leading zeros ("0:1a:…"), so each part may be one digit.
    init?(string: String) {
        let parts = string.split(whereSeparator: { $0 == ":" || $0 == "-" })
        let bytes = parts.compactMap { UInt8($0, radix: 16) }
        guard parts.count == 6, bytes.count == 6 else { return nil }
        self.bytes = bytes
    }

    var description: String {
        bytes.map { String(format: "%02X", $0) }.joined(separator: ":")
    }

    /// Set when the address was made up by the device rather than assigned by
    /// the maker: common for guest networks and phone hotspots.
    var isLocallyAdministered: Bool { (bytes.first ?? 0) & 0x02 != 0 }

    /// The maker's prefix (OUI), meaningful only for universal addresses.
    var oui: String { bytes.prefix(3).map { String(format: "%02X", $0) }.joined(separator: ":") }
}

nonisolated enum Band: String, Sendable {
    case twoPointFourGHz = "2.4 GHz"
    case fiveGHz = "5 GHz"

    var gigahertz: Double { self == .fiveGHz ? 5 : 2.4 }
}

nonisolated enum SecondaryChannel: UInt8, Codable, Sendable {
    case none = 0
    case above = 1
    case below = 2
}

/// ESP-IDF `wifi_auth_mode_t`.
nonisolated struct AuthMode: RawRepresentable, Hashable, Codable, Sendable {
    var rawValue: UInt8

    static let open = AuthMode(rawValue: 0)
    static let wep = AuthMode(rawValue: 1)
    static let wpaPSK = AuthMode(rawValue: 2)
    static let wpa2PSK = AuthMode(rawValue: 3)
    static let wpaWPA2PSK = AuthMode(rawValue: 4)
    static let wpa2Enterprise = AuthMode(rawValue: 5)
    static let wpa3PSK = AuthMode(rawValue: 6)
    static let wpa2WPA3PSK = AuthMode(rawValue: 7)
    static let owe = AuthMode(rawValue: 9)

    var name: String {
        switch rawValue {
        case 0: String(localized: "Open")
        case 1: "WEP"
        case 2: "WPA"
        case 3: "WPA2"
        case 4: "WPA/WPA2"
        case 5: "WPA2 Enterprise"
        case 6: "WPA3"
        case 7: "WPA2/WPA3"
        case 8: "WAPI"
        case 9: "OWE"
        case 10: "WPA3 Enterprise 192-bit"
        case 11: "WPA3 (extended key)"
        case 12: "WPA3 (extended key, mixed)"
        case 13: "DPP"
        case 14: "WPA3 Enterprise"
        case 15: "WPA2/WPA3 Enterprise"
        default: String(localized: "Other")
        }
    }

    enum Strength: Int, Comparable {
        case none, weak, fair, strong

        static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }
    }

    /// How well the mode protects traffic from other people in range.
    var strength: Strength {
        switch rawValue {
        case 0: .none
        case 1, 2: .weak
        case 3, 4, 5, 8, 9: .fair
        default: .strong
        }
    }

    /// How the phone itself joins this network with `NEHotspotConfiguration`.
    enum JoinMethod: Equatable {
        /// No passphrase: open, OWE (enhanced open).
        case open
        /// A WPA/WPA2/WPA3 pre-shared key.
        case psk
        /// A WEP key. Obsolete, but still joinable.
        case wep
        /// 802.1X/enterprise and anything else iOS will not take from a
        /// passphrase alone; it has to be joined from Settings.
        case unsupported
    }

    var joinMethod: JoinMethod {
        switch rawValue {
        case 0, 9: .open           // open, OWE
        case 1: .wep
        case 2, 3, 4, 6, 7: .psk   // WPA, WPA2, WPA/WPA2, WPA3, WPA2/WPA3 (PSK/SAE)
        default: .unsupported      // enterprise, DPP, WAPI, unknown
        }
    }
}

/// ESP-IDF `wifi_cipher_type_t`.
nonisolated struct Cipher: RawRepresentable, Hashable, Codable, Sendable {
    var rawValue: UInt8

    static let none = Cipher(rawValue: 0)
    static let tkip = Cipher(rawValue: 3)
    static let ccmp = Cipher(rawValue: 4)
    static let gcmp256 = Cipher(rawValue: 9)

    var name: String {
        switch rawValue {
        case 0: String(localized: "None")
        case 1: "WEP-40"
        case 2: "WEP-104"
        case 3: "TKIP"
        case 4: "CCMP (AES)"
        case 5: "TKIP + CCMP"
        case 6: "AES-CMAC-128"
        case 7: "SMS4"
        case 8: "GCMP"
        case 9: "GCMP-256"
        case 10: "AES-GMAC-128"
        case 11: "AES-GMAC-256"
        default: String(localized: "Unknown")
        }
    }
}

nonisolated struct Capabilities: OptionSet, Hashable, Codable, Sendable {
    var rawValue: UInt8

    static let b = Capabilities(rawValue: 1 << 0)
    static let g = Capabilities(rawValue: 1 << 1)
    static let n = Capabilities(rawValue: 1 << 2)
    static let longRange = Capabilities(rawValue: 1 << 3)
    static let ax = Capabilities(rawValue: 1 << 4)
    static let wps = Capabilities(rawValue: 1 << 5)
    static let ftmResponder = Capabilities(rawValue: 1 << 6)

    /// The newest Wi-Fi generation the access point offers. Non-breaking
    /// hyphen and space, so a wrapped line never splits "Wi-Fi 6".
    var generation: String {
        if contains(.ax) { return "Wi\u{2011}Fi\u{00A0}6" }
        if contains(.n) { return "Wi\u{2011}Fi\u{00A0}4" }
        if contains(.g) { return "802.11g" }
        if contains(.b) { return "802.11b" }
        return "—"
    }

    var standards: String {
        var parts: [String] = []
        if contains(.b) { parts.append("b") }
        if contains(.g) { parts.append("g") }
        if contains(.n) { parts.append("n") }
        if contains(.ax) { parts.append("ax") }
        return parts.isEmpty ? "—" : "802.11" + parts.joined(separator: "/")
    }
}
