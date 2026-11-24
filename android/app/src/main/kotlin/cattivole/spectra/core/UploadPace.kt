package cattivole.spectra.core

/**
 * How large a stream's next upload body is. A body has to be answered well
 * within the test to count, and large enough that the wait for the answer
 * does not eat the speed: it grows while answers come quickly and shrinks
 * when they are late. Mirrors the iOS `UploadPace`.
 */
class UploadPace {
    var size = FLOOR
        private set

    fun answered(seconds: Double) {
        if (seconds < 0.4) {
            size = minOf(CEILING, size * 2)
        } else if (seconds > 1.2) {
            size = maxOf(FLOOR, size / 2)
        }
    }

    companion object {
        const val FLOOR = 16_000
        const val CEILING = 1_000_000
    }
}
