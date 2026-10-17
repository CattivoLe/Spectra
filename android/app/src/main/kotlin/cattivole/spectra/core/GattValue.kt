package cattivole.spectra.core

import java.util.Locale

/**
 * Human readings of standard Bluetooth SIG characteristic values. Vendor
 * 128-bit characteristics have no published format, so [decode] returns null
 * for them and the caller falls back to hex.
 */
object GattValue {
    fun decode(uuid: String, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        return when (Catalog.canonicalUuid(uuid)) {
            "2A19" -> "${u8(bytes, 0)}%"
            "2A07" -> "${i8(bytes, 0)} dBm"
            "2A06", "2A3F" -> alertLevel(u8(bytes, 0))
            "2A01" -> if (bytes.size >= 2) appearance(u16(bytes, 0)) else null
            "2A04" -> connectionParameters(bytes)
            "2A1C", "2A1E" -> temperature(bytes)
            "2A2B" -> currentTime(bytes)
            "2A38" -> bodyLocation(u8(bytes, 0))
            "2A1D" -> tempType(u8(bytes, 0))
            "2A50" -> pnpId(bytes)
            "2A23" -> bytes.joinToString(":") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
            "2A37" -> heartRate(bytes)
            "2A6E" -> if (bytes.size >= 2) String.format(Locale.US, "%.2f °C", i16(bytes, 0) / 100.0) else null
            "2A6F" -> if (bytes.size >= 2) String.format(Locale.US, "%.2f%%", u16(bytes, 0) / 100.0) else null
            "2A6D" -> if (bytes.size >= 4) String.format(Locale.US, "%.1f Pa", u32(bytes, 0) / 10.0) else null
            "2A76" -> "${u8(bytes, 0)}"
            "2A00", "2A24", "2A25", "2A26", "2A27", "2A28", "2A29", "2A8A", "2A90" -> text(bytes)
            else -> null
        }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun i8(b: ByteArray, i: Int) = b[i].toInt()
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun i16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    private fun appearance(v: Int): String {
        val category = v and 0xFFC0
        val named = when (category) {
            64 -> "Phone"
            128 -> "Computer"
            192 -> "Watch"
            256 -> "Clock"
            320 -> "Display"
            384 -> "Remote control"
            448 -> "Eye-glasses"
            512 -> "Tag"
            576 -> "Keyring"
            640 -> "Media player"
            704 -> "Barcode scanner"
            768 -> "Thermometer"
            832 -> "Heart rate sensor"
            896 -> "Blood pressure"
            960 -> "Human interface device"
            1024 -> "Glucose meter"
            1088 -> "Running sensor"
            1152 -> "Cycling"
            1216 -> "Control device"
            1280 -> "Network device"
            1344 -> "Sensor"
            1408 -> "Light"
            1472 -> "Fan"
            1536 -> "HVAC"
            1600 -> "Air conditioning"
            1664 -> "Humidifier"
            1728 -> "Heating"
            1792 -> "Access control"
            1856 -> "Motorized device"
            1920 -> "Power device"
            1984 -> "Light source"
            2048 -> "Window covering"
            2112 -> "Audio sink"
            2176 -> "Audio source"
            2240 -> "Motorized vehicle"
            2304 -> "Domestic appliance"
            2368 -> "Wearable audio"
            2432 -> "Aircraft"
            2496 -> "AV equipment"
            2560 -> "Display equipment"
            2624 -> "Hearing aid"
            2688 -> "Gaming"
            2752 -> "Signage"
            2816 -> "Pulse oximeter"
            2880 -> "Weight scale"
            2944 -> "Personal mobility"
            3008 -> "Continuous glucose monitor"
            3072 -> "Insulin pump"
            3136 -> "Medication delivery"
            3200 -> "Outdoor sports"
            else -> null
        }
        return named ?: String.format(Locale.US, "0x%04X", v)
    }

    /** IEEE-11073 32-bit FLOAT: 24-bit signed mantissa, 8-bit signed exponent. */
    private fun temperature(b: ByteArray): String? {
        if (b.size < 5) return null
        val raw = u32(b, 1)
        var mantissa = (raw and 0xFFFFFFL).toInt()
        if (mantissa and 0x800000 != 0) mantissa -= 0x1000000
        val exponent = (raw shr 24).toInt().toByte().toInt()
        if (mantissa == 0x7FFFFF) return null
        val value = mantissa.toDouble() * Math.pow(10.0, exponent.toDouble())
        val unit = if (u8(b, 0) and 1 != 0) "°F" else "°C"
        return String.format(Locale.US, "%.2f %s", value, unit)
    }

    private fun connectionParameters(b: ByteArray): String? {
        if (b.size < 8) return null
        val min = u16(b, 0) * 1.25
        val max = u16(b, 2) * 1.25
        val latency = u16(b, 4)
        val timeout = u16(b, 6) * 10
        return String.format(Locale.US, "%.0f–%.0f ms · latency %d · timeout %d ms", min, max, latency, timeout)
    }

    private fun currentTime(b: ByteArray): String? {
        if (b.size < 7) return null
        return String.format(
            Locale.US,
            "%04d-%02d-%02d %02d:%02d:%02d",
            u16(b, 0), u8(b, 2), u8(b, 3), u8(b, 4), u8(b, 5), u8(b, 6),
        )
    }

    private fun alertLevel(v: Int) = when (v) {
        0 -> "No alert"
        1 -> "Mild alert"
        2 -> "High alert"
        else -> "$v"
    }

    private fun bodyLocation(v: Int) = when (v) {
        0 -> "Other"; 1 -> "Chest"; 2 -> "Wrist"; 3 -> "Finger"; 4 -> "Hand"; 5 -> "Ear lobe"; 6 -> "Foot"
        else -> "$v"
    }

    private fun tempType(v: Int) = when (v) {
        1 -> "Armpit"; 2 -> "Body"; 3 -> "Ear"; 4 -> "Finger"; 5 -> "GI tract"; 6 -> "Mouth"; 7 -> "Rectum"; 8 -> "Toe"; 9 -> "Tympanum"
        else -> "$v"
    }

    private fun pnpId(b: ByteArray): String? {
        if (b.size < 7) return null
        val source = when (u8(b, 0)) {
            1 -> "Bluetooth SIG"
            2 -> "USB-IF"
            else -> "source ${u8(b, 0)}"
        }
        val vid = u16(b, 1)
        val pid = u16(b, 3)
        val ver = u16(b, 5)
        val vidText = Catalog.company(vid)?.let { "$it (${String.format(Locale.US, "0x%04X", vid)})" }
            ?: String.format(Locale.US, "0x%04X", vid)
        return String.format(
            Locale.US,
            "%s · VID %s · PID 0x%04X · v%d.%d.%d",
            source, vidText, pid, ver shr 8, (ver shr 4) and 0xF, ver and 0xF,
        )
    }

    private fun heartRate(b: ByteArray): String? {
        if (b.size < 2) return null
        val flags = u8(b, 0)
        val bpm = if (flags and 1 != 0) {
            if (b.size < 3) return null
            u16(b, 1)
        } else {
            u8(b, 1)
        }
        return "$bpm bpm"
    }

    private fun text(b: ByteArray): String? {
        val s = String(b, Charsets.UTF_8).trimEnd('\u0000')
        return if (s.isNotBlank() && s.all { it.code in 32..126 || it.code > 160 }) s else null
    }
}
