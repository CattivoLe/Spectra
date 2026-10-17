package cattivole.spectra.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import cattivole.spectra.core.Catalog

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

    data class Characteristic(val uuid: String, val properties: String, val value: ByteArray?)
    data class Service(val uuid: String, val name: String?, val characteristics: List<Characteristic>)

    var state: State = State.Idle
        private set
    var services: List<Service> = emptyList()
        private set
    var deviceAddress: String? = null
        private set

    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private var gatt: BluetoothGatt? = null
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        disconnect()
        val device: BluetoothDevice? = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        deviceAddress = address
        services = emptyList()
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
                    Characteristic(Catalog.displayUuid(ch.uuid.toString()), properties(ch), ch.value)
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun readNext() {
        val g = gatt ?: return
        val ch = readQueue.removeFirstOrNull() ?: return
        val ok = runCatching { g.readCharacteristic(ch) }.getOrDefault(false)
        if (!ok) readNext()
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
                for (svc in g.services) {
                    for (ch in svc.characteristics) {
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                            readQueue.add(ch)
                        }
                    }
                }
                publish()
                readNext()
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
                rebuild(g)
                publish()
                readNext()
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
                rebuild(g)
                publish()
                readNext()
            }
        }
    }
}
