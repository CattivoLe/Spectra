package cattivole.spectra

import cattivole.spectra.core.HopPlace
import cattivole.spectra.core.RouteTrace
import cattivole.spectra.core.TraceHop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the iOS `RouteTraceTests`. */
class RouteTraceTest {
    @Test
    fun targetsAreReadFromWhatWasTyped() {
        assertEquals("google.com", RouteTrace.target(" Google.com "))
        assertEquals("ya.ru", RouteTrace.target("https://ya.ru/search?q=1"))
        assertEquals("1.1.1.1", RouteTrace.target("1.1.1.1"))
        assertEquals("example.com", RouteTrace.target("example.com:8443"))
        assertEquals("2606:4700:4700::1111", RouteTrace.target("2606:4700:4700::1111"))
        assertEquals("2606:4700:4700::1111", RouteTrace.target("[2606:4700:4700::1111]:443"))
        assertNull(RouteTrace.target("two words"))
        assertNull(RouteTrace.target(""))
    }

    @Test
    fun onlyPublicAddressesArePlaced() {
        assertTrue(RouteTrace.isPublic("8.8.8.8"))
        assertFalse(RouteTrace.isPublic("192.168.1.1"))
        assertFalse(RouteTrace.isPublic("10.20.0.1"))
        assertFalse(RouteTrace.isPublic("172.20.0.1"))
        assertTrue(RouteTrace.isPublic("172.32.0.1"))
        assertFalse(RouteTrace.isPublic("100.64.0.1"))
        assertTrue(RouteTrace.isPublic("2a00:1450::1"))
        assertFalse(RouteTrace.isPublic("fe80::1"))
    }

    @Test
    fun ipwhoRepliesBecomePlacesAndAPath() {
        val json = """{"ip":"8.8.8.8","success":true,"latitude":37.386,"longitude":-122.0838,"city":"Mountain View","country_code":"US","connection":{"isp":"Google LLC"}}"""
        val place = RouteTrace.place(json)!!
        assertEquals("Mountain View", place.city)
        assertEquals("Google LLC", place.isp)
        assertNull(RouteTrace.place("""{"success":false}"""))
        val near = HopPlace(37.3861, -122.0839)
        val far = HopPlace(52.37, 4.89)
        val hops = listOf(TraceHop("192.168.1.1", 2), TraceHop("8.8.4.4", 9), TraceHop(null, null), TraceHop("8.8.8.8", 10), TraceHop("9.9.9.9", 40))
        // Hop 4 is in the same spot as hop 2 and folds into it.
        assertEquals(listOf(2, 5), RouteTrace.path(hops, mapOf("8.8.4.4" to place, "8.8.8.8" to near, "9.9.9.9" to far)).map { it.first })
    }
}
