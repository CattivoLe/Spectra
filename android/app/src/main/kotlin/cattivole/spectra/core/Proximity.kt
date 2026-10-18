package cattivole.spectra.core

/**
 * Turns a running RSSI into something a person can walk toward: a coarse
 * closeness level, a 0..1 value for a gauge, and whether the last few readings
 * are getting warmer or colder. RSSI-to-distance is too noisy to show in
 * metres, so the radar speaks in "warmer / colder". Pure, for tests.
 */
object Proximity {
    enum class Level { VERY_FAR, FAR, NEAR, CLOSE, HERE }

    enum class Trend { WARMER, COLDER, STEADY }

    fun level(rssi: Int): Level = when {
        rssi >= -45 -> Level.HERE
        rssi >= -60 -> Level.CLOSE
        rssi >= -72 -> Level.NEAR
        rssi >= -85 -> Level.FAR
        else -> Level.VERY_FAR
    }

    /** 0 at -100 dBm or weaker, 1 at -30 dBm or stronger, for a gauge. */
    fun closeness(rssi: Int): Double {
        val clamped = rssi.coerceIn(-100, -30).toDouble()
        return (clamped + 100) / 70.0
    }

    /**
     * Compares the recent half of the samples with the earlier half; a few dB
     * of change is treated as steady so the arrow does not jitter standing still.
     */
    fun trend(samples: List<Int>, threshold: Double = 3.0): Trend {
        if (samples.size < 4) return Trend.STEADY
        val half = samples.size / 2
        val recent = samples.takeLast(half)
        val earlier = samples.take(samples.size - half)
        val delta = recent.average() - earlier.average()
        return when {
            delta > threshold -> Trend.WARMER
            delta < -threshold -> Trend.COLDER
            else -> Trend.STEADY
        }
    }
}
