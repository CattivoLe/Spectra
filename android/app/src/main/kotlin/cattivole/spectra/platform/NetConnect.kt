package cattivole.spectra.platform

import android.net.Network
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A TCP connection to a host name that does not get stuck on a broken IPv6.
 *
 * Many home routers hand out an IPv6 address that goes nowhere. Java tries
 * whatever DNS lists first, often the IPv6 one, and waits out the whole
 * timeout before giving up, while HTTP clients quietly fall back. So the
 * IPv4 addresses are tried first, then the IPv6 ones.
 */
object NetConnect {
    /** A connected socket and how long the successful handshake took. */
    class Connected(val socket: Socket, val millis: Int)

    /** IPv4 first, then IPv6, each family in the order DNS gave. */
    fun ordered(addresses: List<InetAddress>): List<InetAddress> =
        addresses.filterIsInstance<Inet4Address>() + addresses.filter { it !is Inet4Address }

    fun resolve(network: Network?, host: String): List<InetAddress> =
        ordered((network?.getAllByName(host) ?: InetAddress.getAllByName(host)).toList())

    /**
     * Connects to the first address that answers. Each address gets
     * [timeoutMs]; the time reported is the handshake that succeeded.
     */
    fun connect(network: Network?, host: String, port: Int, timeoutMs: Int): Connected {
        var last: Exception = IOException("No address for $host")
        for (address in resolve(network, host)) {
            val socket = Socket()
            try {
                network?.bindSocket(socket)
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                return Connected(socket, ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1))
            } catch (error: Exception) {
                runCatching { socket.close() }
                last = error
            }
        }
        throw last
    }

    /** Handshake time to [host], or null when no address answers. */
    fun millis(network: Network?, host: String, port: Int, timeoutMs: Int): Int? = try {
        val connected = connect(network, host, port, timeoutMs)
        connected.socket.close()
        connected.millis
    } catch (_: Exception) {
        null
    }
}
