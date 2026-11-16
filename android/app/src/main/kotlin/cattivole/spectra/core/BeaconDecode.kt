package cattivole.spectra.core

import java.util.Locale

/**
 * A human reading of a well-known service-data beacon payload, shown above the
 * raw hex in the device card. Kept pure so it can be tested without a radio.
 */
data class BeaconInfo(val title: String, val detail: String? = null)

object BeaconDecode {
    /** Decodes the service data for a UUID, or null when the format is unknown. */
    fun decode(uuid: String, data: ByteArray): BeaconInfo? = when (Catalog.canonicalUuid(uuid)) {
        "FEAA" -> eddystone(data)
        "FE2C" -> fastPair(data)
        "FD6F" -> BeaconInfo("Exposure Notification")
        "FEED", "FEEC" -> BeaconInfo("Tile")
        "FD5A" -> BeaconInfo("Samsung SmartTag")
        else -> null
    }

    // Eddystone (Google)

    private fun eddystone(b: ByteArray): BeaconInfo? {
        if (b.isEmpty()) return null
        return when (b[0].toInt()) {
            0x00 -> { // UID
                if (b.size < 18) return null
                val power = b[1].toInt()
                val namespace = hex(b, 2, 12)
                val instance = hex(b, 12, 18)
                BeaconInfo("Eddystone-UID", "$namespace · $instance · $power dBm")
            }
            0x10 -> { // URL
                if (b.size < 3) return null
                val power = b[1].toInt()
                val url = eddystoneUrl(b[2].toInt() and 0xFF, b, 3)
                BeaconInfo("Eddystone-URL", "$url · $power dBm")
            }
            0x20 -> { // TLM
                if (b.size < 14) return null
                val mv = u8(b, 2) shl 8 or u8(b, 3)
                val tempRaw = ((u8(b, 4) shl 8) or u8(b, 5)).toShort().toInt()
                val temp = tempRaw / 256.0
                val adv = u32(b, 6)
                val sec = u32(b, 10)
                BeaconInfo(
                    "Eddystone-TLM",
                    String.format(Locale.US, "%d mV · %.1f °C · %d adv · %.0f s uptime", mv, temp, adv, sec / 10.0),
                )
            }
            0x30 -> BeaconInfo("Eddystone-EID")
            else -> BeaconInfo("Eddystone")
        }
    }

    private fun eddystoneUrl(scheme: Int, b: ByteArray, from: Int): String {
        val prefix = when (scheme) {
            0 -> "http://www."
            1 -> "https://www."
            2 -> "http://"
            3 -> "https://"
            else -> ""
        }
        val url = StringBuilder(prefix)
        for (index in from until b.size) {
            val byte = b[index].toInt() and 0xFF
            val expansion = urlSuffixes[byte]
            when {
                expansion != null -> url.append(expansion)
                byte in 0x20 until 0x7F -> url.append(byte.toChar())
            }
        }
        return url.toString()
    }

    private val urlSuffixes = mapOf(
        0x00 to ".com/", 0x01 to ".org/", 0x02 to ".edu/", 0x03 to ".net/",
        0x04 to ".info/", 0x05 to ".biz/", 0x06 to ".gov/",
        0x07 to ".com", 0x08 to ".org", 0x09 to ".edu", 0x0A to ".net",
        0x0B to ".info", 0x0C to ".biz", 0x0D to ".gov",
    )

    // Google Fast Pair

    private fun fastPair(b: ByteArray): BeaconInfo? {
        if (b.isEmpty()) return null
        // Find My Device network frame: a 20- or 32-byte ephemeral identifier.
        // The last byte carries the unwanted-tracking flags when present.
        if (b[0].toInt() == 0x40 && b.size >= 21) {
            val flagged = b.size == 22 || b.size == 34
            return BeaconInfo("Find My Device network", if (flagged) "Tracker frame" else null)
        }
        if (b.size == 3) {
            val model = u8(b, 0) shl 16 or (u8(b, 1) shl 8) or u8(b, 2)
            return BeaconInfo("Fast Pair", String.format(Locale.US, "model 0x%06X", model))
        }
        return BeaconInfo("Fast Pair")
    }

    private fun u8(b: ByteArray, index: Int): Int = b[index].toInt() and 0xFF

    private fun u32(b: ByteArray, index: Int): Long =
        (u8(b, index).toLong() shl 24) or (u8(b, index + 1).toLong() shl 16) or
            (u8(b, index + 2).toLong() shl 8) or u8(b, index + 3).toLong()

    private fun hex(b: ByteArray, from: Int, to: Int): String =
        buildString { for (index in from until to) append(String.format(Locale.US, "%02X", b[index].toInt() and 0xFF)) }
}
