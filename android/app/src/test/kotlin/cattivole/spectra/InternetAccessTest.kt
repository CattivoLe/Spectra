package cattivole.spectra

import cattivole.spectra.core.AccessCheck
import cattivole.spectra.core.AccessReport
import cattivole.spectra.core.InternetAccess
import cattivole.spectra.core.LinkReport
import cattivole.spectra.core.LinkStatus
import cattivole.spectra.core.ResourceReach
import cattivole.spectra.core.SavedConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the iOS `InternetAccessTests`. */
class InternetAccessTest {
    @Test
    fun theLightFollowsWhoAnswers() {
        assertEquals(InternetAccess.FULL, AccessCheck.verdict(5, 5))
        assertEquals(InternetAccess.FULL, AccessCheck.verdict(2, 0))
        // One foreign answer next to a working allow-list is a leak, not the open internet.
        assertEquals(InternetAccess.WHITELIST_ONLY, AccessCheck.verdict(1, 4))
        assertEquals(InternetAccess.WHITELIST_ONLY, AccessCheck.verdict(0, 1))
        assertEquals(InternetAccess.FULL, AccessCheck.verdict(1, 0))
        assertEquals(InternetAccess.NONE, AccessCheck.verdict(0, 0))
    }

    @Test
    fun typedSitesAreReducedToAHost() {
        assertEquals("youtube.com", AccessCheck.normalize("YouTube.com"))
        assertEquals("www.youtube.com", AccessCheck.normalize(" https://www.youtube.com/watch?v=1 "))
        assertEquals("example.org", AccessCheck.normalize("user@example.org:8443/path"))
        assertEquals("example.com", AccessCheck.normalize("example.com."))
        assertNull(AccessCheck.normalize("localhost"))
        assertNull(AccessCheck.normalize("two words.com"))
        assertNull(AccessCheck.normalize(""))
    }

    @Test
    fun theSavedListIsReadBack() {
        assertEquals(AccessCheck.defaultResources, AccessCheck.parseList(null))
        assertEquals(listOf("youtube.com", "example.org"), AccessCheck.parseList("youtube.com\nexample.org\nyoutube.com\nrubbish"))
        // An emptied list stays empty: the defaults do not come back.
        assertTrue(AccessCheck.parseList("").isEmpty())
    }

    @Test
    fun theVerdictIsKeptWithASavedConnection() {
        val down = AccessCheck.openHosts.map { ResourceReach(it, false, null) }
        val up = AccessCheck.listedHosts.map { ResourceReach(it, true, 40) }
        val report = LinkReport().apply {
            status = LinkStatus.ONLINE
            access = AccessReport(down, up)
        }
        assertEquals(InternetAccess.WHITELIST_ONLY, report.access?.verdict)
        val saved = SavedConnection.from(report, viaVpn = false, viaCellular = true)!!
        val back = SavedConnection.fromJson(saved.toJson())!!
        assertEquals(saved.access, back.access)
        assertTrue(back.viaCellular)
        assertFalse(SavedConnection.fromJson(saved.toJson().apply { remove("viaCellular") })!!.viaCellular)
    }
}

class BlockedServicesTest {
    @Test
    fun blockedServicesTurnTheLightYellow() {
        // Google and GitHub open, YouTube and Instagram do not: filtered, not full.
        assertEquals(InternetAccess.FILTERED, AccessCheck.verdict(5, 5, 4))
        // One failing service can be that site having a bad minute.
        assertEquals(InternetAccess.FULL, AccessCheck.verdict(5, 5, 1))
        // An allow-list is still told apart from selective blocking.
        assertEquals(InternetAccess.WHITELIST_ONLY, AccessCheck.verdict(0, 3, 6))
        val open = AccessCheck.openHosts.map { ResourceReach(it, true, 30) }
        val blocked = AccessCheck.blockedHosts.map { ResourceReach(it, it == "www.linkedin.com", null) }
        val report = AccessReport(open, emptyList(), blocked)
        assertEquals(InternetAccess.FILTERED, report.verdict)
        assertEquals(AccessCheck.blockedHosts.size - 1, report.unreachable.size)
        assertEquals(report, AccessReport.fromJson(report.toJson()))
        // Checks saved before blocked services were asked keep their verdict.
        val old = AccessReport.fromJson(org.json.JSONObject("""{"open":[],"listed":[]}"""))
        assertNull(old.blocked)
        assertEquals(InternetAccess.FULL, AccessReport(open, emptyList()).verdict)
    }
}
