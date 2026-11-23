package cattivole.spectra.core

import java.nio.ByteBuffer
import java.util.ArrayDeque
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * Speaks TLS to the phone with our certificate and TLS to the server with the
 * system trust store. Bytes between the two handshakes are the cleartext HTTP.
 *
 * The server's handshake runs first: the phone is answered only once the server
 * has chosen its protocol (ALPN), and is offered exactly that one, so both legs
 * speak the same HTTP version. HTTP/2 is offered to the server only when the
 * phone asked for it ([offerHttp2]) and this phone's TLS can pass it on.
 * [onProtocol] says what was chosen, before any cleartext flows.
 */
class TlsBridge(
    host: String,
    port: Int,
    clientContext: SSLContext,
    serverContext: SSLContext,
    private val onClientPlain: (ByteArray) -> Unit,
    private val onServerPlain: (ByteArray) -> Unit,
    private val onServerClosed: () -> Unit,
    private val toClient: (ByteArray) -> Unit,
    private val toServer: (ByteArray) -> Unit,
    offerHttp2: Boolean = false,
    private val onProtocol: (String) -> Unit = {},
    /** Requests these rules match are answered here and never reach the server (HTTP/1.1 only). */
    mapLocal: MapLocal? = null,
) {
    private val clientEngine = clientContext.createSSLEngine().also { engine ->
        engine.useClientMode = false
    }
    /** Server-side ALPN needs Android 10; without it the phone could not be told "h2", so the server is not offered it. */
    private val http2 = offerHttp2 && protocols(clientEngine, arrayOf(H2, HTTP1), identify = false)
    private val serverEngine = serverContext.createSSLEngine(host, port).also { engine ->
        engine.useClientMode = true
        protocols(engine, if (http2) arrayOf(H2, HTTP1) else arrayOf(HTTP1), identify = true)
    }

    /** The protocol both legs speak, once the server's handshake is done. */
    var protocol: String? = null
        private set

    /** The server's side failed (its certificate, its TLS), not the phone's. */
    var serverFailed = false
        private set
    private val fromClient = PacketBuf()
    private val fromServer = PacketBuf()
    private val requests = AcceptEncodingStrip(host, mapLocal, onLocal = { answer ->
        toClientApp.add(ByteBuffer.wrap(answer))
        onServerPlain(answer)
    })
    private val toServerApp = ArrayDeque<ByteBuffer>()
    private val toClientApp = ArrayDeque<ByteBuffer>()
    private var dead = false

    init {
        serverEngine.beginHandshake()
    }

    /** The server's handshake is done: answer the phone with the same protocol. */
    private fun startClient() {
        val chosen = runCatching { serverEngine.applicationProtocol }.getOrNull()?.takeIf { it.isNotEmpty() } ?: HTTP1
        protocols(clientEngine, arrayOf(chosen), identify = false)
        protocol = chosen
        clientEngine.beginHandshake()
        onProtocol(chosen)
    }

    fun fromClient(data: ByteArray) {
        if (dead || data.isEmpty()) return
        fromClient.append(data)
        spin()
    }

    fun fromServer(data: ByteArray) {
        if (dead || data.isEmpty()) return
        fromServer.append(data)
        spin()
    }

    /** The phone closed. Tell the server, in the clear, that the request is finished. */
    fun clientClosed() {
        if (dead) return
        try {
            if (protocol == HTTP1) requests.finish().forEach { toServerApp.add(ByteBuffer.wrap(it)) }
            serverEngine.closeOutbound()
            spin()
        } catch (_: SSLException) {
            dead = true
        }
    }

    /** The server closed. Tell the phone, then let the caller finish the TCP close. */
    fun serverClosed() {
        if (dead) return
        try {
            if (protocol != null) clientEngine.closeOutbound()
            spin()
            onServerClosed()
        } catch (_: SSLException) {
            dead = true
        }
    }

    private fun spin() {
        if (dead) return
        repeat(64) {
            var moved = false
            try {
                moved = tasks(serverEngine) || moved
                moved = wrap(serverEngine, toServerApp, toServer) || moved
                moved = unwrap(serverEngine, fromServer, toClientApp, onServerPlain) || moved
            } catch (error: SSLException) {
                serverFailed = true
                throw error
            }
            if (protocol == null && serverEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                startClient()
                moved = true
            }
            if (protocol != null) {
                moved = tasks(clientEngine) || moved
                moved = wrap(clientEngine, toClientApp, toClient) || moved
                moved = unwrap(clientEngine, fromClient, toServerApp, onClientPlain) || moved
            }
            if (!moved) return
        }
    }

    private fun tasks(engine: SSLEngine): Boolean {
        if (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_TASK) return false
        var ran = false
        while (true) {
            val task = engine.delegatedTask ?: break
            task.run()
            ran = true
        }
        return ran
    }

    private fun wrap(engine: SSLEngine, pending: ArrayDeque<ByteBuffer>, send: (ByteArray) -> Unit): Boolean {
        val status = engine.handshakeStatus
        if (wantsUnwrap(status) || status == SSLEngineResult.HandshakeStatus.NEED_TASK) return false
        val app = when {
            pending.isNotEmpty() -> pending.first()
            status == SSLEngineResult.HandshakeStatus.NEED_WRAP -> EMPTY
            else -> return false
        }
        val net = ByteBuffer.allocate(maxOf(engine.session.packetBufferSize, 18_432))
        val result = engine.wrap(app, net)
        if (pending.isNotEmpty() && app === pending.first() && !app.hasRemaining()) pending.removeFirst()
        if (result.bytesProduced() > 0) {
            val out = ByteArray(result.bytesProduced())
            net.flip()
            net.get(out)
            send(out)
        }
        return result.bytesProduced() > 0 || result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK
    }

    private fun unwrap(
        engine: SSLEngine,
        net: PacketBuf,
        pending: ArrayDeque<ByteBuffer>,
        onPlain: (ByteArray) -> Unit,
    ): Boolean {
        val status = engine.handshakeStatus
        if (status == SSLEngineResult.HandshakeStatus.NEED_WRAP || status == SSLEngineResult.HandshakeStatus.NEED_TASK) return false
        val src = net.readable()
        try {
            if (!src.hasRemaining() && status.name != "NEED_UNWRAP_AGAIN") return false
            val app = ByteBuffer.allocate(maxOf(engine.session.applicationBufferSize, 32_768) + 64)
            val result = engine.unwrap(src, app)
            if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) return false
            if (result.bytesProduced() > 0) {
                val plain = ByteArray(result.bytesProduced())
                app.flip()
                app.get(plain)
                onPlain(plain)
                val forward = if (engine === clientEngine && protocol == HTTP1) requests.feed(plain) else listOf(plain)
                forward.forEach { pending.add(ByteBuffer.wrap(it)) }
            }
            return result.bytesConsumed() > 0 || result.bytesProduced() > 0
        } finally {
            net.compact()
        }
    }

    private class PacketBuf {
        private var data = ByteBuffer.allocate(4096)

        fun append(bytes: ByteArray) {
            if (data.remaining() < bytes.size) {
                val grown = ByteBuffer.allocate((data.position() + bytes.size + 4096).coerceAtLeast(data.capacity() * 2))
                data.flip()
                grown.put(data)
                data = grown
            }
            data.put(bytes)
        }

        fun readable(): ByteBuffer {
            data.flip()
            return data
        }

        fun compact() {
            data.compact()
        }
    }

    companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        private fun wantsUnwrap(status: SSLEngineResult.HandshakeStatus): Boolean =
            status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP || status.name == "NEED_UNWRAP_AGAIN"

        const val H2 = "h2"
        const val HTTP1 = "http/1.1"

        /** Sets the ALPN list; true when the engine took it (Android 10 and later, any desktop JDK). */
        private fun protocols(engine: SSLEngine, list: Array<String>, identify: Boolean): Boolean = runCatching {
            val params = engine.sslParameters
            if (identify) params.endpointIdentificationAlgorithm = "HTTPS"
            params.applicationProtocols = list
            engine.sslParameters = params
            engine.sslParameters.applicationProtocols.contentEquals(list)
        }.getOrDefault(false).also { taken ->
            if (!taken && identify) runCatching {
                val params = engine.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                engine.sslParameters = params
            }
        }
    }
}
