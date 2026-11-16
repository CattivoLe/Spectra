package cattivole.spectra.core

import org.json.JSONObject
import java.text.DateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date

/** What a zone's registry says about a domain. Mirrors the iOS `DomainReport`. */
data class DomainReport(val domain: String, val registered: Long? = null, val expires: Long? = null, val registrar: String? = null)

/** How the registry lookup ended. */
sealed class DomainOutcome {
    data object Skipped : DomainOutcome()
    data class Found(val report: DomainReport) : DomainOutcome()
    data class UnsupportedZone(val zone: String) : DomainOutcome()
    data class NotRegistered(val name: String) : DomainOutcome()
    data class Failed(val reason: String) : DomainOutcome()
}

/** The leaf certificate facts the findings need. */
data class SiteCertificate(
    val issuer: String?,
    val notBefore: Long,
    val notAfter: Long,
    val trusted: Boolean,
    val trustError: String?,
)

/** What a site's certificate and its registry record say about how old and how genuine it is. */
data class SiteReport(
    val host: String,
    val certificate: SiteCertificate?,
    val certificateError: String?,
    val domain: DomainOutcome,
) {
    fun findings(now: Long = System.currentTimeMillis()): List<LinkInspection.Finding> {
        val findings = mutableListOf<LinkInspection.Finding>()
        fun note(kind: LinkInspection.Kind, severity: LinkInspection.Severity, text: String, vararg args: String) {
            findings.add(LinkInspection.Finding(kind, severity, text, args.toList()))
        }
        val danger = LinkInspection.Severity.DANGER
        val caution = LinkInspection.Severity.CAUTION
        val info = LinkInspection.Severity.INFO

        val report = certificate
        if (report != null) {
            if (!report.trusted) {
                note(LinkInspection.Kind.CERTIFICATE_UNTRUSTED, danger, "This phone does not trust this site's certificate: %s.", report.trustError ?: "unknown reason")
            }
            if (report.notAfter < now) {
                note(LinkInspection.Kind.CERTIFICATE_EXPIRED, danger, "The certificate expired on %s.", day(report.notAfter))
            }
            val age = days(report.notBefore, now)
            if (age in 0 until FRESH_CERTIFICATE_DAYS) {
                if (age == 0L) {
                    note(LinkInspection.Kind.CERTIFICATE_NEW, caution, "The certificate was issued today. Phishing sites are usually only days old.")
                } else {
                    note(LinkInspection.Kind.CERTIFICATE_NEW, caution, "The certificate was issued %s days ago. Phishing sites are usually only days old.", age.toString())
                }
            } else if (report.trusted) {
                note(LinkInspection.Kind.CERTIFICATE_INFO, info, "Certificate from %s, issued %s.", report.issuer ?: "?", day(report.notBefore))
            }
        } else if (certificateError != null) {
            note(LinkInspection.Kind.CERTIFICATE_UNAVAILABLE, caution, "No usable HTTPS on this site: %s.", certificateError)
        }

        when (val outcome = domain) {
            is DomainOutcome.Found -> {
                val registered = outcome.report.registered
                if (registered == null) {
                    note(LinkInspection.Kind.DOMAIN_INFO, info, "The registry lists %s but gives no registration date.", outcome.report.domain)
                } else {
                    val age = days(registered, now)
                    val since = day(registered)
                    when {
                        age <= 0 -> note(LinkInspection.Kind.DOMAIN_NEW, danger, "The domain was registered today.")
                        age < NEW_DOMAIN_DAYS -> note(LinkInspection.Kind.DOMAIN_NEW, danger, "The domain was registered %s days ago, on %s.", age.toString(), since)
                        age < YOUNG_DOMAIN_DAYS -> note(LinkInspection.Kind.DOMAIN_YOUNG, caution, "The domain is only %s days old, registered on %s.", age.toString(), since)
                        else -> {
                            val years = ChronoUnit.YEARS.between(
                                Instant.ofEpochMilli(registered).atZone(ZoneId.systemDefault()),
                                Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()),
                            )
                            if (years >= 1) {
                                note(LinkInspection.Kind.DOMAIN_INFO, info, "The domain has existed since %s, %s years.", since, years.toString())
                            } else {
                                note(LinkInspection.Kind.DOMAIN_INFO, info, "The domain was registered on %s.", since)
                            }
                        }
                    }
                }
            }
            is DomainOutcome.NotRegistered ->
                note(LinkInspection.Kind.DOMAIN_MISSING, caution, "The registry has no record of %s. The name may be free, or the link may be a decoy.", outcome.name)
            is DomainOutcome.UnsupportedZone ->
                note(LinkInspection.Kind.DOMAIN_UNKNOWN, info, "The .%s registry does not publish registration dates.", outcome.zone)
            is DomainOutcome.Failed ->
                note(LinkInspection.Kind.DOMAIN_UNKNOWN, info, "The registry could not be reached: %s.", outcome.reason)
            DomainOutcome.Skipped -> Unit
        }
        return findings.sortedByDescending { it.severity }
    }

    companion object {
        const val FRESH_CERTIFICATE_DAYS = 14
        const val NEW_DOMAIN_DAYS = 30
        const val YOUNG_DOMAIN_DAYS = 180

        private fun days(from: Long, to: Long): Long = Math.floorDiv(to - from, 86_400_000L)

        private fun day(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }
}

/** Which RDAP server answers for each top-level domain: IANA's bootstrap list, bundled as `rdap.tsv`. */
object RdapServers {
    private val servers: Map<String, String> by lazy {
        val stream = RdapServers::class.java.classLoader?.getResourceAsStream("spectra/rdap.tsv") ?: return@lazy emptyMap()
        stream.bufferedReader().useLines { lines ->
            val map = HashMap<String, String>()
            for (line in lines) {
                if (line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val url = line.substring(tab + 1).trim()
                // The domain is only ever sent over HTTPS.
                if (!url.startsWith("https://", ignoreCase = true)) continue
                val base = if (url.endsWith("/")) url else "$url/"
                for (tld in line.substring(0, tab).split(' ')) if (tld.isNotEmpty()) map[tld.lowercase()] = base
            }
            map
        }
    }

    /** The registry for a domain, by its last label. [asciiDomain] uses `xn--` for IDN zones. */
    fun server(asciiDomain: String): String? = servers[asciiDomain.lowercase().substringAfterLast('.')]

    /** The registration and expiry events and the registrar's name of an RDAP domain object. */
    fun parse(json: String, domain: String): DomainReport? {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        var registered: Long? = null
        var expires: Long? = null
        val events = root.optJSONArray("events")
        for (index in 0 until (events?.length() ?: 0)) {
            val event = events?.optJSONObject(index) ?: continue
            val date = parseDate(event.optString("eventDate")) ?: continue
            when (event.optString("eventAction").lowercase()) {
                "registration" -> registered = date
                "expiration" -> expires = date
            }
        }
        var registrar: String? = null
        val entities = root.optJSONArray("entities")
        for (index in 0 until (entities?.length() ?: 0)) {
            val entity = entities?.optJSONObject(index) ?: continue
            val roles = entity.optJSONArray("roles")
            val isRegistrar = (0 until (roles?.length() ?: 0)).any { roles?.optString(it).equals("registrar", ignoreCase = true) }
            if (!isRegistrar) continue
            val items = entity.optJSONArray("vcardArray")?.optJSONArray(1)
            for (item in 0 until (items?.length() ?: 0)) {
                val field = items?.optJSONArray(item) ?: continue
                if (field.optString(0) == "fn" && field.length() > 3) {
                    field.optString(3).takeIf { it.isNotEmpty() }?.let { registrar = it }
                }
            }
            if (registrar != null) break
        }
        return DomainReport(root.optString("ldhName").lowercase().ifEmpty { domain }, registered, expires, registrar)
    }

    private fun parseDate(text: String): Long? = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try {
            Instant.parse(text).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
