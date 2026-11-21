package cattivole.spectra

import cattivole.spectra.core.KeyGuard
import cattivole.spectra.core.LocalCa
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the local certificate authority keeps its keys on disk. */
class LocalCaStorageTest {
    /** Stands in for the Keystore: each guard opens only what it sealed itself. */
    private class TestGuard(private val id: Byte) : KeyGuard {
        override fun seal(plain: ByteArray) = byteArrayOf(id) + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun open(sealed: ByteArray): ByteArray {
            check(sealed[0] == id) { "sealed by another guard" }
            return sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private fun tempDir(name: String): File = Files.createTempDirectory(name).toFile()

    private fun names(dir: File): Set<String> = dir.list().orEmpty().toSet()

    @Test
    fun keysAreSealedOnDisk() {
        val dir = tempDir("ca-sealed")
        val guard = TestGuard(1)
        val ca = LocalCa.open(dir, guard)
        assertFalse("no key in the clear", "ca.pk8" in names(dir) || "leaf.pk8" in names(dir))
        val sealed = File(dir, "ca.key").readBytes()
        assertTrue(sealed[0] == 1.toByte())

        LocalCa.forget(dir)
        val again = LocalCa.open(dir, guard)
        assertArrayEquals(ca.certificate.encoded, again.certificate.encoded)
        again.leaf("example.com").verify(again.certificate.publicKey)
    }

    @Test
    fun clearKeysFromEarlierVersionsAreSealedAndKept() {
        val dir = tempDir("ca-legacy")
        val original = LocalCa.open(dir, LocalCa.PLAIN)
        // Lay the directory out as earlier versions did: PKCS#8 keys in the clear.
        File(dir, "ca.key").renameTo(File(dir, "ca.pk8"))
        File(dir, "leaf.key").renameTo(File(dir, "leaf.pk8"))
        LocalCa.forget(dir)

        val migrated = LocalCa.open(dir, TestGuard(2))
        assertArrayEquals("the certificate the phone trusts survives", original.certificate.encoded, migrated.certificate.encoded)
        assertFalse("ca.pk8" in names(dir))
        assertFalse("leaf.pk8" in names(dir))
        assertTrue(File(dir, "ca.key").readBytes()[0] == 2.toByte())

        LocalCa.forget(dir)
        assertArrayEquals(original.certificate.encoded, LocalCa.open(dir, TestGuard(2)).certificate.encoded)
    }

    @Test
    fun keysSealedElsewhereAreReplaced() {
        val dir = tempDir("ca-foreign")
        val foreign = LocalCa.open(dir, TestGuard(3))
        LocalCa.forget(dir)

        // As after a restore on another phone: the files are there, the sealing key is not.
        val fresh = LocalCa.open(dir, TestGuard(4))
        assertFalse(foreign.certificate.encoded.contentEquals(fresh.certificate.encoded))
        fresh.leaf("example.com").verify(fresh.certificate.publicKey)
        assertTrue(File(dir, "ca.key").readBytes()[0] == 4.toByte())
    }

    @Test
    fun relocateMovesTheAuthorityOutOfBackedUpStorage() {
        val old = tempDir("ca-old")
        val new = File(tempDir("ca-root"), "traffic-ca")
        val original = LocalCa.open(old, LocalCa.PLAIN)
        File(old, "ca.key").renameTo(File(old, "ca.pk8"))
        File(old, "leaf.key").renameTo(File(old, "leaf.pk8"))
        LocalCa.forget(old)

        LocalCa.relocate(old, new)
        assertFalse(old.exists())
        val moved = LocalCa.open(new, TestGuard(5))
        assertArrayEquals(original.certificate.encoded, moved.certificate.encoded)
        assertFalse("ca.pk8" in names(new))

        // Nothing left to move: a second call changes nothing.
        LocalCa.relocate(old, new)
        LocalCa.forget(new)
        assertArrayEquals(original.certificate.encoded, LocalCa.open(new, TestGuard(5)).certificate.encoded)
    }

    @Test
    fun relocateKeepsAnAuthorityAlreadyInPlace() {
        val old = tempDir("ca-stale")
        val new = tempDir("ca-current")
        LocalCa.open(old, LocalCa.PLAIN)
        val current = LocalCa.open(new, TestGuard(6))
        LocalCa.forget(old)
        LocalCa.forget(new)

        LocalCa.relocate(old, new)
        assertFalse(old.exists())
        assertArrayEquals(current.certificate.encoded, LocalCa.open(new, TestGuard(6)).certificate.encoded)
    }
}
