package cattivole.spectra

import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.Capabilities
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.Exporter
import cattivole.spectra.core.HistoryEntry
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.RadioSource
import cattivole.spectra.core.SecondaryChannel
import cattivole.spectra.core.TrackedNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExporterTest {
    private fun ap(bytes: ByteArray, ssid: String, rssi: Int, channel: Int, auth: AuthMode, cipher: Cipher = Cipher.CCMP) =
        AccessPointReading(
            MacAddress(bytes), rssi, channel, SecondaryChannel.NONE, auth, cipher, cipher,
            Capabilities.N, null, ssid.encodeToByteArray(),
        )

    private fun network(reading: AccessPointReading) =
        TrackedNetwork(reading, 0, 0, mutableListOf(), source = RadioSource.SENSOR)

    private fun locatedEntry() = HistoryEntry(
        id = "e1", kind = "networks", savedAt = 1_700_000_000_000L, source = "Sensor", captureId = null,
        networks = listOf(
            network(ap(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x11, 0x22, 0x33), "HomeNet", -55, 6, AuthMode.WPA2_PSK)),
            network(ap(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x00, 0x01), "Open Café", -70, 11, AuthMode.OPEN)),
        ),
        latitude = 55.751244, longitude = 37.618423,
    )

    @Test
    fun wigleHasHeaderAndWifiRows() {
        val lines = Exporter.wigle(listOf(locatedEntry())).trim().split("\n")
        assertTrue(lines[0].startsWith("WigleWifi-1.6"))
        assertEquals("MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type", lines[1])
        assertTrue(lines.any { it.contains("AA:BB:CC:11:22:33") && it.contains("HomeNet") && it.contains("[WPA2-PSK-CCMP][ESS]") && it.endsWith("WIFI") })
        assertTrue(lines.any { it.contains("55.7512440,37.6184230") })
    }

    @Test
    fun wigleSkipsUnlocatedEntries() {
        val entry = locatedEntry().apply { latitude = null; longitude = null }
        assertEquals(2, Exporter.wigle(listOf(entry)).trim().split("\n").size)
    }

    @Test
    fun csvEscapesCommas() {
        val entry = HistoryEntry(
            id = "e2", kind = "networks", savedAt = 0, source = "s", captureId = null,
            networks = listOf(network(ap(byteArrayOf(1, 2, 3, 4, 5, 6), "a,b", -40, 1, AuthMode.OPEN))),
        )
        val csv = Exporter.csv(listOf(entry))
        assertTrue(csv.contains("kind,saved_at,source,latitude,longitude"))
        assertTrue(csv.contains("\"a,b\""))
    }

    @Test
    fun kmlPlacemarksOnlyForLocated() {
        val unlocated = locatedEntry().apply { latitude = null; longitude = null }
        val kml = Exporter.kml(listOf(locatedEntry(), unlocated))
        assertTrue(kml.contains("<kml"))
        assertEquals(1, kml.split("<Placemark>").size - 1)
        assertTrue(kml.contains("37.6184230,55.7512440,0"))
    }
}
