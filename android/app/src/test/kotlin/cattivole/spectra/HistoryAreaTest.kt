package cattivole.spectra

import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.BleDevice
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.HistoryArea
import cattivole.spectra.core.HistoryEntry
import cattivole.spectra.core.HistoryLine
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.LinkReport
import cattivole.spectra.core.LinkStatus
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.RadioSource
import cattivole.spectra.core.SavedConnection
import cattivole.spectra.core.SecondaryChannel
import cattivole.spectra.core.TrackedNetwork
import cattivole.spectra.core.forArea
import cattivole.spectra.core.holds
import cattivole.spectra.core.only
import cattivole.spectra.core.without
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAreaTest {
    private val check = SavedConnection.from(LinkReport().apply { status = LinkStatus.ONLINE }, viaVpn = false)!!
    private val host = LanDevice(ipAddress = "192.168.1.20", hostnames = listOf("printer"))
    private val network = TrackedNetwork(
        latest = AccessPointReading(
            MacAddress(byteArrayOf(0, 0, 0, 0, 0, 1)), -60, 6, SecondaryChannel.NONE,
            AuthMode.WPA2_PSK, Cipher.CCMP, Cipher.CCMP, 0, null, "Net".encodeToByteArray(),
        ),
        firstSeen = 0,
        lastSeen = 0,
        samples = mutableListOf(),
        source = RadioSource.PHONE,
    )

    private fun lanSession(devices: List<LanDevice> = listOf(host), checks: List<SavedConnection> = listOf(check)) =
        HistoryEntry(
            id = "lan-1", kind = "lan", savedAt = 42, source = "This phone", captureId = null,
            placeKey = "lan:home", placeLabel = "Home", lan = devices, connections = checks,
            latitude = 1.0, longitude = 2.0,
        )

    @Test
    fun aLanSessionShowsItsDevicesOnLanAndItsChecksOnInternet() {
        val session = lanSession()
        assertTrue(session.holds(HistoryArea.LAN))
        assertTrue(session.holds(HistoryArea.INTERNET))
        assertFalse(session.holds(HistoryArea.WIFI))
        assertFalse(session.holds(HistoryArea.BLUETOOTH))

        val lan = session.only(HistoryArea.LAN)!!
        assertEquals(listOf(host), lan.lan)
        assertTrue(lan.connections.isEmpty())
        assertEquals(listOf("lan"), lan.shownKinds())
        assertEquals(1, lan.itemCount)

        val internet = session.only(HistoryArea.INTERNET)!!
        assertTrue(internet.lan.isEmpty())
        assertEquals(listOf(check), internet.connections)
        assertEquals("Internet", internet.rowTitle)
        // Same session underneath: the same id, place and fix, so opening and deleting find it.
        assertEquals("lan-1", internet.id)
        assertEquals("Home", internet.placeLabel)
        assertEquals(1.0, internet.latitude!!, 0.0)
    }

    @Test
    fun aSessionWithNothingOfTheAreaIsLeftOut() {
        val devicesOnly = lanSession(checks = emptyList())
        assertNull(devicesOnly.only(HistoryArea.INTERNET))
        assertNull(devicesOnly.only(HistoryArea.WIFI))
        val bluetooth = HistoryEntry(
            id = "b", kind = "bluetooth", savedAt = 1, source = "This phone", captureId = null,
            bluetooth = listOf(BleDevice("C4:00:00:00:00:01", "Random static", -60, 0)),
        )
        val wifi = HistoryEntry(id = "w", kind = "networks", savedAt = 2, source = "This phone", captureId = null, networks = listOf(network))
        val all = listOf(devicesOnly, lanSession(), bluetooth, wifi)
        assertEquals(listOf("lan-1", "lan-1"), all.forArea(HistoryArea.LAN).map { it.id })
        assertEquals(listOf("lan-1"), all.forArea(HistoryArea.INTERNET).map { it.id })
        assertEquals(listOf("b"), all.forArea(HistoryArea.BLUETOOTH).map { it.id })
        assertEquals(listOf("w"), all.forArea(HistoryArea.WIFI).map { it.id })
    }

    @Test
    fun sensorLinesBelongToTheTabOfTheirKind() {
        val lines = listOf(HistoryLine("Net", "ch 6", "-60"))
        val lora = HistoryEntry(id = "l", kind = "lora", savedAt = 1, source = "Cardputer", captureId = 7, lines = lines)
        assertTrue(lora.holds(HistoryArea.WIFI))
        assertEquals(lines, lora.only(HistoryArea.WIFI)!!.lines)
        assertNull(lora.only(HistoryArea.LAN))
        val ble = HistoryEntry(id = "s", kind = "bluetooth", savedAt = 1, source = "Cardputer", captureId = 8, lines = lines)
        assertTrue(ble.holds(HistoryArea.BLUETOOTH))
        assertFalse(ble.holds(HistoryArea.WIFI))
    }

    @Test
    fun deletingFromOneTabKeepsTheOtherTabsPart() {
        val session = lanSession()
        val noChecks = session.without(HistoryArea.INTERNET)!!
        assertEquals(listOf(host), noChecks.lan)
        assertTrue(noChecks.connections.isEmpty())
        assertEquals("lan-1", noChecks.id)
        val noDevices = session.without(HistoryArea.LAN)!!
        assertTrue(noDevices.lan.isEmpty())
        assertEquals(listOf(check), noDevices.connections)
        // Removing the last part removes the session.
        assertNull(noDevices.without(HistoryArea.INTERNET))
        // Nothing of that tab: the session is handed back untouched.
        assertSame(noChecks, noChecks.without(HistoryArea.INTERNET))
        assertSame(noChecks, noChecks.without(HistoryArea.BLUETOOTH))
    }
}
