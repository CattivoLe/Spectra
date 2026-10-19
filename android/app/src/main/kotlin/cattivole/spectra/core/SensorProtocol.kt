package cattivole.spectra.core

import java.util.UUID

/**
 * The BLE contract with the Cardputer firmware. docs/protocol.md is the
 * source of truth; the iOS app implements the same bytes.
 */
data class LoraDevice(
    val freqKhz: Long,
    val spreadingFactor: Int,
    val rssi: Int,
    val snr: Int,
    val flags: Int,
    val id: Long,
) {
    val lorawan: Boolean get() = flags and 0x01 != 0
    val privateNetwork: Boolean get() = flags and 0x02 != 0
    val join: Boolean get() = flags and 0x04 != 0
    val downlink: Boolean get() = flags and 0x08 != 0
    val confirmed: Boolean get() = flags and 0x10 != 0

    fun title(): String {
        val hex = "%08X".format(id and 0xFFFF_FFFFL)
        return when {
            join -> "Join $hex"
            lorawan -> "${if (downlink) "↓" else "↑"} DevAddr $hex"
            else -> "Packet $hex"
        }
    }

    fun detail(): String {
        val parts = mutableListOf(
            String.format(java.util.Locale.US, "%.3f MHz · SF%d · SNR %d dB", freqKhz / 1000.0, spreadingFactor, snr),
        )
        if (lorawan && !join) {
            parts.add(if (downlink) "downlink" else "uplink")
            if (confirmed) parts.add("confirmed")
        }
        if (privateNetwork) parts.add("private")
        return parts.joinToString(" · ")
    }
}

object SensorProtocol {
    const val VERSION = 8

    val SERVICE: UUID = UUID.fromString("53500001-8f2d-4b6a-9c1e-737065637472")
    val INFO: UUID = UUID.fromString("53500002-8f2d-4b6a-9c1e-737065637472")
    val CONTROL: UUID = UUID.fromString("53500003-8f2d-4b6a-9c1e-737065637472")
    val RESULTS: UUID = UUID.fromString("53500004-8f2d-4b6a-9c1e-737065637472")
    val FIRMWARE: UUID = UUID.fromString("53500005-8f2d-4b6a-9c1e-737065637472")

    sealed class Command {
        data object ScanOnce : Command()
        data class SetInterval(val seconds: Int) : Command()
        data class AckSaved(val captureId: Long) : Command()
        data object ScanLora : Command()
        /** Store a network the phone already trusts. The sensor also stores one after its own join succeeds. */
        data class RememberWifi(val ssid: String, val password: String) : Command()
        data class ForgetWifi(val ssid: String) : Command()
        /** This phone's system name, shown on the sensor while the link is up. */
        data class SetPeer(val name: String) : Command()
        /** The sensor's uploaded batch is stored. The sensor deletes that batch. */
        data object AckKnown : Command()
        /** The next RememberWifi commands replace the list on the sensor. */
        data object ResetKnown : Command()
        /** Pin the radio to one channel and stream raw frames (`0x24`). [bssid] is
         *  an optional filter; null sends every frame on the channel. */
        data class StartRawCapture(val channel: Int, val bssid: MacAddress?) : Command()
        /** Stop the raw-frame stream. */
        data object StopRawCapture : Command()

        val data: ByteArray
            get() = when (this) {
                ScanOnce -> byteArrayOf(0x01)
                is SetInterval -> byteArrayOf(0x02, seconds.coerceIn(0, 255).toByte())
                ScanLora -> byteArrayOf(0x04)
                is AckSaved -> byteArrayOf(
                    0x03,
                    captureId.toByte(),
                    (captureId shr 8).toByte(),
                    (captureId shr 16).toByte(),
                    (captureId shr 24).toByte(),
                )
                is RememberWifi -> wifiCreds(0x05, ssid, password)
                is ForgetWifi -> wifiCreds(0x06, ssid, null)
                AckKnown -> byteArrayOf(0x08)
                ResetKnown -> byteArrayOf(0x09)
                is StartRawCapture -> byteArrayOf(0x0A, channel.toU8(), if (bssid == null) 0 else 1) +
                    (bssid?.bytes ?: ByteArray(6))
                StopRawCapture -> byteArrayOf(0x0B)
                is SetPeer -> byteArrayOf(0x07) + name.encodeToByteArray().let { bytes ->
                    val end = utf8Cut(bytes, 31)
                    bytes.copyOf(end)
                }
            }

        private fun wifiCreds(command: Int, ssid: String, password: String?): ByteArray {
            val name = ssid.encodeToByteArray().let { it.copyOf(utf8Cut(it, 32)) }
            val secret = password?.encodeToByteArray()?.let { it.copyOf(utf8Cut(it, 64)) }
            val out = ArrayList<Byte>(3 + name.size + (secret?.size ?: 0))
            out.add(command.toByte())
            out.add(name.size.toByte())
            name.forEach { out.add(it) }
            if (secret != null) {
                out.add(secret.size.toByte())
                secret.forEach { out.add(it) }
            }
            return out.toByteArray()
        }

        /** End index of a UTF-8 prefix that fits in [max] bytes. */
        private fun utf8Cut(bytes: ByteArray, max: Int): Int {
            if (bytes.size <= max) return bytes.size
            var end = max
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return end
        }
    }

    sealed class Message {
        data class ScanStarted(val scanId: Int) : Message()
        data class AccessPoint(val scanId: Int, val reading: AccessPointReading) : Message()
        data class ScanFinished(
            val scanId: Int,
            val count: Int,
            val durationMs: Int,
            val stationCount: Int,
            val probeCount: Int,
        ) : Message()
        data class Station(val scanId: Int, val client: WifiClient) : Message()
        data class ProbeReq(val scanId: Int, val probe: ProbeRequest) : Message()
        data class Deauth(val scanId: Int, val event: DeauthEvent) : Message()
        data class SsidReveal(val scanId: Int, val bssid: MacAddress, val rssi: Int, val ssid: String) : Message()
        data class Traffic(val scanId: Int, val bssid: MacAddress, val bytes: Long, val packets: Int) : Message()
        data class VendorIe(val scanId: Int, val bssid: MacAddress, val deviceName: String, val manufacturer: String) : Message()
        data class Battery(val scanId: Int, val level: Int, val isCharging: Boolean) : Message()
        data class Lora(val scanId: Int, val device: LoraDevice) : Message()
        data class LoraScan(val scanId: Int, val started: Boolean, val count: Int, val durationMs: Int) : Message()
        data class LoraProgress(
            val scanId: Int,
            val freqKhz: Long,
            val spreadingFactor: Int,
            val step: Int,
            val total: Int,
        ) : Message() {
            fun line(): String = String.format(
                java.util.Locale.US,
                "%d.%03d MHz · SF%d · %d/%d",
                freqKhz / 1000,
                freqKhz % 1000,
                spreadingFactor,
                step,
                total,
            )
        }
        data class SavedPosition(val captureId: Long, val latitude: Double, val longitude: Double) : Message()
        data class Gps(val valid: Boolean, val latitude: Double, val longitude: Double, val satellites: Int) : Message()
        data class KnownWifi(val index: Int, val total: Int, val ssid: String, val password: String) : Message()
        data class Saved(
            val kind: Int,
            val captureId: Long,
            val unix: Long,
            val index: Int,
            val total: Int,
            val title: String,
            val detail: String,
            val trailing: String,
        ) : Message()
        data class RawFrame(val fragment: RawFrameFragment) : Message()
    }

    /**
     * One `0x24` notification: a whole captured frame or a slice of one. The
     * metadata (timestamp, channel, RSSI, original length) rides on fragment 0;
     * later fragments carry only more bytes. [RawFrameAssembler] stitches them.
     */
    data class RawFrameFragment(
        val seq: Int,
        val fragmentIndex: Int,
        val fragmentCount: Int,
        /** Microseconds since the sensor booted; only meaningful on fragment 0. */
        val timestampMicros: Long,
        val channel: Int,
        val rssi: Int,
        val originalLength: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is RawFrameFragment && seq == other.seq && fragmentIndex == other.fragmentIndex &&
                fragmentCount == other.fragmentCount && timestampMicros == other.timestampMicros &&
                channel == other.channel && rssi == other.rssi &&
                originalLength == other.originalLength && payload.contentEquals(other.payload)

        override fun hashCode(): Int = seq * 31 + payload.contentHashCode()
    }

    private enum class MessageType(val raw: Int) {
        SCAN_STARTED(0x10),
        ACCESS_POINT(0x11),
        SCAN_FINISHED(0x12),
        STATION(0x13),
        PROBE(0x14),
        DEAUTH(0x15),
        SSID_REVEAL(0x16),
        TRAFFIC(0x17),
        VENDOR_IE(0x18),
        BATTERY(0x19),
        SAVED_WIFI(0x1A),
        SAVED_BLE(0x1B),
        SAVED_LAN(0x1C),
        SAVED_GPS(0x1D),
        LORA_DEVICE(0x1E),
        LORA_SCAN(0x1F),
        SAVED_LORA(0x20),
        GPS(0x21),
        LORA_PROGRESS(0x22),
        KNOWN_WIFI(0x23),
        RAW_FRAME(0x24),
        ;

        companion object {
            fun from(raw: Int): MessageType? = entries.firstOrNull { it.raw == raw }
        }
    }

    fun decode(data: ByteArray): Message? {
        if (data.isEmpty()) return null
        val type = MessageType.from(data.u8(0)) ?: return null
        if (type == MessageType.KNOWN_WIFI) return decodeKnownWifi(data)
        if (type == MessageType.SAVED_GPS) return decodeSavedGps(data)
        if (type == MessageType.GPS) return decodeGps(data)
        if (type == MessageType.RAW_FRAME) return decodeRawFrame(data)
        if (type == MessageType.SAVED_WIFI || type == MessageType.SAVED_BLE ||
            type == MessageType.SAVED_LAN || type == MessageType.SAVED_LORA
        ) {
            return decodeSaved(data)
        }
        if (data.size < 3) return null
        val scanId = data.u16le(1)
        return when (type) {
            MessageType.SCAN_STARTED -> Message.ScanStarted(scanId)
            MessageType.SCAN_FINISHED -> {
                if (data.size < 7) return null
                val stations = if (data.size >= 9) data.u16le(7) else 0
                val probes = if (data.size >= 11) data.u16le(9) else 0
                Message.ScanFinished(scanId, data.u16le(3), data.u16le(5), stations, probes)
            }
            MessageType.ACCESS_POINT -> decodeAccessPoint(data, scanId)
            MessageType.STATION -> decodeStation(data, scanId)
            MessageType.PROBE -> decodeProbe(data, scanId)
            MessageType.DEAUTH -> decodeDeauth(data, scanId)
            MessageType.SSID_REVEAL -> decodeReveal(data, scanId)
            MessageType.TRAFFIC -> decodeTraffic(data, scanId)
            MessageType.VENDOR_IE -> decodeVendor(data, scanId)
            MessageType.BATTERY -> {
                if (data.size < 5) return null
                Message.Battery(scanId, minOf(data.u8(3), 100), data.u8(4) and 0x01 != 0)
            }
            MessageType.LORA_DEVICE -> decodeLora(data, scanId)
            MessageType.LORA_SCAN -> {
                if (data.size < 8) return null
                Message.LoraScan(scanId, data.u8(3) == 1, data.u16le(4), data.u16le(6))
            }
            MessageType.LORA_PROGRESS -> {
                if (data.size < 12) return null
                Message.LoraProgress(scanId, data.u32le(3), data.u8(7), data.u16le(8), data.u16le(10))
            }
            MessageType.SAVED_WIFI, MessageType.SAVED_BLE, MessageType.SAVED_LAN, MessageType.SAVED_LORA -> decodeSaved(data)
            MessageType.RAW_FRAME -> decodeRawFrame(data)
            MessageType.SAVED_GPS, MessageType.GPS, MessageType.KNOWN_WIFI -> null
        }
    }

    private fun decodeRawFrame(data: ByteArray): Message.RawFrame? {
        if (data.size < 5) return null
        val seq = data.u16le(1)
        val fragmentIndex = data.u8(3)
        val fragmentCount = data.u8(4)
        if (fragmentCount < 1 || fragmentIndex >= fragmentCount) return null
        return if (fragmentIndex == 0) {
            if (data.size < 13) return null
            Message.RawFrame(
                RawFrameFragment(
                    seq = seq,
                    fragmentIndex = 0,
                    fragmentCount = fragmentCount,
                    timestampMicros = data.u32le(5),
                    channel = data.u8(9),
                    rssi = data.i8(10),
                    originalLength = data.u16le(11),
                    payload = data.copyOfRange(13, data.size),
                ),
            )
        } else {
            Message.RawFrame(
                RawFrameFragment(
                    seq = seq,
                    fragmentIndex = fragmentIndex,
                    fragmentCount = fragmentCount,
                    timestampMicros = 0,
                    channel = 0,
                    rssi = 0,
                    originalLength = 0,
                    payload = data.copyOfRange(5, data.size),
                ),
            )
        }
    }

    private fun e7(data: ByteArray, index: Int): Double = data.u32le(index).toInt() / 1e7

    private fun i16(data: ByteArray, index: Int): Int {
        val v = data.u16le(index)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    private fun decodeKnownWifi(data: ByteArray): Message.KnownWifi? {
        if (data.size < 5) return null
        val index = data.u8(1)
        val total = data.u8(2)
        val ssidLen = data.u8(3)
        if (ssidLen == 0 || ssidLen > 32 || data.size < 5 + ssidLen) return null
        val ssid = String(data, 4, ssidLen, Charsets.UTF_8)
        val passLen = data.u8(4 + ssidLen)
        if (passLen > 64 || data.size < 5 + ssidLen + passLen) return null
        val password = if (passLen == 0) "" else String(data, 5 + ssidLen, passLen, Charsets.UTF_8)
        if (total == 0 || index >= total) return null
        return Message.KnownWifi(index, total, ssid, password)
    }

    private fun decodeSavedGps(data: ByteArray): Message.SavedPosition? {
        if (data.size < 18) return null
        return Message.SavedPosition(data.u32le(1), e7(data, 9), e7(data, 13))
    }

    private fun decodeGps(data: ByteArray): Message.Gps? {
        if (data.size < 11) return null
        val valid = data.u8(10) and 0x01 != 0
        return Message.Gps(valid, if (valid) e7(data, 1) else 0.0, if (valid) e7(data, 5) else 0.0, data.u8(9))
    }

    private fun decodeLora(data: ByteArray, scanId: Int): Message.Lora? {
        if (data.size < 16) return null
        return Message.Lora(
            scanId,
            LoraDevice(
                freqKhz = data.u32le(3),
                spreadingFactor = data.u8(7),
                rssi = i16(data, 8),
                snr = data.i8(10),
                flags = data.u8(11),
                id = data.u32le(12),
            ),
        )
    }

    private fun decodeSaved(data: ByteArray): Message.Saved? {
        if (data.size < 13) return null
        val kind = when (data.u8(0)) {
            0x1A -> 1
            0x1C -> 2
            0x1B -> 3
            0x20 -> 4
            else -> return null
        }
        val captureId = data.u32le(1)
        val unix = data.u32le(5)
        val index = data.u16le(9)
        val total = data.u16le(11)
        if (total <= 0 || index >= total) return null
        val title: String
        val detail: String
        val trailing: String
        when (data.u8(0)) {
            0x1A -> {
                if (data.size < 23) return null
                val ssidLen = data.u8(22)
                if (ssidLen > 32 || data.size < 23 + ssidLen) return null
                val ssid = if (ssidLen > 0) String(data, 23, ssidLen, Charsets.UTF_8) else ""
                title = ssid.ifEmpty { "Hidden network" }
                detail = "${MacAddress(data.copyOfRange(13, 19)).text} · ch ${data.u8(20)} · ${AuthMode(data.u8(21)).name}"
                trailing = data.i8(19).toString()
            }
            0x20 -> {
                if (data.size < 26) return null
                val device = LoraDevice(
                    freqKhz = data.u32le(13),
                    spreadingFactor = data.u8(17),
                    rssi = i16(data, 18),
                    snr = data.i8(20),
                    flags = data.u8(21),
                    id = data.u32le(22),
                )
                title = device.title()
                detail = device.detail()
                trailing = device.rssi.toString()
            }
            0x1B -> {
                if (data.size < 22) return null
                val nameLen = data.u8(21)
                if (nameLen > 20 || data.size < 22 + nameLen) return null
                val name = if (nameLen > 0) String(data, 22, nameLen, Charsets.UTF_8) else ""
                title = name.ifEmpty { "Unknown device" }
                detail = MacAddress(data.copyOfRange(13, 19)).text
                trailing = data.i8(20).toString()
            }
            else -> {
                if (data.size < 18) return null
                val nameLen = data.u8(17)
                if (nameLen > 24 || data.size < 18 + nameLen) return null
                val ip = "${data.u8(13)}.${data.u8(14)}.${data.u8(15)}.${data.u8(16)}"
                val name = if (nameLen > 0) String(data, 18, nameLen, Charsets.UTF_8) else ""
                title = name.ifEmpty { ip }
                detail = ip
                trailing = ""
            }
        }
        return Message.Saved(kind, captureId, unix, index, total, title, detail, trailing)
    }

    fun encode(message: Message): ByteArray {
        val out = ByteWriter()
        when (message) {
            is Message.ScanStarted -> {
                out.u8(MessageType.SCAN_STARTED.raw)
                out.u16(message.scanId)
            }
            is Message.ScanFinished -> {
                out.u8(MessageType.SCAN_FINISHED.raw)
                out.u16(message.scanId)
                out.u16(message.count)
                out.u16(message.durationMs)
                out.u16(message.stationCount)
                out.u16(message.probeCount)
            }
            is Message.AccessPoint -> encodeAccessPoint(out, message)
            is Message.Station -> {
                out.u8(MessageType.STATION.raw)
                out.u16(message.scanId)
                out.bytes(message.client.mac.bytes)
                out.bytes((message.client.apBssid ?: MacAddress(ByteArray(6))).bytes)
                out.i8(message.client.rssi)
                out.u8(if (message.client.isAssociated) 1 else 0)
            }
            is Message.ProbeReq -> {
                out.u8(MessageType.PROBE.raw)
                out.u16(message.scanId)
                out.bytes(message.probe.mac.bytes)
                out.i8(message.probe.rssi)
                out.u8(message.probe.channel)
                val ssid = (message.probe.ssid ?: "").encodeToByteArray().take(32).toByteArray()
                out.u8(ssid.size)
                out.bytes(ssid)
            }
            is Message.Deauth -> {
                out.u8(MessageType.DEAUTH.raw)
                out.u16(message.scanId)
                out.bytes(message.event.source.bytes)
                out.bytes(message.event.target.bytes)
                out.bytes(message.event.bssid.bytes)
                out.u16(message.event.reason)
                out.i8(message.event.rssi)
                out.u8(message.event.channel)
                out.u16(message.event.count)
            }
            is Message.SsidReveal -> {
                out.u8(MessageType.SSID_REVEAL.raw)
                out.u16(message.scanId)
                out.bytes(message.bssid.bytes)
                out.i8(message.rssi)
                val ssid = message.ssid.encodeToByteArray().take(32).toByteArray()
                out.u8(ssid.size)
                out.bytes(ssid)
            }
            is Message.Traffic -> {
                out.u8(MessageType.TRAFFIC.raw)
                out.u16(message.scanId)
                out.bytes(message.bssid.bytes)
                out.u32(message.bytes)
                out.u16(message.packets)
            }
            is Message.VendorIe -> {
                out.u8(MessageType.VENDOR_IE.raw)
                out.u16(message.scanId)
                out.bytes(message.bssid.bytes)
                val name = message.deviceName.encodeToByteArray().take(32).toByteArray()
                out.u8(name.size)
                out.bytes(name)
                val maker = message.manufacturer.encodeToByteArray().take(32).toByteArray()
                out.u8(maker.size)
                out.bytes(maker)
            }
            is Message.Battery -> {
                out.u8(MessageType.BATTERY.raw)
                out.u16(message.scanId)
                out.u8(message.level)
                out.u8(if (message.isCharging) 1 else 0)
            }
            is Message.Lora -> {
                out.u8(MessageType.LORA_DEVICE.raw)
                out.u16(message.scanId)
                out.u32(message.device.freqKhz)
                out.u8(message.device.spreadingFactor)
                out.u16(message.device.rssi and 0xFFFF)
                out.i8(message.device.snr)
                out.u8(message.device.flags)
                out.u32(message.device.id)
            }
            is Message.LoraScan -> {
                out.u8(MessageType.LORA_SCAN.raw)
                out.u16(message.scanId)
                out.u8(if (message.started) 1 else 2)
                out.u16(message.count)
                out.u16(message.durationMs)
            }
            is Message.LoraProgress -> {
                out.u8(MessageType.LORA_PROGRESS.raw)
                out.u16(message.scanId)
                out.u32(message.freqKhz)
                out.u8(message.spreadingFactor)
                out.u16(message.step)
                out.u16(message.total)
            }
            is Message.SavedPosition -> {
                out.u8(MessageType.SAVED_GPS.raw)
                out.u32(message.captureId)
                out.u32(0)
                out.u32(Math.round(message.latitude * 1e7))
                out.u32(Math.round(message.longitude * 1e7))
                out.u8(0)
            }
            is Message.Gps -> {
                out.u8(MessageType.GPS.raw)
                out.u32(if (message.valid) Math.round(message.latitude * 1e7) else 0)
                out.u32(if (message.valid) Math.round(message.longitude * 1e7) else 0)
                out.u8(message.satellites)
                out.u8(if (message.valid) 1 else 0)
            }
            is Message.KnownWifi -> {
                val name = message.ssid.encodeToByteArray().let { it.copyOf(it.size.coerceAtMost(32)) }
                val secret = message.password.encodeToByteArray().let { it.copyOf(it.size.coerceAtMost(64)) }
                out.u8(MessageType.KNOWN_WIFI.raw)
                out.u8(message.index)
                out.u8(message.total)
                out.u8(name.size)
                name.forEach { out.u8(it.toInt() and 0xFF) }
                out.u8(secret.size)
                secret.forEach { out.u8(it.toInt() and 0xFF) }
            }
            is Message.Saved -> Unit
            is Message.RawFrame -> {
                val f = message.fragment
                out.u8(MessageType.RAW_FRAME.raw)
                out.u16(f.seq)
                out.u8(f.fragmentIndex)
                out.u8(f.fragmentCount)
                if (f.fragmentIndex == 0) {
                    out.u32(f.timestampMicros)
                    out.u8(f.channel)
                    out.i8(f.rssi)
                    out.u16(f.originalLength)
                }
                out.bytes(f.payload)
            }
        }
        return out.toByteArray()
    }

    private fun decodeAccessPoint(data: ByteArray, scanId: Int): Message.AccessPoint? {
        if (data.size < 19) return null
        val ssidLength = data.u8(18)
        if (ssidLength > 32 || data.size < 19 + ssidLength) return null
        val country = if (data[16] != 0.toByte() && data[17] != 0.toByte()) {
            String(data, 16, 2, Charsets.US_ASCII)
        } else {
            null
        }
        val reading = AccessPointReading(
            bssid = MacAddress(data.copyOfRange(3, 9)),
            rssi = data.i8(9),
            channel = data.u8(10),
            secondaryChannel = SecondaryChannel.from(data.u8(11)),
            auth = AuthMode(data.u8(12)),
            pairwiseCipher = Cipher(data.u8(13)),
            groupCipher = Cipher(data.u8(14)),
            capabilities = data.u8(15),
            countryCode = country,
            ssidBytes = data.copyOfRange(19, 19 + ssidLength),
        )
        return Message.AccessPoint(scanId, reading)
    }

    private fun encodeAccessPoint(out: ByteWriter, message: Message.AccessPoint) {
        val reading = message.reading
        out.u8(MessageType.ACCESS_POINT.raw)
        out.u16(message.scanId)
        out.bytes(reading.bssid.bytes)
        out.i8(reading.rssi)
        out.u8(reading.channel)
        out.u8(reading.secondaryChannel.raw)
        out.u8(reading.auth.raw)
        out.u8(reading.pairwiseCipher.raw)
        out.u8(reading.groupCipher.raw)
        out.u8(reading.capabilities)
        val country = (reading.countryCode ?: "").encodeToByteArray()
        if (country.size >= 2) {
            out.bytes(country.copyOfRange(0, 2))
        } else {
            out.u8(0)
            out.u8(0)
        }
        val ssid = reading.ssidBytes.take(32).toByteArray()
        out.u8(ssid.size)
        out.bytes(ssid)
    }

    private fun decodeStation(data: ByteArray, scanId: Int): Message.Station? {
        if (data.size < 17) return null
        val ap = MacAddress(data.copyOfRange(9, 15))
        val client = WifiClient(
            mac = MacAddress(data.copyOfRange(3, 9)),
            apBssid = if (ap.isZero) null else ap,
            rssi = data.i8(15),
            isAssociated = data.u8(16) and 0x01 != 0,
        )
        return Message.Station(scanId, client)
    }

    private fun decodeProbe(data: ByteArray, scanId: Int): Message.ProbeReq? {
        if (data.size < 12) return null
        val ssidLen = data.u8(11)
        if (ssidLen > 32 || data.size < 12 + ssidLen) return null
        val ssid = if (ssidLen > 0) {
            String(data, 12, ssidLen, Charsets.UTF_8)
        } else {
            null
        }
        return Message.ProbeReq(
            scanId,
            ProbeRequest(MacAddress(data.copyOfRange(3, 9)), data.i8(9), ssid, data.u8(10)),
        )
    }

    private fun decodeDeauth(data: ByteArray, scanId: Int): Message.Deauth? {
        if (data.size < 27) return null
        val event = DeauthEvent(
            source = MacAddress(data.copyOfRange(3, 9)),
            target = MacAddress(data.copyOfRange(9, 15)),
            bssid = MacAddress(data.copyOfRange(15, 21)),
            reason = data.u16le(21),
            rssi = data.i8(23),
            channel = data.u8(24),
            count = data.u16le(25),
        )
        return Message.Deauth(scanId, event)
    }

    private fun decodeReveal(data: ByteArray, scanId: Int): Message.SsidReveal? {
        if (data.size < 11) return null
        val ssidLen = data.u8(10)
        if (ssidLen == 0 || ssidLen > 32 || data.size < 11 + ssidLen) return null
        val ssid = String(data, 11, ssidLen, Charsets.UTF_8)
        return Message.SsidReveal(scanId, MacAddress(data.copyOfRange(3, 9)), data.i8(9), ssid)
    }

    private fun decodeTraffic(data: ByteArray, scanId: Int): Message.Traffic? {
        if (data.size < 15) return null
        return Message.Traffic(
            scanId,
            MacAddress(data.copyOfRange(3, 9)),
            data.u32le(9),
            data.u16le(13),
        )
    }

    private fun decodeVendor(data: ByteArray, scanId: Int): Message.VendorIe? {
        if (data.size < 11) return null
        val nameLen = data.u8(9)
        if (nameLen > 32 || data.size < 11 + nameLen) return null
        val name = if (nameLen > 0) String(data, 10, nameLen, Charsets.UTF_8) else ""
        val makerAt = 10 + nameLen
        if (makerAt >= data.size) return null
        val makerLen = data.u8(makerAt)
        if (makerLen > 32 || data.size < makerAt + 1 + makerLen) return null
        val maker = if (makerLen > 0) String(data, makerAt + 1, makerLen, Charsets.UTF_8) else ""
        if (name.isEmpty() && maker.isEmpty()) return null
        return Message.VendorIe(scanId, MacAddress(data.copyOfRange(3, 9)), name, maker)
    }
}

data class SensorInfo(
    val proto: Int,
    val fw: String,
    val chip: String,
    val bands: List<String>,
    val interval: Int,
    val ota: Boolean = false,
    val batteryLevel: Int? = null,
    val isCharging: Boolean? = null,
    val lora: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val satellites: Int? = null,
    /** Firmware's fixed name, such as M5Cardputer. Absent on older firmware. */
    val name: String? = null,
    /** SSIDs the sensor will rejoin. Passwords are not on this characteristic. */
    val known: List<String> = emptyList(),
) {
    companion object {
        fun decode(json: String): SensorInfo? {
            val proto = jsonInt(json, "proto") ?: return null
            val fw = jsonString(json, "fw") ?: return null
            val chip = jsonString(json, "chip") ?: return null
            val interval = jsonInt(json, "interval") ?: return null
            val lat = jsonInt(json, "lat")
            val lon = jsonInt(json, "lon")
            return SensorInfo(
                proto = proto,
                fw = fw,
                chip = chip,
                bands = jsonStrings(json, "bands"),
                interval = interval,
                ota = jsonBool(json, "ota") ?: false,
                batteryLevel = jsonInt(json, "bat"),
                isCharging = jsonBool(json, "chg"),
                lora = jsonBool(json, "lora") ?: false,
                latitude = if (lat != null && lon != null) lat / 1e7 else null,
                longitude = if (lat != null && lon != null) lon / 1e7 else null,
                satellites = jsonInt(json, "sats"),
                name = jsonString(json, "name"),
                known = jsonStrings(json, "known"),
            )
        }

        private fun jsonString(json: String, key: String): String? =
            Regex(""""$key"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)

        private fun jsonInt(json: String, key: String): Int? =
            Regex(""""$key"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull()

        private fun jsonBool(json: String, key: String): Boolean? =
            Regex(""""$key"\s*:\s*(true|false)""").find(json)?.groupValues?.get(1)?.toBoolean()

        private fun jsonStrings(json: String, key: String): List<String> {
            val body = Regex(""""$key"\s*:\s*\[(.*?)]""").find(json)?.groupValues?.get(1) ?: return emptyList()
            return Regex(""""([^"]*)"""").findAll(body).map { it.groupValues[1] }.toList()
        }
    }
}
