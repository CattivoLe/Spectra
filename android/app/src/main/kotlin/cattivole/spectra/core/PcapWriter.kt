package cattivole.spectra.core

import kotlin.math.floor

/**
 * Builds a classic `.pcap` file from captured 802.11 frames — the reverse of
 * [PcapParser]. Each frame gets a small radiotap header carrying its channel
 * and signal, so the file is link type 127 and opens the same in Wireshark and
 * in Spectra's own decoder. Mirrors the iOS `PcapWriter`.
 */
object PcapWriter {
    val LINK_TYPE = LinkType.IEEE802_11_RADIOTAP

    /**
     * One frame to write. [bytes] is what was captured (it may be shorter than
     * [originalLength] when the sensor truncated the frame to its snap length).
     * [timestamp] is seconds since the epoch.
     */
    data class Frame(
        val timestamp: Double,
        val channel: Int,
        val rssi: Int,
        val originalLength: Int,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Frame && timestamp == other.timestamp && channel == other.channel &&
                rssi == other.rssi && originalLength == other.originalLength &&
                bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = channel * 31 + bytes.contentHashCode()
    }

    fun pcap(frames: List<Frame>): ByteArray {
        val out = ByteWriter()
        // Global header, little-endian, microsecond timestamps.
        out.u32(0xA1B2C3D4L) // magic
        out.u16(2)           // version major
        out.u16(4)           // version minor
        out.u32(0)           // thiszone
        out.u32(0)           // sigfigs
        out.u32(65535)       // snaplen
        out.u32(LINK_TYPE.raw.toLong())

        for (frame in frames) {
            val radiotap = radiotapHeader(frame.channel, frame.rssi)
            val seconds = floor(frame.timestamp)
            val micros = ((frame.timestamp - seconds) * 1_000_000).toLong()
            val inclLen = radiotap.size + frame.bytes.size
            val origLen = radiotap.size + maxOf(frame.originalLength, frame.bytes.size)
            out.u32(seconds.toLong())
            out.u32(micros)
            out.u32(inclLen.toLong())
            out.u32(origLen.toLong())
            out.bytes(radiotap)
            out.bytes(frame.bytes)
        }
        return out.toByteArray()
    }

    /**
     * A 13-byte radiotap header exposing the channel (frequency + band flags)
     * and the dBm antenna signal — the two fields Spectra knows per frame.
     */
    fun radiotapHeader(channel: Int, rssi: Int): ByteArray {
        val freq = frequencyForChannel(channel)
        val w = ByteWriter()
        w.u8(0); w.u8(0)      // version, pad
        w.u16(13)            // header length
        w.u32(0x28)          // present: bit3 Channel, bit5 dBm antenna signal
        w.u16(freq)
        w.u16(if (freq >= 5000) 0x0100 else 0x0080) // 5 GHz vs 2 GHz
        w.i8(rssi)
        return w.toByteArray()
    }

    private fun frequencyForChannel(channel: Int): Int = when {
        channel == 14 -> 2484
        channel in 1..13 -> 2407 + channel * 5
        channel >= 15 -> 5000 + channel * 5
        else -> 2412
    }
}

/**
 * Reassembles the `0x24` fragments of a capture back into whole frames. BLE
 * delivers notifications in order on one connection, so a run tracks a single
 * in-flight frame and drops a partial if a fragment is lost or arrives out of
 * turn. Mirrors the iOS `RawFrameAssembler`.
 */
class RawFrameAssembler {
    /** A finished frame, ready for [PcapWriter] once a timestamp is set. */
    data class Completed(
        val timestampMicros: Long,
        val channel: Int,
        val rssi: Int,
        val originalLength: Int,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Completed && timestampMicros == other.timestampMicros && channel == other.channel &&
                rssi == other.rssi && originalLength == other.originalLength &&
                bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = channel * 31 + bytes.contentHashCode()
    }

    private var seq = 0
    private var next = 0
    private var count = 0
    private var meta: SensorProtocol.RawFrameFragment? = null
    private var buffer = ByteArray(0)

    /** How many fragments were dropped because one went missing. */
    var dropped = 0
        private set

    fun reset() {
        meta = null
        buffer = ByteArray(0)
        next = 0
        count = 0
    }

    fun ingest(fragment: SensorProtocol.RawFrameFragment): Completed? {
        if (fragment.fragmentIndex == 0) {
            if (meta != null) dropped++ // a previous frame never finished
            seq = fragment.seq
            next = 1
            count = fragment.fragmentCount
            meta = fragment
            buffer = fragment.payload.copyOf()
        } else {
            val current = meta
            if (current == null || fragment.seq != seq ||
                fragment.fragmentIndex != next || fragment.fragmentCount != count
            ) {
                if (meta != null) dropped++
                reset()
                return null
            }
            buffer += fragment.payload
            next++
        }

        val current = meta ?: return null
        if (next != count) return null
        val completed = Completed(
            timestampMicros = current.timestampMicros,
            channel = current.channel,
            rssi = current.rssi,
            originalLength = current.originalLength,
            bytes = buffer,
        )
        reset()
        return completed
    }
}
