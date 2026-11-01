package cattivole.spectra

import cattivole.spectra.core.EspChip
import cattivole.spectra.core.EspFlashException
import cattivole.spectra.core.EspFlashFailure
import cattivole.spectra.core.EspFlashProgress
import cattivole.spectra.core.EspFlashSession
import cattivole.spectra.core.EspPipe
import cattivole.spectra.core.EspReset
import cattivole.spectra.core.EspRom
import cattivole.spectra.core.EspSlip
import cattivole.spectra.core.FlashBundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EspFlashTest {
    @Test
    fun slipRoundTripsEscapesAndSplitChunks() {
        val payload = byteArrayOf(0x00, 0xC0.toByte(), 0xDB.toByte(), 0x11, 0xC0.toByte())
        val encoded = EspSlip.encode(payload)
        assertEquals(0xC0, encoded.first().toInt() and 0xFF)
        assertEquals(0xC0, encoded.last().toInt() and 0xFF)
        val decoder = EspSlip.Decoder()
        val frames = ArrayList<ByteArray>()
        for (b in encoded) frames += decoder.push(byteArrayOf(b))
        assertEquals(1, frames.size)
        assertArrayEquals(payload, frames[0])
    }

    @Test
    fun syncPacketMatchesTheRomSequence() {
        val packet = EspRom.sync()
        assertEquals(0x00, packet[0].toInt() and 0xFF)
        assertEquals(EspRom.SYNC, packet[1].toInt() and 0xFF)
        assertEquals(36, packet[2].toInt() and 0xFF)
        assertEquals(0x07, packet[8].toInt() and 0xFF)
        assertEquals(0x07, packet[9].toInt() and 0xFF)
        assertEquals(0x12, packet[10].toInt() and 0xFF)
        assertEquals(0x20, packet[11].toInt() and 0xFF)
        assertTrue(packet.copyOfRange(12, packet.size).all { it == 0x55.toByte() })
        assertEquals(0xEF xor 0x01 xor 0x02, EspRom.checksum(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun flashBeginCarriesTheUnencryptedWord() {
        val packet = EspRom.flashBegin(eraseSize = 4, blocks = 1, blockSize = EspRom.BLOCK, offset = 0x10000)
        assertEquals(EspRom.FLASH_BEGIN, packet[1].toInt() and 0xFF)
        assertEquals(20, packet[2].toInt() and 0xFF)
        val words = (0 until 5).map { i ->
            val at = 8 + i * 4
            (packet[at].toLong() and 0xFF) or
                ((packet[at + 1].toLong() and 0xFF) shl 8) or
                ((packet[at + 2].toLong() and 0xFF) shl 16) or
                ((packet[at + 3].toLong() and 0xFF) shl 24)
        }
        assertEquals(listOf(4L, 1L, EspRom.BLOCK.toLong(), 0x10000L, 0L), words)
    }

    @Test
    fun usbJtagResetPassesThroughBothLinesHigh() {
        val steps = EspReset.enter(EspReset.Kind.USB_JTAG)
        assertTrue(steps.any { it.dtr && it.rts })
        val both = steps.indexOfFirst { it.dtr && it.rts }
        assertEquals(false, steps[both + 1].dtr)
        assertEquals(true, steps[both + 1].rts)
    }

    @Test
    fun uartResetHoldsIo0LowForTheRequestedTime() {
        val steps = EspReset.enter(EspReset.Kind.UART, 550)
        val io0Low = steps.single { it.dtr && !it.rts }
        assertEquals(550L, io0Low.waitMs)
        assertEquals(false, steps.last().dtr)
        assertEquals(false, steps.last().rts)
    }

    @Test
    fun bundleAcceptsZipAndRejectsAnAppImage() {
        val boot = byteArrayOf(0xE9.toByte(), 1, 2, 3, 4, 5, 6, 7)
        val app = byteArrayOf(0xE9.toByte(), 9, 9, 9)
        val zip = zipOf(
            "flash.json" to """{"chip":"esp32s3","flashSize":8388608,"files":[{"offset":"0x0","name":"bootloader.bin"},{"offset":65536,"name":"firmware.bin"}]}""".toByteArray(),
            "bootloader.bin" to boot,
            "firmware.bin" to app,
        )
        val loaded = FlashBundle.load(zip) as FlashBundle.Load.Ready
        assertEquals(EspChip.S3, loaded.image.chip)
        assertEquals(2, loaded.image.segments.size)
        assertEquals(0L, loaded.image.segments[0].offset)
        assertEquals(0x10000L, loaded.image.segments[1].offset)
        val c5 = zipOf(
            "flash.json" to """{"chip":"esp32c5","flashSize":4194304,"files":[{"offset":8192,"name":"bootloader.bin"},{"offset":65536,"name":"firmware.bin"}]}""".toByteArray(),
            "bootloader.bin" to boot,
            "firmware.bin" to app,
        )
        val c5Image = (FlashBundle.load(c5) as FlashBundle.Load.Ready).image
        assertEquals(EspChip.C5, c5Image.chip)
        assertEquals(0x2000L, c5Image.segments[0].offset)
        val classic = zipOf(
            "flash.json" to """{"chip":"esp32","flashSize":4194304,"files":[{"offset":4096,"name":"bootloader.bin"},{"offset":65536,"name":"firmware.bin"}]}""".toByteArray(),
            "bootloader.bin" to boot,
            "firmware.bin" to app,
        )
        assertEquals(EspChip.ESP32, (FlashBundle.load(classic) as FlashBundle.Load.Ready).image.chip)
        val classicAtZero = zipOf(
            "flash.json" to """{"chip":"esp32","files":[{"offset":0,"name":"bootloader.bin"}]}""".toByteArray(),
            "bootloader.bin" to boot,
        )
        assertEquals(EspFlashFailure.NO_BUNDLE_BOOTLOADER, (FlashBundle.load(classicAtZero) as FlashBundle.Load.Rejected).failure)
        assertEquals(EspFlashFailure.APP_IMAGE, (FlashBundle.load(app) as FlashBundle.Load.Rejected).failure)
        val overlap = zipOf(
            "flash.json" to """{"chip":"esp32s3","files":[{"offset":0,"name":"a.bin"},{"offset":4,"name":"b.bin"}]}""".toByteArray(),
            "a.bin" to boot,
            "b.bin" to app,
        )
        assertEquals(EspFlashFailure.BAD_LAYOUT, (FlashBundle.load(overlap) as FlashBundle.Load.Rejected).failure)
        val noBoot = zipOf(
            "flash.json" to """{"chip":"esp32s3","files":[{"offset":65536,"name":"firmware.bin"}]}""".toByteArray(),
            "firmware.bin" to app,
        )
        assertEquals(EspFlashFailure.NO_BUNDLE_BOOTLOADER, (FlashBundle.load(noBoot) as FlashBundle.Load.Rejected).failure)
    }

    @Test
    fun sessionWritesBothSegmentsAndChecksTheRomMd5() {
        val rom = FakeRom()
        val session = EspFlashSession(rom, sleep = {}, syncAttempts = 1, syncWindowMs = 50)
        val boot = ByteArray(100) { if (it == 0) 0xE9.toByte() else it.toByte() }
        val app = ByteArray(50) { if (it == 0) 0xE9.toByte() else (0x40 + it).toByte() }
        val image = FlashBundle.Image(
            EspChip.S3,
            FlashBundle.FLASH_8MB,
            listOf(FlashBundle.Segment(0, boot), FlashBundle.Segment(0x10000, app)),
        )
        val stages = ArrayList<EspFlashProgress.Stage>()
        session.flash(image, EspReset.Kind.USB_JTAG) { stages.add(it) }
        assertTrue(stages.last() is EspFlashProgress.Stage.Done)
        assertEquals(boot.toList(), rom.bytes(0, boot.size))
        assertEquals(app.toList(), rom.bytes(0x10000, app.size))
        assertEquals(listOf(0L, 0L), rom.begins.map { it.encrypted })
        assertTrue(rom.begins.all { it.blockSize == EspRom.BLOCK })
        assertEquals(2, rom.md5Checks)
        assertTrue(rom.sawSecurity)
        assertTrue(rom.sawSpiAttach)
    }

    @Test
    fun sessionRejectsAChipThatIsNotAnS3() {
        val rom = FakeRom()
        rom.chipId = 0
        val session = EspFlashSession(rom, sleep = {}, syncAttempts = 1, syncWindowMs = 20)
        val boot = byteArrayOf(0xE9.toByte(), 0, 0, 0)
        val error = runCatching {
            session.flash(FlashBundle.Image(EspChip.S3, FlashBundle.FLASH_8MB, listOf(FlashBundle.Segment(0, boot))), EspReset.Kind.USB_JTAG) {}
        }.exceptionOrNull()
        assertTrue(error is EspFlashException)
        assertEquals(EspFlashFailure.NOT_S3, (error as EspFlashException).failure)
        assertTrue(rom.begins.isEmpty())
    }

    @Test
    fun esp32RomOmitsTheEncryptionWordAndIsRecognisedByMagic() {
        val packet = EspRom.flashBegin(eraseSize = 4, blocks = 1, blockSize = EspRom.BLOCK, offset = 0x1000, extended = false)
        assertEquals(16, packet[2].toInt() and 0xFF)
        val rom = FakeRom()
        rom.securityOk = false
        rom.magic = EspChip.ESP32_MAGIC
        val session = EspFlashSession(rom, sleep = {}, syncAttempts = 1, syncWindowMs = 50)
        val boot = byteArrayOf(0xE9.toByte(), 1, 2, 3)
        val image = FlashBundle.Image(EspChip.ESP32, 4L * 1024 * 1024, listOf(FlashBundle.Segment(0x1000, boot)))
        session.flash(image, EspReset.Kind.UART) {}
        assertEquals(listOf(0L), rom.begins.map { it.encrypted })
        assertEquals(16, rom.beginSizes.single())
        assertEquals(boot.toList(), rom.bytes(0x1000, boot.size))
    }

    @Test
    fun c5IsAcceptedFromSecurityInfo() {
        val rom = FakeRom()
        rom.chipId = EspRom.ESP32_C5
        val session = EspFlashSession(rom, sleep = {}, syncAttempts = 1, syncWindowMs = 50)
        val boot = byteArrayOf(0xE9.toByte(), 1, 2, 3)
        session.flash(
            FlashBundle.Image(EspChip.C5, 4L * 1024 * 1024, listOf(FlashBundle.Segment(0x2000, boot))),
            EspReset.Kind.USB_JTAG,
        ) {}
        assertEquals(20, rom.beginSizes.single())
        assertEquals(boot.toList(), rom.bytes(0x2000, boot.size))
    }

    @Test
    fun cancelStopsBetweenBlocks() {
        val rom = FakeRom()
        val session = EspFlashSession(rom, sleep = {}, syncAttempts = 1, syncWindowMs = 50)
        val boot = ByteArray(3000) { if (it == 0) 0xE9.toByte() else 0x11 }
        val error = runCatching {
            session.flash(FlashBundle.Image(EspChip.S3, FlashBundle.FLASH_8MB, listOf(FlashBundle.Segment(0, boot))), EspReset.Kind.USB_JTAG) {
                if (it is EspFlashProgress.Stage.Writing) session.cancelled = true
            }
        }.exceptionOrNull()
        assertEquals(EspFlashFailure.CANCELLED, (error as EspFlashException).failure)
        assertEquals(0, rom.md5Checks)
    }

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

/** A ROM that answers the commands a real ESP32-S3 answers, and keeps what was written. */
private class FakeRom : EspPipe {
    var chipId: Int = EspRom.ESP32_S3
    var securityOk = true
    var magic = 0L
    val begins = ArrayList<Begin>()
    val beginSizes = ArrayList<Int>()
    var md5Checks = 0
    var sawSecurity = false
    var sawSpiAttach = false

    private val flash = HashMap<Long, Byte>()
    private val incoming = ArrayDeque<Byte>()
    private val decoder = EspSlip.Decoder()
    private var cursor = 0L
    private var sequence = 0

    data class Begin(val erase: Long, val blocks: Int, val blockSize: Int, val offset: Long, val encrypted: Long)

    fun bytes(offset: Long, size: Int): List<Byte> = (0 until size).map { flash[offset + it] ?: 0 }

    override fun write(bytes: ByteArray) {
        for (frame in decoder.push(bytes)) handle(frame)
    }

    override fun read(timeoutMs: Long): ByteArray {
        if (incoming.isEmpty()) return ByteArray(0)
        return ByteArray(incoming.size) { incoming.removeFirst() }
    }

    private fun handle(frame: ByteArray) {
        val command = frame[1].toInt() and 0xFF
        val data = frame.copyOfRange(8, frame.size)
        when (command) {
            EspRom.SYNC -> repeat(2) { reply(command) }
            EspRom.SECURITY -> {
                sawSecurity = true
                if (!securityOk) {
                    // The original ESP32 ROM rejects it under another opcode, sometimes more than once.
                    repeat(2) { reply(0x00, status = 1, error = 0x05) }
                } else {
                    val payload = ByteArray(20)
                    payload[12] = chipId.toByte()
                    reply(command, payload = payload)
                }
            }
            EspRom.SPI_ATTACH -> {
                sawSpiAttach = true
                reply(command)
            }
            EspRom.READ_REG -> reply(command, value = if (u32(data, 0) == EspChip.ESP32_MAGIC_REG) magic else 0L)
            EspRom.SPI_PARAMS, EspRom.WRITE_REG -> reply(command)
            EspRom.FLASH_BEGIN -> {
                sequence = 0
                cursor = u32(data, 12)
                beginSizes.add(data.size)
                val encrypted = if (data.size >= 20) u32(data, 16) else 0L
                begins.add(Begin(u32(data, 0), u32(data, 4).toInt(), u32(data, 8).toInt(), cursor, encrypted))
                reply(command)
            }
            EspRom.FLASH_DATA -> {
                val block = data.copyOfRange(16, data.size)
                val sum = frame.copyOfRange(4, 8).let { u32(it, 0).toInt() }
                if (sum != EspRom.checksum(block) || u32(data, 4).toInt() != sequence) {
                    reply(command, status = 1, error = 0x07)
                    return
                }
                sequence += 1
                for (b in block) {
                    flash[cursor] = b
                    cursor += 1
                }
                reply(command)
            }
            EspRom.FLASH_MD5 -> {
                md5Checks += 1
                val address = u32(data, 0)
                val size = u32(data, 4).toInt()
                val region = ByteArray(size) { flash[address + it] ?: 0xFF.toByte() }
                val hex = MessageDigest.getInstance("MD5").digest(region).joinToString("") { "%02x".format(it) }
                reply(command, payload = hex.toByteArray(Charsets.US_ASCII))
            }
            else -> reply(command, status = 1, error = 0x05)
        }
    }

    private fun reply(command: Int, value: Long = 0, payload: ByteArray = ByteArray(0), status: Int = 0, error: Int = 0) {
        val body = ByteArray(payload.size + 4)
        payload.copyInto(body)
        body[payload.size] = status.toByte()
        body[payload.size + 1] = error.toByte()
        val frame = ByteArray(8 + body.size)
        frame[0] = 0x01
        frame[1] = command.toByte()
        frame[2] = (body.size and 0xFF).toByte()
        frame[3] = ((body.size shr 8) and 0xFF).toByte()
        for (i in 0 until 4) frame[4 + i] = ((value shr (8 * i)) and 0xFF).toByte()
        body.copyInto(frame, 8)
        EspSlip.encode(frame).forEach { incoming.add(it) }
    }

    private fun u32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
}
