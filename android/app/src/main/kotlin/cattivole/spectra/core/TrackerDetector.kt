package cattivole.spectra.core

/**
 * A Bluetooth tag that can report its position through someone else's phones,
 * and how worried its presence should make you. Mirrors the iOS
 * `TrackerDetector`.
 */
data class TrackerAssessment(
    val kind: Kind,
    /** True when the tag says it has lost its owner; null when it cannot say. */
    val separated: Boolean?,
    val batteryLabel: String?,
    /** How long the tag has been continuously nearby, in milliseconds. */
    val presentFor: Long,
    val level: Level,
) {
    enum class Kind(val label: String) {
        FIND_MY("Find My tag"),
        FIND_MY_DEVICE("Find My Device tag"),
        TILE("Tile"),
        SMART_TAG("Samsung SmartTag"),
        CHIPOLO("Chipolo"),
        PEBBLEBEE("Pebblebee"),
    }

    enum class Level {
        /** Near its owner, or not nearby: nothing to do. */
        INFO,

        /** Away from its owner and nearby, but not for long yet. */
        WATCH,

        /** Away from its owner and continuously near you for a while. */
        ALERT,
    }

    val ownerLabel: String?
        get() = when (separated) {
            true -> "Away from its owner"
            false -> "Near its owner"
            null -> null
        }
}

/**
 * Spots trackers among the advertisements and judges whether one may be
 * travelling with the user. Pure, for tests.
 *
 * The judgement is deliberately simple: a tag that has lost its owner and has
 * stayed within earshot for [FOLLOW_WINDOW_MS] deserves a look. A phone cannot
 * tell a tag in your bag from one in the bag next to you, so the advice is to
 * move and see whether it follows.
 */
object TrackerDetector {
    const val FOLLOW_WINDOW_MS = 10 * 60_000L

    fun assess(device: BleDevice, now: Long): TrackerAssessment? {
        val kind = kind(device) ?: return null
        val status = if (kind == TrackerAssessment.Kind.FIND_MY) device.findMyStatus else null
        val separated = status?.separated
        val presentFor = device.presentFor(now)
        val level = when {
            !device.isNearby(now) || separated == false -> TrackerAssessment.Level.INFO
            presentFor >= FOLLOW_WINDOW_MS -> TrackerAssessment.Level.ALERT
            else -> TrackerAssessment.Level.WATCH
        }
        return TrackerAssessment(kind, separated, status?.batteryLabel, presentFor, level)
    }

    /**
     * Which kind of tracker the advertisement comes from, if any. Apple devices
     * that are not tags also send Find My when lost, so a device with a name or
     * an audio or Siri identity is left out.
     */
    fun kind(device: BleDevice): TrackerAssessment.Kind? {
        for (item in device.serviceData) {
            when (Catalog.canonicalUuid(item.uuid)) {
                "FEED", "FEEC" -> return TrackerAssessment.Kind.TILE
                "FD5A" -> return TrackerAssessment.Kind.SMART_TAG
                "FE2C" -> if (item.data.size >= 21 && item.data[0].toInt() == 0x40) {
                    return TrackerAssessment.Kind.FIND_MY_DEVICE
                }
            }
        }
        if (device.companyId == 0x067C) return TrackerAssessment.Kind.TILE
        if (device.companyId == 0x08C3) return TrackerAssessment.Kind.CHIPOLO
        device.companyName?.lowercase()?.let { company ->
            if ("chipolo" in company) return TrackerAssessment.Kind.CHIPOLO
            if ("pebblebee" in company) return TrackerAssessment.Kind.PEBBLEBEE
            if ("tile" in company && "inc" in company) return TrackerAssessment.Kind.TILE
        }
        // An iPhone or Mac in Lost Mode sends Find My too, next to its other
        // Apple messages; a tag sends Find My and nothing else.
        val onlyFindMy = device.apple.all { it is AppleAdvertisement.FindMy }
        if (device.advertisesFindMy && onlyFindMy && !device.hasOwnName) return TrackerAssessment.Kind.FIND_MY
        return null
    }

    /** The trackers in a list, most worrying first, then strongest first. */
    fun trackers(devices: Iterable<BleDevice>, now: Long): List<Pair<BleDevice, TrackerAssessment>> =
        devices.mapNotNull { device -> assess(device, now)?.let { device to it } }
            .sortedWith(compareByDescending<Pair<BleDevice, TrackerAssessment>> { it.second.level }.thenByDescending { it.first.rssi })
}
