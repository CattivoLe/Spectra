package cattivole.spectra

import cattivole.spectra.core.FirmwareUpdate
import cattivole.spectra.core.MLabNdt7
import cattivole.spectra.core.SpeedServerChoice
import cattivole.spectra.platform.RedirectTracer
import cattivole.spectra.platform.SensorLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What came over from iOS outside the assistant: 5 GHz capture, firmware per chip, M-Lab, redirects. */
class PortedChecksTest {
    @Test
    fun fiveGigahertzCaptureNeedsTheBandAndNewFirmware() {
        assertEquals(SensorLink.CaptureSupport.Ok, SensorLink.captureSupport(6, listOf("2.4"), "0.9.0"))
        assertEquals(SensorLink.CaptureSupport.BandNotHeard, SensorLink.captureSupport(36, listOf("2.4"), "0.10.1"))
        assertEquals(SensorLink.CaptureSupport.FirmwareTooOld("0.10.0"), SensorLink.captureSupport(36, listOf("2.4", "5"), "0.10.0"))
        assertEquals(SensorLink.CaptureSupport.FirmwareTooOld("0.9.12"), SensorLink.captureSupport(149, listOf("2.4", "5"), "0.9.12"))
        assertEquals(SensorLink.CaptureSupport.Ok, SensorLink.captureSupport(36, listOf("2.4", "5"), "0.10.1"))
        assertEquals(SensorLink.CaptureSupport.Ok, SensorLink.captureSupport(36, listOf("2.4", "5"), "0.11.0"))
        assertTrue(SensorLink.compareVersions("0.9.2", "0.10.1") < 0)
    }

    private fun image(chip: Int, tag: String? = null): ByteArray {
        val body = ByteArray(64)
        body[0] = FirmwareUpdate.MAGIC
        body[12] = (chip and 0xFF).toByte()
        body[13] = (chip shr 8).toByte()
        return if (tag == null) body else body + "SPECTRA-FW:$tag\u0000".toByteArray()
    }

    @Test
    fun firmwareNamesItsChipAndVersion() {
        assertEquals("ESP32-S3", FirmwareUpdate.chipName(image(0x0009)))
        assertEquals("ESP32-C5", FirmwareUpdate.chipName(image(0x0017)))
        assertEquals("ESP32", FirmwareUpdate.chipName(image(0x0000)))
        assertNull(FirmwareUpdate.chipName(image(0x0005)))
        assertEquals("0.10.1", FirmwareUpdate.taggedVersion(image(0x0017, "0.10.1")))
        assertNull(FirmwareUpdate.taggedVersion(image(0x0017)))
    }

    @Test
    fun mlabLocateAndMeasurementsAreRead() {
        val json = """
            {"results":[{"machine":"mlab1-beg01.mlab-oti.measurement-lab.org","location":{"city":"Belgrade","country":"RS"},
            "urls":{"wss:///ndt/v7/download":"wss://ndt-mlab1-beg01.mlab-oti.measurement-lab.org/ndt/v7/download?access_token=x",
            "wss:///ndt/v7/upload":"wss://ndt-mlab1-beg01.mlab-oti.measurement-lab.org/ndt/v7/upload?access_token=y"}},
            {"machine":"broken"}]}
        """.trimIndent()
        val servers = MLabNdt7.parseLocate(json)
        assertEquals(1, servers.size)
        assertEquals("Belgrade", servers.first().city)
        assertEquals("mlab1-beg01", servers.first().shortName)
        assertEquals("ndt-mlab1-beg01.mlab-oti.measurement-lab.org", servers.first().host)
        assertEquals(5000L, MLabNdt7.serverReceived("""{"TCPInfo":{"BytesReceived":4000},"AppInfo":{"NumBytes":5000}}"""))
        assertNull(MLabNdt7.serverReceived("""{"Origin":"server"}"""))
        assertEquals(SpeedServerChoice.MLab("m1"), SpeedServerChoice.fromRaw(SpeedServerChoice.MLab("m1").raw))
        assertEquals(SpeedServerChoice.MLab(null), SpeedServerChoice.fromRaw("mlab"))
        assertEquals(SpeedServerChoice.Cloudflare, SpeedServerChoice.fromRaw(null))
    }

    @Test
    fun redirectsCompareAddressesLoosely() {
        assertTrue(RedirectTracer.sameAddress("https://Example.com/", "https://example.com"))
        assertTrue(RedirectTracer.sameAddress("HTTPS://example.com/a", "https://example.com/a/"))
        assertFalse(RedirectTracer.sameAddress("https://example.com/a", "https://example.com/b"))
    }
}

class PingHeaderTest {
    @Test
    fun aBoundPingHeaderIsNotTheHop() {
        val hop = cattivole.spectra.core.LinkParse.pingOutput(
            "PING 1.1.1.1 (1.1.1.1) from 192.168.1.114 wlan0: 56(84) bytes of data.\nFrom 172.31.255.6: icmp_seq=1 Time to live exceeded\n",
        )
        assertEquals("172.31.255.6", hop.address)
        assertFalse(hop.reached)
        val silent = cattivole.spectra.core.LinkParse.pingOutput("PING 1.1.1.1 (1.1.1.1) from 192.168.1.114 wlan0: 56(84) bytes of data.\n")
        assertNull(silent.address)
        val end = cattivole.spectra.core.LinkParse.pingOutput(
            "PING 1.1.1.1 (1.1.1.1) from 192.168.1.114 wlan0: 56(84) bytes of data.\n64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=7.37 ms\n",
        )
        assertEquals("1.1.1.1", end.address)
        assertTrue(end.reached)
        assertEquals(7, end.millis)
    }
}
