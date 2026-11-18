package alexcattivo.localchat.kit

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
        assertEquals(1, store.searchCacheCount())
        assertNotNull(store.cachedSearch("vulnerability WPS", "SearXNG", now = 2_000L))
        assertNull(store.cachedSearch("vulnerability WPS", "Brave Search", now = 2_000L))
        assertNull(store.cachedSearch("vulnerability WPS", "SearXNG", now = 1_000L + SearchCache.LIFETIME_MS + 1))
        store.clearSearchCache()
        assertEquals(0, store.searchCacheCount())
    }
}
