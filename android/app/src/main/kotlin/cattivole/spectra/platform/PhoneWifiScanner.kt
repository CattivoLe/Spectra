package cattivole.spectra.platform

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.NetworkStore
import cattivole.spectra.core.PhoneWifi

/**
 * The scan iOS cannot do. Android will list nearby access points, including
 * 5 GHz and 6 GHz that the ESP32-S3 never hears, but the system throttles
 * [WifiManager.startScan] to a handful of calls per two minutes and it does
 * not report clients, probes or deauth frames.
 */
class PhoneWifiScanner(
    context: Context,
    private val store: NetworkStore,
) {
    enum class Status { IDLE, SCANNING, THROTTLED, WIFI_OFF, PERMISSION, LOCATION_OFF }

    var status: Status = Status.IDLE
        private set
    var lastResults: Int = 0
        private set
    /** True when the active prefix is wider than a /24 and only that /24 was walked. Unused here. */
    var note: String? = null
        private set

    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)
    private var receiver: BroadcastReceiver? = null
    private var started = false
    private val listeners = mutableListOf<() -> Unit>()

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        val next = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                publishResults()
            }
        }
        receiver = next
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(next, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(next, filter)
        }
        publishResults()
        refresh()
    }

    fun stop() {
        started = false
        receiver?.let {
            try {
                app.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        receiver = null
    }

    @SuppressLint("MissingPermission")
    fun refresh() {
        if (!Permissions.canScanWifi(app)) {
            status = Status.PERMISSION
            publish()
            return
        }
        if (wifi?.isWifiEnabled != true) {
            status = Status.WIFI_OFF
            publish()
            return
        }
        if (Build.VERSION.SDK_INT < 33 && !locationEnabled()) {
            status = Status.LOCATION_OFF
            publishResults()
            return
        }
        val accepted = try {
            wifi.startScan()
        } catch (_: SecurityException) {
            false
        }
        status = if (accepted) Status.SCANNING else Status.THROTTLED
        publishResults()
    }

    @SuppressLint("MissingPermission")
    private fun publishResults() {
        if (!Permissions.canScanWifi(app)) {
            status = Status.PERMISSION
            publish()
            return
        }
        val results = try {
            wifi?.scanResults.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        val readings = results.mapNotNull { it.toReading() }
        lastResults = readings.size
        store.applyPhoneSnapshot(readings)
        if (status == Status.SCANNING) status = Status.IDLE
        note = readings.groupBy { it.band }.entries
            .joinToString(" · ") { "${it.value.size} on ${it.key.label}" }
            .ifEmpty { null }
        publish()
    }

    private fun locationEnabled(): Boolean {
        val manager = app.getSystemService(LocationManager::class.java) ?: return true
        return if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else true
    }

    private fun publish() {
        listeners.toList().forEach { it() }
    }

    private fun ScanResult.toReading(): AccessPointReading? {
        @Suppress("DEPRECATION")
        val ssid = SSID
        val width = when (channelWidth) {
            ScanResult.CHANNEL_WIDTH_40MHZ -> 40
            ScanResult.CHANNEL_WIDTH_80MHZ -> 80
            ScanResult.CHANNEL_WIDTH_160MHZ -> 160
            ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 80
            else -> if (Build.VERSION.SDK_INT >= 34 && channelWidth == 5) 320 else 20
        }
        return PhoneWifi.reading(
            bssid = BSSID ?: return null,
            ssid = ssid,
            rssi = level,
            frequencyMHz = frequency,
            capabilities = capabilities ?: "",
            widthMHz = width,
            centreFrequencyMHz = centerFreq0,
        )
    }
}
