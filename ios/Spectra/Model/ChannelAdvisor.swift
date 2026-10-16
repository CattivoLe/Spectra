import Foundation

/// Judges how crowded each 2.4 GHz channel is from the networks in range.
nonisolated enum ChannelAdvisor {
    /// The only three 20 MHz channels that do not overlap each other.
    static let candidates = [1, 6, 11]

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

    /// The least crowded of the non-overlapping channels.
    static func recommendation(from readings: [AccessPointReading]) -> Int {
        candidates.min { congestion(on: $0, from: readings) < congestion(on: $1, from: readings) } ?? 1
    }

    /// Congestion on a 0…1 scale for display, where 1 is a loud network
    /// (-40 dBm) right on top.
    static func level(_ congestion: Double) -> Double {
        guard congestion > 0 else { return 0 }
        let dBm = 10 * log10(congestion)
        return min(max((dBm + 95) / 55, 0), 1)
    }
}
