package cattivole.spectra

import cattivole.spectra.platform.NetConnect
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class NetConnectTest {
    @Test
    fun ipv4IsTriedBeforeIpv6() {
        val v6a = InetAddress.getByName("2606:4700::6810:1")
        val v4a = InetAddress.getByName("104.16.0.1")
        val v6b = InetAddress.getByName("2606:4700::6810:2")
        val v4b = InetAddress.getByName("104.16.0.2")
        // DNS order is kept within each family.
        assertEquals(listOf(v4a, v4b, v6a, v6b), NetConnect.ordered(listOf(v6a, v4a, v6b, v4b)))
        assertEquals(listOf(v6a), NetConnect.ordered(listOf(v6a)))
    }
}

class StreamAddressTest {
    @Test
    fun camerasGetTheirMakersStreamPath() {
        val url = cattivole.spectra.core.StreamAddress::url
        assertEquals("rtsp://192.168.1.60/Streaming/Channels/101", url("192.168.1.60", 554, false, "Hikvision"))
        assertEquals("rtsp://192.168.1.61:8554/cam/realmonitor?channel=1&subtype=0", url("192.168.1.61", 8554, false, "Dahua Technology"))
        assertEquals("rtsp://192.168.1.62/stream1", url("192.168.1.62", 554, false, "TP-LINK TECHNOLOGIES"))
        assertEquals("rtsp://192.168.1.63/", url("192.168.1.63", 554, false, null))
        // A port that answered RTSP on an unusual number still opens as a stream.
        assertEquals("rtsp://192.168.1.64:10554/", url("192.168.1.64", 10554, true, null))
        assertEquals("rtmp://192.168.1.65:1935/", url("192.168.1.65", 1935, false, null))
        assertEquals("rtsp://[fe80::1]/", url("fe80::1", 554, false, null))
        org.junit.Assert.assertNull(url("192.168.1.66", 80, false, "Hikvision"))
    }
}
