package cattivole.spectra

import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.AppleAdvertisement
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.BleAdvertisement
import cattivole.spectra.core.BleDevice
import cattivole.spectra.core.BleFields
import cattivole.spectra.core.Capabilities
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.DeauthEvent
import cattivole.spectra.core.DiscoveredPort
import cattivole.spectra.core.ExternalAddress
import cattivole.spectra.core.HistoryCodec
import cattivole.spectra.core.HistoryEntry
import cattivole.spectra.core.HistoryStore
import cattivole.spectra.core.LinkReport
import cattivole.spectra.core.LinkStatus
import cattivole.spectra.core.SavedConnection
import cattivole.spectra.core.SpeedSample
import cattivole.spectra.core.HttpBanner
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.LanProfile
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.PhoneWifi
import cattivole.spectra.core.ProbingDevice
import cattivole.spectra.core.RadioSource
import cattivole.spectra.core.SecondaryChannel
import cattivole.spectra.core.ServiceAdvertisement
import cattivole.spectra.core.TrackedNetwork
import cattivole.spectra.core.TrafficSnapshot
import cattivole.spectra.core.UPnPIdentity
import cattivole.spectra.core.WifiClient
import cattivole.spectra.core.WindowsHost
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private val bssid = MacAddress(byteArrayOf(0x50, 0xC7.toByte(), 0xBF.toByte(), 0x1E, 0x22, 0xA1.toByte()))
    private val phone = MacAddress(byteArrayOf(0x7A, 0x11, 0x22, 0x33, 0x44, 0x55))

    @Test
    fun savedNetworkIsASnapshotWithEverythingTheCardShows() {
        val reading = AccessPointReading(
            bssid, -61, 11, SecondaryChannel.BELOW, AuthMode.WPA2_WPA3_PSK, Cipher.CCMP, Cipher.TKIP,
            Capabilities.N or Capabilities.WPS, "RU", "Кафе ☕".encodeToByteArray(),
        )
        val live = TrackedNetwork(
            latest = reading,
            firstSeen = 1_000,
            lastSeen = 9_000,
            samples = mutableListOf(TrackedNetwork.Sample(1_000, -70), TrackedNetwork.Sample(9_000, -61)),
            missedScans = 1,
            clients = mutableListOf(WifiClient(phone, bssid, -55, true)),
            revealedSsid = null,
            traffic = TrafficSnapshot(123_456, 789),
            source = RadioSource.SENSOR,
        )
        val saved = HistoryCodec.network(JSONObject(HistoryCodec.network(live).toString()))!!
        // The live record goes on changing; the saved one must not.
        live.samples.add(TrackedNetwork.Sample(10_000, -40))
        live.clients.clear()
        assertEquals(2, saved.samples.size)
        assertEquals(reading, saved.latest)
        assertEquals("Кафе ☕", saved.latest.ssid)
        assertEquals(1, saved.clients.size)
        assertEquals(phone, saved.clients[0].mac)
        assertEquals(TrafficSnapshot(123_456, 789), saved.traffic)
        assertEquals(1, saved.missedScans)
        assertEquals(9_000, saved.lastSeen)
    }

    @Test
    fun phoneReadingKeepsItsCentreChannel() {
        val wide = PhoneWifi.reading(bssid.text, "Five", -50, 5180, "[WPA2-PSK-CCMP]", 80, centreFrequencyMHz = 5210)!!
        assertEquals(42.0, wide.centreChannel!!, 0.001)
        assertEquals(34.0, wide.occupiedChannels.start, 0.001)
        assertEquals(50.0, wide.occupiedChannels.endInclusive, 0.001)
        val live = TrackedNetwork(wide, 0, 0, mutableListOf(), source = RadioSource.PHONE)
        val back = HistoryCodec.network(JSONObject(HistoryCodec.network(live).toString()))!!
        assertEquals(wide, back.latest)
        assertEquals(RadioSource.PHONE, back.source)
    }

    @Test
    fun probesAndDeauthsRoundTrip() {
        val probe = ProbingDevice(phone, -66, mutableListOf("Home", "Office"), 6)
        val back = HistoryCodec.probe(JSONObject(HistoryCodec.probe(probe).toString()))!!
        assertEquals(probe, back)
        val event = DeauthEvent(phone, MacAddress(ByteArray(6) { 0xFF.toByte() }), bssid, 7, -70, 6, 12)
        assertEquals(event, HistoryCodec.deauth(JSONObject(HistoryCodec.deauth(event).toString())))
    }

    @Test
    fun savedHostKeepsPortsServicesAndProfile() {
        val host = LanDevice(
            ipAddress = "192.168.1.20",
            macAddress = bssid,
            hostnames = listOf("Living-Room-TV"),
            openPorts = listOf(DiscoveredPort(80, "HTTP"), DiscoveredPort(554, "RTSP")),
            services = listOf(ServiceAdvertisement("_airplay._tcp", "Living Room", "AppleTV11,1")),
            portsScanned = true,
            isGateway = false,
            profile = LanProfile(
                upnp = UPnPIdentity(friendlyName = "Living Room", manufacturer = "Sony", model = "Bravia"),
                banners = listOf(HttpBanner(80, false, 200, "nginx", null, "Bravia", null)),
                windows = WindowsHost("TV", "WORKGROUP", listOf("media"), true),
            ),
            firstSeen = 5,
            lastSeen = 6,
        )
        val back = HistoryCodec.lan(JSONObject(HistoryCodec.lan(host).toString()))!!
        assertEquals(host.ipAddress, back.ipAddress)
        assertEquals(host.macAddress, back.macAddress)
        assertEquals(host.hostnames, back.hostnames)
        assertEquals(host.openPorts, back.openPorts)
        assertEquals(host.services, back.services)
        assertEquals(host.profile, back.profile)
        assertEquals(host.displayName, back.displayName)
        assertEquals(5L, back.firstSeen)
    }

    @Test
    fun savedBluetoothDeviceIsReadAgainFromItsAdvertisement() {
        // AirPods Pro proximity pairing: left 80%, right 90%, case 50% charging.
        val airpods = byteArrayOf(0x4C, 0x00, 0x07, 0x06, 0x01, 0x0E, 0x20, 0x00, 0x98.toByte(), 0x45)
        val device = BleDevice("C4:11:22:33:44:55", "Resolvable private", -60, 1_000)
        device.absorb(BleAdvertisement.parse(BleFields(manufacturerData = airpods, isConnectable = true)), null, -60, 1_000)
        device.absorb(BleAdvertisement.parse(BleFields(serviceUuids = listOf("0000180f-0000-1000-8000-00805f9b34fb"))), null, -58, 2_000)
        val back = HistoryCodec.ble(JSONObject(HistoryCodec.ble(device).toString()))!!
        assertEquals("AirPods Pro", back.displayName)
        assertEquals(device.subtitle, back.subtitle)
        assertEquals(device.address, back.address)
        assertEquals(device.addressKind, back.addressKind)
        assertEquals(true, back.isConnectable)
        assertEquals(device.services, back.services)
        assertArrayEquals(device.manufacturerPayload, back.manufacturerPayload)
        assertEquals(device.samples.map { it.rssi }, back.samples.map { it.rssi })
        assertEquals(device.lastSeen, back.lastSeen)
        assertEquals(device.packetCount, back.packetCount)
    }

    @Test
    fun appleMessagesAndServicesAccumulateAcrossPackets() {
        val device = BleDevice("C4:00:00:00:00:01", "Resolvable private", -60, 0)
        val findMy = byteArrayOf(0x4C, 0x00, 0x12, 0x02, 0x00, 0x00)
        val nearby = byteArrayOf(0x4C, 0x00, 0x10, 0x02, 0x0B, 0x00)
        device.absorb(BleAdvertisement.parse(BleFields(manufacturerData = findMy)), null, -60, 0)
        device.absorb(BleAdvertisement.parse(BleFields(manufacturerData = nearby)), null, -61, 1_000)
        assertTrue(device.apple.any { it is AppleAdvertisement.FindMy })
        assertTrue(device.apple.any { it is AppleAdvertisement.NearbyInfoMessage })
        // A name stays once heard, even when the next packet has none.
        device.absorb(BleAdvertisement.parse(BleFields(localName = "Keyboard")), null, -61, 2_000)
        device.absorb(BleAdvertisement.parse(BleFields()), null, -61, 3_000)
        assertEquals("Keyboard", device.displayName)
        assertTrue(device.hasOwnName)
    }

    @Test
    fun oldEntriesWithOnlyLinesStillLoad() {
        val json = JSONObject(
            """{"id":"a","kind":"lan","savedAt":42,"source":"Spectra-1A2B","captureId":7,
               "lines":[{"title":"router","detail":"192.168.1.1","trailing":""}]}""",
        )
        val entry = HistoryStore.decodeEntry(json)
        assertEquals(1, entry.itemCount)
        assertEquals(7L, entry.captureId)
        assertEquals("router", entry.lines.single().title)
        assertTrue(!entry.hasRecords)
        assertTrue(entry.connections.isEmpty())
    }

    @Test
    fun connectionCheckRoundTripsOntoTheLanEntry() {
        val report = LinkReport().apply {
            status = LinkStatus.ONLINE
            external = ExternalAddress(ip = "203.0.113.8", city = "Berlin")
            speed = SpeedSample(latencyMs = 12, downBps = 8_000_000.0, upBps = null)
        }
        val saved = SavedConnection.from(report, viaVpn = true)!!
        val again = SavedConnection.fromJson(saved.toJson())
        assertEquals(saved, again)
        val json = JSONObject(
            """{"id":"a","kind":"lan","savedAt":42,"source":"This phone","connections":[${saved.toJson()}]}""",
        )
        val entry = HistoryStore.decodeEntry(json)
        assertEquals(1, entry.connections.size)
        assertEquals("203.0.113.8", entry.connections[0].external?.ip)
        assertTrue(entry.connections[0].viaVpn)
        val merged = HistoryStore.mergeConnections(entry.connections, listOf(saved.copy(checkedAt = saved.checkedAt + 1)))
        assertEquals(2, merged.size)
        assertEquals(saved.checkedAt + 1, merged[0].checkedAt)
    }

    @Test
    fun historyQueryMatchesThePlaceAndTheSource() {
        val entry = HistoryEntry(
            id = "a",
            kind = "wifi",
            savedAt = 1,
            source = "Cardputer",
            captureId = null,
            placeLabel = "Kitchen",
        )
        assertTrue(entry.matchesQuery("kit"))
        assertTrue(entry.matchesQuery("card"))
        assertFalse(entry.matchesQuery("office"))
        assertTrue(entry.matchesQuery("  "))
    }

    @Test
    fun russianStringsKeepTheirPlaceholders() {
        val stream = javaClass.classLoader!!.getResourceAsStream("spectra/strings-ru.tsv")
        assertNotNull(stream)
        val format = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdf%]""")
        stream!!.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val (english, russian) = line.split('\t', limit = 2)
            assertEquals(line, format.findAll(english).map { it.value }.sorted().toList(), format.findAll(russian).map { it.value }.sorted().toList())
        }
    }
}
