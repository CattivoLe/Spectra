package cattivole.spectra

import cattivole.spectra.core.HistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDistanceTest {
    @Test
    fun metresApproximatesDistance() {
        // 0.001° of latitude is about 111 m.
        assertEquals(111.3, HistoryStore.metres(55.75, 37.61, 55.751, 37.61), 1.0)
    }

    @Test
    fun nearbyIsUnderTheMergeThreshold() {
        // ~45 m apart merges; ~330 m apart does not.
        assertTrue(HistoryStore.metres(55.7500, 37.61, 55.7504, 37.61) <= HistoryStore.MERGE_DISTANCE_METRES)
        assertTrue(HistoryStore.metres(55.7500, 37.61, 55.7530, 37.61) > HistoryStore.MERGE_DISTANCE_METRES)
    }
}
