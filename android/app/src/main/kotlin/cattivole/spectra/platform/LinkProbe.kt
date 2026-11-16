package cattivole.spectra.platform

import cattivole.spectra.core.AbroadView
import cattivole.spectra.core.HopPlace
import cattivole.spectra.core.RouteTrace
import cattivole.spectra.core.AccessCheck
import cattivole.spectra.core.AccessReport
import cattivole.spectra.core.ProbeRoute
import cattivole.spectra.core.ResourceReach
import cattivole.spectra.core.DnsReport
import cattivole.spectra.core.ExposedPort
import cattivole.spectra.core.ExposureReport
import cattivole.spectra.core.TlsCheck
import cattivole.spectra.core.TlsInspector
import cattivole.spectra.core.TlsObservation
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

    /** The mobile network instead of Wi-Fi. Ignored while [viaVpn] is on. */
    var viaCellular: Boolean
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(VIA_CELLULAR, false)
        set(value) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(VIA_CELLULAR, value).apply()
            if (!value) cellular.release()
            publish()
        }

    var route: ProbeRoute
        get() = if (viaVpn) ProbeRoute.VPN else if (viaCellular) ProbeRoute.CELLULAR else ProbeRoute.WIFI
        set(value) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(VIA_VPN, value == ProbeRoute.VPN)
                .putBoolean(VIA_CELLULAR, value == ProbeRoute.CELLULAR)
                .apply()
            if (value != ProbeRoute.CELLULAR) cellular.release()
            publish()
        }

    /** The network the route setting asks for, for the other checks that share it. */
    suspend fun currentNetwork(): Network? = probeNetwork()?.network

    // The person's own sites

    /** Sites to keep an eye on, kept across launches. */
    var resources: List<String> = emptyList()
        private set

    /** The last answer of each site. Not kept: it goes stale in minutes. */
    var resourceResults: Map<String, ResourceReach> = emptyMap()
        private set
    var checkingResources: Set<String> = emptySet()
        private set

    /** Adds a site. False when the text holds no site name or it is already listed. */
    fun addResource(input: String): Boolean {
        val host = AccessCheck.normalize(input) ?: return false
        if (host in resources) return false
        resources = resources + host
        saveResources()
        checkResource(host)
        return true
    }

    fun removeResource(host: String) {
        resources = resources - host
        resourceResults = resourceResults - host
        checkingResources = checkingResources - host
        saveResources()
        publish()
    }

    fun checkResource(host: String) {
        if (host !in resources || host in checkingResources) return
        checkingResources = checkingResources + host
        publish()
        scope.launch {
            val reach = withContext(Dispatchers.IO) {
                probeNetwork()?.let { reach(it.network, host) } ?: ResourceReach(host, false, null)
            }
            checkingResources = checkingResources - host
            if (host in resources) resourceResults = resourceResults + (host to reach)
            publish()
        }
    }

    fun checkAllResources() = resources.forEach(::checkResource)

    private fun saveResources() {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(RESOURCES, resources.joinToString("\n")).apply()
        publish()
    }

    /**
     * A finished TLS handshake with the site's own certificate. That is where
     * a filter cuts in: it reads the site name in the handshake and drops the
     * connection. No page is requested, since some sites answer a bare
     * request with silence.
     */
    private fun reach(network: Network, host: String): ResourceReach {
        val start = System.nanoTime()
        return try {
            NetConnect.connect(network, host, 443, 5000).socket.use {
                it.soTimeout = 6000
                val secure = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                    .createSocket(it, host, 443, true) as javax.net.ssl.SSLSocket
                secure.use { tls ->
                    tls.startHandshake()
                    if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
                        return ResourceReach(host, false, null)
                    }
                }
            }
            ResourceReach(host, true, elapsed(start))
        } catch (_: Exception) {
            ResourceReach(host, false, null)
        }
    }

    private suspend fun checkAccess(network: Network): AccessReport = coroutineScope {
        val open = AccessCheck.openHosts.map { async(Dispatchers.IO) { reach(network, it) } }
        val listed = AccessCheck.listedHosts.map { async(Dispatchers.IO) { reach(network, it) } }
        val blocked = AccessCheck.blockedHosts.map { async(Dispatchers.IO) { reach(network, it) } }
        AccessReport(open.awaitAll(), listed.awaitAll(), blocked.awaitAll())
    }

    private val app = context.applicationContext
    private val cellular = CellularRoute(app)

    init {
        resources = AccessCheck.parseList(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RESOURCES, null))
    }

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
        exposureJob?.cancel()
        cancelRechecks()
        cellular.release()
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
                val route = probeNetwork()
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

    // Ports from the internet

    var checkingExposure: Boolean = false
        private set

    /** Bumped when a finished report gains something worth saving again. */
    var revision: Int = 0
        private set

    /** Ports the person typed in, tried along with the usual ones next time. */
    private val extraExposurePorts = mutableListOf<Int>()
    private var exposureJob: Job? = null

    /**
     * Asks check-host.net to connect to the public address on each of the
     * usual ports, one request per port, and keeps the answers on the report.
     */
    fun checkExposure() = checkExposure(TlsInspector.exposurePorts + extraExposurePorts, merge = false)

    /** Tries one more port from outside and adds it to the list. */
    fun checkExposure(port: Int) {
        if (port !in 1..65535) return
        if (port !in TlsInspector.exposurePorts && port !in extraExposurePorts) extraExposurePorts.add(port)
        checkExposure(listOf(port), merge = true)
    }

    private fun checkExposure(ports: List<Int>, merge: Boolean) {
        val ip = report?.external?.ip ?: return
        if (running || checkingExposure) return
        checkingExposure = true
        publish()
        exposureJob = scope.launch {
            try {
                val route = probeNetwork() ?: return@launch
                var result = probeExposure(route.network, ip, ports)
                val current = report ?: return@launch
                if (current.external?.ip != ip) return@launch
                val previous = current.exposure
                if (merge && previous != null && previous.ip == ip) {
                    val kept = previous.ports.filter { old -> result.ports.none { it.port == old.port } }
                    result = result.copy(
                        ports = kept + result.ports,
                        note = if (result.note == RemoteNote.NONE) previous.note else result.note,
                    )
                }
                current.exposure = result
                revision += 1
            } finally {
                checkingExposure = false
                publish()
            }
        }
    }

    private suspend fun probeExposure(network: Network, ip: String, ports: List<Int>): ExposureReport {
        val started = mutableListOf<Triple<Int, String, String>>()
        var note = RemoteNote.NONE
        for (port in ports) {
            val start = remoteStart(network, "https://check-host.net/check-tcp?host=${encode("$ip:$port")}&max_nodes=1")
            when (start) {
                is CheckStart.Ok -> start.nodes.firstOrNull()?.let { started.add(Triple(port, start.id, it.id)) }
                CheckStart.Busy -> note = RemoteNote.BUSY
                else -> if (note == RemoteNote.NONE) note = RemoteNote.FAILED
            }
            if (start == CheckStart.Busy) break
            delay(250)
        }
        val results = HashMap<Int, Boolean>()
        var pending = started.toList()
        repeat(10) {
            if (pending.isEmpty()) return@repeat
            delay(1500)
            val still = mutableListOf<Triple<Int, String, String>>()
            for (item in pending) {
                val body = withContext(Dispatchers.IO) {
                    httpText(network, "https://check-host.net/check-result/${item.second}", acceptJson = true)
                }
                val open = body?.let { LinkParse.tcpOpen(it, item.third) }
                if (open == null) still.add(item) else results[item.first] = open
            }
            pending = still
        }
        return ExposureReport(ip, System.currentTimeMillis(), started.map { ExposedPort(it.first, results[it.first] ?: false) }, note)
    }

    // Certificates and DNS

    private suspend fun inspectTls(network: Network): TlsCheck = coroutineScope {
        TlsCheck(
            TlsInspector.references.map { reference ->
                async(Dispatchers.IO) {
                    CertificateProbe.fetch(reference.host, network = network).fold(
                        onSuccess = { found ->
                            TlsObservation(
                                host = reference.host,
                                issuer = found.issuer,
                                trusted = found.trusted,
                                expected = TlsInspector.isExpected(found.issuer, reference),
                                subject = found.subject,
                                names = found.names,
                                issuerCommonName = found.issuerCommonName,
                                notBefore = found.notBefore,
                                notAfter = found.notAfter,
                                chain = found.chain,
                                fingerprint = found.fingerprint,
                                trustError = found.trustError,
                            )
                        },
                        onFailure = { error ->
                            TlsObservation(reference.host, issuer = null, trusted = false, expected = true, error = error.message ?: "No reply")
                        },
                    )
                }
            }.awaitAll(),
        )
    }

    /**
     * Who answers this network's lookups: the resolvers the phone is told to
     * use, the address the world sees the lookups come from, whether that
     * resolver invents answers, and whether encrypted DNS gets through.
     */
    private suspend fun inspectDns(network: Network): DnsReport = withContext(Dispatchers.IO) {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val configured = manager?.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { it.hostAddress?.substringBefore('%') }
        coroutineScope {
            // Akamai answers this name with the address of whoever asked: the recursive resolver, not the phone.
            val egress = async { lookup(network, "whoami.akamai.net")?.ifEmpty { null } }
            val lies = async {
                val token = java.lang.Long.toString(kotlin.random.Random.nextLong(Long.MAX_VALUE), 36)
                val answers = listOf("probe-$token.example.com", "spectra-$token-${token.take(4)}.net").map { lookup(network, it) }
                if (answers.all { it == null }) null else answers.any { !it.isNullOrEmpty() }
            }
            val doh = async {
                httpText(network, "https://cloudflare-dns.com/dns-query?name=example.com&type=A", accept = "application/dns-json") != null
            }
            val dot = async { tcpMillis(network, "1.1.1.1", 853, 3000) != null }
            val resolverIp = egress.await()
            val owner = resolverIp?.let { ip ->
                httpText(network, "https://ipwho.is/${encode(ip)}")?.let { LinkParse.mergeWhois(it, ExternalAddress(ip)) }
            }
            DnsReport(
                configured = configured,
                resolverIp = resolverIp,
                resolverName = owner?.isp,
                resolverCountry = owner?.countryCode,
                liesAboutMissingNames = lies.await(),
                dohReachable = doh.await(),
                dotReachable = dot.await(),
            )
        }
    }

    /** The first address of a name, IPv4 preferred: "" when the name does not exist, null when the lookup failed. */
    private fun lookup(network: Network, name: String): String? = try {
        val all = network.getAllByName(name)
        (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress.orEmpty()
    } catch (_: java.net.UnknownHostException) {
        ""
    } catch (_: Exception) {
        null
    }

    // Traceroute to a chosen target

    var routeRunning: Boolean = false
        private set
    var routePhase: String = ""
        private set
    var routeTarget: String? = null
        private set
    var routeAddress: String? = null
        private set
    var routeTrace: LocalTrace? = null
        private set
    var routePlaces: Map<String, HopPlace> = emptyMap()
        private set
    var routeFailure: String? = null
        private set
    private var routeJob: Job? = null

    /**
     * Traces from this phone to [input] over the chosen route, then asks
     * ipwho.is where each public hop is. Android's ping speaks IPv4 here, so
     * a name is resolved to its IPv4 address.
     */
    fun traceRoute(input: String) {
        val host = RouteTrace.target(input)
        if (host == null) {
            routeFailure = "That is not a site name or an IP address."
            publish()
            return
        }
        routeJob?.cancel()
        routeRunning = true
        routeFailure = null
        routeTarget = host
        routeAddress = null
        routeTrace = null
        routePlaces = emptyMap()
        routePhase = "Looking up the address"
        publish()
        routeJob = scope.launch {
            try {
                val route = probeNetwork()
                if (route == null) {
                    routeFailure = "There is no network for the chosen connection."
                    return@launch
                }
                val manager = app.getSystemService(ConnectivityManager::class.java)
                val iface = route.interfaceName ?: manager?.getLinkProperties(route.network)?.interfaceName
                val address = withContext(Dispatchers.IO) {
                    runCatching { NetConnect.resolve(route.network, host).firstOrNull { it is Inet4Address }?.hostAddress }.getOrNull()
                }
                if (address == null) {
                    routeFailure = "Could not find an IPv4 address for this target."
                    return@launch
                }
                routeAddress = address
                routePhase = "Tracing the path"
                publish()
                val trace = localTrace(iface, address, RouteTrace.MAX_HOPS)
                routeTrace = trace
                if (trace.unavailable) {
                    routeFailure = "This phone blocked the probes a traceroute needs."
                    return@launch
                }
                routePhase = "Placing the hops on the map"
                publish()
                val publicHops = trace.hops.mapNotNull { it.address }.filter(RouteTrace::isPublic).distinct()
                val places = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val gate = Semaphore(4)
                        publicHops.map { ip ->
                            async {
                                gate.withPermit { httpText(route.network, "https://ipwho.is/$ip")?.let(RouteTrace::place)?.let { ip to it } }
                            }
                        }.awaitAll().filterNotNull().toMap()
                    }
                }
                routePlaces = places
            } finally {
                routeRunning = false
                routePhase = ""
                publish()
            }
        }
    }

    fun stopRoute() {
        routeJob?.cancel()
        routeRunning = false
        routePhase = ""
        publish()
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
        val route = probeNetwork()
        if (route == null) {
            current.status = LinkStatus.NO_NETWORK
            current.detail = if (throughVpn) {
                "There is no network connection to check."
            } else if (viaCellular) {
                "There is no mobile network to check."
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

        stage("Checking access", 0.34f)
        current.access = checkAccess(network)
        publish()
        if (!live()) return

        stage("Checking countries", 0.42f)
        current.regions = probeRegions(network)
        publish()
        if (!live()) return

        if (webOk) {
            stage("Checking certificates", 0.48f)
            val inspected = coroutineScope {
                val tls = async { inspectTls(network) }
                val resolver = async { inspectDns(network) }
                tls.await() to resolver.await()
            }
            if (!live()) return
            current.tls = inspected.first
            current.resolver = inspected.second
            publish()
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
    private suspend fun probeNetwork(): ProbeNetwork? {
        val throughVpn = viaVpn
        if (!throughVpn && viaCellular) {
            // No gateway to ask and no interface to trace from: the Wi-Fi router is not on this path.
            return cellular.acquire()?.let { ProbeNetwork(it, null, false, null) }
        }
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
    private suspend fun localTrace(iface: String?, target: String = "1.1.1.1", maxHops: Int = 16): LocalTrace = withContext(Dispatchers.IO) {
        val hops = mutableListOf<TraceHop>()
        var timeoutFlag: String? = "-W"
        var bind = iface
        for (ttl in 1..maxHops) {
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

    private fun tcpMillis(network: Network, host: String, port: Int, timeoutMs: Int): Int? =
        NetConnect.millis(network, host, port, timeoutMs)

    private fun httpText(network: Network, url: String, acceptJson: Boolean = false, accept: String? = null): String? {
        val connection = open(network, URL(url), 8000)
        if (acceptJson) connection.setRequestProperty("Accept", "application/json")
        if (accept != null) connection.setRequestProperty("Accept", accept)
        return try {
            // A caller that names its own type wants a real answer, not an error page.
            if (accept != null && connection.responseCode != 200) return null
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
        const val VIA_CELLULAR = "probeCellular"
        const val RESOURCES = "watchedResources"
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
