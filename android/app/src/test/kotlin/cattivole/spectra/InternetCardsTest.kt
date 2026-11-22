package cattivole.spectra

import cattivole.spectra.assistant.AssistantActions
import cattivole.spectra.assistant.AssistantCards
import cattivole.spectra.assistant.AssistantDigest
import cattivole.spectra.assistant.AssistantSnapshot
import cattivole.spectra.assistant.AssistantTopic
import cattivole.spectra.assistant.CardAction
import cattivole.spectra.assistant.CardCheck
import cattivole.spectra.assistant.CardPrompts
import cattivole.spectra.assistant.CardRisk
import cattivole.spectra.assistant.CardStep
import cattivole.spectra.assistant.CardSubject
import cattivole.spectra.assistant.CardSubjects
import cattivole.spectra.assistant.InternetCards
import cattivole.spectra.assistant.LinkShelf
import cattivole.spectra.core.DomainOutcome
import cattivole.spectra.core.DomainReport
import cattivole.spectra.core.LinkInspector
import cattivole.spectra.core.SiteCertificate
import cattivole.spectra.core.SiteReport
import cattivole.spectra.platform.RedirectTrace
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** The Internet and link summary cards: findings, their order, the step buttons, and what the model is given. */
class InternetCardsTest {
    private val original = Locale.getDefault()
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Before
    fun english() = Locale.setDefault(Locale.ENGLISH)

    @After
    fun restore() = Locale.setDefault(original)

    private val healthy = AssistantSnapshot.Link(
        checkedAt = now - 5 * 60_000L, status = "online", gatewayMs = 4, dnsMs = 21, webMs = 140,
        publicIp = "203.0.113.7", provider = "Example Telecom", country = "RS",
        downMbps = 92.4, upMbps = 41.0, latencyMs = 18, jitterMs = 3, intercepted = false, resolverLies = false,
        access = "full", gatewayOk = true, dnsOk = true, webOk = true, resolverName = "Example DNS", resolverCountry = "RS",
        tlsReached = 4, exposureChecked = true, bufferbloat = "A", via = "wifi",
    )

    private fun calls(steps: List<CardStep>) = steps.map { it.call }

    /** Every step is a call the actions can run as it is, with a label and no question mark. */
    private fun assertRunnable(steps: List<CardStep>) {
        for (step in steps) {
            assertNotNull(step.call, AssistantActions.Name.of(step.tool))
            if (step.call.startsWith("{")) JSONObject(step.call)
            assertTrue(step.label, step.label.isNotBlank() && !step.label.endsWith("?"))
        }
    }

    private fun trace(host: String) = """{"tool": "trace_route", "host": "$host"}"""

    // MARK: - Internet

    @Test
    fun withNothingMeasuredTheCardOnlyOffersChecks() {
        val facts = InternetCards.internet(AssistantSnapshot(), now)
        assertTrue(facts.awaitsCheck)
        assertTrue(facts.findings.all { it.risk == CardRisk.INFO })
        assertEquals(listOf("check_connection", "speed_test", "check_sites"), calls(facts.steps))
        assertEquals("Check the connection", facts.steps.first().label)
        assertRunnable(facts.steps)
        // Through the snapshot as well: the ✨ on any internet screen opens this card.
        assertEquals(facts, AssistantCards.facts(CardSubject.Internet, AssistantSnapshot(), now))
    }

    @Test
    fun aHealthyConnectionReadsGreenAndOffersOnlyRefreshes() {
        val facts = InternetCards.internet(AssistantSnapshot(link = healthy), now)
        assertFalse(facts.awaitsCheck)
        assertTrue(facts.findings.none { it.risk == CardRisk.CRITICAL || it.risk == CardRisk.WARNING })
        assertTrue(facts.findings.any { it.risk == CardRisk.OK && it.text.contains("genuine") })
        assertTrue(facts.findings.any { it.text.contains("203.0.113.7") && it.text.contains("Example Telecom") })
        // Nothing wrong: no trace and no speed test; the sites were never tried, and the check can be run again.
        assertEquals(listOf("check_sites", "check_connection"), calls(facts.steps))
        assertEquals("Check the connection again", facts.steps.last().label)
        assertEquals("Internet · Example Telecom", facts.title)
        assertRunnable(facts.steps)
    }

    @Test
    fun interceptionAndOpenPortsComeFirst() {
        val link = healthy.copy(intercepted = true, exposedPorts = listOf(22, 3389), resolverLies = true)
        val facts = InternetCards.internet(AssistantSnapshot(link = link), now)
        assertEquals(CardRisk.CRITICAL, facts.findings[0].risk)
        assertTrue(facts.findings[0].text.contains("re-signed"))
        assertTrue(facts.findings[1].text.contains("22, 3389"))
        assertTrue(facts.findings.any { it.risk == CardRisk.WARNING && it.text.contains("names that do not exist") })
        assertTrue(facts.findings.none { it.text.contains("genuine") })
        // The model reads the same in its data.
        assertTrue(facts.data.contains("re-signed on the way"))
        assertTrue(facts.data.contains("reachable from the internet: 22, 3389"))
    }

    @Test
    fun aSiteThatDoesNotOpenGetsATraceToIt() {
        val link = healthy.copy(access = "filtered", blocked = listOf("www.youtube.com", "www.instagram.com"))
        val sites = listOf(AssistantSnapshot.SiteReach("youtube.com", false), AssistantSnapshot.SiteReach("github.com", true, 90))
        val facts = InternetCards.internet(AssistantSnapshot(link = link, sites = sites), now)
        val warnings = facts.findings.filter { it.risk == CardRisk.WARNING }.map { it.text }
        assertTrue(warnings.any { it.contains("www.youtube.com, www.instagram.com") })
        assertTrue(warnings.any { it.contains("Your sites that do not open: youtube.com") })
        assertTrue(facts.findings.any { it.risk == CardRisk.OK && it.text.contains("github.com") })
        assertEquals(listOf(trace("youtube.com"), "check_sites", "check_connection"), calls(facts.steps))
        assertEquals("Trace the route to youtube.com", facts.steps[0].label)
        assertEquals("Check your sites again", facts.steps[1].label)
        assertTrue(facts.data.contains("- Do not open: youtube.com"))
        assertTrue(facts.data.contains("- Open: github.com (90 ms)"))
        assertRunnable(facts.steps)
    }

    @Test
    fun noInternetOffersACheckAgainAndATraceOut() {
        val link = AssistantSnapshot.Link(checkedAt = now - 60_000L, status = "lan", gatewayMs = 3, gatewayOk = true, dnsOk = false, webOk = false)
        val facts = InternetCards.internet(AssistantSnapshot(link = link), now)
        assertEquals(CardRisk.CRITICAL, facts.findings.first().risk)
        assertTrue(facts.findings.first().text.contains("router answers"))
        assertEquals(listOf("check_connection", trace("1.1.1.1"), "speed_test"), calls(facts.steps))
        assertTrue(facts.data.contains("Did not answer: DNS, web"))
    }

    @Test
    fun aSlowLineAsksForAnotherSpeedTest() {
        val link = healthy.copy(downMbps = 3.2, upMbps = 0.8, latencyMs = 220, jitterMs = 45, bufferbloat = "F")
        val facts = InternetCards.internet(AssistantSnapshot(link = link, sites = listOf(AssistantSnapshot.SiteReach("ya.ru", true))), now)
        val warnings = facts.findings.filter { it.risk == CardRisk.WARNING }.map { it.text }
        assertTrue(warnings.any { it.contains("Slow download: 3.2 Mbit/s") })
        assertTrue(warnings.any { it.contains("220 ms") })
        assertTrue(warnings.any { it.contains("45 ms") })
        assertTrue(warnings.any { it.contains("grade F") })
        // High latency: where it grows is worth a trace; then the speed again.
        assertEquals(listOf(trace("1.1.1.1"), "speed_test", "check_connection"), calls(facts.steps))
        assertEquals("Measure the speed again", facts.steps[1].label)
        assertTrue(facts.data.contains("grade F"))
    }

    @Test
    fun aDownloadSampleAloneOffersTheFullSpeedTest() {
        // The connection check measures the download only: upload and latency under load need the full test.
        val sample = healthy.copy(upMbps = null, bufferbloat = null, jitterMs = null)
        val facts = InternetCards.internet(AssistantSnapshot(link = sample, sites = listOf(AssistantSnapshot.SiteReach("ya.ru", true))), now)
        assertEquals(listOf("speed_test", "check_connection"), calls(facts.steps))
        assertEquals("Measure the speed fully", facts.steps.first().label)
        assertTrue(facts.findings.any { it.text == "Speed: 92.4 Mbit/s down." })
    }

    @Test
    fun anOldCheckIsOfferedAgainFirst() {
        val facts = InternetCards.internet(AssistantSnapshot(link = healthy.copy(checkedAt = now - 3 * 3_600_000L)), now)
        assertTrue(facts.findings.any { it.text == "Checked 3 h ago: the results may be out of date." })
        assertEquals("check_connection", facts.steps.first().call)
    }

    @Test
    fun onlyASpeedTestStillMakesACard() {
        val speed = AssistantSnapshot.Link(checkedAt = now - 60_000L, downMbps = 55.0, upMbps = 12.0, latencyMs = 25)
        val facts = InternetCards.internet(AssistantSnapshot(link = speed), now)
        assertFalse(facts.awaitsCheck)
        assertTrue(facts.findings.any { it.text == "Speed: 55.0 Mbit/s down, 12.0 up." })
        assertTrue(facts.findings.any { it.text.startsWith("The connection has not been checked yet") })
        assertEquals("check_connection", facts.steps.first().call)
        assertFalse("speed_test" in calls(facts.steps))
    }

    @Test
    fun aTraceThatStopsIsReadAndOfferedAgain() {
        val route = AssistantSnapshot.Route(
            target = "example.org", address = "93.184.215.14",
            hops = listOf(
                AssistantSnapshot.RouteHop("192.168.1.1", 3),
                AssistantSnapshot.RouteHop("10.10.0.1", 12),
                AssistantSnapshot.RouteHop("198.51.100.9", 130, "Frankfurt, DE · Example Transit"),
                AssistantSnapshot.RouteHop(null, null),
                AssistantSnapshot.RouteHop(null, null),
            ),
        )
        assertFalse(route.reached)
        assertEquals(2, route.silentTail)
        assertEquals(2 to 118, InternetCards.slowestHop(route.hops))
        val facts = InternetCards.internet(AssistantSnapshot(link = healthy, route = route), now)
        assertTrue(facts.findings.any { it.text.contains("stops after 3 hops") })
        assertTrue(facts.findings.any { it.text == "The delay grows most at hop 3 (198.51.100.9 · Frankfurt, DE · Example Transit): +118 ms." })
        assertTrue(trace("example.org") in calls(facts.steps))
        assertEquals("Trace example.org again", facts.steps.first { it.tool == "trace_route" }.label)
        // The short summary gets the outcome and the places; the deep analysis every hop.
        assertTrue(facts.data.contains("Traceroute to example.org (93.184.215.14): did not reach it; the last 2 hops did not answer"))
        assertTrue(facts.data.contains("- Places along the way: Frankfurt, DE · Example Transit"))
        assertTrue(facts.data.contains("- The delay grows most at hop 3 (198.51.100.9): +118 ms"))
        assertFalse(facts.data.contains("4. *"))
        assertTrue(facts.deepData.contains("3. 198.51.100.9 · 130 ms · Frankfurt, DE · Example Transit"))
        assertTrue(facts.deepData.contains("4. *"))
    }

    @Test
    fun aTraceThatArrivesIsNotOfferedAgain() {
        val route = AssistantSnapshot.Route(
            target = "1.1.1.1", address = "1.1.1.1",
            hops = listOf(AssistantSnapshot.RouteHop("192.168.1.1", 2), AssistantSnapshot.RouteHop(null, null), AssistantSnapshot.RouteHop("1.1.1.1", 9)),
        )
        assertTrue(route.reached)
        assertEquals(null, InternetCards.slowestHop(route.hops))
        val facts = InternetCards.internet(AssistantSnapshot(link = healthy, route = route), now)
        assertTrue(facts.findings.any { it.risk == CardRisk.OK && it.text == "The path to 1.1.1.1 takes 3 hops: it answers." })
        assertFalse("trace_route" in facts.steps.map { it.tool })
        assertFalse(facts.awaitsCheck)
    }

    @Test
    fun theConnectionSectionCarriesTheNewChecks() {
        val link = healthy.copy(via = "vpn", warp = true, blocked = listOf("x.com"))
        val text = AssistantDigest.section(AssistantTopic.LINK, AssistantSnapshot(link = link, sites = listOf(AssistantSnapshot.SiteReach("ya.ru", true, 40))), now)
        assertTrue(text.contains("Checked through the VPN"))
        assertTrue(text.contains("DNS resolver the servers see: Example DNS · RS"))
        assertTrue(text.contains("HTTPS: genuine certificates on 4 reference sites"))
        assertTrue(text.contains("No usual port of the public address answers"))
        assertTrue(text.contains("Often-blocked services that did not open: x.com"))
        assertTrue(text.contains("The user's own sites, 1 tried:\n- Open: ya.ru (40 ms)\n- Do not open: none"))
        assertTrue(text.contains("Cloudflare WARP"))
    }

    // MARK: - A link

    @Test
    fun aPhishingLinkLeadsWithTheVerdictAndOffersItsChecks() {
        val facts = InternetCards.link("Срочно: https://appleid.apple.com.verify-login.xyz/signin", site = null, redirects = null, now = now)
        assertEquals(CardSubject.Link("https://appleid.apple.com.verify-login.xyz/signin"), facts.subject)
        assertEquals("verify-login.xyz", facts.title)
        assertEquals(CardRisk.CRITICAL, facts.findings.first().risk)
        assertTrue(facts.findings.first().text.contains("phishing"))
        assertEquals(
            listOf(
                """{"tool": "check_site", "text": "https://appleid.apple.com.verify-login.xyz/signin"}""",
                """{"tool": "expand_redirects", "text": "https://appleid.apple.com.verify-login.xyz/signin"}""",
            ),
            calls(facts.steps),
        )
        assertRunnable(facts.steps)
        assertFalse(facts.awaitsCheck)
        assertTrue(facts.data.contains("The app's verdict on the address: dangerous"))
        assertTrue(facts.data.contains("Certificate and domain registry: not checked yet."))
    }

    @Test
    fun aYoungDomainAndARedirectElsewhereAreFindings() {
        val link = "https://shop.example.com/sale"
        val site = SiteReport(
            "shop.example.com", SiteCertificate("Let's Encrypt", now - 2 * day, now + 88 * day, true, null), null,
            DomainOutcome.Found(DomainReport("example.com", registered = now - 6 * day, registrar = "Some Registrar")),
        )
        val redirects = RedirectTrace(
            listOf(RedirectTrace.Hop(link, 302), RedirectTrace.Hop("https://cheap-deals.top/x", 200)),
            "https://cheap-deals.top/x",
            null,
        )
        val facts = InternetCards.link(link, site, redirects, now)
        val texts = facts.findings.map { it.text }
        assertEquals(CardRisk.CRITICAL, facts.findings.first().risk)
        assertTrue(texts.first().contains("registered 6 days ago"))
        assertTrue(texts.any { it.contains("issued 2 days ago") })
        assertTrue(texts.any { it == "Redirects once and ends at cheap-deals.top." })
        assertTrue(texts.any { it == "It leads to another site: cheap-deals.top." })
        // Both checks are in: no more buttons, no "not checked yet".
        assertTrue(facts.steps.isEmpty())
        assertTrue(texts.none { it.contains("not been checked") })
        assertTrue(facts.data.contains("registered") && facts.data.contains("6 days ago") && facts.data.contains("Some Registrar"))
        assertTrue(facts.data.contains("Redirects: 1, followed by their headers only:"))
        assertTrue(facts.data.contains("2. https://cheap-deals.top/x → HTTP 200"))
        assertTrue(facts.data.contains("Final address: https://cheap-deals.top/x"))
    }

    @Test
    fun aLinkThatCannotBeRequestedIsNotCalledARedirect() {
        val link = "https://appleid.apple.com.verify-login.xyz/signin"
        val text = InternetCards.redirectText(RedirectTrace(emptyList(), link, RedirectTrace.Stop.Failed("Unable to resolve host")))
        assertTrue(text.startsWith("Redirects: the link could not be requested at all"))
        assertFalse(text.contains("Final address"))
        assertTrue(text.contains("Stopped: Unable to resolve host"))
        val direct = InternetCards.redirectText(RedirectTrace(listOf(RedirectTrace.Hop(link, 200)), link, null))
        assertTrue(direct.startsWith("Redirects: none"))
    }

    @Test
    fun aMailtoOrABareTextHasNoWebChecks() {
        val none = InternetCards.link("привет, как дела", null, null, now)
        assertTrue(none.awaitsCheck)
        assertTrue(none.steps.isEmpty())
        val clean = InternetCards.link("https://www.apple.com/iphone/", null, RedirectTrace(listOf(RedirectTrace.Hop("https://www.apple.com/iphone/", 200)), "https://www.apple.com/iphone/", null), now)
        assertTrue(clean.findings.any { it.risk == CardRisk.OK && it.text.contains("no warning signs") })
        assertTrue(clean.findings.any { it.text == "No redirects: the link opens where it says." })
        assertEquals(listOf("check_site"), clean.steps.map { it.tool })
    }

    @Test
    fun theShelfKeepsWhatEitherSideFound() {
        val shelf = LinkShelf()
        val text = "Look: https://appleid.apple.com.verify-login.xyz/signin now"
        val link = LinkInspector.inspect(text)!!.link
        // The key is stable: inspecting the link again gives the same link.
        assertEquals(link, LinkInspector.inspect(link)!!.link)
        assertEquals(2, shelf.facts(text, now).steps.size)
        shelf.keepRedirects(link, RedirectTrace(listOf(RedirectTrace.Hop(link, 404)), link, null))
        assertEquals(listOf("check_site"), shelf.facts(text, now).steps.map { it.tool })
        for (index in 0 until LinkShelf.KEEP) shelf.keepSite("https://site$index.example/", SiteReport("site$index.example", null, "refused", DomainOutcome.Skipped))
        // Only the latest links are kept.
        assertEquals(null, shelf.entry(link).redirects)
        assertNotNull(shelf.entry("https://site7.example/").site)
    }

    @Test
    fun theRegistryIsAskedAboutTheRightName() {
        assertEquals("verify-login.xyz", LinkShelf.registryName(LinkInspector.inspect("https://appleid.apple.com.verify-login.xyz/")!!))
        assertEquals("xn--80ak6aa92e.com", LinkShelf.registryName(LinkInspector.inspect("https://xn--80ak6aa92e.com/")!!))
        assertEquals(null, LinkShelf.registryName(LinkInspector.inspect("http://192.168.1.1/admin")!!))
        assertEquals("http://paypa1.com/login", LinkShelf.start(LinkInspector.inspect("http://paypa1.com/login")!!))
    }

    // MARK: - Saving and prompts

    @Test
    fun theNewSubjectsSurviveSaving() {
        for (subject in listOf(CardSubject.Internet, CardSubject.Link("https://example.com:8443/a?b=c#d"))) {
            assertEquals(subject, CardSubjects.decode(CardSubjects.encode(subject)))
        }
    }

    @Test
    fun aCheckOnTheInternetCardIsAskedAboutTheCheckAlone() {
        val facts = InternetCards.internet(AssistantSnapshot(link = healthy), now)
        val system = CardPrompts.system(CardAction.CHECK, CardSubject.Kind.INTERNET, Locale("ru"), tool = "trace_route")
        assertTrue(system.contains("traced the path"))
        assertTrue(system.contains("Answer in Russian."))
        // The subject's own brief stays out after a check, or the model answers it instead of the check.
        assertFalse(system.contains("The subject is the user's internet connection"))
        val user = CardPrompts.user(facts, CardAction.CHECK, CardCheck("traceroute", "Traceroute to 1.1.1.1: reached in 7 hops", "trace_route"), "Интернет работает, всё в порядке.")
        assertTrue(user.contains("<check name=\"traceroute\">"))
        assertTrue(user.contains("do not repeat"))
        assertFalse(user.contains("Findings of the app"))
        assertFalse(user.contains("<data>"))
        val summary = CardPrompts.system(CardAction.SUMMARY, CardSubject.Kind.LINK, Locale.ENGLISH)
        assertTrue(summary.contains("one link the user may open"))
        for (tool in listOf("speed_test", "check_sites", "check_site", "expand_redirects", "check_connection")) {
            assertTrue(tool, CardPrompts.system(CardAction.CHECK, CardSubject.Kind.INTERNET, tool = tool).contains("This check"))
        }
    }

    @Test
    fun theInternetStepsHaveWordsForTheCard() {
        // A step's result is headed by its title and shown with its running label while it works.
        for (name in listOf("trace_route", "check_sites", "check_site", "expand_redirects")) {
            assertTrue(AssistantActions.title(name) != name && AssistantActions.runningLabel(name).endsWith("…"))
        }
    }
}
