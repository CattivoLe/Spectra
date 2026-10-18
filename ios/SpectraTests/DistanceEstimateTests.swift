import Foundation
import Testing
@testable import Spectra

struct DistanceEstimateTests {
    @Test func oneMetreAtReferencePower() {
        let d = DistanceEstimate.metres(rssi: DistanceEstimate.referencePowerAtOneMetre)
        #expect(abs(d - 1) < 0.01)
    }

    @Test func oneDecadeOfPathLossIsTenMetres() {
        // rssi a full 10·n dB below the reference → ten metres.
        let rssi = DistanceEstimate.referencePowerAtOneMetre - Int(10 * DistanceEstimate.pathLossExponent)
        let d = DistanceEstimate.metres(rssi: rssi)
        #expect(abs(d - 10) < 0.1)
    }

    @Test func strongerSignalMeansCloser() {
        #expect(DistanceEstimate.metres(rssi: -40) < DistanceEstimate.metres(rssi: -80))
    }

    @Test func clampedToRange() {
        #expect(DistanceEstimate.metres(rssi: -5) >= 0.1)
        #expect(DistanceEstimate.metres(rssi: -120) <= 100)
    }
}
