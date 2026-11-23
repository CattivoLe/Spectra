package cattivole.spectra.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.TrackedNetwork

/**
 * Joins the phone itself to a Wi-Fi network, the counterpart of the iOS
 * `WiFiJoiner`. The sensor's own "Join" is separate (it configures the
 * Cardputer over BLE); this configures Android.
 *
 * Android 10+ cannot silently connect a third-party app's chosen network, so it
 * uses a [WifiNetworkSuggestion]: the system joins it when in range and may ask
 * the user to approve the app once. Older releases take a classic
 * [WifiConfiguration] and connect right away.
 *
 * It also follows the Wi-Fi the phone is on, so the list can mark it and a
 * join counts as done only once the phone is really there; [onJoined] then
 * gets the password that worked.
 */
class WifiJoiner(private val context: Context) {
    sealed interface Status {
        data object Idle : Status
        data class Joining(val ssid: String) : Status
        /** The phone was seen on the network it was asked to join. */
        data class Joined(val ssid: String) : Status
        /** Added as a suggestion; the system connects when the network is in range. */
        data class Suggested(val ssid: String) : Status
        /** Saved in Android's own Wi-Fi list (Android 11+): the phone switches to it, or the user picks it there. */
        data class Saved(val ssid: String) : Status
        data class Failed(val ssid: String, val reason: Reason) : Status
    }

    enum class Reason { GENERIC, WEP_UNSUPPORTED, ADD_FAILED, PERMISSION, NOT_JOINED }

    var status: Status by mutableStateOf(Status.Idle)
        private set

    /** The SSID the phone is on, or null when it is on none or Android hides it (no location permission). */
    var currentSsid: String? by mutableStateOf(null)
        private set

    /**
     * Whether the phone is on a Wi-Fi network, named or not. Null until Android has answered. Not the same as having
     * [currentSsid]: without the location permission, or with location off, Android hides the name.
     */
    var onWifi: Boolean? by mutableStateOf(null)
        private set

    /** The access point the phone is associated with, when Android reports it. */
    var currentBssid: MacAddress? by mutableStateOf(null)
        private set

    /** Called with the SSID and password once the phone is seen on a network it was asked to join. */
    var onJoined: ((String, String) -> Unit)? = null

    /** The join waiting for the phone to show up on the network. */
    private var pending: Pair<String, String?>? = null

    private val wifi: WifiManager?
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val connectivity: ConnectivityManager?
        get() = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val handler = Handler(Looper.getMainLooper())
    private var watching: ConnectivityManager.NetworkCallback? = null

    /** The Wi-Fi networks up now: a phone can hold two at once. */
    private val present = mutableSetOf<Network>()

    /** True when [network] is the access point the phone is on: by BSSID when known, otherwise by name. */
    fun isCurrent(network: TrackedNetwork): Boolean {
        currentBssid?.let { return it == network.id }
        val ssid = currentSsid ?: return false
        return ssid == (network.revealedSsid ?: network.latest.ssid)
    }

    fun isFor(ssid: String): Boolean = when (val s = status) {
        is Status.Joining -> s.ssid == ssid
        is Status.Joined -> s.ssid == ssid
        is Status.Suggested -> s.ssid == ssid
        is Status.Saved -> s.ssid == ssid
        is Status.Failed -> s.ssid == ssid
        else -> false
    }

    /** Whether the phone is on [ssid] right now. */
    fun isOn(ssid: String): Boolean = currentSsid == ssid

    /** The failure to show for [ssid]: none once the phone is on it after all. */
    fun failure(ssid: String): Reason? =
        (status as? Status.Failed)?.takeIf { it.ssid == ssid && !isOn(ssid) }?.reason

    /**
     * Android 11+: the system's "Save this network?" sheet. The network lands in Android's own Wi-Fi list like one
     * the user typed in, so the phone joins it at once and keeps it — unlike a suggestion, which Android takes up
     * only when it decides to. Null on older Android, where [join] does the work. [auth] is [cattivole.spectra.core.AuthMode.raw].
     */
    fun addNetworkIntent(ssid: String, passphrase: String?, auth: Int): android.content.Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val suggestion = suggestionFor(ssid, passphrase, auth) ?: return null
        status = Status.Joining(ssid)
        pending = ssid to passphrase
        return android.content.Intent(android.provider.Settings.ACTION_WIFI_ADD_NETWORKS)
            .putParcelableArrayListExtra(android.provider.Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
    }

    /** What the "Save this network?" sheet answered. */
    fun onAddResult(ssid: String, resultCode: Int, data: android.content.Intent?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val results = data?.getIntegerArrayListExtra(android.provider.Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)
        val result = results?.firstOrNull()
        val saved = resultCode == android.app.Activity.RESULT_OK && (
            result == null ||
                result == android.provider.Settings.ADD_WIFI_RESULT_SUCCESS ||
                result == android.provider.Settings.ADD_WIFI_RESULT_ALREADY_EXISTS
            )
        status = when {
            currentSsid == ssid -> Status.Joined(ssid)
            saved -> Status.Saved(ssid)
            resultCode == android.app.Activity.RESULT_CANCELED -> Status.Idle
            else -> Status.Failed(ssid, Reason.ADD_FAILED)
        }
        if (status == Status.Idle || status is Status.Failed) pending = null
        if (currentSsid == ssid) noteCurrent(currentSsid, currentBssid)
    }

    fun join(ssid: String, passphrase: String?, wep: Boolean, auth: Int = -1) {
        status = Status.Joining(ssid)
        pending = ssid to passphrase
        status = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                joinViaSuggestion(ssid, passphrase, wep, auth)
            } else {
                joinLegacy(ssid, passphrase, wep)
            }
        } catch (e: SecurityException) {
            Status.Failed(ssid, Reason.PERMISSION)
        } catch (e: Exception) {
            Status.Failed(ssid, Reason.GENERIC)
        }
        if (status is Status.Failed) pending = null
        if (status is Status.Joining) {
            // What the system answered is not whether the phone is there: its network is watched for a while.
            handler.postDelayed({
                if (status == Status.Joining(ssid)) status = Status.Failed(ssid, Reason.NOT_JOINED)
            }, JOIN_WAIT_MS)
        }
    }

    fun forget(ssid: String) {
        val manager = wifi ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                suggestionFor(ssid, null, -1)?.let { manager.removeNetworkSuggestions(listOf(it)) }
            } else {
                forgetLegacy(manager, ssid)
            }
        } catch (_: Exception) {
            // Best effort; clearing the status is what the user sees.
        }
        if (pending?.first == ssid) pending = null
        if (isFor(ssid)) status = Status.Idle
    }

    /** Starts following the phone's Wi-Fi. */
    fun start() {
        if (watching != null) return
        val manager = connectivity ?: return
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = notePresent(network, up = true)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = read(network, caps)
                override fun onLost(network: Network) = notePresent(network, up = false)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = notePresent(network, up = true)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = read(network, caps)
                override fun onLost(network: Network) = notePresent(network, up = false)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.registerNetworkCallback(request, callback, handler)
            watching = callback
            // Without any Wi-Fi the callback stays silent, so ask once.
            handler.postDelayed({ onWifi = present.isNotEmpty() }, 500)
        } catch (_: Exception) {
            // Without the callback the list simply marks nothing.
        }
    }

    fun stop() {
        watching?.let { callback -> runCatching { connectivity?.unregisterNetworkCallback(callback) } }
        watching = null
    }

    @Suppress("DEPRECATION")
    private fun read(network: Network, caps: NetworkCapabilities) {
        named = network
        val info = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps.transportInfo as? WifiInfo else null)
            ?: runCatching { wifi?.connectionInfo }.getOrNull()
        val ssid = info?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }
        // Android hands out a placeholder address when location is off or not allowed.
        val bssid = info?.bssid?.takeIf { it != "02:00:00:00:00:00" }?.let { MacAddress.parse(it) }
        noteCurrent(ssid, bssid)
    }

    /** The network [currentSsid] was read from. */
    private var named: Network? = null

    private fun notePresent(network: Network, up: Boolean) {
        if (up) present.add(network) else present.remove(network)
        onWifi = present.isNotEmpty()
        if (!up && (named == network || present.isEmpty())) noteCurrent(null, null)
    }

    private fun noteCurrent(ssid: String?, bssid: MacAddress?) {
        currentSsid = ssid
        currentBssid = bssid
        (status as? Status.Joined)?.let { if (it.ssid != ssid) status = Status.Idle }
        val (wanted, password) = pending ?: return
        if (ssid != wanted) return
        pending = null
        status = Status.Joined(wanted)
        onJoined?.invoke(wanted, password.orEmpty())
    }

    private fun joinViaSuggestion(ssid: String, passphrase: String?, wep: Boolean, auth: Int): Status {
        if (wep) return Status.Failed(ssid, Reason.WEP_UNSUPPORTED) // suggestions have no WEP
        val manager = wifi ?: return Status.Failed(ssid, Reason.GENERIC)
        val suggestion = suggestionFor(ssid, passphrase, auth) ?: return Status.Failed(ssid, Reason.GENERIC)
        val list = listOf(suggestion)
        manager.removeNetworkSuggestions(list) // so a re-add is not a duplicate
        return when (manager.addNetworkSuggestions(list)) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE,
            -> Status.Suggested(ssid)
            else -> Status.Failed(ssid, Reason.ADD_FAILED)
        }
    }

    /**
     * WPA3-only networks need an SAE passphrase and OWE "enhanced open" its own flag; a WPA2 passphrase covers
     * WPA, WPA2 and WPA2/WPA3 mixed. [auth] is the scan's [cattivole.spectra.core.AuthMode.raw]; -1 when unknown.
     */
    private fun suggestionFor(ssid: String, passphrase: String?, auth: Int): WifiNetworkSuggestion? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
        when {
            auth == 9 -> builder.setIsEnhancedOpen(true)
            passphrase.isNullOrEmpty() -> Unit
            auth == 6 || auth == 11 -> builder.setWpa3Passphrase(passphrase)
            else -> builder.setWpa2Passphrase(passphrase)
        }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun joinLegacy(ssid: String, passphrase: String?, wep: Boolean): Status {
        val manager = wifi ?: return Status.Failed(ssid, Reason.GENERIC)
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            when {
                passphrase.isNullOrEmpty() -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                wep -> {
                    wepKeys[0] = "\"$passphrase\""
                    wepTxKeyIndex = 0
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                    allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
                }
                else -> preSharedKey = "\"$passphrase\""
            }
        }
        val netId = manager.addNetwork(config)
        if (netId == -1) return Status.Failed(ssid, Reason.ADD_FAILED)
        manager.disconnect()
        manager.enableNetwork(netId, true)
        manager.reconnect()
        return if (isOn(ssid)) Status.Joined(ssid) else Status.Joining(ssid)
    }

    @Suppress("DEPRECATION")
    private fun forgetLegacy(manager: WifiManager, ssid: String) {
        val quoted = "\"$ssid\""
        manager.configuredNetworks?.firstOrNull { it.SSID == quoted }?.let { manager.removeNetwork(it.networkId) }
    }

    private companion object {
        /** How long a join is watched before it counts as failed. */
        const val JOIN_WAIT_MS = 20_000L
    }
}
