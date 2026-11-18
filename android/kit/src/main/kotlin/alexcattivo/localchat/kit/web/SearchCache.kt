package alexcattivo.localchat.kit.web

import org.json.JSONArray
import org.json.JSONObject

/**
 * What one web search brought back (the results and the pages read), kept so that a similar query soon after is
 * answered from it instead of going online again. The assistant often asks the same thing in slightly different
 * words; a second trip costs time and tells the search service the same thing twice. Mirrors SearchCache.swift
 * in Spectra's iOS copy of the kit.
 */
object SearchCache {
    /** How long a search stays reusable. News and prices move, so not longer than a day. */
    const val LIFETIME_MS = 24L * 3600 * 1000

    /** Entries kept at most; the oldest go first. */
    const val LIMIT = 200

    /**
     * Two queries are "the same search" when their meaningful words (the same [Terms] the passage ranking uses)
     * mostly coincide: a Jaccard overlap of at least 0.7, or one query being the other plus a single extra word.
     */
    fun isSimilar(a: String, b: String): Boolean = similarity(Terms.of(a).toSet(), Terms.of(b).toSet())

    fun similarity(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val shared = a.intersect(b).size
        val overlap = shared.toDouble() / a.union(b).size
        if (overlap >= 0.7) return true
        val (small, large) = if (a.size <= b.size) a to b else b to a
        return small.size >= 2 && large.containsAll(small) && large.size - small.size == 1
    }

    fun key(query: String): String = Terms.of(query).toSet().sorted().joinToString(" ")

    fun encode(gathered: WebResearch.Gathered): String = JSONObject().apply {
        put("results", JSONArray(gathered.results.map { JSONObject().put("title", it.title).put("url", it.url).put("snippet", it.snippet) }))
        put("passages", JSONArray(gathered.passages.map {
            JSONObject().put("sourceIndex", it.sourceIndex).put("heading", it.heading ?: JSONObject.NULL)
                .put("text", it.text).put("position", it.position)
        }))
        put("pagesRead", gathered.pagesRead)
    }.toString()

    fun decode(json: String): WebResearch.Gathered? = runCatching {
        val o = JSONObject(json)
        val results = o.getJSONArray("results").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }.map { SearchResult(it.getString("title"), it.getString("url"), it.optString("snippet")) }
        }
        val passages = o.getJSONArray("passages").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }.map {
                Passage(it.getInt("sourceIndex"), if (it.isNull("heading")) null else it.getString("heading"), it.getString("text"), it.getInt("position"))
            }
        }
        WebResearch.Gathered(results, passages, o.optInt("pagesRead"))
    }.getOrNull()
}

/** A reusable earlier search. */
data class SearchCacheHit(val query: String, val date: Long, val gathered: WebResearch.Gathered)
