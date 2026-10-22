package cattivole.spectra.platform

import cattivole.spectra.core.AbroadView
import cattivole.spectra.core.CheckStart
import cattivole.spectra.core.ExternalAddress
import cattivole.spectra.core.LinkParse
import cattivole.spectra.core.LinkReport
import cattivole.spectra.core.LinkStatus
import cattivole.spectra.core.LocalTrace
import cattivole.spectra.core.ProbeResult
import cattivole.spectra.core.RegionReach
import cattivole.spectra.core.RemoteNote
import cattivole.spectra.core.RemoteTrace
import cattivole.spectra.core.SpeedSample
import cattivole.spectra.core.TraceHop
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/**
 * Measures the network the phone is joined to: whether the internet answers,
 * the public address, a short speed sample, which countries open, whether
 * that public address answers from other countries, and a traceroute.
 *
 * With [viaVpn] off, probes are pinned to Wi-Fi or Ethernet, so a VPN is
 * not what gets measured. With it on, they follow the system route and a
 * connected VPN is included. The traceroute shells out to `ping`; without
 * the VPN it tries to leave from the LAN interface.
 *
 * Country hosts stay in step with `regionHosts` in iOS `LinkProbe.swift`.
 */
class LinkProbe(context: Context, private val lan: LanScanner) {
    var running: Boolean = false
        private set
    var phase: String = ""
        private set
    var fraction: Float = 0f
        private set
    var report: LinkReport? = null
        private set

    /** Country codes whose single-country re-ping is in flight, for the UI spinner. */
    var recheckingRegions: Set<String> = emptySet()
        private set

    /** When on, checks follow the system route, VPN included. Off pins them to Wi-Fi or Ethernet. */
    var viaVpn: Boolean
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(VIA_VPN, false)
        set(value) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(VIA_VPN, value).apply()
            publish()
        }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = mutableListOf<() -> Unit>()
    private var job: Job? = null
    private var token = 0
    private val recheckJobs = mutableMapOf<String, Job>()

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun check() {
        val mine = ++token
        job?.cancel()
        cancelRechecks()
        report = LinkReport()
        running = true
        phase = "Reading the network"
        fraction = 0.04f
        publish()
        job = scope.launch {
            try {
                runCheck(mine)
            } finally {
                if (token == mine) {
                    running = false
                    fraction = if (report != null) 1f else 0f
                    phase = ""
                    publish()
                }
            }
        }
    }

    fun stop() {
        token++
        job?.cancel()
        cancelRechecks()
        running = false
        phase = ""
        publish()
    }

    /** Re-probe one country row in place, for when the user taps it. */
    fun recheckRegion(code: String) {
        if (code in recheckingRegions) return
        val host = REGION_HOSTS.firstOrNull { it.first == code }?.second ?: return
        recheckJobs[code]?.cancel()
        recheckingRegions = recheckingRegions + code
        publish()
        recheckJobs[code] = scope.launch {
            val reach = withContext(Dispatchers.IO) {
                val route = probeNetwork(viaVpn)
                if (route == null) RegionReach(code, host, false, null) else probeRegion(route.network, code, host)
            }
            report?.let { current ->
                current.regions = current.regions.map { if (it.code == code) reach else it }
            }
            recheckingRegions = recheckingRegions - code
            recheckJobs.remove(code)
            publish()
        }
    }

    private fun cancelRechecks() {
        recheckJobs.values.forEach { it.cancel() }
        recheckJobs.clear()
        recheckingRegions = emptySet()
    }

    private suspend fun runCheck(mine: Int) {
        val current = report ?: return
        suspend fun live() = token == mine && coroutineContext.isActive
        val throughVpn = viaVpn
        val route = probeNetwork(throughVpn)
        if (route == null) {
            current.status = LinkStatus.NO_NETWORK
            current.detail = if (throughVpn) {
                "There is no network connection to check."
            } else {
                "Connect to a Wi-Fi or Ethernet network first."
            }
            publish()
            return
        }
        val network = route.network
        stage("Checking the gateway", 0.12f)
        val gateway = route.gateway?.let { probeGateway(network, it) }
        val dns = probeDns(network)
        if (!live()) return
        stage("Looking up the external address", 0.28f)
        val (trace, webMillis) = withContext(Dispatchers.IO) {
            val body = httpText(network, "https://1.1.1.1/cdn-cgi/trace")
            val parsed = body?.let { LinkParse.externalFromTrace(it) }
            parsed to if (parsed != null) tcpMillis(network, "1.1.1.1", 443, 2000) else null
        }
        val external = trace
        val webOk = external != null
        current.gateway = gateway
        current.dns = dns
        current.web = ProbeResult("1.1.1.1", webOk, webMillis)
        current.external = external
        applyStatus(current, route.captive, gateway, dns, webOk)
        publish()
        if (external != null && live()) {
            val whois = withContext(Dispatchers.IO) { httpText(network, "https://ipwho.is/${external.ip}") }
            if (whois != null) current.external = LinkParse.mergeWhois(whois, external)
            publish()
        }
        if (!live()) return

        stage("Checking countries", 0.42f)
        current.regions = probeRegions(network)
        publish()
        if (!live()) return

        if (webOk) {
            stage("Measuring speed", 0.58f)
            val sample = measureSpeed(network)
            current.speed = sample
            current.speedFailed = sample.latencyMs == null && sample.downBps == null && sample.upBps == null
            publish()
        }
        if (!live()) return

        val ip = current.external?.ip
        if (!live()) return
        stage(if (ip != null) "Asking other countries" else "Tracing the path", 0.74f)
        val ping = ip?.let { remoteStart(network, "https://check-host.net/check-ping?host=${encode(it)}&max_nodes=6") }
        val traceReq = ip?.let { remoteStart(network, "https://check-host.net/check-traceroute?host=${encode(it)}&max_nodes=3") }
        if (!live()) return
        stage("Tracing the path", 0.88f)
        // The remote lookups only wait on HTTP, so the local traceroute runs alongside them.
        val gathered = coroutineScope {
            val abroad = async { abroadOutcome(network, ping) }
            val traces = async { traceOutcome(network, traceReq, ip) }
            val local = async { localTrace(route.interfaceName) }
            Triple(abroad.await(), traces.await(), local.await())
        }
        if (!live()) return
        current.abroad = gathered.first.first
        current.abroadNote = gathered.first.second
        current.remoteTraces = gathered.second.first
        current.traceNote = gathered.second.second
        current.localTrace = gathered.third
        publish()
    }

    private suspend fun abroadOutcome(network: Network, ping: CheckStart?): Pair<List<AbroadView>, RemoteNote> =
        when (ping) {
            is CheckStart.Ok -> pollAbroad(network, ping) to RemoteNote.NONE
            CheckStart.Busy -> emptyList<AbroadView>() to RemoteNote.BUSY
            CheckStart.Failed -> emptyList<AbroadView>() to RemoteNote.FAILED
            null -> emptyList<AbroadView>() to RemoteNote.NONE
        }

    private suspend fun traceOutcome(
        network: Network,
        traceReq: CheckStart?,
        target: String?,
    ): Pair<List<RemoteTrace>, RemoteNote> = when {
        traceReq is CheckStart.Ok && target != null -> pollTraces(network, traceReq, target) to RemoteNote.NONE
        traceReq is CheckStart.Busy -> emptyList<RemoteTrace>() to RemoteNote.BUSY
        traceReq is CheckStart.Failed -> emptyList<RemoteTrace>() to RemoteNote.FAILED
        else -> emptyList<RemoteTrace>() to RemoteNote.NONE
    }

    /** Wi-Fi or Ethernet when the VPN is skipped; the system default route when it is not. */
    private fun probeNetwork(throughVpn: Boolean): ProbeNetwork? {
        if (!throughVpn) {
            val route = lan.lanRoute() ?: return null
            return ProbeNetwork(route.network, route.subnet.gateway, route.captive, route.subnet.interfaceName)
        }
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val caps = manager.getNetworkCapabilities(network) ?: return null
        val gateway = manager.getLinkProperties(network)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
            ?.takeIf { it != "0.0.0.0" }
        return ProbeNetwork(
            network,
            gateway,
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            null,
        )
    }

    private fun applyStatus(
        report: LinkReport,
        captive: Boolean,
        gateway: ProbeResult?,
        dns: ProbeResult?,
        webOk: Boolean,
    ) {
        when {
            webOk -> report.status = LinkStatus.ONLINE
            captive -> {
                report.status = LinkStatus.CAPTIVE
                report.detail = "This network wants a sign-in before it allows the internet."
            }
            gateway?.ok == true && dns?.ok != true -> {
                report.status = LinkStatus.LAN
                report.detail = "The gateway answers, but nothing past it does."
            }
            dns?.ok == true -> {
                report.status = LinkStatus.OFFLINE
                report.detail = "DNS works, but secure web does not. A sign-in page may be blocking it."
            }
            else -> {
                report.status = LinkStatus.OFFLINE
                report.detail = "No route to the internet from this network."
            }
        }
    }

    private suspend fun probeGateway(network: Network, gateway: String): ProbeResult = withContext(Dispatchers.IO) {
        for (port in intArrayOf(443, 80, 53)) {
            val millis = tcpMillis(network, gateway, port, 800)
            if (millis != null) return@withContext ProbeResult(gateway, true, millis)
        }
        ProbeResult(gateway, false, null)
    }

    private suspend fun probeDns(network: Network): ProbeResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        val ok = try {
            network.getAllByName(DNS_NAME).isNotEmpty()
        } catch (_: Exception) {
            false
        }
        ProbeResult(DNS_NAME, ok, if (ok) elapsed(start) else null)
    }

    private suspend fun probeRegions(network: Network): List<RegionReach> = withContext(Dispatchers.IO) {
        val gate = Semaphore(6)
        coroutineScope {
            REGION_HOSTS.map { (code, host) ->
                async { gate.withPermit { probeRegion(network, code, host) } }
            }.awaitAll()
        }
    }

    /** A single country: resolve the host and open a TCP connection to port 443. Blocking; call on IO. */
    private fun probeRegion(network: Network, code: String, host: String): RegionReach {
        val start = System.nanoTime()
        val ok = try {
            val address = network.getAllByName(host).firstOrNull { it is Inet4Address }
                ?: network.getAllByName(host).firstOrNull()
            if (address == null) {
                false
            } else {
                Socket().use { socket ->
                    network.bindSocket(socket)
                    socket.connect(InetSocketAddress(address, 443), 2200)
                }
                true
            }
        } catch (_: Exception) {
            false
        }
        return RegionReach(code, host, ok, if (ok) elapsed(start) else null)
    }

    private suspend fun measureSpeed(network: Network): SpeedSample = withContext(Dispatchers.IO) {
        val latency = mutableListOf<Int>()
        repeat(4) {
            tcpMillis(network, "1.1.1.1", 443, 2000)?.let { latency += it }
        }
        val down = transfer(network, download = true)
        val up = transfer(network, download = false)
        SpeedSample(median(latency), down, up)
    }

    private fun transfer(network: Network, download: Boolean): Double? {
        val url = if (download) {
            URL("https://speed.cloudflare.com/__down?bytes=10000000")
        } else {
            URL("https://speed.cloudflare.com/__up")
        }
        val connection = open(network, url, if (download) 8000 else 12000)
        val start = System.nanoTime()
        var total = 0L
        return try {
            if (!download) {
                val size = 750_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(size)
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.outputStream.use { out ->
                    val buf = ByteArray(8192)
                    var left = size
                    while (left > 0) {
                        val n = minOf(buf.size, left)
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
                total = size.toLong()
                connection.responseCode
            } else {
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (total < 8_000_000 && elapsedSeconds(start) < 5.0) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                    }
                }
            }
            val seconds = elapsedSeconds(start)
            if (total < 80_000 || seconds < 0.25) null else total * 8.0 / seconds
        } catch (_: Exception) {
            val seconds = elapsedSeconds(start)
            if (download && total >= 80_000 && seconds >= 0.25) total * 8.0 / seconds else null
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun remoteStart(network: Network, url: String): CheckStart? = withContext(Dispatchers.IO) {
        val body = httpText(network, url, acceptJson = true) ?: return@withContext null
        LinkParse.checkStart(body)
    }

    private suspend fun pollAbroad(network: Network, start: CheckStart.Ok): List<AbroadView> {
        var last = emptyList<AbroadView>()
        repeat(12) {
            delay(1100)
            if (!coroutineContext.isActive) return last
            val body = withContext(Dispatchers.IO) {
                httpText(network, "https://check-host.net/check-result/${start.id}", acceptJson = true)
            } ?: return@repeat
            val parsed = LinkParse.abroad(body, start.nodes) ?: return@repeat
            last = parsed.second
            if (parsed.first) return last
        }
        return last
    }

    private suspend fun pollTraces(network: Network, start: CheckStart.Ok, target: String): List<RemoteTrace> {
        var last = emptyList<RemoteTrace>()
        repeat(12) {
            delay(1100)
            if (!coroutineContext.isActive) return last
            val body = withContext(Dispatchers.IO) {
                httpText(network, "https://check-host.net/check-result/${start.id}", acceptJson = true)
            } ?: return@repeat
            val parsed = LinkParse.traces(body, start.nodes, target) ?: return@repeat
            last = parsed.second
            if (parsed.first) return last
        }
        return last
    }

    /**
     * Hop-limited ICMP via the platform ping. Android does not give an app a
     * raw socket, and on many phones the ping binary is allowed to set a TTL.
     * [iface] keeps the trace on the LAN when the check is not following the VPN.
     * If the phone refuses that bind, the trace falls back to the system route.
     */
    private suspend fun localTrace(iface: String?): LocalTrace = withContext(Dispatchers.IO) {
        val target = "1.1.1.1"
        val hops = mutableListOf<TraceHop>()
        var timeoutFlag: String? = "-W"
        var bind = iface
        for (ttl in 1..16) {
            if (!coroutineContext.isActive) break
            var output = runPing(target, ttl, timeoutFlag, bind)
            if (output != null && timeoutFlag != null && optionRejected(output)) {
                timeoutFlag = null
                output = runPing(target, ttl, null, bind)
            }
            if (bind != null && output != null && ifaceRejected(output)) {
                bind = null
                output = runPing(target, ttl, timeoutFlag, null)
            }
            if (output == null) {
                return@withContext LocalTrace(target, hops, unavailable = hops.isEmpty())
            }
            val reply = LinkParse.pingOutput(output)
            if (reply.denied) {
                return@withContext LocalTrace(target, hops, unavailable = hops.isEmpty())
            }
            hops += TraceHop(reply.address, reply.millis)
            if (reply.reached || reply.unreachable) break
            if (hops.size >= 3 && hops.takeLast(3).all { it.address == null }) break
        }
        LocalTrace(target, hops, unavailable = false)
    }

    private fun runPing(host: String, ttl: Int, timeoutFlag: String?, iface: String?): String? {
        val cmd = mutableListOf("/system/bin/ping", "-c", "1", "-n", "-t", ttl.toString())
        if (timeoutFlag != null) cmd += listOf(timeoutFlag, "1")
        if (iface != null) cmd += listOf("-I", iface)
        cmd += host
        return try {
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            process.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            null
        }
    }

    private fun tcpMillis(network: Network, host: String, port: Int, timeoutMs: Int): Int? {
        val start = System.nanoTime()
        return try {
            Socket().use { socket ->
                network.bindSocket(socket)
                val address = InetAddress.getByName(host)
                socket.connect(InetSocketAddress(address, port), timeoutMs)
            }
            elapsed(start)
        } catch (_: Exception) {
            null
        }
    }

    private fun httpText(network: Network, url: String, acceptJson: Boolean = false): String? {
        val connection = open(network, URL(url), 8000)
        if (acceptJson) connection.setRequestProperty("Accept", "application/json")
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun open(network: Network, url: URL, timeoutMs: Int): HttpURLConnection =
        (network.openConnection(url) as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Spectra")
        }

    private fun stage(text: String, next: Float) {
        phase = text
        fraction = next
        publish()
    }

    private fun publish() {
        listeners.toList().forEach { it() }
    }

    private fun elapsed(start: Long): Int = ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)

    private fun elapsedSeconds(start: Long): Double = (System.nanoTime() - start) / 1_000_000_000.0

    private fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        return values.sorted()[values.size / 2]
    }

    private fun encode(ip: String): String = URLEncoder.encode(ip, Charsets.UTF_8.name())

    private fun optionRejected(output: String): Boolean =
        output.contains("invalid option", ignoreCase = true) ||
            output.contains("unknown option", ignoreCase = true) ||
            output.contains("bad option", ignoreCase = true)

    private fun ifaceRejected(output: String): Boolean =
        output.contains("SO_BINDTODEVICE", ignoreCase = true) ||
            output.contains("can't assign requested address", ignoreCase = true) ||
            output.contains("cannot assign requested address", ignoreCase = true) ||
            (output.contains("bind", ignoreCase = true) && output.contains("not permitted", ignoreCase = true))

    private companion object {
        const val PREFS = "spectra"
        const val VIA_VPN = "probeViaVpn"
        const val DNS_NAME = "one.one.one.one"

        private data class ProbeNetwork(
            val network: Network,
            val gateway: String?,
            val captive: Boolean,
            val interfaceName: String?,
        )

        val REGION_HOSTS = listOf(
            "US" to "www.usa.gov",
            "DE" to "www.denic.de",
            "NL" to "www.ripe.net",
            "GB" to "www.gov.uk",
            "FR" to "www.afnic.fr",
            "RU" to "ya.ru",
            "CN" to "www.baidu.com",
            "JP" to "www.yahoo.co.jp",
            "KR" to "www.naver.com",
            "BR" to "registro.br",
            "IN" to "www.nic.in",
            "AU" to "www.abc.net.au",
            "SG" to "www.sgnic.sg",
            "ZA" to "www.gov.za",
        )
    }
}
