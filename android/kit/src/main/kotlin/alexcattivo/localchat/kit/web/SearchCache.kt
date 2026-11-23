package alexcattivo.localchat.kit.web

import alexcattivo.localchat.kit.SearchLogEntry
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

/** One kept search, as the list of searches needs it. [gathered] is [SearchCache.encode]'s text. */
data class KeptSearch(val id: Long, val date: Long, val provider: String, val query: String, val terms: String, val gathered: String)

/** One search for the list of searches: that it was sent, and what came back while it is kept for reuse. */
data class PastSearch(
    val date: Long,
    val provider: String,
    val query: String,
    val succeeded: Boolean,
    /** What it found; empty once the kept copy is gone. */
    val results: List<SearchResult> = emptyList(),
    /** Until when a similar query is answered from the kept copy. */
    val keptUntil: Long? = null,
    val logId: Long? = null,
    val keptId: Long? = null,
) {
    val id: String get() = "${logId ?: -1}-${keptId ?: -1}"

    private fun keeping(row: KeptSearch) = copy(
        keptId = row.id,
        keptUntil = row.date + SearchCache.LIFETIME_MS,
        results = SearchCache.decode(row.gathered)?.results.orEmpty(),
    )

    companion object {
        /**
         * Each kept copy belongs to the newest logged search with the same words and service. One whose log record was
         * deleted still gets a row: it is on the phone and is used. Newest first.
         */
        fun joined(log: List<SearchLogEntry>, kept: List<KeptSearch>): List<PastSearch> {
            val left = kept.toMutableList()
            val searches = log.sortedByDescending { it.date }.map { entry ->
                val search = PastSearch(entry.date, entry.provider, entry.query, entry.succeeded, logId = entry.id)
                val index = if (!entry.succeeded) -1 else left.indexOfFirst { it.provider == entry.provider && it.terms == SearchCache.key(entry.query) }
                if (index < 0) search else search.keeping(left.removeAt(index))
            }
            val rest = left.map { PastSearch(it.date, it.provider, it.query, succeeded = true).keeping(it) }
            return (searches + rest).sortedByDescending { it.date }
        }
    }
}
