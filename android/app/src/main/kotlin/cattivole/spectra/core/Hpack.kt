package cattivole.spectra.core

/**
 * HPACK (RFC 7541): the header compression of HTTP/2. One decoder per
 * direction of a connection, fed every header block in order, since each block
 * can change the table the next one refers to.
 */
class HpackDecoder(private var maxSize: Int = 4096) {
    private class Entry(val field: HttpField, val size: Int)

    /** Newest first. */
    private val table = ArrayDeque<Entry>()
    private var size = 0

    /** Decodes one complete header block. Throws on malformed input: the connection can no longer be read. */
    fun decode(block: ByteArray): List<HttpField> {
        val out = ArrayList<HttpField>()
        val reader = Reader(block)
        while (reader.more()) {
            val b = reader.peek()
            when {
                b and 0x80 != 0 -> out += field(reader.int(7))
                b and 0x40 != 0 -> {
                    val (name, nameBytes) = name(reader, 6)
                    val (value, valueBytes) = reader.string()
                    val field = HttpField(name, value)
                    out += field
                    add(Entry(field, nameBytes + valueBytes + 32))
                }
                b and 0x20 != 0 -> {
                    val next = reader.int(5)
                    if (next > MAX_TABLE) throw IllegalStateException("table size $next")
                    maxSize = next
                    evict(0)
                }
                else -> {
                    val (name, _) = name(reader, 4)
                    out += HttpField(name, reader.string().first)
                }
            }
        }
        return out
    }

    private fun name(reader: Reader, prefix: Int): Pair<String, Int> {
        val index = reader.int(prefix)
        if (index == 0) return reader.string()
        val name = field(index).name
        return name to name.toByteArray(Charsets.UTF_8).size
    }

    private fun field(index: Int): HttpField = when {
        index <= 0 -> throw IllegalStateException("index 0")
        index <= STATIC.size -> STATIC[index - 1]
        index - STATIC.size - 1 < table.size -> table[index - STATIC.size - 1].field
        else -> throw IllegalStateException("index $index")
    }

    private fun add(entry: Entry) {
        evict(entry.size)
        if (entry.size > maxSize) return
        table.addFirst(entry)
        size += entry.size
    }

    private fun evict(room: Int) {
        while (table.isNotEmpty() && size + room > maxSize) size -= table.removeLast().size
    }

    private class Reader(private val data: ByteArray) {
        private var at = 0

        fun more() = at < data.size

        fun peek(): Int = data[at].toInt() and 0xFF

        private fun next(): Int {
            if (at >= data.size) throw IllegalStateException("truncated")
            return data[at++].toInt() and 0xFF
        }

        fun int(prefix: Int): Int {
            val mask = (1 shl prefix) - 1
            var value = next() and mask
            if (value < mask) return value
            var shift = 0
            while (true) {
                val b = next()
                value += (b and 0x7F) shl shift
                if (value < 0 || shift > 28) throw IllegalStateException("integer")
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }

        /** The text and its length in octets, which is what the table counts. */
        fun string(): Pair<String, Int> {
            val huffman = peek() and 0x80 != 0
            val length = int(7)
            if (length > data.size - at) throw IllegalStateException("string")
            val raw = data.copyOfRange(at, at + length)
            at += length
            val bytes = if (huffman) Huffman.decode(raw) else raw
            return String(bytes, Charsets.UTF_8) to bytes.size
        }
    }

    companion object {
        private const val MAX_TABLE = 1 shl 20

        val STATIC: List<HttpField> = listOf(
            ":authority" to "", ":method" to "GET", ":method" to "POST", ":path" to "/", ":path" to "/index.html",
            ":scheme" to "http", ":scheme" to "https", ":status" to "200", ":status" to "204", ":status" to "206",
            ":status" to "304", ":status" to "400", ":status" to "404", ":status" to "500", "accept-charset" to "",
            "accept-encoding" to "gzip, deflate", "accept-language" to "", "accept-ranges" to "", "accept" to "",
            "access-control-allow-origin" to "", "age" to "", "allow" to "", "authorization" to "", "cache-control" to "",
            "content-disposition" to "", "content-encoding" to "", "content-language" to "", "content-length" to "",
            "content-location" to "", "content-range" to "", "content-type" to "", "cookie" to "", "date" to "",
            "etag" to "", "expect" to "", "expires" to "", "from" to "", "host" to "", "if-match" to "",
            "if-modified-since" to "", "if-none-match" to "", "if-range" to "", "if-unmodified-since" to "",
            "last-modified" to "", "link" to "", "location" to "", "max-forwards" to "", "proxy-authenticate" to "",
            "proxy-authorization" to "", "range" to "", "referer" to "", "refresh" to "", "retry-after" to "",
            "server" to "", "set-cookie" to "", "strict-transport-security" to "", "transfer-encoding" to "",
            "user-agent" to "", "vary" to "", "via" to "", "www-authenticate" to "",
        ).map { HttpField(it.first, it.second) }
    }
}

/**
 * The static Huffman code of HPACK. It is canonical: codes follow from the
 * lengths alone, shortest first and by symbol within a length, so only the
 * lengths are listed (symbols 0–255, then end-of-string).
 */
object Huffman {
    private val LENGTHS = intArrayOf(
        13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28,
        6, 10, 10, 12, 13, 6, 8, 11, 10, 10, 8, 11, 8, 6, 6, 6, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 7, 8, 15, 6, 12, 10,
        13, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 7, 8, 13, 19, 13, 14, 6,
        15, 5, 6, 5, 6, 5, 6, 6, 6, 5, 7, 7, 6, 6, 6, 5, 6, 7, 6, 5, 5, 6, 7, 7, 7, 7, 7, 15, 11, 14, 13, 28,
        20, 22, 20, 20, 22, 22, 22, 23, 22, 23, 23, 23, 23, 23, 24, 23, 24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23, 24,
        22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23, 21, 21, 22, 21, 23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23,
        26, 26, 20, 19, 22, 23, 22, 25, 26, 26, 26, 27, 27, 26, 24, 25, 19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26, 28, 27, 27, 27,
        20, 24, 20, 21, 22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23, 26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27, 27, 27, 27, 26,
        30,
    )

    /** For each length: the first code, and where its symbols start in [SYMBOLS]. */
    private val FIRST = LongArray(31)
    private val COUNT = IntArray(31)
    private val START = IntArray(31)
    private val SYMBOLS: IntArray

    init {
        SYMBOLS = (0..256).sortedWith(compareBy({ LENGTHS[it] }, { it })).toIntArray()
        for (length in LENGTHS) COUNT[length]++
        var code = 0L
        var index = 0
        for (length in 1..30) {
            FIRST[length] = code
            START[length] = index
            code = (code + COUNT[length]) shl 1
            index += COUNT[length]
        }
    }

    fun decode(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size * 8 / 5 + 1)
        var code = 0L
        var length = 0
        for (byte in data) {
            for (bit in 7 downTo 0) {
                code = (code shl 1) or ((byte.toInt() shr bit) and 1).toLong()
                length++
                val offset = code - FIRST[length]
                if (offset in 0 until COUNT[length]) {
                    val symbol = SYMBOLS[START[length] + offset.toInt()]
                    if (symbol == 256) throw IllegalStateException("EOS in string")
                    out.write(symbol)
                    code = 0
                    length = 0
                } else if (length >= 30) {
                    throw IllegalStateException("bad code")
                }
            }
        }
        // What is left must be padding: fewer than eight bits, all ones.
        if (length > 7 || code != (1L shl length) - 1) throw IllegalStateException("bad padding")
        return out.toByteArray()
    }
}
