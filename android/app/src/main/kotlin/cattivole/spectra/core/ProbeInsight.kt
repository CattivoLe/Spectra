package cattivole.spectra.core

/**
 * What a device's probe requests reveal. A probe names a network the device has
 * joined before, so the list is a small fingerprint of where it has been — and
 * any name it probes for, it will auto-join if an access point claims it. Pure.
 */
object ProbeInsight {
    /** Probing for this many distinct networks makes a device easy to single out. */
    const val TRACKABLE_THRESHOLD = 3

    data class Result(
        val rememberedCount: Int,
        val presentHere: List<String>,
        val autoJoinOpen: List<String>,
        val trackable: Boolean,
    )

    fun assess(ssids: List<String>, networksInRange: List<AccessPointReading>): Result {
        val remembered = ssids.filter { it.isNotEmpty() }.toSet()
        val present = mutableListOf<String>()
        val autoJoin = mutableListOf<String>()
        for (ssid in remembered) {
            val matches = networksInRange.filter { it.ssid?.equals(ssid, ignoreCase = true) == true }
            if (matches.isEmpty()) continue
            present.add(ssid)
            if (matches.any { it.auth == AuthMode.OPEN }) autoJoin.add(ssid)
        }
        return Result(remembered.size, present.sorted(), autoJoin.sorted(), remembered.size >= TRACKABLE_THRESHOLD)
    }
}
