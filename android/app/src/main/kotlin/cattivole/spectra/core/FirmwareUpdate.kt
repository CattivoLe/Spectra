package cattivole.spectra.core

/** Delivery of an ESP32 application image over the Firmware characteristic. */
object FirmwareUpdate {
    const val MAX_IMAGE_BYTES = 0x330000
    const val MAX_CHUNK_BYTES = 500
    const val MAGIC: Byte = 0xE9.toByte()

    sealed class Command {
        data class Begin(val size: Long, val crc: Long) : Command()
        data class Chunk(val offset: Long, val bytes: ByteArray) : Command()
        data object Finish : Command()
        data object Abort : Command()

        val data: ByteArray
            get() {
                val out = ByteWriter()
                when (this) {
                    is Begin -> {
                        out.u8(0x01)
                        out.u32(size)
                        out.u32(crc)
                    }
                    is Chunk -> {
                        out.u8(0x02)
                        out.u32(offset)
                        out.bytes(bytes)
                    }
                    Finish -> out.u8(0x03)
                    Abort -> out.u8(0x04)
                }
                return out.toByteArray()
            }
    }

    data class Status(val phase: Phase, val bytesAccepted: Long, val failure: Failure) {
        enum class Phase(val raw: Int) { READY(1), PROGRESS(2), DONE(3), FAILED(4) }

        enum class Failure(val raw: Int) {
            NONE(0),
            BUSY(1),
            TOO_LARGE(2),
            BEGIN_FAILED(3),
            BAD_OFFSET(4),
            WRITE_FAILED(5),
            CHECKSUM(6),
            FINALIZE_FAILED(7),
            ABORTED(8),
            BAD_IMAGE(9),
            ;

            val message: String
                get() = when (this) {
                    NONE, FINALIZE_FAILED -> "The sensor could not finish the update."
                    BUSY -> "The sensor is still scanning. Wait for the sweep to finish and try again."
                    TOO_LARGE -> "This image does not fit in the sensor's update slot."
                    BEGIN_FAILED -> "The sensor could not start the update. Flash it once over USB so the OTA slots exist, then try again."
                    BAD_OFFSET -> "The sensor lost its place in the image."
                    WRITE_FAILED -> "The sensor could not write the image."
                    CHECKSUM -> "The image failed its checksum. Transfer it again."
                    ABORTED -> "Update cancelled."
                    BAD_IMAGE -> "The sensor rejected the image header."
                }

            companion object {
                fun from(raw: Int): Failure = entries.firstOrNull { it.raw == raw } ?: FINALIZE_FAILED
            }
        }

        companion object {
            fun decode(data: ByteArray): Status? {
                if (data.size < 7 || data.u8(0) != 0x30) return null
                val phase = Phase.entries.firstOrNull { it.raw == data.u8(1) } ?: return null
                return Status(phase, data.u32le(2), Failure.from(data.u8(6)))
            }
        }
    }

    enum class ImageError(val message: String) {
        TOO_SMALL("This is not an ESP32 application image. Choose firmware.bin from pio run, not a merged flash image."),
        TOO_LARGE("This image does not fit in the sensor's update slot."),
        NOT_AN_APP_IMAGE("This is not an ESP32 application image. Choose firmware.bin from pio run, not a merged flash image."),
    }

    enum class TransferError(val message: String) {
        NOT_CONNECTED("The sensor is not connected."),
        TIMED_OUT("The update timed out. Keep the Cardputer close and try again."),
        CANCELLED("Update cancelled."),
        LINK_LOST("The link to the sensor dropped."),
    }

    fun validate(image: ByteArray): ImageError? = when {
        image.size < 32 -> ImageError.TOO_SMALL
        image.size > MAX_IMAGE_BYTES -> ImageError.TOO_LARGE
        image[0] != MAGIC -> ImageError.NOT_AN_APP_IMAGE
        else -> null
    }

    /** ISO-HDLC CRC-32, the same value as zlib. `123456789` hashes to `0xCBF43926`. */
    object Crc32 {
        fun hash(data: ByteArray): Long {
            var crc = 0xFFFF_FFFF.toInt()
            for (byte in data) {
                crc = crc xor (byte.toInt() and 0xFF)
                repeat(8) {
                    val mix = if (crc and 1 != 0) 0xEDB88320.toInt() else 0
                    crc = crc ushr 1 xor mix
                }
            }
            return crc.inv().toLong() and 0xFFFF_FFFFL
        }
    }
}

data class FirmwareUpdateProgress(val stage: Stage) {
    sealed class Stage {
        data object Preparing : Stage()
        data class Uploading(val sent: Int, val total: Int) : Stage()
        data object Finishing : Stage()
        data object Rebooting : Stage()
        data class Succeeded(val version: String) : Stage()
        data class Failed(val message: String) : Stage()

        val isRunning: Boolean
            get() = this is Preparing || this is Uploading || this is Finishing || this is Rebooting
    }

    val isRunning: Boolean get() = stage.isRunning
}

/**
 * Orders Begin, data chunks, and Finish. The link waits for a GATT response
 * before asking for the next packet, so the device never sees a gap.
 */
class FirmwareUpload(val image: ByteArray, chunkSize: Int) {
    sealed class Phase {
        data object Idle : Phase()
        data object WaitReady : Phase()
        data object Sending : Phase()
        data object WaitDone : Phase()
        data object Succeeded : Phase()
        data class Failed(val failure: FirmwareUpdate.Status.Failure) : Phase()
    }

    val crc: Long = FirmwareUpdate.Crc32.hash(image)
    val chunkSize: Int = minOf(FirmwareUpdate.MAX_CHUNK_BYTES, maxOf(1, chunkSize))
    var offset: Int = 0
        private set
    var phase: Phase = Phase.Idle
        private set

    fun begin(): ByteArray {
        phase = Phase.WaitReady
        offset = 0
        return FirmwareUpdate.Command.Begin(image.size.toLong(), crc).data
    }

    fun handle(status: FirmwareUpdate.Status): ByteArray? {
        when (status.phase) {
            FirmwareUpdate.Status.Phase.FAILED -> {
                phase = Phase.Failed(
                    if (status.failure == FirmwareUpdate.Status.Failure.NONE) {
                        FirmwareUpdate.Status.Failure.FINALIZE_FAILED
                    } else {
                        status.failure
                    },
                )
                return null
            }
            FirmwareUpdate.Status.Phase.DONE -> {
                phase = Phase.Succeeded
                return null
            }
            FirmwareUpdate.Status.Phase.READY -> {
                if (phase != Phase.WaitReady) return null
                phase = Phase.Sending
                return nextChunk()
            }
            FirmwareUpdate.Status.Phase.PROGRESS -> return null
        }
    }

    fun onWriteCompleted(): ByteArray? {
        if (phase != Phase.Sending) return null
        if (offset >= image.size) {
            phase = Phase.WaitDone
            return FirmwareUpdate.Command.Finish.data
        }
        return nextChunk()
    }

    val abortCommand: ByteArray get() = FirmwareUpdate.Command.Abort.data

    private fun nextChunk(): ByteArray {
        val end = minOf(offset + chunkSize, image.size)
        val payload = image.copyOfRange(offset, end)
        val packet = FirmwareUpdate.Command.Chunk(offset.toLong(), payload).data
        offset = end
        return packet
    }
}
