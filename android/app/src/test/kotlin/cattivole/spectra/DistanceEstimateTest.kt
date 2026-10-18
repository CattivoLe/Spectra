package cattivole.spectra

import cattivole.spectra.core.DistanceEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceEstimateTest {
    @Test
    fun oneMetreAtReferencePower() {
        assertEquals(1.0, DistanceEstimate.metres(DistanceEstimate.REFERENCE_POWER_AT_ONE_METRE), 0.01)
    }

    @Test
    fun oneDecadeOfPathLossIsTenMetres() {
        val rssi = DistanceEstimate.REFERENCE_POWER_AT_ONE_METRE - (10 * DistanceEstimate.PATH_LOSS_EXPONENT).toInt()
        assertEquals(10.0, DistanceEstimate.metres(rssi), 0.1)
    }

    @Test
    fun strongerSignalMeansCloser() {
        assertTrue(DistanceEstimate.metres(-40) < DistanceEstimate.metres(-80))
    }

    @Test
    fun clampedToRange() {
        assertTrue(DistanceEstimate.metres(-5) >= 0.1)
        assertTrue(DistanceEstimate.metres(-120) <= 100.0)
    }
}
