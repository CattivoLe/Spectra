package cattivole.spectra.core

import java.nio.ByteBuffer
import java.util.UUID

data class BleFields(
    val localName: String? = null,
    val manufacturerData: ByteArray? = null,
    val serviceUuids: List<String> = emptyList(),
    val overflowServiceUuids: List<String> = emptyList(),
    val solicitedServiceUuids: List<String> = emptyList(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val txPower: Int? = null,
    val isConnectable: Boolean? = null,
)

data class BleServiceRef(val uuid: String, val name: String?)

data class BleServiceData(val uuid: String, val name: String?, val data: ByteArray)

/** One Apple continuity message. Company id `0x004C`, then type, length, payload. */
sealed class AppleAdvertisement {
    data object AirPrint : AppleAdvertisement()
    data class IBeacon(val uuid: String, val major: Int, val minor: Int, val measuredPower: Int) : AppleAdvertisement()
    data object AirDrop : AppleAdvertisement()
    data class HomeKit(val category: String?) : AppleAdvertisement()
    data class Proximity(val pairing: ProximityPairing) : AppleAdvertisement()
    data class HeySiri(val device: String?) : AppleAdvertisement()
    data object AirPlayTarget : AppleAdvertisement()
    data object AirPlaySource : AppleAdvertisement()
    data object AutoUnlock : AppleAdvertisement()
    data object Handoff : AppleAdvertisement()
    data object WifiSettings : AppleAdvertisement()
    data object InstantHotspot : AppleAdvertisement()
    data class NearbyActionMessage(val action: NearbyAction?) : AppleAdvertisement()
    data class NearbyInfoMessage(val activity: NearbyActivity?) : AppleAdvertisement()
    data class FindMy(val status: FindMyStatus?) : AppleAdvertisement()

    val distinctiveName: String?
        get() = when (this) {
            AirPrint -> "AirPrint"
            is IBeacon -> "iBeacon"
            AirDrop -> "AirDrop"
            is HomeKit -> category ?: "HomeKit"
            is Proximity -> pairing.modelName ?: "Apple audio"
            is HeySiri -> device ?: "Hey Siri"
            AirPlayTarget, AirPlaySource -> "AirPlay"
            is FindMy -> "Find My"
            else -> null
        }

    /** Lower is a better name. Background chatter stays unnamed. */
    val nameRank: Int?
        get() = when (this) {
            is Proximity, is HeySiri -> 0
            is HomeKit -> 1
            AirPlayTarget, AirPlaySource -> 2
            is FindMy -> 3
            is IBeacon -> 4
            AirPrint -> 5
            AirDrop -> 6
            else -> null
        }

    val label: String
        get() = when (this) {
            AirPrint -> "AirPrint"
            is IBeacon -> "iBeacon"
            AirDrop -> "AirDrop"
            is HomeKit -> category ?: "HomeKit"
            is Proximity -> pairing.modelLabel
            is HeySiri -> device ?: "Hey Siri"
            AirPlayTarget -> "AirPlay target"
            AirPlaySource -> "AirPlay source"
            AutoUnlock -> "Auto Unlock"
            Handoff -> "Handoff"
            WifiSettings -> "Wi-Fi settings"
            InstantHotspot -> "Instant Hotspot"
            is NearbyActionMessage -> action?.label ?: "Nearby"
            is NearbyInfoMessage -> activity?.label ?: "Nearby"
            is FindMy -> "Find My"
        }

    val detail: String?
        get() = when (this) {
            is Proximity -> pairing.batterySummary
            is IBeacon -> "$uuid · $major · $minor · $measuredPower dBm"
            is HomeKit -> if (category == null) null else "HomeKit"
            is FindMy -> status?.summary
            else -> null
        }
}

/**
 * What an Offline Finding (Find My) advertisement says about the tag.
 *
 * A tag near its owner sends a short two-byte message. One that has lost its
 * owner sends the long one with a public key, so the Find My network can
 * report it: that is the form a tracker slipped into a bag transmits.
 */
data class FindMyStatus(
    /** The long advertisement: the tag is away from its owner. */
    val separated: Boolean,
    /** 0 full, 1 medium, 2 low, 3 critical. */
    val battery: Int?,
) {
    val batteryLabel: String?
        get() = when (battery) {
            0 -> "Battery full"
            1 -> "Battery medium"
            2 -> "Battery low"
            3 -> "Battery critical"
            else -> null
        }

    val ownerLabel: String get() = if (separated) "Away from its owner" else "Near its owner"

    val summary: String get() = listOfNotNull(ownerLabel, batteryLabel).joinToString(" · ")

    companion object {
        /** [payload] is the message body after the type and length bytes. */
        fun parse(payload: ByteArray): FindMyStatus? {
            if (payload.isEmpty()) return null
            return FindMyStatus(separated = payload.size >= 24, battery = (payload[0].toInt() and 0xFF) shr 6)
        }
    }
}

enum class NearbyActivity(val raw: Int, val label: String) {
    REPORTING_OFF(1, "Activity off"),
    LOCKED(3, "Locked"),
    AUDIO(5, "Audio playing"),
    SCREEN_CHANGE(7, "Screen changing"),
    VIDEO(9, "Video playing"),
    WATCH_NOTIFICATIONS(10, "Notifying a Watch"),
    ACTIVE(11, "In use"),
    DRIVING(13, "Driving"),
    CALL(14, "On a call"),
    ;

    companion object {
        fun from(nibble: Int): NearbyActivity? = entries.firstOrNull { it.raw == nibble }
    }
}

enum class NearbyAction(val raw: Int, val label: String) {
    APPLE_TV_SETUP(0x01, "Apple TV setup"),
    MOBILE_BACKUP(0x04, "Mobile backup"),
    WATCH_SETUP(0x05, "Watch setup"),
    APPLE_TV_PAIR(0x06, "Apple TV pairing"),
    INTERNET_RELAY(0x07, "Internet relay"),
    WIFI_PASSWORD(0x08, "Wi-Fi password"),
    IOS_SETUP(0x09, "iOS setup"),
    REPAIR(0x0A, "Repair"),
    SPEAKER_SETUP(0x0B, "Speaker setup"),
    APPLE_PAY(0x0C, "Apple Pay"),
    WHOLE_HOME_AUDIO(0x0D, "Whole-home audio"),
    DEVELOPER_PAIRING(0x0E, "Developer pairing"),
    ANSWERED_CALL(0x0F, "Call answered"),
    ENDED_CALL(0x10, "Call ended"),
    COMPANION_LINK(0x14, "Companion Link"),
    REMOTE_MANAGEMENT(0x15, "Remote management"),
    REMOTE_DISPLAY(0x17, "Remote display"),
    ;

    companion object {
        fun from(raw: Int): NearbyAction? = entries.firstOrNull { it.raw == raw }
    }
}

data class ProximityPairing(
    val modelId: Int,
    val modelName: String?,
    val leftBattery: Int?,
    val rightBattery: Int?,
    val caseBattery: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
) {
    val modelLabel: String
        get() {
            val hex = "0x%04X".format(modelId)
            return if (modelName != null) "$modelName ($hex)" else hex
        }

    val batterySummary: String?
        get() {
            val parts = buildList {
                leftBattery?.let { add(part("Left", it, leftCharging)) }
                rightBattery?.let { add(part("Right", it, rightCharging)) }
                caseBattery?.let { add(part("Case", it, caseCharging)) }
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

    private fun part(label: String, percent: Int, charging: Boolean): String =
        if (charging) "$label $percent% (charging)" else "$label $percent%"
}

data class BleAdvertisement(
    val localName: String?,
    val companyId: Int?,
    val companyName: String?,
    val manufacturerPayload: ByteArray,
    val services: List<BleServiceRef>,
    val solicitedServices: List<BleServiceRef>,
    val serviceData: List<BleServiceData>,
    val txPower: Int?,
    val isConnectable: Boolean?,
    val apple: List<AppleAdvertisement>,
) {
    companion object {
        const val APPLE_COMPANY = 0x004C

        fun parse(fields: BleFields): BleAdvertisement {
            var companyId: Int? = null
            var payload = ByteArray(0)
            val data = fields.manufacturerData
            if (data != null && data.size >= 2) {
                companyId = data.u8(0) or (data.u8(1) shl 8)
                payload = data.copyOfRange(2, data.size)
            }
            val seen = mutableSetOf<String>()
            val services = buildList {
                for (uuid in fields.serviceUuids + fields.overflowServiceUuids) {
                    val key = Catalog.canonicalUuid(uuid)
                    if (!seen.add(key)) continue
                    add(BleServiceRef(Catalog.displayUuid(uuid), Catalog.serviceName(uuid)))
                }
            }
            val solicited = fields.solicitedServiceUuids.map {
                BleServiceRef(Catalog.displayUuid(it), Catalog.serviceName(it))
            }
            val serviceData = fields.serviceData
                .map { (uuid, bytes) -> BleServiceData(Catalog.displayUuid(uuid), Catalog.serviceName(uuid), bytes) }
                .sortedBy { it.uuid }
            val apple = if (companyId == APPLE_COMPANY) parseApple(payload) else emptyList()
            return BleAdvertisement(
                localName = fields.localName,
                companyId = companyId,
                companyName = companyId?.let { Catalog.company(it) },
                manufacturerPayload = payload,
                services = services,
                solicitedServices = solicited,
                serviceData = serviceData,
                txPower = fields.txPower,
                isConnectable = fields.isConnectable,
                apple = apple,
            )
        }

        private fun parseApple(payload: ByteArray): List<AppleAdvertisement> {
            val messages = mutableListOf<AppleAdvertisement>()
            var index = 0
            while (index + 2 <= payload.size) {
                val type = payload.u8(index)
                val length = payload.u8(index + 1)
                val start = index + 2
                if (start + length > payload.size) break
                appleMessage(type, payload.copyOfRange(start, start + length))?.let { messages.add(it) }
                index = start + length
            }
            return messages
        }

        private fun appleMessage(type: Int, payload: ByteArray): AppleAdvertisement? = when (type) {
            0x02 -> if (payload.size == 21) {
                val uuid = uuidString(payload.copyOfRange(0, 16)) ?: return null
                val major = (payload.u8(16) shl 8) or payload.u8(17)
                val minor = (payload.u8(18) shl 8) or payload.u8(19)
                AppleAdvertisement.IBeacon(uuid, major, minor, payload.i8(20))
            } else {
                null
            }
            0x03 -> AppleAdvertisement.AirPrint
            0x05 -> AppleAdvertisement.AirDrop
            0x06 -> {
                val category = if (payload.size >= 9) {
                    homeKit[(payload.u8(7) shl 8) or payload.u8(8)]
                } else {
                    null
                }
                AppleAdvertisement.HomeKit(category)
            }
            0x07 -> proximity(payload)?.let { AppleAdvertisement.Proximity(it) }
            0x08 -> {
                val device = if (payload.size >= 6) siri[(payload.u8(4) shl 8) or payload.u8(5)] else null
                AppleAdvertisement.HeySiri(device)
            }
            0x09 -> AppleAdvertisement.AirPlayTarget
            0x0A -> AppleAdvertisement.AirPlaySource
            0x0B -> AppleAdvertisement.AutoUnlock
            0x0C -> AppleAdvertisement.Handoff
            0x0D -> AppleAdvertisement.WifiSettings
            0x0E -> AppleAdvertisement.InstantHotspot
            0x0F -> AppleAdvertisement.NearbyActionMessage(
                if (payload.size >= 2) NearbyAction.from(payload.u8(1)) else null,
            )
            0x10 -> AppleAdvertisement.NearbyInfoMessage(
                if (payload.isEmpty()) null else NearbyActivity.from(payload.u8(0) and 0x0F),
            )
            0x12 -> AppleAdvertisement.FindMy(FindMyStatus.parse(payload))
            else -> null
        }

        private fun proximity(bytes: ByteArray): ProximityPairing? {
            if (bytes.size < 3 || bytes.u8(0) != 0x01) return null
            val model = (bytes.u8(1) shl 8) or bytes.u8(2)
            var left: Int? = null
            var right: Int? = null
            var box: Int? = null
            var leftCharging = false
            var rightCharging = false
            var caseCharging = false
            if (bytes.size >= 5) {
                right = percent(bytes.u8(4) shr 4)
                left = percent(bytes.u8(4) and 0x0F)
            }
            if (bytes.size >= 6) {
                caseCharging = bytes.u8(5) and 0x40 != 0
                rightCharging = bytes.u8(5) and 0x20 != 0
                leftCharging = bytes.u8(5) and 0x10 != 0
                box = percent(bytes.u8(5) and 0x0F)
            }
            return ProximityPairing(
                model, audioModels[model], left, right, box, leftCharging, rightCharging, caseCharging,
            )
        }

        private fun percent(nibble: Int): Int? = if (nibble <= 10) nibble * 10 else null

        private fun uuidString(data: ByteArray): String? {
            if (data.size != 16) return null
            val buffer = ByteBuffer.wrap(data)
            return UUID(buffer.long, buffer.long).toString().uppercase()
        }

        private val audioModels = mapOf(
            0x0220 to "AirPods",
            0x0320 to "Powerbeats 3",
            0x0520 to "BeatsX",
            0x0620 to "Beats Solo 3",
            0x0A20 to "AirPods Max",
            0x0B20 to "Powerbeats Pro",
            0x0C20 to "Beats Solo Pro",
            0x0E20 to "AirPods Pro",
            0x0F20 to "AirPods (2nd generation)",
            0x1020 to "Beats Flex",
            0x1120 to "Beats Studio Buds",
            0x1220 to "Beats Fit Pro",
            0x1320 to "AirPods (3rd generation)",
            0x1420 to "AirPods Pro (2nd generation)",
        )

        private val siri = mapOf(
            0x0002 to "iPhone",
            0x0003 to "iPad",
            0x0007 to "HomePod",
            0x0009 to "MacBook",
            0x000A to "Watch",
        )

        private val homeKit = mapOf(
            0x0100 to "Other",
            0x0200 to "Bridge",
            0x0300 to "Fan",
            0x0400 to "Garage Door Opener",
            0x0500 to "Lightbulb",
            0x0600 to "Door Lock",
            0x0700 to "Outlet",
            0x0800 to "Switch",
            0x0900 to "Thermostat",
            0x0A00 to "Sensor",
            0x0B00 to "Security System",
            0x0C00 to "Door",
            0x0D00 to "Window",
            0x0E00 to "Window Covering",
            0x0F00 to "Programmable Switch",
            0x1000 to "Range Extender",
            0x1100 to "IP Camera",
            0x1200 to "Video Doorbell",
            0x1300 to "Air Purifier",
            0x1400 to "Heater",
            0x1500 to "Air Conditioner",
            0x1600 to "Humidifier",
            0x1700 to "Dehumidifier",
            0x1C00 to "Sprinklers",
            0x1D00 to "Faucets",
            0x1E00 to "Shower Systems",
        )
    }
}

class BleDevice(
    val address: String,
    val addressKind: String,
    rssi: Int,
    seen: Long,
    val transport: Transport = Transport.LE,
) {
    enum class Transport { LE, CLASSIC }

    var localName: String? = null
    var peripheralName: String? = null
    var companyId: Int? = null
    var companyName: String? = null
    var manufacturerPayload: ByteArray = ByteArray(0)
    var services: List<BleServiceRef> = emptyList()
    var solicitedServices: List<BleServiceRef> = emptyList()
    var serviceData: List<BleServiceData> = emptyList()
    var txPower: Int? = null
    var isConnectable: Boolean? = null
    var apple: List<AppleAdvertisement> = emptyList()
    var rssi: Int = rssi
    var orderRssi: Int = rssi
        private set
    private var smoothedRssi: Double = rssi.toDouble()
    val samples = mutableListOf<Sample>()
    var packetCount: Int = 0
    /** May be earlier than anything heard since launch: the device was met before. */
    var firstSeen: Long = seen
    var lastSeen: Long = seen

    /**
     * Start of the current unbroken stretch of advertisements. A silence
     * longer than [PRESENCE_GAP_MS] starts a new one, so "near you for 20 min"
     * means it really stayed, not that it came back.
     */
    var presentSince: Long = seen
        private set

    /** How long the device has been continuously nearby. */
    fun presentFor(now: Long): Long = (minOf(now, lastSeen) - presentSince).coerceAtLeast(0)

    /** The Find My status, when the device advertises Offline Finding. */
    val findMyStatus: FindMyStatus?
        get() = apple.filterIsInstance<AppleAdvertisement.FindMy>().firstOrNull()?.status

    val advertisesFindMy: Boolean
        get() = apple.any { it is AppleAdvertisement.FindMy }
    var deviceClass: String? = null

    data class Sample(val id: Int, val date: Long, val rssi: Int)

    private var sampleCounter = 0

    fun isNearby(now: Long): Boolean = now - lastSeen < NEARBY_WINDOW_MS

    val isSpectraSensor: Boolean
        get() {
            val sensor = SensorProtocol.SERVICE.toString()
            if (services.any { it.uuid.equals(sensor, ignoreCase = true) }) return true
            val name = localName ?: peripheralName ?: ""
            return name.startsWith("Spectra-")
        }

    val proximity: ProximityPairing?
        get() = apple.filterIsInstance<AppleAdvertisement.Proximity>().firstOrNull()?.pairing

    val heySiriDevice: String?
        get() = apple.filterIsInstance<AppleAdvertisement.HeySiri>().firstOrNull()?.device

    val nearbyActivity: NearbyActivity?
        get() = apple.filterIsInstance<AppleAdvertisement.NearbyInfoMessage>().firstOrNull()?.activity

    val nearbyAction: NearbyAction?
        get() = apple.filterIsInstance<AppleAdvertisement.NearbyActionMessage>().firstOrNull()?.action

    val displayName: String
        get() {
            cleaned(localName)?.let { return it }
            cleaned(peripheralName)?.let { return it }
            proximity?.let { return it.modelName ?: "Apple audio" }
            heySiriDevice?.let { return it }
            apple.mapNotNull { message ->
                val rank = message.nameRank ?: return@mapNotNull null
                val name = message.distinctiveName ?: return@mapNotNull null
                rank to name
            }.minByOrNull { it.first }?.second?.let { return it }
            companyName?.let { return it }
            return if (transport == Transport.CLASSIC) "Classic device" else "Unknown device"
        }

    val subtitle: String
        get() {
            val parts = mutableListOf<String>()
            if (transport == Transport.CLASSIC) parts.add("Classic")
            val company = companyName
            if (company != null && company != displayName) parts.add(company)
            proximity?.batterySummary?.let { parts.add(it) }
                ?: nearbyActivity?.label?.let { parts.add(it) }
                ?: nearbyAction?.label?.let { parts.add(it) }
                ?: services.firstNotNullOfOrNull { it.name }?.takeIf { it != displayName }?.let { parts.add(it) }
            deviceClass?.let { if (it != displayName) parts.add(it) }
            if (parts.isEmpty()) parts.add(address)
            return parts.joinToString(" · ")
        }

    val hasOwnName: Boolean
        get() = cleaned(localName) != null || cleaned(peripheralName) != null ||
            proximity?.modelName != null || heySiriDevice != null

    val strongestRssi: Int get() = samples.maxOfOrNull { it.rssi } ?: rssi
    val weakestRssi: Int get() = samples.minOfOrNull { it.rssi } ?: rssi
    val averageRssi: Int get() = if (samples.isEmpty()) rssi else samples.sumOf { it.rssi } / samples.size

    /**
     * Folds another advertisement in. Identity fields are kept across packets,
     * because one advertisement often carries only the name and the next only
     * the services. Signal samples are limited to a few a second so a chatty
     * peripheral does not redraw the list on every packet.
     */
    fun absorb(advertisement: BleAdvertisement, peripheralName: String?, rssi: Int, at: Long) {
        packetCount += 1
        cleaned(advertisement.localName)?.let { localName = it }
        cleaned(peripheralName)?.let { this.peripheralName = it }
        if (advertisement.companyId != null) {
            companyId = advertisement.companyId
            companyName = advertisement.companyName
        }
        if (advertisement.manufacturerPayload.isNotEmpty()) manufacturerPayload = advertisement.manufacturerPayload
        if (advertisement.apple.isNotEmpty()) {
            // Messages rotate across packets. Keep each kind and refresh it when
            // it shows up again, so the name does not flicker between packets.
            val merged = apple.toMutableList()
            for (message in advertisement.apple) {
                val index = merged.indexOfFirst { it::class == message::class }
                if (index >= 0) merged[index] = message else merged.add(message)
            }
            apple = merged
        }
        val knownServices = services.toMutableList()
        for (service in advertisement.services) {
            if (knownServices.none { it.uuid == service.uuid }) knownServices.add(service)
        }
        services = knownServices
        val knownSolicited = solicitedServices.toMutableList()
        for (service in advertisement.solicitedServices) {
            if (knownSolicited.none { it.uuid == service.uuid }) knownSolicited.add(service)
        }
        solicitedServices = knownSolicited
        val knownData = serviceData.toMutableList()
        for (item in advertisement.serviceData) {
            val index = knownData.indexOfFirst { it.uuid == item.uuid }
            if (index >= 0) knownData[index] = item else knownData.add(item)
        }
        serviceData = knownData
        if (advertisement.txPower != null) txPower = advertisement.txPower
        if (advertisement.isConnectable != null) isConnectable = advertisement.isConnectable

        if (at - lastSeen > PRESENCE_GAP_MS) presentSince = at
        lastSeen = at
        val due = samples.lastOrNull()?.let { at - it.date >= 250 } ?: true
        if (!due || rssi == 127) return
        this.rssi = rssi
        smoothedRssi = smoothedRssi * 0.5 + rssi * 0.5
        val smoothed = kotlin.math.round(smoothedRssi).toInt()
        if (kotlin.math.abs(smoothed - orderRssi) >= 8) orderRssi = smoothed
        sampleCounter += 1
        samples.add(Sample(sampleCounter, at, rssi))
        if (samples.size > 60) samples.subList(0, samples.size - 60).clear()
    }

    /** A saved device coming back from history. */
    fun restore(
        advertisement: BleAdvertisement,
        peripheralName: String?,
        samples: List<Sample>,
        packets: Int,
        lastSeen: Long,
        deviceClass: String?,
    ) {
        absorb(advertisement, peripheralName, 127, firstSeen)
        this.samples.clear()
        this.samples.addAll(samples)
        sampleCounter = samples.size
        packetCount = packets
        this.lastSeen = lastSeen
        presentSince = lastSeen
        this.deviceClass = deviceClass
        orderRssi = rssi
        smoothedRssi = rssi.toDouble()
    }

    private fun cleaned(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        return trimmed.ifEmpty { null }
    }

    companion object {
        const val NEARBY_WINDOW_MS = 20_000L
        const val PRESENCE_GAP_MS = 90_000L
        /** Quiet for this long while the scan runs and the row leaves the list. */
        const val REMOVAL_WINDOW_MS = 120_000L
    }
}
