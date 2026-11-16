package cattivole.spectra

import cattivole.spectra.core.DiscoveredPort
import cattivole.spectra.core.DnsReport
import cattivole.spectra.core.DomainOutcome
import cattivole.spectra.core.DomainReport
import cattivole.spectra.core.ExposedPort
import cattivole.spectra.core.ExposureReport
import cattivole.spectra.core.GatewayControl
import cattivole.spectra.core.HistoryCodec
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.LanProfile
import cattivole.spectra.core.LinkInspection.Kind
import cattivole.spectra.core.LinkParse
import cattivole.spectra.core.LinkReport
import cattivole.spectra.core.LinkStatus
import cattivole.spectra.core.PortForwarding
import cattivole.spectra.core.PortMapping
import cattivole.spectra.core.PortRisk
import cattivole.spectra.core.QrPayload
import cattivole.spectra.core.RdapServers
import cattivole.spectra.core.RemoteNote
import cattivole.spectra.core.RiskLevel
import cattivole.spectra.core.SavedConnection
import cattivole.spectra.core.SiteCertificate
import cattivole.spectra.core.SiteReport
import cattivole.spectra.core.SpeedResult
import cattivole.spectra.core.TlsCheck
import cattivole.spectra.core.TlsInspector
import cattivole.spectra.core.TlsObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/** The checks ported from Probe Lab: router forwardings, HTTPS interception, DNS, outside ports, site age, QR, speed. */
class NetworkChecksTest {
    private val day = 86_400_000L

    // Port forwarding

    private val description = """
        <root><URLBase>http://192.168.1.1:5000/</URLBase><device><serviceList>
        <service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType><controlURL>/l3f</controlURL></service>
        <service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>/ctl/IPConn</controlURL></service>
        </serviceList></device></root>
    """.trimIndent()

    @Test
    fun theWanServiceIsFoundAndItsControlUrlResolved() {
        val service = GatewayControl.services(description).single()
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", service.serviceType)
        assertEquals(
            "http://192.168.1.1:5000/ctl/IPConn",
            GatewayControl.resolveControlUrl(service.controlUrl, "http://192.168.1.1:1900/desc.xml", description).toString(),
        )
        assertEquals(
            "http://192.168.1.1:1900/ctl",
            GatewayControl.resolveControlUrl("/ctl", "http://192.168.1.1:1900/desc.xml", "<root/>").toString(),
        )
    }

    @Test
    fun soapRequestsAndRepliesRoundTrip() {
        val request = GatewayControl.soapRequest(
            URI("http://192.168.1.1:5000/ctl/IPConn"), "urn:x:WANIPConnection:1",
            "GetGenericPortMappingEntry", listOf("NewPortMappingIndex" to "0"),
        ).toString(Charsets.UTF_8)
        assertTrue(request.startsWith("POST /ctl/IPConn HTTP/1.1\r\nHost: 192.168.1.1:5000\r\n"))
        assertTrue("SOAPAction: \"urn:x:WANIPConnection:1#GetGenericPortMappingEntry\"" in request)
        assertTrue("<NewPortMappingIndex>0</NewPortMappingIndex>" in request)
        val body = request.substringAfter("\r\n\r\n")
        assertTrue("Content-Length: ${body.toByteArray().size}\r\n" in request)

        val reply = "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\n\r\n<s:Body><u:R><NewRemoteHost></NewRemoteHost>" +
            "<NewExternalPort>8080</NewExternalPort><NewProtocol>tcp</NewProtocol><NewInternalPort>80</NewInternalPort>" +
            "<NewInternalClient>192.168.1.20</NewInternalClient><NewEnabled>1</NewEnabled>" +
            "<NewPortMappingDescription>Camera</NewPortMappingDescription><NewLeaseDuration>0</NewLeaseDuration></u:R></s:Body>"
        val outcome = GatewayControl.soapOutcome(reply.toByteArray())
        assertEquals(200, outcome.status)
        val mapping = GatewayControl.parsePortMapping(outcome.body)!!
        assertEquals(PortMapping(8080, 80, "192.168.1.20", "TCP", "Camera", true, 0, null), mapping)

        val end = "HTTP/1.1 500 Internal Server Error\r\n\r\n<UPnPError><errorCode>713</errorCode></UPnPError>"
        assertEquals(GatewayControl.INDEX_INVALID, GatewayControl.soapOutcome(end.toByteArray()).errorCode)
        assertNull(GatewayControl.parsePortMapping("<x/>"))
    }

    @Test
    fun forwardingsBecomeFindingsAndSurviveHistory() {
        val rule = PortMapping(8080, 80, "192.168.1.20", "TCP", "Camera")
        val camera = LanDevice(
            "192.168.1.20", openPorts = listOf(DiscoveredPort(443, "HTTPS")), portsScanned = true,
            profile = LanProfile(inbound = listOf(rule)),
        )
        val reachable = PortRisk.findings(camera).first()
        assertEquals("Reachable from the internet", reachable.title)
        assertEquals(RiskLevel.HIGH, reachable.level)
        assertEquals("TCP 8080", reachable.detailArg)

        val router = LanDevice(
            "192.168.1.1", openPorts = listOf(DiscoveredPort(1900, "UPnP")), portsScanned = true, isGateway = true,
            profile = LanProfile(portForwarding = PortForwarding(1_000, true, "203.0.113.7", listOf(rule))),
        )
        val titles = PortRisk.findings(router).map { it.title }
        assertEquals(listOf("Port forwarding is on", "UPnP port control"), titles)

        val restored = HistoryCodec.lan(HistoryCodec.lan(router))!!
        assertEquals(router.profile.portForwarding, restored.profile.portForwarding)
        assertEquals(listOf(rule), HistoryCodec.lan(HistoryCodec.lan(camera))!!.profile.inbound)
    }

    // HTTPS interception

    private fun seen(host: String, issuer: String?, trusted: Boolean = true, error: String? = null): TlsObservation {
        val reference = TlsInspector.references.first { it.host == host }
        return TlsObservation(host, issuer, trusted, error != null || TlsInspector.isExpected(issuer, reference), error)
    }

    @Test
    fun aProxyThatSignsEverythingIsInterception() {
        val clean = TlsCheck(
            listOf(
                seen("www.apple.com", "Apple Inc."), seen("www.google.com", "Google Trust Services"),
                seen("www.cloudflare.com", "Let's Encrypt"), seen("www.wikipedia.org", "DigiCert Inc"),
            ),
        )
        assertFalse(clean.intercepted)
        assertEquals(4, clean.reachable)
        val proxied = TlsCheck(TlsInspector.references.map { seen(it.host, "Zscaler Inc.") })
        assertTrue(proxied.intercepted)
        assertTrue(TlsCheck(listOf(seen("www.apple.com", "Apple Inc.", trusted = false))).intercepted)
        // A site that could not be reached says nothing either way.
        val offline = TlsCheck(TlsInspector.references.map { seen(it.host, null, trusted = false, error = "timeout") })
        assertFalse(offline.intercepted)
        assertEquals(0, offline.reachable)
    }

    @Test
    fun distinguishedNamesGiveUpTheirParts() {
        val dn = "CN=WE1,O=Google Trust Services,C=US"
        assertEquals("Google Trust Services", TlsInspector.attribute(dn, "O"))
        assertEquals("WE1", TlsInspector.attribute(dn, "cn"))
        assertEquals("Acme, Inc.", TlsInspector.attribute("CN=x,O=Acme\\, Inc.,C=US", "O"))
        assertNull(TlsInspector.attribute(dn, "OU"))
    }

    // DNS and outside ports

    @Test
    fun theResolverPathIsReadFromWhoAnswers() {
        val router = DnsReport(configured = listOf("192.168.1.1"), resolverIp = "8.8.8.8")
        assertEquals(DnsReport.Path.FORWARDED, router.path("203.0.113.7"))
        assertEquals(DnsReport.Path.LOCAL, router.copy(resolverIp = "203.0.113.7").path("203.0.113.7"))
        assertEquals(DnsReport.Path.DIRECT, DnsReport(listOf("1.1.1.1"), "1.1.1.1").path(null))
        assertEquals(DnsReport.Path.UNKNOWN, DnsReport().path("203.0.113.7"))
        assertTrue(DnsReport.isPrivate("fd00::1"))
        assertFalse(DnsReport.isPrivate("172.32.0.1"))
    }

    @Test
    fun aTcpCheckIsOpenClosedOrStillRunning() {
        assertEquals(true, LinkParse.tcpOpen("""{"de1.node":[{"time":0.04,"address":"203.0.113.7"}]}""", "de1.node"))
        assertEquals(false, LinkParse.tcpOpen("""{"de1.node":[{"error":"Connection timed out"}]}""", "de1.node"))
        assertNull(LinkParse.tcpOpen("""{"de1.node":null}""", "de1.node"))
        assertNull(LinkParse.tcpOpen("not json", "de1.node"))
    }

    @Test
    fun theChecksAreKeptWithASavedConnection() {
        val report = LinkReport().apply {
            status = LinkStatus.ONLINE
            tls = TlsCheck(listOf(TlsObservation("www.apple.com", "Apple Inc.", true, true, notBefore = 1_700_000_000_000, chain = listOf("a", "b"))))
            resolver = DnsReport(listOf("192.168.1.1"), "8.8.8.8", "Google LLC", "US", false, true, false)
            exposure = ExposureReport("203.0.113.7", 1_700_000_000_000, listOf(ExposedPort(22, false), ExposedPort(443, true)), RemoteNote.BUSY)
        }
        val saved = SavedConnection.from(report, viaVpn = false)!!
        val back = SavedConnection.fromJson(saved.toJson())!!
        assertEquals(saved.tls, back.tls)
        assertEquals(saved.resolver, back.resolver)
        assertEquals(saved.exposure, back.exposure)
        // The keys an iPhone writes, with its dates as seconds since 2001.
        val json = saved.toJson()
        assertEquals("8.8.8.8", json.getJSONObject("resolver").getString("resolverIP"))
        assertEquals(721_692_800.0, json.getJSONObject("exposure").getDouble("checkedAt"), 0.001)
        assertEquals("busy", json.getJSONObject("exposure").getString("note"))
    }

    // Certificate and domain age

    @Test
    fun rdapAnswersGiveTheRegistrationDate() {
        assertEquals("https://rdap.verisign.com/com/v1/", RdapServers.server("example.com"))
        assertNull(RdapServers.server("example.invalidzone"))
        val json = """{"ldhName":"EXAMPLE.COM","events":[{"eventAction":"registration","eventDate":"1995-08-14T04:00:00Z"},
            {"eventAction":"expiration","eventDate":"2027-08-13T04:00:00.000Z"}],
            "entities":[{"roles":["registrar"],"vcardArray":["vcard",[["version",{},"text","4.0"],["fn",{},"text","Example Registrar"]]]}]}"""
        val report = RdapServers.parse(json, "example.com")!!
        assertEquals(DomainReport("example.com", 808_372_800_000, 1_818_129_600_000, "Example Registrar"), report)
        assertNull(RdapServers.parse("oops", "example.com"))
    }

    @Test
    fun aYoungSiteIsFlagged() {
        val now = 1_800_000_000_000
        fun kinds(report: SiteReport) = report.findings(now).map { it.kind }
        val fresh = SiteReport(
            "pay.example", SiteCertificate("Let's Encrypt", now - 3 * day, now + 80 * day, true, null), null,
            DomainOutcome.Found(DomainReport("pay.example", registered = now - 5 * day)),
        )
        assertEquals(listOf(Kind.DOMAIN_NEW, Kind.CERTIFICATE_NEW), kinds(fresh))
        val old = SiteReport(
            "example.com", SiteCertificate("DigiCert Inc", now - 90 * day, now + 200 * day, true, null), null,
            DomainOutcome.Found(DomainReport("example.com", registered = now - 4000 * day)),
        )
        assertEquals(setOf(Kind.CERTIFICATE_INFO, Kind.DOMAIN_INFO), kinds(old).toSet())
        val broken = SiteReport(
            "bad.example", SiteCertificate("Nobody", now - 400 * day, now - day, false, "expired"), null,
            DomainOutcome.NotRegistered("bad.example"),
        )
        assertEquals(setOf(Kind.CERTIFICATE_UNTRUSTED, Kind.CERTIFICATE_EXPIRED, Kind.DOMAIN_MISSING), kinds(broken).toSet())
        val silent = SiteReport("x.example", null, "refused", DomainOutcome.UnsupportedZone("example"))
        assertEquals(listOf(Kind.CERTIFICATE_UNAVAILABLE, Kind.DOMAIN_UNKNOWN), kinds(silent))
        assertEquals(listOf(Kind.DOMAIN_YOUNG), kinds(SiteReport("y", null, null, DomainOutcome.Found(DomainReport("y", now - 100 * day)))))
    }

    // QR and speed

    @Test
    fun scannedCodesAreToldApart() {
        assertEquals(QrPayload.Link("https://example.com/pay"), QrPayload.classify("https://example.com/pay"))
        assertEquals(QrPayload.Link("Scan to pay: bit.ly/3abc"), QrPayload.classify("  Scan to pay: bit.ly/3abc  "))
        assertEquals(QrPayload.Text("Table 12"), QrPayload.classify("Table 12"))
        assertEquals(QrPayload.Wifi("Cafe Wi-Fi", "WPA", false), QrPayload.classify("WIFI:T:WPA;S:Cafe Wi-Fi;P:secret;;"))
        assertEquals(QrPayload.Wifi("Semi;colon", null, true), QrPayload.classify("WIFI:S:Semi\\;colon;T:nopass;H:true;;"))
        assertEquals(QrPayload.Wifi("Old", "WEP", false), QrPayload.classify("wifi:T:WEP;S:Old;P:1;"))
    }

    @Test
    fun speedFiguresComeFromTheSamples() {
        assertEquals(20 to 5, SpeedResult.latency(listOf(20, 25, 20, 15)))
        assertEquals(null to null, SpeedResult.latency(emptyList()))
        // The first second, while the streams ramp up, is left out.
        val samples = listOf(1.0, 2.0, 3.0, 4.0) + List(8) { 100.0 }
        assertEquals(100.0, SpeedResult.settled(samples)!!, 0.001)
        assertNull(SpeedResult.settled(emptyList()))
        // Short runs keep their last four samples, as on iOS.
        assertEquals(15.0, SpeedResult.settled(listOf(1.0, 10.0, 20.0, 15.0, 15.0))!!, 0.001)
        assertEquals(30.0, SpeedResult.smoothed(listOf(0.0, 0.0, 40.0, 40.0, 40.0)), 0.001)
        assertEquals(0.0, SpeedResult.smoothed(emptyList()), 0.001)
        assertEquals("A", SpeedResult.grade(10))
        assertEquals("C", SpeedResult.grade(150))
        assertEquals("F", SpeedResult.grade(900))
        val result = SpeedResult(0, 1e8, 2e7, 12, 2, 40, 95, emptyList(), emptyList(), "BEG")
        assertEquals("C", result.bufferbloatGrade)
    }
}
