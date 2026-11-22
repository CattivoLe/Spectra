package cattivole.spectra

import cattivole.spectra.core.AcceptEncodingStrip
import cattivole.spectra.core.BodyPreview
import cattivole.spectra.core.BodyView
import cattivole.spectra.core.Curl
import cattivole.spectra.core.Har
import cattivole.spectra.core.HpackDecoder
import cattivole.spectra.core.Http2Assembler
import cattivole.spectra.core.Huffman
import cattivole.spectra.core.WsMessage
import cattivole.spectra.core.BodySink
import cattivole.spectra.core.TlsHostFilter
import cattivole.spectra.core.TrafficBodies
import org.json.JSONObject
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
        val assembler = Recorder()
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
        assertTrue(assembler.responseText(0)!!.contains("\"ok\": true"))
        assertFalse(done.responseCut)
        val big = Recorder()
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
        assertTrue(big.responseText(0)!!.contains("\"k3000\""))
    }

    @Test
    fun httpFormAndJsonParameters() {
        val assembler = Recorder()
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
        val assembler = Recorder()
        assembler.client("GET /a HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val payload = """{"a":1}"""
        val chunked = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "${payload.length.toString(16)}\r\n$payload\r\n0\r\n\r\n"
        assembler.server(chunked.toByteArray().copyOf(12))
        assertNull(assembler.exchanges().single().status)
        assembler.server(chunked.toByteArray().copyOfRange(12, chunked.length))
        assertTrue(assembler.responseText(0)!!.contains("\"a\": 1"))
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
        assertTrue(assembler.responseText(1)!!.contains("\"ok\": true"))
        val pipelined = Recorder()
        pipelined.client("GET /a HTTP/1.1\r\nHost: h\r\n\r\nGET /b HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val first = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nx\r\n0\r\n\r\n"
        val second = "HTTP/1.1 204 No Content\r\n\r\n"
        pipelined.server((first + second).toByteArray())
        assertEquals(204, pipelined.exchanges()[1].status)
    }

    @Test
    fun interimContinueAndOversizedHeadersStop() {
        val assembler = Recorder()
        assembler.client("POST /up HTTP/1.1\r\nContent-Length: 2\r\n\r\nab".toByteArray())
        assembler.server("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n".toByteArray())
        assertEquals(204, assembler.exchanges().single().status)
        val stalled = Recorder()
        stalled.client(("GET / HTTP/1.1\r\nX: " + "a".repeat(40_000) + "\r\n\r\n").toByteArray())
        assertTrue(stalled.exchanges().isEmpty())
        stalled.client("GET /later HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        assertTrue(stalled.exchanges().isEmpty())
    }

    @Test
    fun authoritySignsLeavesAndReloads() {
        val dir = Files.createTempDirectory("spectra-ca").toFile()
        val ca = LocalCa.open(dir, LocalCa.PLAIN)
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
        val again = LocalCa.open(dir, LocalCa.PLAIN)
        assertTrue(ca.certificate.encoded.contentEquals(again.certificate.encoded))
    }

    @Test
    fun jsonWithXssiPrefixAndBrotliBody() {
        val assembler = Recorder()
        assembler.client("GET /p HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val prefixed = ")]}',\n{\"ok\":true}"
        assembler.server("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${prefixed.toByteArray().size}\r\n\r\n$prefixed".toByteArray())
        assertTrue(assembler.responseText(0)!!.contains("\"ok\": true"))
        val page = Recorder()
        page.client("GET /page HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val html = "<html><body>hello</body></html>"
        page.server("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\n\r\n$html".toByteArray())
        assertTrue(page.responseText(0)!!.contains("hello"))
        val stream = Recorder()
        stream.client("GET /events HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val events = "data: {\"ok\":true}\n\n"
        stream.server("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${events.length}\r\n\r\n$events".toByteArray())
        assertTrue(stream.responseText(0)!!.contains("\"ok\":true"))
        val brotli = Recorder()
        brotli.client("GET /b HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val compressed = byteArrayOf(0x0f, 0x05, 0x80.toByte(), 0x7b, 0x22, 0x6f, 0x6b, 0x22, 0x3a, 0x74, 0x72, 0x75, 0x65, 0x7d, 0x03)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: br\r\nContent-Length: ${compressed.size}\r\n\r\n"
        brotli.server(head.toByteArray() + compressed)
        assertTrue(brotli.responseText(0)!!.contains("\"ok\": true"))
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
        val ca = LocalCa.open(Files.createTempDirectory("spectra-bridge").toFile(), LocalCa.PLAIN)
        val origin = LocalCa.open(Files.createTempDirectory("spectra-origin").toFile(), LocalCa.PLAIN)
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

    @Test
    fun bodiesTimesAndSizesAreRecorded() {
        val recorder = Recorder()
        recorder.now = 1_000
        val form = "user=ada&pin=1"
        val head = "POST /login?next=%2Fhome HTTP/1.1\r\nHost: example.com\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${form.length}\r\n\r\n"
        recorder.client((head + form).toByteArray())
        recorder.now = 1_120
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        val reply = "HTTP/1.1 201 Created\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\n\r\n"
        recorder.server(reply.toByteArray().copyOf(10))
        recorder.now = 1_300
        recorder.server(reply.toByteArray().copyOfRange(10, reply.length) + png)
        val exchange = recorder.exchanges().single()
        assertEquals(1_000L, exchange.startedAt)
        assertEquals(1_000L, exchange.requestEndAt)
        assertEquals(1_120L, exchange.responseStartAt)
        assertEquals(1_300L, exchange.endedAt)
        assertEquals(120L, exchange.waitMs)
        assertEquals(300L, exchange.durationMs)
        assertEquals(head.length.toLong(), exchange.requestHeaderBytes)
        assertEquals(form.length.toLong(), exchange.requestBodyBytes)
        assertEquals((head.length + form.length).toLong(), exchange.requestSize)
        assertEquals((reply.length + png.size).toLong(), exchange.responseSize)
        assertEquals("HTTP/1.1", exchange.httpVersion)
        assertEquals("Created", exchange.statusText)
        assertEquals("/login?next=%2Fhome", exchange.target)
        assertTrue(exchange.requestBodyStored)
        assertTrue(exchange.responseBodyStored)
        assertEquals(form, String(recorder.bodies["0-true"]!!))
        assertTrue(recorder.bodies["0-false"]!!.contentEquals(png))
        assertTrue(BodyPreview.of(png, "image/png") is BodyView.Image)
        assertEquals("ada", exchange.parameters.first { it.name == "user" }.value)
    }

    @Test
    fun rawRequestBodiesAndCutsAreKept() {
        val recorder = Recorder()
        val bytes = ByteArray(300) { it.toByte() }
        recorder.client("PUT /blob HTTP/1.1\r\nHost: h\r\nContent-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray() + bytes)
        recorder.server("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
        assertTrue(recorder.bodies["0-true"]!!.contentEquals(bytes))
        val view = BodyPreview.of(bytes, "application/octet-stream")
        assertTrue(view is BodyView.Binary)
        assertTrue((view as BodyView.Binary).hex.startsWith("00000000  00 01 02 03"))
        assertEquals(300, view.size)
        assertFalse(recorder.exchanges().single().responseBodyStored)

        val big = Recorder()
        val huge = ByteArray(HttpAssembler.REQUEST_CAP + 10) { 'a'.code.toByte() }
        big.client("POST /up HTTP/1.1\r\nHost: h\r\nContent-Length: ${huge.size}\r\n\r\n".toByteArray() + huge)
        val cut = big.exchanges().single()
        assertTrue(cut.requestCut)
        assertEquals(huge.size.toLong(), cut.requestBodyBytes)
        assertEquals(HttpAssembler.REQUEST_CAP, big.bodies["0-true"]!!.size)

        val text = Recorder()
        text.client("POST /t HTTP/1.1\r\nHost: h\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello".toByteArray())
        assertTrue(BodyPreview.of(text.bodies["0-true"]!!, "text/plain") is BodyView.Text)
        val json = BodyPreview.of("""{"a":[1,2]}""".toByteArray(), "application/json")
        assertTrue((json as BodyView.Json).text.contains("\"a\": ["))
    }

    @Test
    fun chunkedSizeCountsFramingAndUnkeptBodiesAreFlagged() {
        val assembler = HttpAssembler(sink = { _, _, _ -> false })
        assembler.client("GET /c HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
        val body = "5\r\nhello\r\n0\r\n\r\n"
        assembler.server(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + body).toByteArray())
        val exchange = assembler.exchanges().single()
        assertEquals(body.length.toLong(), exchange.responseBodyBytes)
        assertFalse(exchange.responseBodyStored)
    }

    @Test
    fun oldSessionsStillOpen() {
        val legacy = """{"id":"s","startedAt":1,"stoppedAt":2,"flows":[{"id":3,"protocol":"TCP","source":"10.0.0.1",
            "sourcePort":5,"destination":"1.2.3.4","destinationPort":80,"host":"h","request":"GET /","app":null,
            "tls":false,"sent":1,"received":2,"startedAt":1,"lastAt":2,"closed":true,"exchanges":[{"method":"GET",
            "path":"/","query":[],"requestHeaders":[{"name":"Host","value":"h"}],"parameters":[],"status":200,
            "responseHeaders":[],"responseJson":"{\n  \"ok\": true\n}","responseCut":false}]}]}"""
        val session = TrafficSession.fromJson(legacy)!!
        val exchange = session.flows.single().exchanges.single()
        assertEquals(200, exchange.status)
        assertTrue(exchange.responseJson!!.contains("ok"))
        assertNull(exchange.startedAt)
        assertNull(exchange.requestSize)
        assertNull(exchange.durationMs)
        assertFalse(exchange.responseBodyStored)
        val again = HttpExchange.fromJson(exchange.copy(startedAt = 5, endedAt = 9, target = "/?a=1").toJson())!!
        assertEquals(4L, again.durationMs)
        assertEquals("/?a=1", again.target)
        val har = JSONObject(Har.build(session.flows, { _, _, _ -> null }, "1.0"))
        val content = har.getJSONObject("log").getJSONArray("entries").getJSONObject(0).getJSONObject("response").getJSONObject("content")
        assertTrue(content.getString("text").contains("ok"))
    }

    @Test
    fun harDescribesEveryExchange() {
        val get = HttpExchange(
            method = "GET", path = "/a", query = listOf(HttpField("q", "1 2")),
            requestHeaders = listOf(HttpField("Host", "example.com"), HttpField("Accept", "*/*")),
            parameters = emptyList(), status = 200, responseHeaders = listOf(HttpField("Content-Type", "image/png")),
            responseBodyStored = true, httpVersion = "HTTP/1.1", target = "/a?q=1%202", statusText = "OK",
            startedAt = 1_700_000_000_000, requestEndAt = 1_700_000_000_010, responseStartAt = 1_700_000_000_050,
            endedAt = 1_700_000_000_080, requestHeaderBytes = 40, requestBodyBytes = 0, responseHeaderBytes = 60, responseBodyBytes = 3,
        )
        val post = get.copy(
            method = "POST", path = "/b", target = "/b", query = emptyList(), status = null, responseHeaders = emptyList(),
            requestHeaders = listOf(HttpField("Content-Type", "application/json")), requestBodyStored = true, responseBodyStored = false,
            responseStartAt = null, endedAt = null, responseHeaderBytes = null, responseBodyBytes = null,
        )
        val flow = TrafficFlow(
            id = 9, protocol = "TCP", source = "10.0.0.1", sourcePort = 50000, destination = "1.2.3.4", destinationPort = 443,
            host = "example.com", request = "GET /a", app = null, tls = true, sent = 1, received = 1,
            startedAt = 1, lastAt = 2, closed = true, exchanges = listOf(get, post),
        )
        val bodies = mapOf("0-false" to byteArrayOf(1, 2, 3), "1-true" to """{"x":1}""".toByteArray())
        val har = JSONObject(Har.build(listOf(flow), { _, index, request -> bodies["$index-$request"] }, "1.1.0")).getJSONObject("log")
        assertEquals("1.2", har.getString("version"))
        assertEquals("Spectra", har.getJSONObject("creator").getString("name"))
        val entries = har.getJSONArray("entries")
        assertEquals(2, entries.length())
        val first = entries.getJSONObject(0)
        assertEquals(80, first.getInt("time"))
        assertTrue(first.getString("startedDateTime").contains("T"))
        val request = first.getJSONObject("request")
        assertEquals("https://example.com/a?q=1%202", request.getString("url"))
        assertEquals("1 2", request.getJSONArray("queryString").getJSONObject(0).getString("value"))
        val timings = first.getJSONObject("timings")
        assertEquals(10, timings.getInt("send"))
        assertEquals(40, timings.getInt("wait"))
        assertEquals(30, timings.getInt("receive"))
        val response = first.getJSONObject("response")
        assertEquals(200, response.getInt("status"))
        assertEquals("OK", response.getString("statusText"))
        assertEquals(63, response.getInt("headersSize") + response.getInt("bodySize"))
        val content = response.getJSONObject("content")
        assertEquals("base64", content.getString("encoding"))
        assertEquals("AQID", content.getString("text"))
        assertEquals("image/png", content.getString("mimeType"))
        val second = entries.getJSONObject(1)
        assertEquals("""{"x":1}""", second.getJSONObject("request").getJSONObject("postData").getString("text"))
        assertEquals(0, second.getJSONObject("response").getInt("status"))
    }

    @Test
    fun curlRebuildsTheRequest() {
        val exchange = HttpExchange(
            method = "POST", path = "/api", query = emptyList(),
            requestHeaders = listOf(
                HttpField("Host", "example.com:8443"),
                HttpField("Content-Type", "application/json"),
                HttpField("Content-Length", "13"),
                HttpField("Accept-Encoding", "gzip"),
                HttpField("X-Note", "it's"),
            ),
            parameters = emptyList(), status = 200, responseHeaders = emptyList(), target = "/api?x=1",
        )
        val flow = TrafficFlow(
            id = 1, protocol = "TCP", source = "10.0.0.1", sourcePort = 1, destination = "1.2.3.4", destinationPort = 8443,
            host = "example.com", request = null, app = null, tls = true, sent = 0, received = 0, startedAt = 0, lastAt = 0, closed = false,
        )
        val command = Curl.command(flow, exchange, """{"a":"b'c"}""".toByteArray())
        assertTrue(command.startsWith("curl \\\n  'https://example.com:8443/api?x=1'"))
        assertFalse(command.contains("-X POST"))
        assertFalse(command.contains("Content-Length"))
        assertFalse(command.contains("Host:"))
        assertTrue(command.contains("--compressed"))
        assertTrue(command.contains("-H 'X-Note: it'\\''s'"))
        assertTrue(command.contains("--data-binary '{\"a\":\"b'\\''c\"}'"))
        val get = Curl.command(flow.copy(tls = false, destinationPort = 80, host = null), exchange.copy(method = "DELETE", requestHeaders = emptyList(), target = "/x"), null)
        assertEquals("curl \\\n  -X DELETE \\\n  'http://1.2.3.4/x'", get)
        val binary = Curl.command(flow, exchange.copy(requestHeaders = emptyList()), byteArrayOf(0, 'a'.code.toByte(), 0x27))
        assertTrue(binary.endsWith("--data-binary $'\\x00a\\''"))
    }

    @Test
    fun hostPatternsMatchLikeCharles() {
        assertTrue(TlsHostFilter.matches("*.example.com", "api.example.com"))
        assertTrue(TlsHostFilter.matches("*.example.com", "a.b.example.com"))
        assertTrue(TlsHostFilter.matches("*.example.com", "example.com"))
        assertFalse(TlsHostFilter.matches("*.example.com", "badexample.com"))
        assertTrue(TlsHostFilter.matches("EXAMPLE.com:443", "example.com"))
        assertTrue(TlsHostFilter.matches("api?.example.com", "api2.example.com"))
        assertTrue(TlsHostFilter.matches("*google*", "www.google.ru"))
        assertFalse(TlsHostFilter.matches("example.com", "www.example.com"))
        assertNull(TlsHostFilter.normalize("  "))
        assertNull(TlsHostFilter.normalize("bad host"))
        assertEquals("example.com", TlsHostFilter.normalize("https://Example.com/path"))

        val all = TlsHostFilter()
        assertTrue(all.allows("x.com"))
        assertTrue(all.allows(null))
        val only = TlsHostFilter(TlsHostFilter.Mode.ONLY, only = listOf("*.example.com"), except = listOf("pinned.example.com"))
        assertTrue(only.allows("api.example.com"))
        assertFalse(only.allows("pinned.example.com"))
        assertFalse(only.allows("other.com"))
        assertFalse(only.allows(null))
        val except = TlsHostFilter(TlsHostFilter.Mode.EXCEPT, except = listOf("*.apple.com"))
        assertFalse(except.allows("gs.apple.com"))
        assertTrue(except.allows("example.com"))
        assertTrue(except.allows(null))
        val excluded = all.excluding("Pinned.App.com")
        assertEquals(TlsHostFilter.Mode.EXCEPT, excluded.mode)
        assertEquals(listOf("pinned.app.com"), excluded.except)
        assertFalse(excluded.allows("pinned.app.com"))
        assertEquals(excluded, excluded.excluding("pinned.app.com"))
        assertEquals(TlsHostFilter.Mode.ONLY, only.excluding("api.example.com").mode)
    }

    @Test
    fun bodyFolderKeepsBudgetAndCopies() {
        val dir = Files.createTempDirectory("spectra-bodies").toFile()
        val store = TrafficBodies(java.io.File(dir, "live"))
        assertTrue(store.reserve(10))
        store.write(3, 0, false, "hello".toByteArray())
        assertEquals("hello", String(store.read(3, 0, false)!!))
        assertNull(store.read(3, 0, true))
        assertFalse(store.reserve(TrafficBodies.BUDGET.toInt()))
        val saved = java.io.File(dir, "s.bodies")
        store.copyTo(saved)
        assertEquals("hello", String(TrafficBodies(saved).read(3, 0, false)!!))
        store.clear()
        assertNull(store.read(3, 0, false))
        assertEquals("hello", String(TrafficBodies(saved).read(3, 0, false)!!))
        assertTrue(store.reserve(10))
    }

    private fun hex(text: String): ByteArray = text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun hpackDecodesTheRfcExamples() {
        val plain = HpackDecoder()
        assertEquals(
            listOf(":method" to "GET", ":scheme" to "http", ":path" to "/", ":authority" to "www.example.com"),
            plain.decode(hex("8286 8441 0f77 7777 2e65 7861 6d70 6c65 2e63 6f6d")).map { it.name to it.value },
        )
        assertEquals("no-cache", plain.decode(hex("8286 84be 5808 6e6f 2d63 6163 6865")).last().value)
        val third = plain.decode(hex("8287 85bf 400a 6375 7374 6f6d 2d6b 6579 0c63 7573 746f 6d2d 7661 6c75 65"))
        assertEquals("/index.html", third.first { it.name == ":path" }.value)
        assertEquals("custom-value", third.first { it.name == "custom-key" }.value)
        val huffman = HpackDecoder()
        assertEquals("www.example.com", huffman.decode(hex("8286 8441 8cf1 e3c2 e5f2 3a6b a0ab 90f4 ff")).last().value)
        assertEquals("no-cache", huffman.decode(hex("8286 84be 5886 a8eb 1064 9cbf")).last().value)
        assertEquals("custom-value", huffman.decode(hex("8287 85bf 4088 25a8 49e9 5ba9 7d7f 8925 a849 e95b b8e8 b4bf")).last().value)
        val response = HpackDecoder(256).decode(hex("4882 6402 5885 aec3 771a 4b61 96d0 7abe 9410 54d4 44a8 2005 9504 0b81 66e0 82a6 2d1b ff6e 919d 29ad 1718 63c7 8f0b 97c8 e9ae 82ae 43d3"))
        assertEquals(listOf(":status" to "302", "cache-control" to "private", "date" to "Mon, 21 Oct 2013 20:13:21 GMT", "location" to "https://www.example.com"), response.map { it.name to it.value })
        assertEquals("302", String(Huffman.decode(hex("6402"))))
    }

    /** One HTTP/2 frame. */
    private fun frame(type: Int, flags: Int, stream: Int, payload: ByteArray): ByteArray =
        byteArrayOf((payload.size shr 16).toByte(), (payload.size shr 8).toByte(), payload.size.toByte(), type.toByte(), flags.toByte(),
            (stream shr 24).toByte(), (stream shr 16).toByte(), (stream shr 8).toByte(), stream.toByte()) + payload

    /** A header block of literal fields without indexing: what any HPACK decoder reads. */
    private fun literals(vararg fields: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((name, value) in fields) {
            out.write(0)
            out.write(name.length)
            out.write(name.toByteArray())
            out.write(value.length)
            out.write(value.toByteArray())
        }
        return out.toByteArray()
    }

    @Test
    fun http2StreamsBecomeExchanges() {
        var now = 100L
        val bodies = HashMap<String, ByteArray>()
        val h2 = Http2Assembler(sink = { index, request, body -> bodies["$index-$request"] = body; true }, clock = { now })
        val settings = frame(4, 0, 0, ByteArray(0))
        val request = frame(1, 0x4, 1, hex("8287 85bf".substring(0, 4)) + literals(":path" to "/api?x=1", ":authority" to "example.com", "content-type" to "application/json"))
        val requestBody = """{"a":1}""".toByteArray()
        h2.client(Http2Assembler.PREFACE + settings + request)
        now = 110
        h2.client(frame(0, 0x1, 1, requestBody))
        val second = frame(1, 0x5, 3, literals(":method" to "GET", ":path" to "/b", ":authority" to "example.com"))
        h2.client(second)
        now = 150
        // A response split across two reads, with a padded DATA frame and trailers.
        val response = settings + frame(1, 0x4, 1, literals(":status" to "200", "content-type" to "application/json"))
        h2.server(response.copyOf(15))
        h2.server(response.copyOfRange(15, response.size))
        now = 170
        val padded = byteArrayOf(2) + """{"ok":true}""".toByteArray() + byteArrayOf(0, 0)
        h2.server(frame(0, 0x8, 1, padded) + frame(1, 0x5, 1, literals("grpc-status" to "0")))
        h2.server(frame(1, 0x5, 3, literals(":status" to "404")))
        val list = h2.exchanges()
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals("GET", first.method)
        assertEquals("/api", first.path)
        assertEquals("/api?x=1", first.target)
        assertEquals("1", first.query.single().value)
        assertEquals("HTTP/2", first.httpVersion)
        assertEquals(200, first.status)
        assertEquals("0", first.header("grpc-status", response = true))
        assertEquals(100L, first.startedAt)
        assertEquals(110L, first.requestEndAt)
        assertEquals(150L, first.responseStartAt)
        assertEquals(170L, first.endedAt)
        assertEquals(requestBody.size.toLong(), first.requestBodyBytes)
        assertEquals(11L, first.responseBodyBytes)
        assertTrue(first.requestBodyStored && first.responseBodyStored)
        assertEquals("1", first.parameters.single { it.name == "a" }.value)
        assertEquals("""{"ok":true}""", String(bodies["0-false"]!!))
        assertEquals(404, list[1].status)
        assertEquals("/b", list[1].path)
        assertEquals("""{"a":1}""", String(bodies["0-true"]!!))
        val flow = TrafficFlow(1, "TCP", "10.0.0.1", 1, "1.2.3.4", 443, "example.com", null, null, true, 0, 0, 0, 0, false, list)
        val curl = Curl.command(flow, first, requestBody)
        assertTrue(curl.contains("--http2"))
        assertTrue(curl.contains("'https://example.com/api?x=1'"))
        assertFalse(curl.contains("':"))
        val broken = Http2Assembler()
        broken.client("GET / HTTP/1.1\r\n\r\n".toByteArray() + ByteArray(20))
        assertTrue(broken.exchanges().isEmpty())
    }

    /** A WebSocket frame; client frames are masked. */
    private fun wsFrame(opcode: Int, payload: ByteArray, fin: Boolean = true, mask: Boolean = false, rsv1: Boolean = false): ByteArray {
        val head = java.io.ByteArrayOutputStream()
        head.write((if (fin) 0x80 else 0) or (if (rsv1) 0x40 else 0) or opcode)
        val maskBit = if (mask) 0x80 else 0
        when {
            payload.size < 126 -> head.write(maskBit or payload.size)
            else -> {
                head.write(maskBit or 126)
                head.write(payload.size shr 8)
                head.write(payload.size and 0xFF)
            }
        }
        if (!mask) return head.toByteArray() + payload
        val key = byteArrayOf(1, 2, 3, 4)
        return head.toByteArray() + key + ByteArray(payload.size) { (payload[it].toInt() xor key[it and 3].toInt()).toByte() }
    }

    private fun deflateMessage(deflater: java.util.zip.Deflater, text: String): ByteArray {
        deflater.setInput(text.toByteArray())
        val out = ByteArray(1024)
        val count = deflater.deflate(out, 0, out.size, java.util.zip.Deflater.SYNC_FLUSH)
        return out.copyOf(count - 4)
    }

    @Test
    fun webSocketMessagesFollowTheUpgrade() {
        val messages = HashMap<String, ByteArray>()
        var now = 0L
        val sink = object : BodySink {
            override fun keep(index: Int, request: Boolean, body: ByteArray) = true
            override fun keepMessage(index: Int, number: Int, body: ByteArray): Boolean {
                messages["$index-$number"] = body
                return true
            }
        }
        val assembler = HttpAssembler(sink = sink, clock = { now })
        assembler.client("GET /chat HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray())
        val hello = wsFrame(1, "hello".toByteArray(), mask = true)
        now = 5
        assembler.server("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n".toByteArray() + wsFrame(9, "p".toByteArray()))
        now = 10
        // A fragmented client message with a ping in between, sent in two pieces.
        val parts = hello + wsFrame(1, "par".toByteArray(), fin = false, mask = true) + wsFrame(10, ByteArray(0), mask = true) +
            wsFrame(0, "ts".toByteArray(), mask = true)
        assembler.client(parts.copyOf(9))
        assembler.client(parts.copyOfRange(9, parts.size))
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        assembler.server(wsFrame(1, deflateMessage(deflater, "first compressed"), rsv1 = true))
        assembler.server(wsFrame(1, deflateMessage(deflater, "first compressed again"), rsv1 = true))
        assembler.server(wsFrame(2, byteArrayOf(0, 1, 2)) + wsFrame(8, byteArrayOf(0x03, 0xE8.toByte()) + "bye".toByteArray()))
        val exchange = assembler.exchanges().single()
        assertEquals(101, exchange.status)
        val list = exchange.messages
        assertEquals(listOf("ping", "text", "pong", "text", "text", "text", "binary", "close"), list.map { it.kind })
        assertEquals(false, list[0].fromClient)
        assertEquals("hello", list[1].preview)
        assertTrue(list[1].fromClient)
        assertEquals("parts", list[3].preview)
        assertEquals(10L, list[3].at)
        assertEquals("first compressed", list[4].preview)
        assertTrue(list[4].compressed)
        assertEquals("first compressed again", list[5].preview)
        assertEquals("1000 bye", list[7].preview)
        assertTrue(messages["0-6"]!!.contentEquals(byteArrayOf(0, 1, 2)))
        assertEquals("parts", String(messages["0-3"]!!))
        val again = HttpExchange.fromJson(exchange.toJson())!!
        assertEquals(list, again.messages)
        val flow = TrafficFlow(1, "TCP", "10.0.0.1", 1, "1.2.3.4", 80, "h", null, null, false, 0, 0, 0, 0, false, listOf(exchange))
        val har = JSONObject(Har.build(listOf(flow), object : cattivole.spectra.core.BodySource {
            override fun body(flow: TrafficFlow, index: Int, request: Boolean): ByteArray? = null
            override fun message(flow: TrafficFlow, index: Int, number: Int) = messages["$index-$number"]
        }, "1"))
        val socket = har.getJSONObject("log").getJSONArray("entries").getJSONObject(0).getJSONArray("_webSocketMessages")
        assertEquals(8, socket.length())
        assertEquals("send", socket.getJSONObject(1).getString("type"))
        assertEquals("hello", socket.getJSONObject(1).getString("data"))
        assertEquals("AAEC", socket.getJSONObject(6).getString("data"))
    }

    @Test
    fun acceptEncodingStripStepsAsideAfterAnUpgrade() {
        val strip = AcceptEncodingStrip()
        val upgrade = "GET /chat HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\nAccept-Encoding: gzip\r\n\r\n"
        val frame = wsFrame(1, "hi".toByteArray(), mask = true)
        val out = strip.feed(upgrade.toByteArray() + frame).fold(ByteArray(0)) { a, b -> a + b }
        assertTrue(String(out, Charsets.ISO_8859_1).contains("Accept-Encoding: identity"))
        assertTrue(out.copyOfRange(out.size - frame.size, out.size).contentEquals(frame))
        val later = wsFrame(1, "more".toByteArray(), mask = true)
        assertTrue(strip.feed(later).single().contentEquals(later))
        val other = AcceptEncodingStrip()
        val binary = byteArrayOf(0x16, 3, 3, 0, 5)
        assertTrue(other.feed(binary).single().contentEquals(binary))
    }

    @Test
    fun clientHelloAlpnIsRead() {
        val name = "example.com".toByteArray()
        val sni = byteArrayOf(0, 0, 0, (5 + name.size).toByte(), 0, (3 + name.size).toByte(), 0, 0, name.size.toByte()) + name
        val protocols = byteArrayOf(2) + "h2".toByteArray() + byteArrayOf(8) + "http/1.1".toByteArray()
        val alpn = byteArrayOf(0, 16, 0, (protocols.size + 2).toByte(), 0, protocols.size.toByte()) + protocols
        val extensions = sni + alpn
        val head = ByteArray(43)
        head[0] = 3
        head[1] = 3
        head[36] = 2
        head[38] = 0x2f
        head[39] = 1
        head[41] = (extensions.size shr 8).toByte()
        head[42] = extensions.size.toByte()
        val body = head + extensions
        val handshake = byteArrayOf(1, 0, 0, body.size.toByte()) + body
        val hello = byteArrayOf(22, 3, 3, (handshake.size shr 8).toByte(), handshake.size.toByte()) + handshake
        assertEquals(listOf("h2", "http/1.1"), TrafficHints.tlsAlpn(hello))
        assertEquals("example.com", TrafficHints.tlsServerName(hello))
        assertTrue(TrafficHints.tlsAlpn(clientHello("example.com")).isEmpty())
    }

    @Test
    fun bridgeGivesThePhoneTheProtocolTheServerChose() {
        assertEquals("h2" to "h2", bridgeProtocols(phone = arrayOf("h2", "http/1.1"), origin = arrayOf("h2", "http/1.1"), offer = true))
        assertEquals("http/1.1" to "http/1.1", bridgeProtocols(phone = arrayOf("h2", "http/1.1"), origin = arrayOf("http/1.1"), offer = true))
        assertEquals("http/1.1" to "http/1.1", bridgeProtocols(phone = arrayOf("h2", "http/1.1"), origin = arrayOf("h2", "http/1.1"), offer = false))
    }

    /** Runs a full handshake through the bridge and passes the HTTP/2 preface both ways; returns what phone and origin agreed. */
    private fun bridgeProtocols(phone: Array<String>, origin: Array<String>, offer: Boolean): Pair<String, String> {
        val ca = LocalCa.open(Files.createTempDirectory("spectra-bridge").toFile(), LocalCa.PLAIN)
        val originCa = LocalCa.open(Files.createTempDirectory("spectra-origin").toFile(), LocalCa.PLAIN)
        val toOrigin = ArrayDeque<ByteArray>()
        val toPhone = ArrayDeque<ByteArray>()
        val fromPhone = java.io.ByteArrayOutputStream()
        var chosen: String? = null
        val bridge = TlsBridge(
            host = "example.com", port = 443,
            clientContext = ca.contextFor("example.com"),
            serverContext = LocalCa.trusting(originCa.certificate),
            onClientPlain = { fromPhone.write(it) }, onServerPlain = {}, onServerClosed = {},
            toClient = { toPhone.add(it) }, toServer = { toOrigin.add(it) },
            offerHttp2 = offer, onProtocol = { chosen = it },
        )
        val phoneEngine = LocalCa.trusting(ca.certificate).createSSLEngine("example.com", 443)
        phoneEngine.useClientMode = true
        phoneEngine.sslParameters = phoneEngine.sslParameters.apply { applicationProtocols = phone }
        phoneEngine.beginHandshake()
        val server = originCa.contextFor("example.com").createSSLEngine()
        server.useClientMode = false
        server.sslParameters = server.sslParameters.apply { applicationProtocols = origin }
        server.beginHandshake()
        val phoneInbox = ArrayDeque<ByteArray>()
        val serverInbox = ArrayDeque<ByteArray>()
        val phoneOut = ArrayDeque<ByteArray>()
        val serverOut = ArrayDeque<ByteArray>()
        var sent = false
        for (step in 0 until 48) {
            val plain = if (!sent && phoneEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) Http2Assembler.PREFACE else null
            tick(phoneEngine, phoneInbox, phoneOut, plain)
            if (plain != null) sent = true
            while (phoneOut.isNotEmpty()) bridge.fromClient(phoneOut.removeFirst())
            while (toOrigin.isNotEmpty()) serverInbox.add(toOrigin.removeFirst())
            tick(server, serverInbox, serverOut, null)
            while (serverOut.isNotEmpty()) bridge.fromServer(serverOut.removeFirst())
            while (toPhone.isNotEmpty()) phoneInbox.add(toPhone.removeFirst())
            if (fromPhone.size() >= Http2Assembler.PREFACE.size) break
        }
        assertTrue(fromPhone.toByteArray().contentEquals(Http2Assembler.PREFACE))
        assertEquals(phoneEngine.applicationProtocol, chosen)
        return phoneEngine.applicationProtocol to server.applicationProtocol
    }
}

/** An assembler that keeps every body it is handed, on a clock the test moves. */
private class Recorder {
    var now = 0L
    val bodies = HashMap<String, ByteArray>()
    private val assembler = HttpAssembler(sink = { index, request, body -> bodies["$index-$request"] = body; true }, clock = { now })

    fun client(data: ByteArray) = assembler.client(data)
    fun server(data: ByteArray) = assembler.server(data)
    fun serverClosed() = assembler.serverClosed()
    fun exchanges() = assembler.exchanges()

    /** The response body as the screen shows text: formatted JSON or plain text. */
    fun responseText(index: Int): String? = when (val view = bodies["$index-false"]?.let { BodyPreview.of(it, null) }) {
        is BodyView.Json -> view.text
        is BodyView.Text -> view.text
        else -> null
    }
}
