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
) {
    private val clientEngine = clientContext.createSSLEngine().also { engine ->
        engine.useClientMode = false
        preferHttp1(engine, identify = false)
    }
    private val serverEngine = serverContext.createSSLEngine(host, port).also { engine ->
        engine.useClientMode = true
        preferHttp1(engine, identify = true)
    }
    private val fromClient = PacketBuf()
    private val fromServer = PacketBuf()
    private val requests = AcceptEncodingStrip()
    private val toServerApp = ArrayDeque<ByteBuffer>()
    private val toClientApp = ArrayDeque<ByteBuffer>()
    private var dead = false

    init {
        clientEngine.beginHandshake()
        serverEngine.beginHandshake()
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
            requests.finish().forEach { toServerApp.add(ByteBuffer.wrap(it)) }
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
            clientEngine.closeOutbound()
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
            moved = tasks(clientEngine) || moved
            moved = tasks(serverEngine) || moved
            moved = wrap(serverEngine, toServerApp, toServer) || moved
            moved = wrap(clientEngine, toClientApp, toClient) || moved
            moved = unwrap(clientEngine, fromClient, toServerApp, onClientPlain) || moved
            moved = unwrap(serverEngine, fromServer, toClientApp, onServerPlain) || moved
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
                val forward = if (engine === clientEngine) requests.feed(plain) else listOf(plain)
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

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        fun wantsUnwrap(status: SSLEngineResult.HandshakeStatus): Boolean =
            status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP || status.name == "NEED_UNWRAP_AGAIN"

        fun preferHttp1(engine: SSLEngine, identify: Boolean) {
            runCatching {
                val params = engine.sslParameters
                params.applicationProtocols = arrayOf("http/1.1")
                if (identify) params.endpointIdentificationAlgorithm = "HTTPS"
                engine.sslParameters = params
            }
        }
    }
}
