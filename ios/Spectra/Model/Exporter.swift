import Foundation

/// Turns saved history into shareable files. Pure string builders, so the
/// output can be checked by tests without any UI.
///
/// Three formats:
/// - `csv`    one row per record across every kind, with a `kind` column.
/// - `kml`    a placemark per located session, for Google Earth / maps.
/// - `wigle`  WiGLE's `WigleWifi` CSV, for uploading Wi-Fi finds to wigle.net.
enum Exporter {
    enum Format: String, CaseIterable, Identifiable {
        case csv, kml, wigle
        var id: String { rawValue }

        var fileExtension: String { self == .kml ? "kml" : "csv" }
        var label: String {
            switch self {
            case .csv: "CSV"
            case .kml: "KML (map)"
            case .wigle: "WiGLE CSV"
            }
        }
    }

    /// A single file ready to hand to a share sheet.
    struct File {
        var name: String
        var contents: String
    }

    static func file(_ format: Format, from entries: [HistoryEntry], now: Date = .now) -> File {
        let stamp = fileStamp.string(from: now)
        let body: String
        switch format {
        case .csv: body = csv(from: entries)
        case .kml: body = kml(from: entries)
        case .wigle: body = wigle(from: entries, now: now)
        }
        return File(name: "spectra-\(stamp).\(format.fileExtension)", contents: body)
    }

    // MARK: - Generic CSV

    /// One row per record, every kind in one file. A reader keeps only the
    /// columns it cares about by the `kind` field.
    static func csv(from entries: [HistoryEntry]) -> String {
        var rows = [
            "kind,saved_at,source,latitude,longitude,identifier,name,rssi,channel,security,vendor,notes"
        ]
        for entry in entries.sorted(by: { $0.savedAt < $1.savedAt }) {
            let when = iso.string(from: entry.savedAt)
            let lat = entry.latitude.map { String(format: "%.7f", $0) } ?? ""
            let lon = entry.longitude.map { String(format: "%.7f", $0) } ?? ""

            func row(_ fields: [String: String]) {
                let cells = [
                    fields["kind"] ?? "", when, entry.source, lat, lon,
                    fields["identifier"] ?? "", fields["name"] ?? "",
                    fields["rssi"] ?? "", fields["channel"] ?? "",
                    fields["security"] ?? "", fields["vendor"] ?? "", fields["notes"] ?? "",
                ]
                rows.append(cells.map(escape).joined(separator: ","))
            }

            for network in entry.networks {
                let ap = network.latest
                row([
                    "kind": "wifi",
                    "identifier": ap.bssid.description,
                    "name": network.revealedSSID ?? ap.ssid ?? "",
                    "rssi": String(ap.rssi),
                    "channel": String(ap.channel),
                    "security": ap.auth.name,
                    "vendor": ap.bssid.isLocallyAdministered ? "" : (OUIDatabase.lookup(bytes: ap.bssid.bytes) ?? ""),
                    "notes": network.traffic.map { "\($0.bytes) bytes, \($0.packets) pkts" } ?? "",
                ])
            }
            for probe in entry.probes {
                row([
                    "kind": "probe",
                    "identifier": probe.mac.description,
                    "name": probe.ssids.joined(separator: " | "),
                    "rssi": String(probe.rssi),
                    "channel": String(probe.channel),
                    "vendor": probe.manufacturer ?? "",
                ])
            }
            for event in entry.deauths {
                row([
                    "kind": "deauth",
                    "identifier": event.bssid.description,
                    "name": "",
                    "rssi": String(event.rssi),
                    "channel": String(event.channel),
                    "notes": "src \(event.source) → \(event.isBroadcast ? "broadcast" : event.target.description), \(event.reasonDescription), ×\(event.count)",
                ])
            }
            for host in entry.lan {
                row([
                    "kind": "lan",
                    "identifier": host.macAddress?.description ?? host.ipAddress,
                    "name": host.hostnames.first ?? "",
                    "notes": {
                        var parts: [String] = [host.ipAddress]
                        if !host.openPorts.isEmpty { parts.append("ports " + host.openPorts.map { String($0.number) }.joined(separator: "/")) }
                        if host.isGateway { parts.append("gateway") }
                        return parts.joined(separator: "; ")
                    }(),
                ])
            }
            for device in entry.bluetooth {
                row([
                    "kind": "bluetooth",
                    "identifier": device.id.uuidString,
                    "name": device.localName ?? device.peripheralName ?? "",
                    "rssi": String(device.rssi),
                    "vendor": device.companyName ?? "",
                ])
            }
            // Sensor summaries and LoRa only carry display lines.
            if entry.networks.isEmpty && entry.probes.isEmpty && entry.deauths.isEmpty
                && entry.lan.isEmpty && entry.bluetooth.isEmpty {
                for line in entry.lines {
                    row([
                        "kind": entry.kind.rawValue,
                        "name": line.title,
                        "notes": [line.detail, line.trailing].filter { !$0.isEmpty }.joined(separator: " · "),
                    ])
                }
            }
        }
        return rows.joined(separator: "\n") + "\n"
    }

    // MARK: - KML

    /// A placemark per located session. Unlocated sessions are left out, since
    /// KML has nowhere to put them.
    static func kml(from entries: [HistoryEntry]) -> String {
        var placemarks: [String] = []
        for entry in entries where entry.latitude != nil && entry.longitude != nil {
            let lat = entry.latitude!
            let lon = entry.longitude!
            let title = "\(entry.kind.title) · \(entry.itemCount)"
            let desc = kmlDescription(entry)
            placemarks.append("""
                <Placemark>
                  <name>\(xml(title))</name>
                  <description>\(xml(desc))</description>
                  <TimeStamp><when>\(iso.string(from: entry.savedAt))</when></TimeStamp>
                  <Point><coordinates>\(String(format: "%.7f,%.7f,0", lon, lat))</coordinates></Point>
                </Placemark>
                """)
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
            <Document>
              <name>Spectra</name>
            \(placemarks.joined(separator: "\n"))
            </Document>
            </kml>
            """
    }

    private static func kmlDescription(_ entry: HistoryEntry) -> String {
        var lines: [String] = ["\(entry.source) · \(entry.savedAt.formatted(date: .abbreviated, time: .shortened))"]
        for network in entry.networks.prefix(40) {
            lines.append("\(network.revealedSSID ?? network.latest.ssid ?? "(hidden)") — \(network.latest.bssid) \(network.latest.rssi) dBm \(network.latest.auth.name)")
        }
        for host in entry.lan.prefix(40) {
            lines.append("\(host.ipAddress) \(host.hostnames.first ?? "")")
        }
        for device in entry.bluetooth.prefix(40) {
            lines.append("\(device.localName ?? device.peripheralName ?? "(unnamed)") \(device.rssi) dBm")
        }
        if entry.networks.isEmpty && entry.lan.isEmpty && entry.bluetooth.isEmpty {
            for line in entry.lines.prefix(40) { lines.append(line.title) }
        }
        return lines.joined(separator: "\n")
    }

    // MARK: - WiGLE

    /// WiGLE's `WigleWifi-1.6` CSV. Only Wi-Fi networks that carry a fix are
    /// exported: WiGLE places each row on the map, and Bluetooth here has a
    /// CoreBluetooth UUID rather than the hardware address WiGLE needs.
    static func wigle(from entries: [HistoryEntry], now: Date = .now) -> String {
        let header = "WigleWifi-1.6,appRelease=\(appVersion),model=iPhone,release=\(osVersion),device=Spectra,display=,board=,brand=Apple"
        let columns = "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type"
        var rows = [header, columns]
        for entry in entries.sorted(by: { $0.savedAt < $1.savedAt }) {
            guard let lat = entry.latitude, let lon = entry.longitude else { continue }
            let seen = wigleStamp.string(from: entry.savedAt)
            for network in entry.networks {
                let ap = network.latest
                let cells = [
                    ap.bssid.description,
                    network.revealedSSID ?? ap.ssid ?? "",
                    wigleAuth(ap),
                    seen,
                    String(ap.channel),
                    String(ap.rssi),
                    String(format: "%.7f", lat),
                    String(format: "%.7f", lon),
                    "0", "0", "WIFI",
                ]
                rows.append(cells.map(escape).joined(separator: ","))
            }
        }
        return rows.joined(separator: "\n") + "\n"
    }

    /// WiGLE's bracketed capability string, e.g. `[WPA2-PSK-CCMP][ESS]`.
    private static func wigleAuth(_ ap: AccessPointReading) -> String {
        var tags: [String] = []
        let cipher = ap.pairwiseCipher == .none ? "" : "-" + wigleCipher(ap.pairwiseCipher)
        switch ap.auth.rawValue {
        case 0: break // open
        case 1: tags.append("WEP")
        case 2: tags.append("WPA-PSK\(cipher)")
        case 3: tags.append("WPA2-PSK\(cipher)")
        case 4: tags.append("WPA-PSK\(cipher)"); tags.append("WPA2-PSK\(cipher)")
        case 5: tags.append("WPA2-EAP\(cipher)")
        case 6, 11, 12: tags.append("WPA3-SAE\(cipher)")
        case 7: tags.append("WPA2-PSK\(cipher)"); tags.append("WPA3-SAE\(cipher)")
        case 9: tags.append("OWE")
        case 10, 14, 15: tags.append("WPA3-EAP\(cipher)")
        default: tags.append(ap.auth.name)
        }
        tags.append("ESS")
        return tags.map { "[\($0)]" }.joined()
    }

    private static func wigleCipher(_ cipher: Cipher) -> String {
        switch cipher.rawValue {
        case 3: "TKIP"
        case 4, 5: "CCMP"
        case 9: "GCMP"
        default: "CCMP"
        }
    }

    // MARK: - Helpers

    /// RFC 4180: wrap in quotes and double any inner quote when a cell has a
    /// comma, quote or newline.
    private static func escape(_ field: String) -> String {
        if field.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) {
            return "\"" + field.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return field
    }

    private static func xml(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
    }

    private static let iso: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    private static let wigleStamp: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()

    private static let fileStamp: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd-HHmm"
        return f
    }()

    private static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    private static var osVersion: String {
        ProcessInfo.processInfo.operatingSystemVersion.majorVersion.description
    }
}
