package cattivole.spectra

import cattivole.spectra.core.Proximity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTest {
    @Test
    fun levelsStepWithSignal() {
        assertEquals(Proximity.Level.HERE, Proximity.level(-30))
        assertEquals(Proximity.Level.CLOSE, Proximity.level(-50))
        assertEquals(Proximity.Level.NEAR, Proximity.level(-65))
        assertEquals(Proximity.Level.FAR, Proximity.level(-80))
        assertEquals(Proximity.Level.VERY_FAR, Proximity.level(-95))
    }

    @Test
    fun closenessIsClampedZeroToOne() {
        assertEquals(1.0, Proximity.closeness(-20), 0.0001)
        assertEquals(0.0, Proximity.closeness(-110), 0.0001)
        val mid = Proximity.closeness(-65)
        assertTrue(mid > 0.4 && mid < 0.6)
    }

    @Test
    fun trendFollowsRecentSamples() {
        assertEquals(Proximity.Trend.WARMER, Proximity.trend(listOf(-80, -78, -60, -55)))
        assertEquals(Proximity.Trend.COLDER, Proximity.trend(listOf(-55, -58, -78, -82)))
        assertEquals(Proximity.Trend.STEADY, Proximity.trend(listOf(-70, -71, -70, -69)))
        assertEquals(Proximity.Trend.STEADY, Proximity.trend(listOf(-70, -40)))
    }
}
