package cattivole.spectra.assistant

import cattivole.spectra.SpectraModel
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.LinkInspector
import cattivole.spectra.core.MacAddress
import java.util.Locale

/** What a card's "More detail" or "Deep analysis" asks the web: a text query and, for a device, a picture query. */
data class WebLookup(val query: String, val imageQuery: String?)

/**
 * The web queries for a summary card. Only what names a product goes out — its maker and model, a link's domain —
 * never an address, a MAC, a network name or a name a person gave their device.
 */
object CardWeb {
    /** The subjects a web search can add something to: what a device is and what is known about it, or a site. */
    fun lookup(model: SpectraModel, subject: CardSubject, action: CardAction, locale: Locale = Locale.getDefault()): WebLookup? {
        if (action != CardAction.DETAILS && action != CardAction.DEEP) return null
        val russian = locale.language == "ru"
        return when (subject) {
            is CardSubject.Host -> model.lan.devices.firstOrNull { it.ipAddress == subject.ip }?.let { device(host(it), action, russian) }
            is CardSubject.Bluetooth -> model.ble.devices[subject.address]?.let { ble ->
                device(product(ble.companyName, personal(ble.localName ?: ble.peripheralName)), action, russian)
            }
            is CardSubject.Wifi -> MacAddress.parse(subject.bssid)?.let(model.store::network)?.let { network ->
                // The access point's own WPS name («Keenetic Giga») or its maker; never the network's name.
                val info = network.vendorInfo
                device(product(info?.manufacturer?.ifBlank { null } ?: network.latest.bssid.vendor(), info?.deviceName?.ifBlank { null }), action, russian, router = true)
            }
            is CardSubject.Link -> LinkInspector.inspect(subject.link)?.let { link ->
                val host = (link.asciiHost ?: link.host).removePrefix("www.").ifBlank { return null }
                WebLookup(if (russian) "$host отзывы мошенничество" else "$host scam reviews", null)
            }
            else -> null
        }
    }

    private const val MIIO_UNKNOWN = "Mi Home miIO"
    private val VERSION_WORD = Regex("""\p{L}+[-_]?v?\d+(?:\.\d+)+""", RegexOption.IGNORE_CASE)

    /** A maker and a product name, the parts that identify a device; null when there is neither. */
    internal data class Product(val maker: String?, val name: String?)

    internal fun product(maker: String?, name: String?): Product? {
        val cleanMaker = maker?.trim()?.takeIf { it.isNotEmpty() }
        // «UE55U8092FUXXH AllShare1.0»: a protocol and its version are not part of the model.
        val cleanName = name?.split(Regex("""\s+"""))?.filterNot { VERSION_WORD.matches(it) }?.joinToString(" ")
            ?.trim()?.takeIf { it.isNotEmpty() && !it.equals(cleanMaker, ignoreCase = true) }
        if (cleanMaker == null && cleanName == null) return null
        return Product(cleanMaker, cleanName)
    }

    /** A LAN host by what identified it: UPnP model, the mDNS model id, the recognized model, the maker. */
    private fun host(device: LanDevice): Product? {
        if (device.isLocalDevice) return null
        val identity = device.identity
        val maker = identity.manufacturer ?: device.manufacturer ?: device.profile.upnp?.manufacturer
        val miio = device.services.firstOrNull { "miio" in it.type.lowercase() }
        val name = when {
            miio != null -> miio.model ?: MIIO_UNKNOWN
            else -> device.profile.upnp?.model ?: device.services.firstNotNullOfOrNull { it.model?.takeIf { m -> !m.contains(',') || m.length < 30 } }
                ?: identity.model
        }
        return product(maker, name?.takeIf { !LanDevice.isOpaqueIdentifier(it) })
    }

    /** A Bluetooth name that a person gave the device («Alex's AirPods», «iPhone Анны») is not sent; a product name is. */
    internal fun personal(name: String?): String? {
        val text = name?.trim()?.takeIf { it.length >= 3 } ?: return null
        if (Regex("""['’]s\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        if (LanDevice.isOpaqueIdentifier(text)) return null
        // «iPhone Анны», «MacBook (Alex)»: a device kind with someone's name after it.
        if (Regex("""^(?:iphone|ipad|macbook|galaxy|pixel)\b.*(?:\(|—|-|\s)\p{Lu}\p{Ll}+$""", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
            !Regex("""\b(?:pro|max|plus|ultra|mini|air|fold|flip|watch|buds|tab)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text.substringAfterLast(' '))
        ) {
            return null
        }
        return text
    }

    private fun device(product: Product?, action: CardAction, russian: Boolean, router: Boolean = false): WebLookup? {
        product ?: return null
        val name = listOfNotNull(product.maker, product.name).joinToString(" ")
        val kind = if (router && product.name == null) (if (russian) " роутер" else " router") else ""
        val topic = when (action) {
            CardAction.DEEP -> if (russian) " уязвимости прошивка" else " vulnerabilities firmware"
            else -> if (russian) " характеристики безопасность" else " specs security"
        }
        // A maker alone shows its logo, not the device: a picture only when the model is known.
        val picture = if (product.name != null && product.name != MIIO_UNKNOWN) name else null
        return WebLookup("$name$kind$topic", picture)
    }
}
