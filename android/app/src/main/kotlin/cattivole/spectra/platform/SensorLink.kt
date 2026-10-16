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
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
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
    var liveInterval: Int = 5
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
    private val writes = ArrayDeque<ByteArray>()
    private var writeTarget: BluetoothGattCharacteristic? = null
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
        liveInterval = prefs.getInt(KEY_INTERVAL, 5)
        radio.addListener { result -> onScan(result) }
        refreshAvailability(connectSaved = true)
    }

    fun refreshAvailability(connectSaved: Boolean = false) {
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

    @SuppressLint("MissingPermission")
    fun connect(address: String, name: String = address) {
        if (!readyForRadio()) return
        val device = adapter?.getRemoteDevice(address) ?: return
        userDisconnected = false
        shouldReconnect = false
        closeGatt()
        state = State.Connecting(name)
        radio.setWants(sensor = false, devices = radioKeepsDevices())
        publish()
        gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userDisconnected = true
        shouldReconnect = false
        prefs.edit().remove(KEY_SENSOR).apply()
        closeGatt()
        state = State.Idle
        info = null
        publish()
    }

    fun scanNow() {
        if (demo != null) demo?.scan() else enqueue(control, SensorProtocol.Command.ScanOnce.data)
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
        upload?.let { enqueue(firmwareChar, it.abortCommand) }
    }

    fun dismissFirmwareUpdate() {
        if (firmwareUpdate?.isRunning == true) return
        firmwareUpdate = null
        publish()
    }

    fun close() {
        demo?.stop()
        updateJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        closeGatt()
    }

    fun noteDeviceScan(active: Boolean) {
        deviceScanWanted = active
        if (state is State.Searching || active) {
            radio.setWants(sensor = state is State.Searching, devices = active)
        }
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
        if (userDisconnected || demo != null) return
        connect(address, "Spectra")
    }

    private fun onScan(result: ScanResult) {
        val record = result.scanRecord
        val name = record?.deviceName ?: result.device.name
        val services = record?.serviceUuids?.map { it.uuid } ?: emptyList()
        val spectra = services.any { it == SensorProtocol.SERVICE } || (name?.startsWith("Spectra-") == true)
        if (!spectra || state !is State.Searching) return
        val address = result.device.address ?: return
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
                        shouldReconnect = true
                        upload = null
                    }
                    else -> if (next != null) enqueue(firmwareChar, next)
                }
                publish()
            }
            return
        }
        val message = SensorProtocol.decode(data) ?: return
        if (message is SensorProtocol.Message.Battery) {
            batteryLevel = message.level
            isCharging = message.isCharging
        }
        store.handle(message)
        publish()
    }

    private fun enqueue(characteristic: BluetoothGattCharacteristic?, bytes: ByteArray) {
        if (characteristic == null || gatt == null || state !is State.Connected) return
        writes.addLast(bytes)
        writeTarget = characteristic
        drain()
    }

    @SuppressLint("MissingPermission")
    private fun drain() {
        if (writing) return
        val gatt = gatt ?: return
        val characteristic = writeTarget ?: return
        val bytes = writes.removeFirstOrNull() ?: return
        writing = true
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
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
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    this@SensorLink.gatt = gatt
                    gatt.discoverServices()
                } else {
                    closeGatt()
                    store.abandonScan()
                    if (shouldReconnect && !userDisconnected) {
                        state = State.Searching
                        radio.setWants(sensor = true, devices = deviceScanWanted)
                    } else if (state !is State.Demo) {
                        state = State.Idle
                    }
                    publish()
                }
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
                gatt.requestMtu(517)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) this@SensorLink.mtu = mtu
                val infoChar = gatt.getService(SensorProtocol.SERVICE)?.getCharacteristic(SensorProtocol.INFO)
                if (infoChar != null) gatt.readCharacteristic(infoChar) else subscribe(gatt)
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
                val name = gatt.device.name ?: gatt.device.address
                prefs.edit().putString(KEY_SENSOR, gatt.device.address).apply()
                state = State.Connected(name ?: "Spectra")
                enqueue(control, SensorProtocol.Command.SetInterval(liveInterval).data)
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
                        writes.addFirst(next)
                        writeTarget = characteristic
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
        val liveIntervals = listOf(0, 3, 5, 10, 30)
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val KEY_SENSOR = "lastSensor"
        private const val KEY_INTERVAL = "liveInterval"
    }
}
