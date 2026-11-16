package cattivole.spectra.core

import org.json.JSONArray
import org.json.JSONObject

/** One reference site's certificate as seen from this network. Mirrors the iOS `TLSObservation`. */
data class TlsObservation(
    val host: String,
    val issuer: String?,
    val trusted: Boolean,
    /** The issuer is one this site is known to use, or a public authority. */
    val expected: Boolean,
    val error: String? = null,
    val subject: String? = null,
    val names: List<String>? = null,
    val issuerCommonName: String? = null,
    val notBefore: Long? = null,
    val notAfter: Long? = null,
    val chain: List<String>? = null,
    /** SHA-256 of the leaf, in hex. */
    val fingerprint: String? = null,
    val trustError: String? = null,
)

/** Whether HTTPS to well-known sites arrives untouched. */
data class TlsCheck(val observations: List<TlsObservation>) {
    /**
     * A certificate the system rejects, an issuer no public authority would
     * use, or one issuer signing for every site at once: something in the
     * path re-signs HTTPS.
     */
    val intercepted: Boolean
        get() {
            val seen = observations.filter { it.error == null }
            if (seen.any { !it.trusted || !it.expected }) return true
            val issuers = seen.mapNotNull { it.issuer?.lowercase() }.toSet()
            return seen.size >= 3 && issuers.size == 1
        }

    val reachable: Int get() = observations.count { it.error == null }
}

/** Who really answers this network's name lookups. */
data class DnsReport(
    /** Resolvers the phone is configured to ask. */
    val configured: List<String> = emptyList(),
    /** The address the authoritative servers see the lookups come from. */
    val resolverIp: String? = null,
    val resolverName: String? = null,
    val resolverCountry: String? = null,
    /** The resolver returned an address for a name that does not exist. */
    val liesAboutMissingNames: Boolean? = null,
    val dohReachable: Boolean? = null,
    val dotReachable: Boolean? = null,
) {
    enum class Path {
        /** The lookups leave from this network's own public address. */
        LOCAL,

        /** A configured private resolver (the router) hands lookups to someone else. */
        FORWARDED,

        /** A public resolver, asked directly. */
        DIRECT,
        UNKNOWN,
    }

    fun path(publicIp: String?): Path {
        val resolver = resolverIp ?: return Path.UNKNOWN
        if (publicIp != null && resolver == publicIp) return Path.LOCAL
        return if (configured.any(::isPrivate)) Path.FORWARDED else Path.DIRECT
    }

    companion object {
        fun isPrivate(address: String): Boolean {
            val parts = address.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size == 4 && '.' in address && ':' !in address) {
                return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168) ||
                    (parts[0] == 169 && parts[1] == 254) || parts[0] == 127
            }
            val lower = address.lowercase()
            return lower == "::1" || lower.startsWith("fd") || lower.startsWith("fc") || lower.startsWith("fe")
        }
    }
}

/** One port on the public address, tried from the internet. */
data class ExposedPort(val port: Int, val open: Boolean)

/** Which ports of the public address answer from outside. */
data class ExposureReport(val ip: String, val checkedAt: Long, val ports: List<ExposedPort>, val note: RemoteNote) {
    val openPorts: List<ExposedPort> get() = ports.filter { it.open }
}

/**
 * Judges whether anything between the phone and the internet re-signs HTTPS.
 * A corporate proxy, an antivirus or an attacker on the Wi-Fi has to present
 * its own certificate, issued by an authority no public site uses.
 */
object TlsInspector {
    /** [issuers] are substrings the issuer's name is expected to contain; empty means any public authority. */
    class Reference(val host: String, val issuers: List<String>)

    val references = listOf(
        Reference("www.apple.com", listOf("apple")),
        Reference("www.google.com", listOf("google")),
        Reference("www.cloudflare.com", emptyList()),
        Reference("www.wikipedia.org", emptyList()),
    )

    /** Authorities public sites buy certificates from. A proxy's own root is not here. */
    val publicAuthorities = listOf(
        "digicert", "let's encrypt", "google trust services", "sectigo", "globalsign", "amazon", "godaddy",
        "entrust", "identrust", "cloudflare", "microsoft", "apple", "buypass", "ssl corporation", "ssl.com",
        "certum", "actalis", "zerossl", "comodo", "geotrust", "thawte", "rapidssl", "starfield", "trustwave",
        "swisssign", "quovadis", "d-trust", "telia", "harica", "izenpe", "certigna", "twca", "hongkong post",
        "netlock", "e-tugra", "cybertrust", "secom", "atos", "deutsche telekom", "t-systems", "unizeto",
    )

    /** Ports a home router is most often found answering on from outside. */
    val exposurePorts = listOf(21, 22, 23, 80, 443, 445, 3389, 7547, 8080, 8443)

    /** Whether an issuer is one the reference host would use. */
    fun isExpected(issuer: String?, reference: Reference): Boolean {
        val name = issuer?.lowercase() ?: return false
        if (reference.issuers.isNotEmpty()) return reference.issuers.any { it in name }
        return publicAuthorities.any { it in name }
    }

    /** The value of one attribute ("O", "CN") in an RFC 2253 distinguished name. */
    fun attribute(dn: String, key: String): String? {
        // Commas inside a value are escaped with a backslash or quoted.
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < dn.length) {
            val char = dn[index]
            when {
                char == '\\' && index + 1 < dn.length -> {
                    current.append(dn[index + 1])
                    index += 1
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    parts.add(current.toString())
                    current.clear()
                }
                else -> current.append(char)
            }
            index += 1
        }
        parts.add(current.toString())
        for (part in parts) {
            val equals = part.indexOf('=')
            if (equals > 0 && part.substring(0, equals).trim().equals(key, ignoreCase = true)) {
                return part.substring(equals + 1).trim().ifEmpty { null }
            }
        }
        return null
    }
}

/** The history form of these checks: the keys the iOS app writes, so a file opens on either phone. */
internal object InspectJson {
    /** iOS writes dates as seconds since 2001. */
    private const val APPLE_EPOCH_MS = 978_307_200_000L

    private fun appleSeconds(millis: Long): Double = (millis - APPLE_EPOCH_MS) / 1000.0

    private fun millisOf(json: JSONObject, key: String): Long? =
        if (json.has(key) && !json.isNull(key)) (json.optDouble(key) * 1000).toLong() + APPLE_EPOCH_MS else null

    private fun JSONObject.text(key: String): String? = if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null

    private fun JSONObject.flag(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null

    private fun JSONArray?.strings(): List<String>? = this?.let { array -> (0 until array.length()).map { array.optString(it) } }

    fun tls(check: TlsCheck): JSONObject = JSONObject().put("observations", JSONArray().apply {
        check.observations.forEach { seen ->
            put(
                JSONObject()
                    .put("host", seen.host)
                    .put("trusted", seen.trusted)
                    .put("expected", seen.expected)
                    .putOpt("issuer", seen.issuer)
                    .putOpt("error", seen.error)
                    .putOpt("subject", seen.subject)
                    .putOpt("names", seen.names?.let { JSONArray(it) })
                    .putOpt("issuerCommonName", seen.issuerCommonName)
                    .putOpt("notBefore", seen.notBefore?.let(::appleSeconds))
                    .putOpt("notAfter", seen.notAfter?.let(::appleSeconds))
                    .putOpt("chain", seen.chain?.let { JSONArray(it) })
                    .putOpt("fingerprint", seen.fingerprint)
                    .putOpt("trustError", seen.trustError),
            )
        }
    })

    fun tls(json: JSONObject): TlsCheck {
        val rows = json.optJSONArray("observations") ?: JSONArray()
        return TlsCheck((0 until rows.length()).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            TlsObservation(
                host = item.optString("host"),
                issuer = item.text("issuer"),
                trusted = item.optBoolean("trusted"),
                expected = item.optBoolean("expected"),
                error = item.text("error"),
                subject = item.text("subject"),
                names = item.optJSONArray("names").strings(),
                issuerCommonName = item.text("issuerCommonName"),
                notBefore = millisOf(item, "notBefore"),
                notAfter = millisOf(item, "notAfter"),
                chain = item.optJSONArray("chain").strings(),
                fingerprint = item.text("fingerprint"),
                trustError = item.text("trustError"),
            )
        })
    }

    fun dns(report: DnsReport): JSONObject = JSONObject()
        .put("configured", JSONArray(report.configured))
        .putOpt("resolverIP", report.resolverIp)
        .putOpt("resolverName", report.resolverName)
        .putOpt("resolverCountry", report.resolverCountry)
        .putOpt("liesAboutMissingNames", report.liesAboutMissingNames)
        .putOpt("dohReachable", report.dohReachable)
        .putOpt("dotReachable", report.dotReachable)

    fun dns(json: JSONObject): DnsReport = DnsReport(
        configured = json.optJSONArray("configured").strings().orEmpty(),
        resolverIp = json.text("resolverIP"),
        resolverName = json.text("resolverName"),
        resolverCountry = json.text("resolverCountry"),
        liesAboutMissingNames = json.flag("liesAboutMissingNames"),
        dohReachable = json.flag("dohReachable"),
        dotReachable = json.flag("dotReachable"),
    )

    fun exposure(report: ExposureReport): JSONObject = JSONObject()
        .put("ip", report.ip)
        .put("checkedAt", appleSeconds(report.checkedAt))
        .put("ports", JSONArray().apply { report.ports.forEach { put(JSONObject().put("port", it.port).put("open", it.open)) } })
        .put("note", report.note.name.lowercase())

    fun exposure(json: JSONObject): ExposureReport? {
        val ip = json.text("ip") ?: return null
        val rows = json.optJSONArray("ports") ?: JSONArray()
        return ExposureReport(
            ip = ip,
            checkedAt = millisOf(json, "checkedAt") ?: 0L,
            ports = (0 until rows.length()).mapNotNull { index ->
                rows.optJSONObject(index)?.let { ExposedPort(it.optInt("port"), it.optBoolean("open")) }
            },
            note = RemoteNote.entries.firstOrNull { it.name.equals(json.optString("note"), ignoreCase = true) } ?: RemoteNote.NONE,
        )
    }
}
