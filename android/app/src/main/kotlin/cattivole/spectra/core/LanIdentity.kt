package cattivole.spectra.core

import android.os.Build

import java.net.URI
import java.nio.charset.Charset

data class UPnPIdentity(
    val friendlyName: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val deviceType: String? = null,
    val server: String? = null,
) {
    fun filled(previous: UPnPIdentity?): UPnPIdentity {
        previous ?: return this
        return UPnPIdentity(
            friendlyName = friendlyName.keptName(previous.friendlyName),
            manufacturer = manufacturer.kept(previous.manufacturer),
            model = model.kept(previous.model),
            deviceType = deviceType.kept(previous.deviceType),
            server = server.kept(previous.server),
        )
    }
}

data class HttpBanner(
    val port: Int,
    val secure: Boolean,
    val status: Int?,
    val server: String?,
    val poweredBy: String?,
    val title: String?,
    val realm: String?,
) {
    val summary: String
        get() = listOfNotNull(
            status?.let { "HTTP $it" },
            server,
            poweredBy,
            title,
            realm,
        ).joinToString(" · ")
}

data class RtspService(val port: Int, val server: String?, val methods: String?) {
    val summary: String?
        get() = listOfNotNull(server, methods).joinToString(" · ").ifEmpty { null }
}

data class WindowsHost(
    val computerName: String? = null,
    val domain: String? = null,
    val shares: List<String> = emptyList(),
    val guestSession: Boolean = false,
) {
    fun filled(previous: WindowsHost?): WindowsHost {
        previous ?: return this
        return WindowsHost(
            computerName = computerName.keptName(previous.computerName),
            domain = domain.kept(previous.domain),
            shares = shares.ifEmpty { previous.shares },
            guestSession = guestSession || previous.guestSession,
        )
    }
}

data class LanProfile(
    val upnp: UPnPIdentity? = null,
    val banners: List<HttpBanner> = emptyList(),
    val rtsp: List<RtspService> = emptyList(),
    val windows: WindowsHost? = null,
    /** The router's UPnP port-forwarding table. Gateway only; null until asked. */
    val portForwarding: PortForwarding? = null,
    /** Forwardings on the router that point at this host. */
    val inbound: List<PortMapping> = emptyList(),
) {
    val isEmpty: Boolean
        get() = upnp == null && banners.isEmpty() && rtsp.isEmpty() && windows == null && portForwarding == null && inbound.isEmpty()

    fun filled(previous: LanProfile): LanProfile {
        if (isEmpty) return previous
        return LanProfile(
            upnp = upnp?.filled(previous.upnp) ?: previous.upnp,
            banners = banners.ifEmpty { previous.banners },
            rtsp = rtsp.ifEmpty { previous.rtsp },
            windows = windows?.filled(previous.windows) ?: previous.windows,
            portForwarding = portForwarding ?: previous.portForwarding,
            inbound = inbound.ifEmpty { previous.inbound },
        )
    }
}

data class ServiceAdvertisement(val type: String, val name: String, val model: String? = null) {
    val kind: String
        get() {
            val folded = type.lowercase()
            return when {
                "apple-mobdev" in folded -> "iPhone sync"
                "companion-link" in folded -> "Continuity"
                "device-info" in folded -> "Device info"
                "airplay" in folded -> "AirPlay"
                "raop" in folded -> "AirPlay audio"
                "googlecast" in folded -> "Google Cast"
                "hap" in folded -> "HomeKit"
                "spotify" in folded -> "Spotify"
                "ipp" in folded || "printer" in folded -> "Printer"
                "smb" in folded -> "SMB"
                "ssh" in folded -> "SSH"
                "http" in folded -> "Web"
                else -> folded
            }
        }
}

data class HostIdentity(
    val manufacturer: String? = null,
    val operatingSystem: String? = null,
    val model: String? = null,
) {
    val label: String
        get() = listOfNotNull(manufacturer, operatingSystem, model).joinToString(" · ")

    companion object {
        fun recognize(
            names: List<String>,
            services: List<ServiceAdvertisement> = emptyList(),
            ports: List<Int> = emptyList(),
            vendor: String? = null,
            upnpName: String? = null,
            upnpModel: String? = null,
            upnpMaker: String? = null,
            upnpType: String? = null,
        ): HostIdentity {
            var found = HostIdentity()
            val texts = names + services.map { it.name } + services.mapNotNull { it.model } + listOfNotNull(upnpName, upnpModel)
            for (text in texts) appleIdentity(text)?.let { found = found.apply(it, overwrite = true) }
            for (text in texts) namedIdentity(text)?.let { found = found.apply(it, overwrite = false) }
            if (upnpMaker != null) found = found.apply(upnpHint(upnpMaker, upnpType, upnpName), overwrite = false)
            val types = services.map { it.type.lowercase() }
            if (types.any { "device-info" in it || "companion-link" in it }) {
                found = found.apply(HostIdentity(manufacturer = "Apple"), overwrite = false)
            }
            val handheld = texts.any {
                val folded = it.lowercase()
                "iphone" in folded || "ipad" in folded || "ipod" in folded
            }
            if (found.operatingSystem == null && !handheld &&
                types.any { "device-info" in it || "companion-link" in it } &&
                types.none { "apple-mobdev" in it }
            ) {
                found = found.apply(HostIdentity("Apple", "macOS", "Mac"), overwrite = false)
            }
            if (types.any { "apple-mobdev" in it }) {
                val ipad = texts.any { "ipad" in it.lowercase() }
                found = found.apply(
                    HostIdentity("Apple", if (ipad) "iPadOS" else "iOS", if (ipad) "iPad" else "iPhone"),
                    overwrite = false,
                )
            }
            if (62078 in ports) found = found.apply(HostIdentity("Apple", "iOS", "iPhone"), overwrite = false)
            if (548 in ports) found = found.apply(HostIdentity("Apple", "macOS"), overwrite = false)
            if (3389 in ports || 5357 in ports) found = found.apply(HostIdentity("Microsoft", "Windows"), overwrite = false)
            val androidTv = 6466 in ports ||
                types.any { "androidtv" in it } ||
                texts.any { text ->
                    val folded = text.lowercase()
                    "android tv" in folded || "google tv" in folded || "androidtv" in folded || "bravia" in folded
                }
            if (androidTv && (found.operatingSystem == null || found.operatingSystem == "Android")) {
                found = found.copy(operatingSystem = "Android TV")
            }
            if (found.manufacturer == null) shortVendor(vendor)?.let { found = found.copy(manufacturer = it) }
            return found
        }

        /** The phone running Spectra. Its own mDNS name often never comes back. */
        fun thisDevice(): HostIdentity {
            val model = Build.MODEL?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            val maker = Build.MANUFACTURER?.takeIf { it.isNotBlank() }
            val recognized = if (model != null) recognize(names = listOf(model)) else HostIdentity()
            return recognized.copy(
                manufacturer = recognized.manufacturer ?: shortVendor(maker) ?: maker?.replaceFirstChar { it.uppercase() },
                operatingSystem = recognized.operatingSystem ?: "Android",
                model = recognized.model ?: model,
            )
        }

        fun mergeBlanks(base: HostIdentity, extra: HostIdentity): HostIdentity = base.apply(extra, overwrite = false)

        private fun HostIdentity.apply(other: HostIdentity, overwrite: Boolean): HostIdentity = copy(
            manufacturer = other.manufacturer?.takeIf { manufacturer == null || overwrite } ?: manufacturer,
            operatingSystem = other.operatingSystem?.takeIf { operatingSystem == null || overwrite } ?: operatingSystem,
            model = other.model?.takeIf { model == null || overwrite } ?: model,
        )

        private fun appleIdentity(text: String): HostIdentity? {
            val match = appleId.find(text) ?: return null
            val id = match.groupValues[1]
            val key = id.lowercase()
            return when {
                key.startsWith("iphone") || key.startsWith("ipod") ->
                    HostIdentity("Apple", "iOS", appleModels[id] ?: "iPhone")
                key.startsWith("ipad") -> HostIdentity("Apple", "iPadOS", appleModels[id] ?: "iPad")
                key.startsWith("watch") -> HostIdentity("Apple", "watchOS", "Apple Watch")
                key.startsWith("appletv") -> HostIdentity("Apple", "tvOS", "Apple TV")
                key.startsWith("audioaccessory") -> HostIdentity("Apple", "audioOS", "HomePod")
                else -> HostIdentity("Apple", "macOS", appleModels[id] ?: macFamily(key))
            }
        }

        /** Intel-era ids name the family; Apple silicon Macs are all "MacNN,N". */
        private fun macFamily(key: String): String = when {
            key.startsWith("macbookpro") -> "MacBook Pro"
            key.startsWith("macbookair") -> "MacBook Air"
            key.startsWith("macbook") -> "MacBook"
            key.startsWith("macmini") -> "Mac mini"
            key.startsWith("macpro") -> "Mac Pro"
            key.startsWith("imac") -> "iMac"
            else -> "Mac"
        }

        private fun namedIdentity(text: String): HostIdentity? {
            val folded = text.lowercase().replace('-', ' ').replace('_', ' ')
            val compact = folded.replace(" ", "")
            val coded = text.lowercase()
            foldGeneration(folded, compact)?.let { generation ->
                return HostIdentity("Samsung", "Android", titled(text, "fold") ?: "Galaxy Z Fold$generation")
            }
            return when {
                "iphone" in folded || "ipod" in folded -> HostIdentity("Apple", "iOS", "iPhone")
                "ipad" in folded -> HostIdentity("Apple", "iPadOS", "iPad")
                "macbook" in folded || "imac" in folded || "mac mini" in folded || "mac studio" in folded ||
                    "mac pro" in folded || folded == "mac" || folded.endsWith(" mac") ->
                    HostIdentity(
                        "Apple",
                        "macOS",
                        when {
                            "macbook" in folded -> "MacBook"
                            "imac" in folded -> "iMac"
                            "mac mini" in folded -> "Mac mini"
                            "mac studio" in folded -> "Mac Studio"
                            "mac pro" in folded -> "Mac Pro"
                            else -> "Mac"
                        },
                    )
                "apple tv" in folded || "appletv" in folded -> HostIdentity("Apple", "tvOS", "Apple TV")
                "z fold" in folded || "galaxy fold" in folded || "samsung fold" in folded ->
                    HostIdentity("Samsung", "Android", titled(text, "fold") ?: "Galaxy Z Fold")
                flipCode.containsMatchIn(compact) || "z flip" in folded || "galaxy flip" in folded ->
                    HostIdentity("Samsung", "Android", titled(text, "flip") ?: "Galaxy Z Flip")
                "galaxy" in folded || samsungCode.containsMatchIn(coded) ->
                    HostIdentity("Samsung", "Android", titled(text, "galaxy") ?: titled(text, "sm-"))
                "pixel" in folded -> HostIdentity("Google", "Android", titled(text, "pixel") ?: "Pixel")
                folded == "android" || folded.startsWith("android ") -> HostIdentity(operatingSystem = "Android")
                "crystal uhd" in folded || "qled" in folded || "the frame" in folded || "neo qled" in folded ||
                    (!compact.startsWith("android") && tvCode.containsMatchIn(compact)) ->
                    HostIdentity("Samsung", "Tizen", shortLabel(text))
                "eversolo" in folded -> HostIdentity("Eversolo", null, shortLabel(text, "eversolo"))
                else -> null
            }
        }

        /** Fold2 is SM-F916, Fold4 is SM-F936: the digit after F9 is one less than the generation. */
        private fun foldGeneration(folded: String, compact: String): Int? {
            foldCode.find(compact)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it + 1 }
            foldWord.find(folded)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            return null
        }

        private fun upnpHint(maker: String, type: String?, name: String?): HostIdentity {
            val short = shortVendor(maker) ?: maker
            val kind = type?.lowercase().orEmpty()
            val television = short == "Samsung" && ("mediarenderer" in kind || "tv" in kind)
            return HostIdentity(
                manufacturer = short,
                operatingSystem = if (television) "Tizen" else null,
                model = if (television) name?.let { shortLabel(it) } else null,
            )
        }

        private fun shortLabel(text: String, brand: String? = null): String? {
            val shown = text.trim().removeSuffix(".local").replace('-', ' ').replace('_', ' ')
                .split(' ').filter { it.isNotBlank() }.joinToString(" ")
            if (shown.length !in 1..40) return null
            if (brand != null && shown.equals(brand, ignoreCase = true)) return null
            return shown
        }

        private fun titled(text: String, needle: String): String? {
            var shown = text.trim().removeSuffix(".local")
            shown = shown.replace('-', ' ').replace('_', ' ')
            val words = shown.split(' ').filter { it.isNotBlank() }
            if (words.size !in 1..6 || shown.length > 40) return null
            if (needle !in shown.lowercase()) return null
            return words.joinToString(" ")
        }

        private fun shortVendor(vendor: String?): String? {
            val folded = vendor?.lowercase() ?: return null
            return when {
                "apple" in folded -> "Apple"
                "samsung" in folded -> "Samsung"
                "google" in folded -> "Google"
                else -> vendor
            }
        }

        private val appleId = Regex(
            """(?i)\b((?:MacBookPro|MacBookAir|Macmini|MacPro|iPhone|iPad|iPod|AppleTV|AudioAccessory|iMac|Watch|Mac)\d+,\d+)\b""",
        )
        private val foldCode = Regex("""smf9(\d)\d""")
        private val foldWord = Regex("""(?:z\s*)?fold\s*(\d)""")
        private val flipCode = Regex("""smf7\d{2}""")
        private val tvCode = Regex("""(?:ue|un|qn|qa|qe|ls)\d{2}""")
        private val samsungCode = Regex("""\bsm-[a-z]\d""")
        private val appleModels = mapOf(
            "iPhone14,2" to "iPhone 13 Pro",
            "iPhone14,3" to "iPhone 13 Pro Max",
            "iPhone14,4" to "iPhone 13 mini",
            "iPhone14,5" to "iPhone 13",
            "iPhone14,6" to "iPhone SE",
            "iPhone14,7" to "iPhone 14",
            "iPhone14,8" to "iPhone 14 Plus",
            "iPhone15,2" to "iPhone 14 Pro",
            "iPhone15,3" to "iPhone 14 Pro Max",
            "iPhone15,4" to "iPhone 15",
            "iPhone15,5" to "iPhone 15 Plus",
            "iPhone16,1" to "iPhone 15 Pro",
            "iPhone16,2" to "iPhone 15 Pro Max",
            "iPhone17,1" to "iPhone 16 Pro",
            "iPhone17,2" to "iPhone 16 Pro Max",
            "iPhone17,3" to "iPhone 16",
            "iPhone17,4" to "iPhone 16 Plus",
            "iPhone17,5" to "iPhone 16e",
        )
    }
}

data class NetBiosName(val name: String, val suffix: Int, val group: Boolean)

enum class NotableDevice(val title: String) {
    CAMERA("Camera"),
    IOT("IoT"),
    ;

    companion object {
        fun classify(manufacturer: String?, oui: String?, upnpType: String?, hasRtsp: Boolean): NotableDevice? {
            if (hasRtsp) return CAMERA
            val type = (upnpType ?: "").lowercase()
            if ("camera" in type || "videosurveillance" in type || "doorbell" in type) return CAMERA
            vendorKind(manufacturer)?.let { return it }
            vendorKind(oui?.let { Catalog.suspiciousOui(it) })?.let { return it }
            if ("binarylight" in type || "dimmable" in type || "hvac" in type || "sensor" in type || "basic" in type) {
                return IOT
            }
            return null
        }

        private fun vendorKind(name: String?): NotableDevice? {
            val folded = name?.lowercase() ?: return null
            if (Catalog.cameraMakers.any { it in folded }) return CAMERA
            if (Catalog.iotMakers.any { it in folded }) return IOT
            return null
        }
    }
}

/** Parsers for the text and SMB packets a host answers with. Sockets live in the scanner. */
object LanIdentity {
    private val dnsSdEscapes = Regex("""\\(\d{3})""")
    private val serviceTypeSuffix = Regex("""(?i)(?:\._[a-z0-9-]+)*\._(?:tcp|udp)(?:\.local)?$""")

    private val genericNames = setOf(
        "unknown", "unnamed", "device", "localhost", "windows",
        "mediarenderer", "mediaserver", "basic", "internetgatewaydevice",
        "wandevice", "wanconnectiondevice",
    )

    private val latin1: Charset = Charset.forName("ISO-8859-1")

    const val SMB_SUCCESS = 0L
    const val SMB_MORE_PROCESSING = 0xC0000016L

    fun ssdpSearch(host: String): ByteArray =
        "M-SEARCH * HTTP/1.1\r\nHOST: $host:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n"
            .encodeToByteArray()

    data class Ssdp(val location: String?, val server: String?, val searchTarget: String?)

    fun parseSsdp(data: ByteArray): Ssdp? {
        val text = data.decodeText() ?: return null
        val message = HeaderText.parse(text)
        if (!message.start.uppercase().contains("HTTP/")) return null
        val location = message.headers["location"]
        val server = message.headers["server"]
        val target = message.headers["st"]
        if (location == null && server == null && target == null) return null
        return Ssdp(location, server, target)
    }

    fun locationIsOnHost(location: String, ip: String): Boolean {
        val uri = try {
            URI(location)
        } catch (_: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        return host == ip.lowercase() || host == "[${ip.lowercase()}]"
    }

    fun parseUpnpDescription(xml: String): UPnPIdentity {
        val type = tag("deviceType", xml)?.let { shortDeviceType(it) }
        val model = listOfNotNull(tag("modelName", xml), tag("modelNumber", xml))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return UPnPIdentity(
            friendlyName = tag("friendlyName", xml),
            manufacturer = tag("manufacturer", xml),
            model = model.ifEmpty { null },
            deviceType = type,
        )
    }

    fun httpRequest(host: String, method: String, version: String = "1.0"): ByteArray =
        "$method / HTTP/$version\r\nHost: $host\r\nUser-Agent: Spectra\r\nConnection: close\r\n\r\n".encodeToByteArray()

    /** Router pages often answer HEAD with 501 and still serve the page for GET. */
    fun httpMethodRejected(status: Int?): Boolean = status == 400 || status == 405 || status == 501 || status == 505

    /** A later response is worth keeping when it is a real page rather than the HEAD error. */
    fun httpPrefers(status: Int?, title: String?, otherStatus: Int?, otherTitle: String?): Boolean {
        fun rank(code: Int?) = when (code) {
            in 200..399 -> 0
            401, 403 -> 1
            null -> 3
            else -> 2
        }
        if (rank(status) != rank(otherStatus)) return rank(status) < rank(otherStatus)
        return title != null && otherTitle == null
    }

    data class HttpFields(
        val status: Int?,
        val server: String?,
        val poweredBy: String?,
        val title: String?,
        val realm: String?,
    )

    fun parseHttp(data: ByteArray): HttpFields? {
        val text = data.decodeText() ?: return null
        val message = HeaderText.parse(text)
        if (!message.start.uppercase().startsWith("HTTP/")) return null
        val status = message.start.split(" ").getOrNull(1)?.toIntOrNull()
        return HttpFields(
            status,
            clip(message.headers["server"]),
            clip(message.headers["x-powered-by"]),
            htmlTitle(message.body),
            realm(message.headers["www-authenticate"]),
        )
    }

    fun rtspOptions(): ByteArray =
        "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: Spectra\r\n\r\n".encodeToByteArray()

    data class RtspFields(val server: String?, val methods: String?)

    fun parseRtsp(data: ByteArray): RtspFields? {
        val text = data.decodeText() ?: return null
        val message = HeaderText.parse(text)
        if (!message.start.uppercase().startsWith("RTSP/")) return null
        return RtspFields(clip(message.headers["server"]), clip(message.headers["public"]))
    }

    fun netbiosNodeStatusRequest(): ByteArray {
        val out = ByteWriter()
        out.bytes(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0x20, 0x43, 0x4B))
        repeat(30) { out.u8(0x41) }
        out.bytes(byteArrayOf(0, 0, 0x21, 0, 1))
        return out.toByteArray()
    }

    fun parseNetBios(data: ByteArray): List<NetBiosName> {
        val marker = findBytes(byteArrayOf(0, 0x21, 0, 1), data) ?: return emptyList()
        var index = marker + 4
        if (index + 2 <= data.size && data.u8(index) == 0xC0) index += 2
        if (index + 10 > data.size) return emptyList()
        index += 8
        index += 2
        if (index >= data.size) return emptyList()
        val count = data.u8(index)
        index += 1
        val names = mutableListOf<NetBiosName>()
        repeat(count) {
            if (index + 18 > data.size) return names
            val name = String(data, index, 15, Charsets.US_ASCII).trim()
            val suffix = data.u8(index + 15)
            val flags = (data.u8(index + 16) shl 8) or data.u8(index + 17)
            if (name.isNotEmpty()) names.add(NetBiosName(name, suffix, flags and 0x8000 != 0))
            index += 18
        }
        return names
    }

    fun ntlmType1(): ByteArray {
        val out = ByteWriter()
        out.bytes("NTLMSSP\u0000".encodeToByteArray())
        out.bytes(byteArrayOf(0x01, 0, 0, 0))
        out.bytes(byteArrayOf(0x05, 0x02, 0x08, 0))
        repeat(8) { out.u8(0) }
        out.bytes(byteArrayOf(0x20, 0, 0, 0))
        repeat(4) { out.u8(0) }
        out.bytes(byteArrayOf(0x20, 0, 0, 0))
        return out.toByteArray()
    }

    fun ntlmType3(): ByteArray {
        val out = ByteWriter()
        out.bytes("NTLMSSP\u0000".encodeToByteArray())
        out.bytes(byteArrayOf(0x03, 0, 0, 0))
        repeat(6) { out.bytes(byteArrayOf(0, 0, 0, 0, 0x40, 0, 0, 0)) }
        out.bytes(byteArrayOf(0x05, 0x02, 0x08, 0))
        return out.toByteArray()
    }

    data class NtlmNames(val computer: String?, val domain: String?)

    fun parseNtlmChallenge(data: ByteArray): NtlmNames? {
        val start = findBytes("NTLMSSP\u0000".encodeToByteArray(), data) ?: return null
        if (start + 48 > data.size) return null
        val type = data.u32le(start + 8)
        if (type != 2L) return null
        val infoLength = data.u16le(start + 40)
        val infoOffset = data.u32le(start + 44).toInt()
        val infoStart = start + infoOffset
        if (infoLength <= 0 || infoStart < 0 || infoStart + infoLength > data.size) return null
        var netbiosComputer: String? = null
        var dnsComputer: String? = null
        var netbiosDomain: String? = null
        var dnsDomain: String? = null
        var cursor = infoStart
        val end = infoStart + infoLength
        while (cursor + 4 <= end) {
            val id = data.u16le(cursor)
            val length = data.u16le(cursor + 2)
            cursor += 4
            if (cursor + length > end) break
            if (id == 0) break
            val value = utf16le(data.copyOfRange(cursor, cursor + length))
            when (id) {
                1 -> netbiosComputer = netbiosComputer ?: value
                2 -> netbiosDomain = netbiosDomain ?: value
                3 -> dnsComputer = dnsComputer ?: value
                4 -> dnsDomain = dnsDomain ?: value
            }
            cursor += length
        }
        val computer = preferredComputerName(netbiosComputer, dnsComputer)
        val domain = netbiosDomain ?: dnsDomain
        if (computer == null && domain == null) return null
        return NtlmNames(computer, domain)
    }

    /**
     * NetBIOS cuts a name to 15 capitals: "ALEXANDERS-MACB". The DNS name a
     * Mac or a Windows PC also sends ("Alexanders-MacBook-Pro.local") keeps
     * the words that identify it, so its host part wins when it says more.
     */
    fun preferredComputerName(netbios: String?, dns: String?): String? {
        val host = dns?.substringBefore('.')?.let { cleanedText(it) } ?: return netbios
        if (netbios == null) return host
        return if (host.length > netbios.length) host else netbios
    }

    fun smbStatus(response: ByteArray): Long? {
        if (response.size < 12) return null
        if (response.u8(0) != 0xFE || response.u8(1) != 0x53 || response.u8(2) != 0x4D || response.u8(3) != 0x42) {
            return null
        }
        return response.u32le(8)
    }

    fun smbSessionId(response: ByteArray): Long? {
        if (smbStatus(response) == null || response.size < 48) return null
        var value = 0L
        for (shift in 0 until 8) value = value or (response.u8(40 + shift).toLong() shl (8 * shift))
        return value
    }

    fun smbNegotiate(): ByteArray = frame(smbHeader(0, 0) + negotiateBody())

    fun smbSessionSetup(ntlm: ByteArray, messageId: Long, sessionId: Long = 0): ByteArray {
        val body = ByteWriter()
        body.u16(25)
        body.u8(0)
        body.u8(1)
        body.u32(0)
        body.u32(0)
        body.u16(88)
        body.u16(ntlm.size)
        body.u32(0)
        body.u32(0)
        body.bytes(ntlm)
        return frame(smbHeader(1, messageId, sessionId) + body.toByteArray())
    }

    fun smbTreeConnect(share: String, ip: String, messageId: Long, sessionId: Long): ByteArray {
        val path = utf16("\\\\$ip\\$share")
        val body = ByteWriter()
        body.u16(9)
        body.u16(0)
        body.u16(72)
        body.u16(path.size)
        body.bytes(path)
        return frame(smbHeader(3, messageId, sessionId) + body.toByteArray())
    }

    fun smbPayload(framed: ByteArray): ByteArray {
        if (framed.size < 4) return framed
        val length = (framed.u8(1) shl 16) or (framed.u8(2) shl 8) or framed.u8(3)
        val end = minOf(framed.size, 4 + length)
        return framed.copyOfRange(4, end)
    }

    fun cleanedText(raw: String): String? {
        val filtered = raw.filter { char ->
            val value = char.code
            value !in 0x00..0x1F && value != 0x7F && value != 0xFEFF &&
                value != 0x200B && value != 0x200C && value != 0x200D && value != 0x2060
        }.trim()
        return filtered.ifEmpty { null }
    }

    fun presentableName(raw: String): String? {
        val cleaned = cleanedText(raw) ?: return null
        if (!cleaned.any { it.isLetter() }) return null
        if (cleaned.lowercase() in genericNames) return null
        return cleaned
    }

    /**
     * The instance name a person would read. AirPlay audio prefixes a MAC,
     * Android's resolver sometimes glues on the service type, and DNS-SD
     * writes a space as `\032`.
     */
    fun tidyInstanceName(raw: String): String {
        var name = dnsSdEscapes.replace(raw) { match ->
            val code = match.groupValues[1].toInt()
            if (code in 32..126) code.toChar().toString() else ""
        }.trim().trim('\u0000')
        val at = name.indexOf('@')
        if (at == 12 && name.substring(0, at).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            name = name.substring(at + 1).trim()
        }
        name = name.trimEnd('.')
        name = serviceTypeSuffix.replace(name, "")
        if (name.endsWith(".local", ignoreCase = true)) {
            name = name.dropLast(6).trim()
        }
        return name.trim().trimEnd('.')
    }

    fun decodeXmlEntities(raw: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < raw.length) {
            if (raw[index] == '&') {
                val rest = raw.substring(index)
                val end = rest.indexOf(';')
                if (end in 1..16) {
                    val decoded = xmlEntity(rest.substring(1, end))
                    if (decoded != null) {
                        result.append(decoded)
                        index += end + 1
                        continue
                    }
                }
                if (rest.startsWith("&quot:")) {
                    result.append('"')
                    index += 6
                    continue
                }
            }
            result.append(raw[index])
            index += 1
        }
        return result.toString()
    }

    private fun smbHeader(command: Int, messageId: Long, sessionId: Long = 0): ByteArray {
        val header = ByteWriter()
        header.bytes(byteArrayOf(0xFE.toByte(), 0x53, 0x4D, 0x42))
        header.u16(64)
        header.u16(0)
        header.u32(0)
        header.u16(command)
        header.u16(1)
        header.u32(0)
        header.u32(0)
        header.u32(messageId and 0xFFFF_FFFFL)
        header.u32(messageId ushr 32)
        header.u32(0)
        header.u32(0)
        header.u32(sessionId and 0xFFFF_FFFFL)
        header.u32(sessionId ushr 32)
        repeat(16) { header.u8(0) }
        return header.toByteArray()
    }

    private fun negotiateBody(): ByteArray {
        val body = ByteWriter()
        body.u16(36)
        body.u16(4)
        body.u16(1)
        body.u16(0)
        body.u32(0)
        repeat(16) { body.u8(0x11) }
        body.u32(0)
        body.u16(0)
        body.u16(0)
        for (dialect in intArrayOf(0x0202, 0x0210, 0x0300, 0x0302)) body.u16(dialect)
        return body.toByteArray()
    }

    private fun frame(smb: ByteArray): ByteArray {
        val out = ByteWriter()
        out.u8(0)
        out.u8((smb.size shr 16) and 0xFF)
        out.u8((smb.size shr 8) and 0xFF)
        out.u8(smb.size and 0xFF)
        out.bytes(smb)
        return out.toByteArray()
    }

    internal fun tag(name: String, xml: String): String? {
        val regex = Regex("<$name\\b[^>]*>([^<]*)</$name>", RegexOption.IGNORE_CASE)
        for (match in regex.findAll(xml)) {
            val value = decodeXmlEntities(match.groupValues[1])
            cleanedText(value)?.let { return it }
        }
        return null
    }

    private fun xmlEntity(token: String): Char? = when (token.lowercase()) {
        "quot" -> '"'
        "apos" -> '\''
        "amp" -> '&'
        "lt" -> '<'
        "gt" -> '>'
        else -> {
            val scalar = when {
                token.startsWith("#x", ignoreCase = true) -> token.drop(2).toIntOrNull(16)
                token.startsWith("#") -> token.drop(1).toIntOrNull()
                else -> null
            }
            scalar?.takeIf { Character.isValidCodePoint(it) }?.let { it.toChar() }
        }
    }

    private fun shortDeviceType(urn: String): String {
        val parts = urn.split(":")
        if (parts.size < 2) return urn
        return parts[parts.size - 2]
    }

    private fun htmlTitle(body: String): String? {
        val lower = body.lowercase()
        val open = lower.indexOf("<title")
        if (open < 0) return null
        val start = body.indexOf('>', open)
        if (start < 0) return null
        val rest = body.substring(start + 1)
        val close = rest.lowercase().indexOf("</")
        if (close < 0) return null
        return clip(decodeXmlEntities(rest.substring(0, close)).trim())
    }

    private fun realm(header: String?): String? {
        header ?: return null
        val marker = Regex("realm=\"", RegexOption.IGNORE_CASE).find(header) ?: return null
        val rest = header.substring(marker.range.last + 1)
        val end = rest.indexOf('"')
        if (end < 0) return null
        return clip(rest.substring(0, end))
    }

    private fun clip(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return trimmed.take(120)
    }

    private fun utf16(text: String): ByteArray {
        val out = ByteWriter()
        for (unit in text) {
            val code = unit.code
            out.u8(code and 0xFF)
            out.u8((code shr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun utf16le(bytes: ByteArray): String? {
        val text = String(bytes, Charsets.UTF_16LE).trim()
        return text.ifEmpty { null }
    }

    private fun ByteArray.decodeText(): String? =
        try {
            String(this, Charsets.UTF_8)
        } catch (_: Exception) {
            String(this, latin1)
        }
}

object HeaderText {
    data class Message(val start: String, val headers: Map<String, String>, val body: String)

    fun parse(text: String): Message {
        val split = text.indexOf("\r\n\r\n").let { if (it >= 0) it to 4 else text.indexOf("\n\n").let { lf -> if (lf >= 0) lf to 2 else null } }
        val head: String
        val body: String
        if (split == null) {
            head = text
            body = ""
        } else {
            head = text.substring(0, split.first)
            body = text.substring(split.first + split.second)
        }
        val lines = head.split('\n').map { it.trimEnd('\r') }.toMutableList()
        val start = lines.firstOrNull().orEmpty()
        if (lines.isNotEmpty()) lines.removeAt(0)
        val headers = linkedMapOf<String, String>()
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (key !in headers) headers[key] = value
        }
        return Message(start, headers, body)
    }
}

private fun String?.keptName(previous: String?): String? {
    if (this != null && LanIdentity.presentableName(this) != null) return LanIdentity.cleanedText(this)
    return previous
}

private fun String?.kept(previous: String?): String? {
    if (!this.isNullOrBlank()) return this
    return previous
}
