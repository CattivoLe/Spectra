package cattivole.spectra.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** A device's web page the user kept, to open without scanning the network. Mirrors the iOS `WebLink`. */
data class WebLink(
    val id: String = UUID.randomUUID().toString(),
    /** What the user called it. Without it the device's own name is shown. */
    val name: String? = null,
    val deviceName: String,
    /** The whole address, as the browser gets it. The user may add a path. */
    val address: String,
    /** Where the device was last seen, and the port the link was made from. */
    val ip: String,
    val port: Int,
    /** Hardware address and names the device is recognised by; never its IP. */
    val prints: List<String>,
    /** The network it is on, by `LanScanner.networkKey`, and its name. */
    val network: String?,
    val networkLabel: String?,
    /** The device's own icon, by name, and the one the user picked instead, by [WebLinkIcon.id]. */
    val icon: String? = null,
    val customIcon: String? = null,
    /** What the device and its page say about themselves, to suggest icons by. */
    val hints: List<String> = emptyList(),
) {
    val title: String get() = name ?: deviceName

    /** The address without the scheme, for a list row. */
    val shownAddress: String get() = address.substringAfter("://")

    val secure: Boolean get() = address.startsWith("https://", ignoreCase = true)

    companion object {
        /** What the user typed as a web address, or null when a browser would not open it. */
        fun normalized(typed: String): String? {
            val text = typed.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            val full = if ("://" in text) text else "http://$text"
            val uri = runCatching { URI(full) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https") || host(uri).isNullOrEmpty()) return null
            return full
        }

        /** The address with the device's IP replaced. One the user pointed at a name or another host stays as it is. */
        fun moved(address: String, from: String, to: String): String {
            val uri = runCatching { URI(address) }.getOrNull() ?: return address
            if (host(uri) != from) return address
            val at = address.indexOf(from, startIndex = address.indexOf("://") + 3)
            return if (at < 0) address else address.replaceRange(at, at + from.length, to)
        }

        /** `URI.host` is null for names with an underscore, which home devices do have. */
        private fun host(uri: URI): String? =
            uri.host ?: uri.rawAuthority?.let { looseAuthority.matchEntire(it) }?.groupValues?.get(1)

        private val looseAuthority = Regex("(?:[^@]*@)?([A-Za-z0-9._-]+)(?::\\d{1,5})?")
    }
}

/** The network a scan walked, as the links remember it. */
data class WebLinkNetwork(
    /** The key to store, and every key the same network may have been stored under before. */
    val key: String?,
    val keys: List<String>,
    val label: String?,
) {
    fun includes(link: WebLink): Boolean = link.network == null || link.network == key || link.network in keys
}

/**
 * The kept web pages of devices. A router hands out addresses anew, so a link
 * follows its device by the fingerprints [KnownHosts] uses. Persisted across
 * launches, on this phone only. Mirrors the iOS `WebLinks`.
 */
class WebLinks(private val store: TextStore = MemoryTextStore(), private val icon: (LanDevice) -> String? = { null }) {
    var links: List<WebLink> = load()
        private set

    /** The kept devices by name, each once. */
    val titles: List<String> get() = links.map { it.title }.distinct()

    /** The link made from this port of this host, if there is one. */
    fun link(device: LanDevice, port: Int, network: WebLinkNetwork): WebLink? = links.firstOrNull {
        it.port == port && it.ip == device.ipAddress && network.includes(it) && !conflicts(it, device)
    }

    /** Keeps the web page on this port, or drops it when it is already kept. True when it is kept now. */
    fun toggle(device: LanDevice, port: Int, network: WebLinkNetwork): Boolean {
        link(device, port, network)?.let {
            remove(it.id)
            return false
        }
        val address = device.webAddress(port) ?: return false
        links = links + WebLink(
            deviceName = device.displayName,
            address = address,
            ip = device.ipAddress,
            port = port,
            prints = prints(device),
            network = network.key,
            networkLabel = network.label,
            icon = icon(device),
            hints = hints(device, port),
        )
        persist()
        return true
    }

    /** A blank name and no icon go back to the device's own; a bad address changes nothing. */
    fun edit(id: String, name: String, address: String, icon: String?) {
        val valid = WebLink.normalized(address) ?: return
        links = links.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { null }, address = valid, customIcon = icon) else it }
        persist()
    }

    fun remove(id: String) {
        links = links.filterNot { it.id == id }
        persist()
    }

    /** Finds the kept devices among those a scan of their network saw, and takes their new addresses and names. */
    fun follow(devices: List<LanDevice>, network: WebLinkNetwork) {
        if (links.isEmpty() || devices.isEmpty()) return
        val before = links
        links = before.map { link ->
            val home = if (network.includes(link)) home(link, devices, before, network) else null
            if (home == null) {
                link
            } else {
                val newIp = home.ipAddress
                link.copy(
                    deviceName = home.knownName ?: if (link.deviceName == link.ip) newIp else link.deviceName,
                    address = if (newIp == link.ip) link.address else WebLink.moved(link.address, link.ip, newIp),
                    ip = newIp,
                    prints = (link.prints + prints(home)).distinct(),
                    network = network.key ?: link.network,
                    networkLabel = network.label ?: link.networkLabel,
                    icon = icon(home) ?: link.icon,
                    hints = hints(home, link.port).ifEmpty { link.hints },
                )
            }
        }
        if (links != before) persist()
    }

    /**
     * The device a link is about. Another host may have taken its address, and
     * two hosts may share a name: then it stays where it is.
     */
    private fun home(link: WebLink, devices: List<LanDevice>, links: List<WebLink>, network: WebLinkNetwork): LanDevice? {
        val here = devices.firstOrNull { it.ipAddress == link.ip }
        if (link.prints.isEmpty()) return here
        val same = devices.filter { recognizes(link, it) }
        if (here != null && here in same) return here
        val found = same.singleOrNull() ?: return null
        // A device with a link of its own is not this one under a new address.
        val taken = links.any { it.ip == found.ipAddress && network.includes(it) && (it.prints.isEmpty() || recognizes(it, found)) }
        return found.takeUnless { taken }
    }

    private fun prints(device: LanDevice): List<String> = device.fingerprints.filterNot { it.startsWith("ip:") }

    /** The page's own title first, then what is known of the device. */
    private fun hints(device: LanDevice, port: Int): List<String> {
        val banner = device.profile.banners.firstOrNull { it.port == port }
        val upnp = device.profile.upnp
        return listOfNotNull(
            banner?.title, banner?.realm, upnp?.friendlyName, upnp?.model, device.identity.model,
            device.identity.manufacturer ?: device.manufacturer,
        ).filter { it.isNotEmpty() }
    }

    private fun recognizes(link: WebLink, device: LanDevice): Boolean =
        !conflicts(link, device) && prints(device).any { it in link.prints }

    /** Both hardware addresses are known and differ: not the same device. */
    private fun conflicts(link: WebLink, device: LanDevice): Boolean {
        val mac = device.macAddress ?: return false
        val kept = link.prints.filter { it.startsWith("mac:") }
        return kept.isNotEmpty() && "mac:${mac.text}" !in kept
    }

    private fun load(): List<WebLink> = runCatching {
        val array = JSONArray(store.read(KEY) ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val address = item.optString("address").ifEmpty { return@mapNotNull null }
            WebLink(
                id = item.optString("id").ifEmpty { UUID.randomUUID().toString() },
                name = item.optString("name").ifEmpty { null },
                deviceName = item.optString("deviceName").ifEmpty { address },
                address = address,
                ip = item.optString("ip"),
                port = item.optInt("port"),
                prints = strings(item.optJSONArray("prints")),
                network = item.optString("network").ifEmpty { null },
                networkLabel = item.optString("networkLabel").ifEmpty { null },
                icon = item.optString("icon").ifEmpty { null },
                customIcon = item.optString("customIcon").ifEmpty { null },
                hints = strings(item.optJSONArray("hints")),
            )
        }
    }.getOrDefault(emptyList())

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }

    private fun persist() {
        val array = JSONArray()
        for (link in links) {
            val item = JSONObject()
                .put("id", link.id)
                .put("deviceName", link.deviceName)
                .put("address", link.address)
                .put("ip", link.ip)
                .put("port", link.port)
                .put("prints", JSONArray(link.prints))
            link.name?.let { item.put("name", it) }
            link.network?.let { item.put("network", it) }
            link.networkLabel?.let { item.put("networkLabel", it) }
            link.icon?.let { item.put("icon", it) }
            link.customIcon?.let { item.put("customIcon", it) }
            if (link.hints.isNotEmpty()) item.put("hints", JSONArray(link.hints))
            array.put(item)
        }
        store.write(KEY, array.toString())
    }

    private companion object {
        const val KEY = "webLinks"
    }
}
