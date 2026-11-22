package alexcattivo.localchat.kit.web

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/*
 * Search services with an API key. Each gets the query and nothing else: no conversation, no profile, nothing about
 * the device. The ones that read pages themselves (Tavily, Exa, Firecrawl, You.com) hand back the page text in
 * [SearchResult.content], so the phone does not fetch those pages again.
 */

private fun JSONObject.arrayOrEmpty(name: String): List<JSONObject> {
    val array = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

private fun parse(body: String): JSONObject = runCatching { JSONObject(body) }.getOrElse { throw WebSearchException.BadResponse() }

private fun requireKey(key: String) {
    if (key.isBlank()) throw WebSearchException.NotConfigured(NotConfiguredReason.ApiKey)
}

private fun hostOf(url: String): String = runCatching { URI(url).host ?: "" }.getOrDefault("")

/** How much of a page the services are asked for: enough for the passages the model gets, no more. */
private const val PAGE_CHARACTERS = 6000

/** Tavily: a search API made for language models; returns the page text and, when asked, pictures. */
class TavilyProvider(private val apiKey: String, private val language: String = "ru") : SearchProvider {
    override val name = "Tavily"

    private suspend fun request(query: String, limit: Int, images: Boolean): JSONObject {
        requireKey(apiKey)
        val body = JSONObject()
            .put("query", query)
            .put("max_results", limit.coerceIn(1, 20))
            .put("search_depth", "basic")
            .put("include_raw_content", if (images) false else "text")
            .put("include_images", images)
            .put("safe_search", true)
        if (language.length == 2) body.put("language", language)
        return parse(WebClient.post("https://api.tavily.com/search", body, mapOf("Authorization" to "Bearer ${apiKey.trim()}")))
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        request(query, limit, images = false).arrayOrEmpty("results").mapNotNull {
            val url = it.optString("url").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            SearchResult(
                WebClient.plainText(it.optString("title").ifEmpty { hostOf(url) }),
                url,
                WebClient.plainText(it.optString("content")),
                it.optString("raw_content").takeIf(String::isNotBlank)?.take(PAGE_CHARACTERS),
            )
        }.take(limit)

    override suspend fun searchImages(query: String, limit: Int): List<ImageResult> {
        val json = request(query, 5, images = true)
        val page = json.arrayOrEmpty("results").firstOrNull()?.optString("url").orEmpty()
        val top = json.optJSONArray("images") ?: JSONArray()
        return (0 until top.length()).mapNotNull { index ->
            // Either a bare address or {url, description}.
            val item = top.opt(index)
            val url = (item as? JSONObject)?.optString("url") ?: item as? String ?: return@mapNotNull null
            if (!url.startsWith("http")) return@mapNotNull null
            val title = (item as? JSONObject)?.optString("description").orEmpty()
            ImageResult(WebClient.plainText(title).take(120), url, url, page.ifEmpty { url })
        }.distinctBy { it.image }.take(limit)
    }
}

/** Exa: search by meaning rather than keywords; returns page text and each page's preview picture. */
class ExaProvider(private val apiKey: String, private val country: String? = null) : SearchProvider {
    override val name = "Exa"

    private suspend fun request(query: String, limit: Int, text: Boolean): List<JSONObject> {
        requireKey(apiKey)
        val contents = JSONObject()
        if (text) contents.put("text", JSONObject().put("maxCharacters", PAGE_CHARACTERS)) else contents.put("highlights", true)
        val body = JSONObject()
            .put("query", query)
            .put("numResults", limit.coerceIn(1, 25))
            .put("type", "auto")
            .put("moderation", true)
            .put("contents", contents)
        country?.let { body.put("userLocation", it) }
        return parse(WebClient.post("https://api.exa.ai/search", body, mapOf("x-api-key" to apiKey.trim()))).arrayOrEmpty("results")
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        request(query, limit, text = true).mapNotNull {
            val url = it.optString("url").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val text = it.optString("text").takeIf(String::isNotBlank)
            val snippet = it.optJSONArray("highlights")?.let { list -> (0 until list.length()).joinToString(" … ") { i -> list.optString(i) } }
                ?.takeIf(String::isNotBlank) ?: text?.take(300).orEmpty()
            SearchResult(WebClient.plainText(it.optString("title").ifEmpty { hostOf(url) }), url, WebClient.plainText(snippet), text)
        }.take(limit)

    /** The preview picture each page gives itself: a product page's is usually the product. */
    override suspend fun searchImages(query: String, limit: Int): List<ImageResult> =
        request(query, limit * 2, text = false).mapNotNull {
            val image = it.optString("image").takeIf { url -> url.startsWith("http") } ?: return@mapNotNull null
            ImageResult(WebClient.plainText(it.optString("title")), image, image, it.optString("url").ifEmpty { image })
        }.distinctBy { it.image }.take(limit)
}

/** Firecrawl: search plus each page read into clean markdown; also an image search. */
class FirecrawlProvider(private val apiKey: String, private val country: String? = null) : SearchProvider {
    override val name = "Firecrawl"

    private suspend fun request(query: String, limit: Int, source: String, scrape: Boolean): JSONObject {
        requireKey(apiKey)
        val body = JSONObject()
            .put("query", query.take(500))
            .put("limit", limit.coerceIn(1, 20))
            .put("sources", JSONArray().put(source))
            .put("safe", true)
            .put("timeout", 35_000)
        country?.let { body.put("country", it) }
        if (scrape) {
            body.put("scrapeOptions", JSONObject().put("formats", JSONArray().put("markdown")).put("onlyMainContent", true))
        }
        val json = parse(WebClient.post("https://api.firecrawl.dev/v2/search", body, mapOf("Authorization" to "Bearer ${apiKey.trim()}")))
        return json.optJSONObject("data") ?: JSONObject()
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        request(query, limit, "web", scrape = true).arrayOrEmpty("web").mapNotNull {
            val url = it.optString("url").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            SearchResult(
                WebClient.plainText(it.optString("title").ifEmpty { hostOf(url) }),
                url,
                WebClient.plainText(it.optString("description")),
                it.optString("markdown").takeIf(String::isNotBlank)?.take(PAGE_CHARACTERS),
            )
        }.take(limit)

    override suspend fun searchImages(query: String, limit: Int): List<ImageResult> =
        request(query, limit * 2, "images", scrape = false).arrayOrEmpty("images").mapNotNull {
            val image = it.optString("imageUrl").takeIf { url -> url.startsWith("http") } ?: return@mapNotNull null
            ImageResult(WebClient.plainText(it.optString("title")), image, image, it.optString("url").ifEmpty { image })
        }.distinctBy { it.image }.take(limit)
}

/** You.com: its own web index; with full-page extraction it returns each page as markdown. */
class YouProvider(private val apiKey: String, private val language: String = "ru") : SearchProvider {
    override val name = "You.com"

    private suspend fun request(query: String, limit: Int, pages: Boolean): List<JSONObject> {
        requireKey(apiKey)
        val body = JSONObject()
            .put("query", query)
            .put("count", limit.coerceIn(1, 20))
            .put("safesearch", "strict")
        if (language.length == 2) body.put("language", language.uppercase())
        if (pages) {
            body.put(
                "extraction",
                JSONObject().put("extraction_mode", "full_page")
                    .put("full_page", JSONObject().put("extraction_formats", JSONArray().put("markdown"))),
            )
        }
        val json = parse(WebClient.post("https://ydc-index.io/v1/search", body, mapOf("X-API-Key" to apiKey.trim())))
        return json.optJSONObject("results")?.arrayOrEmpty("web").orEmpty()
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        request(query, limit, pages = true).mapNotNull {
            val url = it.optString("url").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val snippets = it.optJSONArray("snippets")?.let { list -> (0 until list.length()).joinToString(" … ") { i -> list.optString(i) } }
            SearchResult(
                WebClient.plainText(it.optString("title").ifEmpty { hostOf(url) }),
                url,
                WebClient.plainText(it.optString("description").ifEmpty { snippets.orEmpty() }),
                it.optJSONObject("contents")?.optString("markdown")?.takeIf(String::isNotBlank)?.take(PAGE_CHARACTERS),
            )
        }.take(limit)

    /** You.com has no image search: each page's own preview picture stands in. */
    override suspend fun searchImages(query: String, limit: Int): List<ImageResult> =
        request(query, limit * 2, pages = false).mapNotNull {
            val image = it.optString("thumbnail_url").takeIf { url -> url.startsWith("http") } ?: return@mapNotNull null
            ImageResult(WebClient.plainText(it.optString("title")), image, image, it.optString("url").ifEmpty { image })
        }.distinctBy { it.image }.take(limit)
}

/** Serper: Google's results and Google Images through one key; cheap, with a free start. Snippets only, pages are read by the phone. */
class SerperProvider(private val apiKey: String, private val language: String = "ru") : SearchProvider {
    override val name = "Serper (Google)"

    private suspend fun request(endpoint: String, query: String, limit: Int): JSONObject {
        requireKey(apiKey)
        val body = JSONObject().put("q", query).put("num", limit.coerceIn(1, 20))
        if (language.length == 2) body.put("hl", language)
        return parse(WebClient.post("https://google.serper.dev/$endpoint", body, mapOf("X-API-KEY" to apiKey.trim())))
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        request("search", query, limit).arrayOrEmpty("organic").mapNotNull {
            val url = it.optString("link").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            SearchResult(WebClient.plainText(it.optString("title").ifEmpty { hostOf(url) }), url, WebClient.plainText(it.optString("snippet")))
        }.take(limit)

    override suspend fun searchImages(query: String, limit: Int): List<ImageResult> =
        request("images", query, limit * 2).arrayOrEmpty("images").mapNotNull {
            val image = it.optString("imageUrl").takeIf { url -> url.startsWith("http") } ?: return@mapNotNull null
            // Google's own small copy: the picture's site sees nothing until the full image is opened.
            val thumbnail = it.optString("thumbnailUrl").takeIf { url -> url.startsWith("http") } ?: image
            ImageResult(WebClient.plainText(it.optString("title")), thumbnail, image, it.optString("link").ifEmpty { image })
        }.distinctBy { it.image }.take(limit)
}
