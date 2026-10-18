package cattivole.spectra.core

import kotlin.math.pow

/**
 * A rough distance from RSSI, using the log-distance path-loss model
 * `d = 10^((P₁ − rssi) / (10·n))`, where P₁ is the expected power at one metre
 * and n the path-loss exponent. Walls, bodies and antenna orientation swing
 * this by metres, so it is only ever shown as an approximation — the radar's
 * "warmer / colder" is the reliable guide.
 */
object DistanceEstimate {
    /** Typical received power at one metre for a BLE device, in dBm. */
    const val REFERENCE_POWER_AT_ONE_METRE = -59

    /** Indoor path-loss exponent (free space is 2; indoors runs higher). */
    const val PATH_LOSS_EXPONENT = 2.5

    /** Estimated metres, clamped to a sane 0.1..100 m. */
    fun metres(
        rssi: Int,
        referencePower: Int = REFERENCE_POWER_AT_ONE_METRE,
        pathLoss: Double = PATH_LOSS_EXPONENT,
    ): Double {
        if (rssi >= 0) return 0.1
        val exponent = (referencePower - rssi) / (10 * pathLoss)
        return 10.0.pow(exponent).coerceIn(0.1, 100.0)
    }
}
