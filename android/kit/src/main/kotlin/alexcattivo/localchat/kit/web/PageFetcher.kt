package alexcattivo.localchat.kit.web

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FetchedPage(val url: String, val html: String)

sealed class PageFetchException(message: String) : Exception(message) {
    class BlockedAddress : PageFetchException("blocked address")
    class NotHtml(val type: String) : PageFetchException("not html: $type")
    class Http(val code: Int) : PageFetchException("HTTP $code")
    class Unreadable : PageFetchException("unreadable")
}

/** Источник страниц. В приложении — сеть, в тестах — заготовки. */
fun interface PageFetching {
    suspend fun fetch(url: String): FetchedPage
}

/**
 * Скачивает найденную страницу. Читает не больше 1,5 МБ, только HTML и текст, без cookie. Адреса домашней сети
 * не открывает: ссылки приходят из выдачи поиска, и через неё нельзя добраться до роутера или NAS.
 */
class PageFetcher(private val maxBytes: Int = 1_500_000, private val timeoutMillis: Int = 8_000) : PageFetching {
    override suspend fun fetch(url: String): FetchedPage = withContext(Dispatchers.IO) {
        var current = url
        var redirects = 0
        while (true) {
            // Каждый адрес, в том числе после переадресации, должен быть внешним: переадресацию проверяем сами.
            if (!isAllowed(current)) throw PageFetchException.BlockedAddress()
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                useCaches = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LocalChat")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399 && redirects < 6) {
                    current = URL(URL(current), connection.getHeaderField("Location") ?: throw PageFetchException.Unreadable()).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) throw PageFetchException.Http(code)
                val type = (connection.contentType ?: "text/html").lowercase()
                if (!type.contains("html") && !type.contains("text/plain")) throw PageFetchException.NotHtml(type)
                val data = connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (out.size() < maxBytes) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, minOf(count, maxBytes - out.size()))
                    }
                    out.toByteArray()
                }
                val html = decode(data, type) ?: throw PageFetchException.Unreadable()
                return@withContext FetchedPage(current, html)
            } finally {
                connection.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw PageFetchException.Unreadable()
    }

    companion object {
        /** Кодировка из заголовка, иначе из `<meta charset>`, иначе UTF-8. Старые русские сайты бывают в windows-1251 и KOI8-R. */
        fun decode(data: ByteArray, contentType: String): String? {
            val head = String(data, 0, minOf(4096, data.size), Charsets.UTF_8).lowercase()
            val declared = charset(contentType) ?: charset(head)
            if (declared != null) {
                runCatching { Charset.forName(declared) }.getOrNull()?.let { return String(data, it) }
            }
            val decoder = Charsets.UTF_8.newDecoder()
            return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(data)).toString() }.getOrNull()
                // Обрезка на 1,5 МБ могла разрезать последний символ: отбрасываем хвост.
                ?: runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data, 0, maxOf(0, data.size - 3))).toString() }.getOrNull()
                ?: String(data, Charset.forName("windows-1251"))
        }

        private fun charset(text: String): String? {
            val index = text.indexOf("charset=")
            if (index < 0) return null
            val rest = text.substring(index + 8).trimStart('"', '\'')
            val name = rest.takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            return name.ifEmpty { null }
        }

        /** Только http(s) во внешнем интернете: не localhost, не *.local и не частные, служебные или IPv6-адреса. */
        fun isAllowed(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
            if (host.isEmpty()) return false
            if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan")) return false
            if (host.contains(":")) return false
            val parts = host.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size == 4 && host.split(".").size == 4) {
                val (a, b) = parts[0] to parts[1]
                if (a == 0 || a == 10 || a == 127 || a >= 224) return false
                if (a == 169 && b == 254) return false
                if (a == 172 && b in 16..31) return false
                if (a == 192 && b == 168) return false
                if (a == 100 && b in 64..127) return false
            }
            return true
        }
    }
}
