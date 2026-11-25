import Foundation

/// A named tally, e.g. a protocol or an application and how many packets carried it.
nonisolated struct NamedCount: Sendable, Identifiable {
    var name: String
    var count: Int
    var id: String { name }
}

/// One network endpoint and how much it sent or received.
nonisolated struct Talker: Sendable, Identifiable {
    var endpoint: String
    var packets: Int
    var bytes: Int
    var id: String { endpoint }
}

/// A pair of endpoints that exchanged packets.
nonisolated struct Conversation: Sendable, Identifiable {
    var a: String
    var b: String
    var packets: Int
    var bytes: Int
    var id: String { a + " | " + b }
}

/// One row for the packet list.
nonisolated struct PacketRow: Sendable, Identifiable {
    var id: Int
    var timestamp: Date?
    var length: Int
    var transport: String
    var source: String
    var destination: String
    var info: String
    /// The application guessed from the ports, when there is one.
    var app: String? = nil
    /// The addresses the summary counts hosts by, for the host filter.
    var netSource: String? = nil
    var netDestination: String? = nil
}

/// What the packet list is narrowed to. Mirrors the Android `PacketFilter`.
nonisolated struct PacketFilter: Equatable, Sendable {
    /// Text anywhere in the packet's line, addresses or ports.
    var query = ""
    /// Protocols to keep; empty keeps all.
    var transports: Set<String> = []
    var app: String?
    /// One host, or the two ends of a conversation.
    var endpoints: [String] = []

    var isActive: Bool {
        !query.trimmingCharacters(in: .whitespaces).isEmpty || !transports.isEmpty || app != nil || !endpoints.isEmpty
    }

    func matches(_ row: PacketRow) -> Bool {
        if !transports.isEmpty, !transports.contains(row.transport) { return false }
        if let app, row.app != app { return false }
        switch endpoints.count {
        case 0: break
        case 1:
            guard row.netSource == endpoints[0] || row.netDestination == endpoints[0] else { return false }
        default:
            guard Set([row.netSource, row.netDestination].compactMap { $0 }) == Set(endpoints.prefix(2)) else { return false }
        }
        let needle = query.trimmingCharacters(in: .whitespaces)
        if needle.isEmpty { return true }
        return [row.info, row.source, row.destination, row.transport, row.app ?? ""]
            .contains { $0.localizedCaseInsensitiveContains(needle) }
    }

    mutating func toggle(transport: String) {
        if transports.contains(transport) { transports.remove(transport) } else { transports.insert(transport) }
    }
}

nonisolated struct CaptureSummary: Sendable {
    var packetCount: Int
    var capturedBytes: Int
    var wireBytes: Int
    var start: Date?
    var end: Date?
    var linkTypes: [String]
    var protocols: [NamedCount]
    var apps: [NamedCount]
    var talkers: [Talker]
    var conversations: [Conversation]
    /// How many frames of each WPA 4-way handshake message (1…4) were seen.
    /// Empty when the capture carried no EAPOL key exchange.
    var eapolMessages: [Int: Int] = [:]

    var duration: TimeInterval? {
        guard let start, let end, end >= start else { return nil }
        return end.timeIntervalSince(start)
    }

    /// True when the capture holds Wi‑Fi frames, so the handshake section is worth
    /// showing even when no message was heard.
    var hasRadio: Bool { linkTypes.contains { $0.hasPrefix("802.11") } }
}

nonisolated struct CaptureAnalysis: Sendable {
    var format: String
    var summary: CaptureSummary
    var rows: [PacketRow]
    /// True when there were more packets than the row cap, so the list is partial.
    var rowsTruncated: Bool
    /// hashcat 22000 lines for any WPA handshake or PMKID found, ready to export.
    var hc22000: [String] = []

    /// How many rows the list shows at most, filtered or not.
    static let shownRows = 2000
}

/// Turns raw file bytes into a summary and a (capped) packet list. Pure and
/// `nonisolated`, so it runs off the main actor on a background task.
nonisolated enum PcapAnalyzer {
    /// Rows are kept for far more packets than the list shows, so a filter
    /// finds matches anywhere in a large capture, not just in its start.
    static func analyze(_ data: Data, maxRows: Int = 50_000) throws -> CaptureAnalysis {
        let file = try PcapParser.parse(data)

        var protocolCounts: [Transport: Int] = [:]
        var appCounts: [String: Int] = [:]
        var talkerPackets: [String: Int] = [:]
        var talkerBytes: [String: Int] = [:]
        var conversationPackets: [String: Int] = [:]
        var conversationBytes: [String: Int] = [:]
        var conversationPair: [String: (String, String)] = [:]
        var start: Date?
        var end: Date?
        var wireBytes = 0
        var capturedBytes = 0
        var eapolCounts: [Int: Int] = [:]
        var rows: [PacketRow] = []

        for (index, packet) in file.packets.enumerated() {
            let info = PacketDissector.dissect(linkType: packet.linkType, packet.data)
            protocolCounts[info.transport, default: 0] += 1
            if let app = info.app { appCounts[app, default: 0] += 1 }
            if let message = info.eapolMessage { eapolCounts[message, default: 0] += 1 }
            wireBytes += packet.originalLength
            capturedBytes += packet.data.count

            if let time = packet.timestamp {
                if start == nil || time < start! { start = time }
                if end == nil || time > end! { end = time }
            }

            let size = packet.originalLength
            if let source = info.netSource {
                talkerPackets[source, default: 0] += 1
                talkerBytes[source, default: 0] += size
            }
            if let destination = info.netDestination {
                talkerPackets[destination, default: 0] += 1
                talkerBytes[destination, default: 0] += size
            }
            if let source = info.netSource, let destination = info.netDestination, source != destination {
                let ends = [source, destination].sorted()
                let key = ends.joined(separator: "|")
                conversationPackets[key, default: 0] += 1
                conversationBytes[key, default: 0] += size
                if conversationPair[key] == nil { conversationPair[key] = (ends[0], ends[1]) }
            }

            if rows.count < maxRows {
                rows.append(PacketRow(
                    id: index, timestamp: packet.timestamp, length: size,
                    transport: info.transport.rawValue, source: info.source,
                    destination: info.destination, info: info.info,
                    app: info.app, netSource: info.netSource, netDestination: info.netDestination
                ))
            }
        }

        let protocols = protocolCounts
            .map { NamedCount(name: $0.key.rawValue, count: $0.value) }
            .sorted { $0.count > $1.count }
        let apps = appCounts
            .map { NamedCount(name: $0.key, count: $0.value) }
            .sorted { $0.count > $1.count }
        let talkers = talkerPackets.keys
            .map { Talker(endpoint: $0, packets: talkerPackets[$0] ?? 0, bytes: talkerBytes[$0] ?? 0) }
            .sorted { $0.bytes > $1.bytes }
            .prefix(12)
        let conversations = conversationPair
            .map { key, pair in
                Conversation(a: pair.0, b: pair.1,
                             packets: conversationPackets[key] ?? 0,
                             bytes: conversationBytes[key] ?? 0)
            }
            .sorted { $0.bytes > $1.bytes }
            .prefix(12)

        let summary = CaptureSummary(
            packetCount: file.packets.count,
            capturedBytes: capturedBytes,
            wireBytes: wireBytes,
            start: start,
            end: end,
            linkTypes: Set(file.packets.map { $0.linkType.name }).sorted(),
            protocols: protocols,
            apps: apps,
            talkers: Array(talkers),
            conversations: Array(conversations),
            eapolMessages: eapolCounts
        )
        return CaptureAnalysis(
            format: file.format.rawValue,
            summary: summary,
            rows: rows,
            rowsTruncated: file.packets.count > maxRows,
            hc22000: Hc22000.lines(from: file)
        )
    }
}
