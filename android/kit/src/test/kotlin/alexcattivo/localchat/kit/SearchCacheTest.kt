package alexcattivo.localchat.kit

import alexcattivo.localchat.kit.web.KeptSearch
import alexcattivo.localchat.kit.web.PastSearch
import alexcattivo.localchat.kit.web.SearchCache
import alexcattivo.localchat.kit.web.SearchResult
import alexcattivo.localchat.kit.web.WebResearch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spectra's addition to the kit: a similar query within a day reuses the earlier search. */
class SearchCacheTest {
    @Test
    fun similarQueriesMatchAndDifferentOnesDoNot() {
        assertTrue(SearchCache.isSimilar("высота Эйфелевой башни", "Эйфелевой башни высота"))
        assertTrue(SearchCache.isSimilar("WPS vulnerability router", "WPS vulnerability router Keenetic"))
        assertFalse(SearchCache.isSimilar("высота Эйфелевой башни", "вес Эйфелевой башни"))
    }

    @Test
    fun aSearchIsKeptForADay() = runTest {
        val store = ChatStore(JdbcDatabase())
        val gathered = WebResearch.Gathered(listOf(SearchResult("WPS", "https://example.com/wps", "WPS is weak")), emptyList(), 0)
        store.cacheSearch("WPS vulnerability", "SearXNG", gathered, now = 1_000L)
        assertEquals(listOf("WPS vulnerability"), store.searches(now = 2_000L).map { it.query })
        assertNotNull(store.cachedSearch("vulnerability WPS", "SearXNG", now = 2_000L))
        assertNull(store.cachedSearch("vulnerability WPS", "Brave Search", now = 2_000L))
        assertNull(store.cachedSearch("vulnerability WPS", "SearXNG", now = 1_000L + SearchCache.LIFETIME_MS + 1))
        store.clearSearches()
        assertEquals(emptyList<PastSearch>(), store.searches(now = 2_000L))
    }

    @Test
    fun aSearchAndWhatItFoundAreOneRow() {
        fun kept(id: Long, query: String, date: Long) = KeptSearch(
            id, date, "Brave", query, SearchCache.key(query),
            SearchCache.encode(WebResearch.Gathered(listOf(SearchResult(query, "https://example.com", "")), emptyList(), 0)),
        )
        val now = 100_000_000L
        val log = listOf(
            SearchLogEntry(3, now, "Brave", "acme router", true),
            SearchLogEntry(2, now - 60_000, "Brave", "other thing", false),
            SearchLogEntry(1, now - 90_000_000, "Brave", "acme router", true),
        )
        val rows = PastSearch.joined(log, listOf(kept(7, "acme router", now), kept(8, "lost log row", now - 30_000)))
        assertEquals(listOf("acme router", "lost log row", "other thing", "acme router"), rows.map { it.query })
        assertEquals(listOf(1, 1, 0, 0), rows.map { it.results.size })
        assertEquals(listOf(true, true, false, true), rows.map { it.succeeded })
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
    }

    @Test
    fun deletingASearchForgetsWhatItFound() = runTest {
        val store = ChatStore(JdbcDatabase())
        val gathered = WebResearch.Gathered(listOf(SearchResult("WPS", "https://example.com/wps", "WPS is weak")), emptyList(), 0)
        store.logSearch("WPS vulnerability", "SearXNG", true)
        store.cacheSearch("WPS vulnerability", "SearXNG", gathered)
        val search = store.searches().single()
        assertEquals("WPS", search.results.single().title)
        store.deleteSearch(search)
        assertEquals(emptyList<PastSearch>(), store.searches())
        assertNull(store.cachedSearch("WPS vulnerability", "SearXNG"))
    }
}
