package cattivole.spectra.platform

import android.net.Network
import cattivole.spectra.core.TlsInspector
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** The certificate a host presented, and whether this phone would trust it. */
data class CertificateReport(
    val subject: String?,
    val names: List<String>,
    /** The issuer's organisation, or its common name when it gives no organisation. */
    val issuer: String?,
    val issuerCommonName: String?,
    val notBefore: Long,
    val notAfter: Long,
    /** Subjects from the leaf up, as the server sent them. */
    val chain: List<String>,
    /** SHA-256 of the leaf, in hex. */
    val fingerprint: String,
    val trusted: Boolean,
    val trustError: String?,
)

/**
 * Completes a TLS handshake without loading a page and reads the chain. The
 * handshake itself accepts any certificate, so a forged one can be shown;
 * trust is judged afterwards against the system's authorities.
 */
object CertificateProbe {
    fun fetch(host: String, port: Int = 443, network: Network? = null, timeoutMs: Int = 6000): Result<CertificateReport> = runCatching {
        var presented: Array<out X509Certificate> = emptyArray()
        var authType = "RSA"
        val recorder = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, type: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, type: String) {
                presented = chain
                authType = type
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recorder), null) }
        val plain = NetConnect.connect(network, host, port, timeoutMs).socket
        plain.soTimeout = timeoutMs
        val socket = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        socket.use {
            it.startHandshake()
            val chain = presented.ifEmpty { it.session.peerCertificates.filterIsInstance<X509Certificate>().toTypedArray() }
            val leaf = chain.firstOrNull() ?: error("The server sent no certificate.")
            var trustError: String? = null
            try {
                systemTrust().checkServerTrusted(chain, authType)
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, it.session)) {
                    trustError = "The certificate is for another site."
                }
            } catch (error: Exception) {
                trustError = error.message ?: "The certificate is not trusted."
            }
            val issuerDn = leaf.issuerX500Principal.name
            val issuerName = TlsInspector.attribute(issuerDn, "CN")
            CertificateReport(
                subject = TlsInspector.attribute(leaf.subjectX500Principal.name, "CN"),
                names = leaf.subjectAlternativeNames.orEmpty().mapNotNull { entry ->
                    // 2 is a DNS name, 7 an IP address.
                    val kind = entry.getOrNull(0) as? Int
                    if (kind == 2 || kind == 7) entry.getOrNull(1)?.toString() else null
                },
                issuer = TlsInspector.attribute(issuerDn, "O") ?: issuerName,
                issuerCommonName = issuerName,
                notBefore = leaf.notBefore.time,
                notAfter = leaf.notAfter.time,
                chain = chain.map { cert ->
                    val dn = cert.subjectX500Principal.name
                    TlsInspector.attribute(dn, "CN") ?: TlsInspector.attribute(dn, "O") ?: dn
                },
                fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02X".format(it) },
                trusted = trustError == null,
                trustError = trustError,
            )
        }
    }

    private fun systemTrust(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
