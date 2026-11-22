package alexcattivo.localchat.kit

import alexcattivo.localchat.kit.web.ContentExtractor
import alexcattivo.localchat.kit.web.SearchResult
import alexcattivo.localchat.kit.web.WebAccess
import alexcattivo.localchat.kit.web.WebResearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search services that read pages themselves (Tavily, Exa, Firecrawl, You.com): their text is used, the page is not fetched. */
class ServiceTextTest {
    private val markdown = """
        # Xiaomi Smart Air Fryer
        The air fryer connects over Wi-Fi to the [Mi Home](https://home.mi.com) app.
        ![photo](https://example.com/fryer.jpg)

        ## Security
        Firmware updates arrive through Mi Home; the local miIO protocol on UDP 54321 needs the device token.
    """.trimIndent() + "\n\n" + "Filler sentence about cooking without oil. ".repeat(12)

    @Test
    fun markdownBecomesHeadingsAndParagraphsWithoutAddresses() {
        val page = ContentExtractor.fromText("Fryer", markdown)
        assertTrue(page.blocks.first().isHeading)
        assertEquals("Xiaomi Smart Air Fryer", page.blocks.first().text)
        assertTrue(page.blocks.any { it.isHeading && it.text == "Security" })
        val text = page.blocks.joinToString(" ") { it.text }
        assertTrue(text.contains("to the Mi Home app"))
        assertFalse(text.contains("https://"))
    }

    @Test
    fun pageTextFromTheServiceIsNotFetchedAgain() = runBlocking {
        val given = SearchResult("Fryer", "https://example.com/fryer", "short", content = markdown)
        val plain = SearchResult("Other", "https://example.com/other", "snippet")
        val fetcher = FakeFetcher(mapOf("https://example.com/other" to articleHtml))
        val research = WebResearch(FakeProvider(listOf(given, plain)), CountingFetcher(fetcher), pagesToRead = 2)
        val gathered = research.gather("xiaomi air fryer")
        assertEquals(2, gathered.pagesRead)
        assertEquals(listOf("https://example.com/other"), CountingFetcher.fetched)
        assertTrue(gathered.passages.any { it.sourceIndex == 0 && it.text.contains("UDP 54321") })
    }

    @Test
    fun eachServiceKeepsItsOwnKey() {
        val access = WebAccess(MemorySettings(), MemorySecrets())
        access.isEnabled = true
        access.provider = WebAccess.ProviderKind.Tavily
        assertFalse(access.isReady)
        access.setKey(WebAccess.ProviderKind.Tavily, "tvly-1")
        access.setKey(WebAccess.ProviderKind.Serper, "serper-2")
        assertTrue(access.isReady)
        assertEquals("Tavily", access.makeProvider().name)
        access.provider = WebAccess.ProviderKind.Serper
        assertEquals("Serper (Google)", access.makeProvider().name)
        assertEquals("tvly-1", access.key(WebAccess.ProviderKind.Tavily))
        access.provider = WebAccess.ProviderKind.Exa
        assertFalse(access.isReady)
    }
}

private class CountingFetcher(private val inner: FakeFetcher) : alexcattivo.localchat.kit.web.PageFetching {
    init {
        fetched.clear()
    }

    override suspend fun fetch(url: String): alexcattivo.localchat.kit.web.FetchedPage {
        fetched += url
        return inner.fetch(url)
    }

    companion object {
        val fetched = mutableListOf<String>()
    }
}

private class MemorySettings : SettingsStore {
    val values = mutableMapOf<String, Any>()
    override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun getString(key: String) = values[key] as? String
    override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}

private class MemorySecrets : alexcattivo.localchat.kit.web.SecretStore {
    val values = mutableMapOf<String, String>()
    override fun read(account: String): String? = values[account]
    override fun write(value: String?, account: String) { if (value == null) values.remove(account) else values[account] = value }
}
