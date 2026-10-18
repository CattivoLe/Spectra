import Foundation

/// Turns a running RSSI into something a person can walk toward: a coarse
/// closeness level, a 0…1 value for a gauge, and whether the last few readings
/// are getting warmer or colder. RSSI-to-distance is too noisy to show in
/// metres, so the radar speaks in "warmer / colder" instead. Pure, for tests.
enum Proximity {
    enum Level: Int, Comparable {
        case veryFar, far, near, close, here
        static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }

        var label: String {
            switch self {
            case .here: String(localized: "Right here")
            case .close: String(localized: "Very close")
            case .near: String(localized: "Close")
            case .far: String(localized: "Nearby")
            case .veryFar: String(localized: "Far")
            }
        }
    }

    enum Trend {
        case warmer, colder, steady

        var label: String {
            switch self {
            case .warmer: String(localized: "Warmer")
            case .colder: String(localized: "Colder")
            case .steady: String(localized: "Steady")
            }
        }
    }

    static func level(rssi: Int) -> Level {
        switch rssi {
        case (-45)...: .here
        case (-60)..<(-45): .close
        case (-72)..<(-60): .near
        case (-85)..<(-72): .far
        default: .veryFar
        }
    }

    /// 0 at −100 dBm or weaker, 1 at −30 dBm or stronger, for a gauge.
    static func closeness(rssi: Int) -> Double {
        let clamped = Double(min(-30, max(-100, rssi)))
        return (clamped + 100) / 70
    }

    /// Compares the recent half of the samples with the earlier half; a few dB
    /// of change is treated as steady so the arrow does not jitter standing still.
    static func trend(samples: [Int], threshold: Double = 3) -> Trend {
        guard samples.count >= 4 else { return .steady }
        let recent = samples.suffix(samples.count / 2)
        let earlier = samples.prefix(samples.count - recent.count)
        let recentAvg = Double(recent.reduce(0, +)) / Double(recent.count)
        let earlierAvg = Double(earlier.reduce(0, +)) / Double(earlier.count)
        let delta = recentAvg - earlierAvg
        if delta > threshold { return .warmer }
        if delta < -threshold { return .colder }
        return .steady
    }
}
