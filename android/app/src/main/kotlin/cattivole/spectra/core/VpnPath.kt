package cattivole.spectra.core

import java.net.InetAddress

/** A route a VPN asked for. [excluded]: addresses it leaves to the ordinary network (Android 13+). */
data class TunnelRoute(val address: InetAddress, val prefix: Int, val excluded: Boolean = false)

/**
 * What a VPN does with a connection that wants to go around it.
 *
 * Most VPN apps do not allow bypassing, and then Android refuses to bind a socket to Wi-Fi or the mobile network
 * (EPERM) — with or without "Block connections without VPN". A socket left unbound is no way around either: it goes
 * into the tunnel, whose far end may answer for any address. Only addresses the VPN did not ask for still leave by
 * the ordinary network.
 */
object VpnPath {
    /** Public addresses of unrelated owners: a VPN that takes any of them takes the internet, not just its DNS server. */
    val publicSamples: List<InetAddress> = listOf(
        byteArrayOf(151.toByte(), 101, 1, 69),
        byteArrayOf(77, 88, 55, 88),
        byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
    ).map { InetAddress.getByAddress(it) }

    /** Android said no to leaving the VPN, as opposed to the network being gone. */
    fun isRefusal(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(6).any { cause ->
        val text = cause.message.orEmpty()
        text.contains("EPERM") || text.contains("EACCES") || text.contains("Operation not permitted", ignoreCase = true)
    }

    /** Whether the VPN takes a connection to [address]: its most specific route decides. */
    fun carries(routes: List<TunnelRoute>, address: InetAddress): Boolean =
        routes.filter { contains(it, address) }.maxByOrNull { it.prefix }?.excluded == false

    fun carriesInternet(routes: List<TunnelRoute>): Boolean = publicSamples.any { carries(routes, it) }

    private fun contains(route: TunnelRoute, address: InetAddress): Boolean {
        val net = route.address.address
        val host = address.address
        if (net.size != host.size || route.prefix !in 0..net.size * 8) return false
        val whole = route.prefix / 8
        for (i in 0 until whole) if (net[i] != host[i]) return false
        val rest = route.prefix % 8
        if (rest == 0) return true
        val mask = 0xFF shl (8 - rest) and 0xFF
        return (net[whole].toInt() and mask) == (host[whole].toInt() and mask)
    }
}
