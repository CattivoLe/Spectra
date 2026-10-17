package cattivole.spectra.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import cattivole.spectra.core.HistoryStore
import cattivole.spectra.core.DemoSensor
import cattivole.spectra.core.FirmwareUpdate
import cattivole.spectra.core.FirmwareUpdateProgress
import cattivole.spectra.core.FirmwareUpload
import cattivole.spectra.core.NetworkStore
import cattivole.spectra.core.SensorInfo
import cattivole.spectra.core.SensorProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * GATT client for a Spectra sensor. Android does not raise the ATT MTU on
 * its own the way iOS does, so the link asks for 517 and sizes firmware
 * chunks from whatever the sensor actually grants. The last sensor is
 * remembered by its hardware address, which Android exposes, and the next
 * launch connects to that address directly.
 */
class SensorLink(
    context: Context,
    private val store: NetworkStore,
    private val radio: BleRadio,
    private val history: HistoryStore,
) {
    sealed class State {
        data object Starting : State()
        data object PoweredOff : State()
        data object Unauthorized : State()
        data object Unsupported : State()
        data object Idle : State()
        data object Searching : State()
        data class Connecting(val name: String) : State()
        data class Connected(val name: String) : State()
        data object Demo : State()
    }

    data class Discovered(val address: String, val name: String, var rssi: Int, var lastSeen: Long)

    var state: State = State.Starting
        private set
    var discovered: List<Discovered> = emptyList()
        private set
    var info: SensorInfo? = null
        private set
    var batteryLevel: Int? = null
        private set
    var isCharging: Boolean? = null
        private set
    var firmwareUpdate: FirmwareUpdateProgress? = null
        private set
    var liveInterval: Int = 30
        private set

    val isConnected: Boolean get() = state is State.Connected || state is State.Demo

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("spectra", Context.MODE_PRIVATE)
    private val adapter: BluetoothAdapter? =
        app.getSystemService(BluetoothManager::class.java)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val found = linkedMapOf<String, Discovered>()

    private var gatt: BluetoothGatt? = null
    private var control: BluetoothGattCharacteristic? = null
    private var firmwareChar: BluetoothGattCharacteristic? = null
    private var results: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var userDisconnected = false
    private var shouldReconnect = false
    private var writing = false
    /** Each write keeps its own characteristic: a control command can queue behind firmware chunks. */
    private val writes = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var rebootJob: Job? = null
    private var upload: FirmwareUpload? = null
    private var updateJob: Job? = null
    private var firmwareCancel = false
    private var demo: DemoSensor? = null
    private var demoJob: Job? = null

    private val listeners = mutableListOf<() -> Unit>()

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    init {
        val saved = prefs.getInt(KEY_INTERVAL, 30)
        liveInterval = if (saved in liveIntervals) saved else 30
        radio.addListener { result -> onScan(result) }
        radio.onAdapterChange { adapterChanged() }
        refreshAvailability(connectSaved = true)
    }

    /** Bluetooth was switched on or off in the system. */
    private fun adapterChanged() {
        if (state is State.Demo) return
        if (adapter?.isEnabled != true) {
            closeGatt()
            resetSession()
            state = State.PoweredOff
            publish()
        } else {
            refreshAvailability(connectSaved = true)
        }
    }

    fun refreshAvailability(connectSaved: Boolean = false) {
        // The demo needs no radio; a missing permission must not end it.
        if (state is State.Demo) return
        when {
            adapter == null -> state = State.Unsupported
            !Permissions.canScanBluetooth(app) -> state = State.Unauthorized
            adapter.isEnabled != true -> state = State.PoweredOff
            state is State.Starting || state is State.Unauthorized || state is State.PoweredOff -> {
                state = State.Idle
                if (connectSaved) connectSaved()
            }
        }
        publish()
    }

    fun startSearching() {
        if (!readyForRadio()) return
        userDisconnected = false
        found.clear()
        discovered = emptyList()
        state = State.Searching
        radio.setWants(sensor = true, devices = radioKeepsDevices())
        publish()
    }

    fun stopSearching() {
        if (state !is State.Searching) return
        radio.setWants(sensor = false, devices = radioKeepsDevices())
        state = State.Idle
        publish()
    }

    /**
     * [background] keeps the request pending until the sensor is in range,
     * which is how the last sensor and a dropped link come back by themselves.
     */
    @SuppressLint("MissingPermission")
    fun connect(address: String, name: String = address, background: Boolean = false) {
        if (!readyForRadio()) return
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return
        userDisconnected = false
        closeGatt()
        state = State.Connecting(name)
        radio.setWants(sensor = false, devices = radioKeepsDevices())
        publish()
        gatt = device.connectGatt(app, background, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userDisconnected = true
        shouldReconnect = false
        rebootJob?.cancel()
        prefs.edit().remove(KEY_SENSOR).remove(KEY_SENSOR_NAME).apply()
        closeGatt()
        resetSession()
        state = State.Idle
        publish()
    }

    /** What belongs to one connection: forgotten when it ends. */
    private fun resetSession() {
        info = null
        batteryLevel = null
        isCharging = null
        store.abandonScan()
        if (upload != null) failUpdate(FirmwareUpdate.TransferError.LINK_LOST.message)
    }

    fun scanNow() {
        if (demo != null) demo?.scan() else enqueue(control, SensorProtocol.Command.ScanOnce.data)
    }

    /** The firmware file could not be read. */
    fun failFirmwareUpdate(message: String) {
        firmwareUpdate = FirmwareUpdateProgress(FirmwareUpdateProgress.Stage.Failed(message))
        publish()
    }

    fun setLiveInterval(seconds: Int) {
        liveInterval = seconds
        prefs.edit().putInt(KEY_INTERVAL, seconds).apply()
        enqueue(control, SensorProtocol.Command.SetInterval(seconds).data)
        demo?.intervalSeconds = seconds
        publish()
    }

    fun setDemo(on: Boolean) {
        if (on) {
            if (gatt != null) disconnect()
            radio.setWants(sensor = false, devices = radioKeepsDevices())
            val sensor = DemoSensor(
                emit = { receive(it) },
                schedule = { delayMs, block ->
                    demoJob?.cancel()
                    demoJob = scope.launch {
                        delay(delayMs)
                        block()
                    }
                },
                cancelSchedule = { demoJob?.cancel() },
            )
            sensor.intervalSeconds = liveInterval
            demo = sensor
            info = SensorInfo(SensorProtocol.VERSION, "Demo", "Simulated", listOf("2.4"), liveInterval)
            batteryLevel = 72
            isCharging = false
            state = State.Demo
            sensor.scan()
        } else {
            demo?.stop()
            demo = null
            demoJob?.cancel()
            info = null
            batteryLevel = null
            isCharging = null
            store.abandonScan()
            state = if (adapter?.isEnabled == true) State.Idle else State.PoweredOff
        }
        publish()
    }

    fun installFirmware(image: ByteArray) {
        if (firmwareUpdate?.isRunning == true) return
        val error = FirmwareUpdate.validate(image)
        if (error != null) {
            firmwareUpdate = FirmwareUpdateProgress(FirmwareUpdateProgress.Stage.Failed(error.message))
            publish()
            return
        }
        firmwareCancel = false
        firmwareUpdate = FirmwareUpdateProgress(FirmwareUpdateProgress.Stage.Preparing)
        publish()
        updateJob = scope.launch {
            if (!waitUntilReady()) {
                val reason = if (firmwareCancel) FirmwareUpdate.TransferError.CANCELLED else FirmwareUpdate.TransferError.NOT_CONNECTED
                firmwareUpdate = FirmwareUpdateProgress(FirmwareUpdateProgress.Stage.Failed(reason.message))
                publish()
                return@launch
            }
            val chunk = minOf(FirmwareUpdate.MAX_CHUNK_BYTES, (mtu - 3 - 5).coerceAtLeast(1))
            val session = FirmwareUpload(image, chunk)
            upload = session
            enqueue(firmwareChar, session.begin())
            firmwareUpdate = FirmwareUpdateProgress(FirmwareUpdateProgress.Stage.Uploading(0, image.size))
            publish()
        }
    }

    fun cancelFirmwareUpdate() {
        firmwareCancel = true
        val session = upload
        if (session != null) {
            enqueue(firmwareChar, session.abortCommand)
        } else if (firmwareUpdate?.stage is FirmwareUpdateProgress.Stage.Preparing) {
            failUpdate(FirmwareUpdate.TransferError.CANCELLED.message)
        }
    }

    fun dismissFirmwareUpdate() {
        if (firmwareUpdate?.isRunning == true) return
        firmwareUpdate = null
        publish()
    }

    fun close() {
        demo?.stop()
        updateJob?.cancel()
        rebootJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        closeGatt()
    }

    fun noteDeviceScan(active: Boolean) {
        deviceScanWanted = active
        radio.setWants(sensor = state is State.Searching, devices = active)
    }

    private var deviceScanWanted = false

    private fun radioKeepsDevices(): Boolean = deviceScanWanted

    private fun readyForRadio(): Boolean {
        refreshAvailability()
        return state is State.Idle || state is State.Searching || state is State.Connected || state is State.Connecting
    }

    @SuppressLint("MissingPermission")
    private fun connectSaved() {
        val address = prefs.getString(KEY_SENSOR, null) ?: return
        if (userDisconnected || demo != null || gatt != null) return
        connect(address, prefs.getString(KEY_SENSOR_NAME, null) ?: "Spectra", background = true)
    }

    private fun onScan(result: ScanResult) {
        val record = result.scanRecord
        val name = record?.deviceName ?: result.device.name
        val services = record?.serviceUuids?.map { it.uuid } ?: emptyList()
        val spectra = services.any { it == SensorProtocol.SERVICE } || (name?.startsWith("Spectra-") == true)
        if (!spectra || state !is State.Searching) return
        val address = result.device.address ?: return
        if (shouldReconnect && address == prefs.getString(KEY_SENSOR, null)) {
            // Back after a reboot or out of range: no need to pick it again.
            connect(address, name ?: "Spectra")
            return
        }
        val row = found.getOrPut(address) {
            Discovered(address, name ?: "Spectra", result.rssi, System.currentTimeMillis())
        }
        row.rssi = result.rssi
        row.lastSeen = System.currentTimeMillis()
        if (!name.isNullOrBlank()) {
            found[address] = row.copy(name = name).also {
                it.rssi = row.rssi
                it.lastSeen = row.lastSeen
            }
        }
        discovered = found.values.sortedByDescending { it.rssi }
        publish()
    }

    private suspend fun waitUntilReady(): Boolean {
        enqueue(control, SensorProtocol.Command.SetInterval(0).data)
        val start = System.currentTimeMillis()
        while (store.isScanning && System.currentTimeMillis() - start < 20_000) {
            if (firmwareCancel) return false
            delay(200)
        }
        return state is State.Connected && firmwareChar != null && !firmwareCancel
    }

    private fun receive(data: ByteArray) {
        FirmwareUpdate.Status.decode(data)?.let { status ->
            val session = upload
            if (session != null) {
                val next = session.handle(status)
                when (val phase = session.phase) {
                    is FirmwareUpload.Phase.Failed -> failUpdate(phase.failure.message)
                    FirmwareUpload.Phase.Succeeded -> {
                        firmwareUpdate = FirmwareUpdateProgress(FirmwareUpdateProgress.Stage.Rebooting)
                        upload = null
                        waitForReboot()
                    }
                    else -> if (next != null) enqueue(firmwareChar, next)
                }
                publish()
            }
            return
        }
        val message = SensorProtocol.decode(data) ?: return
        if (message is SensorProtocol.Message.Saved) {
            if (history.ingest(message, sensorName())) {
                enqueue(control, SensorProtocol.Command.AckSaved(message.captureId).data)
            }
            publish()
            return
        }
        if (message is SensorProtocol.Message.Battery) {
            batteryLevel = message.level
            isCharging = message.isCharging
        }
        store.handle(message)
        publish()
    }

    /**
     * The sensor drops the link to boot the new image. Success is the same
     * sensor back, connected, with its info read again.
     */
    private fun waitForReboot() {
        shouldReconnect = true
        rebootJob?.cancel()
        rebootJob = scope.launch {
            var sawDrop = false
            val deadline = System.currentTimeMillis() + 45_000
            while (System.currentTimeMillis() < deadline) {
                when (val current = state) {
                    is State.Connecting, State.Searching, State.PoweredOff -> sawDrop = true
                    State.Idle -> {
                        sawDrop = true
                        val saved = prefs.getString(KEY_SENSOR, null)
                        if (saved != null) connect(saved, prefs.getString(KEY_SENSOR_NAME, null) ?: "Spectra", background = true)
                    }
                    is State.Connected -> if (sawDrop && info != null) {
                        shouldReconnect = false
                        firmwareUpdate = FirmwareUpdateProgress(FirmwareUpdateProgress.Stage.Succeeded(info?.fw ?: current.name))
                        publish()
                        return@launch
                    }
                    else -> Unit
                }
                delay(250)
            }
            firmwareUpdate = FirmwareUpdateProgress(
                FirmwareUpdateProgress.Stage.Failed("The sensor rebooted, but the app could not reconnect. It is looking for the Cardputer again."),
            )
            publish()
        }
    }

    private fun sensorName(): String = (state as? State.Connected)?.name ?: "Cardputer"

    private fun enqueue(characteristic: BluetoothGattCharacteristic?, bytes: ByteArray) {
        if (characteristic == null || gatt == null || state !is State.Connected) return
        writes.addLast(characteristic to bytes)
        drain()
    }

    @SuppressLint("MissingPermission")
    private fun drain() {
        if (writing) return
        val gatt = gatt ?: return
        val (characteristic, bytes) = writes.removeFirstOrNull() ?: return
        writing = true
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (!started) {
            writing = false
            failUpdate(FirmwareUpdate.TransferError.LINK_LOST.message)
        }
    }

    private fun failUpdate(message: String) {
        if (upload == null && firmwareUpdate?.isRunning != true) return
        upload = null
        firmwareUpdate = FirmwareUpdateProgress(FirmwareUpdateProgress.Stage.Failed(message))
        // The update paused automatic scans; give them back.
        enqueue(control, SensorProtocol.Command.SetInterval(liveInterval).data)
        publish()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        gatt?.close()
        gatt = null
        control = null
        firmwareChar = null
        results = null
        writes.clear()
        writing = false
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                if (gatt != this@SensorLink.gatt) {
                    // A link that was already replaced or closed.
                    gatt.close()
                    return@launch
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    gatt.discoverServices()
                    return@launch
                }
                val wasConnected = state is State.Connected
                val device = gatt.device
                closeGatt()
                resetSession()
                when {
                    state is State.Demo || userDisconnected -> Unit
                    wasConnected || shouldReconnect -> {
                        // Out of range or rebooting: keep a request pending, which
                        // Android completes whenever the sensor comes back.
                        val name = (state as? State.Connected)?.name ?: prefs.getString(KEY_SENSOR_NAME, null) ?: "Spectra"
                        state = State.Connecting(name)
                        publish()
                        // A failure can come back at once; do not spin on it.
                        delay(800)
                        if (state is State.Connecting && this@SensorLink.gatt == null && !userDisconnected) {
                            connect(device.address, name, background = true)
                        }
                    }
                    else -> state = State.Idle
                }
                publish()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            scope.launch {
                val service = gatt.getService(SensorProtocol.SERVICE)
                if (service == null) {
                    disconnect()
                    return@launch
                }
                control = service.getCharacteristic(SensorProtocol.CONTROL)
                results = service.getCharacteristic(SensorProtocol.RESULTS)
                firmwareChar = service.getCharacteristic(SensorProtocol.FIRMWARE)
                if (!gatt.requestMtu(517)) readInfo(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) this@SensorLink.mtu = mtu
                readInfo(gatt)
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            scope.launch { onInfo(gatt, characteristic, value) }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            scope.launch { onInfo(gatt, characteristic, value) }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            scope.launch {
                if (gatt != this@SensorLink.gatt) return@launch
                val name = gatt.device.name ?: (state as? State.Connecting)?.name ?: gatt.device.address
                prefs.edit()
                    .putString(KEY_SENSOR, gatt.device.address)
                    .putString(KEY_SENSOR_NAME, name)
                    .apply()
                state = State.Connected(name ?: "Spectra")
                radio.setWants(sensor = false, devices = radioKeepsDevices())
                // The firmware forgets the interval on disconnect, so set it every time.
                enqueue(control, SensorProtocol.Command.SetInterval(liveInterval).data)
                if (liveInterval == 0) enqueue(control, SensorProtocol.Command.ScanOnce.data)
                publish()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            scope.launch {
                writing = false
                if (characteristic.uuid == SensorProtocol.FIRMWARE && status == BluetoothGatt.GATT_SUCCESS) {
                    val next = upload?.onWriteCompleted()
                    if (next != null) {
                        val sent = upload?.offset ?: 0
                        val total = upload?.image?.size ?: 0
                        firmwareUpdate = FirmwareUpdateProgress(FirmwareUpdateProgress.Stage.Uploading(sent, total))
                        writes.addFirst(characteristic to next)
                    } else if (upload?.phase is FirmwareUpload.Phase.WaitDone) {
                        firmwareUpdate = FirmwareUpdateProgress(FirmwareUpdateProgress.Stage.Finishing)
                    }
                }
                drain()
                publish()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            scope.launch { receive(value) }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            scope.launch { receive(value) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onInfo(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == SensorProtocol.INFO) {
            val parsed = SensorInfo.decode(value.toString(Charsets.UTF_8))
            info = parsed
            if (parsed?.batteryLevel != null) batteryLevel = parsed.batteryLevel
            if (parsed?.isCharging != null) isCharging = parsed.isCharging
        }
        subscribe(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun readInfo(gatt: BluetoothGatt) {
        val infoChar = gatt.getService(SensorProtocol.SERVICE)?.getCharacteristic(SensorProtocol.INFO)
        if (infoChar == null || !gatt.readCharacteristic(infoChar)) subscribe(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(gatt: BluetoothGatt) {
        val characteristic = results ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD) ?: return
        val payload = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, payload)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = payload
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun publish() {
        listeners.toList().forEach { it() }
    }

    companion object {
        val liveIntervals = listOf(0, 10, 30, 60, 120, 180, 240)

        fun intervalTitle(seconds: Int): String = when {
            seconds <= 0 -> "Only when asked"
            seconds % 60 == 0 -> "Every ${seconds / 60} min"
            else -> "Every $seconds s"
        }
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val KEY_SENSOR = "lastSensor"
        private const val KEY_SENSOR_NAME = "lastSensorName"
        private const val KEY_INTERVAL = "liveInterval"
    }
}
