package cattivole.spectra

import cattivole.spectra.core.LinkType
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.PacketDissector
import cattivole.spectra.core.PcapParser
import cattivole.spectra.core.PcapWriter
import cattivole.spectra.core.RawFrameAssembler
import cattivole.spectra.core.SensorProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCaptureTest {
    private fun beacon(ssid: String): ByteArray {
        val name = ssid.encodeToByteArray()
        return byteArrayOf(0x80.toByte(), 0, 0, 0) +
            byteArrayOf(-1, -1, -1, -1, -1, -1) +
            byteArrayOf(0x02, 0x00, 0x5E, 0x10, 0x00, 0x01) +
            byteArrayOf(0x02, 0x00, 0x5E, 0x10, 0x00, 0x01) +
            byteArrayOf(0, 0) + ByteArray(8) + byteArrayOf(0x64, 0) + byteArrayOf(0x01, 0x04) +
            byteArrayOf(0x00, name.size.toByte()) + name
    }

    @Test
    fun captureCommands() {
        assertArrayEquals(
            byteArrayOf(0x0A, 6, 0, 0, 0, 0, 0, 0, 0),
            SensorProtocol.Command.StartRawCapture(6, null).data,
        )
        assertArrayEquals(
            byteArrayOf(0x0A, 11, 1, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x01, 0x02, 0x03),
            SensorProtocol.Command.StartRawCapture(11, MacAddress(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x01, 0x02, 0x03))).data,
        )
        assertArrayEquals(byteArrayOf(0x0B), SensorProtocol.Command.StopRawCapture.data)
    }

    @Test
    fun rawFrameFragmentRoundTrips() {
        val fragment = SensorProtocol.RawFrameFragment(
            seq = 258, fragmentIndex = 0, fragmentCount = 1,
            timestampMicros = 123_456_789L, channel = 6, rssi = -58,
            originalLength = 4, payload = byteArrayOf(0x80.toByte(), 0x00, 0x11, 0x22),
        )
        val decoded = SensorProtocol.decode(SensorProtocol.encode(SensorProtocol.Message.RawFrame(fragment)))
        assertEquals(SensorProtocol.Message.RawFrame(fragment), decoded)
    }

    @Test
    fun rawFrameAssemblerStitchesFragments() {
        val assembler = RawFrameAssembler()
        val whole = ByteArray(120) { it.toByte() }
        val head = SensorProtocol.RawFrameFragment(42, 0, 2, 9_000L, 3, -70, whole.size, whole.copyOfRange(0, 80))
        val tail = SensorProtocol.RawFrameFragment(42, 1, 2, 0, 0, 0, 0, whole.copyOfRange(80, 120))
        assertNull(assembler.ingest(head))
        val completed = assembler.ingest(tail)!!
        assertArrayEquals(whole, completed.bytes)
        assertEquals(3, completed.channel)
        assertEquals(-70, completed.rssi)
        assertEquals(120, completed.originalLength)
        assertEquals(0, assembler.dropped)
    }

    @Test
    fun rawFrameAssemblerDropsBrokenFrame() {
        val assembler = RawFrameAssembler()
        assembler.ingest(SensorProtocol.RawFrameFragment(1, 0, 2, 0, 1, -40, 8, byteArrayOf(1, 2, 3, 4)))
        val stray = SensorProtocol.RawFrameFragment(2, 1, 2, 0, 0, 0, 0, byteArrayOf(9))
        assertNull(assembler.ingest(stray))
        assertEquals(1, assembler.dropped)
    }

    @Test
    fun pcapRoundTripsThroughParser() {
        val frames = listOf(
            PcapWriter.Frame(1_700_000_000.5, 6, -42, 0, beacon("Alpha")).let {
                PcapWriter.Frame(it.timestamp, it.channel, it.rssi, it.bytes.size, it.bytes)
            },
            PcapWriter.Frame(1_700_000_001.25, 11, -77, 0, beacon("Beta")).let {
                PcapWriter.Frame(it.timestamp, it.channel, it.rssi, it.bytes.size, it.bytes)
            },
        )
        val parsed = PcapParser.parse(PcapWriter.pcap(frames))
        assertEquals(2, parsed.packets.size)
        val first = parsed.packets[0]
        assertEquals(LinkType.IEEE802_11_RADIOTAP, first.linkType)
        val radiotapLen = first.data[2].toInt() and 0xFF or ((first.data[3].toInt() and 0xFF) shl 8)
        assertEquals(13, radiotapLen)
        assertArrayEquals(beacon("Alpha"), first.data.copyOfRange(radiotapLen, first.data.size))
        val info = PacketDissector.dissect(first.linkType, first.data)
        assertTrue(info.info.contains("beacon", ignoreCase = true))
    }

    @Test
    fun truncatedFrameKeepsOriginalLength() {
        val captured = beacon("Trunc")
        val parsed = PcapParser.parse(PcapWriter.pcap(listOf(PcapWriter.Frame(10.0, 1, -50, 300, captured))))
        val packet = parsed.packets.first()
        assertEquals(13 + captured.size, packet.data.size)
        assertEquals(13 + 300, packet.originalLength)
    }

    @Test
    fun radiotapHeaderEncodesChannelAndSignal() {
        val header = PcapWriter.radiotapHeader(6, -58)
        assertEquals(13, header.size)
        val freq = (header[8].toInt() and 0xFF) or ((header[9].toInt() and 0xFF) shl 8)
        assertEquals(2437, freq)
        assertEquals(-58, header[12].toInt())
    }
}
