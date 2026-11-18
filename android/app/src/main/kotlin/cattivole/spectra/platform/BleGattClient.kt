package cattivole.spectra.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.os.Handler
import android.os.Looper
import cattivole.spectra.core.Catalog
import java.util.UUID

/**
 * Connects to one LE device and reads the services and characteristics it
 * exposes. Android serves one read at a time, so readable characteristics are
 * read from a queue. Everything runs on the main thread: the GATT callbacks
 * post there, and the UI drives connect/disconnect from there too.
 */
class BleGattClient(context: Context) {
    sealed class State {
        data object Idle : State()
        data object Connecting : State()
        data object Discovering : State()
        data object Connected : State()
        data class Failed(val message: String) : State()
    }

    data class Characteristic(val uuid: String, val name: String?, val properties: String, val value: ByteArray?)
    data class Service(val uuid: String, val name: String?, val characteristics: List<Characteristic>)

    var state: State = State.Idle
        private set
    var services: List<Service> = emptyList()
        private set
    var deviceAddress: String? = null
        private set

    /** What one device published over GATT, as last read. */
    data class Read(val address: String, val services: List<Service>, val readAt: Long)

    /**
     * The last GATT read that returned anything, kept after the link is dropped so the assistant can still
     * explain it ("what did that device say about itself?"). Replaced by the next read of any device.
     */
    var lastRead: Read? = null
        private set

    /** The link ended after the device had answered: what it gave is kept. */
    var endedAfterRead: Boolean = false
        private set

    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private var gatt: BluetoothGatt? = null
    private val readQueue = ArrayDeque<Pending>()
    /** Bytes from the read callback. On API 33 the characteristic's own value field stays empty. */
    private val values = mutableMapOf<String, ByteArray>()

    private sealed class Pending {
        data class Read(val characteristic: BluetoothGattCharacteristic) : Pending()
        data class Subscribe(val characteristic: BluetoothGattCharacteristic) : Pending()
    }

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        disconnect()
        val device: BluetoothDevice? = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        deviceAddress = address
        endedAfterRead = false
        services = emptyList()
        values.clear()
        readQueue.clear()
        if (device == null || !Permissions.canScanBluetooth(app)) {
            state = State.Failed("Bluetooth is unavailable.")
            publish()
            return
        }
        state = State.Connecting
        publish()
        gatt = runCatching {
            device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) {
            state = State.Failed("Could not start the connection.")
            publish()
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        readQueue.clear()
        state = State.Idle
        services = emptyList()
        deviceAddress = null
        publish()
    }

    private fun publish() = listeners.toList().forEach { it() }

    private fun properties(c: BluetoothGattCharacteristic): String {
        val p = c.properties
        val parts = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts.add("Read")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts.add("Write")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts.add("WriteNR")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts.add("Notify")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts.add("Indicate")
        return parts.joinToString(" · ")
    }

    @SuppressLint("MissingPermission")
    private fun rebuild(g: BluetoothGatt) {
        services = g.services.map { svc ->
            Service(
                Catalog.displayUuid(svc.uuid.toString()),
                Catalog.serviceName(svc.uuid.toString()),
                svc.characteristics.map { ch ->
                    @Suppress("DEPRECATION")
                    val bytes = values[ch.uuid.toString()] ?: ch.value
                    Characteristic(
                        Catalog.displayUuid(ch.uuid.toString()),
                        Catalog.characteristicName(ch.uuid.toString()),
                        properties(ch),
                        bytes,
                    )
                },
            )
        }
        noteRead()
    }

    /** Copies what the bound device has given so far into [lastRead]. */
    private fun noteRead() {
        val address = deviceAddress ?: return
        if (services.isEmpty()) return
        lastRead = Read(address, services, System.currentTimeMillis())
    }

    @SuppressLint("MissingPermission")
    private fun pump() {
        val g = gatt ?: return
        val next = readQueue.removeFirstOrNull() ?: return
        val started = when (next) {
            is Pending.Read -> runCatching { g.readCharacteristic(next.characteristic) }.getOrDefault(false)
            is Pending.Subscribe -> subscribe(g, next.characteristic)
        }
        if (!started) pump()
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CCCD) ?: return false
        val indicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val enable = if (indicate) byteArrayOf(0x02, 0x00) else byteArrayOf(0x01, 0x00)
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(descriptor, enable) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = enable
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    }

    private fun store(characteristic: BluetoothGattCharacteristic, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        values[characteristic.uuid.toString()] = bytes
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) {
                    runCatching { g.close() }
                    return@post
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        state = State.Discovering
                        publish()
                        runCatching { g.discoverServices() }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (state !is State.Idle) {
                            if (state is State.Connected && services.isNotEmpty()) endedAfterRead = true
                            state = if (status != BluetoothGatt.GATT_SUCCESS && state !is State.Connected) {
                                State.Failed("The device refused the connection.")
                            } else {
                                State.Idle
                            }
                            publish()
                        }
                        runCatching { g.close() }
                        if (g === gatt) gatt = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    state = State.Failed("Could not read the services.")
                    publish()
                    return@post
                }
                rebuild(g)
                state = State.Connected
                readQueue.clear()
                val subscribe = mutableListOf<BluetoothGattCharacteristic>()
                for (svc in g.services) {
                    for (ch in svc.characteristics) {
                        val props = ch.properties
                        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                            readQueue.add(Pending.Read(ch))
                        }
                        if (props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                            subscribe.add(ch)
                        }
                    }
                }
                subscribe.forEach { readQueue.add(Pending.Subscribe(it)) }
                publish()
                pump()
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            main.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) store(characteristic, value)
                rebuild(g)
                publish()
                pump()
            }
        }

        @Deprecated("Superseded by the value overload on API 33+")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= 33) return
            main.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    store(characteristic, characteristic.value)
                }
                rebuild(g)
                publish()
                pump()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            main.post {
                if (g !== gatt) return@post
                store(characteristic, value)
                rebuild(g)
                publish()
            }
        }

        @Deprecated("Superseded by the value overload on API 33+")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val bytes = characteristic.value
            main.post {
                if (g !== gatt) return@post
                store(characteristic, bytes)
                rebuild(g)
                publish()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g !== gatt) return@post
                pump()
            }
        }
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
