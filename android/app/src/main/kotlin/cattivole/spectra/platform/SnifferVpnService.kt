package cattivole.spectra.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import cattivole.spectra.MainActivity
import cattivole.spectra.R
import cattivole.spectra.ui.tr
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local VPN that hands every IP packet to [TrafficProxy]. The system asks
 * the user once; after that the tunnel stays up until the recording stops.
 * TLS is decrypted only when the user turns that on, with the app's own certificate.
 */
class SnifferVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var proxy: TrafficProxy? = null
    private var startedAt = 0L
    private var foreground = false
    private var lastNote = 0L
    private val stopped = AtomicBoolean(false)
    /** Why the proxy stopped the recording on its own, shown on the Traffic screen. */
    @Volatile private var giveUp: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (proxy != null) return START_NOT_STICKY
        startInForeground(0)
        val fd = try {
            openTunnel()
        } catch (_: Exception) {
            stopped.set(true)
            CaptureBus.publish(running = false, flows = emptyList(), startedAt = 0, error = ERROR_START)
            endForeground()
            return START_NOT_STICKY
        }
        if (fd == null) {
            stopped.set(true)
            CaptureBus.publish(running = false, flows = emptyList(), startedAt = 0, error = ERROR_DENIED)
            endForeground()
            return START_NOT_STICKY
        }
        tunnel = fd
        giveUp = null
        CaptureBus.clearNotice()
        startedAt = System.currentTimeMillis()
        val underlying = physicalNetwork()
        val created = TrafficProxy(
            this,
            fd.fileDescriptor,
            underlying,
            ::lookupApp,
            CaptureBus.decryptTls,
            { CaptureBus.authority },
            { flows ->
                val still = proxy != null
                CaptureBus.publish(running = still, flows = flows, startedAt = startedAt, error = giveUp)
                val now = System.currentTimeMillis()
                if (still && now - lastNote > 1000) {
                    lastNote = now
                    startInForeground(flows.size)
                }
            },
            onGiveUp = { reason ->
                giveUp = when (reason) {
                    TrafficProxy.StopReason.STALLED -> ERROR_STALLED
                    TrafficProxy.StopReason.OVERLOADED -> ERROR_OVERLOADED
                }
                // Off the watchdog thread: shutting down joins the proxy's threads.
                Thread { shutdown() }.start()
            },
            onRefusedCertificate = { CaptureBus.noteRefusedCertificate() },
            bodies = CaptureBus.bodies,
            hostFilter = CaptureBus.hostFilter,
        )
        proxy = created
        CaptureBus.publish(running = true, flows = emptyList(), startedAt = startedAt, error = null)
        created.start()
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        shutdown()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun openTunnel(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Spectra")
            .setMtu(1500)
            .addAddress("10.111.222.1", 32)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)
            .setConfigureIntent(openApp())
        var ipv6 = true
        try {
            builder.addAddress("fd00:111:222::1", 128)
        } catch (_: Exception) {
            ipv6 = false
        }
        if (ipv6) {
            try {
                builder.addRoute("::", 0)
            } catch (_: Exception) {
                builder.allowFamily(android.system.OsConstants.AF_INET6)
            }
        } else {
            builder.allowFamily(android.system.OsConstants.AF_INET6)
        }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        val network = physicalNetwork()
        val dns = network?.let { getSystemService(ConnectivityManager::class.java).getLinkProperties(it)?.dnsServers }.orEmpty()
        val servers = dns.ifEmpty { listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("8.8.8.8")) }
        if (Build.VERSION.SDK_INT >= 33) {
            // Local networks stay off the tunnel: printers, casting and the LAN scan talk to them directly,
            // and nothing there is internet traffic worth recording. The DNS server keeps its route so lookups
            // are still read, even when it is the router.
            for ((address, prefix) in LOCAL_RANGES) {
                runCatching { builder.excludeRoute(android.net.IpPrefix(InetAddress.getByName(address), prefix)) }
            }
            for (server in servers.take(4)) {
                runCatching { builder.addRoute(server, if (server is java.net.Inet4Address) 32 else 128) }
            }
        }
        var addedDns = false
        servers.take(4).forEach { server ->
            if (runCatching { builder.addDnsServer(server) }.isSuccess) addedDns = true
        }
        if (!addedDns) builder.addDnsServer(InetAddress.getByName("1.1.1.1"))
        if (network != null) runCatching { builder.setUnderlyingNetworks(arrayOf(network)) }
        return builder.establish()
    }

    private fun physicalNetwork(): Network? {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun lookupApp(protocol: Int, src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val uid = try {
            getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
                protocol,
                InetSocketAddress(InetAddress.getByAddress(src), srcPort),
                InetSocketAddress(InetAddress.getByAddress(dst), dstPort),
            )
        } catch (_: Exception) {
            return null
        }
        val packages = packageManager.getPackagesForUid(uid) ?: return null
        val info = runCatching { packageManager.getApplicationInfo(packages[0], 0) }.getOrNull() ?: return null
        return packageManager.getApplicationLabel(info)?.toString()
    }

    private fun startInForeground(connections: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            val channel = NotificationChannel(CHANNEL, tr("Traffic"), NotificationManager.IMPORTANCE_LOW)
            channel.description = tr("Shown while Spectra is forwarding this phone's traffic.")
            manager.createNotificationChannel(channel)
        }
        val text = if (connections == 0) {
            tr("This phone's connections are being forwarded.")
        } else {
            tr("%d connections", connections)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_traffic)
            .setContentTitle(tr("Recording traffic"))
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, tr("Stop"), stopIntent())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION, notification)
        }
        foreground = true
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, SnifferVpnService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun shutdown() {
        if (!stopped.compareAndSet(false, true)) return
        val active = proxy
        proxy = null
        active?.stop()
        try {
            tunnel?.close()
        } catch (_: Exception) {
        }
        tunnel = null
        if (active == null || giveUp != null) {
            CaptureBus.publish(running = false, flows = CaptureBus.flows, startedAt = startedAt, error = giveUp ?: CaptureBus.error)
        }
        endForeground()
    }

    private fun endForeground() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "cattivole.spectra.STOP_TRAFFIC"
        const val ERROR_DENIED = "The VPN permission was denied."
        const val ERROR_START = "Could not start the recording."
        const val ERROR_STALLED = "Recording stopped by itself: the recorder stopped responding, and the phone would have been left without a network."
        const val ERROR_OVERLOADED = "Recording stopped by itself: there was more traffic than the recorder could handle, and the phone would have been left without a network."

        /** Private, link-local and multicast ranges. The tunnel's own 10.111.222.1 is an address, not a route. */
        private val LOCAL_RANGES = listOf(
            "10.0.0.0" to 8,
            "192.168.0.0" to 16,
            "172.16.0.0" to 12,
            "169.254.0.0" to 16,
            "224.0.0.0" to 4,
            "fe80::" to 10,
            "fc00::" to 7,
            "ff00::" to 8,
        )
        private const val CHANNEL = "traffic"
        private const val NOTIFICATION = 42
    }
}
