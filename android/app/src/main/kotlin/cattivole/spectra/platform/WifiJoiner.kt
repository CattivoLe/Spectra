package cattivole.spectra.platform

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Joins the phone itself to a Wi-Fi network, the counterpart of the iOS
 * `WiFiJoiner`. The sensor's own "Join" is separate (it configures the
 * Cardputer over BLE); this configures Android.
 *
 * Android 10+ cannot silently connect a third-party app's chosen network, so it
 * uses a [WifiNetworkSuggestion]: the system joins it when in range and may ask
 * the user to approve the app once. Older releases take a classic
 * [WifiConfiguration] and connect right away.
 */
class WifiJoiner(private val context: Context) {
    sealed interface Status {
        data object Idle : Status
        data class Joining(val ssid: String) : Status
        /** Connected now (legacy path). */
        data class Joined(val ssid: String) : Status
        /** Added as a suggestion; the system connects when the network is in range. */
        data class Suggested(val ssid: String) : Status
        data class Failed(val ssid: String, val reason: Reason) : Status
    }

    enum class Reason { GENERIC, WEP_UNSUPPORTED, ADD_FAILED, PERMISSION }

    var status: Status by mutableStateOf(Status.Idle)
        private set

    private val wifi: WifiManager?
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun isFor(ssid: String): Boolean = when (val s = status) {
        is Status.Joining -> s.ssid == ssid
        is Status.Joined -> s.ssid == ssid
        is Status.Suggested -> s.ssid == ssid
        is Status.Failed -> s.ssid == ssid
        else -> false
    }

    fun join(ssid: String, passphrase: String?, wep: Boolean) {
        status = Status.Joining(ssid)
        status = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                joinViaSuggestion(ssid, passphrase, wep)
            } else {
                joinLegacy(ssid, passphrase, wep)
            }
        } catch (e: SecurityException) {
            Status.Failed(ssid, Reason.PERMISSION)
        } catch (e: Exception) {
            Status.Failed(ssid, Reason.GENERIC)
        }
    }

    fun forget(ssid: String) {
        val manager = wifi ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                suggestionFor(ssid, null, false)?.let { manager.removeNetworkSuggestions(listOf(it)) }
            } else {
                forgetLegacy(manager, ssid)
            }
        } catch (_: Exception) {
            // Best effort; clearing the status is what the user sees.
        }
        if (isFor(ssid)) status = Status.Idle
    }

    private fun joinViaSuggestion(ssid: String, passphrase: String?, wep: Boolean): Status {
        if (wep) return Status.Failed(ssid, Reason.WEP_UNSUPPORTED) // suggestions have no WEP
        val manager = wifi ?: return Status.Failed(ssid, Reason.GENERIC)
        val suggestion = suggestionFor(ssid, passphrase, wep) ?: return Status.Failed(ssid, Reason.GENERIC)
        val list = listOf(suggestion)
        manager.removeNetworkSuggestions(list) // so a re-add is not a duplicate
        return when (manager.addNetworkSuggestions(list)) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE,
            -> Status.Suggested(ssid)
            else -> Status.Failed(ssid, Reason.ADD_FAILED)
        }
    }

    private fun suggestionFor(ssid: String, passphrase: String?, wep: Boolean): WifiNetworkSuggestion? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
        if (!passphrase.isNullOrEmpty()) builder.setWpa2Passphrase(passphrase) // covers WPA2 and WPA2/WPA3 mixed
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
        return Status.Joined(ssid)
    }

    @Suppress("DEPRECATION")
    private fun forgetLegacy(manager: WifiManager, ssid: String) {
        val quoted = "\"$ssid\""
        manager.configuredNetworks?.firstOrNull { it.SSID == quoted }?.let { manager.removeNetwork(it.networkId) }
    }
}
