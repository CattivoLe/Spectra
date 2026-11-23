package cattivole.spectra.platform

import android.net.Network
import android.net.VpnService
import cattivole.spectra.core.BodySink
import cattivole.spectra.core.Http2Assembler
import cattivole.spectra.core.HttpAssembler
import cattivole.spectra.core.HttpStream
import cattivole.spectra.core.HttpExchange
import cattivole.spectra.core.IpCodec
import cattivole.spectra.core.LocalCa
import cattivole.spectra.core.MapLocal
import cattivole.spectra.core.MapRule
import cattivole.spectra.core.RefusedHosts
import cattivole.spectra.core.Throttle
import cattivole.spectra.core.ThrottleSettings
import cattivole.spectra.core.TlsBridge
import cattivole.spectra.core.TlsHostFilter
import cattivole.spectra.core.TrafficBodies
import cattivole.spectra.core.TrafficFlow
import cattivole.spectra.core.TrafficHints
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** Resolves the app that owns a socket. Null when the system will not say. */
fun interface AppNames {
    fun lookup(protocol: Int, src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): String?
}

/**
 * Forwards what the TUN delivers and keeps one row per connection.
 *
 * The phone speaks TCP and UDP to the tunnel. Each connection is replayed
 * through a socket that [VpnService.protect] keeps off the tunnel, and the
 * reply is written back as an IP packet. Payloads are not kept. A name is
 * recorded from DNS, from the TLS server name, or from cleartext HTTP.
 * When [decryptTls] is on, TLS to a named host is opened with [certificate]
 * and the cleartext is read the same way as HTTP.
 */
class TrafficProxy(
    private val vpn: VpnService,
    private val tunFd: java.io.FileDescriptor,
    private val network: Network?,
    private val apps: AppNames,
    private val decryptTls: java.util.concurrent.atomic.AtomicBoolean,
    private val certificate: () -> LocalCa?,
    private val onFlows: (List<TrafficFlow>) -> Unit,
    /** The proxy stopped on its own; the value names why. The service ends the recording. */
    private val onGiveUp: (StopReason) -> Unit = {},
    /** Apps keep refusing the certificate and none has accepted it: it is probably not trusted. */
    private val onRefusedCertificate: () -> Unit = {},
    /** Where finished HTTP bodies are written. Null keeps none. */
    private val bodies: TrafficBodies? = null,
    /** Which hosts are decrypted while [decryptTls] is on. */
    private val hostFilter: java.util.concurrent.atomic.AtomicReference<TlsHostFilter> =
        java.util.concurrent.atomic.AtomicReference(TlsHostFilter()),
    /** How fast connections may go; read again on every turn of the loop. */
    private val throttleSettings: java.util.concurrent.atomic.AtomicReference<ThrottleSettings> =
        java.util.concurrent.atomic.AtomicReference(ThrottleSettings()),
    /** Map Local rules, read for every new decrypted connection. */
    private val mapRules: java.util.concurrent.atomic.AtomicReference<List<MapRule>> =
        java.util.concurrent.atomic.AtomicReference(emptyList()),
) {
    enum class StopReason { STALLED, OVERLOADED }

    private val running = AtomicBoolean(true)
    private val selector = Selector.open()
    private val incoming = ArrayBlockingQueue<ByteArray>(512)
    private val output = FileOutputStream(tunFd)
    private val tcp = HashMap<String, TcpLink>()
    private val udp = HashMap<String, UdpLink>()
    private val shown = ArrayList<LiveFlow>()
    private val names = LinkedHashMap<String, String>()
    private val ipId = AtomicInteger(1)
    private var dirty = false
    private var lastPublish = 0L
    private var reader: Thread? = null
    private var loop: Thread? = null
    private var watchdog: Thread? = null

    /** When the loop last went round. A loop that stops turning takes every connection on the phone with it. */
    @Volatile private var lastBeat = System.currentTimeMillis()
    /** Since when the packet queue has been full, or 0. */
    @Volatile private var fullSince = 0L

    private val refusedHosts = RefusedHosts()
    private var refusals = 0
    private var acceptances = 0
    private var refusalNoticeSent = false

    /** The buckets for the settings in force; rebuilt when the settings change. */
    private var throttle: Throttle? = null
    private var throttleFor: ThrottleSettings? = null
    /** Links holding data back for the throttle; the loop turns quickly while there are any. */
    private val throttled = LinkedHashSet<TcpLink>()

    private fun refreshThrottle() {
        val settings = throttleSettings.get()
        if (settings === throttleFor) return
        throttleFor = settings
        throttle = if (settings.enabled) Throttle(settings) else null
    }

    /** Writes bodies off the loop: a slow disk must not hold up packets. */
    private val bodyWriter = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "spectra-bodies").apply { isDaemon = true } }

    private fun keepBody(flowId: Long, index: Int, request: Boolean, body: ByteArray): Boolean {
        val store = bodies ?: return false
        if (!store.reserve(body.size)) return false
        bodyWriter.execute { store.write(flowId, index, request, body) }
        return true
    }

    private fun keepMessage(flowId: Long, index: Int, number: Int, body: ByteArray): Boolean {
        val store = bodies ?: return false
        if (!store.reserve(body.size)) return false
        bodyWriter.execute { store.writeMessage(flowId, index, number, body) }
        return true
    }

    /** Where one connection's bodies and WebSocket messages go. */
    private fun sinkFor(flowId: Long) = object : BodySink {
        override fun keep(index: Int, request: Boolean, body: ByteArray) = keepBody(flowId, index, request, body)
        override fun keepMessage(index: Int, number: Int, body: ByteArray) = keepMessage(flowId, index, number, body)
    }

    fun start() {
        reader = Thread(this::readTun, "spectra-tun").also { it.start() }
        loop = Thread(this::pump, "spectra-nat").also { it.start() }
        watchdog = Thread(this::watch, "spectra-watch").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * Stops the recording when the loop has stalled or cannot keep up, so the phone is not left without a
     * network. Reads only volatile fields: waiting on the loop would stall with it.
     */
    private fun watch() {
        while (running.get()) {
            try {
                Thread.sleep(WATCH_EVERY)
            } catch (_: InterruptedException) {
                return
            }
            if (!running.get()) return
            val now = System.currentTimeMillis()
            val full = fullSince
            val reason = when {
                now - lastBeat > STALL_AFTER -> StopReason.STALLED
                full != 0L && now - full > OVERLOAD_AFTER -> StopReason.OVERLOADED
                else -> null
            } ?: continue
            android.util.Log.w("SpectraTraffic", "stopping: $reason, beat ${now - lastBeat} ms ago, links ${tcp.size + udp.size}")
            onGiveUp(reason)
            return
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            output.close()
        } catch (_: Exception) {
        }
        try {
            selector.wakeup()
        } catch (_: Exception) {
        }
        reader?.join(400)
        loop?.join(400)
        bodyWriter.shutdown()
        try {
            selector.close()
        } catch (_: Exception) {
        }
    }

    private fun readTun() {
        val input = FileInputStream(tunFd)
        val buffer = ByteArray(65535)
        while (running.get()) {
            val size = try {
                input.read(buffer)
            } catch (_: IOException) {
                break
            }
            if (size <= 0) break
            if (incoming.offer(buffer.copyOf(size))) {
                fullSince = 0L
                selector.wakeup()
            } else if (fullSince == 0L) {
                fullSince = System.currentTimeMillis()
            }
        }
        running.set(false)
        selector.wakeup()
    }

    private fun pump() {
        while (running.get()) {
            try {
                lastBeat = System.currentTimeMillis()
                refreshThrottle()
                selector.select(if (throttled.isEmpty()) 200 else THROTTLE_TICK)
                while (true) {
                    val packet = incoming.poll() ?: break
                    try {
                        handle(packet)
                    } catch (_: Exception) {
                    }
                }
                val keys = selector.selectedKeys()
                val iterator = keys.iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()
                    if (!key.isValid) continue
                    try {
                        when (val link = key.attachment()) {
                            is TcpLink -> {
                                if (key.isConnectable) link.finishConnect()
                                if (key.isValid && key.isWritable) link.flushRemote()
                                if (key.isValid && key.isReadable) link.readRemote()
                            }
                            is UdpLink -> if (key.isReadable) link.readRemote()
                        }
                    } catch (_: Exception) {
                        when (val link = key.attachment()) {
                            is TcpLink -> link.fail()
                            is UdpLink -> link.close()
                        }
                    }
                }
                if (throttled.isNotEmpty()) {
                    val waiting = throttled.toList()
                    throttled.clear()
                    waiting.forEach { link -> runCatching { link.resume() }.onFailure { link.fail() } }
                }
                val now = System.currentTimeMillis()
                tcp.values.toList().forEach { it.sweep(now) }
                udp.values.toList().forEach { it.sweep(now) }
                publish(now, force = false)
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
        tcp.values.toList().forEach { it.close() }
        udp.values.toList().forEach { it.close() }
        publish(System.currentTimeMillis(), force = true)
    }

    private fun handle(raw: ByteArray) {
        val ip = IpCodec.parse(raw) ?: return
        if (!IpCodec.unicast(ip.dst) || ip.dst.contentEquals(VPN4) || ip.dst.contentEquals(VPN6)) return
        when (ip.protocol) {
            IpCodec.TCP -> {
                val tcpHeader = IpCodec.parseTcp(ip.payload) ?: return
                val key = key("TCP", ip.src, tcpHeader.srcPort, ip.dst, tcpHeader.dstPort)
                val link = tcp[key]
                if (link == null) {
                    if (tcpHeader.flags and IpCodec.SYN != 0 && tcpHeader.flags and IpCodec.ACK == 0) openTcp(ip, tcpHeader, key)
                    else reject(ip, tcpHeader)
                } else {
                    link.onPacket(tcpHeader)
                }
            }
            IpCodec.UDP -> {
                val header = IpCodec.parseUdp(ip.payload) ?: return
                // QUIC hides HTTP from the TLS handshake. Dropping it makes the app try TCP, which can be read.
                // A host the filter leaves encrypted keeps its QUIC; one whose name is not known yet loses it.
                val name = names[IpCodec.text(ip.dst)]
                if (decryptTls.get() && header.dstPort == 443 && (name == null || hostFilter.get().allows(name))) {
                    udp.remove(key("UDP", ip.src, header.srcPort, ip.dst, header.dstPort))?.close()
                    return
                }
                val key = key("UDP", ip.src, header.srcPort, ip.dst, header.dstPort)
                val link = udp[key] ?: openUdp(ip, header, key) ?: return
                link.onPacket(header.payload)
            }
        }
    }

    /**
     * Frees a slot when the table is full by closing the link that has been quiet longest, so a new
     * connection is never refused: refusing every SYN would leave the phone offline until the recording stops.
     */
    private fun makeRoom() {
        while (tcp.size + udp.size >= MAX_LINKS) {
            val oldestUdp = udp.values.minByOrNull { it.flow.lastAt }
            val oldestTcp = tcp.values.minByOrNull { it.flow.lastAt }
            when {
                oldestUdp != null && (oldestTcp == null || oldestUdp.flow.lastAt <= oldestTcp.flow.lastAt) -> oldestUdp.close()
                oldestTcp != null -> oldestTcp.fail()
                else -> return
            }
        }
    }

    private fun openTcp(ip: IpCodec.Packet, header: IpCodec.Tcp, key: String) {
        makeRoom()
        val channel = try {
            SocketChannel.open().apply { configureBlocking(false) }
        } catch (_: IOException) {
            reject(ip, header)
            return
        }
        try {
            channel.socket().tcpNoDelay = true
            if (!vpn.protect(channel.socket())) throw IOException("protect")
            try {
                network?.bindSocket(channel.socket())
            } catch (_: Exception) {
            }
            channel.connect(InetSocketAddress(InetAddress.getByAddress(ip.dst), header.dstPort))
        } catch (_: Exception) {
            runCatching { channel.close() }
            reject(ip, header)
            return
        }
        val flow = track(ip, "TCP", header.srcPort, header.dstPort)
        val link = TcpLink(key, flow, ip.src, ip.dst, header.srcPort, header.dstPort, ip.version == 6, channel)
        tcp[key] = link
        link.keySel = channel.register(selector, SelectionKey.OP_CONNECT, link)
        link.onPacket(header)
    }

    private fun openUdp(ip: IpCodec.Packet, header: IpCodec.Udp, key: String): UdpLink? {
        makeRoom()
        val channel = try {
            DatagramChannel.open().apply { configureBlocking(false) }
        } catch (_: IOException) {
            return null
        }
        try {
            if (!vpn.protect(channel.socket())) throw IOException("protect")
            try {
                network?.bindSocket(channel.socket())
            } catch (_: Exception) {
            }
            channel.connect(InetSocketAddress(InetAddress.getByAddress(ip.dst), header.dstPort))
        } catch (_: Exception) {
            runCatching { channel.close() }
            return null
        }
        val flow = track(ip, "UDP", header.srcPort, header.dstPort)
        val link = UdpLink(key, flow, ip.src, ip.dst, header.srcPort, header.dstPort, channel)
        udp[key] = link
        channel.register(selector, SelectionKey.OP_READ, link)
        return link
    }

    private fun track(ip: IpCodec.Packet, protocol: String, srcPort: Int, dstPort: Int): LiveFlow {
        val destination = IpCodec.text(ip.dst)
        val flow = LiveFlow(
            id = NEXT_ID.incrementAndGet().toLong(),
            protocol = protocol,
            source = IpCodec.text(ip.src),
            sourcePort = srcPort,
            destination = destination,
            destinationPort = dstPort,
            host = names[destination],
            startedAt = System.currentTimeMillis(),
        )
        flow.app = apps.lookup(ip.protocol, ip.src, srcPort, ip.dst, dstPort)
        shown += flow
        evict()
        dirty = true
        return flow
    }

    private fun evict() {
        if (shown.size <= MAX_SHOWN) return
        val iterator = shown.iterator()
        var extra = shown.size - MAX_SHOWN
        while (iterator.hasNext() && extra > 0) {
            if (iterator.next().closed) {
                iterator.remove()
                extra--
            }
        }
    }

    private fun reject(ip: IpCodec.Packet, tcp: IpCodec.Tcp) {
        val ack = tcp.seq + tcp.payload.size +
            (if (tcp.flags and IpCodec.SYN != 0) 1 else 0) +
            (if (tcp.flags and IpCodec.FIN != 0) 1 else 0)
        write(
            IpCodec.tcpPacket(
                src = ip.dst,
                dst = ip.src,
                srcPort = tcp.dstPort,
                dstPort = tcp.srcPort,
                seq = tcp.ack,
                ack = ack,
                flags = IpCodec.RST or IpCodec.ACK,
                window = 0,
                payload = ByteArray(0),
                id = nextId(),
            ),
        )
    }

    private fun remember(ip: String, host: String) {
        names[ip] = host
        if (names.size > 512) {
            val iterator = names.keys.iterator()
            repeat(64) { if (iterator.hasNext()) iterator.next().also { iterator.remove() } }
        }
        for (flow in shown) {
            if (flow.destination == ip && flow.host == null) flow.host = host
        }
    }

    private fun write(packet: ByteArray) {
        try {
            output.write(packet)
        } catch (_: IOException) {
            running.set(false)
        }
    }

    private fun nextId(): Int = ipId.getAndIncrement() and 0xFFFF

    private fun publish(now: Long, force: Boolean) {
        if (!force && (!dirty || now - lastPublish < 200)) return
        dirty = false
        lastPublish = now
        val rows = shown.map { it.snapshot() }
            .sortedWith(compareBy<TrafficFlow> { it.closed }.thenByDescending { it.lastAt })
            .take(MAX_SHOWN)
        onFlows(rows)
    }

    private fun key(protocol: String, src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): String =
        "$protocol ${IpCodec.text(src)}:$srcPort>${IpCodec.text(dst)}:$dstPort"

    private class LiveFlow(
        val id: Long,
        val protocol: String,
        val source: String,
        val sourcePort: Int,
        val destination: String,
        val destinationPort: Int,
        var host: String?,
        val startedAt: Long,
    ) {
        var request: String? = null
        var app: String? = null
        var tls: Boolean = false
        var sent: Long = 0
        var received: Long = 0
        var lastAt: Long = startedAt
        var closed: Boolean = false
        var exchanges: List<HttpExchange> = emptyList()
        var refused: Boolean = false
        var lookups: Int = if (app == null) 0 else 2

        fun snapshot(): TrafficFlow = TrafficFlow(
            id, protocol, source, sourcePort, destination, destinationPort,
            host, request, app, tls, sent, received, startedAt, lastAt, closed, exchanges, refused,
        )
    }

    private enum class Inspect { UNKNOWN, TLS, HTTP, OTHER }

    private inner class TcpLink(
        val key: String,
        val flow: LiveFlow,
        val src: ByteArray,
        val dst: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val v6: Boolean,
        val channel: SocketChannel,
    ) {
        var keySel: SelectionKey? = null

        private val isn = (System.nanoTime() and 0xFFFFFFFFL)
        private var ourSeq = isn
        private var theirNext = 0L
        private var theirAcked = isn
        private var theirScale = 0
        private var ourScale = 0
        private var clientWindow = 65535
        private var synSeen = false
        private var synSent = false
        private var synAcked = false
        private var clientFin = false
        private var finSent = false
        private var remoteEof = false
        private val outbound = ArrayDeque<ByteBuffer>()
        private val unacked = ArrayDeque<Chunk>()
        private var unackedBytes = 0
        private val inbound = ArrayDeque<ByteArray>()
        /** When each [inbound] chunk may go to the phone: now, or later by the throttle's delay. */
        private val inboundReady = ArrayDeque<Long>()
        private var inboundBytes = 0
        private val prelude = java.io.ByteArrayOutputStream()
        /** HTTP/1.1 until a decrypted connection turns out to speak HTTP/2. */
        private var http: HttpStream = HttpAssembler(sink = sinkFor(flow.id))
        private var held: ByteArray? = null
        private var mitm: TlsBridge? = null
        private var inspect = Inspect.UNKNOWN
        private var lastTransmit = 0L
        private var lastAck = System.currentTimeMillis()

        fun onPacket(tcp: IpCodec.Tcp) {
            flow.lastAt = System.currentTimeMillis()
            dirty = true
            if (tcp.flags and IpCodec.RST != 0) {
                close()
                return
            }
            if (tcp.flags and IpCodec.SYN != 0 && !synAcked) {
                handshake(tcp)
                lookupApp()
                return
            }
            if (tcp.flags and IpCodec.ACK != 0) acceptAck(tcp.ack)
            // The scale from the handshake applies only after the SYN is acknowledged.
            clientWindow = tcp.window shl (if (synAcked) theirScale else 0)
            acceptData(tcp)
            pumpToClient()
            lookupApp()
            maybeFinish()
        }

        fun finishConnect() {
            try {
                if (!channel.finishConnect()) return
                interest(connect = false, read = true, write = outbound.isNotEmpty())
                answerSyn()
                flushRemote()
            } catch (_: IOException) {
                fail()
            }
        }

        fun flushRemote() {
            if (!channel.isConnected) return
            val now = System.currentTimeMillis()
            val limit = throttle
            try {
                while (outbound.isNotEmpty()) {
                    val buffer = outbound.first()
                    val allowed = limit?.up?.take(buffer.remaining(), now) ?: buffer.remaining()
                    if (allowed <= 0) {
                        throttled += this
                        interest(write = false)
                        return
                    }
                    val end = buffer.limit()
                    buffer.limit(buffer.position() + allowed)
                    channel.write(buffer)
                    val socketFull = buffer.hasRemaining()
                    buffer.limit(end)
                    if (socketFull) {
                        interest(write = true)
                        return
                    }
                    if (buffer.hasRemaining()) {
                        throttled += this
                        interest(write = false)
                        return
                    }
                    outbound.removeFirst()
                }
            } catch (_: IOException) {
                fail()
                return
            }
            interest(write = false)
            if (clientFin) runCatching { channel.shutdownOutput() }
            sendAck()
        }

        fun readRemote() {
            if (inboundBytes >= MAX_IN) {
                interest(read = false)
                return
            }
            val buffer = ByteBuffer.allocate(mss())
            val size = try {
                channel.read(buffer)
            } catch (_: IOException) {
                fail()
                return
            }
            if (size < 0) {
                remoteEof = true
                val bridge = mitm
                if (bridge != null) runCatching { bridge.serverClosed() } else noteServerClosed()
                interest(read = false)
                pumpToClient()
                return
            }
            if (size == 0) return
            val bytes = buffer.array().copyOf(size)
            val bridge = mitm
            if (bridge != null) {
                flow.received += size
                flow.lastAt = System.currentTimeMillis()
                dirty = true
                try {
                    bridge.fromServer(bytes)
                } catch (_: Exception) {
                    fail()
                }
                return
            }
            noteServer(bytes)
            queueInbound(bytes)
            flow.received += size
            flow.lastAt = System.currentTimeMillis()
            dirty = true
            pumpToClient()
        }

        fun sweep(now: Long) {
            val idle = if (synAcked) TCP_IDLE else 15_000L
            if (now - flow.lastAt > idle) {
                fail()
                return
            }
            if (!synAcked && synSent && now - lastTransmit > 1000) {
                sendSynAck(retransmit = true)
                return
            }
            if (unacked.isNotEmpty() && now - lastTransmit > 1000 && now - lastAck > 800) {
                val chunk = unacked.first()
                emit(chunk.seq, IpCodec.ACK or IpCodec.PSH, chunk.data, advance = false)
            }
        }

        fun fail() {
            if (!flow.closed) {
                emit(ourSeq, IpCodec.RST or IpCodec.ACK, ByteArray(0), advance = false)
            }
            close()
        }

        fun close() {
            if (flow.closed) return
            flow.closed = true
            flow.lastAt = System.currentTimeMillis()
            dirty = true
            tcp.remove(key)
            runCatching { keySel?.cancel() }
            runCatching { channel.close() }
            mitm = null
        }

        private fun handshake(tcp: IpCodec.Tcp) {
            if (!synSeen) {
                synSeen = true
                theirNext = tcp.seq + 1
                theirScale = (tcp.windowScale ?: 0).coerceIn(0, 14)
                ourScale = if (theirScale > 0) 7 else 0
                clientWindow = tcp.window
            }
            answerSyn()
        }

        /**
         * Accepts the app's connection once the server has accepted ours, and not before. An app that gets no answer
         * over IPv6 goes on to IPv4 by itself; answered at once, it stayed with an address that led nowhere.
         */
        private fun answerSyn() {
            if (synSeen && channel.isConnected) sendSynAck(retransmit = synSent)
        }

        private fun sendSynAck(retransmit: Boolean) {
            val scale = if (ourScale > 0) ourScale else null
            emit(isn, IpCodec.SYN or IpCodec.ACK, ByteArray(0), advance = !retransmit && !synSent, mss = mss(), windowScale = scale, syn = true)
            synSent = true
        }

        private fun acceptAck(ack: Long) {
            if (!seqBefore(theirAcked, ack) || seqBefore(ourSeq, ack)) return
            while (unacked.isNotEmpty()) {
                val chunk = unacked.first()
                val end = chunk.seq + chunk.data.size
                if (seqBefore(ack, end)) break
                unacked.removeFirst()
                unackedBytes -= chunk.data.size
            }
            theirAcked = ack
            lastAck = System.currentTimeMillis()
            if (!seqBefore(ack, isn + 1)) synAcked = true
        }

        private fun acceptData(tcp: IpCodec.Tcp) {
            if (flow.closed) return
            val payload = tcp.payload
            if (payload.isNotEmpty()) {
                val diff = (tcp.seq - theirNext).toInt()
                val slice = when {
                    diff == 0 -> payload
                    diff < 0 && -diff < payload.size -> payload.copyOfRange(-diff, payload.size)
                    else -> ByteArray(0)
                }
                if (slice.isNotEmpty()) take(slice)
            }
            if (tcp.flags and IpCodec.FIN != 0 && !clientFin) {
                val finAt = tcp.seq + payload.size
                if ((finAt - theirNext).toInt() == 0) {
                    clientFin = true
                    theirNext += 1
                    val bridge = mitm
                    if (bridge != null) runCatching { bridge.clientClosed() }
                    else if (outbound.isEmpty() && channel.isConnected) runCatching { channel.shutdownOutput() }
                }
            }
            if (payload.isNotEmpty() || tcp.flags and IpCodec.FIN != 0) sendAck()
        }

        private fun take(data: ByteArray) {
            val room = MAX_OUT - outboundBytes()
            if (room <= 0) return
            val size = min(data.size, room)
            val piece = if (size == data.size) data else data.copyOf(size)
            theirNext += size
            flow.sent += size
            if (route(piece)) return
            outbound.add(ByteBuffer.wrap(piece))
            note(piece)
            if (channel.isConnected) flushRemote()
        }

        /** True when [data] is kept off the raw socket, for a TLS handshake we open ourselves. */
        private fun route(data: ByteArray): Boolean {
            mitm?.let { bridge ->
                try {
                    bridge.fromClient(data)
                } catch (_: Exception) {
                    if (flow.exchanges.isEmpty() && !clientTrusted && !bridge.serverFailed) refused()
                    fail()
                }
                return true
            }
            val buffering = held != null
            val tls = data.isNotEmpty() && (data[0].toInt() and 0xFF) == 22
            if (!buffering && !(decryptTls.get() && inspect == Inspect.UNKNOWN && tls)) return false
            val buf = (held ?: ByteArray(0)) + data
            held = buf
            if (!TrafficHints.tlsRecordComplete(buf)) {
                if (buf.size > 18_000) passthroughHeld()
                return true
            }
            val sni = TrafficHints.tlsServerName(buf)
            if ((sni != null && refusedHosts.contains(flow.app, sni)) || !hostFilter.get().allows(sni?.takeIf { it.isNotEmpty() })) {
                // This app refused our certificate for this host before, or the user left it encrypted: read
                // nothing, but let it work.
                if (!sni.isNullOrEmpty()) flow.host = sni
                flow.tls = true
                inspect = Inspect.OTHER
                passthroughHeld()
                return true
            }
            if (!startMitm(buf)) passthroughHeld()
            return true
        }

        /** The app on the phone refused our certificate for this host. */
        private fun refused() {
            flow.refused = true
            flow.host?.takeIf { it.isNotEmpty() }?.let { refusedHosts.add(flow.app, it) }
            refusals++
            if (!refusalNoticeSent && acceptances == 0 && refusals >= REFUSALS_BEFORE_NOTICE) {
                refusalNoticeSent = true
                onRefusedCertificate()
            }
        }

        /** True once the phone's side of the handshake went through: the app trusted our certificate. */
        private var clientTrusted = false

        private fun startMitm(hello: ByteArray): Boolean {
            val sni = TrafficHints.tlsServerName(hello)
            if (!sni.isNullOrEmpty()) flow.host = sni
            flow.tls = true
            inspect = Inspect.TLS
            val ca = certificate() ?: return false
            val host = sni?.takeIf { it.isNotEmpty() } ?: IpCodec.text(dst)
            // A host with Map Local rules stays on HTTP/1.1, where single requests can be answered here.
            val mapLocal = MapLocal(mapRules.get())
            val offerHttp2 = TrafficHints.tlsAlpn(hello).contains(TlsBridge.H2) && !mapLocal.coversHost(sni)
            val bridge = try {
                TlsBridge(
                    host = host,
                    port = dstPort,
                    clientContext = ca.contextFor(host),
                    serverContext = LocalCa.trustDefault(),
                    onClientPlain = { plain ->
                        if (!clientTrusted) {
                            clientTrusted = true
                            acceptances++
                        }
                        http.client(plain)
                        applyHttp()
                    },
                    onServerPlain = { plain ->
                        http.server(plain)
                        applyHttp()
                    },
                    onServerClosed = {
                        http.serverClosed()
                        applyHttp()
                    },
                    toClient = ::toClient,
                    toServer = ::toServer,
                    offerHttp2 = offerHttp2,
                    mapLocal = mapLocal.takeUnless { it.isEmpty },
                    onProtocol = { chosen -> if (chosen == TlsBridge.H2) http = Http2Assembler(sink = sinkFor(flow.id)) },
                )
            } catch (_: Exception) {
                return false
            }
            mitm = bridge
            held = null
            try {
                bridge.fromClient(hello)
            } catch (_: Exception) {
                mitm = null
                if (!bridge.serverFailed) refused()
                fail()
            }
            return true
        }

        private fun passthroughHeld() {
            val buf = held ?: return
            held = null
            outbound.add(ByteBuffer.wrap(buf))
            note(buf)
            if (channel.isConnected) flushRemote()
        }

        private fun toClient(bytes: ByteArray) {
            queueInbound(bytes)
            pumpToClient()
        }

        private fun queueInbound(bytes: ByteArray) {
            inbound.add(bytes)
            inboundReady.add(System.currentTimeMillis() + (throttle?.latencyMs ?: 0))
            inboundBytes += bytes.size
        }

        /** The throttle let more through: carry on in both directions. */
        fun resume() {
            if (flow.closed) return
            flushRemote()
            pumpToClient()
        }

        private fun toServer(bytes: ByteArray) {
            outbound.add(ByteBuffer.wrap(bytes))
            if (channel.isConnected) flushRemote()
        }

        private fun pumpToClient() {
            if (flow.closed) return
            var room = clientWindow - unackedBytes
            val now = System.currentTimeMillis()
            val limit = throttle
            while (room > 0 && inbound.isNotEmpty()) {
                if (inboundReady.first() > now) {
                    throttled += this
                    break
                }
                val chunk = inbound.first()
                val wanted = min(chunk.size, min(room, mss()))
                val size = limit?.down?.take(wanted, now) ?: wanted
                if (size <= 0) {
                    throttled += this
                    break
                }
                val piece = if (size == chunk.size) chunk else chunk.copyOf(size)
                inbound.removeFirst()
                val ready = inboundReady.removeFirst()
                if (size != chunk.size) {
                    inbound.addFirst(chunk.copyOfRange(size, chunk.size))
                    inboundReady.addFirst(ready)
                }
                inboundBytes -= size
                val seq = ourSeq
                emit(seq, IpCodec.ACK or IpCodec.PSH, piece, advance = true)
                unacked.add(Chunk(seq, piece))
                unackedBytes += size
                room -= size
            }
            if (inbound.isEmpty() && remoteEof && !finSent) {
                emit(ourSeq, IpCodec.FIN or IpCodec.ACK, ByteArray(0), advance = true)
                finSent = true
            }
            // OP_READ is illegal until the socket has left OP_CONNECT.
            if (channel.isConnected) interest(read = !remoteEof && inboundBytes < MAX_IN)
            maybeFinish()
        }

        private fun maybeFinish() {
            if (finSent && clientFin && unacked.isEmpty() && (theirAcked - ourSeq).toInt() == 0) close()
        }

        private fun sendAck() {
            emit(ourSeq, IpCodec.ACK, ByteArray(0), advance = false)
        }

        private fun note(data: ByteArray) {
            when (inspect) {
                Inspect.OTHER -> return
                Inspect.TLS -> captureSni(data)
                Inspect.HTTP -> {
                    http.client(data)
                    applyHttp()
                }
                Inspect.UNKNOWN -> {
                    if (prelude.size() >= 32_768) {
                        inspect = Inspect.OTHER
                        return
                    }
                    prelude.write(data)
                    val buf = prelude.toByteArray()
                    if (buf.isNotEmpty() && (buf[0].toInt() and 0xFF) == 22) {
                        inspect = Inspect.TLS
                        captureSni(buf)
                        return
                    }
                    val httpReady = TrafficHints.httpRequest(buf) != null
                    if (TrafficHints.awaitsHttp(buf) || httpReady) {
                        if (!httpReady) return
                        inspect = Inspect.HTTP
                        http.client(buf)
                        applyHttp()
                        return
                    }
                    if (buf.size >= 8) inspect = Inspect.OTHER
                }
            }
        }

        private fun noteServer(data: ByteArray) {
            if (inspect != Inspect.HTTP) return
            http.server(data)
            applyHttp()
        }

        private fun noteServerClosed() {
            if (inspect != Inspect.HTTP) return
            http.serverClosed()
            applyHttp()
        }

        private fun applyHttp() {
            val list = http.exchanges()
            if (list.isEmpty()) return
            flow.exchanges = list
            val first = list.first()
            flow.request = first.summary
            if (flow.host.isNullOrEmpty()) {
                first.requestHeaders.firstOrNull { it.name.equals("Host", true) }
                    ?.value
                    ?.substringBefore(':')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { flow.host = it }
            }
        }

        private fun captureSni(data: ByteArray) {
            if (flow.tls && flow.host != null) return
            if (prelude.size() < 4096) {
                val already = prelude.toByteArray()
                if (!already.contentEquals(data) && data.isNotEmpty() && already.isNotEmpty() && !data.contentEquals(already)) {
                    val room = 4096 - prelude.size()
                    if (room > 0) prelude.write(data, 0, min(data.size, room))
                }
            }
            val buf = prelude.toByteArray()
            val name = TrafficHints.tlsServerName(buf)
            if (name != null) {
                flow.host = name
                flow.tls = true
            } else if (TrafficHints.tlsRecordComplete(buf)) {
                flow.tls = true
            }
        }

        private fun lookupApp() {
            if (flow.app != null || flow.lookups >= 2) return
            flow.lookups++
            flow.app = apps.lookup(IpCodec.TCP, src, srcPort, dst, dstPort)
        }

        private fun emit(
            seq: Long,
            flags: Int,
            payload: ByteArray,
            advance: Boolean,
            mss: Int? = null,
            windowScale: Int? = null,
            syn: Boolean = false,
        ) {
            write(
                IpCodec.tcpPacket(
                    src = dst,
                    dst = src,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seq = seq,
                    ack = theirNext,
                    flags = flags,
                    window = if (syn) 65535 else windowField(),
                    payload = payload,
                    id = nextId(),
                    mss = mss,
                    windowScale = windowScale,
                ),
            )
            if (advance) {
                ourSeq += payload.size
                if (flags and IpCodec.SYN != 0) ourSeq += 1
                if (flags and IpCodec.FIN != 0) ourSeq += 1
            }
            lastTransmit = System.currentTimeMillis()
        }

        private fun windowField(): Int {
            val room = (MAX_OUT - outboundBytes()).coerceAtLeast(0)
            if (ourScale == 0) return room.coerceAtMost(65535)
            return (room shr ourScale).coerceIn(0, 65535)
        }

        private fun mss(): Int = if (v6) 1200 else 1360

        private fun outboundBytes(): Int = outbound.sumOf { it.remaining() }

        private fun interest(read: Boolean? = null, write: Boolean? = null, connect: Boolean? = null) {
            val selected = keySel ?: return
            if (!selected.isValid) return
            var ops = selected.interestOps()
            if (read != null) ops = if (read) ops or SelectionKey.OP_READ else ops and SelectionKey.OP_READ.inv()
            if (write != null) ops = if (write) ops or SelectionKey.OP_WRITE else ops and SelectionKey.OP_WRITE.inv()
            if (connect != null) ops = if (connect) ops or SelectionKey.OP_CONNECT else ops and SelectionKey.OP_CONNECT.inv()
            runCatching { selected.interestOps(ops) }
        }
    }

    private inner class UdpLink(
        val key: String,
        val flow: LiveFlow,
        val src: ByteArray,
        val dst: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val channel: DatagramChannel,
    ) {
        fun onPacket(payload: ByteArray) {
            flow.sent += payload.size
            flow.lastAt = System.currentTimeMillis()
            dirty = true
            if (dstPort == 53) {
                TrafficHints.dnsQuestion(payload)?.let { if (flow.host == null) flow.host = it }
            }
            if (flow.app == null && flow.lookups < 2) {
                flow.lookups++
                flow.app = apps.lookup(IpCodec.UDP, src, srcPort, dst, dstPort)
            }
            try {
                channel.write(ByteBuffer.wrap(payload))
            } catch (_: IOException) {
                close()
            }
        }

        fun readRemote() {
            val buffer = ByteBuffer.allocate(8192)
            val size = try {
                channel.read(buffer)
            } catch (_: IOException) {
                close()
                return
            }
            if (size <= 0) return
            val payload = ByteArray(size)
            buffer.flip()
            buffer.get(payload)
            flow.received += size
            flow.lastAt = System.currentTimeMillis()
            dirty = true
            if (dstPort == 53) {
                for ((host, ip) in TrafficHints.dnsAnswers(payload)) remember(ip, host)
            }
            write(
                IpCodec.udpPacket(
                    src = dst,
                    dst = src,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    payload = payload,
                    id = nextId(),
                ),
            )
        }

        fun sweep(now: Long) {
            // Every DNS query comes from a new port: kept for a minute, they alone would fill the table.
            val idle = if (dstPort == 53) DNS_IDLE else UDP_IDLE
            if (now - flow.lastAt > idle) close()
        }

        fun close() {
            if (flow.closed) return
            flow.closed = true
            flow.lastAt = System.currentTimeMillis()
            dirty = true
            udp.remove(key)
            runCatching { channel.close() }
        }
    }

    private class Chunk(val seq: Long, val data: ByteArray)

    companion object {
        val VPN4: ByteArray = InetAddress.getByName("10.111.222.1").address
        val VPN6: ByteArray = InetAddress.getByName("fd00:111:222::1").address
        private val NEXT_ID = AtomicInteger()
        private const val MAX_LINKS = 400
        private const val MAX_SHOWN = 400
        private const val MAX_OUT = 512 * 1024
        private const val MAX_IN = 256 * 1024
        private const val TCP_IDLE = 300_000L
        private const val UDP_IDLE = 60_000L
        private const val DNS_IDLE = 10_000L
        private const val WATCH_EVERY = 2_000L
        /** How often the loop turns while the throttle holds data back. */
        private const val THROTTLE_TICK = 10L
        private const val STALL_AFTER = 10_000L
        private const val OVERLOAD_AFTER = 10_000L
        private const val REFUSALS_BEFORE_NOTICE = 10
    }
}

/** [a] is earlier than [b] in TCP sequence space. */
private fun seqBefore(a: Long, b: Long): Boolean = (a - b).toInt() < 0
