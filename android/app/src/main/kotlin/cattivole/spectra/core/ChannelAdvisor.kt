package cattivole.spectra.core

import kotlin.math.log10
import kotlin.math.pow

/** Judges how crowded each 2.4 GHz channel is from the networks in range. */
object ChannelAdvisor {
    /** The only three 20 MHz channels that do not overlap each other. */
    val candidates = listOf(1, 6, 11)

    /**
     * Common non-DFS 20 MHz channels in the 5 GHz band (UNII-1 and UNII-3).
     * DFS channels (52–144) are left out: quieter, but a router there must
     * vacate on radar, so they make a poor fixed recommendation.
     */
    val fiveCandidates = listOf(36, 40, 44, 48, 149, 153, 157, 161)

    /**
     * Interference a network on [channel] would meet: received power of every
     * network whose signal overlaps it, weighted by how much it overlaps.
     * Power adds in milliwatts, so one loud neighbour outweighs several faint ones.
     */
    fun congestion(channel: Int, readings: List<AccessPointReading>): Double {
        val oursLow = (channel - 2).toDouble()
        val oursHigh = (channel + 2).toDouble()
        return readings.sumOf { reading ->
            val theirs = reading.occupiedChannels
            val overlap = minOf(oursHigh, theirs.endInclusive) - maxOf(oursLow, theirs.start)
            if (overlap <= 0) {
                0.0
            } else {
                val share = overlap / (oursHigh - oursLow)
                share * 10.0.pow(reading.rssi / 10.0)
            }
        }
    }

    /** The least crowded of the non-overlapping 2.4 GHz channels. */
    fun recommendation(readings: List<AccessPointReading>): Int =
        candidates.minByOrNull { congestion(it, readings) } ?: 1

    /** The least crowded of the common non-DFS 5 GHz channels. */
    fun recommendationFive(readings: List<AccessPointReading>): Int =
        fiveCandidates.minByOrNull { congestion(it, readings) } ?: 36

    /** Congestion on a 0..1 scale, where 1 is a loud network (-40 dBm) right on top. */
    fun level(congestion: Double): Double {
        if (congestion <= 0) return 0.0
        val dbm = 10 * log10(congestion)
        return ((dbm + 95) / 55).coerceIn(0.0, 1.0)
    }
}
