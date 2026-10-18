import Foundation

/// A rough distance from RSSI, using the log-distance path-loss model
/// `d = 10^((P₁ − rssi) / (10·n))`, where P₁ is the expected power at one metre
/// and n the path-loss exponent. Walls, bodies and antenna orientation swing
/// this by metres, so it is only ever shown as an approximation — the radar's
/// "warmer / colder" is the reliable guide.
enum DistanceEstimate {
    /// Typical received power at one metre for a BLE device, in dBm.
    static let referencePowerAtOneMetre = -59
    /// Indoor path-loss exponent (free space is 2; indoors runs higher).
    static let pathLossExponent = 2.5

    /// Estimated metres, clamped to a sane 0.1…100 m.
    static func metres(
        rssi: Int,
        referencePower: Int = referencePowerAtOneMetre,
        pathLoss: Double = pathLossExponent
    ) -> Double {
        guard rssi < 0 else { return 0.1 }
        let exponent = (Double(referencePower) - Double(rssi)) / (10 * pathLoss)
        return min(100, max(0.1, pow(10, exponent)))
    }
}
