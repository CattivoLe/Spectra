package cattivole.spectra.core

/**
 * What one client sent and received in one sweep, counted by the sensor while it listened on the client's channel
 * (`0x25`, sensor firmware 0.11 and later). `Stream.h` in the firmware has the same record and the same rule.
 */
data class ClientTraffic(
    val mac: MacAddress,
    val bssid: MacAddress,
    val channel: Int,
    /** The strongest frame the client sent. */
    val rssi: Int,
    /** How long the sensor really listened on that channel. */
    val listenMs: Int,
    /** Client to access point. */
    val upBytes: Long,
    val upFrames: Int,
    /** Access point to client, from the first frame the client sent. */
    val downBytes: Long,
    val downFrames: Int,
    /** 100 ms slices of the listening time in which the client sent data. */
    val busySlots: Int,
) {
    val totalSlots: Int get() = ((listenMs + 99) / 100).coerceAtMost(32)
    val upBytesPerSecond: Long get() = if (listenMs > 0) upBytes * 1000 / listenMs else 0
    val downBytesPerSecond: Long get() = if (listenMs > 0) downBytes * 1000 / listenMs else 0

    /** Sends like a camera or a microphone: steadily, in large frames, and far more than it receives. */
    val looksLikeStream: Boolean
        get() = upFrames > 0 && listenMs > 0 &&
            upBytesPerSecond >= Threats.CLIENT_MIN_BYTES_PER_SECOND &&
            upBytes >= Threats.CLIENT_MIN_FRAME_BYTES * upFrames &&
            upBytes / Threats.CLIENT_UP_OVER_DOWN >= downBytes &&
            busySlots * 100 >= Threats.CLIENT_BUSY_PERCENT * totalSlots
}

/**
 * A client followed across sweeps. Like [TrackedNetwork.steadyScans]: a sweep that looks like a stream adds one, one
 * that does not takes one off. [traffic] is the latest sweep in which it looked like a stream.
 */
data class TrackedSender(val traffic: ClientTraffic, val score: Int) {
    val id: MacAddress get() = traffic.mac
    val isSteady: Boolean get() = score >= Threats.STREAMING_SCANS

    companion object {
        /** The next state of all senders after a sweep. One device heard under two access points counts once. */
        fun next(senders: Map<MacAddress, TrackedSender>, sweep: List<ClientTraffic>): Map<MacAddress, TrackedSender> {
            val best = linkedMapOf<MacAddress, ClientTraffic>()
            for (traffic in sweep) {
                if (!traffic.looksLikeStream) continue
                val have = best[traffic.mac]
                if (have == null || have.upBytesPerSecond < traffic.upBytesPerSecond) best[traffic.mac] = traffic
            }
            val result = linkedMapOf<MacAddress, TrackedSender>()
            for ((mac, sender) in senders) {
                if (mac !in best && sender.score > 1) result[mac] = sender.copy(score = sender.score - 1)
            }
            for ((mac, traffic) in best) {
                val score = ((senders[mac]?.score ?: 0) + 1).coerceAtMost(Threats.STREAMING_SCANS_KEPT)
                result[mac] = TrackedSender(traffic, score)
            }
            return result
        }
    }
}
