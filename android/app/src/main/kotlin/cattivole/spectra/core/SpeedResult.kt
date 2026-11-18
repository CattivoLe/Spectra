package cattivole.spectra.core

/** A finished full speed test. Mirrors the iOS `SpeedResult`. */
data class SpeedResult(
    val measuredAt: Long,
    val downBps: Double?,
    val upBps: Double?,
    val idleLatencyMs: Int?,
    val jitterMs: Int?,
    val loadedDownLatencyMs: Int?,
    val loadedUpLatencyMs: Int?,
    /** Throughput every quarter second, in bit/s. */
    val downSamples: List<Double>,
    val upSamples: List<Double>,
    /** The Cloudflare edge that served the test, like "BEG", or the M-Lab machine as "Belgrade · mlab1-beg01". */
    val server: String?,
    /** Measurement Lab ran the test, over one stream each way. */
    val mlab: Boolean = false,
    /** Download streams. */
    val streams: Int = 4,
) {
    /**
     * Latency added by a full download or upload queue, the "bufferbloat"
     * grade speed-test sites give: A under 30 ms, B under 60, C under 200,
     * D under 400, F beyond.
     */
    val bufferbloatGrade: String?
        get() {
            val idle = idleLatencyMs ?: return null
            val loaded = listOfNotNull(loadedDownLatencyMs, loadedUpLatencyMs).maxOrNull() ?: return null
            return grade(loaded - idle)
        }

    companion object {
        fun grade(added: Int): String = when {
            added < 30 -> "A"
            added < 60 -> "B"
            added < 200 -> "C"
            added < 400 -> "D"
            else -> "F"
        }

        /** The middle of the samples, and the mean step between neighbours as jitter. */
        fun latency(samples: List<Int>): Pair<Int?, Int?> {
            if (samples.isEmpty()) return null to null
            val sorted = samples.sorted()
            val median = sorted[sorted.size / 2]
            if (samples.size < 2) return median to null
            val steps = samples.zipWithNext { a, b -> kotlin.math.abs(a - b) }
            return median to (steps.sum().toDouble() / steps.size).let { kotlin.math.round(it).toInt() }
        }

        /** The mean of the last four quarter-second rates: a one-second moving average. */
        fun smoothed(raw: List<Double>): Double {
            val window = raw.takeLast(4)
            return if (window.isEmpty()) 0.0 else window.average()
        }

        /**
         * The rate a test reports: the mean of the smoothed samples without
         * the first second, while the streams were still ramping up. Same
         * rule as iOS: at least four samples are always kept.
         */
        fun settled(samples: List<Double>): Double? {
            val steady = samples.drop(minOf(4, maxOf(0, samples.size - 4)))
            return steady.takeIf { it.isNotEmpty() }?.average()?.takeIf { it > 0 }
        }
    }
}
