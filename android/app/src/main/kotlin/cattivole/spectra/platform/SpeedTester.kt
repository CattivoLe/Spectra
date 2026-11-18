package cattivole.spectra.platform

import android.content.Context
import android.net.Network
import android.util.Log
import cattivole.spectra.core.MLabNdt7
import cattivole.spectra.core.MLabServer
import cattivole.spectra.core.SpeedResult
import cattivole.spectra.core.SpeedServerChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Measures the line the way a speed-test site does: several parallel streams
 * for a fixed time, with the quarter-second throughput kept for a chart, and
 * small connections before and during the load to see how much the full
 * queue slows everything else down.
 *
 * Uses Cloudflare's speed endpoints or a Measurement Lab ndt7 server, over
 * the connection the route setting names, like the connection check.
 */
class SpeedTester(context: Context, private val route: suspend () -> Network?) {
    enum class Phase { IDLE, PREPARING, LATENCY, DOWNLOAD, UPLOAD, DONE, FAILED }

    var phase: Phase = Phase.IDLE
        private set
    var progress: Float = 0f
        private set

    /** Throughput over the last quarter second, in bit/s, while a transfer runs. */
    var liveBps: Double = 0.0
        private set
    var downSamples: List<Double> = emptyList()
        private set
    var upSamples: List<Double> = emptyList()
        private set
    var result: SpeedResult? = null
        private set

    /**
     * Cloudflare answered 429 during the last test: it limits tests from one
     * address after several in a row. The figures may then be lower than the line.
     */
    var throttled: Boolean = false
        private set

    /** Machines M-Lab offered at the last look, for the server picker. */
    var mlabServers: List<MLabServer> = emptyList()
        private set
    var loadingServers: Boolean = false
        private set

    /** The server the next test runs against. Remembered across launches. */
    var serverChoice: SpeedServerChoice = SpeedServerChoice.fromRaw(
        context.applicationContext.getSharedPreferences("spectra", Context.MODE_PRIVATE).getString(KEY_SERVER, null),
    )
        set(value) {
            field = value
            app.getSharedPreferences("spectra", Context.MODE_PRIVATE).edit().putString(KEY_SERVER, value.raw).apply()
            publish()
        }

    /** Set when the machine picked earlier was not offered, so the nearest one ran the test. */
    var substituted: Pair<String, String>? = null
        private set

    /** Why the last test failed, in English for `tr`; the argument is filled into `%s`. */
    var failure: Pair<String, String?>? = null
        private set

    val running: Boolean
        get() = phase == Phase.PREPARING || phase == Phase.LATENCY || phase == Phase.DOWNLOAD || phase == Phase.UPLOAD

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = mutableListOf<() -> Unit>()
    private var job: Job? = null

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** Asks M-Lab for the machines nearest to this network. */
    fun loadServers() {
        if (loadingServers) return
        loadingServers = true
        publish()
        scope.launch {
            val network = route()
            val found = if (network == null) emptyList() else withContext(Dispatchers.IO) { locate(network) }
            if (found.isNotEmpty()) mlabServers = found
            loadingServers = false
            publish()
        }
    }

    private fun locate(network: Network): List<MLabServer> =
        text(network, MLabNdt7.LOCATE_URL)?.let(MLabNdt7::parseLocate).orEmpty()

    fun start() {
        job?.cancel()
        substituted = null
        phase = Phase.PREPARING
        progress = 0.02f
        liveBps = 0.0
        downSamples = emptyList()
        upSamples = emptyList()
        result = null
        failure = null
        throttled = false
        publish()
        job = scope.launch {
            try {
                measure()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Whatever broke, the screen must not stay on "measuring".
                fail("The test stopped with an error: %s", "${error.javaClass.simpleName}: ${error.message.orEmpty()}", error)
            }
        }
    }

    private fun fail(reason: String, detail: String? = null, error: Throwable? = null) {
        Log.w(TAG, "Speed test failed in $phase: ${detail ?: reason}", error)
        liveBps = 0.0
        failure = reason to detail
        phase = Phase.FAILED
        publish()
    }

    /** The whole test, one phase after another. */
    private suspend fun measure() {
        run {
            val network = route()
            if (network == null) {
                fail("There is no network for the chosen connection. Check Wi-Fi, or pick another route under Connection.")
                return
            }
            phase = Phase.LATENCY
            progress = 0.06f
            publish()
            val choice = serverChoice
            val backend: Backend
            if (choice is SpeedServerChoice.MLab) {
                val offered = withContext(Dispatchers.IO) { locate(network) }
                if (offered.isNotEmpty()) {
                    mlabServers = offered + mlabServers.filter { old -> offered.none { it.machine == old.machine } }
                }
                // Test URLs carry single-use tokens, so a machine picked earlier needs a fresh offer.
                val chosen = choice.machine?.let { wanted -> offered.firstOrNull { it.machine == wanted } }
                val server = chosen ?: offered.firstOrNull()
                if (server == null) {
                    fail("M-Lab did not offer a server. Try again or pick Cloudflare.")
                    return
                }
                if (choice.machine != null && chosen == null) {
                    substituted = choice.machine.substringBefore('.') to server.shortName
                }
                backend = Backend.MLab(server)
            } else {
                backend = Backend.Cloudflare
            }
            val (edge, idle) = withContext(Dispatchers.IO) {
                val label = when (backend) {
                    Backend.Cloudflare -> text(network, "https://$HOST/cdn-cgi/trace")
                        ?.lineSequence()?.firstOrNull { it.startsWith("colo=") }?.substringAfter('=')
                    is Backend.MLab -> listOf(backend.server.city, backend.server.shortName).filter { it.isNotEmpty() }.joinToString(" · ")
                }
                label to (0 until 8).mapNotNull { connectMillis(network, backend) }
            }
            // Without a latency figure the throughput can still be measured:
            // only a test that moves no data at all has failed.
            if (idle.isEmpty()) Log.w(TAG, "No latency sample from $HOST")
            val (latency, jitter) = SpeedResult.latency(idle)

            phase = Phase.DOWNLOAD
            val down = transfer(network, backend, download = true, from = 0.1f, to = 0.55f) { downSamples = it }
            phase = Phase.UPLOAD
            liveBps = 0.0
            val up = transfer(network, backend, download = false, from = 0.55f, to = 1f) { upSamples = it }

            result = SpeedResult(
                measuredAt = System.currentTimeMillis(),
                downBps = SpeedResult.settled(down.first),
                upBps = SpeedResult.settled(up.first),
                idleLatencyMs = latency,
                jitterMs = jitter,
                loadedDownLatencyMs = SpeedResult.latency(down.second).first,
                loadedUpLatencyMs = SpeedResult.latency(up.second).first,
                downSamples = down.first,
                upSamples = up.first,
                server = edge,
                mlab = backend is Backend.MLab,
                streams = backend.downloadStreams,
            )
            liveBps = 0.0
            progress = 1f
            Log.i(TAG, "Speed test: down ${result?.downBps} up ${result?.upBps} via $edge")
            if (result?.downBps == null && result?.upBps == null) {
                if (throttled && backend == Backend.Cloudflare) {
                    fail("Cloudflare is turning away speed tests from this address for now (HTTP 429). Try again in a few minutes.")
                } else {
                    fail("No data got through. The network may be blocking the speed server.")
                }
            } else {
                phase = Phase.DONE
                publish()
            }
        }
    }

    fun stop() {
        job?.cancel()
        phase = Phase.IDLE
        liveBps = 0.0
        progress = 0f
        publish()
    }

    fun close() {
        job?.cancel()
    }

    /** Runs the streams for [SECONDS] and returns the quarter-second rates and the latency seen meanwhile. */
    private suspend fun transfer(
        network: Network,
        backend: Backend,
        download: Boolean,
        from: Float,
        to: Float,
        onSamples: (List<Double>) -> Unit,
    ): Pair<List<Double>, List<Int>> = coroutineScope {
        val leg = Leg(System.nanoTime() + SECONDS * 1_000_000_000L)
        val moved = leg.moved
        val deadline = leg.deadline
        val open = leg.open
        val streams = if (download) backend.downloadStreams else backend.uploadStreams
        // Each stream stops by itself at the deadline. It is not cancelled:
        // a blocked socket read or write ignores cancellation anyway.
        val workers = (0 until streams).map {
            launch(Dispatchers.IO) {
                when (backend) {
                    Backend.Cloudflare -> while (System.nanoTime() < deadline) {
                        if (download) pull(network, leg) else push(network, leg)
                    }
                    is Backend.MLab -> if (download) {
                        MLabSocket.download(network, backend.server.downloadUrl, leg.moved, deadline, leg.sockets)
                    } else {
                        MLabSocket.upload(network, backend.server.uploadUrl, leg.moved, deadline, leg.sockets)
                    }
                }
            }
        }
        val loaded = async(Dispatchers.IO) {
            val seen = mutableListOf<Int>()
            delay(1000)
            while (isActive && System.nanoTime() < deadline) {
                connectMillis(network, backend)?.let { seen.add(it) }
                delay(400)
            }
            seen
        }
        val raw = mutableListOf<Double>()
        val samples = mutableListOf<Double>()
        var last = 0L
        var lastAt = System.nanoTime()
        while (System.nanoTime() < deadline) {
            delay(250)
            val now = System.nanoTime()
            val total = moved.get()
            raw.add((total - last) * 8.0 / ((now - lastAt) / 1_000_000_000.0))
            last = total
            lastAt = now
            // A one-second moving average, as on iOS: uploads land a whole
            // megabyte at a time and would otherwise saw-tooth the chart.
            val rate = SpeedResult.smoothed(raw)
            samples.add(rate)
            liveBps = rate
            progress = from + (to - from) * (1f - ((deadline - now).coerceAtLeast(0) / (SECONDS * 1_000_000_000f)))
            onSamples(samples.toList())
            publish()
        }
        // A stream stuck in a write to a slow uplink, or waiting for the
        // server's reply, would hold the test past its end: closing the
        // connection makes the blocked call return.
        withContext(Dispatchers.IO) {
            open.forEach { runCatching { it.disconnect() } }
            leg.sockets.forEach { runCatching { it.cancel() } }
        }
        if (withTimeoutOrNull(STOP_GRACE_MS) { workers.joinAll() } == null) workers.forEach { it.cancel() }
        val latencies = withTimeoutOrNull(STOP_GRACE_MS) { loaded.await() } ?: emptyList()
        if (!loaded.isCompleted) loaded.cancel()
        if (leg.limited.get()) throttled = true
        samples.toList() to latencies
    }

    /** What the streams of one direction share. */
    private class Leg(val deadline: Long) {
        val moved = AtomicLong()
        val open: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()
        val sockets: MutableSet<okhttp3.WebSocket> = ConcurrentHashMap.newKeySet()

        /** The download size asked for; halved each time the server answers 429. */
        val chunk = AtomicInteger(DOWNLOAD_CHUNK_START)
        val limited = AtomicBoolean(false)

        /** A 429: ask for less next time, down to the floor. */
        fun throttled() {
            limited.set(true)
            chunk.updateAndGet { maxOf(DOWNLOAD_CHUNK_FLOOR, it / 2) }
        }
    }

    /**
     * One download, as large as the server allows right now. Cloudflare turns
     * away big downloads with 429 after several tests from one address, so
     * the size shrinks until it is let through. A body read to the end leaves
     * its connection in the pool for the next one.
     */
    private fun pull(network: Network, leg: Leg) {
        val connection = open(network, "https://$HOST/__down?bytes=${leg.chunk.get()}")
        leg.open.add(connection)
        var finished = false
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                if (status == 429) leg.throttled()
                connection.errorStream?.use { it.readBytes() }
                finished = true
                Thread.sleep(if (status == 429) 300 else 200)
                return
            }
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (System.nanoTime() < leg.deadline) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        finished = true
                        break
                    }
                    leg.moved.addAndGet(read.toLong())
                }
            }
        } catch (_: Exception) {
        } finally {
            leg.open.remove(connection)
            if (!finished) connection.disconnect()
        }
    }

    /**
     * POSTs one 1 MB body, like the iOS test. The body only counts once the
     * server has answered, so bytes still sitting in socket buffers do not
     * inflate the number. A body cut off at the deadline does not count.
     * The reply is read to the end and the connection is not torn down, so
     * the next body reuses it instead of paying for a new TLS handshake.
     */
    private fun push(network: Network, leg: Leg) {
        val connection = open(network, "https://$HOST/__up")
        leg.open.add(connection)
        var finished = false
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(UPLOAD_CHUNK.size)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.outputStream.use { it.write(UPLOAD_CHUNK) }
            val status = connection.responseCode
            (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { reply ->
                val sink = ByteArray(4096)
                while (reply.read(sink) >= 0) Unit
            }
            finished = true
            if (status in 200..299 && System.nanoTime() < leg.deadline) leg.moved.addAndGet(UPLOAD_CHUNK.size.toLong())
            if (status == 429) leg.limited.set(true)
            if (status !in 200..299) Thread.sleep(if (status == 429) 300 else 200)
        } catch (_: Exception) {
        } finally {
            leg.open.remove(connection)
            // Only a half-done exchange is torn down; a finished one goes back to the pool.
            if (!finished) connection.disconnect()
        }
    }

    /** Where the bytes go: Cloudflare's HTTP endpoints over several streams, or one ndt7 WebSocket each way. */
    private sealed class Backend {
        data object Cloudflare : Backend()
        data class MLab(val server: MLabServer) : Backend()

        val downloadStreams: Int get() = if (this is MLab) 1 else DOWNLOAD_STREAMS
        val uploadStreams: Int get() = if (this is MLab) 1 else UPLOAD_STREAMS
    }

    /** A fresh TCP connection to the server, in milliseconds. */
    private fun connectMillis(network: Network, backend: Backend = Backend.Cloudflare): Int? = when (backend) {
        Backend.Cloudflare -> NetConnect.millis(network, HOST, 443, 3000)
        is Backend.MLab -> NetConnect.millis(network, backend.server.host, 443, 3000)
    }

    private fun text(network: Network, url: String): String? {
        val connection = open(network, url)
        return try {
            if (connection.responseCode == 200) connection.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun open(network: Network, url: String): HttpURLConnection =
        (network.openConnection(URL(url)) as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Spectra")
        }

    private fun publish() {
        listeners.toList().forEach { it() }
    }

    companion object {
        const val SECONDS = 8L
        const val DOWNLOAD_STREAMS = 4
        const val UPLOAD_STREAMS = 3
        const val HOST = "speed.cloudflare.com"
        private const val TAG = "SpectraSpeed"
        private const val KEY_SERVER = "speedServer"

        /** The first download size: what Cloudflare's own test asks for at most. */
        const val DOWNLOAD_CHUNK_START = 25_000_000

        /** The smallest download size: still several round trips' worth on a fast line. */
        const val DOWNLOAD_CHUNK_FLOOR = 1_000_000

        /** One upload body; the same size the iOS test sends. */
        private val UPLOAD_CHUNK = ByteArray(1_000_000)

        /** How long the end of a phase waits for its streams to let go. */
        const val STOP_GRACE_MS = 3_000L
    }
}
