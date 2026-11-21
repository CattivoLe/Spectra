package cattivole.spectra

import cattivole.spectra.assistant.AssistantActions
import cattivole.spectra.assistant.AssistantCardRunner
import cattivole.spectra.assistant.AssistantCards
import cattivole.spectra.assistant.AssistantSnapshot
import cattivole.spectra.assistant.CardAction
import cattivole.spectra.assistant.CardFinding
import cattivole.spectra.assistant.CardPrompts
import cattivole.spectra.assistant.CardRisk
import cattivole.spectra.assistant.CardSubject
import cattivole.spectra.assistant.CardSubjects
import cattivole.spectra.core.CaptureAnalysis
import cattivole.spectra.core.CaptureSummary
import cattivole.spectra.core.NamedCount
import cattivole.spectra.core.Talker
import cattivole.spectra.core.TrafficFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** Summary cards: findings and buttons built by the app's own rules, how a card is saved, and its narrow prompts. */
class AssistantCardsTest {
    private val original = Locale.getDefault()
    private val now = 1_800_000_000_000L

    @Before
    fun english() = Locale.setDefault(Locale.ENGLISH)

    @After
    fun restore() = Locale.setDefault(original)

    private val router = AssistantSnapshot.Host(
        ip = "192.168.1.1", mac = "AA:BB:CC:00:00:01", name = "Keenetic", maker = "Keenetic", isGateway = true,
        portsScanned = true, ports = listOf("23 Telnet", "80 HTTP"),
        risks = listOf("Telnet (High risk)"), portRisks = listOf(AssistantSnapshot.PortRiskItem("Telnet: clear-text login", high = true)),
    )
    private val camera = AssistantSnapshot.Host(ip = "192.168.1.64", name = "IPC", maker = "Hikvision", isNew = true)
    private val phone = AssistantSnapshot.Host(ip = "192.168.1.20", name = "This phone", isThisPhone = true)

    private fun calls(steps: List<cattivole.spectra.assistant.CardStep>) = steps.map { it.call }

    /** Every step must be a call the actions can run as it is. */
    private fun assertRunnable(steps: List<cattivole.spectra.assistant.CardStep>) {
        for (step in steps) {
            assertNotNull(step.call, AssistantActions.Name.of(step.tool))
            if (step.call.startsWith("{")) JSONObject(step.call)
            assertTrue(step.label, step.label.isNotBlank() && !step.label.endsWith("?"))
        }
    }

    // MARK: - LAN

    @Test
    fun aHostWithARiskyPortLeadsItsCard() {
        val facts = AssistantCards.host(AssistantSnapshot(hosts = listOf(router, camera)), "192.168.1.1", now)
        assertEquals(CardRisk.CRITICAL, facts.findings.first().risk)
        assertTrue(facts.findings.first().text.contains("Telnet"))
        assertTrue(facts.findings.any { it.text.contains("router") })
        // Already scanned: the next scan goes deeper, and the router's forwarding table is worth a look.
        assertEquals(
            listOf("""{"tool": "scan_ports", "host": "192.168.1.1", "text": "all"}""", "check_port_forwarding"),
            calls(facts.steps),
        )
        assertRunnable(facts.steps)
        // Only this host goes to the short summary; the neighbours wait for the deep analysis.
        assertTrue(facts.data.contains("192.168.1.1"))
        assertFalse(facts.data.contains("192.168.1.64"))
        assertTrue(facts.deepData.contains("192.168.1.64"))
    }

    @Test
    fun anUnscannedHostOffersAPortScan() {
        val facts = AssistantCards.host(AssistantSnapshot(hosts = listOf(router, camera)), "192.168.1.64", now)
        assertEquals(CardRisk.WARNING, facts.findings.first().risk) // New on the network.
        assertTrue(facts.findings.any { it.risk == CardRisk.INFO && it.text.contains("Ports have not been checked") })
        assertEquals(listOf("""{"tool": "scan_ports", "host": "192.168.1.64"}"""), calls(facts.steps))
        assertEquals("IPC · Hikvision · 192.168.1.64", facts.title)
    }

    @Test
    fun aSuspiciousMarkIsCriticalAndAQuietHostIsFine() {
        val marked = camera.copy(trust = "suspicious", isNew = false)
        assertEquals(CardRisk.CRITICAL, AssistantCards.host(AssistantSnapshot(hosts = listOf(marked)), marked.ip, now).findings.first().risk)
        val quiet = camera.copy(isNew = false, portsScanned = true, trust = "trusted")
        val facts = AssistantCards.host(AssistantSnapshot(hosts = listOf(quiet)), quiet.ip, now)
        assertEquals(CardRisk.OK, AssistantCards.worst(facts.findings))
    }

    @Test
    fun aHostThatIsGoneAsksForANewScan() {
        val facts = AssistantCards.host(AssistantSnapshot(hosts = listOf(router)), "192.168.1.99", now)
        assertEquals(listOf(CardRisk.INFO), facts.findings.map { it.risk })
        assertEquals(listOf("scan_lan"), calls(facts.steps))
    }

    @Test
    fun theNetworkCardRanksEveryHostAndTheConnection() {
        val link = AssistantSnapshot.Link(checkedAt = now, intercepted = true, exposedPorts = listOf(8080))
        val facts = AssistantCards.lan(AssistantSnapshot(hosts = listOf(camera, router, phone), link = link, networkLabel = "Home"), now)
        val risks = facts.findings.map { it.risk }
        assertEquals(risks.sortedBy { it.ordinal }, risks)
        assertEquals(3, facts.findings.count { it.risk == CardRisk.CRITICAL }) // Interception, open port, Telnet.
        assertTrue(facts.findings.any { it.risk == CardRisk.WARNING && it.text.startsWith("IPC (192.168.1.64)") })
        assertTrue(facts.findings.none { it.text.contains("This phone") })
        assertTrue(facts.findings.any { it.text.contains("Devices on Home: 3") })
        assertTrue(facts.steps.size <= 3)
        assertTrue("check_port_forwarding" in calls(facts.steps))
        assertRunnable(facts.steps)
    }

    @Test
    fun anUnscannedNetworkStartsWithAScan() {
        val facts = AssistantCards.lan(AssistantSnapshot(), now)
        assertEquals(listOf("scan_lan", "check_connection"), calls(facts.steps))
        assertTrue(facts.findings.single().text.contains("not been scanned"))
    }

    // MARK: - Wi‑Fi

    private val home = AssistantSnapshot.Wifi("Home", "AA:BB:CC:00:00:10", -50, 6, "2.4 GHz", "WPA2", isCurrent = true)

    @Test
    fun encryptionIsRanked() {
        assertEquals(CardRisk.CRITICAL, AssistantCards.securityLevel("Open"))
        assertEquals(CardRisk.CRITICAL, AssistantCards.securityLevel("WEP"))
        assertEquals(CardRisk.WARNING, AssistantCards.securityLevel("WPA"))
        assertEquals(CardRisk.WARNING, AssistantCards.securityLevel("WPA/WPA2"))
        assertEquals(CardRisk.OK, AssistantCards.securityLevel("WPA2"))
        assertEquals(CardRisk.OK, AssistantCards.securityLevel("WPA3"))
        assertEquals(CardRisk.INFO, AssistantCards.securityLevel("Other"))
    }

    @Test
    fun aNetworkCardTakesOnlyItsOwnThreats() {
        val twin = AssistantSnapshot.Finding("critical", "Possible evil twin", "\"Home\" is broadcast by 2 access points", listOf("aa:bb:cc:00:00:10"))
        val elsewhere = AssistantSnapshot.Finding("warning", "Steady data stream", "\"Cafe\" moves data", listOf("11:22:33:44:55:66"))
        val crowd = (1..4).map { AssistantSnapshot.Wifi("N$it", "00:00:00:00:00:0$it", -70, 6, "2.4 GHz", "WPA2") }
        val s = AssistantSnapshot(wifi = listOf(home) + crowd, findings = listOf(twin, elsewhere), sensorConnected = true)
        val facts = AssistantCards.wifi(s, home.bssid, now)
        assertEquals(CardRisk.CRITICAL, facts.findings.first().risk)
        assertTrue(facts.findings.first().text.startsWith("Possible evil twin"))
        assertTrue(facts.findings.none { it.text.contains("Cafe") })
        assertTrue(facts.findings.any { it.risk == CardRisk.OK && it.text.contains("WPA2") })
        assertTrue(facts.findings.any { it.text.contains("Channel 6 is crowded") })
        assertEquals("""{"tool": "capture_pcap", "bssid": "AA:BB:CC:00:00:10"}""", facts.steps.first().call)
        assertRunnable(facts.steps)
        assertFalse(facts.data.contains("Cafe"))
    }

    @Test
    fun anOpenNetworkWithoutTheSensorCannotBeCaptured() {
        val open = home.copy(security = "Open")
        val facts = AssistantCards.wifi(AssistantSnapshot(wifi = listOf(open)), open.bssid, now)
        assertEquals(CardRisk.CRITICAL, facts.findings.first().risk)
        assertTrue(facts.findings.any { it.text.contains("Without the sensor") })
        assertEquals(listOf("scan_wifi"), calls(facts.steps))
    }

    // MARK: - Bluetooth

    private val tag = AssistantSnapshot.Bluetooth(
        "AirTag", company = "Apple", rssi = -48, minutesNearby = 25, tracker = "Find My tag", trackerLevel = "alert", away = true,
        address = "C4:00:00:00:00:01",
    )
    private val speaker = AssistantSnapshot.Bluetooth("Momentum 3", company = "Sennheiser", rssi = -62, address = "C4:00:00:00:00:02")

    @Test
    fun aTrackerAwayFromItsOwnerIsCritical() {
        val facts = AssistantCards.bluetooth(AssistantSnapshot(bluetooth = listOf(tag, speaker)), "c4:00:00:00:00:01", now)
        assertEquals(CardRisk.CRITICAL, facts.findings.first().risk)
        assertTrue(facts.findings.first().text.contains("25 min"))
        assertTrue(facts.findings.any { it.text.contains("very close") })
        assertFalse(facts.data.contains("Momentum"))
    }

    @Test
    fun aDeviceIsOfferedForAReadUntilItIsRead() {
        val before = AssistantCards.bluetooth(AssistantSnapshot(bluetooth = listOf(speaker)), speaker.address, now)
        assertEquals("""{"tool": "connect_bluetooth", "text": "Momentum 3"}""", before.steps.first().call)
        assertRunnable(before.steps)
        val read = AssistantSnapshot(bluetooth = listOf(speaker), gatt = "GATT of Momentum 3: Battery 80%", gattAddress = speaker.address)
        val after = AssistantCards.bluetooth(read, speaker.address, now)
        assertEquals(listOf("scan_bluetooth"), calls(after.steps))
        assertTrue(after.findings.any { it.risk == CardRisk.OK })
        assertTrue(after.data.contains("Battery 80%"))
        // Another device's read never rides along.
        val other = AssistantCards.bluetooth(read.copy(bluetooth = listOf(speaker, tag)), tag.address, now)
        assertFalse(other.data.contains("Battery"))
    }

    // MARK: - Traffic and captures

    private fun flow(id: Long, host: String, tls: Boolean, request: String? = null) =
        TrafficFlow(id, "TCP", "10.0.0.2", 40000, "93.184.216.34", if (tls) 443 else 80, host, request, "Browser", tls, 500, 5000, 0, 1000, true)

    @Test
    fun plainHttpIsAWarning() {
        val facts = AssistantCards.traffic(listOf(flow(1, "example.com", tls = false, request = "GET /"), flow(2, "api.example.org", tls = true)), "Current recording", "digest")
        assertEquals(CardRisk.WARNING, facts.findings.first().risk)
        assertTrue(facts.findings.first().text.contains("example.com"))
        assertTrue(facts.findings.none { it.risk == CardRisk.OK })
        assertTrue(facts.steps.isEmpty())
        val clean = AssistantCards.traffic(listOf(flow(2, "api.example.org", tls = true)), "Current recording", "digest")
        assertTrue(clean.findings.any { it.risk == CardRisk.OK })
        assertEquals(CardRisk.INFO, AssistantCards.traffic(emptyList(), "x", "d").findings.single().risk)
    }

    private fun capture(apps: List<NamedCount>, packets: Int = 500) = CaptureAnalysis(
        "pcap",
        CaptureSummary(packets, 1000, 1000, 0.0, 6.0, listOf("802.11 radiotap"), emptyList(), apps, listOf(Talker("AA:BB:CC:00:00:10", 300, 600)), emptyList()),
        emptyList(),
        false,
    )

    @Test
    fun aDeauthBurstInACaptureIsCritical() {
        val burst = AssistantCards.capture(capture(listOf(NamedCount("beacon", 400), NamedCount("deauth", 40))), "Home", "digest")
        assertEquals(CardRisk.CRITICAL, burst.findings.first().risk)
        val few = AssistantCards.capture(capture(listOf(NamedCount("deauth", 2))), "Home", "digest")
        assertEquals(CardRisk.WARNING, few.findings.first().risk)
        val quiet = AssistantCards.capture(capture(listOf(NamedCount("beacon", 400))), "Home", "digest")
        assertEquals(CardRisk.OK, quiet.findings.last().risk)
        assertTrue(quiet.findings.any { it.text.contains("500 packets over 6 s") })
        assertEquals(CardRisk.INFO, AssistantCards.capture(capture(emptyList(), packets = 0), "Empty", "d").findings.single().risk)
    }

    // MARK: - Ranking, saving, prompts

    @Test
    fun findingsAreRankedDedupedAndCapped() {
        val many = (1..12).map { CardFinding(if (it % 3 == 0) CardRisk.CRITICAL else CardRisk.INFO, "finding $it") } + CardFinding(CardRisk.INFO, "finding 1")
        val ranked = AssistantCards.ranked(many)
        assertEquals(AssistantCards.MAX_FINDINGS, ranked.size)
        assertEquals(listOf("finding 3", "finding 6", "finding 9", "finding 12"), ranked.take(4).map { it.text })
        assertEquals(ranked.map { it.text }.toSet().size, ranked.size)
    }

    @Test
    fun aSavedCardReadsBackTheSame() {
        val findings = listOf(CardFinding(CardRisk.CRITICAL, "Telnet: clear-text login"), CardFinding(CardRisk.OK, "WPA2"))
        val text = AssistantCards.render("Keenetic · 192.168.1.1", findings)
        assertEquals("Keenetic · 192.168.1.1" to findings, AssistantCards.parse(text))
        assertNull(AssistantCards.parse("Just a question from an old chat"))
        for (subject in listOf(CardSubject.Lan, CardSubject.Host("192.168.1.1"), CardSubject.Bluetooth("C4:00:00:00:00:01"), CardSubject.Wifi("AA:BB:CC:00:00:10"), CardSubject.Traffic, CardSubject.Capture)) {
            val encoded = CardSubjects.encode(subject)
            assertTrue(CardSubjects.isCard(encoded))
            assertEquals(subject, CardSubjects.decode(encoded))
        }
        assertFalse(CardSubjects.isCard(""))
        assertNull(CardSubjects.decode("You are a helpful assistant"))
    }

    @Test
    fun savedTextsKeepTheirActionInEitherLanguage() {
        assertEquals(CardAction.DEEP to "Long text", AssistantCardRunner.savedText("**Deep analysis**\n\nLong text"))
        Locale.setDefault(Locale.forLanguageTag("ru"))
        val russian = "**${CardAction.DETAILS.heading}**\n\nТекст"
        assertNotEquals("**More detail**\n\nТекст", russian)
        assertEquals(CardAction.DETAILS to "Текст", AssistantCardRunner.savedText(russian))
        // Saved in English, read after the app switched to Russian.
        assertEquals(CardAction.SUMMARY to "Words", AssistantCardRunner.savedText("**In plain words**\n\nWords"))
        assertEquals(null to "Сканировать порты", AssistantCardRunner.savedText("✓ Сканировать порты"))
    }

    @Test
    fun everyActionAndSubjectGetsItsOwnShortPrompt() {
        val prompts = CardAction.entries.flatMap { action -> CardSubject.Kind.entries.map { CardPrompts.system(action, it, Locale.ENGLISH) } }
        assertEquals(prompts.size, prompts.toSet().size)
        // Short enough to read in a few seconds on the phone's CPU (about 4 characters a token).
        assertTrue(prompts.maxOf { it.length } < 2200)
        assertTrue(CardPrompts.system(CardAction.SUMMARY, CardSubject.Kind.HOST, Locale.forLanguageTag("ru")).endsWith("Answer in Russian."))
        assertTrue(CardAction.SUMMARY.maxTokens <= 160)
        assertTrue(CardAction.DEEP.maxTokens > CardAction.DETAILS.maxTokens)
    }

    @Test
    fun theDeepAnalysisReadsTheWiderData() {
        val facts = AssistantCards.host(AssistantSnapshot(hosts = listOf(router, camera)), "192.168.1.64", now)
        val short = CardPrompts.user(facts, CardAction.SUMMARY)
        val deep = CardPrompts.user(facts, CardAction.DEEP)
        assertTrue(short.contains("[info] Ports have not been checked yet."))
        assertFalse(short.contains("192.168.1.1 "))
        assertTrue(deep.contains("192.168.1.1"))
        assertTrue(short.contains("<data>") && short.contains("</data>"))
    }

    @Test
    fun findingsReadInRussian() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        val facts = AssistantCards.host(AssistantSnapshot(hosts = listOf(camera)), camera.ip, now)
        assertTrue(facts.findings.joinToString { it.text }, facts.findings.all { finding -> finding.text.any { it in 'а'..'я' } })
        assertTrue(facts.steps.single().label.contains("порт", ignoreCase = true))
    }
}
