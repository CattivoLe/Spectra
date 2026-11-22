package cattivole.spectra

import alexcattivo.localchat.kit.web.Passage
import alexcattivo.localchat.kit.web.SearchPayload
import alexcattivo.localchat.kit.web.SearchResult
import cattivole.spectra.assistant.CardAction
import cattivole.spectra.assistant.CardFacts
import cattivole.spectra.assistant.CardFinding
import cattivole.spectra.assistant.CardPrompts
import cattivole.spectra.assistant.CardRisk
import cattivole.spectra.assistant.CardSubject
import cattivole.spectra.assistant.CardWeb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** "More detail" with the web: what may go out as a query, and what the model reads back. */
class CardWebTest {
    @Test
    fun namesPeopleGaveTheirDevicesAreNotSent() {
        assertNull(CardWeb.personal("Alex's AirPods"))
        assertNull(CardWeb.personal("Alex’s iPhone"))
        assertNull(CardWeb.personal("iPhone Anna"))
        assertNull(CardWeb.personal("1b067baf05912b622b215c7a"))
        assertEquals("Momentum 3", CardWeb.personal("Momentum 3"))
        assertEquals("Galaxy Buds2 Pro", CardWeb.personal("Galaxy Buds2 Pro"))
        assertEquals("iPhone 15 Pro", CardWeb.personal("iPhone 15 Pro"))
    }

    @Test
    fun aProductNeedsAMakerOrAName() {
        assertNull(CardWeb.product(null, " "))
        assertEquals(CardWeb.Product("Xiaomi", null), CardWeb.product("Xiaomi", "xiaomi"))
        // Seen on the user's Samsung TV: the UPnP model carries the DLNA stack's name and version.
        assertEquals(CardWeb.Product("Samsung", "UE55U8092FUXXH"), CardWeb.product("Samsung", "UE55U8092FUXXH AllShare1.0"))
        assertEquals("iPhone 15 Pro", CardWeb.product("Apple", "iPhone 15 Pro")?.name)
    }

    private val facts = CardFacts(
        CardSubject.Host("192.168.1.95"), "Xiaomi · 192.168.1.95",
        listOf(CardFinding(CardRisk.OK, "Only UDP 54321 is open.")), emptyList(), "data", "deep data",
    )

    @Test
    fun theModelReadsTheWebAsNumberedSources() {
        val web = SearchPayload(
            query = "Xiaomi careli.fryer.maf02 specs security",
            provider = "Tavily",
            results = listOf(
                SearchResult("Mi Smart Air Fryer", "https://www.mi.com/fryer", "Smart air fryer"),
                SearchResult("Unused page", "https://example.com", "nothing"),
            ),
            evidence = listOf(Passage(0, null, "The Mi Smart Air Fryer 3.5L is controlled from the Mi Home app.", 0)),
        )
        val user = CardPrompts.user(facts, CardAction.DETAILS, web = web)
        assertTrue(user.contains("<web>"))
        assertTrue(user.contains("[1] Mi Smart Air Fryer (www.mi.com)"))
        assertFalse(user.contains("Unused page"))
        assertTrue(CardPrompts.system(CardAction.DETAILS, CardSubject.Kind.HOST, Locale.ENGLISH, web = true).contains("<web>"))
        assertFalse(CardPrompts.system(CardAction.DETAILS, CardSubject.Kind.HOST, Locale.ENGLISH).contains("<web>"))
        assertFalse(CardPrompts.user(facts, CardAction.DETAILS).contains("<web>"))
    }
}
