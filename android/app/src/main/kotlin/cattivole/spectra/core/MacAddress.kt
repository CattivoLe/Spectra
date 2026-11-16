package cattivole.spectra.core

/**
 * Six-byte address. On Android this is the hardware address the radio
 * actually reports. iOS substitutes a per-app UUID for BLE devices and
 * often hides LAN hardware addresses; this type is the same on both,
 * and the Android UI is allowed to show it.
 */
class MacAddress(bytes: ByteArray) {
    val bytes: ByteArray = bytes.copyOf(6)

    val text: String
        get() = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    val oui: String
        get() = bytes.take(3).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    /** Set when the device chose the address itself: guest networks, hotspots, private BLE. */
    val isLocallyAdministered: Boolean
        get() = (bytes.firstOrNull()?.toInt()?.and(0x02) ?: 0) != 0

    val isBroadcast: Boolean
        get() = bytes.all { it == 0xFF.toByte() }

    val isZero: Boolean
        get() = bytes.all { it == 0.toByte() }

    /**
     * Bluetooth random addresses use the top two bits. 0b11 is resolvable
     * private, 0b01 is static random, 0b00 is public.
     */
    val bluetoothAddressKind: String
        get() {
            val top = bytes[0].toInt().and(0xC0)
            return when (top) {
                0xC0 -> "Resolvable private"
                0x40 -> "Random static"
                0x00 -> "Public"
                else -> "Non-resolvable private"
            }
        }

    fun vendor(): String? {
        if (isLocallyAdministered) return null
        return Catalog.oui(bytes) ?: Catalog.suspiciousOui(oui)
    }

    override fun equals(other: Any?): Boolean =
        other is MacAddress && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = text

    companion object {
        fun parse(text: String): MacAddress? {
            val parts = text.trim().split(":", "-")
            if (parts.size != 6) return null
            val bytes = ByteArray(6)
            for (index in parts.indices) {
                val value = parts[index].toIntOrNull(16) ?: return null
                if (value !in 0..255) return null
                bytes[index] = value.toByte()
            }
            return MacAddress(bytes)
        }
    }
}
