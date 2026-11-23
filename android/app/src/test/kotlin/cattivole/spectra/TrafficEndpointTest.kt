package cattivole.spectra

import cattivole.spectra.core.Endpoint
import cattivole.spectra.core.EndpointGroup
import cattivole.spectra.core.HttpExchange
import cattivole.spectra.core.HttpField
import cattivole.spectra.core.RequestFilter
import cattivole.spectra.core.TrafficFlow
import cattivole.spectra.core.WsMessage
import cattivole.spectra.core.queryFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Requests and connections for the tests of recorded traffic. */
object TrafficSamples {
    /** Looks like a JSON Web Token; signs nothing. */
    const val JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLW5vdC1yZWFs"

    fun exchange(
        method: String = "GET", target: String = "/", host: String? = "api.example.com", status: Int? = 200,
        request: List<Pair<String, String>> = emptyList(), response: List<Pair<String, String>> = emptyList(),
        parameters: List<Pair<String, String>> = emptyList(), at: Long = 1000, took: Long? = 100, wait: Long? = null,
        size: Long = 2000, version: String = "HTTP/1.1", messages: List<WsMessage> = emptyList(), stored: Boolean = false,
    ): HttpExchange {
        fun fields(list: List<Pair<String, String>>) = list.map { HttpField(it.first, it.second) }
        val named = host?.let { listOf((if (version == "HTTP/2") ":authority" else "Host") to it) }.orEmpty()
        return HttpExchange(
            method, target.substringBefore('?'), queryFields(target), fields(named + request), fields(parameters), status, fields(response),
            requestBodyStored = stored, responseBodyStored = stored, httpVersion = version, target = target, startedAt = at, requestEndAt = at,
            responseStartAt = (wait ?: took)?.let { at + it }, endedAt = took?.let { at + it },
            requestHeaderBytes = 300, requestBodyBytes = 0, responseHeaderBytes = 200, responseBodyBytes = size, messages = messages,
        )
    }

    fun flow(
        vararg exchanges: HttpExchange, id: Long = 1, host: String? = "api.example.com", tls: Boolean = true, closed: Boolean = true, refused: Boolean = false,
    ) = TrafficFlow(
        id, "TCP", "10.0.0.2", 40000, "93.184.216.34", if (tls) 443 else 80, host, null, null, tls, 500, 5000, 1000 + id, 9000, closed, exchanges.toList(), refused,
    )
}

class EndpointTest {
    /** The same table is in iOS's `EndpointTests`: the rule is one. */
    private val paths = listOf(
        "/" to "/", "" to "/", "/v1" to "/v1", "/v1/users" to "/v1/users", "/v1/users/" to "/v1/users",
        // Numbers.
        "/v1/users/42" to "/v1/users/{id}", "/v1/users/42?full=1" to "/v1/users/{id}", "/users/42/posts/7" to "/users/{id}/posts/{id}",
        "/items/-1" to "/items/{id}", "/items/12,15,18" to "/items/{id}", "/events/2026-11-23" to "/events/{id}",
        "/geo/55.7558,37.6173" to "/geo/{id}", "/call/+79161234567" to "/call/{id}", "/items/42;v=2" to "/items/{id}",
        // Versions are not somebody's number.
        "/2/tweets/1460323737035677698" to "/2/tweets/{id}", "/api/3/movie/popular" to "/api/3/movie/popular", "/api/2.0/list" to "/api/2.0/list",
        "/v2.1/users/5" to "/v2.1/users/{id}", "/api/10" to "/api/{id}", "/rest/v1.2.3/a" to "/rest/v1.2.3/a",
        // UUIDs, hex.
        "/o/550e8400-e29b-41d4-a716-446655440000" to "/o/{id}", "/o/550E8400-E29B-41D4-A716-446655440000/items" to "/o/{id}/items",
        "/doc/507f1f77bcf86cd799439011" to "/doc/{id}", "/blob/0x9f86d081884c7d65" to "/blob/{id}", "/commit/9f86d08" to "/commit/9f86d08",
        "/feedface" to "/feedface", "/accede" to "/accede",
        // Tokens, and words that only look long.
        "/t/dGhpcyBpcyBhIHRlc3Q5OTg3" to "/t/{id}", "/s/3kTMd8fJ2nQpX7vL9wZb" to "/s/{id}", "/verify/" + TrafficSamples.JWT to "/verify/{id}",
        "/how-to-build-a-house-in-2024" to "/how-to-build-a-house-in-2024", "/getUserByIdAndNameV2Async" to "/getUserByIdAndNameV2Async",
        "/Nexus6P-Android8-API26-build" to "/Nexus6P-Android8-API26-build", "/iphone-15-pro-256gb-black" to "/iphone-15-pro-256gb-black",
        "/internationalization" to "/internationalization", "/com.example.application.release" to "/com.example.application.release",
        // A word and what changes; files.
        "/user-550e8400-e29b-41d4-a716-446655440000" to "/user-{id}", "/order_100482" to "/order_{id}", "/python-3" to "/python-3",
        "/img/12345.jpg" to "/img/{id}.jpg", "/img/9f86d081884c7d659a2f.png" to "/img/{id}.png", "/js/app-9f86d081884c.js" to "/js/app-{id}.js",
        "/js/jquery-3.7.1.min.js" to "/js/jquery-3.7.1.min.js", "/index.html" to "/index.html",
        // What must not be shown.
        "/u/ivan.petrov@example.com/mail" to "/u/{id}/mail", "/u/ivan%40example.com" to "/u/{id}",
        "/bot123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw/getMe" to "/{id}/getMe",
        // Names in other alphabets stay readable.
        "/%D0%BD%D0%BE%D0%B2%D0%BE%D1%81%D1%82%D0%B8/15" to "/новости/{id}", "/a%2Fb/1" to "/a%2Fb/{id}",
    )

    @Test fun pathsAreTemplated() {
        for ((path, expected) in paths) assertEquals(path, expected, Endpoint.template(path))
    }

    @Test fun theServerIsTheOneTheRequestNames() {
        fun key(exchange: HttpExchange, flow: TrafficFlow = TrafficSamples.flow()) = Endpoint.of(flow, exchange).key
        assertEquals("GET api.example.com/a/{id}", key(TrafficSamples.exchange("get", "/a/1", host = "API.Example.com:443")))
        assertEquals("GET example.com:8443/a", key(TrafficSamples.exchange("GET", "/a", host = "example.com:8443")))
        // One HTTP/2 connection carries several names.
        assertEquals("GET cdn.example.com/a", key(TrafficSamples.exchange("GET", "/a", host = "cdn.example.com", version = "HTTP/2")))
        assertEquals("GET api.example.com/a", key(TrafficSamples.exchange("GET", "/a", host = null)))
        assertEquals("GET 93.184.216.34/a", key(TrafficSamples.exchange("GET", "/a", host = null), TrafficSamples.flow(host = null)))
        assertEquals("GET proxy.example.com/b/{id}", key(TrafficSamples.exchange("GET", "http://proxy.example.com/b/7?x=1", host = "other.example")))
        assertEquals("CONNECT example.com", key(TrafficSamples.exchange("CONNECT", "example.com:443", host = null)))
        assertEquals("GET [::1]/", key(TrafficSamples.exchange("GET", "/", host = "[::1]:80")))
        // The query never takes part.
        assertEquals(key(TrafficSamples.exchange("GET", "/a?token=abc")), key(TrafficSamples.exchange("GET", "/a?token=xyz")))
        assertNotEquals(key(TrafficSamples.exchange("POST", "/a")), key(TrafficSamples.exchange("GET", "/a")))
    }

    @Test fun aGroupCountsErrorsTimesAndSize() {
        val flow = TrafficSamples.flow(
            TrafficSamples.exchange("GET", "/u/1", status = 200, at = 1000, took = 100),
            TrafficSamples.exchange("GET", "/u/2", status = 500, at = 2000, took = 300),
            TrafficSamples.exchange("GET", "/u/3", status = 200, at = 3000, took = 900),
            TrafficSamples.exchange("GET", "/u/4", status = null, at = 4000, took = null),
            TrafficSamples.exchange("POST", "/u", status = 200, at = 500, took = 50),
        )
        val groups = EndpointGroup.build(listOf(flow))
        assertEquals(listOf("GET api.example.com/u/{id}", "POST api.example.com/u"), groups.map { it.key })
        val group = groups.first()
        assertEquals("newest first", listOf(3, 2, 1, 0), group.hits.map { it.index })
        assertEquals("a 500, and no answer on a closed connection", 2, group.errors)
        assertEquals(300L, group.typicalMs)
        assertEquals(900L, group.worstMs)
        assertEquals(4 * 2500L, group.bytes)
        assertEquals(4000L, group.lastAt)
        assertEquals(1000L, group.firstAt)
        assertEquals(listOf(200 to 2, 0 to 1, 500 to 1), group.statuses)
        assertEquals(4, EndpointGroup.find(group.key, listOf(flow))?.hits?.size)
        assertNull(EndpointGroup.find("GET nowhere/", listOf(flow)))
    }

    @Test fun theFilterWorksInsideGroups() {
        val flows = listOf(
            TrafficSamples.flow(
                TrafficSamples.exchange("GET", "/u/1", status = 200), TrafficSamples.exchange("GET", "/u/2", status = 404),
                TrafficSamples.exchange("GET", "/ok", status = 200), TrafficSamples.exchange("POST", "/u", status = 201),
            ),
        )
        val failed = EndpointGroup.build(flows, RequestFilter(failedOnly = true))
        assertEquals(1, failed.size)
        assertEquals("the numbers stay the endpoint's own", 1 to 2, failed[0].shown.size to failed[0].hits.size)
        assertEquals(404, failed[0].shown.single().exchange.status)
        assertEquals(listOf("POST api.example.com/u"), EndpointGroup.build(flows, RequestFilter(methods = setOf("POST"))).map { it.key })
        assertEquals(listOf(1), EndpointGroup.build(flows, RequestFilter(query = "/u/2")).map { it.shown.size })
        assertTrue(EndpointGroup.build(flows, RequestFilter(query = "nothing")).isEmpty())
    }

    @Test fun aWebSocketIsNotSlowAndAStreamOnHttp2IsStillARequest() {
        val messages = listOf(WsMessage(fromClient = true, opcode = 1, at = 1, size = 5, preview = "hello", stored = false))
        val socket = EndpointGroup.build(listOf(TrafficSamples.flow(TrafficSamples.exchange("GET", "/ws", status = 101, took = 600_000, messages = messages)))).single()
        assertTrue(socket.webSocket)
        assertNull(socket.typicalMs)
        val events = TrafficSamples.flow(TrafficSamples.exchange("GET", "/events", response = listOf("Content-Type" to "text/event-stream"), took = 90_000))
        assertNull(EndpointGroup.build(listOf(events)).single().worstMs)
        // Many requests in one connection, and a stream whose headers have not come yet (an empty method).
        val many = (1..50).map { TrafficSamples.exchange("GET", "/p/$it", at = it.toLong(), version = "HTTP/2") } +
            HttpExchange("", "", emptyList(), emptyList(), emptyList(), null, emptyList())
        val groups = EndpointGroup.build(listOf(TrafficSamples.flow(*many.toTypedArray(), closed = false)))
        assertEquals(1, groups.size)
        assertEquals(50, groups[0].hits.size)
        assertTrue(groups[0].http2)
        assertEquals(0, groups[0].errors)
    }

    @Test fun aLargeRecordingIsGroupedQuickly() {
        val flows = (0 until 200).map { id ->
            TrafficSamples.flow(*Array(50) { TrafficSamples.exchange("GET", "/v1/users/${id * 50 + it}/posts", at = it.toLong()) }, id = id.toLong())
        }
        EndpointGroup.build(flows)
        val started = System.nanoTime()
        val groups = EndpointGroup.build(flows)
        assertEquals(10_000, groups.single().hits.size)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
    }
}
