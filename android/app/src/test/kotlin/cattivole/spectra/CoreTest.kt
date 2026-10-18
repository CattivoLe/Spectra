package cattivole.spectra

import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.Capabilities
import cattivole.spectra.core.Catalog
import cattivole.spectra.core.ChannelAdvisor
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.DeauthEvent
import cattivole.spectra.core.FirmwareUpdate
import cattivole.spectra.core.GattValue
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.LanIdentity
import cattivole.spectra.core.LanProfile
import cattivole.spectra.core.LoraDevice
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.NetworkStore
import cattivole.spectra.core.NotableDevice
import cattivole.spectra.core.PhoneWifi
import cattivole.spectra.core.ProbeRequest
import cattivole.spectra.core.RadioSource
import cattivole.spectra.core.SecondaryChannel
import cattivole.spectra.core.SensorInfo
import cattivole.spectra.core.HostIdentity
import cattivole.spectra.core.SensorProtocol
import cattivole.spectra.core.ServiceAdvertisement
import cattivole.spectra.core.UPnPIdentity
import cattivole.spectra.core.WifiClient
import cattivole.spectra.core.WindowsHost
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val reading = AccessPointReading(
        bssid = MacAddress(byteArrayOf(0x50, 0xC7.toByte(), 0xBF.toByte(), 0x1E, 0x22, 0xA1.toByte())),
        rssi = -67,
        channel = 11,
        secondaryChannel = SecondaryChannel.BELOW,
        auth = AuthMode.WPA2_WPA3_PSK,
        pairwiseCipher = Cipher.CCMP,
        groupCipher = Cipher.CCMP,
        capabilities = Capabilities.B or Capabilities.G or Capabilities.N or Capabilities.AX or Capabilities.WPS,
        countryCode = "RU",
        ssidBytes = "Кафе ☕".encodeToByteArray(),
    )

    @Test
    fun accessPointRoundTrips() {
        val data = SensorProtocol.encode(SensorProtocol.Message.AccessPoint(513, reading))
        assertEquals(19 + reading.ssidBytes.size, data.size)
        assertEquals(SensorProtocol.Message.AccessPoint(513, reading), SensorProtocol.decode(data))
    }

    @Test
    fun decodesFirmwareLayout() {
        val bytes = byteArrayOf(
            0x11, 0x07, 0x00,
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x01, 0x02, 0x03,
            0xB5.toByte(),
            0x06, 0x00,
            0x00, 0x00, 0x00,
            0x03,
            0x00, 0x00,
            0x00,
        )
        val message = SensorProtocol.decode(bytes) as SensorProtocol.Message.AccessPoint
        assertEquals(7, message.scanId)
        assertEquals("AA:BB:CC:01:02:03", message.reading.bssid.text)
        assertEquals(-75, message.reading.rssi)
        assertEquals(6, message.reading.channel)
        assertEquals(AuthMode.OPEN, message.reading.auth)
        assertEquals(Capabilities.B or Capabilities.G, message.reading.capabilities)
        assertNull(message.reading.countryCode)
        assertNull(message.reading.ssid)
        assertEquals(2437, message.reading.frequencyMHz)
    }

    @Test
    fun scanFinishedVersions() {
        val full = SensorProtocol.Message.ScanFinished(65535, 23, 1640, 7, 12)
        assertEquals(full, SensorProtocol.decode(SensorProtocol.encode(full)))
        val v1 = byteArrayOf(0x12, 0xFF.toByte(), 0xFF.toByte(), 0x05, 0x00, 0x68, 0x06)
        val decoded = SensorProtocol.decode(v1) as SensorProtocol.Message.ScanFinished
        assertEquals(0, decoded.stationCount)
        assertEquals(0, decoded.probeCount)
        val v2 = byteArrayOf(0x12, 0x01, 0x00, 0x03, 0x00, 0xE8.toByte(), 0x03, 0x02, 0x00)
        assertEquals(0, (SensorProtocol.decode(v2) as SensorProtocol.Message.ScanFinished).probeCount)
    }

    @Test
    fun stationProbeDeauthAndBattery() {
        val client = WifiClient(
            MacAddress(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x01, 0x02, 0x03)),
            MacAddress(byteArrayOf(0x50, 0xC7.toByte(), 0xBF.toByte(), 0x1E, 0x22, 0xA1.toByte())),
            -58,
            true,
        )
        val station = SensorProtocol.Message.Station(42, client)
        assertEquals(17, SensorProtocol.encode(station).size)
        assertEquals(station, SensorProtocol.decode(SensorProtocol.encode(station)))

        val probing = WifiClient(MacAddress(byteArrayOf(0x7A, 0x12, 0x34, 0x56, 0x78, 0x9A.toByte())), null, -74, false)
        val decoded = SensorProtocol.decode(SensorProtocol.encode(SensorProtocol.Message.Station(1, probing))) as SensorProtocol.Message.Station
        assertNull(decoded.client.apBssid)

        val probe = ProbeRequest(probing.mac, -68, "Sheraton_WiFi", 6)
        assertEquals(12 + 13, SensorProtocol.encode(SensorProtocol.Message.ProbeReq(42, probe)).size)
        assertEquals(SensorProtocol.Message.ProbeReq(42, probe), SensorProtocol.decode(SensorProtocol.encode(SensorProtocol.Message.ProbeReq(42, probe))))

        val event = DeauthEvent(
            MacAddress(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x00, 0x01)),
            MacAddress(ByteArray(6) { 0xFF.toByte() }),
            reading.bssid,
            7, -45, 6, 24,
        )
        assertEquals(27, SensorProtocol.encode(SensorProtocol.Message.Deauth(99, event)).size)
        assertTrue(event.isBroadcast)
        assertEquals(DeauthEvent.Severity.CRITICAL, event.copy(count = 5).severity)
        assertEquals(0xCBF43926L, FirmwareUpdate.Crc32.hash("123456789".encodeToByteArray()))
        assertNull(SensorProtocol.decode(byteArrayOf(0x18, 0x01, 0x00, 0x50, 0xC7.toByte(), 0xBF.toByte(), 0x1E, 0x00, 0xA0.toByte(), 0x00, 0x00)))
        assertArrayEquals(byteArrayOf(0x01), SensorProtocol.Command.ScanOnce.data)
        assertArrayEquals(byteArrayOf(0x02, 0xFF.toByte()), SensorProtocol.Command.SetInterval(900).data)
    }

    @Test
    fun loraRoundTripAndCapFix() {
        val device = LoraDevice(868_100, 7, -118, 4, 0x01, 0xA1B2C3D4)
        val message = SensorProtocol.Message.Lora(3, device)
        assertEquals(message, SensorProtocol.decode(SensorProtocol.encode(message)))
        val position = SensorProtocol.Message.SavedPosition(9, 55.75124, 37.61842)
        val decoded = SensorProtocol.decode(SensorProtocol.encode(position)) as SensorProtocol.Message.SavedPosition
        assertEquals(9, decoded.captureId)
        assertEquals(55.75124, decoded.latitude, 0.00001)
        assertEquals(37.61842, decoded.longitude, 0.00001)
        val info = SensorInfo.decode("""{"proto":7,"fw":"0.9.0","chip":"ESP32-S3","bands":["2.4"],"interval":0,"lora":true,"lat":557512400,"lon":376184200,"sats":8}""")
        assertEquals(true, info?.lora)
        assertEquals(55.75124, info?.latitude ?: 0.0, 0.00001)
        assertEquals(8, info?.satellites)
        assertArrayEquals(byteArrayOf(0x04), SensorProtocol.Command.ScanLora.data)
        val progress = SensorProtocol.Message.LoraProgress(3, 868_100, 7, 12, 51)
        assertEquals(progress, SensorProtocol.decode(SensorProtocol.encode(progress)))
        assertEquals("868.100 MHz · SF7 · 12/51", progress.line())
    }

    @Test
    fun gattValuesDecode() {
        assertEquals("87%", GattValue.decode("00002a19-0000-1000-8000-00805f9b34fb", byteArrayOf(87)))
        assertEquals("Headphones", GattValue.decode("2A00", "Headphones".encodeToByteArray()))
        assertEquals("Phone", GattValue.decode("2A01", byteArrayOf(0x40, 0x00)))
        assertEquals("36.50 °C", GattValue.decode("2A6E", byteArrayOf(0x42, 0x0E)))
    }

    @Test
    fun infoDecodesBattery() {
        val info = SensorInfo.decode("""{"proto":6,"fw":"0.7.0","chip":"ESP32-S3","bands":["2.4"],"interval":5,"ota":true,"bat":85,"chg":false}""")
        assertNotNull(info)
        assertEquals(85, info!!.batteryLevel)
        assertEquals(false, info.isCharging)
        assertTrue(info.ota)
    }

    @Test
    fun networkGoesOutOfRangeAfterMissedScans() {
        val store = NetworkStore()
        fun reading(last: Int, rssi: Int) = AccessPointReading(
            MacAddress(byteArrayOf(0, 0, 0, 0, 0, last.toByte())),
            rssi, 1, SecondaryChannel.NONE, AuthMode.WPA2_PSK, Cipher.CCMP, Cipher.CCMP,
            Capabilities.N, null, "n$last".encodeToByteArray(),
        )
        fun scan(id: Int, readings: List<AccessPointReading>) {
            store.handle(SensorProtocol.Message.ScanStarted(id))
            readings.forEach { store.handle(SensorProtocol.Message.AccessPoint(id, it)) }
            store.handle(SensorProtocol.Message.ScanFinished(id, readings.size, 1000, 0, 0))
        }
        val second = MacAddress(byteArrayOf(0, 0, 0, 0, 0, 2))
        scan(1, listOf(reading(1, -50), reading(2, -80)))
        for (id in 2..NetworkStore.SCANS_BEFORE_OUT_OF_RANGE) {
            scan(id, listOf(reading(1, -52)))
            assertTrue(store.networks()[second]!!.isInRange)
        }
        scan(99, listOf(reading(1, -51)))
        assertFalse(store.networks()[second]!!.isInRange)
        val one = store.networks()[MacAddress(byteArrayOf(0, 0, 0, 0, 0, 1))]!!
        assertEquals(NetworkStore.SCANS_BEFORE_OUT_OF_RANGE + 1, one.samples.size)
        assertEquals(-50, one.strongestRssi)
        assertEquals(-52, one.weakestRssi)
    }

    @Test
    fun phoneSnapshotDoesNotAgeSensorNetworks() {
        val store = NetworkStore()
        val sensorMac = MacAddress(byteArrayOf(0x50, 0xC7.toByte(), 0xBF.toByte(), 0x1E, 0x00, 0xA0.toByte()))
        val phoneMac = MacAddress(byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60))
        val sensorReading = AccessPointReading(
            sensorMac, -40, 6, SecondaryChannel.ABOVE, AuthMode.WPA2_PSK, Cipher.CCMP, Cipher.CCMP,
            Capabilities.N, "RU", "Home".encodeToByteArray(),
        )
        store.handle(SensorProtocol.Message.ScanStarted(1))
        store.handle(SensorProtocol.Message.AccessPoint(1, sensorReading))
        store.handle(SensorProtocol.Message.ScanFinished(1, 1, 1000, 0, 0))
        val phoneReading = PhoneWifi.reading(phoneMac.text, "Five", -55, 5180, "[WPA2-PSK-CCMP][ESS]", 80)!!
        store.applyPhoneSnapshot(listOf(phoneReading))
        assertEquals(RadioSource.SENSOR, store.network(sensorMac)!!.source)
        assertTrue(store.network(sensorMac)!!.isInRange)
        assertEquals(RadioSource.PHONE, store.network(phoneMac)!!.source)
        assertEquals(BandWait.FIVE, store.network(phoneMac)!!.latest.band)
        store.applyPhoneSnapshot(emptyList())
        store.applyPhoneSnapshot(emptyList())
        store.applyPhoneSnapshot(emptyList())
        assertTrue(store.network(sensorMac)!!.isInRange)
        assertFalse(store.network(phoneMac)!!.isInRange)
    }

    @Test
    fun phoneCapabilitiesAndChannel() {
        assertEquals(36, PhoneWifi.channel(5180))
        assertEquals(6, PhoneWifi.channel(2437))
        val parsed = PhoneWifi.parseCapabilities("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]")
        assertEquals(AuthMode.WPA2_PSK, parsed.auth)
        assertEquals(Cipher.CCMP, parsed.pairwise)
        assertTrue(parsed.flags and Capabilities.WPS != 0)
        val mixed = PhoneWifi.parseCapabilities("[RSN-SAE-CCMP][RSN-PSK-CCMP][ESS]")
        assertEquals(AuthMode.WPA2_WPA3_PSK, mixed.auth)
    }

    @Test
    fun quietestChannelPrefersTheEmptyOne() {
        val loud = AccessPointReading(
            MacAddress(byteArrayOf(1, 2, 3, 4, 5, 6)),
            -40, 6, SecondaryChannel.NONE, AuthMode.OPEN, Cipher.NONE, Cipher.NONE, 0, null, "a".encodeToByteArray(),
        )
        assertEquals(1, ChannelAdvisor.recommendation(listOf(loud)))
    }

    @Test
    fun lanIdentityParsesTheSameRepliesAsIos() {
        val raw = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.20:1400/xml/device_description.xml\r\nSERVER: Linux/3.14 UPnP/1.0 Sonos/1\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val ssdp = LanIdentity.parseSsdp(raw.encodeToByteArray())
        assertEquals("http://192.168.1.20:1400/xml/device_description.xml", ssdp?.location)
        assertTrue(LanIdentity.locationIsOnHost(ssdp!!.location!!, "192.168.1.20"))
        assertFalse(LanIdentity.locationIsOnHost("file:///etc/passwd", "192.168.1.20"))

        val xml = """
            <root><device>
            <friendlyName>&#0;</friendlyName>
            <friendlyName> </friendlyName>
            <friendlyName>55&quot; Crystal UHD</friendlyName>
            <manufacturer>Samsung &amp; Co</manufacturer>
            <modelName>UE55&#34;AU</modelName>
            </device></root>
        """.trimIndent()
        val device = LanIdentity.parseUpnpDescription(xml)
        assertEquals("55\" Crystal UHD", device.friendlyName)
        assertEquals("Samsung & Co", device.manufacturer)
        assertEquals("UE55\"AU", device.model)
        assertEquals("55\" Crystal UHD", LanIdentity.decodeXmlEntities("55&quot: Crystal UHD"))
        assertEquals("&quot;", LanIdentity.decodeXmlEntities("&amp;quot;"))

        val known = LanProfile(upnp = UPnPIdentity("55\" Crystal UHD", "Samsung", "UE55", "MediaRenderer", "Samsung"))
        assertEquals(known, LanProfile().filled(known))

        assertTrue(LanIdentity.httpMethodRejected(501))
        assertTrue(LanIdentity.httpPrefers(200, "Keenetic", 501, null))
        assertFalse(LanIdentity.httpPrefers(501, null, 200, "Keenetic"))

        val mac = HostIdentity.recognize(names = listOf("Alexander's Mac"))
        assertEquals("Apple", mac.manufacturer)
        assertEquals("macOS", mac.operatingSystem)
        assertEquals("Mac", mac.model)
        val office = HostIdentity.recognize(
            names = listOf("Office"),
            services = listOf(ServiceAdvertisement("_companion-link._tcp", "Office")),
        )
        assertEquals("Apple", office.manufacturer)
        assertEquals("macOS", office.operatingSystem)
        val phone = HostIdentity.recognize(
            names = listOf("Phone"),
            services = listOf(ServiceAdvertisement("_device-info._tcp", "Phone", "iPhone16,1")),
        )
        assertEquals("Apple", phone.manufacturer)
        assertEquals("iOS", phone.operatingSystem)
        assertEquals("iPhone 15 Pro", phone.model)
        val fold = HostIdentity.recognize(names = listOf("Galaxy Z Fold5"))
        assertEquals("Samsung", fold.manufacturer)
        assertEquals("Android", fold.operatingSystem)
        assertEquals("Galaxy Z Fold5", HostIdentity.recognize(names = listOf("SM-F946B")).model)
        val fold4 = HostIdentity.recognize(names = listOf("SM-F936B"))
        assertEquals("Samsung", fold4.manufacturer)
        assertEquals("Android", fold4.operatingSystem)
        assertEquals("Galaxy Z Fold4", fold4.model)
        val plain = HostIdentity.recognize(names = listOf("Android"))
        assertEquals("Android", plain.operatingSystem)
        assertNull(plain.manufacturer)
        val hashed = HostIdentity.recognize(names = listOf("android-a1b2c3d4e5f67890"))
        assertEquals("Android", hashed.operatingSystem)
        assertNull(hashed.manufacturer)
        val tvShaped = HostIdentity.recognize(names = listOf("android-ls12abcd"))
        assertEquals("Android", tvShaped.operatingSystem)
        assertNull(tvShaped.manufacturer)
        val hyphenated = HostIdentity.recognize(names = listOf("Galaxy-Z-Fold4"))
        assertEquals("Samsung", hyphenated.manufacturer)
        assertEquals("Android", hyphenated.operatingSystem)
        assertEquals("Galaxy Z Fold4", hyphenated.model)
        val streamer = HostIdentity.recognize(
            names = listOf("Eversolo DMP-A6"),
            services = listOf(ServiceAdvertisement("_airplay._tcp", "Eversolo DMP-A6")),
        )
        assertEquals("Eversolo", streamer.manufacturer)
        assertNull(streamer.operatingSystem)
        val television = HostIdentity.recognize(
            names = listOf("Living Room"),
            services = listOf(ServiceAdvertisement("_raop._tcp", "Living Room")),
            upnpName = "55\" Crystal UHD",
            upnpMaker = "Samsung Electronics",
            upnpType = "MediaRenderer",
        )
        assertEquals("Samsung", television.manufacturer)
        assertEquals("Tizen", television.operatingSystem)
        assertEquals("55\" Crystal UHD", television.model)
        assertNull(
            HostIdentity.recognize(
                names = listOf("Living Room"),
                services = listOf(ServiceAdvertisement("_airplay._tcp", "Living Room")),
            ).manufacturer,
        )
        val synced = HostIdentity.recognize(
            names = listOf("Alex"),
            services = listOf(ServiceAdvertisement("_apple-mobdev2._tcp", "Alex")),
        )
        assertEquals("Apple", synced.manufacturer)
        assertEquals("iOS", synced.operatingSystem)
        assertEquals("iPhone", synced.model)

        val named = LanDevice("192.168.1.20", hostnames = listOf(""), profile = LanProfile(upnp = UPnPIdentity(friendlyName = "55\" Crystal UHD")))
        assertEquals("55\" Crystal UHD", named.displayName)
        assertEquals("Living-Room", LanDevice.usefulHostname("Living-Room.local", "192.168.1.20"))
        assertEquals("Living Room", LanDevice.usefulHostname("Living\\032Room._googlecast._tcp.local", "192.168.1.20"))
        assertEquals("Kitchen", LanDevice.usefulHostname("A1B2C3D4E5F6@Kitchen", "192.168.1.20"))
        assertNull(LanDevice.usefulHostname("192.168.1.20", "192.168.1.20"))

        val http = "HTTP/1.0 200 OK\r\nServer: Webs\r\nWWW-Authenticate: Basic realm=\"DS-2CD2043\"\r\n\r\n<html><title>Hikvision</title></html>"
        val banner = LanIdentity.parseHttp(http.encodeToByteArray())
        assertEquals(200, banner?.status)
        assertEquals("Webs", banner?.server)
        assertEquals("Hikvision", banner?.title)
        assertEquals("DS-2CD2043", banner?.realm)

        assertEquals(NotableDevice.CAMERA, NotableDevice.classify("Hikvision", "44:19:B6", null, false))
        assertEquals(NotableDevice.IOT, NotableDevice.classify("Espressif", null, null, false))
        assertNull(NotableDevice.classify("Apple", null, null, false))
        assertEquals(NotableDevice.CAMERA, NotableDevice.classify("Apple", null, null, true))
        assertNull(Catalog.oui("44:19:B6"))
        assertEquals("Hikvision", Catalog.suspiciousOui("44:19:B6"))
        assertEquals("Apple, Inc.", Catalog.company(0x004C))

        val framed = LanIdentity.smbNegotiate()
        assertEquals(0, framed[0].toInt())
        val payload = LanIdentity.smbPayload(framed)
        assertEquals(0xFE, payload[0].toInt() and 0xFF)
        assertEquals(0x53, payload[1].toInt() and 0xFF)
        assertEquals(0L, LanIdentity.smbStatus(payload))
    }

    @Test
    fun ntlmPrefersTheDnsNameOverATruncatedNetbiosName() {
        fun av(id: Int, text: String): ByteArray {
            val value = text.toByteArray(Charsets.UTF_16LE)
            return byteArrayOf(id.toByte(), 0, value.size.toByte(), 0) + value
        }
        val info = av(1, "ALEXANDERS-MACB") + av(2, "WORKGROUP") + av(3, "Alexanders-MacBook-Pro.local") + byteArrayOf(0, 0, 0, 0)
        val header = "NTLMSSP\u0000".encodeToByteArray() +
            byteArrayOf(2, 0, 0, 0) +
            byteArrayOf(0, 0, 0, 0, 48, 0, 0, 0) +
            ByteArray(4 + 8 + 8) +
            byteArrayOf(info.size.toByte(), 0, info.size.toByte(), 0) +
            byteArrayOf(48, 0, 0, 0)
        val names = LanIdentity.parseNtlmChallenge(byteArrayOf(0xFE.toByte(), 0x53) + header + info)
        assertEquals("Alexanders-MacBook-Pro", names?.computer)
        assertEquals("WORKGROUP", names?.domain)
        assertEquals("OFFICE-PC", LanIdentity.preferredComputerName("OFFICE-PC", "office-pc"))
    }

    @Test
    fun aMacWithFileSharingIsRecognisedFromItsSmbName() {
        val mac = LanDevice(
            ipAddress = "192.168.1.30",
            profile = LanProfile(windows = WindowsHost("Alexanders-MacBook-Pro", "WORKGROUP")),
        )
        assertEquals("Apple", mac.identity.manufacturer)
        assertEquals("macOS", mac.identity.operatingSystem)
        assertEquals("MacBook", mac.identity.model)

        val pro = HostIdentity.recognize(
            names = listOf("Office"),
            services = listOf(ServiceAdvertisement("_device-info._tcp", "Office", "MacBookPro18,3")),
        )
        assertEquals("MacBook Pro", pro.model)
    }
}

private typealias BandWait = cattivole.spectra.core.Band
