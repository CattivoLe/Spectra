package cattivole.spectra

import cattivole.spectra.core.AcceptEncodingStrip
import cattivole.spectra.core.HttpAssembler
import cattivole.spectra.core.HttpExchange
import cattivole.spectra.core.HttpField
import cattivole.spectra.core.IpCodec
import cattivole.spectra.core.LocalCa
import cattivole.spectra.core.TlsBridge
import cattivole.spectra.core.TrafficFlow
import cattivole.spectra.core.TrafficHints
import cattivole.spectra.core.TrafficKind
import cattivole.spectra.core.TrafficSession
import cattivole.spectra.core.kind
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.ArrayDeque
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficTest {
    @Test
    fun tcpPacketRoundTripsAndChecksOut() {
        val src = byteArrayOf(10, 0, 0, 2)
        val dst = byteArrayOf(1, 2, 3, 4)
        val payload = "hello".toByteArray()
        val packet = IpCodec.tcpPacket(
            src, dst, 40000, 443, seq = 1000, ack = 2000,
            flags = IpCodec.ACK or IpCodec.PSH, window = 65535, payload = payload, id = 7,
        )
        val ip = IpCodec.parse(packet)
        assertNotNull(ip)
        assertEquals(4, ip!!.version)
        assertEquals(IpCodec.TCP, ip.protocol)
        assertTrue(src.contentEquals(ip.src))
        assertTrue(dst.contentEquals(ip.dst))
        val tcp = IpCodec.parseTcp(ip.payload)
        assertNotNull(tcp)
        assertEquals(40000, tcp!!.srcPort)
        assertEquals(443, tcp.dstPort)
        assertEquals(1000L, tcp.seq)
        assertEquals(2000L, tcp.ack)
        assertEquals(IpCodec.ACK or IpCodec.PSH, tcp.flags)
        assertTrue(payload.contentEquals(tcp.payload))
        assertTrue(IpCodec.transportValid(ip.src, ip.dst, IpCodec.TCP, ip.payload))
    }

    @Test
    fun synAckCarriesMssAndWindowScale() {
        val src = byteArrayOf(1, 1, 1, 1)
        val dst = byteArrayOf(10, 111.toByte(), 222.toByte(), 1)
        val packet = IpCodec.tcpPacket(
            src, dst, 443, 50000, seq = 9, ack = 2,
            flags = IpCodec.SYN or IpCodec.ACK, window = 65535, payload = ByteArray(0),
            mss = 1360, windowScale = 7,
        )
        val tcp = IpCodec.parseTcp(IpCodec.parse(packet)!!.payload)!!
        assertEquals(7, tcp.windowScale)
        assertEquals(IpCodec.SYN or IpCodec.ACK, tcp.flags)
        assertTrue(tcp.payload.isEmpty())
        assertTrue(IpCodec.transportValid(src, dst, IpCodec.TCP, IpCodec.parse(packet)!!.payload))
    }

    @Test
    fun udpPacketRoundTrips() {
        val src = byteArrayOf(8, 8, 8, 8)
        val dst = byteArrayOf(10, 0, 0, 2)
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = IpCodec.udpPacket(src, dst, 53, 53000, payload, id = 3)
        val ip = IpCodec.parse(packet)!!
        val udp = IpCodec.parseUdp(ip.payload)!!
        assertEquals(53, udp.srcPort)
        assertEquals(53000, udp.dstPort)
        assertTrue(payload.contentEquals(udp.payload))
        assertTrue(IpCodec.transportValid(ip.src, ip.dst, IpCodec.UDP, ip.payload))
    }

    @Test
    fun ipv6TcpRoundTrips() {
        val src = ByteArray(16).also { it[0] = 0x20; it[15] = 1 }
        val dst = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
        val packet = IpCodec.tcpPacket(
            src, dst, 1234, 80, seq = 5, ack = 6,
            flags = IpCodec.ACK, window = 1000, payload = byteArrayOf(9),
        )
        val ip = IpCodec.parse(packet)!!
        assertEquals(6, ip.version)
        assertTrue(src.contentEquals(ip.src))
        assertTrue(dst.contentEquals(ip.dst))
        assertEquals(1, IpCodec.parseTcp(ip.payload)!!.payload.size)
        assertTrue(IpCodec.transportValid(src, dst, IpCodec.TCP, ip.payload))
    }

    @Test
    fun classicHeaderParses() {
        val header = byteArrayOf(
            0x45, 0x00, 0x00, 0x3c, 0x1c, 0x46, 0x40, 0x00, 0x40, 0x06,
            0xb1.toByte(), 0xe6.toByte(), 0xac.toByte(), 0x10, 0x0a, 0x63, 0xac.toByte(), 0x10, 0x0a, 0x0c,
        ) + ByteArray(40)
        val ip = IpCodec.parse(header)!!
        assertEquals("172.16.10.99", IpCodec.text(ip.src))
        assertEquals("172.16.10.12", IpCodec.text(ip.dst))
        assertEquals(IpCodec.TCP, ip.protocol)
    }

    @Test
    fun fragmentsAndMulticastAreDropped() {
        val fragment = byteArrayOf(
            0x45, 0x00, 0x00, 0x14, 0x00, 0x01, 0x00, 0x08, 0x40, 0x06,
            0x00, 0x00, 10, 0, 0, 1, 1, 2, 3, 4,
        )
        assertNull(IpCodec.parse(fragment))
        assertFalse(IpCodec.unicast(byteArrayOf(224.toByte(), 0, 0, 1)))
        assertFalse(IpCodec.unicast(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte())))
        assertTrue(IpCodec.unicast(byteArrayOf(8, 8, 8, 8)))
    }

    @Test
    fun dnsQuestionAndAnswer() {
        val query = byteArrayOf(
            0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
            0x00, 0x01, 0x00, 0x01,
        )
        assertEquals("example.com", TrafficHints.dnsQuestion(query))
        val answer = byteArrayOf(
            0x00, 0x01, 0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
            0x00, 0x01, 0x00, 0x01,
            0xc0.toByte(), 0x0c, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3c, 0x00, 0x04,
            1, 2, 3, 4,
        )
        assertEquals(listOf("example.com" to "1.2.3.4"), TrafficHints.dnsAnswers(answer))
        assertTrue(TrafficHints.dnsAnswers(query).isEmpty())
    }

    @Test
    fun tlsServerNameAndCleartextHttp() {
        val name = "example.com"
        val hello = clientHello(name)
        assertEquals(name, TrafficHints.tlsServerName(hello))
        assertTrue(TrafficHints.tlsRecordComplete(hello))
        assertNull(TrafficHints.tlsServerName(hello.copyOf(10)))
        val http = "GET /index HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        assertEquals("GET /index" to "example.com", TrafficHints.httpRequest(http))
        assertTrue(TrafficHints.awaitsHttp("GET /ind".toByteArray()))
        assertFalse(TrafficHints.awaitsHttp(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
    }

    @Test
    fun sessionRoundTrips() {
        val exchange = HttpExchange(
            method = "GET", path = "/index", query = listOf(HttpField("q", "1")),
            requestHeaders = listOf(HttpField("Host", "example.com")),
            parameters = emptyList(), status = 200,
            responseHeaders = listOf(HttpField("Content-Type", "application/json")),
            responseJson = "{\n  \"ok\": true\n}", responseCut = false,
        )
        val flow = TrafficFlow(
            id = 4, protocol = "TCP", source = "10.111.222.1", sourcePort = 9,
            destination = "1.2.3.4", destinationPort = 443, host = "example.com",
            request = "GET /index", app = "Chrome", tls = false, sent = 20, received = 80,
            startedAt = 10, lastAt = 30, closed = true, exchanges = listOf(exchange),
        )
        assertEquals(TrafficKind.HTTP, flow.kind())
        val plain = flow.copy(exchanges = emptyList(), request = null, tls = true)
        assertEquals(TrafficKind.TLS, plain.kind())
        val session = TrafficSession("abc", 10, 40, listOf(flow))
        val decoded = TrafficSession.fromJson(session.toJson().toString())
        assertNotNull(decoded)
        assertEquals("abc", decoded!!.id)
        assertEquals(1, decoded.flows.size)
        assertEquals(flow, decoded.flows[0])
        val legacy = flow.toJson().apply { remove("exchanges") }
        assertTrue(TrafficFlow.fromJson(legacy)!!.exchanges.isEmpty())
    }

    @Test
    fun httpQueryHeadersAndJson() {
        val assembler = HttpAssembler()
        assembler.client("GET /search?q=hello+world&n=2 HTTP/1.1\r\nHost: example.com\r\nAccept: application/json\r\n\r\n".toByteArray())
        val open = assembler.exchanges().single()
        assertEquals("GET", open.method)
        assertEquals("/search", open.path)
        assertEquals("hello world", open.query.first { it.name == "q" }.value)
        assertEquals("example.com", open.requestHeaders.first { it.name == "Host" }.value)
        assertNull(open.status)
        val body = """{"ok":true,"n":2}"""
        assembler.server("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body".toByteArray())
        val done = assembler.exchanges().single()
        assertEquals(200, done.status)
        assertTrue(done.responseJson!!.contains("\"ok\": true"))
        assertFalse(done.responseCut)
        val big = HttpAssembler()
        val wide = buildString {
            append('{')
            for (i in 1..3000) {
                if (i > 1) append(',')
                append("\"k$i\":\"v$i\"")
            }
            append('}')
        }
        big.client("GET /big HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        big.server("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${wide.toByteArray().size}\r\n\r\n$wide".toByteArray())
        val whole = big.exchanges().single()
        assertFalse(whole.responseCut)
        assertTrue(whole.responseJson!!.contains("\"k3000\""))
    }

    @Test
    fun httpFormAndJsonParameters() {
        val assembler = HttpAssembler()
        val form = "user=a%20b&n=1"
        assembler.client("POST /login HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${form.length}\r\n\r\n$form".toByteArray())
        assembler.server("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
        val posted = assembler.exchanges().single()
        assertEquals("a b", posted.parameters.first { it.name == "user" }.value)
        assertEquals(204, posted.status)
        val json = """{"id":7,"name":"ada","nested":{"x":1}}"""
        assembler.client("POST /api HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: ${json.toByteArray().size}\r\n\r\n$json".toByteArray())
        assembler.server("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
        val created = assembler.exchanges()[1]
        assertEquals("7", created.parameters.first { it.name == "id" }.value)
        assertEquals("ada", created.parameters.first { it.name == "name" }.value)
        assertTrue(created.parameters.none { it.name == "nested" })
    }

    @Test
    fun chunkedJsonThenNextRequestAndGzip() {
        val assembler = HttpAssembler()
        assembler.client("GET /a HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val payload = """{"a":1}"""
        val chunked = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "${payload.length.toString(16)}\r\n$payload\r\n0\r\n\r\n"
        assembler.server(chunked.toByteArray().copyOf(12))
        assertNull(assembler.exchanges().single().status)
        assembler.server(chunked.toByteArray().copyOfRange(12, chunked.length))
        assertTrue(assembler.exchanges().single().responseJson!!.contains("\"a\": 1"))
        assembler.client("GET /b HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val raw = """{"ok":true}""".toByteArray()
        val gzip = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(raw) }
            out.toByteArray()
        }
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\nContent-Length: ${gzip.size}\r\n\r\n"
        assembler.server(head.toByteArray() + gzip)
        assertEquals(2, assembler.exchanges().size)
        assertEquals("/b", assembler.exchanges()[1].path)
        assertTrue(assembler.exchanges()[1].responseJson!!.contains("\"ok\": true"))
        val pipelined = HttpAssembler()
        pipelined.client("GET /a HTTP/1.1\r\nHost: h\r\n\r\nGET /b HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val first = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nx\r\n0\r\n\r\n"
        val second = "HTTP/1.1 204 No Content\r\n\r\n"
        pipelined.server((first + second).toByteArray())
        assertEquals(204, pipelined.exchanges()[1].status)
    }

    @Test
    fun interimContinueAndOversizedHeadersStop() {
        val assembler = HttpAssembler()
        assembler.client("POST /up HTTP/1.1\r\nContent-Length: 2\r\n\r\nab".toByteArray())
        assembler.server("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n".toByteArray())
        assertEquals(204, assembler.exchanges().single().status)
        val stalled = HttpAssembler()
        stalled.client(("GET / HTTP/1.1\r\nX: " + "a".repeat(40_000) + "\r\n\r\n").toByteArray())
        assertTrue(stalled.exchanges().isEmpty())
        stalled.client("GET /later HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        assertTrue(stalled.exchanges().isEmpty())
    }

    @Test
    fun authoritySignsLeavesAndReloads() {
        val dir = Files.createTempDirectory("spectra-ca").toFile()
        val ca = LocalCa.open(dir)
        ca.certificate.verify(ca.certificate.publicKey)
        assertTrue(ca.certificate.basicConstraints >= 0)
        assertTrue(ca.certificate.subjectX500Principal.name.contains("Spectra"))
        val leaf = ca.leaf("example.com")
        leaf.verify(ca.certificate.publicKey)
        assertTrue(leaf.basicConstraints < 0)
        assertTrue(leaf.subjectAlternativeNames.any { it[0] == 2 && it[1] == "example.com" })
        val ip = ca.leaf("1.2.3.4")
        ip.verify(ca.certificate.publicKey)
        assertTrue(ip.subjectAlternativeNames.any { it[0] == 7 })
        LocalCa.forget(dir)
        val again = LocalCa.open(dir)
        assertTrue(ca.certificate.encoded.contentEquals(again.certificate.encoded))
    }

    @Test
    fun jsonWithXssiPrefixAndBrotliBody() {
        val assembler = HttpAssembler()
        assembler.client("GET /p HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val prefixed = ")]}',\n{\"ok\":true}"
        assembler.server("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${prefixed.toByteArray().size}\r\n\r\n$prefixed".toByteArray())
        assertTrue(assembler.exchanges().single().responseJson!!.contains("\"ok\": true"))
        val page = HttpAssembler()
        page.client("GET /page HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val html = "<html><body>hello</body></html>"
        page.server("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\n\r\n$html".toByteArray())
        assertTrue(page.exchanges().single().responseJson!!.contains("hello"))
        val stream = HttpAssembler()
        stream.client("GET /events HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val events = "data: {\"ok\":true}\n\n"
        stream.server("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${events.length}\r\n\r\n$events".toByteArray())
        assertTrue(stream.exchanges().single().responseJson!!.contains("\"ok\":true"))
        val brotli = HttpAssembler()
        brotli.client("GET /b HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val compressed = byteArrayOf(0x0f, 0x05, 0x80.toByte(), 0x7b, 0x22, 0x6f, 0x6b, 0x22, 0x3a, 0x74, 0x72, 0x75, 0x65, 0x7d, 0x03)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: br\r\nContent-Length: ${compressed.size}\r\n\r\n"
        brotli.server(head.toByteArray() + compressed)
        assertTrue(brotli.exchanges().single().responseJson!!.contains("\"ok\": true"))
    }

    @Test
    fun acceptEncodingIsRewrittenButTheBodyIsKept() {
        val strip = AcceptEncodingStrip()
        val body = "{\"ok\":true}"
        val request = "POST /api HTTP/1.1\r\nHost: example.com\r\nAccept-Encoding: gzip, deflate, br\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val out = StringBuilder()
        request.chunked(5).forEach { piece ->
            strip.feed(piece.toByteArray()).forEach { out.append(String(it, Charsets.ISO_8859_1)) }
        }
        assertTrue(out.contains("Accept-Encoding: identity"))
        assertFalse(out.contains("br"))
        assertTrue(out.endsWith(body))
        val next = strip.feed("GET /next HTTP/1.1\r\nHost: example.com\r\nAccept-Encoding: gzip\r\n\r\n".toByteArray())
        val second = next.joinToString("") { String(it, Charsets.ISO_8859_1) }
        assertTrue(second.contains("GET /next"))
        assertTrue(second.contains("Accept-Encoding: identity"))
    }

    @Test
    fun bridgeShowsCleartextHttp() {
        val ca = LocalCa.open(Files.createTempDirectory("spectra-bridge").toFile())
        val origin = LocalCa.open(Files.createTempDirectory("spectra-origin").toFile())
        val toOrigin = ArrayDeque<ByteArray>()
        val toPhone = ArrayDeque<ByteArray>()
        val fromPhone = StringBuilder()
        val fromServer = StringBuilder()
        val bridge = TlsBridge(
            host = "example.com",
            port = 443,
            clientContext = ca.contextFor("example.com"),
            serverContext = LocalCa.trusting(origin.certificate),
            onClientPlain = { fromPhone.append(String(it, Charsets.ISO_8859_1)) },
            onServerPlain = { fromServer.append(String(it, Charsets.ISO_8859_1)) },
            onServerClosed = {},
            toClient = { toPhone.add(it) },
            toServer = { toOrigin.add(it) },
        )
        val phone = LocalCa.trusting(ca.certificate).createSSLEngine("example.com", 443)
        phone.useClientMode = true
        phone.sslParameters = phone.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        phone.beginHandshake()
        val server = origin.contextFor("example.com").createSSLEngine()
        server.useClientMode = false
        server.beginHandshake()
        val phoneInbox = ArrayDeque<ByteArray>()
        val serverInbox = ArrayDeque<ByteArray>()
        val phoneOut = ArrayDeque<ByteArray>()
        val serverOut = ArrayDeque<ByteArray>()
        val request = "GET /search?q=1 HTTP/1.1\r\nHost: example.com\r\n\r\n"
        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ok\":true}"
        var sentRequest = false
        var sentResponse = false
        for (step in 0 until 48) {
            val requestBytes = if (!sentRequest && phone.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) request.toByteArray() else null
            tick(phone, phoneInbox, phoneOut, requestBytes)
            if (requestBytes != null) sentRequest = true
            while (phoneOut.isNotEmpty()) bridge.fromClient(phoneOut.removeFirst())
            while (toOrigin.isNotEmpty()) serverInbox.add(toOrigin.removeFirst())
            val responseBytes = if (!sentResponse && fromPhone.contains("GET /search") && server.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) response.toByteArray() else null
            tick(server, serverInbox, serverOut, responseBytes)
            if (responseBytes != null) sentResponse = true
            while (serverOut.isNotEmpty()) bridge.fromServer(serverOut.removeFirst())
            while (toPhone.isNotEmpty()) phoneInbox.add(toPhone.removeFirst())
            if (fromServer.contains("{\"ok\":true}")) break
        }
        assertTrue(
            "phone=${phone.handshakeStatus} server=${server.handshakeStatus} request=$fromPhone response=$fromServer origin=${toOrigin.size} phoneQ=${toPhone.size}",
            fromPhone.contains("GET /search?q=1") && fromServer.contains("{\"ok\":true}"),
        )
    }

    private fun tick(engine: SSLEngine, inbox: ArrayDeque<ByteArray>, outbox: ArrayDeque<ByteArray>, plain: ByteArray?) {
        if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            while (true) {
                val task = engine.delegatedTask ?: break
                task.run()
            }
        }
        val app = when {
            plain != null -> ByteBuffer.wrap(plain)
            engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP -> ByteBuffer.allocate(0)
            else -> null
        }
        if (app != null && (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP || engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)) {
            val net = ByteBuffer.allocate(maxOf(engine.session.packetBufferSize, 18_432))
            val result = engine.wrap(app, net)
            if (result.bytesProduced() > 0) {
                val bytes = ByteArray(result.bytesProduced())
                net.flip()
                net.get(bytes)
                outbox.add(bytes)
            }
        }
        val status = engine.handshakeStatus
        if (inbox.isNotEmpty() && (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP || status.name == "NEED_UNWRAP_AGAIN" || status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)) {
            val src = ByteBuffer.wrap(inbox.removeFirst())
            val buf = ByteBuffer.allocate(maxOf(engine.session.applicationBufferSize, 32_768) + 64)
            engine.unwrap(src, buf)
            if (src.hasRemaining()) {
                val rest = ByteArray(src.remaining())
                src.get(rest)
                inbox.addFirst(rest)
            }
        }
    }

    private fun clientHello(host: String): ByteArray {
        val name = host.toByteArray()
        val serverName = byteArrayOf(0, 0, 0, (5 + name.size).toByte()) +
            byteArrayOf(0, (3 + name.size).toByte(), 0) +
            byteArrayOf(0, name.size.toByte()) + name
        val head = ByteArray(43)
        head[0] = 3
        head[1] = 3
        head[35] = 0
        head[36] = 2
        head[38] = 0x2f
        head[39] = 1
        val extLen = serverName.size
        head[41] = (extLen shr 8).toByte()
        head[42] = extLen.toByte()
        val body = head + serverName
        val handshake = byteArrayOf(1, 0, 0, body.size.toByte()) + body
        return byteArrayOf(22, 3, 3, (handshake.size shr 8).toByte(), handshake.size.toByte()) + handshake
    }
}
