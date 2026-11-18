import Foundation

/// A device's GATT services and characteristics written up for the assistant. Used for a read the model makes itself
/// (`connect_bluetooth`) and for the last read made on the Bluetooth device screen, so the two read the same. Every
/// name and value is flattened with `AssistantDigest.clean`: this is an unknown device's own words.
enum GATTDigest {
    /// Upper bound in characters: it shares the question with the Bluetooth list.
    static let budget = 1800

    static func text(_ read: GATTRead, intro: String) -> String {
        let name = AssistantDigest.clean(read.name)
        var lines = ["\(intro) \"\(name)\"\(read.rssi.map { " (\($0) dBm)" } ?? ""), GATT services and characteristics:"]
        for service in read.services.prefix(12) {
            let title = service.name.map { "\(AssistantDigest.clean($0)) (\(service.uuid))" } ?? service.uuid
            lines.append("- Service \(title)")
            for characteristic in service.characteristics.prefix(10) {
                var parts = [AssistantDigest.clean(BLENumbers.characteristicName(uuid: characteristic.uuid) ?? characteristic.uuid)]
                if !characteristic.properties.isEmpty { parts.append(characteristic.properties) }
                if let data = characteristic.value, !data.isEmpty {
                    if let decoded = GATTValue.decode(uuid: characteristic.uuid, bytes: data) {
                        parts.append("= \(AssistantDigest.clean(decoded, limit: 80))")
                    } else {
                        parts.append("= 0x" + data.prefix(16).map { String(format: "%02x", $0) }.joined())
                    }
                }
                lines.append("  · " + parts.joined(separator: " · "))
            }
            if service.characteristics.isEmpty { lines.append("  · (no characteristics readable without pairing)") }
        }
        if read.services.count > 12 { lines.append("- …and \(read.services.count - 12) more services.") }
        lines.append("Write/Notify only say what the device accepts; nothing was written to it.")
        var text = lines.joined(separator: "\n")
        if text.count > budget { text = String(text.prefix(budget)) + "\n…(cut)" }
        return text
    }
}
