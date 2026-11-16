package cattivole.spectra

import cattivole.spectra.core.BeaconDecode
import cattivole.spectra.core.BleAdvertisement
import cattivole.spectra.core.BleDevice
import cattivole.spectra.core.BleFields
import cattivole.spectra.core.Catalog
import cattivole.spectra.core.DeviceLabel
import cattivole.spectra.core.DeviceLabels
import cattivole.spectra.core.DeviceTrust
import cattivole.spectra.core.DiscoveredPort
import cattivole.spectra.core.FindMyStatus
import cattivole.spectra.core.KnownHosts
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.LanProfile
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.MemoryTextStore
import cattivole.spectra.core.PortRisk
import cattivole.spectra.core.RiskLevel
import cattivole.spectra.core.TrackerAssessment
import cattivole.spectra.core.TrackerDetector
import cattivole.spectra.core.WindowsHost
import cattivole.spectra.core.fingerprints
import cattivole.spectra.core.needsAttention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What came over from Probe Lab: the full OUI registry, trackers, port risk, known hosts and labels. */
class ProbeLabPortTest {
    private fun host(
        ip: String,
        mac: String? = null,
        names: List<String> = emptyList(),
        ports: List<Int> = emptyList(),
        gateway: Boolean = false,
        profile: LanProfile = LanProfile(),
    ) = LanDevice(
        ipAddress = ip,
        macAddress = mac?.let { MacAddress.parse(it) },
        hostnames = names,
        openPorts = ports.map { DiscoveredPort(it, DiscoveredPort.serviceName(it)) },
        portsScanned = true,
        isGateway = gateway,
        profile = profile,
    )

    @Test
    fun theMostSpecificRegistryBlockWins() {
        assertEquals("Hikvision", Catalog.oui(MacAddress.parse("44:19:B6:01:02:03")!!.bytes))
        // 70:B3:D5 belongs to the registration authority; its 36-bit blocks have their own makers.
        assertEquals("SOREDI touch systems", Catalog.oui(MacAddress.parse("70:B3:D5:00:12:34")!!.bytes))
        assertNull(Catalog.oui("70:B3:D5"))
        assertNull(Catalog.oui(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun findMyTellsATagAwayFromItsOwner() {
        val near = FindMyStatus.parse(byteArrayOf(0x40, 0x00))!!
        assertFalse(near.separated)
        assertEquals(1, near.battery)
        val away = FindMyStatus.parse(ByteArray(25).also { it[0] = 0x80.toByte() })!!
        assertTrue(away.separated)
        assertEquals("Away from its owner · Battery low", away.summary)
        assertNull(FindMyStatus.parse(ByteArray(0)))
    }

    private fun tag(payload: ByteArray, seen: Long = 0): BleDevice {
        val data = byteArrayOf(0x4C, 0x00, 0x12, payload.size.toByte()) + payload
        val device = BleDevice("C4:00:00:00:00:01", "Resolvable private", -60, seen)
        device.absorb(BleAdvertisement.parse(BleFields(manufacturerData = data)), null, -60, seen)
        return device
    }

    @Test
    fun aSeparatedTagThatStaysBecomesAnAlert() {
        val lost = ByteArray(25).also { it[0] = 0x00 }
        val device = tag(lost)
        assertEquals(TrackerAssessment.Kind.FIND_MY, TrackerDetector.kind(device))
        assertEquals(TrackerAssessment.Level.WATCH, TrackerDetector.assess(device, 5_000)?.level)
        // Heard every 30 s for eleven minutes: one unbroken stretch.
        var at = 0L
        while (at < 11 * 60_000L) {
            at += 30_000
            device.absorb(BleAdvertisement.parse(BleFields(manufacturerData = byteArrayOf(0x4C, 0x00, 0x12, 25) + lost)), null, -60, at)
        }
        val assessment = TrackerDetector.assess(device, at + 1_000)!!
        assertEquals(TrackerAssessment.Level.ALERT, assessment.level)
        assertEquals(true, assessment.separated)
        // Long after the last packet it is no longer nearby.
        assertEquals(TrackerAssessment.Level.INFO, TrackerDetector.assess(device, at + 60_000)?.level)
    }

    @Test
    fun aSilenceStartsANewStretch() {
        val device = tag(ByteArray(25))
        device.absorb(BleAdvertisement.parse(BleFields(manufacturerData = byteArrayOf(0x4C, 0x00, 0x12, 2, 0, 0))), null, -60, 200_000)
        assertEquals(0, device.presentFor(200_000))
        assertEquals(TrackerAssessment.Level.INFO, TrackerDetector.assess(tag(byteArrayOf(0, 0)), 1_000)?.level)
    }

    @Test
    fun otherTagsAreKnownByTheirServiceData() {
        val tile = BleDevice("C4:00:00:00:00:02", "Random static", -70, 0)
        tile.absorb(BleAdvertisement.parse(BleFields(serviceData = mapOf("0000feed-0000-1000-8000-00805f9b34fb" to byteArrayOf(1)))), null, -70, 0)
        assertEquals(TrackerAssessment.Kind.TILE, TrackerDetector.kind(tile))
        val phone = BleDevice("C4:00:00:00:00:03", "Resolvable private", -70, 0)
        phone.absorb(BleAdvertisement.parse(BleFields(localName = "Pixel")), null, -70, 0)
        assertNull(TrackerDetector.kind(phone))
        assertEquals("Samsung SmartTag", BeaconDecode.decode("FD5A", byteArrayOf(1))?.title)
        assertEquals("Find My Device network", BeaconDecode.decode("FE2C", ByteArray(21).also { it[0] = 0x40 })?.title)
        assertEquals("Tracker frame", BeaconDecode.decode("FE2C", ByteArray(22).also { it[0] = 0x40 })?.detail)
    }

    @Test
    fun openPortsAreReadAsARiskReview() {
        val camera = host("192.168.1.20", ports = listOf(23, 80, 554))
        val findings = PortRisk.findings(camera)
        assertEquals(RiskLevel.HIGH, findings.first().level)
        assertEquals("Telnet", findings.first().title)
        assertTrue(camera.needsAttention)
        assertFalse(host("192.168.1.21", ports = listOf(22, 443)).needsAttention)
        assertNull(PortRisk.level(host("192.168.1.22", ports = listOf(443))))
        val router = host("192.168.1.1", ports = listOf(80), gateway = true)
        assertEquals("Router admin page without encryption", PortRisk.findings(router).single().title)
        val shares = host(
            "192.168.1.30", ports = listOf(445),
            profile = LanProfile(windows = WindowsHost(shares = listOf("Public"), guestSession = true)),
        )
        val open = PortRisk.findings(shares).single()
        assertEquals(RiskLevel.HIGH, open.level)
        assertEquals("Public", open.detailArg)
        // Nothing is said before the ports were scanned.
        val unscanned = LanDevice("192.168.1.40", openPorts = listOf(DiscoveredPort(23, "Telnet")))
        assertTrue(PortRisk.findings(unscanned).isEmpty())
    }

    @Test
    fun theFirstScanIsABaselineAndLaterHostsAreNew() {
        val store = MemoryTextStore()
        val known = KnownHosts(store)
        val router = host("192.168.1.1", "50:C7:BF:12:34:56", gateway = true)
        val laptop = host("192.168.1.10", "3C:22:FB:11:22:33", names = listOf("MacBook-Pro.local"))
        val first = known.classify(listOf(router, laptop), "lan:gw:x", now = 1_000)
        assertTrue(first.isBaseline)
        assertTrue(first.newIps.isEmpty())
        assertFalse(laptop.isNew)

        val stranger = host("192.168.1.77", "AA:BB:CC:00:00:01")
        // The laptop came back on another address without its MAC: still known by name.
        val moved = host("192.168.1.11", names = listOf("MacBook-Pro.local"))
        val second = KnownHosts(store).classify(listOf(router, moved, stranger), "lan:gw:x", now = 2_000)
        assertFalse(second.isBaseline)
        assertEquals(listOf("192.168.1.77"), second.newIps)
        assertTrue(stranger.isNew)
        assertFalse(moved.isNew)
        assertEquals(1_000L, known.let { KnownHosts(store).firstSeen(moved, "lan:gw:x") })

        val again = KnownHosts(store)
        again.forget("lan:gw:x")
        assertFalse(again.knows("lan:gw:x"))
    }

    @Test
    fun opaqueIdentifiersAreNotNames() {
        assertTrue(LanDevice.isOpaqueIdentifier("1b067baf05912b622b215c7a"))
        assertTrue(LanDevice.isOpaqueIdentifier("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
        assertFalse(LanDevice.isOpaqueIdentifier("Living-Room-TV"))
        val tv = host("192.168.1.15", names = listOf("1b067baf05912b622b215c7a"))
        assertEquals(listOf("ip:192.168.1.15"), tv.fingerprints)
    }

    @Test
    fun labelsFollowTheHostNotTheAddress() {
        val store = MemoryTextStore()
        val labels = DeviceLabels(store)
        val laptop = host("192.168.1.10", "3C:22:FB:11:22:33")
        laptop.isNew = true
        labels.set(DeviceLabel(" Work Mac ", DeviceTrust.TRUSTED, "  "), laptop)
        assertEquals("Work Mac", laptop.displayName)
        assertNull(laptop.note)
        assertFalse(laptop.isNew)

        val reloaded = DeviceLabels(store)
        val sameMac = host("192.168.1.99", "3C:22:FB:11:22:33")
        reloaded.apply(sameMac)
        assertEquals(DeviceTrust.TRUSTED, sameMac.trust)
        // Another device that took over the address does not inherit the label.
        val squatter = host("192.168.1.10", "AA:BB:CC:00:00:02")
        reloaded.apply(squatter)
        assertEquals(DeviceTrust.UNKNOWN, squatter.trust)

        reloaded.set(DeviceLabel(), sameMac)
        assertNull(DeviceLabels(store).label(sameMac))
    }
}
