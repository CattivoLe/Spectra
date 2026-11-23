import Foundation

/// What one client sent and received in one sweep, counted by the sensor while it listened on the client's channel
/// (`0x25`, sensor firmware 0.11 and later). `Stream.h` in the firmware has the same record and the same rule.
nonisolated struct ClientTraffic: Equatable, Codable, Sendable {
    var mac: BSSID
    var bssid: BSSID
    var channel: Int
    /// The strongest frame the client sent.
    var rssi: Int
    /// How long the sensor really listened on that channel.
    var listenMs: Int
    /// Client to access point.
    var upBytes: UInt32
    var upFrames: Int
    /// Access point to client, from the first frame the client sent.
    var downBytes: UInt32
    var downFrames: Int
    /// 100 ms slices of the listening time in which the client sent data.
    var busySlots: Int

    var totalSlots: Int { min((listenMs + 99) / 100, 32) }

    var upBytesPerSecond: Int { listenMs > 0 ? Int(UInt64(upBytes) * 1000 / UInt64(listenMs)) : 0 }
    var downBytesPerSecond: Int { listenMs > 0 ? Int(UInt64(downBytes) * 1000 / UInt64(listenMs)) : 0 }

    /// Sends like a camera or a microphone: steadily, in large frames, and far more than it receives.
    var looksLikeStream: Bool {
        guard upFrames > 0, listenMs > 0 else { return false }
        return upBytesPerSecond >= Threats.clientMinBytesPerSecond
            && Int(upBytes) >= Threats.clientMinFrameBytes * upFrames
            && Int(upBytes) / Threats.clientUpOverDown >= Int(downBytes)
            && busySlots * 100 >= Threats.clientBusyPercent * totalSlots
    }

    var maker: String? { mac.isLocallyAdministered ? nil : OUIDatabase.lookup(bytes: mac.bytes) }
}

/// A client followed across sweeps. Like `TrackedNetwork.steadyScans`: a sweep that looks like a stream adds one, one
/// that does not takes one off.
nonisolated struct TrackedSender: Equatable, Sendable, Identifiable {
    /// The latest sweep in which it looked like a stream.
    var traffic: ClientTraffic
    var score: Int

    var id: BSSID { traffic.mac }
    var isSteady: Bool { score >= Threats.streamingScans }

    /// The next state of all senders after a sweep. One device heard under two access points counts once.
    static func next(_ senders: [BSSID: TrackedSender], sweep: [ClientTraffic]) -> [BSSID: TrackedSender] {
        var best: [BSSID: ClientTraffic] = [:]
        for traffic in sweep where traffic.looksLikeStream {
            if let have = best[traffic.mac], have.upBytesPerSecond >= traffic.upBytesPerSecond { continue }
            best[traffic.mac] = traffic
        }
        var result: [BSSID: TrackedSender] = [:]
        for (mac, sender) in senders where best[mac] == nil && sender.score > 1 {
            result[mac] = TrackedSender(traffic: sender.traffic, score: sender.score - 1)
        }
        for (mac, traffic) in best {
            result[mac] = TrackedSender(traffic: traffic, score: min((senders[mac]?.score ?? 0) + 1, Threats.streamingScansKept))
        }
        return result
    }
}

/// Bits per second as people read them.
nonisolated enum Bitrate {
    static func text(bytesPerSecond: Int) -> String {
        let bitsPerSecond = Double(bytesPerSecond) * 8
        if bitsPerSecond >= 1_000_000 {
            return String(localized: "\(bitsPerSecond / 1_000_000, specifier: "%.1f") Mbit/s")
        } else if bitsPerSecond >= 1_000 {
            return String(localized: "\(bitsPerSecond / 1_000, specifier: "%.0f") kbit/s")
        }
        return String(localized: "\(bitsPerSecond, specifier: "%.0f") bit/s")
    }
}
