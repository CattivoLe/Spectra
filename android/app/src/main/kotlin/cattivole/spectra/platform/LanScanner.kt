package cattivole.spectra.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import cattivole.spectra.core.DiscoveredPort
import cattivole.spectra.core.HttpBanner
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.LanIdentity
import cattivole.spectra.core.LanProfile
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.RtspService
import cattivole.spectra.core.ServiceAdvertisement
import cattivole.spectra.core.SubnetInfo
import cattivole.spectra.core.UPnPIdentity
import cattivole.spectra.core.WindowsHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** The Wi-Fi or Ethernet network a probe should use. VPN tunnels are left out. */
data class LanRoute(
    val network: Network,
    val subnet: SubnetInfo,
    val validated: Boolean,
    val captive: Boolean,
)

/**
 * Walks the Wi-Fi (or Ethernet) subnet the phone is on. Android still fills
 * `/proc/net/arp` with neighbour hardware addresses, which iOS usually hides.
 * ICMP is not available without root. A host counts as present the same way
 * it does on iOS: a TCP connect that opens or is refused (the host answered),
 * an ARP entry left by that probe, or a service advertisement.
 */
class LanScanner(context: Context) {
    var devices: List<LanDevice> = emptyList()
        private set
    var subnet: SubnetInfo? = null
        private set
    var scanning: Boolean = false
        private set
    var progress: String = ""
        private set
    var widePrefix: Boolean = false
        private set
    /** 0..1 through the sweep, for the progress bar. */
    var fraction: Float = 0f
        private set
    var lastScanDate: Long? = null
        private set
    /** The last attempt found no Wi-Fi or Ethernet to walk. */
    var noNetwork: Boolean = false
        private set

    /**
     * Stable identity of the joined network, so repeat scans of the same LAN
     * merge into one saved session: the gateway's hardware address; falls back
     * to the subnet's network address and prefix.
     */
    val networkKey: String?
        get() {
            devices.firstOrNull { it.isGateway }?.macAddress?.let { return "lan:gw:${it.text}" }
            val info = subnet ?: return null
            return "lan:net:${info.networkLabel}"
        }

    val networkLabel: String? get() = subnet?.networkLabel

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var portJob: Job? = null
    private val listeners = mutableListOf<() -> Unit>()
    private val known = linkedMapOf<String, LanDevice>()
    /** The Wi-Fi or Ethernet network the sweep is walking, so probes do not leave via a VPN. */
    private var routeNetwork: Network? = null

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun scan() {
        if (scanning) return
        portJob?.cancel()
        known.values.forEach { it.portScanFraction = null }
        job = scope.launch {
            scanning = true
            widePrefix = false
            noNetwork = false
            fraction = 0.02f
            describedHosts.clear()
            progress = "Reading the network"
            publish()
            val info = currentSubnet()
            subnet = info
            if (info == null) {
                progress = "Join a Wi-Fi network to scan it"
                noNetwork = true
                scanning = false
                publish()
                return@launch
            }
            val hosts = hostAddresses(info)
            progress = "Looking for ${hosts.size} addresses"
            fraction = 0.05f
            publish()
            val lock = multicastLock()
            try {
                val found = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val ssdp = async { ssdpSearch() }
                        val poked = async { poke(hosts) }
                        val names = async { discoverMdns() }
                        val answered = poked.await()
                        val onLan = hosts.toHashSet().apply { add(info.localIp) }
                        val arp = readArp().filterKeys { it in onLan }
                        val mdns = names.await()
                        val announced = ssdp.await()
                        val alive = (
                            answered + arp.keys + mdns.names.keys + mdns.services.keys +
                                announced.keys + info.localIp
                            ).filter { it in onLan }.distinct()
                        progress = "Checking ${alive.size} hosts"
                        fraction = 0.6f
                        publishOnMain()
                        var checked = 0
                        val profiles = alive.map { ip ->
                            async {
                                val ports = scanPorts(ip)
                                val profile = identify(ip, ports)
                                withContext(Dispatchers.Main.immediate) {
                                    checked += 1
                                    fraction = 0.6f + 0.4f * checked / alive.size
                                    publish()
                                }
                                Triple(ip, ports, profile)
                            }
                        }.awaitAll()
                        Assembled(arp, mdns.names, mdns.services, announced, profiles)
                    }
                }
                apply(info, found)
                progress = "${devices.size} devices"
                lastScanDate = System.currentTimeMillis()
            } finally {
                lock?.release()
                scanning = false
                publish()
            }
        }
    }

    fun stop() {
        job?.cancel()
        scanning = false
        publish()
    }

    fun clear() {
        stop()
        portJob?.cancel()
        known.clear()
        devices = emptyList()
        subnet = null
        lastScanDate = null
        progress = ""
        publish()
    }

    /**
     * Ports of one host: the common list, or every TCP port from 1 to 65535.
     * Banners and Windows names are read again from what turns up.
     */
    fun scanHostPorts(ip: String, all: Boolean = false) {
        val device = known[ip] ?: return
        if (scanning || device.portScanFraction != null) return
        portJob?.cancel()
        portJob = scope.launch {
            device.portScanFraction = 0f
            publish()
            try {
                val candidates = if (all) (1..65535).map { it to DiscoveredPort.serviceName(it) } else DiscoveredPort.commonPorts
                val gate = Semaphore(if (all) 384 else 24)
                val done = AtomicInteger()
                val ticker = launch {
                    while (true) {
                        delay(250)
                        device.portScanFraction = done.get().toFloat() / candidates.size
                        publish()
                    }
                }
                val found = withContext(Dispatchers.IO) {
                    coroutineScope {
                        candidates.map { (port, name) ->
                            async {
                                val open = gate.withPermit { probePort(ip, port, if (all) 350 else 400) }
                                done.incrementAndGet()
                                if (open) DiscoveredPort(port, name) else null
                            }
                        }.awaitAll().filterNotNull()
                    }
                }
                ticker.cancel()
                val profile = withContext(Dispatchers.IO) { identify(ip, found) }
                device.openPorts = found
                device.portsScanned = true
                device.profile = profile.filled(device.profile)
            } finally {
                device.portScanFraction = null
                publish()
            }
        }
    }

    private suspend fun publishOnMain() {
        withContext(Dispatchers.Main.immediate) { publish() }
    }

    private fun apply(info: SubnetInfo, found: Assembled) {
        val next = linkedMapOf<String, LanDevice>()
        for ((ip, ports, profile) in found.profiles) {
            val previous = known[ip]
            val mac = found.arp[ip]?.let { MacAddress.parse(it) }
            val names = buildList {
                found.mdns[ip]?.let { addAll(it) }
                found.ssdp[ip]?.server?.let { add(it) }
            }
            val services = found.services[ip].orEmpty().ifEmpty { previous?.services.orEmpty() }
            val described = describedHosts[ip]
            val withUpnp = if (described == null) {
                profile
            } else {
                profile.copy(upnp = described.copy(server = found.ssdp[ip]?.server ?: described.server).filled(profile.upnp))
            }
            val mergedProfile = withUpnp
            val device = LanDevice(
                ipAddress = ip,
                macAddress = mac ?: previous?.macAddress,
                hostnames = names.ifEmpty { previous?.hostnames.orEmpty() },
                openPorts = ports,
                services = services,
                portsScanned = true,
                isGateway = ip == info.gateway,
                isLocalDevice = ip == info.localIp,
                profile = if (previous != null) mergedProfile.filled(previous.profile) else mergedProfile,
                firstSeen = previous?.firstSeen ?: System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
            )
            device.preservePinnedName(previous)
            next[ip] = device
        }
        known.clear()
        known.putAll(next)
        devices = next.values.sortedWith(
            compareBy<LanDevice> { !it.isLocalDevice }.thenBy { !it.isGateway }.thenBy { ipv4(it.ipAddress) ?: 0L },
        )
    }

    private data class SubnetCandidate(
        val wifi: Boolean,
        val hasGateway: Boolean,
        val info: SubnetInfo,
        val network: Network,
        val validated: Boolean,
        val captive: Boolean,
    )

    /** The joined LAN, for probes that should not escape through a VPN. */
    fun lanRoute(): LanRoute? = selectRoute()

    /**
     * The Wi-Fi or Ethernet subnet the phone shares with the LAN.
     *
     * The phone often carries extra virtual interfaces: a VPN, or a local
     * tunnel app (ad blocker, private DNS, proxy) that assigns a tiny
     * `/30` such as `26.26.26.0/30` with a `0.0.0.0` gateway. `activeNetwork`
     * can point at one of those, so the real network is never walked. Every
     * network is considered instead: VPN transports are dropped, and the one
     * with a real default gateway and the widest subnet wins.
     */
    private fun currentSubnet(): SubnetInfo? {
        val route = selectRoute()
        routeNetwork = route?.network
        return route?.subnet
    }

    @Suppress("DEPRECATION")
    private fun selectRoute(): LanRoute? {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = buildList {
            for (network in manager.allNetworks) {
                val caps = manager.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!wifi && !ethernet) continue
                val properties = manager.getLinkProperties(network) ?: continue
                val gateway = properties.routes
                    .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                    ?.gateway?.hostAddress
                    ?.takeIf { it != "0.0.0.0" }
                val ipv4 = properties.linkAddresses.filter {
                    val host = it.address
                    host is Inet4Address && !host.isLoopbackAddress && !host.isLinkLocalAddress
                }
                if (ipv4.isEmpty()) continue
                // With two addresses on one interface, keep the one on the gateway's subnet.
                val chosen = gateway?.let { gw -> ipv4.firstOrNull { sameSubnet(it, gw) } } ?: ipv4.first()
                val ip = chosen.address.hostAddress ?: continue
                add(
                    SubnetCandidate(
                        wifi,
                        gateway != null,
                        SubnetInfo(
                            localIp = ip,
                            prefixLength = chosen.prefixLength,
                            gateway = gateway,
                            interfaceName = properties.interfaceName,
                        ),
                        network,
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                    ),
                )
            }
        }
        // A real default gateway first, then Wi-Fi, then the widest subnet —
        // a tunnel's /30 with no gateway sinks to the bottom.
        val chosen = candidates
            .sortedWith(
                compareByDescending<SubnetCandidate> { it.hasGateway }
                    .thenByDescending { it.wifi }
                    .thenBy { it.info.prefixLength },
            )
            .firstOrNull() ?: return null
        return LanRoute(chosen.network, chosen.info, chosen.validated, chosen.captive)
    }

    private fun sameSubnet(address: android.net.LinkAddress, gateway: String): Boolean {
        val ip = ipv4(address.address.hostAddress ?: return false) ?: return false
        val gw = ipv4(gateway) ?: return false
        val prefix = address.prefixLength
        if (prefix <= 0 || prefix > 32) return false
        val mask = (-1L shl (32 - prefix)) and 0xFFFF_FFFFL
        return (ip and mask) == (gw and mask)
    }

    private fun hostAddresses(info: SubnetInfo): List<String> {
        val local = ipv4(info.localIp) ?: return emptyList()
        var prefix = info.prefixLength
        // iOS walks any subnet up to /20 (4094 addresses). Wider than that is
        // almost never the LAN — a tunnel or a carrier net — so stay on the
        // phone's own /24 instead of pretending the whole range is local.
        if (prefix < 20) {
            prefix = 24
            widePrefix = true
        }
        if (prefix > 30) return emptyList()
        val mask = (-1L shl (32 - prefix)) and 0xFFFF_FFFFL
        val network = local and mask
        val broadcast = network or mask.inv() and 0xFFFF_FFFFL
        val hosts = mutableListOf<String>()
        var cursor = network + 1
        while (cursor < broadcast && hosts.size < 4094) {
            hosts.add(formatIp(cursor))
            cursor += 1
        }
        return hosts
    }

    /**
     * Same discovery ports as iOS. A reset counts: the host is there, the port
     * is closed. The old probe only kept a successful connect to 80, 443 or
     * 445, and gave up after about 120 ms, so a phone or a printer that
     * refuses those ports never appeared.
     */
    private suspend fun poke(hosts: List<String>): Set<String> = withContext(Dispatchers.IO) {
        primeArp(hosts)
        delay(300)
        val targets = hosts.flatMap { ip -> discoveryPorts.map { port -> ip to port } }
        val alive = linkedSetOf<String>()
        var done = 0
        for (start in targets.indices step probeBatch) {
            if (!isActive) break
            val slice = targets.subList(start, minOf(start + probeBatch, targets.size))
            val outcomes = probeTcp(slice, discoveryTimeoutMs)
            for (index in slice.indices) {
                if (outcomes[index] != HostProbe.SILENT) alive.add(slice[index].first)
            }
            done += slice.size
            withContext(Dispatchers.Main.immediate) {
                fraction = 0.05f + 0.5f * done / targets.size.coerceAtLeast(1)
                publish()
            }
        }
        alive
    }

    /** One UDP datagram per address, so the kernel ARPs hosts that drop TCP. */
    private fun primeArp(hosts: List<String>) {
        val socket = try {
            DatagramSocket()
        } catch (_: Exception) {
            return
        }
        socket.use {
            bindToRoute(it)
            val payload = byteArrayOf(0)
            for (ip in hosts) {
                val address = inet4(ip) ?: continue
                try {
                    it.send(DatagramPacket(payload, payload.size, address, 7))
                } catch (_: Exception) {
                }
            }
        }
    }

    private enum class HostProbe { OPEN, REFUSED, SILENT }

    /**
     * Non-blocking connects, one timeout for the whole batch, matching the
     * iOS `poll`. A refused connection is [HostProbe.REFUSED]: the host answered.
     */
    private fun probeTcp(targets: List<Pair<String, Int>>, timeoutMs: Int): List<HostProbe> {
        val results = MutableList(targets.size) { HostProbe.SILENT }
        val selector = try {
            Selector.open()
        } catch (_: Exception) {
            return results
        }
        selector.use { sel ->
            var pending = 0
            for ((index, target) in targets.withIndex()) {
                val address = inet4(target.first) ?: continue
                val channel = try {
                    SocketChannel.open()
                } catch (_: Exception) {
                    continue
                }
                try {
                    channel.configureBlocking(false)
                    try {
                        channel.socket().setSoLinger(true, 0)
                    } catch (_: Exception) {
                    }
                    bindToRoute(channel.socket())
                    val connected = channel.connect(InetSocketAddress(address, target.second))
                    if (connected) {
                        results[index] = HostProbe.OPEN
                        channel.close()
                    } else {
                        channel.register(sel, SelectionKey.OP_CONNECT, index)
                        pending += 1
                    }
                } catch (error: Exception) {
                    if (hostAnswered(error)) results[index] = HostProbe.REFUSED
                    try {
                        channel.close()
                    } catch (_: Exception) {
                    }
                }
            }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (pending > 0 && System.nanoTime() < deadline) {
                val leftMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
                val ready = try {
                    sel.select(leftMs)
                } catch (_: Exception) {
                    break
                }
                if (ready == 0) continue
                val keys = sel.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    if (!key.isValid || !key.isConnectable) continue
                    val index = key.attachment() as Int
                    val channel = key.channel() as SocketChannel
                    var finished = true
                    try {
                        if (channel.finishConnect()) results[index] = HostProbe.OPEN else finished = false
                    } catch (error: Exception) {
                        if (hostAnswered(error)) results[index] = HostProbe.REFUSED
                    }
                    if (!finished) continue
                    pending -= 1
                    key.cancel()
                    try {
                        channel.close()
                    } catch (_: Exception) {
                    }
                }
            }
            sel.keys().toList().forEach { key ->
                try {
                    key.channel().close()
                } catch (_: Exception) {
                }
            }
        }
        return results
    }

    /** The host sent a reset or closed the socket. Timeouts and "no route" do not count. */
    private fun hostAnswered(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains("ECONNREFUSED", ignoreCase = true) ||
                message.contains("Connection refused", ignoreCase = true) ||
                message.contains("ECONNRESET", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun inet4(ip: String): InetAddress? {
        val value = ipv4(ip) ?: return null
        val bytes = ByteArray(4) { shift -> ((value shr (24 - shift * 8)) and 0xFF).toByte() }
        return InetAddress.getByAddress(bytes)
    }

    private fun bindToRoute(socket: Socket) {
        try {
            routeNetwork?.bindSocket(socket)
        } catch (_: Exception) {
        }
    }

    private fun bindToRoute(socket: DatagramSocket) {
        try {
            routeNetwork?.bindSocket(socket)
        } catch (_: Exception) {
        }
    }

    private suspend fun scanPorts(ip: String): List<DiscoveredPort> = coroutineScope {
        val gate = Semaphore(24)
        DiscoveredPort.commonPorts.map { (port, name) ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    if (probePort(ip, port, 200)) DiscoveredPort(port, name) else null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun identify(ip: String, ports: List<DiscoveredPort>): LanProfile {
        val numbers = ports.map { it.number }.toSet()
        val banners = httpBanners(ip, numbers)
        val rtsp = rtsp(ip, numbers)
        var upnp: UPnPIdentity? = null
        val windows = if (139 in numbers || 445 in numbers || 137 in numbers) windows(ip, 445 in numbers) else null
        return LanProfile(upnp = upnp, banners = banners, rtsp = rtsp, windows = windows)
    }

    private fun httpBanners(ip: String, ports: Set<Int>): List<HttpBanner> {
        val plain = setOf(80, 81, 8000, 8001, 8080, 8081, 8888, 5000)
        val secure = setOf(443, 8443, 5001)
        return ports.sorted().mapNotNull { port ->
            val https = port in secure
            if (!https && port !in plain) return@mapNotNull null
            val head = if (https) exchangeTls(ip, port, LanIdentity.httpRequest(ip, "HEAD"))
            else exchangeTcp(ip, port, LanIdentity.httpRequest(ip, "HEAD"), 2000)
            val body = head ?: return@mapNotNull null
            var parsed = LanIdentity.parseHttp(body) ?: return@mapNotNull null
            for (version in listOf("1.0", "1.1")) {
                val rejected = LanIdentity.httpMethodRejected(parsed.status)
                val empty = parsed.server == null && parsed.title == null
                if (if (version == "1.1") !rejected else !(rejected || empty)) continue
                val get = if (https) exchangeTls(ip, port, LanIdentity.httpRequest(ip, "GET", version))
                else exchangeTcp(ip, port, LanIdentity.httpRequest(ip, "GET", version), 2000, 65_536)
                val richer = get?.let { LanIdentity.parseHttp(it) } ?: continue
                if (LanIdentity.httpPrefers(richer.status, richer.title, parsed.status, parsed.title)) parsed = richer
                if (!LanIdentity.httpMethodRejected(parsed.status)) break
            }
            val full = parsed
            HttpBanner(port, https, full.status, full.server, full.poweredBy, full.title, full.realm)
        }
    }

    private fun rtsp(ip: String, ports: Set<Int>): List<RtspService> =
        listOf(554, 8554).filter { it in ports }.mapNotNull { port ->
            val data = exchangeTcp(ip, port, LanIdentity.rtspOptions(), 2000) ?: return@mapNotNull null
            val parsed = LanIdentity.parseRtsp(data) ?: return@mapNotNull null
            RtspService(port, parsed.server, parsed.methods)
        }

    private fun windows(ip: String, smb: Boolean): WindowsHost? {
        var computer: String? = null
        var domain: String? = null
        val replies = exchangeUdp(ip, 137, LanIdentity.netbiosNodeStatusRequest(), 800)
        for (reply in replies) {
            for (entry in LanIdentity.parseNetBios(reply)) {
                if (entry.suffix == 0x00 && !entry.group && computer == null) computer = entry.name
                else if (entry.group && (entry.suffix == 0x00 || entry.suffix == 0x1E) && domain == null) domain = entry.name
            }
        }
        var shares = emptyList<String>()
        var guest = false
        if (smb) {
            val session = readSmb(ip)
            if (session != null) {
                computer = session.computer ?: computer
                domain = session.domain ?: domain
                shares = session.shares
                guest = session.guest
            }
        }
        if (computer == null && domain == null && shares.isEmpty() && !guest) return null
        return WindowsHost(computer, domain, shares, guest)
    }

    private data class SmbResult(val computer: String?, val domain: String?, val shares: List<String>, val guest: Boolean)

    private fun readSmb(ip: String): SmbResult? {
        val socket = connect(ip, 445, 2000) ?: return null
        socket.use { raw ->
            if (!writeAll(raw, LanIdentity.smbNegotiate())) return null
            val negotiate = readFrame(raw) ?: return null
            if (LanIdentity.smbStatus(LanIdentity.smbPayload(negotiate)) != LanIdentity.SMB_SUCCESS) return null
            if (!writeAll(raw, LanIdentity.smbSessionSetup(LanIdentity.ntlmType1(), 1))) return null
            val setup = readFrame(raw) ?: return null
            val payload = LanIdentity.smbPayload(setup)
            val names = LanIdentity.parseNtlmChallenge(payload)
            val session = LanIdentity.smbSessionId(payload) ?: 0L
            val status = LanIdentity.smbStatus(payload)
            var established = status == LanIdentity.SMB_SUCCESS && session != 0L
            if (status == LanIdentity.SMB_MORE_PROCESSING && session != 0L) {
                if (!writeAll(raw, LanIdentity.smbSessionSetup(LanIdentity.ntlmType3(), 2, session))) return null
                val second = readFrame(raw) ?: return null
                established = LanIdentity.smbStatus(LanIdentity.smbPayload(second)) == LanIdentity.SMB_SUCCESS
            }
            if (!established) return SmbResult(names?.computer, names?.domain, emptyList(), false)
            val shares = mutableListOf<String>()
            var message = 3L
            for (share in listOf("Users", "Public", "share", "Shared", "media", "video", "photos", "backup", "homes")) {
                val request = LanIdentity.smbTreeConnect(share, ip, message, session)
                message += 1
                if (!writeAll(raw, request)) break
                val response = readFrame(raw) ?: break
                if (LanIdentity.smbStatus(LanIdentity.smbPayload(response)) == LanIdentity.SMB_SUCCESS) shares.add(share)
            }
            return SmbResult(names?.computer, names?.domain, shares, true)
        }
    }

    private fun ssdpSearch(): Map<String, LanIdentity.Ssdp> {
        val found = linkedMapOf<String, LanIdentity.Ssdp>()
        val socket = DatagramSocket()
        socket.soTimeout = 1200
        bindToRoute(socket)
        try {
            val payload = LanIdentity.ssdpSearch("239.255.255.250")
            val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("239.255.255.250"), 1900)
            socket.send(packet)
            val buffer = ByteArray(2048)
            val end = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < end) {
                try {
                    val incoming = DatagramPacket(buffer, buffer.size)
                    socket.receive(incoming)
                    val bytes = incoming.data.copyOf(incoming.length)
                    val parsed = LanIdentity.parseSsdp(bytes) ?: continue
                    val ip = incoming.address?.hostAddress ?: continue
                    found[ip] = parsed
                    val location = parsed.location
                    if (location != null && LanIdentity.locationIsOnHost(location, ip)) {
                        val xml = fetchUrl(location)
                        if (xml != null) {
                            val described = LanIdentity.parseUpnpDescription(xml)
                            found[ip] = parsed.copy(server = parsed.server)
                            describedHosts[ip] = described
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            socket.close()
        }
        return found
    }

    private val describedHosts = linkedMapOf<String, UPnPIdentity>()

    /**
     * One type at a time, then one resolve at a time. Samsung's NsdManager
     * drops every discovery when several run together, so a phone that only
     * answers mDNS never appeared.
     */
    private suspend fun discoverMdns(): MdnsFound = withContext(Dispatchers.Main.immediate) {
        val manager = app.getSystemService(NsdManager::class.java) ?: return@withContext MdnsFound()
        val names = linkedMapOf<String, MutableList<String>>()
        val services = linkedMapOf<String, MutableList<ServiceAdvertisement>>()
        val types = listOf(
            "_googlecast._tcp", "_device-info._tcp", "_companion-link._tcp", "_apple-mobdev2._tcp",
            "_airplay._tcp", "_raop._tcp", "_hap._tcp", "_spotify-connect._tcp", "_ipp._tcp",
            "_printer._tcp", "_smb._tcp", "_ssh._tcp", "_http._tcp",
        )
        var resolved = 0
        for (type in types) {
            if (resolved >= 24) break
            for (info in discoverType(manager, type)) {
                if (resolved >= 24) break
                val found = resolveOne(manager, info) ?: continue
                resolved += 1
                rememberResolved(found, names, services)
            }
        }
        MdnsFound(names, services)
    }

    private suspend fun discoverType(manager: NsdManager, type: String): List<NsdServiceInfo> =
        suspendCancellableCoroutine { cont ->
            val found = mutableListOf<NsdServiceInfo>()
            var resumed = false
            fun finish(rows: List<NsdServiceInfo>) {
                if (resumed || !cont.isActive) return
                resumed = true
                cont.resume(rows)
            }
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) = finish(found.toList())
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(emptyList())
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = finish(found.toList())
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    found.add(serviceInfo)
                }
            }
            try {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: Exception) {
                finish(emptyList())
                return@suspendCancellableCoroutine
            }
            val stop = Runnable {
                try {
                    manager.stopServiceDiscovery(listener)
                } catch (_: Exception) {
                    finish(found.toList())
                }
            }
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed(stop, 1000)
            handler.postDelayed({ finish(found.toList()) }, 1500)
            cont.invokeOnCancellation {
                handler.removeCallbacks(stop)
                try {
                    manager.stopServiceDiscovery(listener)
                } catch (_: Exception) {
                }
            }
        }

    private suspend fun resolveOne(manager: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            var resumed = false
            fun finish(resolved: NsdServiceInfo?) {
                if (resumed || !cont.isActive) return
                resumed = true
                cont.resume(resolved)
            }
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = finish(null)
                override fun onServiceResolved(resolved: NsdServiceInfo) = finish(resolved)
            }
            try {
                manager.resolveService(info, listener)
            } catch (_: Exception) {
                finish(null)
                return@suspendCancellableCoroutine
            }
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val giveUp = Runnable { finish(null) }
            handler.postDelayed(giveUp, 1000)
            cont.invokeOnCancellation { handler.removeCallbacks(giveUp) }
        }

    private fun rememberResolved(
        resolved: NsdServiceInfo,
        names: MutableMap<String, MutableList<String>>,
        services: MutableMap<String, MutableList<ServiceAdvertisement>>,
    ) {
        val host = resolved.host?.hostAddress ?: return
        val name = LanDevice.usefulHostname(resolved.serviceName, host) ?: resolved.serviceName
        fun keep(value: String?) {
            val shown = value?.let { LanDevice.usefulHostname(it, host) } ?: return
            val list = names.getOrPut(host) { mutableListOf() }
            if (shown !in list) list.add(shown)
        }
        keep(name)
        keep(attribute(resolved, "fn"))
        val model = attribute(resolved, "model") ?: attribute(resolved, "md")
        model?.let { keep(it) }
        val list = services.getOrPut(host) { mutableListOf() }
        if (list.none { it.type == resolved.serviceType && it.name == name }) {
            list.add(ServiceAdvertisement(resolved.serviceType, name, model))
        }
    }

    private fun attribute(info: NsdServiceInfo, key: String): String? {
        val bytes = info.attributes[key] ?: return null
        return bytes.toString(Charsets.UTF_8).trim().ifBlank { null }
    }

    private fun readArp(): Map<String, String> {
        val file = java.io.File("/proc/net/arp")
        if (!file.canRead()) return emptyMap()
        val map = linkedMapOf<String, String>()
        file.readLines().drop(1).forEach { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 4) return@forEach
            val mac = parts[3]
            if (mac == "00:00:00:00:00:00") return@forEach
            map[parts[0]] = mac
        }
        return map
    }

    private fun multicastLock(): WifiManager.MulticastLock? {
        val wifi = app.getSystemService(WifiManager::class.java) ?: return null
        return wifi.createMulticastLock("spectra").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun fetchUrl(location: String): String? {
        val uri = try {
            java.net.URI(location)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host ?: return null
        val https = uri.scheme.equals("https", true)
        val port = if (uri.port > 0) uri.port else if (https) 443 else 80
        val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
        val request = "GET $path HTTP/1.0\r\nHost: $host\r\nUser-Agent: Spectra\r\nConnection: close\r\n\r\n".encodeToByteArray()
        val data = if (https) exchangeTls(host, port, request) else exchangeTcp(host, port, request, 2000, 65_536)
        val text = data?.toString(Charsets.UTF_8) ?: return null
        val split = text.indexOf("\r\n\r\n")
        return if (split >= 0) text.substring(split + 4) else text
    }

    private fun probePort(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                bindToRoute(socket)
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun connect(ip: String, port: Int, timeoutMs: Int): Socket? = try {
        Socket().apply {
            bindToRoute(this)
            connect(InetSocketAddress(ip, port), timeoutMs)
        }
    } catch (_: Exception) {
        null
    }

    private fun exchangeTcp(ip: String, port: Int, payload: ByteArray, timeoutMs: Int, limit: Int = 4096): ByteArray? {
        val socket = connect(ip, port, timeoutMs) ?: return null
        socket.use {
            it.soTimeout = timeoutMs
            if (!writeAll(it, payload)) return null
            return readSome(it, limit)
        }
    }

    private fun exchangeTls(ip: String, port: Int, payload: ByteArray): ByteArray? = try {
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        socket.use {
            it.connect(InetSocketAddress(ip, port), 2000)
            it.soTimeout = 2000
            it.startHandshake()
            if (!writeAll(it, payload)) return null
            readSome(it, 65_536)
        }
    } catch (_: Exception) {
        null
    }

    private fun exchangeUdp(ip: String, port: Int, payload: ByteArray, timeoutMs: Int): List<ByteArray> {
        val socket = DatagramSocket()
        socket.soTimeout = timeoutMs
        bindToRoute(socket)
        val replies = mutableListOf<ByteArray>()
        try {
            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(ip), port))
            val buffer = ByteArray(1024)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                replies.add(packet.data.copyOf(packet.length))
            }
        } catch (_: Exception) {
        } finally {
            socket.close()
        }
        return replies
    }

    private fun writeAll(socket: Socket, payload: ByteArray): Boolean = try {
        socket.getOutputStream().write(payload)
        socket.getOutputStream().flush()
        true
    } catch (_: Exception) {
        false
    }

    private fun readSome(socket: Socket, limit: Int): ByteArray? = try {
        val input = socket.getInputStream()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (out.size() < limit) {
            val count = input.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        out.toByteArray().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun readFrame(socket: Socket): ByteArray? {
        val input = socket.getInputStream()
        val header = ByteArray(4)
        if (!readFully(input, header)) return null
        val length = (header[1].toInt() and 0xFF shl 16) or (header[2].toInt() and 0xFF shl 8) or (header[3].toInt() and 0xFF)
        if (length <= 0 || length > 1_000_000) return null
        val body = ByteArray(length)
        if (!readFully(input, body)) return null
        return header + body
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) return false
            offset += count
        }
        return true
    }

    private fun publish() {
        listeners.toList().forEach { it() }
    }

    private data class MdnsFound(
        val names: Map<String, List<String>> = emptyMap(),
        val services: Map<String, List<ServiceAdvertisement>> = emptyMap(),
    )

    private data class Assembled(
        val arp: Map<String, String>,
        val mdns: Map<String, List<String>>,
        val services: Map<String, List<ServiceAdvertisement>>,
        val ssdp: Map<String, LanIdentity.Ssdp>,
        val profiles: List<Triple<String, List<DiscoveredPort>, LanProfile>>,
    )

    companion object {
        /** Ports tried on every address. Kept in step with iOS `discoveryPorts`. */
        private val discoveryPorts = intArrayOf(80, 443, 22, 445, 62078, 7000, 554, 8080, 9100)
        private const val discoveryTimeoutMs = 1200
        private const val probeBatch = 256

        private fun ipv4(text: String): Long? {
            val parts = text.split(".")
            if (parts.size != 4) return null
            var value = 0L
            for (part in parts) {
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                value = (value shl 8) or octet.toLong()
            }
            return value
        }

        private fun formatIp(value: Long): String =
            listOf(24, 16, 8, 0).joinToString(".") { ((value shr it) and 0xFF).toString() }
    }
}
