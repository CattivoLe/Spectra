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
import cattivole.spectra.core.SensorProtocol
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
        ).apply { bytes?.let { heard -> repeat(Threats.STREAMING_SCANS) { noteTraffic(TrafficSnapshot(heard, 10)) } } }
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

    private fun flagged(network: TrackedNetwork) = Threats.assess(listOf(network), emptyList()).any { it.kind == Threats.Kind.STREAMING }

    /** A camera at about a megabit: 31 KB in the quarter of a second the sensor spends on its channel. */
    @Test
    fun aCameraIsFlaggedOnceItKeepsGoing() {
        val camera = net(byteArrayOf(2, 0, 0, 0, 0, 2), "Camera", AuthMode.WPA2_PSK)
        val feed = TrafficSnapshot(31_000, 40)
        repeat(Threats.STREAMING_SCANS - 1) {
            camera.noteTraffic(feed)
            assertFalse(flagged(camera))
        }
        camera.noteTraffic(feed)
        assertTrue(flagged(camera))
        // One sweep that missed it changes nothing; a feed that stopped goes away.
        camera.noteTraffic(feed)
        camera.noteTraffic(null)
        assertTrue(flagged(camera))
        camera.noteTraffic(null)
        assertFalse(flagged(camera))
    }

    @Test
    fun aSingleBusySweepIsNotAStream() {
        val network = net(byteArrayOf(3, 0, 0, 0, 0, 3), "Browsing", AuthMode.WPA2_PSK)
        network.noteTraffic(TrafficSnapshot(900_000, 700))
        network.noteTraffic(TrafficSnapshot(2_000, 4))
        assertFalse(flagged(network))
    }

    @Test
    fun theBitrateIsOverTheTimeOnTheChannel() {
        assertEquals(1_000_000.0, TrafficSnapshot(31_250, 40).estimatedBitsPerSecond, 0.1)
    }

    /** The sensor says nothing about a network's traffic in a sweep that heard none: that counts against the stream. */
    @Test
    fun theStoreCountsSweepsWithoutTraffic() {
        val store = NetworkStore()
        val camera = net(byteArrayOf(2, 0, 0, 0, 0, 2), "Camera", AuthMode.WPA2_PSK).latest
        fun sweep(id: Int, bytes: Long?) {
            store.handle(SensorProtocol.Message.ScanStarted(id))
            store.handle(SensorProtocol.Message.AccessPoint(id, camera))
            bytes?.let { store.handle(SensorProtocol.Message.Traffic(id, camera.bssid, it, 40)) }
            store.handle(SensorProtocol.Message.ScanFinished(id, 1, 3_000, 0, 0))
        }
        for (id in 1..3) sweep(id, 20_000)
        assertTrue(store.network(camera.bssid)!!.movesDataSteadily)
        sweep(4, null)
        assertNull(store.network(camera.bssid)!!.traffic)
        assertFalse(store.network(camera.bssid)!!.movesDataSteadily)
    }
}
