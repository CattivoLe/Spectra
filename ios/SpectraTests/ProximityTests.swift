import Foundation
import Testing
@testable import Spectra

struct ProximityTests {
    @Test func levelsStepWithSignal() {
        #expect(Proximity.level(rssi: -30) == .here)
        #expect(Proximity.level(rssi: -50) == .close)
        #expect(Proximity.level(rssi: -65) == .near)
        #expect(Proximity.level(rssi: -80) == .far)
        #expect(Proximity.level(rssi: -95) == .veryFar)
    }

    @Test func closenessIsClampedZeroToOne() {
        #expect(Proximity.closeness(rssi: -20) == 1)
        #expect(Proximity.closeness(rssi: -110) == 0)
        let mid = Proximity.closeness(rssi: -65)
        #expect(mid > 0.4 && mid < 0.6)
    }

    @Test func trendFollowsRecentSamples() {
        #expect(Proximity.trend(samples: [-80, -78, -60, -55]) == .warmer)
        #expect(Proximity.trend(samples: [-55, -58, -78, -82]) == .colder)
        #expect(Proximity.trend(samples: [-70, -71, -70, -69]) == .steady)
        // Too few samples to judge.
        #expect(Proximity.trend(samples: [-70, -40]) == .steady)
    }
}
