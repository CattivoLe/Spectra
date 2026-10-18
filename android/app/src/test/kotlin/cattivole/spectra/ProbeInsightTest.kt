package cattivole.spectra

import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.Capabilities
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.ProbeInsight
import cattivole.spectra.core.SecondaryChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeInsightTest {
    private fun ap(ssid: String, auth: AuthMode): AccessPointReading = AccessPointReading(
        MacAddress(byteArrayOf(0, 0, 0, 0, 0, 1)), -50, 6, SecondaryChannel.NONE, auth, Cipher.CCMP, Cipher.CCMP,
        Capabilities.N, null, ssid.encodeToByteArray(),
    )

    @Test
    fun countsDistinctRememberedNetworks() {
        val result = ProbeInsight.assess(listOf("Home", "Home", "Work", ""), emptyList())
        assertEquals(2, result.rememberedCount)
        assertTrue(result.presentHere.isEmpty())
        assertFalse(result.trackable)
    }

    @Test
    fun flagsPresentAndAutoJoinOpen() {
        val probes = listOf("CoffeeShop", "Home", "AirportFree")
        val inRange = listOf(ap("coffeeshop", AuthMode.WPA2_PSK), ap("AirportFree", AuthMode.OPEN))
        val result = ProbeInsight.assess(probes, inRange)
        assertEquals(listOf("AirportFree", "CoffeeShop"), result.presentHere)
        assertEquals(listOf("AirportFree"), result.autoJoinOpen)
    }

    @Test
    fun manyNetworksAreTrackable() {
        assertTrue(ProbeInsight.assess(listOf("A", "B", "C", "D"), emptyList()).trackable)
    }
}
