import Foundation

/// A decoded traffic capture written up for the assistant: what the Decode pcap screen shows, as compact text.
/// Used both for a capture the user already has (the question's attachment) and for one the model takes itself
/// (`capture_pcap`), so the two read the same.
nonisolated enum CaptureDigest {
    /// Upper bound in characters: a capture shares the question with other app data and web results.
    static let budget = 2400

    /// `describe` turns an address into a name the phone knows (a Wi‑Fi network, a maker), when it can.
    static func text(_ analysis: CaptureAnalysis, name: String, origin: String,
                     describe: (String) -> String? = { _ in nil }) -> String {
        let summary = analysis.summary
        func endpoint(_ address: String) -> String {
            describe(address).map { "\(address) (\(AssistantDigest.clean($0)))" } ?? address
        }

        var head = "Traffic capture \"\(AssistantDigest.clean(name, limit: 60))\", \(origin): \(summary.packetCount) packets, \(summary.capturedBytes) bytes"
        if let duration = summary.duration { head += String(format: ", over %.1f s", duration) }
        var lines = [head + "."]
        if !summary.linkTypes.isEmpty { lines.append("Link layer: \(summary.linkTypes.joined(separator: ", ")).") }

        let radio = summary.linkTypes.contains { $0.hasPrefix("802.11") }
        if radio {
            lines.append("These are Wi‑Fi frames heard over the air. Data frames of a protected network are encrypted, so a capture like "
                         + "this shows which devices talk to which and how much, and the management frames (beacons, probes, "
                         + "authentication, deauthentication) — not websites, passwords or messages.")
        }
        if summary.packetCount == 0 {
            lines.append("It holds no packets: nothing was heard, or the capture never started.")
        }

        if !summary.protocols.isEmpty {
            lines.append("Protocols: " + summary.protocols.prefix(8).map { "\($0.name) \($0.count)" }.joined(separator: ", ") + ".")
        }
        if !summary.apps.isEmpty {
            let title = radio ? "Frame types and applications" : "Applications (guessed from ports)"
            lines.append("\(title): " + summary.apps.prefix(10).map { "\($0.name) \($0.count)" }.joined(separator: ", ") + ".")
        }
        if !summary.talkers.isEmpty {
            lines.append("Busiest addresses: " + summary.talkers.prefix(6)
                .map { "\(endpoint($0.endpoint)) \($0.packets) pkt/\($0.bytes) B" }.joined(separator: "; ") + ".")
        }
        if !summary.conversations.isEmpty {
            lines.append("Busiest pairs: " + summary.conversations.prefix(5)
                .map { "\(endpoint($0.a)) ↔ \(endpoint($0.b)) \($0.packets) pkt" }.joined(separator: "; ") + ".")
        }
        if !summary.eapolMessages.isEmpty {
            let present = (1...4).filter { (summary.eapolMessages[$0] ?? 0) > 0 }
            let list = present.map { "M\($0)" }.joined(separator: ", ")
            let complete = present == [1, 2, 3, 4]
            lines.append("WPA 4‑way handshake messages seen: \(list)"
                         + (complete ? " — a complete handshake was captured." : " (incomplete handshake)."))
        } else if radio {
            lines.append("No EAPOL handshake frames (M1–M4) were seen.")
        }

        // The first packets, so the model can describe what actually happened, not only the totals.
        let first = analysis.rows.prefix(12).map { row in
            "- \(row.transport): \(AssistantDigest.clean(row.info, limit: 90))"
        }
        if !first.isEmpty { lines.append("First packets:\n" + first.joined(separator: "\n")) }
        if analysis.rowsTruncated { lines.append("(only part of a very large capture was counted)") }

        var text = lines.joined(separator: "\n")
        if text.count > budget { text = String(text.prefix(budget)) + "\n…(cut)" }
        return text
    }
}
