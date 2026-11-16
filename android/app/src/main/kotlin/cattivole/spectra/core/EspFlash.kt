package cattivole.spectra.core

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.ZipInputStream

/**
 * First-time programming of a blank ESP32-S3, ESP32-C5 or ESP32 over USB.
 *
 * A chip with no Spectra firmware has no Bluetooth, so the phone speaks the
 * ROM serial protocol (SLIP frames, the same one esptool uses) and writes the
 * bootloader, partition table, otadata and application. The S3 and C5 use
 * USB-Serial/JTAG. A WROOM-32 uses the CH340 UART. Later updates still go
 * over Bluetooth.
 */
enum class EspFlashFailure(val message: String) {
    NO_BOOTLOADER("Could not find the ROM bootloader. Hold BOOT (G0 on a Cardputer), tap reset, let go, and try again."),
    NOT_S3("This chip is not an ESP32-S3, an ESP32-C5 or an ESP32."),
    WRONG_BUNDLE("This bundle is for a different chip. Use the included firmware, or pick the bundle built for the board that is plugged in."),
    NO_CHIP_IMAGE("This build of the app has no firmware for this chip. Run pio run for that board, rebuild the app, or pick its flash bundle."),
    CHIP_REJECTED("The chip rejected the flash command."),
    WRITE_FAILED("The chip rejected part of the image."),
    MISMATCH("The image on the chip does not match. Flash it again."),
    CANCELLED("Flash cancelled."),
    LINK_DROPPED("The USB link dropped."),
    OPEN_FAILED("The phone could not open the USB device."),
    PERMISSION("Allow USB access and try again."),
    TIMED_OUT("The chip stopped answering. Keep the cable connected and try again."),
    APP_IMAGE("This is an application image. A blank chip needs the flash bundle: bootloader, partition table and firmware together."),
    BAD_BUNDLE("This file is not a flash bundle."),
    NO_BUNDLE_BOOTLOADER("This bundle has no bootloader at the offset this chip expects."),
    BAD_LAYOUT("The pieces of this bundle overlap or do not fit in the flash."),
    NO_IMAGE("This build of the app has no firmware inside. Run pio run, rebuild the app, or pick a flash bundle."),
    UNREADABLE("Could not read the firmware file."),
}

class EspFlashException(val failure: EspFlashFailure) : Exception(failure.message)

data class EspFlashProgress(val stage: Stage) {
    sealed class Stage {
        data object Preparing : Stage()
        data class Writing(val sent: Long, val total: Long) : Stage()
        data object Checking : Stage()
        data object Restarting : Stage()
        data object Done : Stage()
        data class Failed(val failure: EspFlashFailure) : Stage()

        val isRunning: Boolean
            get() = this is Preparing || this is Writing || this is Checking || this is Restarting
    }

    val isRunning: Boolean get() = stage.isRunning
}

/** Bytes the ROM bootloader exchanges, before SLIP framing. */
interface EspPipe {
    fun write(bytes: ByteArray)
    /** Bytes that arrived, or empty when [timeoutMs] elapsed. */
    fun read(timeoutMs: Long): ByteArray
    fun setLines(dtr: Boolean, rts: Boolean) {}
    fun close() {}
    /**
     * Re-open the USB device after a reset that dropped the link. USB-Serial/JTAG
     * is the chip itself, so toggling DTR/RTS re-enumerates it. A UART bridge
     * stays put; the default is that the link is still there.
     */
    fun recover(timeoutMs: Long): Boolean = true
}

object EspSlip {
    private const val END = 0xC0
    private const val ESC = 0xDB
    private const val ESC_END = 0xDC
    private const val ESC_ESC = 0xDD

    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        out.write(END)
        for (raw in payload) {
            when (val b = raw.toInt() and 0xFF) {
                END -> {
                    out.write(ESC)
                    out.write(ESC_END)
                }
                ESC -> {
                    out.write(ESC)
                    out.write(ESC_ESC)
                }
                else -> out.write(b)
            }
        }
        out.write(END)
        return out.toByteArray()
    }

    class Decoder {
        private val buf = ArrayList<Byte>(256)
        private var inFrame = false
        private var escape = false

        fun clear() {
            buf.clear()
            inFrame = false
            escape = false
        }

        fun push(bytes: ByteArray): List<ByteArray> {
            val frames = ArrayList<ByteArray>()
            for (raw in bytes) {
                val b = raw.toInt() and 0xFF
                if (!inFrame) {
                    if (b == END) {
                        inFrame = true
                        escape = false
                        buf.clear()
                    }
                    continue
                }
                if (escape) {
                    escape = false
                    when (b) {
                        ESC_END -> buf.add(END.toByte())
                        ESC_ESC -> buf.add(ESC.toByte())
                        else -> {
                            inFrame = false
                            buf.clear()
                        }
                    }
                    continue
                }
                when (b) {
                    ESC -> escape = true
                    END -> {
                        if (buf.isNotEmpty()) frames.add(buf.toByteArray())
                        buf.clear()
                    }
                    else -> buf.add(raw)
                }
            }
            return frames
        }
    }
}

/**
 * Chips the phone can program. [romId] is the id from GET_SECURITY_INFO.
 * The original ESP32 does not answer that command; it is recognised by the
 * magic value at [ESP32_MAGIC_REG]. [classicBegin] is the ROM flash-begin
 * packet: the ESP32 omits the encryption word that later chips require.
 * [bootOffset] is where that chip's bootloader image lives.
 */
enum class EspChip(val wire: String, val romId: Int?, val bootOffset: Long, val classicBegin: Boolean, val title: String) {
    S3("esp32s3", 9, 0L, false, "ESP32-S3"),
    C5("esp32c5", 23, 0x2000L, false, "ESP32-C5"),
    ESP32("esp32", null, 0x1000L, true, "ESP32"),
    ;

    companion object {
        const val ESP32_MAGIC_REG = 0x40001000L
        const val ESP32_MAGIC = 0x00F01D83L

        fun fromWire(name: String): EspChip? = entries.firstOrNull { it.wire.equals(name, ignoreCase = true) }

        fun fromRom(id: Int): EspChip? = entries.firstOrNull { it.romId == id }
    }
}

/** Command packets for the ROM loader. No stub is uploaded. */
object EspRom {
    const val ESP32_S3 = 9
    const val ESP32_C5 = 23
    const val BLOCK = 1024

    const val FLASH_BEGIN = 0x02
    const val FLASH_DATA = 0x03
    const val SYNC = 0x08
    const val WRITE_REG = 0x09
    const val READ_REG = 0x0A
    const val SPI_PARAMS = 0x0B
    const val SPI_ATTACH = 0x0D
    const val FLASH_MD5 = 0x13
    const val SECURITY = 0x14
    const val INVALID_COMMAND = 0x05

    data class Response(val command: Int, val value: Long, val data: ByteArray, val status: Int, val error: Int) {
        val ok: Boolean get() = status == 0
    }

    fun checksum(data: ByteArray): Int {
        var sum = 0xEF
        for (raw in data) sum = sum xor (raw.toInt() and 0xFF)
        return sum and 0xFF
    }

    fun packet(command: Int, data: ByteArray = ByteArray(0), checksum: Int = 0): ByteArray {
        val out = ByteWriter()
        out.u8(0)
        out.u8(command)
        out.u16(data.size)
        out.u32(checksum.toLong() and 0xFF)
        out.bytes(data)
        return out.toByteArray()
    }

    fun sync(): ByteArray {
        val data = ByteArray(36)
        data[0] = 0x07
        data[1] = 0x07
        data[2] = 0x12
        data[3] = 0x20
        for (i in 4 until data.size) data[i] = 0x55
        return packet(SYNC, data)
    }

    fun readReg(address: Long): ByteArray = packet(READ_REG, word(address))

    fun writeReg(address: Long, value: Long, mask: Long, delayUs: Long): ByteArray {
        val data = ByteWriter()
        data.u32(address)
        data.u32(value)
        data.u32(mask)
        data.u32(delayUs)
        return packet(WRITE_REG, data.toByteArray())
    }

    /** Default SPI pins. The ROM wants the extra zero word; the stub does not. */
    fun spiAttach(): ByteArray = packet(SPI_ATTACH, ByteArray(8))

    fun spiParams(flashSize: Long): ByteArray {
        val data = ByteWriter()
        data.u32(0)
        data.u32(flashSize)
        data.u32(64L * 1024)
        data.u32(4L * 1024)
        data.u32(256)
        data.u32(0xFFFF)
        return packet(SPI_PARAMS, data.toByteArray())
    }

    /**
     * Later chips take five words; the last one is the encrypted-write flag and
     * stays off. The original ESP32 ROM rejects that extra word.
     */
    fun flashBegin(eraseSize: Long, blocks: Int, blockSize: Int, offset: Long, extended: Boolean = true): ByteArray {
        val data = ByteWriter()
        data.u32(eraseSize)
        data.u32(blocks.toLong())
        data.u32(blockSize.toLong())
        data.u32(offset)
        if (extended) data.u32(0)
        return packet(FLASH_BEGIN, data.toByteArray())
    }

    fun flashData(sequence: Int, block: ByteArray): ByteArray {
        val data = ByteWriter()
        data.u32(block.size.toLong())
        data.u32(sequence.toLong())
        data.u32(0)
        data.u32(0)
        data.bytes(block)
        return packet(FLASH_DATA, data.toByteArray(), checksum(block))
    }

    fun flashMd5(address: Long, size: Long): ByteArray {
        val data = ByteWriter()
        data.u32(address)
        data.u32(size)
        data.u32(0)
        data.u32(0)
        return packet(FLASH_MD5, data.toByteArray())
    }

    fun securityInfo(): ByteArray = packet(SECURITY)

    fun parse(frame: ByteArray): Response? {
        if (frame.size < 10 || frame.u8(0) != 0x01) return null
        val size = frame.u16le(2)
        if (size < 2 || frame.size < 8 + size) return null
        val body = frame.copyOfRange(8, 8 + size)
        val statusLen = if (body.size >= 4) 4 else 2
        val status = body[body.size - statusLen].toInt() and 0xFF
        val error = body[body.size - statusLen + 1].toInt() and 0xFF
        return Response(frame.u8(1), frame.u32le(4), body.copyOf(body.size - statusLen), status, error)
    }

    /** Chip id from a GET_SECURITY_INFO payload, after the status bytes are removed. */
    fun chipId(payload: ByteArray): Int? {
        if (payload.size < 16) return null
        return payload.u32le(12).toInt()
    }

    fun md5Matches(data: ByteArray, reported: ByteArray): Boolean {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        val hex = buildString(32) {
            for (b in digest) append("%02x".format(b))
        }
        val text = reported.toString(Charsets.US_ASCII).trim().lowercase()
        return text.startsWith(hex)
    }

    private fun word(value: Long): ByteArray {
        val out = ByteWriter()
        out.u32(value)
        return out.toByteArray()
    }
}

/** DTR/RTS sequences. USB-Serial-JTAG reads the lines itself; a UART bridge uses the auto-reset circuit. */
object EspReset {
    enum class Kind { USB_JTAG, UART }

    data class Step(val dtr: Boolean, val rts: Boolean, val waitMs: Long)

    /** How long IO0 stays low after EN is released. A slow EN capacitor needs the longer one. */
    val UART_HOLDS = listOf(50L, 550L)

    fun enter(kind: Kind, holdMs: Long = UART_HOLDS.first()): List<Step> = when (kind) {
        Kind.USB_JTAG -> listOf(
            Step(dtr = false, rts = false, waitMs = 100),
            Step(dtr = true, rts = false, waitMs = 100),
            // Pass through both lines high. Going straight to (DTR low, RTS high) glitches the ROM.
            Step(dtr = true, rts = true, waitMs = 0),
            Step(dtr = false, rts = true, waitMs = 100),
            Step(dtr = false, rts = false, waitMs = 50),
        )
        Kind.UART -> listOf(
            Step(dtr = false, rts = false, waitMs = 0),
            Step(dtr = true, rts = true, waitMs = 0),
            Step(dtr = false, rts = true, waitMs = 100),
            Step(dtr = true, rts = false, waitMs = holdMs),
            Step(dtr = false, rts = false, waitMs = 50),
        )
    }

    fun hardReset(): List<Step> = listOf(
        Step(dtr = false, rts = true, waitMs = 200),
        Step(dtr = false, rts = false, waitMs = 0),
    )

    fun play(pipe: EspPipe, steps: List<Step>, sleep: (Long) -> Unit) {
        for (step in steps) {
            pipe.setLines(step.dtr, step.rts)
            if (step.waitMs > 0) sleep(step.waitMs)
        }
    }
}

/**
 * A zip named by `flash.json`:
 * `{"chip":"esp32s3","flashSize":8388608,"files":[{"offset":0,"name":"bootloader.bin"}, ...]}`
 * `chip` is `esp32s3`, `esp32c5` or `esp32`. Offsets are numbers or hex strings
 * (`"0x10000"`). The bootloader sits at 0 on an S3, at 0x2000 on a C5 and at
 * 0x1000 on an ESP32.
 */
object FlashBundle {
    const val FLASH_8MB = 8L * 1024 * 1024

    class Segment(val offset: Long, val data: ByteArray)
    class Image(val chip: EspChip, val flashSize: Long, val segments: List<Segment>)

    sealed class Load {
        class Ready(val image: Image) : Load()
        class Rejected(val failure: EspFlashFailure) : Load()
    }

    fun load(bytes: ByteArray): Load {
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            return loadZip(bytes)
        }
        if (bytes.isNotEmpty() && bytes[0] == 0xE9.toByte()) return Load.Rejected(EspFlashFailure.APP_IMAGE)
        return Load.Rejected(EspFlashFailure.BAD_BUNDLE)
    }

    private fun loadZip(bytes: ByteArray): Load {
        val entries = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var total = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (name.isEmpty() || name.contains("..")) continue
                    val data = zip.readBytes()
                    total += data.size
                    if (data.size > FLASH_8MB || total > FLASH_8MB) return Load.Rejected(EspFlashFailure.BAD_LAYOUT)
                    entries[name] = data
                }
            }
        } catch (_: IOException) {
            return Load.Rejected(EspFlashFailure.BAD_BUNDLE)
        }
        val manifest = entries["flash.json"] ?: return Load.Rejected(EspFlashFailure.BAD_BUNDLE)
        val json = try {
            JSONObject(manifest.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return Load.Rejected(EspFlashFailure.BAD_BUNDLE)
        }
        val chip = EspChip.fromWire(json.optString("chip", "esp32s3"))
            ?: return Load.Rejected(EspFlashFailure.NOT_S3)
        val flashSize = json.optLong("flashSize", FLASH_8MB)
        if (flashSize !in (1024L * 1024)..(16L * 1024 * 1024)) return Load.Rejected(EspFlashFailure.BAD_LAYOUT)
        val files = json.optJSONArray("files") ?: return Load.Rejected(EspFlashFailure.BAD_BUNDLE)
        val segments = ArrayList<Segment>()
        for (i in 0 until files.length()) {
            val item = files.optJSONObject(i) ?: return Load.Rejected(EspFlashFailure.BAD_BUNDLE)
            val name = item.optString("name").substringAfterLast('/')
            val offset = jsonLong(item.opt("offset")) ?: return Load.Rejected(EspFlashFailure.BAD_BUNDLE)
            val data = entries[name] ?: return Load.Rejected(EspFlashFailure.BAD_BUNDLE)
            if (data.isEmpty()) return Load.Rejected(EspFlashFailure.BAD_BUNDLE)
            segments.add(Segment(offset, data))
        }
        return validate(chip, flashSize, segments)
    }

    private fun validate(chip: EspChip, flashSize: Long, segments: List<Segment>): Load {
        if (segments.isEmpty()) return Load.Rejected(EspFlashFailure.BAD_BUNDLE)
        val ordered = segments.sortedBy { it.offset }
        val boot = ordered.firstOrNull { it.offset == chip.bootOffset }
        if (boot == null || boot.data[0] != 0xE9.toByte()) {
            return Load.Rejected(EspFlashFailure.NO_BUNDLE_BOOTLOADER)
        }
        var end = 0L
        for (segment in ordered) {
            if (segment.offset % 4 != 0L || segment.offset < end) return Load.Rejected(EspFlashFailure.BAD_LAYOUT)
            val next = segment.offset + segment.data.size
            if (next > flashSize) return Load.Rejected(EspFlashFailure.BAD_LAYOUT)
            end = next
        }
        val app = ordered.firstOrNull { it.offset >= 0x10000 }
        if (app != null && app.data[0] != 0xE9.toByte()) return Load.Rejected(EspFlashFailure.APP_IMAGE)
        return Load.Ready(Image(chip, flashSize, ordered))
    }

    private fun jsonLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> {
            val text = value.trim()
            if (text.startsWith("0x", ignoreCase = true)) text.substring(2).toLongOrNull(16) else text.toLongOrNull()
        }
        else -> null
    }
}

/**
 * Syncs with the ROM, writes every segment, checks the MD5 the chip reports,
 * then resets it into the new firmware.
 */
class EspFlashSession(
    private val pipe: EspPipe,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val syncAttempts: Int = 5,
    private val syncWindowMs: Long = 250,
) {
    @Volatile var cancelled = false

    private val decoder = EspSlip.Decoder()
    private val pending = ArrayDeque<ByteArray>()

    fun flash(image: FlashBundle.Image, kind: EspReset.Kind, onProgress: (EspFlashProgress.Stage) -> Unit) {
        flash(kind, { detected ->
            if (detected != image.chip) throw EspFlashException(EspFlashFailure.WRONG_BUNDLE)
            image
        }, onProgress)
    }

    fun flash(
        kind: EspReset.Kind,
        select: (EspChip) -> FlashBundle.Image,
        onProgress: (EspFlashProgress.Stage) -> Unit,
    ) {
        try {
            onProgress(EspFlashProgress.Stage.Preparing)
            if (!sync() && !resetIntoRom(kind)) throw EspFlashException(EspFlashFailure.NO_BOOTLOADER)
            val chip = detectChip()
            val image = select(chip)
            if (image.chip != chip) throw EspFlashException(EspFlashFailure.WRONG_BUNDLE)
            disableWatchdogs(chip)
            exchange(EspRom.spiAttach(), 1_000, EspFlashFailure.CHIP_REJECTED)
            exchange(EspRom.spiParams(image.flashSize), 1_000, EspFlashFailure.CHIP_REJECTED)

            val total = image.segments.sumOf { it.data.size.toLong() }
            var sent = 0L
            var reported = 0L
            for (segment in image.segments) {
                checkCancel()
                onProgress(EspFlashProgress.Stage.Preparing)
                val aligned = align4(segment.data)
                val blocks = (aligned.size + EspRom.BLOCK - 1) / EspRom.BLOCK
                exchange(
                    EspRom.flashBegin(
                        aligned.size.toLong(),
                        blocks,
                        EspRom.BLOCK,
                        segment.offset,
                        extended = !chip.classicBegin,
                    ),
                    timeoutFor(aligned.size.toLong(), 30_000),
                    EspFlashFailure.CHIP_REJECTED,
                )
                var sequence = 0
                var offset = 0
                while (offset < aligned.size) {
                    checkCancel()
                    val block = ByteArray(EspRom.BLOCK) { 0xFF.toByte() }
                    val end = minOf(offset + EspRom.BLOCK, aligned.size)
                    aligned.copyInto(block, destinationOffset = 0, startIndex = offset, endIndex = end)
                    exchange(EspRom.flashData(sequence, block), 3_000, EspFlashFailure.WRITE_FAILED)
                    sequence += 1
                    offset = end
                    val absolute = sent + minOf(offset, segment.data.size)
                    if (absolute == total || absolute - reported >= 16 * 1024) {
                        reported = absolute
                        onProgress(EspFlashProgress.Stage.Writing(absolute, total))
                    }
                }
                checkCancel()
                sent += segment.data.size
                onProgress(EspFlashProgress.Stage.Checking)
                val md5 = exchange(
                    EspRom.flashMd5(segment.offset, aligned.size.toLong()),
                    timeoutFor(aligned.size.toLong(), 8_000),
                    EspFlashFailure.MISMATCH,
                )
                if (!EspRom.md5Matches(aligned, md5.data)) throw EspFlashException(EspFlashFailure.MISMATCH)
            }
            onProgress(EspFlashProgress.Stage.Restarting)
            // USB-Serial-JTAG on the S3 can leave the force-download bit set, and the chip then ignores the new firmware.
            if (chip == EspChip.S3) {
                quietly { exchange(EspRom.writeReg(0x6000812C, 0, 0x1, 0), 1_000, EspFlashFailure.CHIP_REJECTED) }
            }
            runCatching { EspReset.play(pipe, EspReset.hardReset(), sleep) }
            onProgress(EspFlashProgress.Stage.Done)
        } catch (e: EspFlashException) {
            throw e
        } catch (_: IOException) {
            throw EspFlashException(EspFlashFailure.LINK_DROPPED)
        }
    }

    private fun detectChip(): EspChip {
        val security = try {
            exchange(EspRom.securityInfo(), 1_000, EspFlashFailure.CHIP_REJECTED)
        } catch (e: EspFlashException) {
            if (e.failure != EspFlashFailure.CHIP_REJECTED && e.failure != EspFlashFailure.TIMED_OUT) throw e
            drain()
            null
        }
        if (security != null) {
            val id = EspRom.chipId(security.data) ?: throw EspFlashException(EspFlashFailure.NOT_S3)
            return EspChip.fromRom(id) ?: throw EspFlashException(EspFlashFailure.NOT_S3)
        }
        // The original ESP32 ROM has no security-info command. Its magic
        // register is how esptool tells it apart from everything else.
        val magic = exchange(EspRom.readReg(EspChip.ESP32_MAGIC_REG), 1_000, EspFlashFailure.NOT_S3).value
        if (magic == EspChip.ESP32_MAGIC) return EspChip.ESP32
        throw EspFlashException(EspFlashFailure.NOT_S3)
    }

    private fun disableWatchdogs(chip: EspChip) {
        // USB-Serial-JTAG does not pet the RTC watchdogs, so a long erase can reset the chip.
        // A UART bridge does, and the original ESP32 has different registers. A chip that
        // refuses the registers can still be flashed.
        when (chip) {
            EspChip.ESP32 -> return
            EspChip.S3 -> quietly { feedWatchdog(0x600080B0, 0x60008098, 0x600080B4, 0x600080B8, 0x8F1D312A, 1L shl 31) }
            EspChip.C5 -> quietly { feedWatchdog(0x600B1C18, 0x600B1C00, 0x600B1C1C, 0x600B1C20, 0x50D83AA1, 1L shl 18) }
        }
    }

    private fun feedWatchdog(protect: Long, config: Long, swdConf: Long, swdProtect: Long, swdKey: Long, autoFeed: Long) {
        exchange(EspRom.writeReg(protect, 0x50D83AA1, 0xFFFFFFFF, 0), 1_000, EspFlashFailure.CHIP_REJECTED)
        exchange(EspRom.writeReg(config, 0, 0xFFFFFFFF, 0), 1_000, EspFlashFailure.CHIP_REJECTED)
        exchange(EspRom.writeReg(protect, 0, 0xFFFFFFFF, 0), 1_000, EspFlashFailure.CHIP_REJECTED)
        val swd = exchange(EspRom.readReg(swdConf), 1_000, EspFlashFailure.CHIP_REJECTED).value
        exchange(EspRom.writeReg(swdProtect, swdKey, 0xFFFFFFFF, 0), 1_000, EspFlashFailure.CHIP_REJECTED)
        exchange(EspRom.writeReg(swdConf, swd or autoFeed, 0xFFFFFFFF, 0), 1_000, EspFlashFailure.CHIP_REJECTED)
        exchange(EspRom.writeReg(swdProtect, 0, 0xFFFFFFFF, 0), 1_000, EspFlashFailure.CHIP_REJECTED)
    }

    private fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: EspFlashException) {
            if (e.failure == EspFlashFailure.CANCELLED) throw e
        }
    }

    private fun resetIntoRom(kind: EspReset.Kind): Boolean {
        val holds = if (kind == EspReset.Kind.UART) EspReset.UART_HOLDS + EspReset.UART_HOLDS else List(3) { 0L }
        for (hold in holds) {
            checkCancel()
            runCatching { EspReset.play(pipe, EspReset.enter(kind, hold), sleep) }
            if (kind == EspReset.Kind.USB_JTAG) {
                // The chip leaves the bus while it resets. esptool reopens the port; so do we.
                if (!pipe.recover(12_000)) continue
                sleep(200)
            }
            decoder.clear()
            pending.clear()
            if (sync()) return true
        }
        return false
    }

    private fun sync(): Boolean {
        for (attempt in 0 until syncAttempts) {
            checkCancel()
            decoder.clear()
            pending.clear()
            try {
                pipe.write(EspSlip.encode(EspRom.sync()))
            } catch (_: IOException) {
                if (!pipe.recover(2_000)) return false
                continue
            }
            val deadline = System.nanoTime() + syncWindowMs * 1_000_000
            while (System.nanoTime() < deadline) {
                val frame = nextFrame(deadline) ?: break
                val response = EspRom.parse(frame) ?: continue
                if (response.command == EspRom.SYNC && response.ok) return true
            }
        }
        return false
    }

    private fun exchange(request: ByteArray, timeoutMs: Long, failure: EspFlashFailure): EspRom.Response {
        checkCancel()
        pipe.write(EspSlip.encode(request))
        val expect = request.u8(1)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            checkCancel()
            val frame = nextFrame(deadline) ?: throw EspFlashException(EspFlashFailure.TIMED_OUT)
            val response = EspRom.parse(frame) ?: continue
            if (response.command != expect) {
                // The ESP32 ROM answers a command it does not know under another opcode.
                if (!response.ok && response.error == EspRom.INVALID_COMMAND) throw EspFlashException(failure)
                continue
            }
            if (!response.ok) throw EspFlashException(failure)
            return response
        }
    }

    /** Drops the extra error replies the ESP32 ROM can send after an unknown command. */
    private fun drain() {
        decoder.clear()
        pending.clear()
        val deadline = System.nanoTime() + 500 * 1_000_000L
        while (System.nanoTime() < deadline) {
            checkCancel()
            if (pipe.read(100).isEmpty()) break
        }
        decoder.clear()
    }

    private fun nextFrame(deadlineNs: Long): ByteArray? {
        if (pending.isNotEmpty()) return pending.removeFirst()
        while (System.nanoTime() < deadlineNs) {
            val left = ((deadlineNs - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
            val chunk = pipe.read(left.coerceAtMost(200))
            if (chunk.isEmpty()) continue
            val frames = decoder.push(chunk)
            if (frames.isNotEmpty()) {
                frames.drop(1).forEach { pending.add(it) }
                return frames.first()
            }
        }
        return null
    }

    private fun checkCancel() {
        if (cancelled) throw EspFlashException(EspFlashFailure.CANCELLED)
    }

    private fun timeoutFor(bytes: Long, perMb: Long): Long = maxOf(3_000L, perMb * bytes / 1_000_000)

    private fun align4(data: ByteArray): ByteArray {
        val size = (data.size + 3) and 3.inv()
        if (size == data.size) return data
        val out = ByteArray(size) { 0xFF.toByte() }
        data.copyInto(out)
        return out
    }
}
