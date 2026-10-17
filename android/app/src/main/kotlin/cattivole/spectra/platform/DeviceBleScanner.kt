package cattivole.spectra.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import cattivole.spectra.core.BleAdvertisement
import cattivole.spectra.core.BleDevice
import cattivole.spectra.core.BleFields
import cattivole.spectra.core.MacAddress

/**
 * Every advertisement the phone can hear, plus an optional classic inquiry.
 * Android reports the hardware address and its type. Classic discovery is
 * separate because starting it pauses LE scanning on many controllers, so
 * it stays off until the user asks.
 */
class DeviceBleScanner(
    context: Context,
    private val radio: BleRadio,
    private val link: SensorLink,
) {
    enum class Availability { STARTING, POWERED_OFF, UNAUTHORIZED, UNSUPPORTED }

    sealed class State {
        data class Unavailable(val why: Availability) : State()
        data object Idle : State()
        data object Scanning : State()
    }

    var state: State = State.Unavailable(Availability.STARTING)
        private set
    var devices: Map<String, BleDevice> = emptyMap()
        private set
    var now: Long = System.currentTimeMillis()
        private set
    var classicInquiry: Boolean = false
        private set
    var classicNote: String? = null
        private set

    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val rows = linkedMapOf<String, BleDevice>()
    private var pausedByUser = false
    private var clock: android.os.Handler? = null
    private var classicReceiver: BroadcastReceiver? = null
    private val listeners = mutableListOf<() -> Unit>()
    private val ticker = object : Runnable {
        override fun run() {
            now = System.currentTimeMillis()
            if (onTab) publish()
            clock?.postDelayed(this, 1000)
        }
    }

    /** Busy airwaves deliver hundreds of packets a second; the list redraws a few times a second. */
    private var publishPending = false
    private val flush = Runnable {
        publishPending = false
        devices = rows.toMap()
        now = System.currentTimeMillis()
        publish()
    }

    private fun publishSoon() {
        if (publishPending) return
        publishPending = true
        ensureClock()
        clock?.postDelayed(flush, 250)
    }

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    init {
        radio.addListener { result -> remember(result) }
    }

    fun start() {
        pausedByUser = false
        onTab = true
        ensureClock()
        begin()
    }

    fun stop() {
        pausedByUser = true
        link.noteDeviceScan(false)
        if (state is State.Scanning) state = State.Idle
        stopClassic()
        publish()
    }

    /** The Bluetooth tab is showing. A scan the user stopped stays stopped. */
    fun resumeUnlessPaused() {
        onTab = true
        if (!pausedByUser) {
            start()
        } else {
            ensureClock()
            if (state is State.Unavailable) state = availability()?.let { State.Unavailable(it) } ?: State.Idle
            publish()
        }
    }

    /**
     * The Bluetooth tab went away. Opening a device stays inside the tab, so
     * a running scan keeps running in the detail and Stop stays stopped.
     */
    fun leave() {
        onTab = false
        link.noteDeviceScan(false)
        if (state is State.Scanning) state = State.Idle
        stopClassic()
        publish()
    }

    /** Bluetooth was switched on or off, or a permission changed. */
    fun availabilityChanged() {
        val missing = availability()
        when {
            missing != null -> {
                link.noteDeviceScan(false)
                stopClassic()
                state = State.Unavailable(missing)
            }
            onTab && !pausedByUser -> {
                begin()
                return
            }
            state is State.Unavailable -> state = State.Idle
        }
        publish()
    }

    val isScanning: Boolean get() = state is State.Scanning
    val nearbyCount: Int get() = devices.values.count { it.isNearby(now) }

    fun clear() {
        rows.clear()
        devices = emptyMap()
        publish()
    }

    fun setClassic(on: Boolean) {
        classicInquiry = on
        if (on && state is State.Scanning) startClassic() else stopClassic()
        classicNote = if (on) {
            "Classic inquiry pauses low-energy scanning on many phones until it finishes."
        } else {
            null
        }
        publish()
    }

    /** The app went to the background. The scan comes back with it if it was running. */
    fun suspend() {
        val wasScanning = state is State.Scanning
        link.noteDeviceScan(false)
        if (wasScanning) state = State.Idle
        stopClassic()
        resumeOnForeground = wasScanning
        publish()
    }

    fun resumeIfWanted() {
        if (!resumeOnForeground || pausedByUser || !onTab) return
        resumeOnForeground = false
        begin()
    }

    private var resumeOnForeground = false
    private var onTab = false

    private fun availability(): Availability? = when {
        adapter == null -> Availability.UNSUPPORTED
        !Permissions.canScanBluetooth(app) -> Availability.UNAUTHORIZED
        adapter.isEnabled != true -> Availability.POWERED_OFF
        else -> null
    }

    private fun begin() {
        val missing = availability()
        if (missing != null) {
            state = State.Unavailable(missing)
        } else {
            state = State.Scanning
            link.noteDeviceScan(true)
            if (classicInquiry) startClassic()
        }
        publish()
    }

    @SuppressLint("MissingPermission")
    private fun remember(result: ScanResult) {
        if (state !is State.Scanning) return
        val address = result.device.address ?: return
        val kind = addressKind(result.device)
        val seen = System.currentTimeMillis()
        val device = rows.getOrPut(address) {
            BleDevice(address, kind, result.rssi, seen, BleDevice.Transport.LE)
        }
        val name = result.scanRecord?.deviceName ?: result.device.name
        val fields = result.scanRecord.toFields().copy(isConnectable = result.isConnectable)
        device.absorb(BleAdvertisement.parse(fields), name, result.rssi, seen)
        publishSoon()
    }

    private fun ScanRecord?.toFields(): BleFields {
        if (this == null) return BleFields()
        val sparse = manufacturerSpecificData
        val manufacturer = if (sparse != null && sparse.size() > 0) {
            val id = sparse.keyAt(0)
            val payload = sparse.valueAt(0)
            byteArrayOf((id and 0xFF).toByte(), ((id shr 8) and 0xFF).toByte()) + payload
        } else {
            null
        }
        val services = serviceUuids?.map { it.uuid.toString() } ?: emptyList()
        val data = mutableMapOf<String, ByteArray>()
        serviceData?.forEach { (uuid, bytes) -> data[uuid.uuid.toString()] = bytes }
        val tx = txPowerLevel
        return BleFields(
            localName = deviceName,
            manufacturerData = manufacturer,
            serviceUuids = services,
            serviceData = data,
            txPower = tx.takeIf { it != Int.MIN_VALUE },
        )
    }

    @SuppressLint("MissingPermission")
    private fun addressKind(device: BluetoothDevice): String {
        if (Build.VERSION.SDK_INT >= 35) {
            return when (device.addressType) {
                BluetoothDevice.ADDRESS_TYPE_PUBLIC -> "Public"
                BluetoothDevice.ADDRESS_TYPE_RANDOM -> "Random"
                else -> "Unknown"
            }
        }
        return MacAddress.parse(device.address)?.bluetoothAddressKind ?: "Unknown"
    }

    @SuppressLint("MissingPermission")
    private fun startClassic() {
        if (classicReceiver != null || adapter == null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                    // An inquiry lasts about 12 seconds. Keep it going while the tab scans.
                    if (classicInquiry && state is State.Scanning) {
                        try {
                            adapter?.startDiscovery()
                        } catch (_: SecurityException) {
                        }
                    }
                    return
                }
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, -100).toInt()
                val klass = device.bluetoothClass?.let { classicLabel(it) }
                val address = device.address ?: return
                val seen = System.currentTimeMillis()
                val row = rows.getOrPut(address) {
                    BleDevice(address, "Classic", rssi, seen, BleDevice.Transport.CLASSIC)
                }
                row.transportNote(device.name, klass, rssi, seen)
                publishSoon()
            }
        }
        classicReceiver = receiver
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        try {
            adapter.startDiscovery()
        } catch (_: SecurityException) {
            stopClassic()
        }
    }

    private fun stopClassic() {
        classicReceiver?.let {
            try {
                app.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        classicReceiver = null
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
    }

    private fun classicLabel(klass: BluetoothClass): String = when (klass.majorDeviceClass) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio"
        BluetoothClass.Device.Major.COMPUTER -> "Computer"
        BluetoothClass.Device.Major.PHONE -> "Phone"
        BluetoothClass.Device.Major.PERIPHERAL -> "Peripheral"
        BluetoothClass.Device.Major.IMAGING -> "Imaging"
        BluetoothClass.Device.Major.WEARABLE -> "Wearable"
        BluetoothClass.Device.Major.TOY -> "Toy"
        else -> "Classic"
    }

    private fun ensureClock() {
        if (clock != null) return
        clock = android.os.Handler(android.os.Looper.getMainLooper())
        clock?.postDelayed(ticker, 1000)
    }

    fun shutdown() {
        clock?.removeCallbacks(ticker)
        clock?.removeCallbacks(flush)
        stopClassic()
        link.noteDeviceScan(false)
    }

    private fun publish() {
        listeners.toList().forEach { it() }
    }
}

private fun BleDevice.transportNote(name: String?, kind: String?, rssi: Int, seen: Long) {
    if (!name.isNullOrBlank()) peripheralName = name
    deviceClass = kind
    absorb(BleAdvertisement.parse(BleFields()), name, rssi, seen)
}
