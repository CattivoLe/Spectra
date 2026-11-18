package cattivole.spectra.platform

import android.net.Network
import cattivole.spectra.core.MLabNdt7
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Measurement Lab's ndt7 over one WebSocket per direction. For a download the server streams binary
 * messages; for an upload the phone sends them and the server reports, in text messages, how much arrived.
 */
internal object MLabSocket {
    private fun client(network: Network): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .dns(object : okhttp3.Dns { override fun lookup(hostname: String) = network.getAllByName(hostname).toList() })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    private fun request(url: String): Request = Request.Builder()
        .url(url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://"))
        .header("Sec-WebSocket-Protocol", MLabNdt7.SUBPROTOCOL)
        .header("User-Agent", "Spectra")
        .build()

    /** Counts every byte the server streams until the deadline. */
    fun download(network: Network, url: String, moved: AtomicLong, deadline: Long, open: MutableSet<WebSocket>) {
        val done = CountDownLatch(1)
        val http = client(network)
        val socket = http.newWebSocket(request(url), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (System.nanoTime() < deadline) moved.addAndGet(bytes.size.toLong())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = done.countDown()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = done.countDown()
        })
        open.add(socket)
        try {
            done.await(((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } finally {
            socket.cancel()
            open.remove(socket)
            http.dispatcher.executorService.shutdown()
        }
    }

    /**
     * Sends binary messages until the deadline, growing them as ndt7 suggests, and counts what the server says
     * it received: what the phone has merely queued would inflate the number. Until the server first reports,
     * bytes count when they leave the queue.
     */
    fun upload(network: Network, url: String, moved: AtomicLong, deadline: Long, open: MutableSet<WebSocket>) {
        val ready = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val tally = Tally(moved)
        val http = client(network)
        val socket = http.newWebSocket(request(url), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = ready.countDown()

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (System.nanoTime() >= deadline) return
                MLabNdt7.serverReceived(text)?.let(tally::server)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready.countDown()
                closed.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready.countDown()
                closed.countDown()
            }
        })
        open.add(socket)
        try {
            if (!ready.await(5, TimeUnit.SECONDS) || closed.count == 0L) return
            var size = 1 shl 13
            var sent = 0L
            var chunk = ByteArray(size).toByteString()
            while (System.nanoTime() < deadline && closed.count > 0L) {
                // Keep about a megabyte in flight: OkHttp fails a socket whose queue passes 16 MB.
                if (socket.queueSize() > MAX_QUEUED) {
                    Thread.sleep(5)
                    tally.drained(sent - socket.queueSize())
                    continue
                }
                if (!socket.send(chunk)) break
                sent += size
                tally.drained(sent - socket.queueSize())
                if (size < (1 shl 20) && sent >= size * 16L) {
                    size *= 2
                    chunk = ByteArray(size).toByteString()
                }
            }
        } finally {
            socket.cancel()
            open.remove(socket)
            http.dispatcher.executorService.shutdown()
        }
    }

    private const val MAX_QUEUED = 1L shl 20

    /** Feeds the shared counter: queue-drained bytes until the server reports, then the server's total. */
    private class Tally(private val moved: AtomicLong) {
        private var counted = 0L
        private var reported = false

        @Synchronized
        fun drained(total: Long) {
            if (reported || total <= counted) return
            moved.addAndGet(total - counted)
            counted = total
        }

        @Synchronized
        fun server(total: Long) {
            reported = true
            if (total > counted) {
                moved.addAndGet(total - counted)
                counted = total
            }
        }
    }
}
