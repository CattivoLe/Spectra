package cattivole.spectra.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import cattivole.spectra.core.SensorProtocol

/**
 * One LE scan for the whole app. Android merges scan requests inside a
 * process poorly when they use different filters, so the sensor search and
 * the Bluetooth tab share this radio. A filtered scan is used only while
 * nothing else wants every advertisement.
 */
class BleRadio(context: Context) {
    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? =
        app.getSystemService(BluetoothManager::class.java)?.adapter

    var onFailure: ((Int) -> Unit)? = null
    private val listeners = mutableListOf<(ScanResult) -> Unit>()
    private val adapterListeners = mutableListOf<() -> Unit>()

    fun addListener(listener: (ScanResult) -> Unit) {
        listeners.add(listener)
    }

    /** Called on the main thread when Bluetooth is switched on or off. */
    fun onAdapterChange(listener: () -> Unit) {
        adapterListeners.add(listener)
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val now = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (now != BluetoothAdapter.STATE_ON && now != BluetoothAdapter.STATE_OFF) return
            // A scan started before the radio was on never delivers anything.
            restart(force = true)
            adapterListeners.toList().forEach { it() }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(adapterReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(adapterReceiver, filter)
        }
    }

    /** Permissions changed: try again with whatever is wanted. */
    fun retry() = restart(force = true)

    private var sensorSearch = false
    private var deviceScan = false
    private var callback: ScanCallback? = null
    private var mode = Mode.NONE

    private enum class Mode { NONE, SENSOR, ALL }

    val isSupported: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun setWants(sensor: Boolean, devices: Boolean) {
        sensorSearch = sensor
        deviceScan = devices
        restart()
    }

    fun stop() {
        sensorSearch = false
        deviceScan = false
        restart()
        try {
            app.unregisterReceiver(adapterReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    /**
     * Android silently stops delivering results to an app that starts more
     * than five scans in 30 seconds, so an unchanged request keeps the scan
     * that is already running.
     */
    @SuppressLint("MissingPermission")
    private fun restart(force: Boolean = false) {
        val wanted = when {
            deviceScan -> Mode.ALL
            sensorSearch -> Mode.SENSOR
            else -> Mode.NONE
        }
        if (!force && wanted == mode && (callback != null || wanted == Mode.NONE)) return
        mode = wanted
        val scanner = adapter?.bluetoothLeScanner
        callback?.let { current ->
            try {
                scanner?.stopScan(current)
            } catch (_: Exception) {
                // Not permitted any more, or the adapter is already off.
            }
        }
        callback = null
        if (!sensorSearch && !deviceScan) return
        if (!Permissions.canScanBluetooth(app) || scanner == null || adapter?.isEnabled != true) return
        val filters = if (sensorSearch && !deviceScan) {
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SensorProtocol.SERVICE)).build())
        } else {
            emptyList()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val next = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                listeners.toList().forEach { it(result) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result -> listeners.toList().forEach { it(result) } }
            }

            override fun onScanFailed(errorCode: Int) {
                onFailure?.invoke(errorCode)
            }
        }
        callback = next
        try {
            scanner.startScan(filters, settings, next)
        } catch (_: Exception) {
            callback = null
        }
    }
}
