package cattivole.spectra.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import cattivole.spectra.core.TunnelRoute
import cattivole.spectra.core.VpnPath
import java.net.InetAddress
import java.net.Socket

/** The network a check may use for the route it was asked to measure. */
sealed interface RouteAnswer {
    data class Open(val network: Network) : RouteAnswer

    /** There is no such network right now. */
    data object Missing : RouteAnswer

    /** The network is there, but the VPN that is on does not let this app reach it on its own. */
    data object HeldByVpn : RouteAnswer
}

/** Finds out whether Wi-Fi or the mobile network can be used while a VPN is on. See [VpnPath]. */
class VpnGuard(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    sealed interface Path {
        /** Bind sockets to the network, as always. */
        data object Bound : Path

        /** Binding is refused, but this VPN leaves these addresses alone: go out unbound, or through [vpn]. */
        data class Around(val vpn: Network) : Path

        data object Held : Path
    }

    /** The VPN this app's traffic goes into, if any. A VPN that leaves Spectra out is not one. */
    fun activeVpn(): Network? {
        val network = manager?.activeNetwork ?: return null
        val caps = manager.getNetworkCapabilities(network) ?: return null
        return network.takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    }

    /** A network the VPN runs over: Wi-Fi, Ethernet or mobile. */
    @Suppress("DEPRECATION")
    private fun physical(): Network? = manager?.allNetworks?.firstOrNull { network ->
        val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** Whether the VPN that is on keeps the networks under it from being measured on their own. */
    fun holdsInternet(): Boolean = activeVpn() != null && physical()?.let { toInternet(it) == Path.Held } == true

    /** How to reach [host], an address on the network's own subnet. */
    fun toLocal(network: Network, host: InetAddress): Path = path(network) { !VpnPath.carries(it, host) }

    /**
     * How to reach the internet over [network]. Only when it is what carries the VPN's leftovers: a tunnel that takes
     * just its DNS server leaves the rest to one network, and that one can be measured.
     */
    fun toInternet(network: Network): Path {
        val vpn = activeVpn()
        val underlying = vpn != null && sharesTransport(vpn, network)
        return path(network) { underlying && !VpnPath.carriesInternet(it) }
    }

    private fun path(network: Network, leftAlone: (List<TunnelRoute>) -> Boolean): Path {
        val refused = try {
            Socket().use { network.bindSocket(it) }
            false
        } catch (error: Exception) {
            // Anything else (the network just went away) shows up as a check that gets no reply.
            VpnPath.isRefusal(error)
        }
        if (!refused) return Path.Bound
        val vpn = activeVpn() ?: return Path.Held
        val routes = manager?.getLinkProperties(vpn)?.let(::tunnelRoutes) ?: return Path.Held
        return if (leftAlone(routes)) Path.Around(vpn) else Path.Held
    }

    private fun sharesTransport(vpn: Network, network: Network): Boolean {
        val tunnel = manager?.getNetworkCapabilities(vpn) ?: return false
        val own = manager.getNetworkCapabilities(network) ?: return false
        return listOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_CELLULAR,
        ).any { tunnel.hasTransport(it) && own.hasTransport(it) }
    }

    private fun tunnelRoutes(properties: LinkProperties): List<TunnelRoute> = properties.routes.mapNotNull { route ->
        val destination = route.destination
        TunnelRoute(
            destination.address ?: return@mapNotNull null,
            destination.prefixLength,
            excluded = Build.VERSION.SDK_INT >= 33 && route.type != RouteInfo.RTN_UNICAST,
        )
    }
}
