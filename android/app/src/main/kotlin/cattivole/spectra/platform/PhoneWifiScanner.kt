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
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
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
    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var callback: WifiManager.ScanResultsCallback? = null
    private var started = false
    private val listeners = mutableListOf<() -> Unit>()
    private val settle = Runnable {
        if (status == Status.SCANNING) publishResults(finished = true)
    }

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
                publishResults(finished = true)
            }
        }
        receiver = next
        // The scan-results broadcast comes from the system. NOT_EXPORTED drops
        // it on several releases, so the list stayed empty with no sensor.
        ContextCompat.registerReceiver(app, next, filter, ContextCompat.RECEIVER_EXPORTED)
        if (Build.VERSION.SDK_INT >= 30 && wifi != null) {
            val results = object : WifiManager.ScanResultsCallback() {
                override fun onScanResultsAvailable() {
                    publishResults(finished = true)
                }
            }
            callback = results
            try {
                wifi.registerScanResultsCallback(ContextCompat.getMainExecutor(app), results)
            } catch (_: SecurityException) {
                callback = null
            }
        }
        refresh()
    }

    fun stop() {
        started = false
        main.removeCallbacks(settle)
        receiver?.let {
            try {
                app.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        receiver = null
        if (Build.VERSION.SDK_INT >= 30) {
            callback?.let { registered ->
                try {
                    wifi?.unregisterScanResultsCallback(registered)
                } catch (_: SecurityException) {
                }
            }
        }
        callback = null
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
        if (!locationEnabled()) {
            status = Status.LOCATION_OFF
            publish()
            return
        }
        val accepted = try {
            wifi.startScan()
        } catch (_: SecurityException) {
            false
        }
        status = if (accepted) Status.SCANNING else Status.THROTTLED
        if (accepted) {
            publish()
            // The completion broadcast is the real result. Reading the cache
            // here is the previous pass, often empty, and used to mark the
            // scan finished before any network arrived.
            main.removeCallbacks(settle)
            main.postDelayed(settle, 8_000)
        } else {
            publishResults(finished = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun publishResults(finished: Boolean) {
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
        // An empty read while the radio is still sweeping must not age out
        // networks the previous pass already found.
        if (readings.isNotEmpty() || finished) store.applyPhoneSnapshot(readings)
        if (finished) {
            main.removeCallbacks(settle)
            if (status == Status.SCANNING) {
                status = if (readings.isEmpty() && !locationEnabled()) Status.LOCATION_OFF else Status.IDLE
            }
        }
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
        val ssid = networkName()
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

    private fun ScanResult.networkName(): String? {
        if (Build.VERSION.SDK_INT >= 33) {
            val text = wifiSsid?.toString().orEmpty()
            if (text.isEmpty() || text == "<unknown ssid>") return null
            return text.removeSurrounding("\"")
        }
        @Suppress("DEPRECATION")
        return SSID
    }
}
