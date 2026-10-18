package cattivole.spectra

import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.BleDevice
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.HistoryEntry
import cattivole.spectra.core.HistoryLine
import cattivole.spectra.core.HistoryStore
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.RadioSource
import cattivole.spectra.core.SecondaryChannel
import cattivole.spectra.core.TrackedNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session upsert folds a re-scan into one entry; these cover its merge rules. */
class HistoryMergeTest {
    private fun bssid(last: Int) = MacAddress(byteArrayOf(0, 0, 0, 0, 0, last.toByte()))

    private fun reading(last: Int, rssi: Int) = AccessPointReading(
        bssid(last), rssi, 6, SecondaryChannel.NONE, AuthMode.WPA2_PSK, Cipher.CCMP, Cipher.CCMP,
        0, null, "Net".encodeToByteArray(),
    )

    private fun network(last: Int, first: Long, lastSeen: Long, rssi: Int = -60) = TrackedNetwork(
        latest = reading(last, rssi),
        firstSeen = first,
        lastSeen = lastSeen,
        samples = mutableListOf(TrackedNetwork.Sample(lastSeen, rssi)),
        source = RadioSource.SENSOR,
    )

    private fun ble(address: String, first: Long, last: Long, rssi: Int = -50): BleDevice {
        val device = BleDevice(address, "Resolvable private", rssi, first)
        device.lastSeen = last
        device.samples.add(BleDevice.Sample(0, last, rssi))
        device.packetCount = 1
        return device
    }

    private fun lan(ip: String, first: Long, last: Long, host: String) = LanDevice(
        ipAddress = ip,
        hostnames = listOf(host),
        firstSeen = first,
        lastSeen = last,
    )

    @Test
    fun mergeNetworksUnionsAndKeepsEarliestFirstSeen() {
        val a = listOf(network(1, first = 1_000, lastSeen = 1_000))
        val b = listOf(
            network(1, first = 2_000, lastSeen = 2_000, rssi = -40),
            network(2, first = 2_000, lastSeen = 2_000),
        )
        val merged = HistoryStore.mergeNetworks(a, b)
        assertEquals(2, merged.size)
        val one = merged.first { it.id == bssid(1) }
        assertEquals(1_000L, one.firstSeen)
        assertEquals(2_000L, one.lastSeen)
        assertEquals(-40, one.latest.rssi) // the newer reading wins
        assertEquals(2, one.samples.size)
    }

    @Test
    fun mergeNetworksKeepsOlderReadingWhenIncomingIsStale() {
        val a = listOf(network(1, first = 1_000, lastSeen = 5_000, rssi = -50))
        val b = listOf(network(1, first = 2_000, lastSeen = 2_000, rssi = -80))
        val merged = HistoryStore.mergeNetworks(a, b)
        assertEquals(1, merged.size)
        assertEquals(-50, merged[0].latest.rssi) // the fresher sighting stays representative
        assertEquals(1_000L, merged[0].firstSeen)
        assertEquals(5_000L, merged[0].lastSeen)
    }

    @Test
    fun mergeBluetoothUnionsSamplesAndSeen() {
        val merged = HistoryStore.mergeBle(
            listOf(ble("C4:00:00:00:00:01", first = 1_000, last = 1_000)),
            listOf(ble("C4:00:00:00:00:01", first = 2_000, last = 2_000)),
        )
        assertEquals(1, merged.size)
        assertEquals(1_000L, merged[0].firstSeen)
        assertEquals(2_000L, merged[0].lastSeen)
        assertEquals(2, merged[0].samples.size)
        assertEquals(2, merged[0].packetCount)
    }

    @Test
    fun mergeLanUnionsByIpAndKeepsNewerHostname() {
        val a = listOf(lan("192.168.1.10", first = 100, last = 100, host = "old"))
        val b = listOf(
            lan("192.168.1.10", first = 200, last = 200, host = "new"),
            lan("192.168.1.20", first = 200, last = 200, host = "other"),
        )
        val merged = HistoryStore.mergeLan(a, b)
        assertEquals(2, merged.size)
        val one = merged.first { it.ipAddress == "192.168.1.10" }
        assertEquals(100L, one.firstSeen)
        assertEquals(200L, one.lastSeen)
        assertEquals(listOf("new"), one.hostnames)
    }

    @Test
    fun mergeLinesDedupesByTitle() {
        val existing = listOf(HistoryLine("A", "", ""))
        val incoming = listOf(HistoryLine("A", "x", ""), HistoryLine("B", "", ""))
        assertEquals(listOf("A", "B"), HistoryStore.mergeLines(existing, incoming).map { it.title })
    }

    @Test
    fun wifiAndInternetFromTheSamePlaceBecomeOneSession() {
        val day = 1_700_000_000_000L
        val wifi = HistoryEntry(
            id = "wifi", kind = "networks", savedAt = day, source = "This phone", captureId = null,
            placeKey = "geo:1_2", networks = listOf(network(1, day, day)),
            latitude = 55.7510, longitude = 37.6180,
        )
        val internet = HistoryEntry(
            id = "lan", kind = "lan", savedAt = day + 1_000, source = "This phone", captureId = null,
            placeKey = "lan:gw:aa", lan = listOf(lan("192.168.1.1", day, day, "router")),
            latitude = 55.7512, longitude = 37.6181,
        )
        val joined = HistoryStore.coalesce(listOf(wifi, internet))
        assertEquals(1, joined.size)
        assertEquals(listOf("networks", "lan"), joined[0].shownKinds())
        assertEquals(1, joined[0].networks.size)
        assertEquals(1, joined[0].lan.size)
        assertEquals("lan:gw:aa", joined[0].placeKey)
        assertEquals(2, joined[0].itemCount)
    }

    @Test
    fun aFarVisitAndASensorCaptureStaySeparate() {
        val day = 1_700_000_000_000L
        val here = HistoryEntry(
            id = "here", kind = "networks", savedAt = day, source = "This phone", captureId = null,
            placeKey = "geo:1", latitude = 55.7510, longitude = 37.6180,
            networks = listOf(network(1, day, day)),
        )
        val away = HistoryEntry(
            id = "away", kind = "lan", savedAt = day, source = "This phone", captureId = null,
            placeKey = "lan:gw:bb", latitude = 55.7600, longitude = 37.6180,
            lan = listOf(lan("10.0.0.1", day, day, "other")),
        )
        val sensor = HistoryEntry(
            id = "sensor", kind = "networks", savedAt = day, source = "Spectra-AB", captureId = 9,
            placeKey = "geo:1", latitude = 55.7510, longitude = 37.6180,
            lines = listOf(HistoryLine("Cafe", "", "")),
        )
        assertEquals(3, HistoryStore.coalesce(listOf(here, away, sensor)).size)
    }

    @Test
    fun importSkipsWhatIsAlreadyStored() {
        val day = 1_700_000_000_000L
        val wifi = HistoryEntry(
            id = "wifi", kind = "networks", savedAt = day, source = "This phone", captureId = null,
            networks = listOf(network(1, day, day)),
        )
        val document = HistoryStore.encodeDocument(listOf(wifi))
        val again = HistoryStore.mergeImport(listOf(wifi), document)
        assertEquals(0, again!!.second)
        val fresh = HistoryStore.mergeImport(emptyList(), document)
        assertEquals(1, fresh!!.second)
        assertEquals(1, fresh.first.single().networks.size)
        assertEquals(null, HistoryStore.mergeImport(emptyList(), "not a history"))
    }

    @Test
    fun geoBucketGroupsNearbyFixesAndEmptyWithoutOne() {
        assertEquals("", HistoryStore.geoBucket(null))
        // Two points ~20 m apart land in the same ~55 m bucket.
        val a = HistoryStore.geoBucket(55.7510 to 37.6180)
        val b = HistoryStore.geoBucket(55.7511 to 37.6181)
        assertEquals(a, b)
        assertTrue(a.startsWith("geo:"))
    }
}
