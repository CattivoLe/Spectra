package cattivole.spectra.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import cattivole.spectra.core.AppleAdvertisement
import cattivole.spectra.core.AuthMode
import cattivole.spectra.core.BleAdvertisement
import cattivole.spectra.core.BleDevice
import cattivole.spectra.core.Catalog
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.NotableDevice
import java.text.DateFormat
import java.util.Date

// Time

fun clockTime(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

fun dateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

/** "just now", "5 min ago", "2 h 10 min ago", or the date for anything older than a day. */
fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = ((now - millis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 10 -> tr("just now")
        seconds < 60 -> tr("%d s ago", seconds)
        seconds < 3600 -> tr("%d min ago", seconds / 60)
        seconds < 86_400 -> tr("%d h %d min ago", seconds / 3600, (seconds % 3600) / 60)
        else -> dateTime(millis)
    }
}

/** m:ss under an hour, then hours and minutes. */
fun stopwatch(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return if (seconds < 3600) "%d:%02d".format(seconds / 60, seconds % 60) else "%dh %02dm".format(seconds / 3600, (seconds % 3600) / 60)
}

fun byteSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> tr("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> tr("%.0f KB", bytes / 1_000.0)
    else -> tr("%d B", bytes)
}

/** Bits per second over a capture window, in the reader's units. */
/** Null when nothing was listened to. */
fun bitrate(bytes: Long, durationMs: Int): String? = if (durationMs <= 0) null else bitrate(bytes * 8.0 / (durationMs / 1000.0))

fun bitrate(bits: Double): String = when {
    bits >= 1_000_000 -> tr("%.1f Mbit/s", bits / 1_000_000)
    bits >= 1_000 -> tr("%.0f kbit/s", bits / 1_000)
    else -> tr("%.0f bit/s", bits)
}

// Wi-Fi

/** The protection in the reader's language; the standards' own names stay as they are. */
fun AuthMode.shownName(): String = when (name) {
    "Open" -> tr("Open##security")
    "Other" -> tr("Other##security")
    else -> name
}

fun AuthMode.isOpen(): Boolean = strength == AuthMode.Strength.NONE

fun macKind(mac: MacAddress): String =
    if (mac.isLocallyAdministered) tr("Randomised") else tr("Maker-assigned (OUI %s)", mac.oui)

/** An icon guessed from the name a station or probe goes by. */
fun stationIcon(name: String, mac: MacAddress, associated: Boolean): ImageVector {
    val folded = name.lowercase()
    return when {
        "apple" in folded || "iphone" in folded -> Icons.Rounded.PhoneIphone
        "ipad" in folded -> Icons.Rounded.TabletAndroid
        "mac" in folded || "book" in folded -> Icons.Rounded.Laptop
        "samsung" in folded || "xiaomi" in folded || "huawei" in folded || "oneplus" in folded -> Icons.Rounded.PhoneAndroid
        "amazon" in folded || "echo" in folded -> Icons.Rounded.Speaker
        "google" in folded || "chromecast" in folded -> Icons.Rounded.Tv
        "raspberry" in folded || "espressif" in folded -> Icons.Rounded.Memory
        mac.isLocallyAdministered -> Icons.Rounded.QuestionMark
        associated -> Icons.Rounded.Wifi
        else -> Icons.Rounded.SettingsInputAntenna
    }
}

// LAN

fun LanDevice.icon(): ImageVector {
    if (isGateway) return Icons.Rounded.Router
    if (isLocalDevice) return Icons.Rounded.PhoneAndroid
    when (identity.operatingSystem) {
        "iOS" -> return Icons.Rounded.PhoneIphone
        "iPadOS" -> return Icons.Rounded.TabletAndroid
        "macOS" -> return Icons.Rounded.Laptop
        "tvOS", "Android TV", "Google TV" -> return MediaBox
        "Tizen", "webOS" -> return Icons.Rounded.Tv
        "watchOS" -> return Icons.Rounded.Watch
        "Android" -> return if (looksLikeMediaPlayer()) MediaBox else Icons.Rounded.PhoneAndroid
        "Windows" -> return Icons.Rounded.DesktopWindows
    }
    when (notable) {
        NotableDevice.CAMERA -> return Icons.Rounded.Videocam
        NotableDevice.IOT -> return Icons.Rounded.Sensors
        null -> Unit
    }
    val name = displayName.lowercase()
    when {
        "macbook" in name || "imac" in name || "mac mini" in name || "book" in name -> return Icons.Rounded.Laptop
        "iphone" in name -> return Icons.Rounded.PhoneIphone
        "ipad" in name -> return Icons.Rounded.TabletAndroid
        "homepod" in name -> return Icons.Rounded.Speaker
        isMediaPlayerName(name) -> return MediaBox
        "tv" in name -> return Icons.Rounded.Tv
        "echo" in name || "alexa" in name || "sonos" in name || "eversolo" in name -> return Icons.Rounded.Speaker
        "print" in name || "canon" in name || "epson" in name || "brother" in name -> return Icons.Rounded.Print
        "xbox" in name || "playstation" in name || "ps5" in name || "nintendo" in name -> return Icons.Rounded.SportsEsports
        "raspberry" in name || "esp" in name -> return Icons.Rounded.Memory
    }
    val maker = manufacturer?.lowercase() ?: return Icons.Rounded.Apps
    return when {
        maker == "apple" || "apple" in maker -> Icons.Rounded.PhoneIphone
        listOf("samsung", "google", "xiaomi", "huawei", "oneplus", "oppo", "vivo", "motorola").any { it in maker } -> Icons.Rounded.PhoneAndroid
        listOf("tp-link", "netgear", "asus", "d-link", "ubiquiti", "mikrotik", "keenetic", "zyxel").any { it in maker } -> Icons.Rounded.Router
        listOf("canon", "epson", "brother", "hewlett").any { it in maker } -> Icons.Rounded.Print
        listOf("intel", "dell", "lenovo", "micro-star", "gigabyte").any { it in maker } -> Icons.Rounded.DesktopWindows
        "espressif" in maker || "raspberry" in maker -> Icons.Rounded.Memory
        "sonos" in maker || "bose" in maker -> Icons.Rounded.Speaker
        "sony" in maker || "lg electronics" in maker -> Icons.Rounded.Tv
        "synology" in maker || "qnap" in maker -> Icons.Rounded.Storage
        else -> Icons.Rounded.Apps
    }
}

/** A low set-top box, distinct from the television glyph. */
private val MediaBox: ImageVector by lazy {
    ImageVector.Builder(
        name = "MediaBox",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.Black),
        pathFillType = PathFillType.EvenOdd,
    ) {
        moveTo(4f, 8.5f)
        lineTo(20f, 8.5f)
        arcTo(2f, 2f, 0f, false, true, 22f, 10.5f)
        lineTo(22f, 15.5f)
        arcTo(2f, 2f, 0f, false, true, 20f, 17.5f)
        lineTo(4f, 17.5f)
        arcTo(2f, 2f, 0f, false, true, 2f, 15.5f)
        lineTo(2f, 10.5f)
        arcTo(2f, 2f, 0f, false, true, 4f, 8.5f)
        close()
        moveTo(17.8f, 13f)
        arcTo(1.15f, 1.15f, 0f, false, true, 15.5f, 13f)
        arcTo(1.15f, 1.15f, 0f, false, true, 17.8f, 13f)
        close()
    }.build()
}

/** Android TV boxes are often labelled just Android, which would draw a phone. */
private fun LanDevice.looksLikeMediaPlayer(): Boolean {
    if (openPorts.any { it.number == 6466 }) return true
    if (isMediaPlayerName(displayName)) return true
    val title = displayName.lowercase()
    if (" tv" in title || title.endsWith("tv")) return true
    val servicesBlob = services.joinToString(" ") { "${it.type} ${it.name}" }.lowercase()
    return "androidtv" in servicesBlob || "googlecast" in servicesBlob || "bravia" in servicesBlob
}

private fun isMediaPlayerName(name: String): Boolean {
    val folded = name.lowercase()
    return "android tv" in folded || "google tv" in folded || "androidtv" in folded ||
        "bravia" in folded || "chromecast" in folded || "shield" in folded ||
        "roku" in folded || "apple tv" in folded || "appletv" in folded ||
        "fire tv" in folded || "firestick" in folded || "fire stick" in folded ||
        "mi box" in folded || "mibox" in folded || "media player" in folded
}

// Bluetooth

fun BleDevice.icon(): ImageVector {
    if (isSpectraSensor) return Icons.Rounded.SettingsInputAntenna
    if (proximity != null) return Icons.Rounded.Headphones
    when (heySiriDevice) {
        "iPhone" -> return Icons.Rounded.PhoneIphone
        "iPad" -> return Icons.Rounded.TabletAndroid
        "Watch" -> return Icons.Rounded.Watch
        "MacBook" -> return Icons.Rounded.Laptop
        "HomePod" -> return Icons.Rounded.Speaker
    }
    if (apple.any { it is AppleAdvertisement.HomeKit }) return Icons.Rounded.Home
    if (apple.any { it is AppleAdvertisement.AirPlayTarget || it is AppleAdvertisement.AirPlaySource }) return Icons.Rounded.Tv
    if (advertisesFindMy) return Icons.Rounded.LocationOn
    if (apple.any { it is AppleAdvertisement.AirPrint }) return Icons.Rounded.Print
    for (service in services) {
        when (Catalog.canonicalUuid(service.uuid)) {
            "180D" -> return Icons.Rounded.Favorite
            "1812" -> return Icons.Rounded.Keyboard
            "1814" -> return Icons.AutoMirrored.Rounded.DirectionsRun
            "1816" -> return Icons.AutoMirrored.Rounded.DirectionsBike
            "180F" -> return Icons.Rounded.BatteryFull
            "FE2C", "110B", "111E" -> return Icons.Rounded.Headphones
        }
    }
    when (deviceClass) {
        "Audio" -> return Icons.Rounded.Headphones
        "Computer" -> return Icons.Rounded.Laptop
        "Phone" -> return Icons.Rounded.PhoneAndroid
        "Peripheral" -> return Icons.Rounded.Keyboard
        "Imaging" -> return Icons.Rounded.Print
        "Wearable" -> return Icons.Rounded.Watch
    }
    if (companyId == BleAdvertisement.APPLE_COMPANY) return Icons.Rounded.PhoneIphone
    return Icons.Rounded.Bluetooth
}

/** Like [BleDevice.displayName], in the reader's language. */
fun BleDevice.shownName(): String {
    // A name the device broadcast is its own; one the app made up from its messages is translated.
    if (!localName.isNullOrBlank() || !peripheralName.isNullOrBlank()) return displayName
    return if (proximity?.modelName != null || heySiriDevice != null || displayName == companyName) displayName else tr(displayName)
}

/** Like [BleDevice.subtitle], in the reader's language. */
fun BleDevice.shownSubtitle(): String {
    val parts = mutableListOf<String>()
    if (transport == BleDevice.Transport.CLASSIC) parts.add(tr("Classic"))
    val company = companyName
    val shown = displayName
    if (company != null && company != shown) parts.add(company)
    val battery = proximity?.batterySummary?.let(::batteryText)
    when {
        battery != null -> parts.add(battery)
        nearbyActivity != null -> parts.add(tr(nearbyActivity!!.label))
        nearbyAction != null -> parts.add(tr(nearbyAction!!.label))
        else -> services.firstNotNullOfOrNull { it.name }?.takeIf { it != shown }?.let { parts.add(it) }
    }
    deviceClass?.let { if (it != shown) parts.add(tr(it)) }
    if (parts.isEmpty()) parts.add(address)
    return parts.joinToString(" · ")
}

/** "Left 80% (charging)" pieces from the core, in the reader's language. */
fun batteryText(summary: String): String = summary.split(" · ").joinToString(" · ") { part ->
    val charging = part.endsWith(" (charging)")
    val bare = part.removeSuffix(" (charging)")
    val space = bare.indexOf(' ')
    if (space < 0) return@joinToString part
    val label = tr(bare.substring(0, space))
    val value = bare.substring(space + 1)
    if (charging) tr("%s %s, charging", label, value) else "$label $value"
}

fun appleLabel(message: AppleAdvertisement): String = when (message) {
    is AppleAdvertisement.Proximity -> message.pairing.modelLabel
    is AppleAdvertisement.HomeKit -> message.category?.let(::tr) ?: "HomeKit"
    is AppleAdvertisement.NearbyActionMessage -> message.action?.label?.let(::tr) ?: tr("Nearby")
    is AppleAdvertisement.NearbyInfoMessage -> message.activity?.label?.let(::tr) ?: tr("Nearby")
    else -> tr(message.label)
}

fun appleDetail(message: AppleAdvertisement): String? = when (message) {
    is AppleAdvertisement.Proximity -> message.pairing.batterySummary?.let(::batteryText)
    is AppleAdvertisement.FindMy -> message.status?.let { status ->
        listOfNotNull(status.ownerLabel, status.batteryLabel).joinToString(" · ") { tr(it) }
    }
    else -> message.detail
}
