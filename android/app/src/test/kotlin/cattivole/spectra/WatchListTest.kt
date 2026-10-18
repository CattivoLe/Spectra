package cattivole.spectra

import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.Capabilities
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.NetworkStore
import cattivole.spectra.core.RadioSource
import cattivole.spectra.core.SecondaryChannel
import cattivole.spectra.core.TrackedNetwork
import cattivole.spectra.core.WatchList
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchListTest {
    private fun net(bssid: ByteArray, rssi: Int, missed: Int = 0): TrackedNetwork {
        val reading = AccessPointReading(
            MacAddress(bssid), rssi, 6, SecondaryChannel.NONE, AuthMode.WPA2_PSK, Cipher.CCMP, Cipher.CCMP,
            Capabilities.N, null, "Net".encodeToByteArray(),
        )
        return TrackedNetwork(reading, 0, 0, mutableListOf(), missedScans = missed, source = RadioSource.SENSOR)
    }

    @Test
    fun presentReturnsOnlyWatchedInRange() {
        val watched = net(byteArrayOf(0xAA.toByte(), 0, 0, 0, 0, 1), -50)
        val watchedGone = net(byteArrayOf(0xBB.toByte(), 0, 0, 0, 0, 2), -80, missed = NetworkStore.SCANS_BEFORE_OUT_OF_RANGE + 1)
        val other = net(byteArrayOf(0xCC.toByte(), 0, 0, 0, 0, 3), -40)
        val ids = setOf(watched.latest.bssid.text, watchedGone.latest.bssid.text)
        val present = WatchList.presentNetworks(ids, listOf(watched, watchedGone, other))
        assertEquals(1, present.size)
        assertEquals(watched.latest.bssid.text, present.first().latest.bssid.text)
    }
}
