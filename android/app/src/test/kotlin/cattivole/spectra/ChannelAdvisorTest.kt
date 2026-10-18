package cattivole.spectra

import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.Capabilities
import cattivole.spectra.core.ChannelAdvisor
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.SecondaryChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAdvisorTest {
    private fun ap(channel: Int, rssi: Int): AccessPointReading = AccessPointReading(
        MacAddress(byteArrayOf(channel.toByte(), 0, 0, 0, 0, rssi.toByte())),
        rssi, channel, SecondaryChannel.NONE, AuthMode.WPA2_PSK, Cipher.CCMP, Cipher.CCMP,
        Capabilities.N, null, "n".encodeToByteArray(),
    )

    @Test
    fun avoidsTheLoudTwoFourChannel() {
        val best = ChannelAdvisor.recommendation(listOf(ap(1, -40), ap(1, -45)))
        assertTrue(best in ChannelAdvisor.candidates)
        assertNotEquals(1, best)
    }

    @Test
    fun fiveGhzRecommendationAvoidsOccupiedChannel() {
        val best = ChannelAdvisor.recommendationFive(listOf(ap(36, -38)))
        assertTrue(best in ChannelAdvisor.fiveCandidates)
        assertNotEquals(36, best)
    }

    @Test
    fun congestionRisesWithASignal() {
        assertEquals(0.0, ChannelAdvisor.congestion(6, emptyList()), 0.0)
        assertTrue(ChannelAdvisor.congestion(6, listOf(ap(6, -50))) > 0.0)
    }
}
