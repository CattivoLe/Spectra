package alexcattivo.localchat.kit.web

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Одна найденная страница. */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    /** The page's text when the service already read it (Tavily, Exa, Firecrawl, You.com): then it is not fetched again. */
    val content: String? = null,
) {
    val host: String get() = runCatching { URI(url).host ?: "" }.getOrDefault("")
}

/** One picture found by an image search: a small preview to show, the full image and the page it is on. */
data class ImageResult(val title: String, val thumbnail: String, val image: String, val page: String) {
    val host: String get() = runCatching { URI(page).host ?: "" }.getOrDefault("")
}

/** Что нашлось по запросу. Хранится вместе с текстом сводки: её источники и картинки видны и в сохранённой. */
data class SearchPayload(
    val query: String,
    val provider: String,
    val date: Long = System.currentTimeMillis(),
    val results: List<SearchResult>,
    /** Куски страниц, отобранные под вопрос. `null` — без чтения страниц. */
    val evidence: List<Passage>? = null,
    /** Сколько страниц удалось прочитать целиком. */
    val pagesRead: Int? = null,
    /** Запросы повторного захода: были в чате, остаются для прочтения старых записей. */
    val extraQueries: List<String>? = null,
    /** Pictures of the device a card is about. `null` when none were looked for. */
    val images: List<ImageResult>? = null,
) {
    /** Все запросы этого поиска по порядку. */
    val allQueries: List<String> get() = listOf(query) + extraQueries.orEmpty()

    /** Источники, из которых что-то попало к модели: их показывают под текстом. */
    val usedSourceIndices: List<Int> get() = evidence?.map { it.sourceIndex }?.toSortedSet()?.toList() ?: results.indices.toList()

    fun toJson(): String = JSONObject().apply {
        put("query", query)
        put("provider", provider)
        put("date", date)
        put("results", JSONArray(results.map { JSONObject().put("title", it.title).put("url", it.url).put("snippet", it.snippet) }))
        evidence?.let { list ->
            put("evidence", JSONArray(list.map {
                JSONObject().put("sourceIndex", it.sourceIndex).put("heading", it.heading ?: JSONObject.NULL)
                    .put("text", it.text).put("position", it.position)
            }))
        }
        pagesRead?.let { put("pagesRead", it) }
        extraQueries?.let { put("extraQueries", JSONArray(it)) }
        images?.let { list ->
            put("images", JSONArray(list.map {
                JSONObject().put("title", it.title).put("thumbnail", it.thumbnail).put("image", it.image).put("page", it.page)
            }))
        }
    }.toString()

    companion object {
        fun fromJson(json: String): SearchPayload? = runCatching {
            val o = JSONObject(json)
            val results = o.getJSONArray("results").let { array ->
                (0 until array.length()).map { array.getJSONObject(it) }.map { SearchResult(it.getString("title"), it.getString("url"), it.optString("snippet")) }
            }
            val evidence = o.optJSONArray("evidence")?.let { array ->
                (0 until array.length()).map { array.getJSONObject(it) }.map {
                    Passage(it.getInt("sourceIndex"), if (it.isNull("heading")) null else it.getString("heading"), it.getString("text"), it.getInt("position"))
                }
            }
            SearchPayload(
                query = o.getString("query"),
                provider = o.getString("provider"),
                date = o.optLong("date", System.currentTimeMillis()),
                results = results,
                evidence = evidence,
                pagesRead = if (o.has("pagesRead")) o.getInt("pagesRead") else null,
                extraQueries = o.optJSONArray("extraQueries")?.let { array -> (0 until array.length()).map { array.getString(it) } },
                images = o.optJSONArray("images")?.let { array ->
                    (0 until array.length()).map { array.getJSONObject(it) }.map {
                        ImageResult(it.optString("title"), it.getString("thumbnail"), it.optString("image"), it.optString("page"))
                    }
                },
            )
        }.getOrNull()
    }
}

/** Ошибки поиска. Текст для человека составляет приложение. */
sealed class WebSearchException(message: String) : Exception(message) {
    class NotConfigured(val what: NotConfiguredReason) : WebSearchException("not configured: $what")
    class InvalidAddress : WebSearchException("invalid address")
    class Offline : WebSearchException("offline")
    class Http(val code: Int) : WebSearchException("HTTP $code")
    class BadResponse : WebSearchException("bad response")
}

enum class NotConfiguredReason { SearxngAddress, BraveKey, ApiKey }

/** Источник результатов. Поиск получает только текст запроса: ни диалога, ни профиля, ни сведений об устройстве. */
interface SearchProvider {
    val name: String
    suspend fun search(query: String, limit: Int): List<SearchResult>
    /** Pictures for [query]. Only the query goes out, as with [search]. */
    suspend fun searchImages(query: String, limit: Int): List<ImageResult> = emptyList()
}

/** Сетевой клиент поиска: без cookie и кэша, с короткими тайм-аутами и нейтральным User-Agent. */
object WebClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                useCaches = false
                setRequestProperty("User-Agent", "LocalChat")
                setRequestProperty("Accept", "application/json")
                for ((name, value) in headers) setRequestProperty(name, value)
            }
        } catch (_: IllegalArgumentException) {
            throw WebSearchException.InvalidAddress()
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw WebSearchException.Http(code)
            connection.inputStream.use { it.readBytes().decodeToString() }
        } catch (error: UnknownHostException) {
            throw WebSearchException.Offline()
        } catch (error: SocketTimeoutException) {
            throw WebSearchException.Offline()
        } catch (error: java.net.ConnectException) {
            throw WebSearchException.Offline()
        } finally {
            connection.disconnect()
        }
    }

    /** A JSON POST, for the search APIs that take their query as a body. */
    suspend fun post(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                // Services that read the pages themselves answer slower than a plain search.
                readTimeout = 40_000
                useCaches = false
                setRequestProperty("User-Agent", "LocalChat")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                for ((name, value) in headers) setRequestProperty(name, value)
            }
        } catch (_: IllegalArgumentException) {
            throw WebSearchException.InvalidAddress()
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) throw WebSearchException.Http(code)
            connection.inputStream.use { it.readBytes().decodeToString() }
        } catch (error: UnknownHostException) {
            throw WebSearchException.Offline()
        } catch (error: SocketTimeoutException) {
            throw WebSearchException.Offline()
        } catch (error: java.net.ConnectException) {
            throw WebSearchException.Offline()
        } finally {
            connection.disconnect()
        }
    }

    /** Убирает разметку и спецсимволы из фрагментов, которые отдают поисковики. */
    fun plainText(html: String): String {
        var text = html.replace(Regex("<[^>]+>"), "")
        for ((entity, character) in listOf("&quot;" to "\"", "&#39;" to "'", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ")) {
            text = text.replace(entity, character)
        }
        return text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** An http(s) address as SearXNG gives it: absolute, protocol-relative («//host/…») or relative to the server. */
    fun absolute(link: String, base: String): String? {
        val trimmed = link.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("data:")) return null
        val resolved = runCatching { URI(base.trimEnd('/') + "/").resolve(trimmed).toString() }.getOrNull() ?: return null
        return resolved.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }
}

/** Свой или чужой сервер SearXNG: метапоиск без слежки. На сервере должен быть включён формат JSON. */
class SearXNGProvider(private val base: String, private val language: String = "ru") : SearchProvider {
    override val name: String get() = "SearXNG (${runCatching { URI(base).host }.getOrNull() ?: "?"})"

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        val url = base.trimEnd('/') + "/search?q=" + WebClient.encode(query) + "&format=json&language=" + WebClient.encode(language)
        val body = WebClient.get(url)
        val results = runCatching { JSONObject(body).getJSONArray("results") }.getOrElse { throw WebSearchException.BadResponse() }
        return (0 until results.length()).map { results.getJSONObject(it) }
            // Некоторые источники отдают ссылки без сервера («http:///go/…»): такие не открыть и не прочитать.
            .filter { runCatching { !URI(it.getString("url")).host.isNullOrEmpty() }.getOrDefault(false) }
            .take(limit)
            .map {
                val link = it.getString("url")
                SearchResult(
                    WebClient.plainText(it.optString("title").ifEmpty { URI(link).host }),
                    link,
                    WebClient.plainText(it.optString("content")),
                )
            }
    }

    override suspend fun searchImages(query: String, limit: Int): List<ImageResult> {
        val url = base.trimEnd('/') + "/search?q=" + WebClient.encode(query) + "&format=json&categories=images&safesearch=1&language=" +
            WebClient.encode(language)
        val body = WebClient.get(url)
        val results = runCatching { JSONObject(body).getJSONArray("results") }.getOrElse { throw WebSearchException.BadResponse() }
        return (0 until results.length()).map { results.getJSONObject(it) }.mapNotNull {
            val image = WebClient.absolute(it.optString("img_src"), base) ?: return@mapNotNull null
            val thumbnail = WebClient.absolute(it.optString("thumbnail_src"), base) ?: image
            ImageResult(WebClient.plainText(it.optString("title")), thumbnail, image, it.optString("url").ifEmpty { image })
        }.distinctBy { it.image }.take(limit)
    }
}

/** Brave Search API: настоящий веб-поиск, нужен собственный ключ (есть бесплатный тариф). */
class BraveProvider(private val apiKey: String) : SearchProvider {
    override val name = "Brave Search"

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        if (apiKey.isEmpty()) throw WebSearchException.NotConfigured(NotConfiguredReason.BraveKey)
        val url = "https://api.search.brave.com/res/v1/web/search?q=" + WebClient.encode(query) + "&count=$limit"
        val body = WebClient.get(url, mapOf("X-Subscription-Token" to apiKey))
        val web = runCatching { JSONObject(body).optJSONObject("web") }.getOrElse { throw WebSearchException.BadResponse() }
        val results = web?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { results.getJSONObject(it) }.take(limit).map {
            SearchResult(WebClient.plainText(it.getString("title")), it.getString("url"), WebClient.plainText(it.optString("description")))
        }
    }

    override suspend fun searchImages(query: String, limit: Int): List<ImageResult> {
        if (apiKey.isEmpty()) throw WebSearchException.NotConfigured(NotConfiguredReason.BraveKey)
        val url = "https://api.search.brave.com/res/v1/images/search?q=" + WebClient.encode(query) + "&count=${limit * 2}&safesearch=strict"
        val body = WebClient.get(url, mapOf("X-Subscription-Token" to apiKey))
        val results = runCatching { JSONObject(body).getJSONArray("results") }.getOrElse { throw WebSearchException.BadResponse() }
        return (0 until results.length()).map { results.getJSONObject(it) }.mapNotNull {
            // Brave serves the preview from its own proxy: the picture's site sees nothing until it is opened.
            val thumbnail = it.optJSONObject("thumbnail")?.optString("src")?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val image = it.optJSONObject("properties")?.optString("url")?.takeIf(String::isNotEmpty) ?: thumbnail
            ImageResult(WebClient.plainText(it.optString("title")), thumbnail, image, it.optString("url").ifEmpty { image })
        }.take(limit)
    }
}
