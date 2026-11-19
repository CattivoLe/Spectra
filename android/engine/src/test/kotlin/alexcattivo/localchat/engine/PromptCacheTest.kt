package alexcattivo.localchat.engine

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheTest {
    private val prefix = IntArray(100) { it + 1 }
    private val key = PromptCache.Key("Ministral-3-3B-Instruct-2512-Q4_K_M.gguf", 2_147_023_008, 8192, "cpu", prefix)

    @Test
    fun theSameInputsNameTheSameFile() {
        assertEquals(key.fileName, key.copy(prefix = prefix.copyOf()).fileName)
        assertTrue(key.fileName.matches(Regex("system-[0-9a-f]{32}\\.kvstate")))
    }

    @Test
    fun anyChangeMissesTheOldFile() {
        val changed = listOf(
            key.copy(model = "LFM3-1.7B-Q4_K_M.gguf"),
            key.copy(modelBytes = key.modelBytes + 1),
            key.copy(contextLength = 4096),
            key.copy(backend = "gpu"),
            key.copy(prefix = prefix.copyOf().also { it[50] = 7 }),
            key.copy(prefix = prefix.copyOf(99)),
        )
        for (other in changed) assertNotEquals(other.toString(), key.fileName, other.fileName)
        assertEquals(changed.size, changed.map { it.fileName }.toSet().size)
    }

    @Test
    fun commitReplacesTheFileAndDeletesStaleOnes() {
        val directory = Files.createTempDirectory("prompt-cache").toFile()
        val cache = PromptCache(directory)
        val stale = directory.resolve("system-00000000000000000000000000000000.kvstate").apply { writeText("old prompt") }
        val leftover = directory.resolve("system-11111111111111111111111111111111.kvstate.partial").apply { writeText("cut short") }
        cache.temporaryFile(key).writeText("state")

        assertTrue(cache.commit(key))
        assertEquals("state", cache.file(key).readText())
        assertFalse(cache.temporaryFile(key).exists())
        assertFalse(stale.exists())
        assertFalse(leftover.exists())
        assertEquals(listOf(key.fileName), directory.list()!!.toList())

        cache.temporaryFile(key).writeText("newer")
        assertTrue(cache.commit(key))
        assertEquals("newer", cache.file(key).readText())

        cache.discard(key)
        assertEquals(0, directory.list()!!.size)
        directory.deleteRecursively()
    }

    @Test
    fun commitWithoutAWrittenStateFails() {
        val directory = Files.createTempDirectory("prompt-cache").toFile()
        val cache = PromptCache(directory)
        assertFalse(cache.commit(key))
        assertFalse(cache.file(key).exists())
        directory.deleteRecursively()
    }

    @Test
    fun theSystemPartEndsWhereTwoQuestionsDiffer() {
        val a = prefix + intArrayOf(500, 501)
        val b = prefix + intArrayOf(600, 501)
        assertEquals(prefix.size, PromptCache.sharedPrefixLength(a, b))
        assertEquals(0, PromptCache.sharedPrefixLength(intArrayOf(), a))
        assertEquals(2, PromptCache.sharedPrefixLength(intArrayOf(1, 2), intArrayOf(1, 2)))
    }

    @Test
    fun onlyAPrefixOfThePromptIsUsed() {
        val prompt = prefix + intArrayOf(500, 501, 502)
        assertArrayEquals(prefix, PromptCache.usablePrefix(prefix, prompt))
        // Too short to be worth a file.
        assertNull(PromptCache.usablePrefix(prefix.copyOf(PromptCache.MINIMUM_TOKENS - 1), prompt))
        // The whole prompt: nothing would be left to compute the next token from.
        assertNull(PromptCache.usablePrefix(prefix, prefix))
        // The real question tokenized the boundary differently.
        assertNull(PromptCache.usablePrefix(prefix, prompt.copyOf().also { it[prefix.size - 1] = 9999 }))
    }
}
