package cattivole.spectra.core

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.i8(index: Int): Int = this[index].toInt()

internal fun ByteArray.u16le(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

internal fun ByteArray.u32le(index: Int): Long =
    u8(index).toLong() or
        (u8(index + 1).toLong() shl 8) or
        (u8(index + 2).toLong() shl 16) or
        (u8(index + 3).toLong() shl 24)

internal fun Int.toI8(): Byte = coerceIn(-128, 127).toByte()

internal fun Int.toU8(): Byte = coerceIn(0, 255).toByte()

internal fun Int.clampU16(): Int = coerceIn(0, 0xFFFF)

internal class ByteWriter {
    private val bytes = ArrayList<Byte>()

    fun u8(value: Int) {
        bytes.add(value.toU8())
    }

    fun i8(value: Int) {
        bytes.add(value.toI8())
    }

    fun u16(value: Int) {
        val v = value.clampU16()
        bytes.add((v and 0xFF).toByte())
        bytes.add((v shr 8).toByte())
    }

    fun u32(value: Long) {
        val v = value and 0xFFFF_FFFFL
        bytes.add((v and 0xFF).toByte())
        bytes.add(((v shr 8) and 0xFF).toByte())
        bytes.add(((v shr 16) and 0xFF).toByte())
        bytes.add(((v shr 24) and 0xFF).toByte())
    }

    fun bytes(data: ByteArray) {
        data.forEach { bytes.add(it) }
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

fun hex(data: ByteArray, separator: String = " "): String =
    data.joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }

fun findBytes(needle: ByteArray, haystack: ByteArray): Int? {
    if (needle.isEmpty() || haystack.size < needle.size) return null
    val last = haystack.size - needle.size
    for (index in 0..last) {
        var match = true
        for (offset in needle.indices) {
            if (haystack[index + offset] != needle[offset]) {
                match = false
                break
            }
        }
        if (match) return index
    }
    return null
}
