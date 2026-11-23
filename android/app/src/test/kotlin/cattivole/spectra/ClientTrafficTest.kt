package cattivole.spectra

import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.Capabilities
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.ClientTraffic
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.NetworkStore
import cattivole.spectra.core.SecondaryChannel
import cattivole.spectra.core.SensorProtocol
import cattivole.spectra.core.Threats
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What one client sends (`0x25`), and the rule that names the one that keeps sending. */
class ClientTrafficTest {
    private fun mac(vararg bytes: Int) = MacAddress(ByteArray(6) { bytes[it].toByte() })

    private val ap = mac(0, 0, 0, 0, 0, 1)
    private val cameraMac = mac(0x44, 0x19, 0xB6, 1, 2, 3)

    private fun client(
        up: Long,
        frames: Int,
        down: Long,
        busy: Int,
        listenMs: Int = 1250,
        address: MacAddress = cameraMac,
    ) = ClientTraffic(address, ap, 6, -50, listenMs, up, frames, down, 40, busy)

    private val camera = client(78_000, 60, 4_000, 13)

    private fun sweep(store: NetworkStore, id: Int, apBytes: Long = 0, vararg sent: ClientTraffic, counts: Boolean = true) {
        val reading = AccessPointReading(
            ap, -50, 6, SecondaryChannel.NONE, AuthMode.WPA2_PSK, Cipher.CCMP, Cipher.CCMP,
            Capabilities.N, null, "Home".encodeToByteArray(),
        )
        store.handle(SensorProtocol.Message.ScanStarted(id))
        store.handle(SensorProtocol.Message.AccessPoint(id, reading))
        store.handle(SensorProtocol.Message.Traffic(id, ap, apBytes, 1))
        sent.forEach { store.handle(SensorProtocol.Message.ClientSent(id, it)) }
        store.handle(SensorProtocol.Message.ScanFinished(id, 1, 1000, 0, 0, if (counts) sent.size else null))
    }

    private fun findings(store: NetworkStore, lanName: (MacAddress) -> String? = { null }) =
        Threats.assess(store.networks().values.toList(), emptyList(), store.steadySenders, lanName)

    /** The same bytes as in the firmware's `test_client_traffic_layout`. */
    @Test
    fun layout() {
        val bytes = intArrayOf(
            0x25, 0x01, 0x02,
            0x44, 0x47, 0xCC, 0x01, 0x02, 0x03,
            0x50, 0xC7, 0xBF, 0x1E, 0x00, 0xA0,
            0x06, 0xCE,
            0xE2, 0x04,
            0xB0, 0x30, 0x01, 0x00, 0x3C, 0x00,
            0xA0, 0x0F, 0x00, 0x00, 0x28, 0x00,
            0x0D,
        ).map { it.toByte() }.toByteArray()
        val message = SensorProtocol.Message.ClientSent(
            0x0201,
            ClientTraffic(
                mac(0x44, 0x47, 0xCC, 1, 2, 3), mac(0x50, 0xC7, 0xBF, 0x1E, 0, 0xA0),
                channel = 6, rssi = -50, listenMs = 1250, upBytes = 78_000, upFrames = 60,
                downBytes = 4_000, downFrames = 40, busySlots = 13,
            ),
        )
        assertArrayEquals(bytes, SensorProtocol.encode(message))
        assertEquals(message, SensorProtocol.decode(bytes))
        assertNull(SensorProtocol.decode(bytes.copyOf(31)))
    }

    /** Firmware 0.11 says how many client records it sent; before that the field is not there. */
    @Test
    fun scanFinishedCarriesTheClientCount() {
        val full = SensorProtocol.Message.ScanFinished(1, 2, 3, 4, 5, 6)
        val bytes = SensorProtocol.encode(full)
        assertArrayEquals(byteArrayOf(0x12, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0), bytes)
        assertEquals(full, SensorProtocol.decode(bytes))
        assertNull((SensorProtocol.decode(bytes.copyOf(11)) as SensorProtocol.Message.ScanFinished).clientCount)
    }

    /** The same cases as `test_streaming_rule` in the firmware. */
    @Test
    fun theRuleForOneClient() {
        assertTrue(camera.looksLikeStream)
        assertTrue(client(15_000, 60, 0, 13).looksLikeStream) // a G.711 microphone
        assertFalse(client(9_900, 12, 0, 13).looksLikeStream) // under the bar
        assertFalse(client(100_000, 1000, 0, 13).looksLikeStream) // acknowledgements of a download
        assertFalse(client(150_000, 150, 140_000, 13).looksLikeStream) // a video call
        assertFalse(client(300_000, 220, 2_000, 4).looksLikeStream) // a photo going up
        assertFalse(client(0, 0, 0, 0, listenMs = 0).looksLikeStream)
    }

    @Test
    fun aClientThatKeepsSendingIsNamed() {
        val store = NetworkStore()
        sweep(store, 1, 0, camera)
        sweep(store, 2, 0, camera)
        assertTrue(store.steadySenders.isEmpty())
        sweep(store, 3, 0, camera)
        assertEquals(listOf(cameraMac), store.steadySenders.map { it.id })

        val finding = findings(store) { if (it == cameraMac) "Front door" else null }.single()
        assertEquals(Threats.Kind.STREAMING, finding.kind)
        assertEquals(camera, finding.sender)
        assertEquals(listOf(ap), finding.networks)
        assertEquals("Home", finding.name)
        assertTrue(finding.senderName!!.startsWith("Front door (") && "44:19:B6:01:02:03" in finding.senderName!!)

        // One sweep without it changes nothing, two do.
        sweep(store, 4, 0, camera)
        sweep(store, 5)
        assertEquals(1, findings(store).size)
        sweep(store, 6)
        assertTrue(findings(store).isEmpty())
    }

    /** A film someone watches fills the access point's counter and nobody's uplink. */
    @Test
    fun aBusyNetworkIsNotAStreamWhenClientsAreCounted() {
        val store = NetworkStore()
        val viewer = client(12_000, 120, 400_000, 13, address = mac(2, 2, 2, 2, 2, 2))
        for (id in 1..4) sweep(store, id, 500_000, viewer)
        assertTrue(findings(store).isEmpty())
        assertEquals(listOf(viewer), store.clientTraffic)
    }

    /** An older sensor sends no client records: the access point is judged, as before. */
    @Test
    fun anOlderSensorStillFlagsTheNetwork() {
        val store = NetworkStore()
        for (id in 1..3) sweep(store, id, 31_000, counts = false)
        val finding = findings(store).single()
        assertEquals(Threats.Kind.STREAMING, finding.kind)
        assertNull(finding.sender)
    }

    @Test
    fun aSenderIsCalledByWhatIsKnown() {
        val random = client(78_000, 60, 0, 13, address = mac(0x02, 1, 2, 3, 4, 5))
        assertEquals("02:01:02:03:04:05", Threats.senderName(random) { null })
        assertEquals("Front door (02:01:02:03:04:05)", Threats.senderName(random) { "Front door" })
        val pi = client(78_000, 60, 0, 13, address = mac(0xB8, 0x27, 0xEB, 3, 4, 5))
        assertEquals("${pi.mac.vendor()} (B8:27:EB:03:04:05)", Threats.senderName(pi) { null })
    }
}
