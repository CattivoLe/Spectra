package cattivole.spectra.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import cattivole.spectra.core.EspChip
import cattivole.spectra.core.EspFlashException
import cattivole.spectra.core.EspFlashFailure
import cattivole.spectra.core.EspFlashProgress
import cattivole.spectra.core.EspFlashSession
import cattivole.spectra.core.EspPipe
import cattivole.spectra.core.EspReset
import cattivole.spectra.core.FlashBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finds an Espressif chip or a CH340 UART in ROM download mode on the phone's
 * USB port and writes the flash bundle that matches the chip. A blank chip
 * enumerates on its own. USB-Serial/JTAG (S3, C5) drops off the bus during
 * the DTR/RTS reset, so the pipe re-opens the same VID:PID before SYNC.
 */
class UsbEspFlasher(context: Context) {
    data class Board(val id: Int, val title: String, val port: String, val kind: EspReset.Kind)

    private val app = context.applicationContext
    private val usb = app.getSystemService(UsbManager::class.java)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ -> },
    )
    private val listeners = mutableListOf<() -> Unit>()
    private var job: Job? = null
    private var session: EspFlashSession? = null
    private var pending: Int? = null
    @Volatile private var permissionLatch: CountDownLatch? = null
    @Volatile private var permissionOk = false

    var boards: List<Board> = emptyList()
        private set
    var progress: EspFlashProgress? = null
        private set
    var bundles: Map<EspChip, FlashBundle.Image> = emptyMap()
        private set
    var bundledKnown = false
        private set
    var chosen: FlashBundle.Image? = null
        private set
    var chosenName: String? = null
        private set

    val hasImage: Boolean get() = chosen != null || bundles.isNotEmpty()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> refresh()
                ACTION_USB_PERMISSION -> {
                    val device = deviceExtra(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val latch = permissionLatch
                    if (latch != null) {
                        permissionOk = device != null && granted
                        permissionLatch = null
                        latch.countDown()
                        return
                    }
                    val waiting = pending
                    pending = null
                    if (device != null && granted && waiting != null && waiting == device.deviceId) {
                        start(device)
                    } else if (waiting != null) {
                        publish { progress = EspFlashProgress(EspFlashProgress.Stage.Failed(EspFlashFailure.PERMISSION)) }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        scope.launch(Dispatchers.IO) {
            val found = linkedMapOf<EspChip, FlashBundle.Image>()
            for (name in BUNDLED) {
                val loaded = runCatching {
                    app.assets.open(name).use { FlashBundle.load(it.readBytes()) }
                }.getOrNull()
                if (loaded is FlashBundle.Load.Ready) found[loaded.image.chip] = loaded.image
            }
            if (EspChip.S3 !in found) {
                val legacy = runCatching {
                    app.assets.open("spectra-flash.zip").use { FlashBundle.load(it.readBytes()) }
                }.getOrNull()
                if (legacy is FlashBundle.Load.Ready) found.putIfAbsent(legacy.image.chip, legacy.image)
            }
            publish {
                bundles = found
                bundledKnown = true
            }
        }
        refresh()
    }

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun refresh() {
        val found = usb?.deviceList?.values.orEmpty().mapNotNull { board(it) }.sortedBy { it.id }
        if (found == boards) return
        publish { boards = found }
    }

    fun failed(failure: EspFlashFailure) {
        publish { progress = EspFlashProgress(EspFlashProgress.Stage.Failed(failure)) }
    }

    fun choose(name: String, bytes: ByteArray) {
        when (val loaded = FlashBundle.load(bytes)) {
            is FlashBundle.Load.Ready -> publish {
                chosen = loaded.image
                chosenName = name
                progress = null
            }
            is FlashBundle.Load.Rejected -> publish {
                progress = EspFlashProgress(EspFlashProgress.Stage.Failed(loaded.failure))
            }
        }
    }

    fun useBundled() {
        publish {
            chosen = null
            chosenName = null
        }
    }

    fun flash(boardId: Int) {
        if (progress?.isRunning == true) return
        if (!hasImage) {
            publish { progress = EspFlashProgress(EspFlashProgress.Stage.Failed(EspFlashFailure.NO_IMAGE)) }
            return
        }
        val device = usb?.deviceList?.values?.firstOrNull { it.deviceId == boardId } ?: run {
            refresh()
            return
        }
        val manager = usb ?: return
        if (!manager.hasPermission(device)) {
            pending = boardId
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = PendingIntent.getBroadcast(
                app,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(app.packageName),
                flags,
            )
            manager.requestPermission(device, intent)
            return
        }
        start(device)
    }

    fun cancel() {
        session?.cancelled = true
        job?.cancel()
    }

    fun dismiss() {
        if (progress?.isRunning == true) return
        publish { progress = null }
    }

    fun close() {
        cancel()
        runCatching { app.unregisterReceiver(receiver) }
    }

    private fun start(device: UsbDevice) {
        job?.cancel()
        val kind = if (device.vendorId == ESPRESSIF) EspReset.Kind.USB_JTAG else EspReset.Kind.UART
        val picked = chosen
        val included = bundles
        publish { progress = EspFlashProgress(EspFlashProgress.Stage.Preparing) }
        job = scope.launch {
            val pipe = withContext(Dispatchers.IO) { open(device) }
            if (pipe == null) {
                publish { progress = EspFlashProgress(EspFlashProgress.Stage.Failed(EspFlashFailure.OPEN_FAILED)) }
                return@launch
            }
            val flashing = EspFlashSession(pipe)
            session = flashing
            try {
                withContext(Dispatchers.IO) {
                    flashing.flash(kind, { chip ->
                        if (picked != null) {
                            if (picked.chip != chip) throw EspFlashException(EspFlashFailure.WRONG_BUNDLE)
                            picked
                        } else {
                            included[chip] ?: throw EspFlashException(EspFlashFailure.NO_CHIP_IMAGE)
                        }
                    }) { stage ->
                        publish { progress = EspFlashProgress(stage) }
                    }
                }
            } catch (e: EspFlashException) {
                publish { progress = EspFlashProgress(EspFlashProgress.Stage.Failed(e.failure)) }
            } catch (e: CancellationException) {
                publish { progress = EspFlashProgress(EspFlashProgress.Stage.Failed(EspFlashFailure.CANCELLED)) }
                throw e
            } catch (_: IOException) {
                publish { progress = EspFlashProgress(EspFlashProgress.Stage.Failed(EspFlashFailure.LINK_DROPPED)) }
            } finally {
                session = null
                withContext(Dispatchers.IO) { runCatching { pipe.close() } }
            }
        }
    }

    private fun open(device: UsbDevice): CdcPipe? {
        val handle = openHandle(device) ?: return null
        return CdcPipe(handle)
    }

    private fun openHandle(device: UsbDevice): UsbHandle? {
        val manager = usb ?: return null
        val connection = manager.openDevice(device) ?: return null
        val comm = interfaces(device).firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
        val data = interfaces(device).firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
            ?: interfaces(device).firstOrNull { iface ->
                iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && bulkIn(iface) != null && bulkOut(iface) != null
            }
        if (data == null) {
            connection.close()
            return null
        }
        if (comm != null && !connection.claimInterface(comm, true)) {
            connection.close()
            return null
        }
        if (!connection.claimInterface(data, true)) {
            if (comm != null) connection.releaseInterface(comm)
            connection.close()
            return null
        }
        val input = bulkIn(data)
        val output = bulkOut(data)
        if (input == null || output == null) {
            connection.close()
            return null
        }
        val control = comm ?: data
        // The CH9102 shares the CH340 vendor id but is a plain CDC-ACM device.
        val bridge = when {
            comm != null -> Bridge.CDC
            device.vendorId == CH340 -> Bridge.CH340
            device.vendorId == SILABS -> Bridge.CP210X
            else -> Bridge.CDC
        }
        val ready = when (bridge) {
            Bridge.CH340 -> initCh340(connection)
            Bridge.CP210X -> initCp210x(connection, data.id)
            Bridge.CDC -> {
                val coding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0, 0, 8)
                connection.controlTransfer(0x21, 0x20, 0, control.id, coding, coding.size, 300)
                true
            }
        }
        if (!ready) {
            connection.close()
            return null
        }
        if (device.vendorId == ESPRESSIF) Thread.sleep(100)
        return UsbHandle(connection, data, comm, input, output, control, bridge, device.vendorId, device.productId)
    }

    private fun requestPermissionBlocking(device: UsbDevice, timeoutMs: Long): Boolean {
        val manager = usb ?: return false
        if (manager.hasPermission(device)) return true
        val latch = CountDownLatch(1)
        permissionOk = false
        permissionLatch = latch
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = PendingIntent.getBroadcast(
            app,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(app.packageName),
            flags,
        )
        manager.requestPermission(device, intent)
        val granted = latch.await(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS) && permissionOk
        if (permissionLatch === latch) permissionLatch = null
        return granted
    }

    private enum class Bridge { CDC, CH340, CP210X }

    private class UsbHandle(
        val connection: UsbDeviceConnection,
        val data: UsbInterface,
        val comm: UsbInterface?,
        val input: UsbEndpoint,
        val output: UsbEndpoint,
        val control: UsbInterface,
        val bridge: Bridge,
        val vid: Int,
        val pid: Int,
    )

    private fun board(device: UsbDevice): Board? {
        if (device.vendorId !in VENDORS) return null
        if (interfaces(device).none { bulkIn(it) != null && bulkOut(it) != null }) return null
        val title = runCatching { device.productName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: when (device.vendorId) {
                ESPRESSIF -> "Espressif USB"
                CH340 -> "CH340 serial"
                else -> "USB serial"
            }
        val port = "%04X:%04X".format(device.vendorId, device.productId)
        val kind = if (device.vendorId == ESPRESSIF) EspReset.Kind.USB_JTAG else EspReset.Kind.UART
        return Board(device.deviceId, title, port, kind)
    }

    private fun publish(block: () -> Unit) {
        scope.launch {
            block()
            listeners.toList().forEach { it() }
        }
    }

    private inner class CdcPipe(handle: UsbHandle) : EspPipe {
        private var connection = handle.connection
        private var data = handle.data
        private var comm = handle.comm
        private var input = handle.input
        private var output = handle.output
        private var control = handle.control
        private var bridge = handle.bridge
        private val vid = handle.vid
        private val pid = handle.pid
        private val buffer = ByteArray(16 * 1024)

        override fun write(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val wrote = connection.bulkTransfer(output, bytes, offset, bytes.size - offset, 2_000)
                if (wrote <= 0) throw IOException("usb write")
                offset += wrote
            }
        }

        override fun read(timeoutMs: Long): ByteArray {
            val read = connection.bulkTransfer(input, buffer, buffer.size, timeoutMs.toInt().coerceIn(1, 5_000))
            if (read <= 0) return ByteArray(0)
            return buffer.copyOf(read)
        }

        override fun setLines(dtr: Boolean, rts: Boolean) {
            when (bridge) {
                Bridge.CH340 -> {
                    // CH340 modem bits are active-low in the vendor request.
                    val lines = (if (dtr) 0x20 else 0) or (if (rts) 0x40 else 0)
                    connection.controlTransfer(0x40, 0xA4, lines.inv() and 0xFFFF, 0, null, 0, 200)
                }
                Bridge.CP210X -> {
                    // SET_MHS: the high byte says which of the low bits to apply.
                    val lines = 0x0300 or (if (dtr) 1 else 0) or (if (rts) 2 else 0)
                    connection.controlTransfer(0x41, 0x07, lines, data.id, null, 0, 200)
                }
                Bridge.CDC -> {
                    val value = (if (dtr) 1 else 0) or (if (rts) 2 else 0)
                    connection.controlTransfer(0x21, 0x22, value, control.id, null, 0, 200)
                }
            }
        }

        override fun recover(timeoutMs: Long): Boolean {
            release()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                val device = usb?.deviceList?.values?.firstOrNull { it.vendorId == vid && it.productId == pid }
                if (device != null) {
                    val manager = usb
                    if (manager != null && !manager.hasPermission(device)) {
                        val left = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
                        if (!requestPermissionBlocking(device, left)) {
                            Thread.sleep(150)
                            continue
                        }
                    }
                    val opened = openHandle(device)
                    if (opened != null) {
                        adopt(opened)
                        setLines(false, false)
                        return true
                    }
                }
                Thread.sleep(150)
            }
            return false
        }

        override fun close() = release()

        private fun adopt(handle: UsbHandle) {
            connection = handle.connection
            data = handle.data
            comm = handle.comm
            input = handle.input
            output = handle.output
            control = handle.control
            bridge = handle.bridge
        }

        private fun release() {
            runCatching { connection.releaseInterface(data) }
            if (comm != null) runCatching { connection.releaseInterface(comm) }
            runCatching { connection.close() }
        }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "cattivole.spectra.USB_PERMISSION"
        const val ESPRESSIF = 0x303A
        const val CH340 = 0x1A86
        const val SILABS = 0x10C4
        val VENDORS = setOf(ESPRESSIF, CH340, SILABS, 0x0403)
        val BUNDLED = listOf(
            "spectra-flash-esp32s3.zip",
            "spectra-flash-esp32c5.zip",
            "spectra-flash-esp32.zip",
        )

        /** CH340 ignores CDC line coding and comes up at 9600. The ROM listens at 115200. */
        fun initCh340(connection: UsbDeviceConnection): Boolean {
            fun out(request: Int, value: Int, index: Int) =
                connection.controlTransfer(0x40, request, value, index, null, 0, 1_000) >= 0
            if (!out(0xA1, 0, 0)) return false
            if (!setCh340Baud(connection, 115200)) return false
            if (!out(0x9A, 0x2518, 0xC3)) return false
            if (!out(0xA1, 0x501F, 0xD90A)) return false
            if (!setCh340Baud(connection, 115200)) return false
            return connection.controlTransfer(0x40, 0xA4, 0xFFFF, 0, null, 0, 1_000) >= 0
        }

        /** The CP210x UART stays off until IFC_ENABLE, and it ignores CDC line coding. */
        fun initCp210x(connection: UsbDeviceConnection, iface: Int): Boolean {
            fun out(request: Int, value: Int, data: ByteArray? = null) =
                connection.controlTransfer(0x41, request, value, iface, data, data?.size ?: 0, 1_000) >= 0
            val baud = 115200
            val rate = byteArrayOf(baud.toByte(), (baud shr 8).toByte(), (baud shr 16).toByte(), (baud shr 24).toByte())
            return out(0x00, 0x0001) && out(0x03, 0x0800) && out(0x1E, 0, rate)
        }

        fun setCh340Baud(connection: UsbDeviceConnection, baud: Int): Boolean {
            var factor = 1_532_620_800L / baud
            var divisor = 3
            while (factor > 0xfff0 && divisor > 0) {
                factor = factor shr 3
                divisor -= 1
            }
            if (factor > 0xfff0) return false
            factor = 0x10000 - factor
            divisor = divisor or 0x0080
            val val1 = ((factor and 0xff00) or divisor.toLong()).toInt()
            val val2 = (factor and 0xff).toInt()
            fun out(value: Int, index: Int) =
                connection.controlTransfer(0x40, 0x9A, value, index, null, 0, 1_000) >= 0
            return out(0x1312, val1) && out(0x0F2C, val2)
        }

        fun interfaces(device: UsbDevice) = (0 until device.interfaceCount).map { device.getInterface(it) }

        fun bulkIn(iface: UsbInterface) = endpoint(iface, UsbConstants.USB_DIR_IN)

        fun bulkOut(iface: UsbInterface) = endpoint(iface, UsbConstants.USB_DIR_OUT)

        fun endpoint(iface: UsbInterface, direction: Int): UsbEndpoint? =
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }.firstOrNull {
                it.direction == direction && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            }

        fun deviceExtra(intent: Intent): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
    }
}
