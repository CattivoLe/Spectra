package cattivole.spectra

import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.BleDevice
import cattivole.spectra.core.BodyText
import cattivole.spectra.core.Cipher
import cattivole.spectra.core.ConnectionFilter
import cattivole.spectra.core.HistoryCodec
import cattivole.spectra.core.HistoryEntry
import cattivole.spectra.core.HttpExchange
import cattivole.spectra.core.JsonSyntax
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.NetworkStore
import cattivole.spectra.core.PhoneWifi
import cattivole.spectra.core.RadioSource
import cattivole.spectra.core.RequestFilter
import cattivole.spectra.core.SecondaryChannel
import cattivole.spectra.core.Sightings
import cattivole.spectra.core.TrackedNetwork
import cattivole.spectra.core.TrafficFlow
import cattivole.spectra.core.TunnelRoute
import cattivole.spectra.core.VpnPath
import cattivole.spectra.core.WsMessage
import cattivole.spectra.core.isOpenWifi
import cattivole.spectra.core.matching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.SocketException

class SightingsTest {
    private val early = 1_000_000L
    private val late = 900_000_000L
    private val bssid = MacAddress(byteArrayOf(1, 2, 3, 4, 5, 6))

    private fun network(date: Long) = TrackedNetwork(
        AccessPointReading(bssid, -60, 6, SecondaryChannel.NONE, AuthMode.WPA2_PSK, Cipher.CCMP, Cipher.CCMP, 0, null, "Net".encodeToByteArray()),
        date, date, mutableListOf(), source = RadioSource.PHONE,
    )

    private fun host(ip: String, seen: Long, names: List<String> = emptyList(), mac: String? = null) =
        LanDevice(ipAddress = ip, macAddress = mac?.let { MacAddress.parse(it) }, hostnames = names, firstSeen = seen, lastSeen = seen)

    private fun entry(
        networks: List<TrackedNetwork> = emptyList(),
        lan: List<LanDevice> = emptyList(),
        bluetooth: List<BleDevice> = emptyList(),
        place: String? = null,
        from: String? = null,
    ) = HistoryEntry("id", "networks", late, "This phone", null, placeKey = place, networks = networks, lan = lan, bluetooth = bluetooth, importedFrom = from)

    @Test
    fun aNetworkKeepsTheEarliestDateOfAllSessions() {
        val new = entry(listOf(network(late)))
        val sightings = Sightings(listOf(new, entry(listOf(network(early)))))
        assertEquals(early, sightings.first(bssid))
        assertTrue(sightings.date(new))
        assertEquals(early, new.networks.single().firstSeen)
        // Nothing left to move the second time.
        assertFalse(sightings.date(new))
        assertNull(sightings.first(MacAddress(ByteArray(6) { 9 })))
    }

    @Test
    fun aHostIsKnownByNameOnAnyNetworkAndByAddressOnItsOwn() {
        val home = "lan:net:192.168.1.0/24"
        val sightings = Sightings(
            listOf(entry(lan = listOf(host("192.168.1.5", early, names = listOf("living-room-tv.local")), host("192.168.1.7", early)), place = home)),
        )
        // The name follows the host to a new address, and to the network's other key.
        assertEquals(early, sightings.first(host("192.168.1.80", late, names = listOf("living-room-tv.local")), listOf("lan:gw:02:00:00:00:00:01")))
        // Without a name the address counts, on a network saved under one of the keys.
        assertEquals(early, sightings.first(host("192.168.1.7", late), listOf("lan:gw:02:00:00:00:00:01", home)))
        assertNull(sightings.first(host("192.168.1.7", late), listOf("lan:gw:02:00:00:00:00:99")))
    }

    @Test
    fun thisPhoneIsDatedPerNetwork() {
        val phone = LanDevice(ipAddress = "192.168.1.2", hostnames = listOf("my-phone"), isLocalDevice = true, firstSeen = early, lastSeen = early)
        val sightings = Sightings(listOf(entry(lan = listOf(phone), place = "lan:net:192.168.1.0/24")))
        assertNull(sightings.first(phone, listOf("lan:net:10.0.0.0/24")))
        assertEquals(early, sightings.first(phone, listOf("lan:net:192.168.1.0/24")))
    }

    @Test
    fun anotherPhonesSessionsDoNotCount() {
        val imported = entry(listOf(network(early)), listOf(host("192.168.1.5", early, mac = "02:00:00:00:00:01")), place = "lan:x", from = "Pixel")
        val sightings = Sightings(listOf(imported))
        assertNull(sightings.first(bssid))
        assertNull(sightings.first(host("192.168.1.5", late, mac = "02:00:00:00:00:01"), listOf("lan:x")))
        // And they keep their own dates.
        val theirs = entry(listOf(network(late)), from = "Pixel")
        assertFalse(Sightings(listOf(entry(listOf(network(early))))).date(theirs))
        assertEquals(late, theirs.networks.single().firstSeen)
    }

    @Test
    fun bluetoothIsDatedByItsAddress() {
        val sightings = Sightings(listOf(entry(bluetooth = listOf(BleDevice("C4:00:00:00:00:01", "Public", -60, early)))))
        assertEquals(early, sightings.firstBluetooth("C4:00:00:00:00:01"))
        assertNull(sightings.firstBluetooth("C4:00:00:00:00:02"))
    }

    @Test
    fun theLiveListStartsFromTheEarlierDateAndCatchesUpWithADateReadLater() {
        val store = NetworkStore()
        store.earlier = { early }
        store.applyPhoneSnapshot(listOf(network(late).latest), at = late)
        assertEquals(early, store.network(bssid)!!.firstSeen)
        // The history was still being read when the first scan came.
        val started = NetworkStore()
        started.applyPhoneSnapshot(listOf(network(late).latest), at = late)
        started.earlier = { early }
        started.datesChanged()
        assertEquals(early, started.network(bssid)!!.firstSeen)
    }

    @Test
    fun historySavedWithoutTheStreamCountStillOpens() {
        val json = HistoryCodec.network(network(early).apply { steadyScans = 3 })
        assertEquals(3, HistoryCodec.network(json)!!.steadyScans)
        json.remove("steadyScans")
        assertEquals(0, HistoryCodec.network(json)!!.steadyScans)
    }

    @Test
    fun aSearchNarrowsASessionToWhatItFound() {
        val session = entry(listOf(network(early)), listOf(host("192.168.1.5", early), host("192.168.1.7", early)))
        assertEquals(listOf("192.168.1.7"), session.matching("1.7").lan.map { it.ipAddress })
        assertTrue(session.matching("1.7").networks.isEmpty())
        // Found by its place or source alone, it is shown whole.
        assertSame(session, session.matching("This phone"))
        assertSame(session, session.matching(" "))
    }
}

class VpnPathTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)
    private val everything = TunnelRoute(ip("0.0.0.0"), 0)

    @Test
    fun aFullTunnelTakesTheLocalNetworkToo() {
        assertTrue(VpnPath.carries(listOf(everything), ip("192.168.1.1")))
        assertTrue(VpnPath.carriesInternet(listOf(everything)))
        assertFalse(VpnPath.carries(emptyList(), ip("192.168.1.1")))
    }

    @Test
    fun aTunnelThatLeavesTheLocalNetworkAlone() {
        val excluded = listOf(everything, TunnelRoute(ip("192.168.0.0"), 16, excluded = true))
        assertFalse(VpnPath.carries(excluded, ip("192.168.1.1")))
        assertTrue(VpnPath.carries(excluded, ip("8.8.8.8")))
        // The DNS server put back inside is the more specific route.
        assertTrue(VpnPath.carries(excluded + TunnelRoute(ip("192.168.1.1"), 32), ip("192.168.1.1")))
        // Before Android 13 a VPN lists everything but the private ranges instead.
        val split = listOf(TunnelRoute(ip("0.0.0.0"), 1), TunnelRoute(ip("128.0.0.0"), 2), TunnelRoute(ip("208.0.0.0"), 4))
        assertFalse(VpnPath.carries(split, ip("192.168.1.1")))
    }

    @Test
    fun aDnsOnlyTunnelDoesNotCarryTheInternet() {
        val adBlocker = listOf(TunnelRoute(ip("26.26.26.0"), 30), TunnelRoute(ip("1.1.1.1"), 32), TunnelRoute(ip("8.8.8.8"), 32))
        assertFalse(VpnPath.carriesInternet(adBlocker))
        assertTrue(VpnPath.carries(adBlocker, ip("1.1.1.1")))
    }

    @Test
    fun prefixesNotOnAByteBoundaryAndOtherFamilies() {
        val routes = listOf(TunnelRoute(ip("172.16.0.0"), 12))
        assertTrue(VpnPath.carries(routes, ip("172.31.255.1")))
        assertFalse(VpnPath.carries(routes, ip("172.32.0.1")))
        assertFalse(VpnPath.carries(listOf(TunnelRoute(ip("::"), 0)), ip("172.16.0.1")))
    }

    /** What Android's `bindSocket` throws, as seen on Android 17 with an ordinary VPN. */
    @Test
    fun onlyARefusalIsTheVpnsDoing() {
        val refused = SocketException("Binding socket to network 101 failed: EPERM (Operation not permitted)")
        assertTrue(VpnPath.isRefusal(refused))
        assertTrue(VpnPath.isRefusal(RuntimeException("wrapped", refused)))
        assertFalse(VpnPath.isRefusal(SocketException("Binding socket to network 101 failed: ENONET (Machine is not on the network)")))
        assertFalse(VpnPath.isRefusal(RuntimeException()))
    }
}

class OpenWifiTest {
    @Test
    fun openOnlyWhenNothingSaysOtherwise() {
        assertTrue(isOpenWifi(AuthMode.OPEN, null, listOf(AuthMode.OPEN)))
        assertTrue(isOpenWifi(AuthMode.OPEN, "", emptyList()))
        assertFalse(isOpenWifi(AuthMode.OPEN, "secret", emptyList()))
        assertFalse(isOpenWifi(AuthMode.OPEN, null, listOf(AuthMode.OPEN, AuthMode.WPA2_PSK)))
        assertFalse(isOpenWifi(AuthMode.WPA2_PSK, null, emptyList()))
        // Enhanced open has no password but is encrypted.
        assertFalse(isOpenWifi(AuthMode.OWE, null, emptyList()))
    }

    @Test
    fun protectionTheListDoesNotNameIsNotOpen() {
        assertEquals(AuthMode(8), PhoneWifi.parseCapabilities("[WAPI-WAPI-PSK-SMS4][ESS]").auth)
        assertEquals(AuthMode.OPEN, PhoneWifi.parseCapabilities("[ESS]").auth)
    }
}

class TrafficFilterTest {
    private fun exchange(method: String, path: String, status: Int?, messages: List<WsMessage> = emptyList()) =
        HttpExchange(method, path, emptyList(), emptyList(), emptyList(), status, emptyList(), messages = messages)

    private fun flow(protocol: String, port: Int, tls: Boolean = false, closed: Boolean = true, exchanges: List<HttpExchange> = emptyList()) =
        TrafficFlow(1, protocol, "10.0.0.1", 5000, "1.1.1.1", port, "api.example.com", null, "Mail", tls, 0, 0, 0, 0, closed, exchanges)

    @Test
    fun methodsStatusAndTextNarrowTheRequests() {
        val get = exchange("GET", "/users", 200)
        val post = exchange("post", "/login", 401)
        val both = flow("TCP", 443, tls = true, exchanges = listOf(get, post))
        assertTrue(RequestFilter().passes(get, both) && RequestFilter().passes(post, both) && !RequestFilter().isNarrowing)
        val posts = RequestFilter().toggled("POST")
        assertTrue(!posts.passes(get, both) && posts.passes(post, both) && posts.isNarrowing)
        assertEquals(RequestFilter(), posts.toggled("POST"))
        val failed = RequestFilter(failedOnly = true)
        assertTrue(!failed.passes(get, both) && failed.passes(post, both))
        val text = RequestFilter(query = "USERS ")
        assertTrue(text.passes(get, both) && !text.passes(post, both))
        assertTrue(RequestFilter(query = "mail").passes(post, both))
        assertFalse(RequestFilter(webSocketOnly = true).passes(get, both))
    }

    @Test
    fun aRequestStillOnItsWayHasNotFailed() {
        val waiting = exchange("GET", "/", null)
        assertFalse(RequestFilter.failed(waiting, flow("TCP", 443, closed = false)))
        assertTrue(RequestFilter.failed(waiting, flow("TCP", 443, closed = true)))
    }

    @Test
    fun commonMethodsAlwaysHaveAChip() {
        assertEquals(RequestFilter.commonMethods, RequestFilter.methods(emptyList()))
        val options = flow("TCP", 443, exchanges = listOf(exchange("OPTIONS", "/", 204), exchange("GET", "/", 200)))
        assertEquals(RequestFilter.commonMethods + "OPTIONS", RequestFilter.methods(listOf(options)))
    }

    /** Almost all TCP is TLS or HTTP, and almost all UDP is DNS: the transport filters used to show nothing. */
    @Test
    fun transportFiltersTakeInWhatRunsOverThem() {
        val web = flow("TCP", 443, tls = true)
        val read = flow("TCP", 443, tls = true, exchanges = listOf(exchange("GET", "/", 200)))
        val dns = flow("UDP", 53)
        val raw = flow("TCP", 5222)
        val all = listOf(web, read, dns, raw)
        assertEquals(listOf(web, read, raw), all.filter(ConnectionFilter.TCP::passes))
        assertEquals(listOf(dns), all.filter(ConnectionFilter.UDP::passes))
        assertEquals(listOf(web), all.filter(ConnectionFilter.TLS::passes))
        assertEquals(listOf(read), all.filter(ConnectionFilter.HTTP::passes))
        assertEquals(listOf(web, read, raw), all.filter { ConnectionFilter.passes(null, it) })
    }
}

class JsonSyntaxTest {
    private fun pieces(text: String) = JsonSyntax.spans(text).map { "${it.kind}:${text.substring(it.start, it.end)}" }

    @Test
    fun tellsKeysStringsNumbersAndLiteralsApart() {
        assertEquals(
            listOf("KEY:\"a\"", "STRING:\"b\"", "KEY:\"n\"", "NUMBER:-1.5e3", "KEY:\"t\"", "LITERAL:true", "KEY:\"z\"", "LITERAL:null"),
            pieces("""{"a" : "b", "n": -1.5e3, "t": true, "z": null}"""),
        )
    }

    @Test
    fun escapesAndWideCharactersKeepTheirPlaces() {
        assertEquals(listOf("""KEY:"q\"uote"""", """STRING:"😀 \\"""", "KEY:\"дом\"", "NUMBER:1"), pieces("""{"q\"uote": "😀 \\", "дом": 1}"""))
    }

    /** A body cut at the cap ends anywhere, also right after a backslash. */
    @Test
    fun aCutBodyIsColouredAsFarAsItGoes() {
        assertEquals(listOf("KEY:\"a\"", "STRING:\"unfinis"), pieces("""{"a": "unfinis"""))
        assertEquals(listOf("STRING:\"x\\"), pieces("[\"x\\"))
        assertTrue(pieces("").isEmpty())
    }

    @Test
    fun onlyTextThatStartsLikeJsonIsColoured() {
        assertTrue(JsonSyntax.looksLikeJson("\n  [1]"))
        assertFalse(JsonSyntax.looksLikeJson("<html>{}</html>"))
        assertFalse(JsonSyntax.looksLikeJson("   "))
    }
}

class BodyTextTest {
    private fun shown(body: BodyText) = body.rows.map { body.text.substring(it.start, it.end) }

    @Test
    fun rowsAreTheLinesOfTheText() {
        assertEquals(listOf("one", "", "three"), shown(BodyText("one\n\nthree")))
        assertEquals(listOf("a", "b", ""), shown(BodyText("a\r\nb\n")))
        assertEquals(listOf(""), shown(BodyText("")))
    }

    @Test
    fun aLongLineIsCutWithoutLosingOrSplittingACharacter() {
        val text = "x".repeat(BodyText.MAX_ROW - 1) + "😀" + "y".repeat(BodyText.MAX_ROW * 2)
        val rows = shown(BodyText(text))
        assertEquals(text, rows.joinToString(""))
        assertTrue(rows.all { it.length <= BodyText.MAX_ROW })
        assertTrue(rows[1].startsWith("😀"))
    }

    @Test
    fun aMatchKnowsItsRow() {
        val body = BodyText("{\n  \"Name\": \"first\",\n  \"other\": \"name\"\n}")
        val found = body.find("name")
        assertEquals(2, found.size)
        assertEquals(listOf(1, 2), found.map(body::rowOf))
        assertEquals(0, body.rowOf(0))
        assertEquals(3, body.rowOf(body.text.length))
        assertTrue(body.find("").isEmpty())
        assertTrue(body.find("absent").isEmpty())
    }

    @Test
    fun matchesDoNotOverlapAndAreCapped() {
        assertEquals(listOf(0, 2), BodyText("aaaa").find("aa").toList())
        assertEquals(BodyText.MAX_MATCHES, BodyText("a".repeat(BodyText.MAX_MATCHES + 5)).find("a").size)
    }

    @Test
    fun aRowGetsTheColoursThatTouchIt() {
        val body = BodyText("{\"a\": \"" + "b".repeat(BodyText.MAX_ROW + 10) + "\",\n\"c\": 1}")
        // The long string runs on into the second row.
        assertEquals(listOf(JsonSyntax.Kind.KEY, JsonSyntax.Kind.STRING), body.syntax(body.rows[0]).map { it.kind })
        assertEquals(listOf(JsonSyntax.Kind.STRING), body.syntax(body.rows[1]).map { it.kind })
        assertEquals(listOf(JsonSyntax.Kind.KEY, JsonSyntax.Kind.NUMBER), body.syntax(body.rows[2]).map { it.kind })
        assertTrue(BodyText("plain \"text\"").syntax.isEmpty())
    }
}
