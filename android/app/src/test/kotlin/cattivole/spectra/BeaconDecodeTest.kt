package cattivole.spectra

import cattivole.spectra.core.BeaconDecode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconDecodeTest {
    @Test
    fun eddystoneUid() {
        val data = byteArrayOf(
            0x00, 0xEE.toByte(), // frame + tx power (-18 dBm)
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, // namespace
            0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, // instance
        )
        val info = BeaconDecode.decode("FEAA", data)
        assertEquals("Eddystone-UID", info?.title)
        assertTrue(info?.detail?.contains("0102030405060708090A") == true)
        assertTrue(info?.detail?.contains("0B0C0D0E0F10") == true)
        assertTrue(info?.detail?.contains("-18 dBm") == true)
    }

    @Test
    fun eddystoneUrl() {
        // https://www. + "example" + .com/
        val data = byteArrayOf(0x10, 0xEE.toByte(), 0x01) + "example".toByteArray() + byteArrayOf(0x00)
        val info = BeaconDecode.decode("FEAA", data)
        assertEquals("Eddystone-URL", info?.title)
        assertTrue(info?.detail?.startsWith("https://www.example.com/") == true)
    }

    @Test
    fun eddystoneTlm() {
        val data = byteArrayOf(
            0x20, 0x00,
            0x0B, 0xB8.toByte(), // 3000 mV
            0x19, 0x00, // 25.0 °C (0x1900 / 256)
            0x00, 0x00, 0x04, 0x00, // 1024 advertisements
            0x00, 0x00, 0x00, 0x64, // 100 -> 10.0 s uptime
        )
        val info = BeaconDecode.decode("FEAA", data)
        assertEquals("Eddystone-TLM", info?.title)
        assertTrue(info?.detail?.contains("3000 mV") == true)
        assertTrue(info?.detail?.contains("25.0 °C") == true)
        assertTrue(info?.detail?.contains("1024 adv") == true)
    }

    @Test
    fun fastPairModel() {
        val info = BeaconDecode.decode("FE2C", byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()))
        assertEquals("Fast Pair", info?.title)
        assertEquals("model 0xAABBCC", info?.detail)
    }

    @Test
    fun exposureAndTile() {
        assertEquals("Exposure Notification", BeaconDecode.decode("FD6F", byteArrayOf(0x01))?.title)
        assertEquals("Tile", BeaconDecode.decode("FEED", byteArrayOf(0x01))?.title)
    }

    @Test
    fun unknownIsNull() {
        assertNull(BeaconDecode.decode("1234", byteArrayOf(0x01)))
    }

    @Test
    fun longFormUuidStillMatches() {
        // The UI passes the expanded Bluetooth-base UUID; it must canonicalise back to FEAA.
        val info = BeaconDecode.decode("0000FEAA-0000-1000-8000-00805F9B34FB", byteArrayOf(0x30, 0x00))
        assertEquals("Eddystone-EID", info?.title)
    }
}
