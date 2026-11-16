package cattivole.spectra.platform

import cattivole.spectra.core.DomainOutcome
import cattivole.spectra.core.RdapServers
import cattivole.spectra.core.SiteCertificate
import cattivole.spectra.core.SiteReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URL

/**
 * Asks a site for its certificate, without loading a page, and the zone's own
 * registry for the domain's age over RDAP. No third party sees which domain
 * is being checked; the site and the registry see this phone's address.
 */
object SiteProbe {
    suspend fun probe(host: String, domain: String?): SiteReport = coroutineScope {
        val ascii = runCatching { IDN.toASCII(host) }.getOrDefault(host)
        val certificate = async(Dispatchers.IO) { CertificateProbe.fetch(ascii) }
        val registry = async { if (domain == null) DomainOutcome.Skipped else registration(domain) }
        val found = certificate.await()
        SiteReport(
            host = host,
            certificate = found.getOrNull()?.let { SiteCertificate(it.issuer, it.notBefore, it.notAfter, it.trusted, it.trustError) },
            certificateError = found.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
            domain = registry.await(),
        )
    }

    private suspend fun registration(domain: String): DomainOutcome = withContext(Dispatchers.IO) {
        val ascii = runCatching { IDN.toASCII(domain) }.getOrDefault(domain).lowercase()
        val base = RdapServers.server(ascii) ?: return@withContext DomainOutcome.UnsupportedZone(domain.substringAfterLast('.'))
        try {
            val connection = (URL(base + "domain/" + ascii).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/rdap+json")
                setRequestProperty("User-Agent", "Spectra")
            }
            try {
                when (val status = connection.responseCode) {
                    200 -> RdapServers.parse(connection.inputStream.bufferedReader().use { it.readText() }, domain)
                        ?.let { DomainOutcome.Found(it) } ?: DomainOutcome.Failed("unreadable answer")
                    404 -> DomainOutcome.NotRegistered(domain)
                    else -> DomainOutcome.Failed("HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            DomainOutcome.Failed(error.message ?: error.javaClass.simpleName)
        }
    }
}
