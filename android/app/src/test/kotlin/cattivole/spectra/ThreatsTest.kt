package cattivole.spectra

import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.Capabilities
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.DeauthEvent
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.NetworkStore
import cattivole.spectra.core.RadioSource
import cattivole.spectra.core.SecondaryChannel
import cattivole.spectra.core.Threats
import cattivole.spectra.core.TrackedNetwork
import cattivole.spectra.core.TrafficSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreatsTest {
    private fun net(bssid: ByteArray, ssid: String, auth: AuthMode, bytes: Long? = null): TrackedNetwork {
        val reading = AccessPointReading(
            MacAddress(bssid), -50, 6, SecondaryChannel.NONE, auth, Cipher.CCMP, Cipher.CCMP,
            Capabilities.N, null, ssid.encodeToByteArray(),
        )
        return TrackedNetwork(
            reading, 0, 0, mutableListOf(),
            source = RadioSource.SENSOR,
        ).apply {
            bytes?.let { heard -> repeat(Threats.STREAMING_SCANS) { noteTraffic(TrafficSnapshot(heard, 10)) } }
        }
    }

    @Test
    fun evilTwinWhenSecurityDiffers() {
        val networks = listOf(
            net(byteArrayOf(0xAA.toByte(), 0, 0, 0, 0, 1), "CoffeeWiFi", AuthMode.WPA2_PSK),
            net(byteArrayOf(0xBB.toByte(), 0, 0, 0, 0, 2), "CoffeeWiFi", AuthMode.OPEN),
        )
        val twin = Threats.assess(networks, emptyList()).firstOrNull { it.kind == Threats.Kind.EVIL_TWIN }
        assertNotNull(twin)
        assertEquals(Threats.Severity.CRITICAL, twin!!.severity)
        assertEquals(2, twin.apCount)
    }

    @Test
    fun sameSsidSameVendorAndAuthIsNotFlagged() {
        val networks = listOf(
            net(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0, 0, 1), "HomeMesh", AuthMode.WPA2_PSK),
            net(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0, 0, 2), "HomeMesh", AuthMode.WPA2_PSK),
        )
        assertNull(Threats.assess(networks, emptyList()).firstOrNull { it.kind == Threats.Kind.EVIL_TWIN })
    }

    @Test
    fun evilTwinGoesAwayWhenACopyIsOutOfRange() {
        val gone = net(byteArrayOf(0xBB.toByte(), 0, 0, 0, 0, 2), "CoffeeWiFi", AuthMode.OPEN)
        gone.missedScans = NetworkStore.SCANS_BEFORE_OUT_OF_RANGE
        val networks = listOf(net(byteArrayOf(0xAA.toByte(), 0, 0, 0, 0, 1), "CoffeeWiFi", AuthMode.WPA2_PSK), gone)
        assertNull(Threats.assess(networks, emptyList()).firstOrNull { it.kind == Threats.Kind.EVIL_TWIN })
    }

    @Test
    fun evilTwinListsItsAccessPoints() {
        val networks = listOf(
            net(byteArrayOf(0xAA.toByte(), 0, 0, 0, 0, 1), "CoffeeWiFi", AuthMode.WPA2_PSK),
            net(byteArrayOf(0xBB.toByte(), 0, 0, 0, 0, 2), "CoffeeWiFi", AuthMode.OPEN),
        )
        val twin = Threats.assess(networks, emptyList()).first { it.kind == Threats.Kind.EVIL_TWIN }
        assertEquals(networks.map { it.id }.toSet(), twin.networks.toSet())
    }

    @Test
    fun broadcastDeauthIsCritical() {
        val event = DeauthEvent(
            MacAddress(byteArrayOf(1, 2, 3, 4, 5, 6)),
            MacAddress(ByteArray(6) { 0xFF.toByte() }),
            MacAddress(byteArrayOf(1, 2, 3, 4, 5, 6)), 7, -40, 6, 8,
        )
        val findings = Threats.assess(emptyList(), listOf(event))
        assertEquals(Threats.Kind.DEAUTH_ATTACK, findings.first().kind)
        assertEquals(Threats.Severity.CRITICAL, findings.first().severity)
    }

    /** A camera at about a megabit: 31 KB in a quarter of a second. */
    @Test
    fun aCameraIsFlaggedOnceItKeepsGoing() {
        val camera = net(byteArrayOf(2, 0, 0, 0, 0, 2), "Camera", AuthMode.WPA2_PSK)
        val feed = TrafficSnapshot(31_000, 40)
        fun flagged() = Threats.assess(listOf(camera), emptyList()).isNotEmpty()
        repeat(Threats.STREAMING_SCANS - 1) {
            camera.noteTraffic(feed)
            assertFalse(flagged())
        }
        camera.noteTraffic(feed)
        assertTrue(flagged())
        // One sweep that missed it changes nothing; a feed that stopped goes away.
        camera.noteTraffic(feed)
        camera.noteTraffic(null)
        assertTrue(flagged())
        camera.noteTraffic(null)
        assertFalse(flagged())
    }

    @Test
    fun heavyTrafficIsFlagged() {
        val networks = listOf(
            net(byteArrayOf(1, 0, 0, 0, 0, 1), "Idle", AuthMode.WPA2_PSK, bytes = 500),
            net(byteArrayOf(2, 0, 0, 0, 0, 2), "Camera", AuthMode.WPA2_PSK, bytes = 250_000),
        )
        val streaming = Threats.assess(networks, emptyList()).filter { it.kind == Threats.Kind.STREAMING }
        assertEquals(1, streaming.size)
        assertTrue(streaming.first().name!!.contains("Camera"))
    }
}
