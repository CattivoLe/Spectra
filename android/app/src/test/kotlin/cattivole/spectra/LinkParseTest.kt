package cattivole.spectra

import cattivole.spectra.core.CheckNode
import cattivole.spectra.core.CheckStart
import cattivole.spectra.core.LinkParse
import cattivole.spectra.core.TraceHop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParseTest {
    @Test fun cloudflareTraceKeepsThePublicAddress() {
        val text = """
            fl=abc
            ip=203.0.113.8
            loc=DE
            colo=FRA
            warp=on
        """.trimIndent()
        val address = LinkParse.externalFromTrace(text)
        assertEquals("203.0.113.8", address?.ip)
        assertEquals("DE", address?.countryCode)
        assertEquals("FRA", address?.colo)
        assertTrue(address?.warp == true)
        assertNull(LinkParse.externalFromTrace("colo=FRA"))
    }

    @Test fun whoisFillsTheProviderWithoutDroppingTheEdge() {
        val trace = LinkParse.externalFromTrace("ip=203.0.113.8\nloc=DE\ncolo=FRA\n")!!
        val merged = LinkParse.mergeWhois(
            """
            {"success":true,"country_code":"DE","city":"Berlin",
             "connection":{"asn":3320,"isp":"Deutsche Telekom","org":"DTAG"}}
            """.trimIndent(),
            trace,
        )
        assertEquals("Berlin", merged.city)
        assertEquals("AS3320", merged.asn)
        assertEquals("Deutsche Telekom", merged.isp)
        assertEquals("FRA", merged.colo)
        assertEquals(trace, LinkParse.mergeWhois("""{"success":false}""", trace))
    }

    @Test fun checkHostStartReadsNodesAndTheRateLimit() {
        val started = LinkParse.checkStart(
            """
            {"ok":1,"request_id":"abc","nodes":{
              "de1.node.check-host.net":["de","Germany","Falkenstein","1.2.3.4","AS24940"]
            }}
            """.trimIndent(),
        ) as CheckStart.Ok
        assertEquals("abc", started.id)
        assertEquals(CheckNode("de1.node.check-host.net", "de", "Falkenstein"), started.nodes.single())
        assertEquals(CheckStart.Busy, LinkParse.checkStart("""{"error":"limit_exceeded"}"""))
        assertEquals(CheckStart.Failed, LinkParse.checkStart("not json"))
    }

    @Test fun abroadPingTreatsSecondsAndPendingNodes() {
        val nodes = listOf(
            CheckNode("us1", "us", "Los Angeles"),
            CheckNode("ch1", "ch", "Zurich"),
            CheckNode("pt1", "pt", "Viana"),
        )
        val (complete, views) = LinkParse.abroad(
            """
            {"us1":[[["OK",0.044,"203.0.113.8"],["TIMEOUT",3.005],["OK",0.030]]],
             "ch1":[[null]],
             "pt1":null}
            """.trimIndent(),
            nodes,
        )!!
        assertFalse(complete)
        assertEquals(2, views.size)
        assertEquals(30, views[0].millis)
        assertTrue(views[0].ok)
        assertFalse(views[1].ok)
        assertEquals("Zurich", views[1].city)
    }

    @Test fun tracerouteTimesAreMilliseconds() {
        val nodes = listOf(CheckNode("jp1", "jp", "Tokyo"))
        val json = """
            {"jp1":[[
              [{"host":"172.16.0.1","query_times":["0.50","0.39"]}],
              [{"query_times":[null]},{"host":"103.214.168.255","query_times":["1.85"]}],
              [{"host":"1.1.1.1","query_times":["1.12","0.96"]}]
            ]]}
        """.trimIndent()
        val (complete, traces) = LinkParse.traces(json, nodes, "1.1.1.1")!!
        assertTrue(complete)
        val trace = traces.single()
        assertTrue(trace.reached)
        assertEquals("172.16.0.1", trace.hops[0].address)
        assertEquals(1, trace.hops[0].millis)
        assertEquals("103.214.168.255", trace.hops[1].address)
        assertEquals(2, trace.hops[1].millis)
        assertEquals(
            "172.16.0.1 → 103.214.168.255 → 1.1.1.1",
            LinkParse.compactPath(trace.hops),
        )
    }

    @Test fun compactPathCollapsesSilentHops() {
        val hops = listOf(
            TraceHop("10.0.0.1", 1),
            TraceHop(null, null),
            TraceHop(null, null),
            TraceHop("10.1.1.1", 4),
            TraceHop("10.2.2.2", 5),
            TraceHop("10.3.3.3", 6),
            TraceHop("10.4.4.4", 7),
        )
        assertEquals("10.0.0.1 → * → … → 10.3.3.3 → 10.4.4.4", LinkParse.compactPath(hops))
    }

    @Test fun pingOutputReadsTtlAndEchoReplies() {
        val exceeded = LinkParse.pingOutput(
            """
            PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.
            From 192.168.1.1: icmp_seq=1 Time to live exceeded
            """.trimIndent(),
        )
        assertEquals("192.168.1.1", exceeded.address)
        assertFalse(exceeded.reached)
        assertFalse(exceeded.denied)

        val echo = LinkParse.pingOutput("64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=11.4 ms")
        assertTrue(echo.reached)
        assertEquals("1.1.1.1", echo.address)
        assertEquals(11, echo.millis)

        val denied = LinkParse.pingOutput("ping: socket: Operation not permitted")
        assertTrue(denied.denied)

        val silent = LinkParse.pingOutput("1 packets transmitted, 0 received, 100% packet loss")
        assertNull(silent.address)
        assertFalse(silent.reached)
        assertFalse(silent.denied)
    }
}
