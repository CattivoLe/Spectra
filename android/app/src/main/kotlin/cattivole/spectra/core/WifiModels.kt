package cattivole.spectra.core

enum class Band(val label: String) {
    TWO_FOUR("2.4 GHz"),
    FIVE("5 GHz"),
    SIX("6 GHz"),
}

enum class SecondaryChannel(val raw: Int) {
    NONE(0),
    ABOVE(1),
    BELOW(2),
    ;

    companion object {
        fun from(raw: Int): SecondaryChannel = entries.firstOrNull { it.raw == raw } ?: NONE
    }
}

enum class RadioSource { SENSOR, PHONE }

enum class SsidVisibility { NAMED, HIDDEN, REDACTED }

/** ESP-IDF `wifi_auth_mode_t`. Unknown values stay visible as "Other". */
@JvmInline
value class AuthMode(val raw: Int) {
    val name: String
        get() = when (raw) {
            0 -> "Open"
            1 -> "WEP"
            2 -> "WPA"
            3 -> "WPA2"
            4 -> "WPA/WPA2"
            5 -> "WPA2 Enterprise"
            6 -> "WPA3"
            7 -> "WPA2/WPA3"
            8 -> "WAPI"
            9 -> "OWE"
            10 -> "WPA3 Enterprise 192-bit"
            11 -> "WPA3 (extended key)"
            12 -> "WPA3 (extended key, mixed)"
            13 -> "DPP"
            14 -> "WPA3 Enterprise"
            15 -> "WPA2/WPA3 Enterprise"
            else -> "Other"
        }

    enum class Strength { NONE, WEAK, FAIR, STRONG }

    val strength: Strength
        get() = when (raw) {
            0 -> Strength.NONE
            1, 2 -> Strength.WEAK
            3, 4, 5, 8, 9 -> Strength.FAIR
            else -> Strength.STRONG
        }

    /** How the phone itself joins this network. Mirrors the iOS `AuthMode.JoinMethod`. */
    enum class JoinMethod { OPEN, PSK, WEP, UNSUPPORTED }

    val joinMethod: JoinMethod
        get() = when (raw) {
            0, 9 -> JoinMethod.OPEN        // open, OWE
            1 -> JoinMethod.WEP
            2, 3, 4, 6, 7 -> JoinMethod.PSK // WPA, WPA2, WPA/WPA2, WPA3, WPA2/WPA3 (PSK/SAE)
            else -> JoinMethod.UNSUPPORTED  // enterprise, DPP, WAPI, unknown
        }

    companion object {
        val OPEN = AuthMode(0)
        val WEP = AuthMode(1)
        val WPA_PSK = AuthMode(2)
        val WPA2_PSK = AuthMode(3)
        val WPA_WPA2_PSK = AuthMode(4)
        val WPA2_ENTERPRISE = AuthMode(5)
        val WPA3_PSK = AuthMode(6)
        val WPA2_WPA3_PSK = AuthMode(7)
        val OWE = AuthMode(9)
    }
}

/** ESP-IDF `wifi_cipher_type_t`. */
@JvmInline
value class Cipher(val raw: Int) {
    val name: String
        get() = when (raw) {
            0 -> "None"
            1 -> "WEP-40"
            2 -> "WEP-104"
            3 -> "TKIP"
            4 -> "CCMP (AES)"
            5 -> "TKIP + CCMP"
            6 -> "AES-CMAC-128"
            7 -> "SMS4"
            8 -> "GCMP"
            9 -> "GCMP-256"
            10 -> "AES-GMAC-128"
            11 -> "AES-GMAC-256"
            else -> "Unknown"
        }

    companion object {
        val NONE = Cipher(0)
        val TKIP = Cipher(3)
        val CCMP = Cipher(4)
        val GCMP_256 = Cipher(9)
    }
}

object Capabilities {
    const val B = 1 shl 0
    const val G = 1 shl 1
    const val N = 1 shl 2
    const val LONG_RANGE = 1 shl 3
    const val AX = 1 shl 4
    const val WPS = 1 shl 5
    const val FTM = 1 shl 6

    fun generation(raw: Int, extra: String? = null): String {
        if (extra == "Wi-Fi 7") return "Wi\u2011Fi\u00A07"
        if (raw and AX != 0) return "Wi\u2011Fi\u00A06"
        if (extra == "Wi-Fi 5") return "Wi\u2011Fi\u00A05"
        if (raw and N != 0) return "Wi\u2011Fi\u00A04"
        if (raw and G != 0) return "802.11g"
        if (raw and B != 0) return "802.11b"
        return "—"
    }

    fun standards(raw: Int): String {
        val parts = buildList {
            if (raw and B != 0) add("b")
            if (raw and G != 0) add("g")
            if (raw and N != 0) add("n")
            if (raw and AX != 0) add("ax")
        }
        return if (parts.isEmpty()) "—" else "802.11" + parts.joinToString("/")
    }
}

class AccessPointReading(
    val bssid: MacAddress,
    val rssi: Int,
    val channel: Int,
    val secondaryChannel: SecondaryChannel,
    val auth: AuthMode,
    val pairwiseCipher: Cipher,
    val groupCipher: Cipher,
    val capabilities: Int,
    val countryCode: String?,
    val ssidBytes: ByteArray,
    val band: Band = if (channel > 14) Band.FIVE else Band.TWO_FOUR,
    val widthMHz: Int? = null,
    val visibility: SsidVisibility = if (ssidBytes.isEmpty() || ssidBytes.all { it == 0.toByte() }) {
        SsidVisibility.HIDDEN
    } else {
        SsidVisibility.NAMED
    },
    /** Phone scans sometimes advertise HT/VHT/EHT without a protocol bit. */
    val phyNote: String? = null,
    /**
     * Middle of a wide channel, in channel numbers. The phone reports it for
     * 40 MHz and wider; the sensor says which side the second half is on instead.
     */
    val centreChannel: Double? = null,
) {
    val ssid: String?
        get() {
            if (visibility != SsidVisibility.NAMED) return null
            if (ssidBytes.isEmpty() || ssidBytes.all { it == 0.toByte() }) return null
            return String(ssidBytes, Charsets.UTF_8)
        }

    val displayName: String
        get() = when (visibility) {
            SsidVisibility.NAMED -> ssid ?: "Hidden network"
            SsidVisibility.HIDDEN -> "Hidden network"
            SsidVisibility.REDACTED -> "Name withheld"
        }

    val frequencyMHz: Int
        get() = when {
            channel == 14 -> 2484
            band == Band.TWO_FOUR -> 2407 + channel * 5
            band == Band.SIX -> 5950 + channel * 5
            else -> 5000 + channel * 5
        }

    val channelWidthMHz: Int
        get() = when {
            secondaryChannel != SecondaryChannel.NONE -> 40
            widthMHz != null -> widthMHz
            else -> 20
        }

    /** Channels this signal covers, in channel-number units (5 MHz each). */
    val occupiedChannels: ClosedFloatingPointRange<Double>
        get() {
            val centre = when (secondaryChannel) {
                SecondaryChannel.NONE -> centreChannel ?: channel.toDouble()
                SecondaryChannel.ABOVE -> channel + 2.0
                SecondaryChannel.BELOW -> channel - 2.0
            }
            val half = when {
                secondaryChannel != SecondaryChannel.NONE -> 4.0
                widthMHz != null -> widthMHz / 10.0
                else -> 2.0
            }
            return (centre - half)..(centre + half)
        }

    override fun equals(other: Any?): Boolean {
        if (other !is AccessPointReading) return false
        return bssid == other.bssid &&
            rssi == other.rssi &&
            channel == other.channel &&
            secondaryChannel == other.secondaryChannel &&
            auth == other.auth &&
            pairwiseCipher == other.pairwiseCipher &&
            groupCipher == other.groupCipher &&
            capabilities == other.capabilities &&
            countryCode == other.countryCode &&
            ssidBytes.contentEquals(other.ssidBytes) &&
            band == other.band &&
            widthMHz == other.widthMHz &&
            visibility == other.visibility &&
            phyNote == other.phyNote &&
            centreChannel == other.centreChannel
    }

    override fun hashCode(): Int {
        var result = bssid.hashCode()
        result = 31 * result + rssi
        result = 31 * result + channel
        result = 31 * result + ssidBytes.contentHashCode()
        return result
    }
}

data class WifiClient(
    val mac: MacAddress,
    val apBssid: MacAddress?,
    var rssi: Int,
    val isAssociated: Boolean,
) {
    val id: MacAddress get() = mac
    val manufacturer: String? get() = mac.vendor()
    val displayName: String get() = manufacturer ?: mac.text
}

data class ProbeRequest(
    val mac: MacAddress,
    var rssi: Int,
    val ssid: String?,
    val channel: Int,
) {
    val manufacturer: String? get() = mac.vendor()
    val displayName: String get() = manufacturer ?: mac.text
}

data class ProbingDevice(
    val mac: MacAddress,
    var rssi: Int,
    val ssids: MutableList<String>,
    val channel: Int,
) {
    val manufacturer: String? get() = mac.vendor()
    val displayName: String get() = manufacturer ?: mac.text
}

data class DeauthEvent(
    val source: MacAddress,
    val target: MacAddress,
    val bssid: MacAddress,
    val reason: Int,
    val rssi: Int,
    val channel: Int,
    val count: Int,
) {
    val id: String get() = "$source-$target-$bssid-$reason"
    val isBroadcast: Boolean get() = target.isBroadcast

    enum class Severity { INFO, WARNING, CRITICAL }

    val severity: Severity
        get() = when {
            isBroadcast && count >= 3 -> Severity.CRITICAL
            count >= 10 -> Severity.CRITICAL
            isBroadcast || count >= 3 -> Severity.WARNING
            else -> Severity.INFO
        }

    val reasonDescription: String
        get() = when (reason) {
            1 -> "Unspecified"
            2 -> "Auth no longer valid"
            3 -> "Leaving / deassociated"
            4 -> "Inactivity"
            5 -> "AP full"
            6 -> "Class 2 from non-auth"
            7 -> "Class 3 from non-assoc"
            8 -> "Leaving / disassociated"
            9 -> "Not authenticated"
            15 -> "4-way handshake timeout"
            16 -> "Group key handshake timeout"
            else -> "Code $reason"
        }
}

data class VendorDeviceInfo(val deviceName: String, val manufacturer: String)

data class TrafficSnapshot(val bytes: Long, val packets: Int) {
    val formattedBytes: String
        get() = when {
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }

    fun estimatedBitrate(durationMs: Int): String? {
        if (durationMs <= 0) return null
        val bitsPerSecond = bytes * 8.0 / (durationMs / 1000.0)
        return when {
            bitsPerSecond >= 1_000_000 -> "%.1f Mbps".format(bitsPerSecond / 1_000_000)
            bitsPerSecond >= 1_000 -> "%.0f Kbps".format(bitsPerSecond / 1_000)
            else -> "%.0f bps".format(bitsPerSecond)
        }
    }
}

class TrackedNetwork(
    var latest: AccessPointReading,
    val firstSeen: Long,
    var lastSeen: Long,
    val samples: MutableList<Sample>,
    var missedScans: Int = 0,
    var clients: MutableList<WifiClient> = mutableListOf(),
    var revealedSsid: String? = null,
    var vendorInfo: VendorDeviceInfo? = null,
    var traffic: TrafficSnapshot? = null,
    val source: RadioSource,
) {
    data class Sample(val date: Long, val rssi: Int)

    val id: MacAddress get() = latest.bssid
    val wasInLastScan: Boolean get() = missedScans == 0
    val isInRange: Boolean get() = missedScans < NetworkStore.SCANS_BEFORE_OUT_OF_RANGE
    val strongestRssi: Int get() = samples.maxOfOrNull { it.rssi } ?: latest.rssi
    val weakestRssi: Int get() = samples.minOfOrNull { it.rssi } ?: latest.rssi
    val averageRssi: Int
        get() = if (samples.isEmpty()) latest.rssi else samples.sumOf { it.rssi } / samples.size

    val title: String get() = revealedSsid ?: latest.displayName
}

enum class SignalQuality {
    EXCELLENT, GOOD, FAIR, WEAK, POOR;

    val label: String
        get() = when (this) {
            EXCELLENT -> "Excellent"
            GOOD -> "Good"
            FAIR -> "Fair"
            WEAK -> "Weak"
            POOR -> "Poor"
        }

    companion object {
        fun of(rssi: Int): SignalQuality = when {
            rssi >= -55 -> EXCELLENT
            rssi >= -65 -> GOOD
            rssi >= -73 -> FAIR
            rssi >= -82 -> WEAK
            else -> POOR
        }

        fun fraction(rssi: Int): Float =
            ((rssi + 95) / 50.0).coerceIn(0.05, 1.0).toFloat()
    }
}

/** True when this network's name is one the phone has a password for. */
fun TrackedNetwork.hasStoredPassword(ssids: Set<String>): Boolean {
    val name = revealedSsid ?: latest.ssid
    return name != null && name in ssids
}

/** Whether a network stays on the Wi-Fi list. */
fun TrackedNetwork.passesWifiFilter(
    showOutOfRange: Boolean,
    openOnly: Boolean,
    band: Band?,
    needle: String,
    knownOnly: Boolean = false,
    knownSsids: Set<String> = emptySet(),
): Boolean {
    if (!showOutOfRange && !isInRange) return false
    if (openOnly && latest.auth.strength != AuthMode.Strength.NONE) return false
    if (knownOnly && !hasStoredPassword(knownSsids)) return false
    if (band != null && latest.band != band) return false
    if (needle.isEmpty()) return true
    return title.contains(needle, ignoreCase = true) ||
        id.text.contains(needle, ignoreCase = true) ||
        id.vendor()?.contains(needle, ignoreCase = true) == true
}

/**
 * A probing device is looking for a network, so the Open and Known chips hide the section.
 */
fun ProbingDevice.passesWifiFilter(
    openOnly: Boolean,
    band: Band?,
    needle: String,
    knownOnly: Boolean = false,
): Boolean {
    if (openOnly || knownOnly) return false
    if (band == Band.FIVE || band == Band.SIX) return false
    if (band == Band.TWO_FOUR && channel > 14) return false
    if (needle.isEmpty()) return true
    return displayName.contains(needle, ignoreCase = true) ||
        mac.text.contains(needle, ignoreCase = true) ||
        mac.vendor()?.contains(needle, ignoreCase = true) == true ||
        ssids.any { it.contains(needle, ignoreCase = true) }
}
