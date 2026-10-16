package cattivole.spectra.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
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

    fun addListener(listener: (ScanResult) -> Unit) {
        listeners.add(listener)
    }

    private var sensorSearch = false
    private var deviceScan = false
    private var callback: ScanCallback? = null

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
    }

    @SuppressLint("MissingPermission")
    private fun restart() {
        val scanner = adapter?.bluetoothLeScanner
        callback?.let { current ->
            try {
                scanner?.stopScan(current)
            } catch (_: SecurityException) {
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
        } catch (_: SecurityException) {
            callback = null
        }
    }
}
