package cattivole.spectra.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Grass
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocalLaundryService
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import cattivole.spectra.core.WebLink
import cattivole.spectra.core.WebLinkIcon

private val vectors: Map<String, ImageVector> by lazy {
    mapOf(
        "router" to Icons.Rounded.Router,
        "wifi" to Icons.Rounded.Wifi,
        "network" to Icons.Rounded.Lan,
        "storage" to Icons.Rounded.Storage,
        "server" to Icons.Rounded.Dns,
        "camera" to Icons.Rounded.Videocam,
        "doorbell" to Icons.Rounded.Notifications,
        "lock" to Icons.Rounded.Lock,
        "smartHome" to Icons.Rounded.Home,
        "light" to Icons.Rounded.Lightbulb,
        "plug" to Icons.Rounded.Power,
        "thermostat" to Icons.Rounded.Thermostat,
        "fan" to Icons.Rounded.Air,
        "water" to Icons.Rounded.WaterDrop,
        "energy" to Icons.Rounded.Bolt,
        "charger" to Icons.Rounded.EvStation,
        "sensor" to Icons.Rounded.Sensors,
        "vacuum" to Icons.Rounded.CleaningServices,
        "washer" to Icons.Rounded.LocalLaundryService,
        "kitchen" to Icons.Rounded.Kitchen,
        "tv" to Icons.Rounded.Tv,
        "media" to Icons.Rounded.SmartDisplay,
        "speaker" to Icons.Rounded.Speaker,
        "printer" to Icons.Rounded.Print,
        "printer3d" to Icons.Rounded.ViewInAr,
        "computer" to Icons.Rounded.DesktopWindows,
        "laptop" to Icons.Rounded.Laptop,
        "board" to Icons.Rounded.Memory,
        "phone" to Icons.Rounded.Phone,
        "game" to Icons.Rounded.SportsEsports,
        "download" to Icons.Rounded.Download,
        "shield" to Icons.Rounded.Shield,
        "stats" to Icons.Rounded.QueryStats,
        "terminal" to Icons.Rounded.Terminal,
        "car" to Icons.Rounded.DirectionsCar,
        "garden" to Icons.Rounded.Grass,
        "web" to Icons.Rounded.Language,
        "star" to Icons.Rounded.Star,
        "heart" to Icons.Rounded.Favorite,
    )
}

fun WebLinkIcon.vector(): ImageVector = vectors[id] ?: Icons.Rounded.Language

/** The icon the user gave the link, or the device's own. */
fun WebLink.vector(): ImageVector = vectors[customIcon] ?: deviceIcon(icon)

/** The icon of the list that looks like the device's own, if one does. */
internal fun ownIconId(link: WebLink): String? {
    val own = deviceIcon(link.icon)
    return vectors.entries.firstOrNull { it.value.name == own.name }?.key
}
