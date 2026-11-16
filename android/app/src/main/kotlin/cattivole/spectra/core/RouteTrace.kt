package cattivole.spectra.core

import org.json.JSONObject

/** Where a hop's address is, as ipwho.is places it. Mirrors the iOS `HopPlace`. */
data class HopPlace(
    val latitude: Double,
    val longitude: Double,
    val city: String? = null,
    val countryCode: String? = null,
    val isp: String? = null,
)

/** The pure parts of a traceroute to a target the person chose. Mirrors the iOS `RouteTrace`. */
object RouteTrace {
    const val DEFAULT_TARGET = "1.1.1.1"
    const val MAX_HOPS = 24

    /** The host in what was typed: a name, an IP address, or a link. */
    fun target(input: String): String? {
        var text = input.trim().lowercase()
        val scheme = text.indexOf("://")
        if (scheme >= 0) text = text.substring(scheme + 3)
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0) return null
            text = text.substring(1, close)
            return text.takeIf { isIpv6(it) }
        }
        if (isIpv6(text)) return text
        text = text.takeWhile { it !in "/?#" }
        val colon = text.lastIndexOf(':')
        if (colon >= 0) text = text.substring(0, colon)
        text = text.trimEnd('.')
        if (text.isEmpty() || text.any { it.isWhitespace() } || text.length > 253) return null
        return text
    }

    private fun isIpv6(text: String): Boolean =
        text.count { it == ':' } >= 2 && text.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }

    private fun ipv4(text: String): List<Int>? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        return parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 && part.isNotEmpty() } ?: return null }
    }

    /** Addresses the internet can place: not private, not CGNAT, not link-local or loopback. */
    fun isPublic(address: String): Boolean {
        ipv4(address)?.let { b ->
            if (b[0] == 10 || b[0] == 127 || b[0] == 0 || b[0] >= 224) return false
            if (b[0] == 172 && b[1] in 16..31) return false
            if (b[0] == 192 && b[1] == 168) return false
            if (b[0] == 169 && b[1] == 254) return false
            if (b[0] == 100 && b[1] in 64..127) return false
            return true
        }
        if (isIpv6(address)) {
            val first = address.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return false
            return first and 0xE000 == 0x2000
        }
        return false
    }

    /** One ipwho.is reply, or null when it has no position. */
    fun place(json: String): HopPlace? {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        if (root.has("success") && !root.optBoolean("success")) return null
        if (!root.has("latitude") || !root.has("longitude")) return null
        val lat = root.optDouble("latitude")
        val lon = root.optDouble("longitude")
        if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) return null
        fun text(value: String?) = value?.takeIf { it.isNotBlank() }
        val connection = root.optJSONObject("connection")
        return HopPlace(
            lat, lon,
            city = text(root.optString("city")),
            countryCode = text(root.optString("country_code")),
            isp = text(connection?.optString("isp")) ?: text(connection?.optString("org")),
        )
    }

    /**
     * The hops that have a place, in path order, with consecutive hops in the
     * same spot folded into one point so the line does not stutter.
     */
    fun path(hops: List<TraceHop>, places: Map<String, HopPlace>): List<Pair<Int, HopPlace>> {
        val points = mutableListOf<Pair<Int, HopPlace>>()
        hops.forEachIndexed { index, hop ->
            val place = hop.address?.let { places[it] } ?: return@forEachIndexed
            val last = points.lastOrNull()?.second
            if (last != null && kotlin.math.abs(last.latitude - place.latitude) < 0.01 && kotlin.math.abs(last.longitude - place.longitude) < 0.01) {
                return@forEachIndexed
            }
            points.add(index + 1 to place)
        }
        return points
    }
}
