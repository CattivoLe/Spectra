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

    var duration: TimeInterval? {
        guard let start, let end, end >= start else { return nil }
        return end.timeIntervalSince(start)
    }
}

nonisolated struct CaptureAnalysis: Sendable {
    var format: String
    var summary: CaptureSummary
    var rows: [PacketRow]
    /// True when there were more packets than the row cap, so the list is partial.
    var rowsTruncated: Bool
}

/// Turns raw file bytes into a summary and a (capped) packet list. Pure and
/// `nonisolated`, so it runs off the main actor on a background task.
nonisolated enum PcapAnalyzer {
    static func analyze(_ data: Data, maxRows: Int = 2000) throws -> CaptureAnalysis {
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
        var rows: [PacketRow] = []

        for (index, packet) in file.packets.enumerated() {
            let info = PacketDissector.dissect(linkType: packet.linkType, packet.data)
            protocolCounts[info.transport, default: 0] += 1
            if let app = info.app { appCounts[app, default: 0] += 1 }
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
                    destination: info.destination, info: info.info
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
            conversations: Array(conversations)
        )
        return CaptureAnalysis(
            format: file.format.rawValue,
            summary: summary,
            rows: rows,
            rowsTruncated: file.packets.count > maxRows
        )
    }
}
