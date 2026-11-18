package cattivole.spectra.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** A link's HTTP redirects, one hop at a time. Mirrors the iOS `RedirectTrace`. */
data class RedirectTrace(val hops: List<Hop>, val final: String, val stop: Stop?) {
    data class Hop(val url: String, val status: Int)

    sealed class Stop {
        data object TooManyHops : Stop()
        data class Failed(val reason: String) : Stop()
        data class LeftTheWeb(val scheme: String) : Stop()
    }
}

/**
 * Follows a link's redirects one hop at a time, reading the headers only. No cookies, no cache and no page
 * body, but each server along the way still sees the request: the screen asks the reader first.
 */
object RedirectTracer {
    suspend fun trace(start: String, maxHops: Int = 10): RedirectTrace = withContext(Dispatchers.IO) {
        var current = start
        val hops = mutableListOf<RedirectTrace.Hop>()
        repeat(maxHops) {
            val connection = try {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    useCaches = false
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Spectra")
                }
            } catch (error: Exception) {
                return@withContext RedirectTrace(hops, current, RedirectTrace.Stop.Failed(error.message ?: error.javaClass.simpleName))
            }
            try {
                val status = connection.responseCode
                hops.add(RedirectTrace.Hop(current, status))
                val location = connection.getHeaderField("Location")
                if (status !in 300..399 || location.isNullOrBlank()) return@withContext RedirectTrace(hops, current, null)
                val next = runCatching { URI(current).resolve(location.trim()).toString() }.getOrNull()
                    ?: return@withContext RedirectTrace(hops, current, null)
                val scheme = next.substringBefore(':', "").lowercase()
                if (scheme != "http" && scheme != "https") {
                    return@withContext RedirectTrace(hops, next, RedirectTrace.Stop.LeftTheWeb(scheme))
                }
                current = next
            } catch (error: Exception) {
                return@withContext RedirectTrace(hops, current, RedirectTrace.Stop.Failed(error.message ?: error.javaClass.simpleName))
            } finally {
                connection.disconnect()
            }
        }
        RedirectTrace(hops, current, RedirectTrace.Stop.TooManyHops)
    }

    /** Equal up to a trailing slash and the case of scheme and host. */
    fun sameAddress(a: String, b: String): Boolean {
        fun key(text: String): String {
            val uri = runCatching { URI(text) }.getOrNull()
            var out = text
            uri?.scheme?.let { out = it.lowercase() + out.drop(it.length) }
            uri?.host?.let { host -> out = out.replaceFirst(host, host.lowercase()) }
            return out.trimEnd('/')
        }
        return key(a) == key(b)
    }
}
