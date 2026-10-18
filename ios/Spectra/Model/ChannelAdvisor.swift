import Foundation

/// Judges how crowded each 2.4 GHz channel is from the networks in range.
nonisolated enum ChannelAdvisor {
    /// The only three 20 MHz channels that do not overlap each other.
    static let candidates = [1, 6, 11]

    /// Common non-DFS 20 MHz channels in the 5 GHz band (UNII-1 and UNII-3).
    /// DFS channels (52–144) are left out: they are quieter but a router there
    /// must vacate on radar, so they make a poor fixed recommendation.
    static let fiveGHzCandidates = [36, 40, 44, 48, 149, 153, 157, 161]

    /// Interference a network on `channel` would meet: the received power of
    /// every network whose signal overlaps it, weighted by how much it
    /// overlaps. Power adds up in milliwatts, so one loud neighbour outweighs
    /// several faint ones, as it does on the air.
    static func congestion(on channel: Int, from readings: [AccessPointReading]) -> Double {
        let ours = Double(channel - 2)...Double(channel + 2)
        return readings.reduce(0) { total, reading in
            let theirs = reading.occupiedChannels
            let overlap = min(ours.upperBound, theirs.upperBound) - max(ours.lowerBound, theirs.lowerBound)
            guard overlap > 0 else { return total }
            let share = overlap / (ours.upperBound - ours.lowerBound)
            return total + share * pow(10, Double(reading.rssi) / 10)
        }
    }

    /// The least crowded of the non-overlapping 2.4 GHz channels.
    static func recommendation(from readings: [AccessPointReading]) -> Int {
        best(among: candidates, from: readings)
    }

    /// The least crowded of the common non-DFS 5 GHz channels.
    static func recommendationFiveGHz(from readings: [AccessPointReading]) -> Int {
        best(among: fiveGHzCandidates, from: readings)
    }

    private static func best(among options: [Int], from readings: [AccessPointReading]) -> Int {
        options.min { congestion(on: $0, from: readings) < congestion(on: $1, from: readings) } ?? (options.first ?? 1)
    }

    /// Congestion on a 0…1 scale for display, where 1 is a loud network
    /// (-40 dBm) right on top.
    static func level(_ congestion: Double) -> Double {
        guard congestion > 0 else { return 0 }
        let dBm = 10 * log10(congestion)
        return min(max((dBm + 95) / 55, 0), 1)
    }
}
