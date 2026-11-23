package cattivole.spectra

import cattivole.spectra.assistant.AssistantCards
import cattivole.spectra.assistant.CardAction
import cattivole.spectra.assistant.CardFacts
import cattivole.spectra.assistant.CardPrompts
import cattivole.spectra.assistant.CardRisk
import cattivole.spectra.assistant.CardSubject
import cattivole.spectra.assistant.CardSubjects
import cattivole.spectra.assistant.CaptureShelf
import cattivole.spectra.assistant.TrafficAudit
import cattivole.spectra.assistant.TrafficCards
import cattivole.spectra.assistant.TrafficDigest
import cattivole.spectra.assistant.TrafficIssue
import cattivole.spectra.assistant.TrafficLibrary
import cattivole.spectra.assistant.TrafficRef
import cattivole.spectra.core.Endpoint
import cattivole.spectra.core.HttpField
import cattivole.spectra.core.MapLocal
import cattivole.spectra.core.RepeatResult
import cattivole.spectra.core.RequestHit
import cattivole.spectra.core.TrackerHosts
import cattivole.spectra.core.TrafficFlow
import cattivole.spectra.core.TrafficSecrets
import cattivole.spectra.core.TrafficSecrets.Find
import cattivole.spectra.core.TrafficSecrets.Kind
import cattivole.spectra.core.TrafficSecrets.Place
import cattivole.spectra.core.TrafficSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficSecretsTest {
    @Test fun fieldsAreKnownByTheirNames() {
        val cases = listOf(
            "password" to Kind.PASSWORD, "user_password" to Kind.PASSWORD, "pwd" to Kind.PASSWORD, "otp" to Kind.PASSWORD,
            "access_token" to Kind.TOKEN, "X-Auth-Token" to Kind.TOKEN, "refreshToken" to Kind.TOKEN, "jwt" to Kind.TOKEN,
            "api_key" to Kind.KEY, "X-Api-Key" to Kind.KEY, "client_secret" to Kind.KEY, "sid" to Kind.SESSION, "JSESSIONID" to Kind.SESSION,
            "cardNumber" to Kind.CARD,
            // Called tokens, open nothing.
            "csrf_token" to null, "X-CSRF-Token" to null, "nextPageToken" to null, "deviceToken" to null, "continuation_token" to null,
            "page" to null, "passage" to null, "monkey" to null, "q" to null, "" to null,
        )
        for ((name, kind) in cases) assertEquals(name, kind, TrafficSecrets.kindOfName(name))
    }

    @Test fun valuesAreKnownByTheirLook() {
        assertEquals(Kind.TOKEN, TrafficSecrets.kindOfValue("x " + TrafficSamples.JWT))
        assertEquals(Kind.KEY, TrafficSecrets.kindOfValue("AIza" + "a".repeat(35)))
        assertEquals(Kind.KEY, TrafficSecrets.kindOfValue("AKIAIOSFODNN7EXAMPLE"))
        assertEquals(Kind.PRIVATE_KEY, TrafficSecrets.kindOfValue("-----BEGIN RSA PRIVATE KEY-----"))
        assertNull(TrafficSecrets.kindOfValue("just a sentence about a token"))
        assertTrue(TrafficSecrets.isEmail("a.b@example.com") && !TrafficSecrets.isEmail("a@b") && !TrafficSecrets.isEmail("@example.com"))
    }

    @Test fun flagsAndBlanksAreNotSecrets() {
        assertFalse(TrafficSecrets.plausible("true", Kind.PASSWORD))
        assertFalse(TrafficSecrets.plausible("", Kind.TOKEN))
        assertFalse(TrafficSecrets.plausible("null", Kind.KEY))
        assertTrue(TrafficSecrets.plausible("hunter22", Kind.PASSWORD))
        assertTrue(TrafficSecrets.plausible("4111 1111 1111 1111", Kind.CARD) && !TrafficSecrets.plausible("4111111111111112", Kind.CARD))
    }

    @Test fun everyPlaceOfARequestIsSearched() {
        val exchange = TrafficSamples.exchange(
            "POST", "/login/${TrafficSamples.JWT}?access_token=abcdef123456&mail=ivan@example.com&page=2",
            request = listOf(
                "Authorization" to "Basic dXNlcjpwYXNz", "Cookie" to "sid=1", "X-Api-Key" to "0123456789",
                "Referer" to "https://x.example/?token=0123456789", ":path" to "/login?access_token=abcdef123456",
            ),
            response = listOf("Location" to "https://y.example/?session_id=0123456789"), parameters = listOf("password" to "hunter22", "remember" to "true"),
        )
        assertEquals(
            setOf(
                Find(Kind.TOKEN, Place.Path), Find(Kind.TOKEN, Place.Query("access_token")), Find(Kind.EMAIL, Place.Query("mail")),
                Find(Kind.PASSWORD, Place.RequestHeader("Authorization")), Find(Kind.SESSION, Place.RequestHeader("Cookie")),
                Find(Kind.KEY, Place.RequestHeader("X-Api-Key")), Find(Kind.TOKEN, Place.RequestHeader("Referer")),
                Find(Kind.TOKEN, Place.ResponseHeader("Location")), Find(Kind.PASSWORD, Place.RequestBody("password")),
            ),
            TrafficSecrets.inExchange(exchange).toSet(),
        )
    }

    @Test fun bodiesAreSearchedByFieldAndByLook() {
        val json = """{"user":{"password":"hunter22","name":"x"},"list":[{"api_key":"0123456789"}],"csrf_token":"0123456789"}""".toByteArray()
        assertEquals(
            setOf(Find(Kind.PASSWORD, Place.RequestBody("password")), Find(Kind.KEY, Place.RequestBody("api_key"))),
            TrafficSecrets.inBody(json, "application/json", request = true).toSet(),
        )
        assertEquals(listOf(Find(Kind.PASSWORD, Place.RequestBody("pwd"))), TrafficSecrets.inBody("a=1&pwd=hunter22".toByteArray(), "application/x-www-form-urlencoded", true))
        assertEquals(listOf(Find(Kind.PRIVATE_KEY, Place.ResponseBody(""))), TrafficSecrets.inBody("key: -----BEGIN PRIVATE KEY-----".toByteArray(), "text/plain", false))
        assertTrue(TrafficSecrets.inBody(json, "image/png", false).isEmpty())
        assertTrue(TrafficSecrets.inBody(byteArrayOf(-1, -2, 0), null, false).isEmpty())
    }

    @Test fun aNameThatIsASecretItselfIsNotShown() {
        assertEquals("{id}", TrafficSecrets.safeName(TrafficSamples.JWT))
        assertEquals("{id}", TrafficSecrets.safeName("550e8400-e29b-41d4-a716-446655440000"))
        assertEquals("{id}", TrafficSecrets.safeName("ivan@example.com"))
        assertEquals("‹script›", TrafficSecrets.safeName("<script>"))
        assertEquals(listOf(Find(Kind.TOKEN, Place.Query("{id}"))), TrafficSecrets.inExchange(TrafficSamples.exchange("GET", "/a?${TrafficSamples.JWT}")))
    }
}

class TrackerHostsTest {
    @Test fun aServiceCoversItsSubdomainsOnly() {
        assertEquals("Google Analytics for Firebase", TrackerHosts.serviceOf("app-measurement.com")?.name)
        assertEquals(TrackerHosts.Kind.ANALYTICS, TrackerHosts.serviceOf("region1.app-measurement.com:443")?.kind)
        assertEquals(TrackerHosts.Kind.CRASH, TrackerHosts.serviceOf("o123.ingest.sentry.io")?.kind)
        assertNull(TrackerHosts.serviceOf("notsentry.io"))
        assertNull(TrackerHosts.serviceOf("example.com"))
        assertNull(TrackerHosts.serviceOf("localhost"))
    }
}

class TrafficAuditTest {
    private fun issues(flow: TrafficFlow, bodies: TrafficAudit.Bodies? = null) = TrafficAudit.issues(TrafficAudit.scan(RequestHit.all(listOf(flow)), bodies))
    private fun kinds(flow: TrafficFlow, bodies: TrafficAudit.Bodies? = null) = issues(flow, bodies).associate { it.kind to it.risk }
    private fun sample(vararg exchanges: cattivole.spectra.core.HttpExchange, tls: Boolean = true, closed: Boolean = true) = TrafficSamples.flow(*exchanges, tls = tls, closed = closed)

    @Test fun anOrdinaryRequestHasNoIssues() {
        val exchange = TrafficSamples.exchange(
            "POST", "/v1/me?page=2", request = listOf("Authorization" to "Bearer 0123456789", "Cookie" to "sid=1"),
            response = listOf("Content-Type" to "application/json", "Cache-Control" to "no-store", "Set-Cookie" to "sid=2; Secure; HttpOnly; SameSite=Lax"),
        )
        assertTrue("signing in over TLS is how it is done", issues(sample(exchange)).isEmpty())
    }

    @Test fun secretsOverPlainHttpAreCritical() {
        assertEquals(
            mapOf(TrafficIssue.Kind.SECRET_UNENCRYPTED to CardRisk.CRITICAL),
            kinds(sample(TrafficSamples.exchange("POST", "/login", parameters = listOf("password" to "hunter22")), tls = false)),
        )
        assertEquals(mapOf(TrafficIssue.Kind.CLEARTEXT to CardRisk.WARNING), kinds(sample(TrafficSamples.exchange("GET", "/news"), tls = false)))
        assertEquals(
            mapOf(TrafficIssue.Kind.CLEARTEXT to CardRisk.INFO),
            kinds(sample(TrafficSamples.exchange("GET", "/generate_204", host = "connectivitycheck.gstatic.com"), tls = false)),
        )
        assertEquals(mapOf(TrafficIssue.Kind.SECRET_IN_ADDRESS to CardRisk.WARNING), kinds(sample(TrafficSamples.exchange("GET", "/a?api_key=0123456789"))))
        assertEquals(mapOf(TrafficIssue.Kind.PERSONAL to CardRisk.INFO), kinds(sample(TrafficSamples.exchange("GET", "/a?to=ivan@example.com"))))
    }

    @Test fun aPrivateKeyInAnAnswerIsFound() {
        val exchange = TrafficSamples.exchange("GET", "/config", response = listOf("Content-Type" to "text/plain"), stored = true)
        val found = kinds(sample(exchange)) { _, request -> if (request) null else "-----BEGIN EC PRIVATE KEY-----\nabc".toByteArray() }
        assertEquals(CardRisk.WARNING, found[TrafficIssue.Kind.PRIVATE_KEY])
    }

    @Test fun errorsAreCountedPerEndpoint() {
        assertEquals(mapOf(TrafficIssue.Kind.SERVER_ERROR to CardRisk.WARNING), kinds(sample(TrafficSamples.exchange(status = 503))))
        assertEquals(mapOf(TrafficIssue.Kind.CLIENT_ERROR to CardRisk.WARNING), kinds(sample(TrafficSamples.exchange(status = 401))))
        assertEquals(mapOf(TrafficIssue.Kind.NO_ANSWER to CardRisk.WARNING), kinds(sample(TrafficSamples.exchange(status = null, took = null))))
        assertTrue("still under way", kinds(sample(TrafficSamples.exchange(status = null, took = null), closed = false)).isEmpty())
        val many = (0 until 8).map { TrafficSamples.exchange("POST", "/a", at = it * 100_000L) } + List(2) { TrafficSamples.exchange("POST", "/a", status = 429) }
        val issue = issues(sample(*many.toTypedArray())).first()
        assertEquals(TrafficIssue.Kind.CLIENT_ERROR to CardRisk.WARNING, issue.kind to issue.risk)
        assertEquals("Errors: 2 of 10 requests (20%): 429 ×2.", issue.text)
    }

    @Test fun slownessIsJudgedByTheUsualTime() {
        fun post(vararg took: Long) = sample(*took.map { TrafficSamples.exchange("POST", "/a", took = it) }.toTypedArray())
        assertEquals(mapOf(TrafficIssue.Kind.SLOW to CardRisk.WARNING), kinds(sample(TrafficSamples.exchange("POST", "/a", took = 2500, wait = 2400))))
        assertTrue(kinds(post(1500)).isEmpty())
        assertEquals(mapOf(TrafficIssue.Kind.SLOW to CardRisk.INFO), kinds(post(100, 120, 9000)))
        assertEquals(mapOf(TrafficIssue.Kind.SLOW to CardRisk.WARNING), kinds(post(1100, 1200)))
        assertTrue(kinds(sample(TrafficSamples.exchange("GET", "/ws", status = 101, took = 900_000))).isEmpty())
    }

    @Test fun sizeCompressionAndCachingAreTheServersDoing() {
        val json = listOf("Content-Type" to "application/json; charset=utf-8", "ETag" to "1")
        val asks = listOf("Accept-Encoding" to "gzip, br")
        assertEquals(
            mapOf(TrafficIssue.Kind.LARGE to CardRisk.WARNING, TrafficIssue.Kind.UNCOMPRESSED to CardRisk.INFO),
            kinds(sample(TrafficSamples.exchange("POST", "/a", request = asks, response = json, size = 3_000_000, version = "HTTP/2"))),
        )
        // Inside decrypted HTTP/1.1 the recorder itself asks for plain text: nothing is said about the server.
        assertEquals(mapOf(TrafficIssue.Kind.LARGE to CardRisk.WARNING), kinds(sample(TrafficSamples.exchange("POST", "/a", request = asks, response = json, size = 3_000_000))))
        assertTrue("the app did not ask for compression", kinds(sample(TrafficSamples.exchange("POST", "/a", response = json, size = 200_000, version = "HTTP/2"))).isEmpty())
        assertTrue(kinds(sample(TrafficSamples.exchange("POST", "/a", request = asks, response = json + ("Content-Encoding" to "br"), size = 200_000, version = "HTTP/2"))).isEmpty())
        assertEquals(
            mapOf(TrafficIssue.Kind.LARGE to CardRisk.INFO, TrafficIssue.Kind.UNCACHED to CardRisk.INFO),
            kinds(sample(TrafficSamples.exchange("GET", "/a.png", response = listOf("Content-Type" to "image/png"), size = 2_000_000))),
        )
        assertTrue(kinds(sample(TrafficSamples.exchange("GET", "/v.mp4", response = listOf("Content-Type" to "video/mp4"), size = 90_000_000))).isEmpty())
        assertEquals(
            mapOf(TrafficIssue.Kind.SHARED_CACHE to CardRisk.WARNING),
            kinds(sample(TrafficSamples.exchange("GET", "/me", response = listOf("Cache-Control" to "public, max-age=60", "Set-Cookie" to "a=1; Secure")))),
        )
        // The phone's own answer says nothing about the server.
        val mapped = TrafficSamples.exchange("GET", "/a.css", request = asks, response = listOf("Content-Type" to "text/css", MapLocal.HEADER to "x"), took = 9000, size = 3_000_000)
        assertEquals(mapOf(TrafficIssue.Kind.MAPPED to CardRisk.INFO), kinds(sample(mapped)))
    }

    @Test fun needlessRepeatsAreCountedInAWindow() {
        fun flow(vararg times: Long, method: String = "GET") =
            sample(*times.map { TrafficSamples.exchange(method, "/a", response = listOf("ETag" to "1"), at = it) }.toTypedArray())
        assertEquals(mapOf(TrafficIssue.Kind.REPEATED to CardRisk.INFO), kinds(flow(0, 1000, 2000)))
        assertEquals(mapOf(TrafficIssue.Kind.REPEATED to CardRisk.WARNING), kinds(flow(0, 1000, 2000, 3000, 4000)))
        assertTrue("polling twice a minute is no storm", kinds(flow(0, 20_000, 40_000)).isEmpty())
        assertTrue("two POSTs may carry different bodies", kinds(flow(0, 1000, 2000, method = "POST")).isEmpty())
        assertEquals(3 to 2, TrafficAudit.repeats(RequestHit.all(listOf(flow(0, 1000, 60_000)))))
    }

    @Test fun cookieFlagsMatterMostForTheOnesThatSignIn() {
        fun cookies(value: String) = TrafficAudit.weakCookies(RequestHit(sample(TrafficSamples.exchange(response = listOf("Set-Cookie" to value))), 0))
        assertEquals(listOf(TrafficAudit.WeakCookie("sid", listOf("Secure", "HttpOnly"), true)), cookies("sid=abc; Path=/"))
        assertEquals(listOf(TrafficAudit.WeakCookie("theme", listOf("Secure"), false)), cookies("theme=dark"))
        assertTrue(cookies("theme=dark; secure").isEmpty())
        assertTrue("a page's script has to read it", cookies("csrftoken=abc; Secure").isEmpty())
        assertTrue("that deletes it", cookies("sid=; Max-Age=0").isEmpty() && cookies("sid=x; expires=Thu, 01 Jan 1970 00:00:00 GMT").isEmpty())
        assertEquals(mapOf(TrafficIssue.Kind.COOKIE to CardRisk.WARNING), kinds(sample(TrafficSamples.exchange(response = listOf("Set-Cookie" to "sid=abc")))))
        assertEquals(mapOf(TrafficIssue.Kind.COOKIE to CardRisk.INFO), kinds(sample(TrafficSamples.exchange(response = listOf("Set-Cookie" to "theme=dark")))))
    }

    @Test fun trackersAndDowngradesAreNamed() {
        val issue = issues(TrafficSamples.flow(TrafficSamples.exchange("POST", "/a", host = "region1.app-measurement.com"), host = null)).first()
        assertEquals(TrafficIssue.Kind.TRACKER to CardRisk.INFO, issue.kind to issue.risk)
        assertTrue("Google Analytics for Firebase" in issue.text)
        assertEquals(
            mapOf(TrafficIssue.Kind.DOWNGRADE to CardRisk.WARNING),
            kinds(sample(TrafficSamples.exchange(status = 301, response = listOf("Location" to "http://example.com/")))),
        )
        assertTrue(kinds(sample(TrafficSamples.exchange(status = 301, response = listOf("Location" to "https://example.com/")))).isEmpty())
    }
}

class TrafficCardsTest {
    /** Every value a request below carries that must not get out of it. */
    private val values = listOf("hunter22", "s3cr3tvalue", TrafficSamples.JWT, "ivan@example.com", "dXNlcjpwYXNz", "cookie-value", "body-value", "4111111111111111")

    private fun leaky(tls: Boolean) = TrafficSamples.flow(
        TrafficSamples.exchange(
            "POST", "/u/ivan@example.com/${TrafficSamples.JWT}?access_token=s3cr3tvalue&${TrafficSamples.JWT}=1&mail=ivan@example.com",
            status = 500, request = listOf("Authorization" to "Basic dXNlcjpwYXNz", "Cookie" to "sid=cookie-value", "User-Agent" to "Shop/1.2 (ivan@example.com)"),
            response = listOf("Set-Cookie" to "sid=cookie-value", "Content-Type" to "application/json"),
            parameters = listOf("password" to "hunter22", "cardnumber" to "4111111111111111"), took = 3000, stored = true,
        ),
        tls = tls,
    )

    private fun context(flows: List<TrafficFlow>, session: String? = null, recording: Boolean = false) =
        TrafficCards.Context(session, flows, { _, _ -> """{"password":"body-value","api_key":"body-value"}""".toByteArray() }, recording)

    /** Everything a card keeps, shows or hands the model. */
    private fun everything(facts: CardFacts): String = (
        listOf(facts.title, CardSubjects.encode(facts.subject), facts.data, facts.deepData, AssistantCards.render(facts.title, facts.findings)) +
            facts.steps.flatMap { listOf(it.call, it.label) } + CardAction.entries.map { CardPrompts.user(facts, it) }
        ).joinToString("\n")

    @Test fun noValueLeavesTheRequest() {
        for (tls in listOf(true, false)) {
            val flow = leaky(tls)
            val key = Endpoint.of(flow, flow.exchanges[0]).key
            assertEquals("POST api.example.com/u/{id}/{id}", key)
            val cards = listOf(
                TrafficCards.request(flow, 0, context(listOf(flow))), TrafficCards.endpoint(key, context(listOf(flow))), TrafficCards.connection(flow, context(listOf(flow))),
                AssistantCards.traffic(listOf(flow), "x", TrafficDigest.text(listOf(flow), "x", "y")),
            )
            for (facts in cards) {
                assertNotNull(facts)
                val text = everything(facts!!)
                for (value in values) assertFalse("$value in the ${facts.subject.kind} card", value in text)
                assertTrue(facts.findings.any { it.risk == if (tls) CardRisk.WARNING else CardRisk.CRITICAL })
            }
        }
    }

    @Test fun aRequestsCardSaysWhatItIsAndOffersWhatHelps() {
        val failing = TrafficSamples.flow(TrafficSamples.exchange("GET", "/v1/users/7", status = 500))
        val facts = TrafficCards.request(failing, 0, context(listOf(failing)))!!
        assertEquals("GET api.example.com/v1/users/{id}", facts.title)
        assertEquals(CardRisk.WARNING, facts.findings.first().risk)
        assertEquals(listOf("repeat_request", "open_repeat", "open_map_local"), facts.steps.map { it.tool })
        assertEquals(TrafficRef(null, failing, 0), facts.steps[0].text?.let(TrafficRef::of))
        assertTrue("Answer: status 500 (an error)" in facts.data)
        assertTrue("URL parameters: none" in facts.data && "Secrets: none found" in facts.data && "Cookies set with missing protection: none" in facts.data)
        // A request that changes something is never sent by a button alone.
        val post = TrafficSamples.flow(TrafficSamples.exchange("POST", "/v1/pay", status = 500))
        assertEquals(listOf("open_repeat", "open_map_local"), TrafficCards.request(post, 0, context(listOf(post)))!!.steps.map { it.tool })
        // Nor is plain HTTP, which the system does not let the app send, nor anything through the recorder.
        val plain = TrafficSamples.flow(TrafficSamples.exchange("GET", "/v1/users/7", status = 500), tls = false)
        assertEquals(listOf("open_repeat", "check_site"), TrafficCards.request(plain, 0, context(listOf(plain)))!!.steps.map { it.tool })
        assertEquals(listOf("open_repeat", "open_map_local"), TrafficCards.request(failing, 0, context(listOf(failing), recording = true))!!.steps.map { it.tool })
        // All in order: the site check, and nothing that would go through the recorder while it is on.
        val fine = TrafficSamples.flow(TrafficSamples.exchange("GET", "/"))
        val good = TrafficCards.request(fine, 0, context(listOf(fine)))!!
        assertEquals(CardRisk.OK, good.findings.last().risk)
        assertTrue(good.findings.none { it.risk == CardRisk.WARNING })
        assertEquals(listOf("open_repeat", "check_site"), good.steps.map { it.tool })
        assertEquals(listOf("open_repeat"), TrafficCards.request(fine, 0, context(listOf(fine), recording = true))!!.steps.map { it.tool })
        assertNull(TrafficCards.request(fine, 9, context(listOf(fine))))
    }

    @Test fun theSiteCheckIsOnlyForPublicNames() {
        for (host in listOf("192.168.1.1", "router.local", "localhost", "example.com:8443")) {
            val flow = TrafficSamples.flow(TrafficSamples.exchange("GET", "/", host = host), host = host)
            assertEquals(host, listOf("open_repeat"), TrafficCards.request(flow, 0, context(listOf(flow)))!!.steps.map { it.tool })
        }
    }

    @Test fun anEndpointsCardCountsItsRequests() {
        val ten = (0 until 9).map { TrafficSamples.exchange("GET", "/v1/users/$it", at = it * 1000L) } + TrafficSamples.exchange("GET", "/v1/users/9", status = 404, at = 9000)
        val flow = TrafficSamples.flow(*ten.toTypedArray())
        val other = TrafficSamples.flow(TrafficSamples.exchange("GET", "/v1/ads"), id = 2)
        val facts = TrafficCards.endpoint("GET api.example.com/v1/users/{id}", context(listOf(flow, other), session = "S"))!!
        assertEquals("Errors: 1 of 10 requests (10%): 404 ×1.", facts.findings.first().text)
        assertEquals(CardSubject.Endpoint("S", "GET api.example.com/v1/users/{id}"), facts.subject)
        // The button repeats the one that failed.
        assertEquals(TrafficRef("S", flow, 9), facts.steps[0].text?.let(TrafficRef::of))
        assertTrue("Answers by status: 200 ×9, 404 ×1" in facts.data)
        assertTrue("/v1/ads" !in facts.data && "GET api.example.com/v1/ads ×1" in facts.deepData)
        assertNull(TrafficCards.endpoint("GET nowhere/", context(listOf(flow))))
        // Every request at once: the numbers stay within the model's budget.
        val big = TrafficSamples.flow(*Array(400) { TrafficSamples.exchange("GET", "/v1/users/$it?p$it=1", response = listOf("Set-Cookie" to "session$it=1")) })
        val large = TrafficCards.endpoint("GET api.example.com/v1/users/{id}", context(listOf(big)))!!
        assertTrue(large.data.length <= TrafficCards.BUDGET + 10 && large.findings.size <= AssistantCards.MAX_FINDINGS)
    }

    @Test fun aConnectionsCardFollowsWhatWasRead() {
        val refused = TrafficSamples.flow(host = "bank.example", refused = true)
        val facts = TrafficCards.connection(refused, context(listOf(refused)))
        assertEquals(listOf("open_tls_hosts", "check_site"), facts.steps.map { it.tool })
        assertTrue("refusing is the app protecting itself", facts.findings.none { it.risk == CardRisk.WARNING || it.risk == CardRisk.CRITICAL })
        assertTrue("refused Spectra's certificate" in facts.data && "Requests read in it: none" in facts.data)
        val multiplexed = TrafficSamples.flow(
            TrafficSamples.exchange("GET", "/a/1", version = "HTTP/2"), TrafficSamples.exchange("GET", "/a/2", version = "HTTP/2"),
            TrafficSamples.exchange("GET", "/b", host = "cdn.example.com", version = "HTTP/2"),
        )
        val read = TrafficCards.connection(multiplexed, context(listOf(multiplexed)))
        assertTrue(read.findings.any { it.text == "Requests in it: 3, to 2 endpoints." })
        assertTrue("GET api.example.com/a/{id} ×2" in read.data)
        val dns = TrafficFlow(4, "UDP", "a", 1, "1.1.1.1", 53, null, null, null, false, 0, 0, 0, 0, true)
        assertEquals(CardRisk.INFO, TrafficCards.connection(dns, context(listOf(dns))).findings.first().risk)
    }

    @Test fun aCardFindsItsRequestAgainAfterTheRecordingWasSaved() {
        val flow = TrafficSamples.flow(TrafficSamples.exchange(), id = 7)
        val other = TrafficSamples.flow(TrafficSamples.exchange("GET", "/other"), id = 7).copy(startedAt = 99)
        val ref = TrafficRef(null, flow, 0)
        assertEquals(ref, TrafficRef.of(ref.text))
        assertEquals(null to flow, TrafficLibrary(live = { listOf(flow) }).find(ref))
        // A new recording has a connection with the same number: not the one the card is about.
        assertNull(TrafficLibrary(live = { listOf(other) }).find(ref))
        val saved = TrafficLibrary(live = { listOf(other) }, saved = { listOf(TrafficSession("S", 0, 1, listOf(flow))) })
        assertEquals("S" to flow, saved.find(ref))
    }

    @Test fun subjectsReadBackTheSame() {
        val flow = TrafficSamples.flow(id = 3)
        for (subject in listOf(
            CardSubject.Request(TrafficRef("0A1B", flow, 5)), CardSubject.Connection(TrafficRef(null, flow)),
            CardSubject.Endpoint(null, "GET [::1]:8080/a|b/{id}"), CardSubject.Endpoint("0A1B", "POST example.com/"),
        )) {
            assertEquals(subject, CardSubjects.decode(CardSubjects.encode(subject)))
        }
        for (broken in listOf("request:", "request:|1|2|", "request:x|y|z|0", "endpoint:", "endpoint:|", "connection:1")) {
            assertNull(broken, CardSubjects.decode("spectra-card:$broken"))
        }
    }

    @Test fun theShelfKeepsTheLastCardsReady() {
        val shelf = CaptureShelf()
        val flow = leaky(true)
        val facts = TrafficCards.request(flow, 0, context(listOf(flow)))!!
        assertNull(shelf.prepared(facts.subject))
        shelf.keep(facts, facts.subject)
        assertEquals(facts, shelf.prepared(facts.subject))
        for (number in 0 until 4) shelf.keep(facts, CardSubject.Endpoint(null, "GET x/$number"))
        assertNull("only the last few", shelf.prepared(facts.subject))
    }

    @Test fun theRepeatedRequestIsComparedByNumbers() {
        val exchange = TrafficSamples.exchange("GET", "/a?token=s3cr3tvalue", status = 500, took = 3000)
        val text = TrafficCards.compare(exchange, RepeatResult(200, "OK", listOf(HttpField("Set-Cookie", "cookie-value")), "body-value".toByteArray(), false, 90, null))
        assertTrue("Recorded: status 500, 3000 ms" in text && "status 200, 90 ms" in text && "Status: changed" in text)
        for (value in values) assertFalse(value in text)
        assertTrue("expired" in TrafficCards.compare(exchange, RepeatResult(401, null, emptyList(), ByteArray(0), false, 1, null)))
        assertTrue(TrafficCards.compare(exchange, RepeatResult(null, null, emptyList(), ByteArray(0), false, 1, "no protocol: s3cr3tvalue")).endsWith("no answer."))
    }

    @Test fun theWholeRecordingIsSummedUpByEndpointWithinItsBudget() {
        val flow = TrafficSamples.flow(
            *((0 until 40).map { TrafficSamples.exchange("GET", "/v1/users/$it", at = it * 60_000L) } +
                TrafficSamples.exchange("POST", "/pay", status = 500) + TrafficSamples.exchange("GET", "/slow?access_token=s3cr3tvalue", took = 4000)).toTypedArray(),
        )
        val many = listOf(flow) + (10L until 60L).map { TrafficSamples.flow(id = it, host = "server-$it.a-rather-long-name.example.com") }
        val text = TrafficDigest.text(many, "x", "y")
        assertTrue("Most requested: GET api.example.com/v1/users/{id} ×40" in text)
        assertTrue("With errors: POST api.example.com/pay 1 of 1." in text)
        assertTrue("GET api.example.com/slow 4000 ms" in text)
        assertTrue("access token in the URL parameter `access_token`" in text && "s3cr3tvalue" !in text)
        assertTrue("Known analytics and advertising services contacted: none." in text)
        assertTrue("the list of servers gives way: ${text.length}", text.length <= TrafficDigest.BUDGET && "more." in text)
        val calm = TrafficDigest.text(listOf(TrafficSamples.flow(TrafficSamples.exchange("POST", "/a"))), "x", "y")
        assertTrue("With errors: none." in calm && "Slow (usually a second or more): none." in calm)
        val shown = AssistantCards.traffic(many, "x", text).findings.map { it.text }
        assertTrue("Fails most often: POST api.example.com/pay, errors 1 of 1." in shown)
        assertTrue(shown.any { "GET api.example.com/slow" in it } && shown.any { "v1/users/{id}" in it })
    }
}
