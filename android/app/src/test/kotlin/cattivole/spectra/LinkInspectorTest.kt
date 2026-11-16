package cattivole.spectra

import cattivole.spectra.core.LinkInspection
import cattivole.spectra.core.LinkInspection.Kind
import cattivole.spectra.core.LinkInspection.Verdict
import cattivole.spectra.core.LinkInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the iOS `LinkInspectorTests`. */
class LinkInspectorTest {
    private fun check(text: String): LinkInspection = LinkInspector.inspect(text)!!

    private fun kinds(inspection: LinkInspection): Set<Kind> = inspection.findings.map { it.kind }.toSet()

    @Test
    fun officialSiteRaisesNoFlags() {
        val inspection = check("https://www.apple.com/iphone/")
        assertEquals("apple.com", inspection.site)
        assertEquals(Verdict.NO_FLAGS, inspection.verdict)
        assertEquals(setOf(Kind.OFFICIAL), kinds(inspection))
    }

    @Test
    fun brandInFrontOfAnotherSiteIsPhishing() {
        val inspection = check("https://appleid.apple.com.verify-login.xyz/signin")
        assertEquals("verify-login.xyz", inspection.site)
        assertEquals(Verdict.DANGEROUS, inspection.verdict)
        assertTrue(kinds(inspection).containsAll(listOf(Kind.BRAND_IN_SUBDOMAIN, Kind.RISKY_ZONE, Kind.BAIT_WORDS)))
    }

    @Test
    fun digitForALetterIsALookalike() {
        val inspection = check("http://paypa1.com/login")
        assertEquals("paypa1.com", inspection.site)
        assertEquals(Verdict.DANGEROUS, inspection.verdict)
        assertTrue(kinds(inspection).containsAll(listOf(Kind.LOOKALIKE, Kind.INSECURE)))
    }

    @Test
    fun cyrillicHomographIsCaught() {
        val inspection = check("https://xn--80ak6aa92e.com/")
        assertEquals("аррӏе.com", inspection.host)
        assertEquals("xn--80ak6aa92e.com", inspection.asciiHost)
        assertEquals(Verdict.DANGEROUS, inspection.verdict)
        assertTrue(kinds(inspection).containsAll(listOf(Kind.LOOKALIKE, Kind.LOOKALIKE_LETTERS, Kind.PUNYCODE)))
    }

    @Test
    fun oneCyrillicLetterAmongLatinIsCaught() {
        val inspection = check("https://pаypal.com/")
        assertTrue(Kind.MIXED_SCRIPTS in kinds(inspection))
        assertEquals(Verdict.DANGEROUS, inspection.verdict)
    }

    @Test
    fun cyrillicSiteInACyrillicZoneIsFine() {
        val inspection = check("https://xn--d1acufc.xn--p1ai/")
        assertEquals("домен.рф", inspection.host)
        assertEquals(Verdict.NO_FLAGS, inspection.verdict)
        assertTrue(Kind.FOREIGN_SCRIPT in kinds(inspection))
    }

    @Test
    fun userInfoHidesTheRealHost() {
        val inspection = check("https://www.google.com@evil.example/x")
        assertEquals("evil.example", inspection.site)
        assertTrue(Kind.USER_INFO in kinds(inspection))
        assertEquals(Verdict.DANGEROUS, inspection.verdict)
    }

    @Test
    fun ipAddressInsteadOfAName() {
        val inspection = check("http://203.0.113.7/bank")
        assertEquals("203.0.113.7", inspection.site)
        assertTrue(Kind.IP_ADDRESS in kinds(inspection))
        assertEquals(Verdict.DANGEROUS, inspection.verdict)
        assertTrue(Kind.IP_ADDRESS in kinds(check("http://3232235777/")))
    }

    @Test
    fun shortLinkAsksForACloserLook() {
        val inspection = check("https://bit.ly/3AbCdE")
        assertEquals(setOf(Kind.SHORTENER), kinds(inspection))
        assertEquals(Verdict.CAUTION, inspection.verdict)
    }

    @Test
    fun linkIsFoundInsideAMessage() {
        val inspection = check("Здравствуйте! Ваш возврат оформлен: https://gosuslugi-vozvrat.ru/form. Спасибо")
        assertEquals("https://gosuslugi-vozvrat.ru/form", inspection.link)
        assertEquals("gosuslugi-vozvrat.ru", inspection.site)
        assertTrue(kinds(inspection).containsAll(listOf(Kind.BRAND_IN_DOMAIN, Kind.BAIT_WORDS)))
        assertEquals(Verdict.SUSPICIOUS, inspection.verdict)
        assertEquals("example.org/a", check("смотри example.org/a, там всё").link)
    }

    @Test
    fun mailFilterWrapperIsOpened() {
        val inspection = check("https://nam12.safelinks.protection.outlook.com/?url=https%3A%2F%2Fpaypa1.com%2Flogin&data=05")
        assertEquals(Verdict.NO_FLAGS, inspection.ownVerdict)
        assertEquals("paypa1.com", inspection.nested.firstOrNull()?.site)
        assertTrue(Kind.LEADS_ON in kinds(inspection))
        assertEquals(Verdict.DANGEROUS, inspection.verdict)
    }

    @Test
    fun freeHostingAccountIsTheSite() {
        val inspection = check("https://apple-support.github.io/")
        assertEquals("apple-support.github.io", inspection.site)
        assertTrue(kinds(inspection).containsAll(listOf(Kind.FREE_HOSTING, Kind.BRAND_IN_DOMAIN)))
        assertEquals(Verdict.SUSPICIOUS, inspection.verdict)
    }

    @Test
    fun twoLabelSuffixKeepsTheOwner() {
        val inspection = check("https://www.bbc.co.uk/news")
        assertEquals("bbc.co.uk", inspection.site)
        assertEquals(Verdict.NO_FLAGS, inspection.verdict)
    }

    @Test
    fun bareAddressWithoutScheme() {
        val inspection = check("apple.com.ru-login.top")
        assertEquals("https", inspection.scheme)
        assertEquals("ru-login.top", inspection.site)
        assertTrue(Kind.BRAND_IN_SUBDOMAIN in kinds(inspection))
    }

    @Test
    fun typosquatIsCaughtButSimilarWordsAreNot() {
        assertTrue(Kind.LOOKALIKE in kinds(check("https://micosoft.com")))
        assertFalse(Kind.LOOKALIKE in kinds(check("https://finance.com")))
    }

    @Test
    fun scriptLinksAreDangerous() {
        val inspection = check("javascript:alert(1)")
        assertEquals(setOf(Kind.NOT_WEB), kinds(inspection))
        assertEquals(Verdict.DANGEROUS, inspection.verdict)
        assertEquals(Verdict.NO_FLAGS, check("mailto:someone@example.com").verdict)
    }

    @Test
    fun unusualPortIsNoted() {
        assertTrue(Kind.UNUSUAL_PORT in kinds(check("https://example.com:8443/")))
    }

    @Test
    fun textWithoutALinkIsNotInspected() {
        assertNull(LinkInspector.inspect("привет, как дела"))
        assertNull(LinkInspector.inspect("   "))
    }

    @Test
    fun punycodeDecodes() {
        assertEquals("аррӏе", LinkInspector.punycodeDecode("80ak6aa92e"))
        assertEquals("домен", LinkInspector.punycodeDecode("d1acufc"))
        assertNull(LinkInspector.punycodeDecode("!!"))
    }
}
