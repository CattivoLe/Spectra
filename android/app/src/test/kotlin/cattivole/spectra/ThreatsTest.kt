package cattivole.spectra

import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.Capabilities
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.DeauthEvent
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.RadioSource
import cattivole.spectra.core.SecondaryChannel
import cattivole.spectra.core.Threats
import cattivole.spectra.core.TrackedNetwork
import cattivole.spectra.core.TrafficSnapshot
import org.junit.Assert.assertEquals
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
            traffic = bytes?.let { TrafficSnapshot(it, 10) },
            source = RadioSource.SENSOR,
        )
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
}
