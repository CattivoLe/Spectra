package cattivole.spectra.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks Android for the mobile network while Wi-Fi stays joined, so a check
 * can go out through it. The request keeps the cellular radio up, so it is
 * dropped a minute after the last check that used it.
 */
class CellularRoute(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var network: Network? = null
    private var waiting: CompletableDeferred<Network?>? = null
    private val expire = Runnable { release() }

    /** The mobile network, or null when there is none or it does not come up in time. Call on the main thread. */
    suspend fun acquire(): Network? {
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, HOLD_MS)
        network?.let { return it }
        val manager = manager ?: return null
        val pending = waiting ?: CompletableDeferred<Network?>().also { deferred ->
            waiting = deferred
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val listener = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(found: Network) {
                    handler.post {
                        network = found
                        deferred.complete(found)
                    }
                }

                override fun onLost(lost: Network) {
                    handler.post { if (network == lost) network = null }
                }

                override fun onUnavailable() {
                    handler.post { deferred.complete(null) }
                }
            }
            callback = listener
            try {
                manager.requestNetwork(request, listener)
            } catch (_: Exception) {
                callback = null
                deferred.complete(null)
            }
        }
        val found = withTimeoutOrNull(WAIT_MS) { pending.await() }
        if (waiting === pending) waiting = null
        if (found == null) release()
        return found
    }

    fun release() {
        handler.removeCallbacks(expire)
        callback?.let { runCatching { manager?.unregisterNetworkCallback(it) } }
        callback = null
        network = null
        waiting?.complete(null)
        waiting = null
    }

    private companion object {
        const val WAIT_MS = 8_000L
        const val HOLD_MS = 60_000L
    }
}
