package cattivole.spectra.core

import kotlin.math.pow

/**
 * A libpcap link-layer type, as stored in a `.pcap` global header or a
 * `.pcapng` interface block. Only the ones Spectra dissects are named; the rest
 * still parse and count, they just show as "link type N". Mirrors the iOS
 * `LinkType`.
 */
@JvmInline
value class LinkType(val raw: Int) {
    val displayName: String
        get() = when (raw) {
            0 -> "BSD loopback"
            1 -> "Ethernet"
            101, 228, 229 -> "Raw IP"
            105 -> "802.11"
            127 -> "802.11 + radiotap"
            113, 276 -> "Linux cooked"
            else -> "link type $raw"
        }

    companion object {
        val NULL = LinkType(0)
        val ETHERNET = LinkType(1)
        val RAW = LinkType(101)
        val IEEE802_11 = LinkType(105)
        val LINUX_SLL = LinkType(113)
        val IEEE802_11_RADIOTAP = LinkType(127)
        val IPV4 = LinkType(228)
        val IPV6 = LinkType(229)
        val LINUX_SLL2 = LinkType(276)
    }
}

/** One packet lifted out of the file. [timestamp] is seconds since the epoch. */
class CapturedPacket(
    val timestamp: Double?,
    val originalLength: Int,
    val data: ByteArray,
    val linkType: LinkType,
)

enum class CaptureFormat(val label: String) { PCAP("pcap"), PCAPNG("pcapng") }

class CaptureFile(val format: CaptureFormat, val packets: List<CapturedPacket>)

enum class PcapError { TOO_SHORT, NOT_A_CAPTURE, TRUNCATED }

class PcapException(val kind: PcapError) : Exception(kind.name)

/**
 * Parses classic `.pcap` and the newer `.pcapng` into a flat list of packets.
 * Every read is bounds-checked, so a truncated or hostile file fails with a
 * [PcapException] (or returns what parsed cleanly) rather than crashing.
 */
object PcapParser {
    fun parse(data: ByteArray): CaptureFile {
        if (data.size < 4) throw PcapException(PcapError.TOO_SHORT)
        val first = data.read32(0, big = true)
        return when (first) {
            0xA1B2C3D4L, 0xD4C3B2A1L, 0xA1B23C4DL, 0x4D3CB2A1L -> parsePcap(data)
            0x0A0D0D0AL -> parsePcapng(data)
            else -> throw PcapException(PcapError.NOT_A_CAPTURE)
        }
    }

    private fun parsePcap(b: ByteArray): CaptureFile {
        if (b.size < 24) throw PcapException(PcapError.TOO_SHORT)
        val magic = listOf(b.u8(0), b.u8(1), b.u8(2), b.u8(3))
        val big: Boolean
        val nanos: Boolean
        when (magic) {
            listOf(0xA1, 0xB2, 0xC3, 0xD4) -> { big = true; nanos = false }
            listOf(0xD4, 0xC3, 0xB2, 0xA1) -> { big = false; nanos = false }
            listOf(0xA1, 0xB2, 0x3C, 0x4D) -> { big = true; nanos = true }
            listOf(0x4D, 0x3C, 0xB2, 0xA1) -> { big = false; nanos = true }
            else -> throw PcapException(PcapError.NOT_A_CAPTURE)
        }
        val linkType = LinkType(b.read32(20, big).toInt())
        val tickScale = if (nanos) 1_000_000_000.0 else 1_000_000.0
        val packets = ArrayList<CapturedPacket>()
        var o = 24
        while (o + 16 <= b.size) {
            val tsSec = b.read32(o, big)
            val tsFrac = b.read32(o + 4, big)
            val capLen = b.read32(o + 8, big).toInt()
            val origLen = b.read32(o + 12, big).toInt()
            o += 16
            if (capLen < 0 || o + capLen > b.size) break // truncated tail: keep what we have
            val data = b.copyOfRange(o, o + capLen)
            o += capLen
            val stamp = if (tsSec == 0L) null else tsSec.toDouble() + tsFrac.toDouble() / tickScale
            packets.add(CapturedPacket(stamp, origLen, data, linkType))
        }
        return CaptureFile(CaptureFormat.PCAP, packets)
    }

    private fun parsePcapng(b: ByteArray): CaptureFile {
        val packets = ArrayList<CapturedPacket>()
        val interfaces = ArrayList<Pair<LinkType, Double>>() // link type, ticks per second
        var big = false
        var offset = 0

        while (offset + 12 <= b.size) {
            val blockType = b.read32(offset, big)
            if (blockType == 0x0A0D0D0AL) { // Section Header Block resets endianness
                val bom = b.read32(offset + 8, big = false)
                big = (bom == 0x4D3C2B1AL)
                interfaces.clear()
            }
            val total = b.read32(offset + 4, big).toInt()
            if (total < 12 || offset + total > b.size) break
            val bodyStart = offset + 8
            val bodyEnd = offset + total - 4

            when (blockType) {
                0x00000001L -> { // Interface Description Block
                    val link = LinkType(b.read16(bodyStart, big))
                    val ticks = timestampTicks(b, bodyStart + 8, bodyEnd, big)
                    interfaces.add(link to ticks)
                }
                0x00000006L -> { // Enhanced Packet Block
                    val interfaceId = b.read32(bodyStart, big).toInt()
                    val tsHigh = b.read32(bodyStart + 4, big)
                    val tsLow = b.read32(bodyStart + 8, big)
                    val capLen = b.read32(bodyStart + 12, big).toInt()
                    val origLen = b.read32(bodyStart + 16, big).toInt()
                    val dataStart = bodyStart + 20
                    if (capLen in 0..(bodyEnd - dataStart)) {
                        val iface = interfaces.getOrNull(interfaceId)
                        val link = iface?.first ?: LinkType.ETHERNET
                        val ticks = iface?.second ?: 1_000_000.0
                        val raw = (tsHigh shl 32) or (tsLow and 0xFFFFFFFFL)
                        val stamp = if (raw == 0L) null else raw.toDouble() / ticks
                        packets.add(CapturedPacket(stamp, origLen, b.copyOfRange(dataStart, dataStart + capLen), link))
                    }
                }
                0x00000003L -> { // Simple Packet Block
                    val origLen = b.read32(bodyStart, big).toInt()
                    val dataStart = bodyStart + 4
                    val capLen = minOf(origLen, bodyEnd - dataStart)
                    if (capLen in 0..(bodyEnd - dataStart)) {
                        val link = interfaces.firstOrNull()?.first ?: LinkType.ETHERNET
                        packets.add(CapturedPacket(null, origLen, b.copyOfRange(dataStart, dataStart + capLen), link))
                    }
                }
            }
            offset += total
        }
        if (packets.isEmpty() && interfaces.isEmpty()) throw PcapException(PcapError.NOT_A_CAPTURE)
        return CaptureFile(CaptureFormat.PCAPNG, packets)
    }

    /** Reads the `if_tsresol` option (code 9) for ticks per second, default 1e6. */
    private fun timestampTicks(b: ByteArray, optionsStart: Int, optionsEnd: Int, big: Boolean): Double {
        var offset = optionsStart
        while (offset + 4 <= optionsEnd) {
            val code = b.read16(offset, big)
            val length = b.read16(offset + 2, big)
            val valueStart = offset + 4
            if (code == 0) break
            if (code == 9 && length >= 1 && valueStart < optionsEnd) {
                val raw = b.u8(valueStart)
                return if (raw and 0x80 == 0) 10.0.pow(raw) else 2.0.pow(raw and 0x7F)
            }
            offset = valueStart + length + ((4 - length % 4) % 4) // 32-bit padding
        }
        return 1_000_000.0
    }
}

// Bounds-checked reads shared by the parser and the dissector.

internal fun ByteArray.be16(o: Int): Int =
    if (o < 0 || o + 2 > size) 0 else (u8(o) shl 8) or u8(o + 1)

internal fun ByteArray.be32(o: Int): Long =
    if (o < 0 || o + 4 > size) 0L
    else (u8(o).toLong() shl 24) or (u8(o + 1).toLong() shl 16) or (u8(o + 2).toLong() shl 8) or u8(o + 3).toLong()

internal fun ByteArray.read16(o: Int, big: Boolean): Int =
    if (o < 0 || o + 2 > size) 0 else if (big) be16(o) else u16le(o)

internal fun ByteArray.read32(o: Int, big: Boolean): Long =
    if (o < 0 || o + 4 > size) 0L else if (big) be32(o) else u32le(o)
