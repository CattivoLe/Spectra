import Foundation

/// Company identifiers and GATT service names published by the Bluetooth SIG.
/// The tables are the assigned-numbers list collected by Nordic's
/// bluetooth-numbers-database: one record a line, hex identifier, tab, name.
nonisolated enum BLENumbers {
    static func company(id: UInt16) -> String? {
        companies[id]
    }

    static func serviceName(uuid: String) -> String? {
        services[canonical(uuid)]
    }

    static func characteristicName(uuid: String) -> String? {
        characteristics[canonical(uuid)]
    }

    /// A SIG-assigned 16- or 32-bit number rather than a vendor's own 128-bit UUID.
    static func isAssigned(_ uuid: String) -> Bool {
        canonical(uuid).count <= 8
    }

    /// Short codes stay short. A 128-bit value is grouped the way Bluetooth prints it.
    static func displayUUID(_ uuid: String) -> String {
        let canonical = canonical(uuid)
        guard canonical.count == 32 else { return canonical }
        let index = { canonical.index(canonical.startIndex, offsetBy: $0) }
        return "\(canonical[index(0)..<index(8)])-\(canonical[index(8)..<index(12)])-\(canonical[index(12)..<index(16)])-\(canonical[index(16)..<index(20)])-\(canonical[index(20)..<index(32)])"
    }

    /// 16-bit and 32-bit UUIDs collapsed to the short form, 128-bit ones
    /// kept as 32 hex digits. The Bluetooth base UUID is shortened too, so
    /// `180F` and `0000180F-0000-1000-8000-00805F9B34FB` share a name.
    static func canonical(_ uuid: String) -> String {
        let stripped = uuid.uppercased().filter { $0 != "-" }
        if stripped.count == 32,
           stripped.hasPrefix("0000"),
           stripped.hasSuffix("00001000800000805F9B34FB") {
            return String(stripped.dropFirst(4).prefix(4))
        }
        if stripped.count == 8, stripped.hasPrefix("0000") {
            return String(stripped.suffix(4))
        }
        return stripped
    }

    private static let companies: [UInt16: String] = load("ble-companies") { UInt16($0, radix: 16) }
    private static let services: [String: String] = load("ble-services") { canonical($0) }
    private static let characteristics: [String: String] = load("ble-characteristics") { canonical($0) }

    /// Reads one of the shared tables from the bundled Catalog folder. The
    /// files live in the Catalog folder.
    private static func load<ID: Hashable>(_ name: String, id: (String) -> ID?) -> [ID: String] {
        guard let url = Bundle.main.url(forResource: name, withExtension: "tsv", subdirectory: "Catalog"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return [:]
        }
        return parse(text, id: id)
    }

    private static func parse<ID: Hashable>(_ table: String, id: (String) -> ID?) -> [ID: String] {
        var result: [ID: String] = [:]
        result.reserveCapacity(table.count / 24)
        for line in table.split(separator: "\n") {
            guard let tab = line.firstIndex(of: "\t") else { continue }
            let key = String(line[..<tab])
            let name = String(line[line.index(after: tab)...])
            if let id = id(key), !name.isEmpty {
                result[id] = name
            }
        }
        return result
    }
}
