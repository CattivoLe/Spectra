package cattivole.spectra.core

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP bodies of one recording, one file per body: `<flow>-<exchange>-req.bin`
 * and `-res.bin`. The live recording writes into its own folder; saving copies
 * that folder next to the saved session, as `<session>.bodies`.
 */
class TrafficBodies(val dir: File) {
    private val used = AtomicLong(-1)

    fun file(flowId: Long, index: Int, request: Boolean): File =
        File(dir, "$flowId-$index-${if (request) "req" else "res"}.bin")

    fun read(flowId: Long, index: Int, request: Boolean): ByteArray? =
        file(flowId, index, request).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    /**
     * Claims room for [size] bytes in this folder's budget. False once a
     * recording has kept [BUDGET] bytes of bodies: later ones are not written.
     */
    fun reserve(size: Int): Boolean {
        used.compareAndSet(-1, folderBytes())
        while (true) {
            val now = used.get()
            if (now + size > BUDGET) return false
            if (used.compareAndSet(now, now + size)) return true
        }
    }

    fun write(flowId: Long, index: Int, request: Boolean, body: ByteArray) {
        runCatching {
            dir.mkdirs()
            file(flowId, index, request).writeBytes(body)
        }
    }

    /**
     * Replaces [target] with a copy of this folder. Files are hard-linked where
     * the disk allows, so saving a large recording is instant; a body file is
     * never changed once written, so the two folders cannot drift apart.
     */
    fun copyTo(target: File) {
        target.deleteRecursively()
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        target.mkdirs()
        for (file in files) {
            val copy = File(target, file.name)
            val linked = runCatching { java.nio.file.Files.createLink(copy.toPath(), file.toPath()) }.isSuccess
            if (!linked) runCatching { file.copyTo(copy, overwrite = true) }
        }
    }

    fun clear() {
        dir.deleteRecursively()
        used.set(0)
    }

    private fun folderBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    companion object {
        /** The most body bytes one recording keeps. */
        const val BUDGET = 64L * 1024 * 1024
    }
}

/** How a body is best shown. */
sealed class BodyView {
    data object Empty : BodyView()
    class Image(val bytes: ByteArray) : BodyView()
    class Json(val text: String) : BodyView()
    class Text(val text: String) : BodyView()
    class Binary(val size: Int, val hex: String) : BodyView()
}

object BodyPreview {
    /** Reads [body] as an image, JSON, text or bytes, going by [contentType] and then by the bytes themselves. */
    fun of(body: ByteArray, contentType: String?): BodyView {
        if (body.isEmpty()) return BodyView.Empty
        val type = contentType?.lowercase().orEmpty()
        if (isImage(body, type)) return BodyView.Image(body)
        val looksJson = "json" in type || type.isEmpty() || "javascript" in type || "text/plain" in type
        if (looksJson) prettyJson(body)?.let { return BodyView.Json(it.first) }
        readableText(body)?.let { return BodyView.Text(it) }
        return BodyView.Binary(body.size, hexDump(body, HEX_LIMIT))
    }

    /** True for raster images a phone can draw. SVG is text and is shown as text. */
    fun isImage(body: ByteArray, contentType: String?): Boolean {
        val type = contentType?.lowercase().orEmpty()
        if (type.startsWith("image/") && "svg" !in type) return true
        return imageMagic(body)
    }

    /** `00000000  48 65 6c 6c 6f  |Hello|`, sixteen bytes a line, at most [limit] bytes. */
    fun hexDump(body: ByteArray, limit: Int = HEX_LIMIT): String = buildString {
        val end = minOf(body.size, limit)
        var offset = 0
        while (offset < end) {
            val line = minOf(16, end - offset)
            append("%08x  ".format(offset))
            for (i in 0 until 16) {
                if (i < line) append("%02x ".format(body[offset + i].toInt() and 0xFF)) else append("   ")
                if (i == 7) append(' ')
            }
            append(" |")
            for (i in 0 until line) {
                val c = body[offset + i].toInt() and 0xFF
                append(if (c in 32..126) c.toChar() else '.')
            }
            append('|')
            offset += line
            if (offset < end) append('\n')
        }
    }

    private fun imageMagic(b: ByteArray): Boolean {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return true
        if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return true
        if (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46) return true
        if (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 && at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50) return true
        if (at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70) {
            val brand = String(b, 8, minOf(4, b.size - 8).coerceAtLeast(0), Charsets.US_ASCII)
            return brand.startsWith("avif") || brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("mif1")
        }
        return false
    }

    const val HEX_LIMIT = 4096
}
