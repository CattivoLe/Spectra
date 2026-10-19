package cattivole.spectra.core

/** A named tally, e.g. a protocol or application and how many packets carried it. */
class NamedCount(val name: String, val count: Int)

/** One network endpoint and how much it sent or received. */
class Talker(val endpoint: String, val packets: Int, val bytes: Int)

/** A pair of endpoints that exchanged packets. */
class Conversation(val a: String, val b: String, val packets: Int, val bytes: Int)

/** One row for the packet list. [timestamp] is seconds since the epoch. */
class PacketRow(
    val index: Int,
    val timestamp: Double?,
    val length: Int,
    val transport: String,
    val source: String,
    val destination: String,
    val info: String,
)

class CaptureSummary(
    val packetCount: Int,
    val capturedBytes: Long,
    val wireBytes: Long,
    val start: Double?,
    val end: Double?,
    val linkTypes: List<String>,
    val protocols: List<NamedCount>,
    val apps: List<NamedCount>,
    val talkers: List<Talker>,
    val conversations: List<Conversation>,
) {
    /** Capture span in seconds, or null when timestamps are absent. */
    val duration: Double?
        get() = if (start != null && end != null && end >= start) end - start else null
}

class CaptureAnalysis(
    val format: String,
    val summary: CaptureSummary,
    val rows: List<PacketRow>,
    /** True when there were more packets than the row cap, so the list is partial. */
    val rowsTruncated: Boolean,
)

/**
 * Turns raw file bytes into a summary and a (capped) packet list. Pure, so it
 * runs off the main thread from the UI. Mirrors the iOS `PcapAnalyzer`.
 */
object PcapAnalyzer {
    fun analyze(data: ByteArray, maxRows: Int = 2000): CaptureAnalysis {
        val file = PcapParser.parse(data)

        val protocolCounts = HashMap<Transport, Int>()
        val appCounts = HashMap<String, Int>()
        val talkerPackets = HashMap<String, Int>()
        val talkerBytes = HashMap<String, Int>()
        val conversationPackets = HashMap<String, Int>()
        val conversationBytes = HashMap<String, Int>()
        val conversationPair = HashMap<String, Pair<String, String>>()
        var start: Double? = null
        var end: Double? = null
        var wireBytes = 0L
        var capturedBytes = 0L
        val rows = ArrayList<PacketRow>()

        file.packets.forEachIndexed { index, packet ->
            val info = PacketDissector.dissect(packet.linkType, packet.data)
            protocolCounts[info.transport] = (protocolCounts[info.transport] ?: 0) + 1
            info.app?.let { appCounts[it] = (appCounts[it] ?: 0) + 1 }
            wireBytes += packet.originalLength
            capturedBytes += packet.data.size

            packet.timestamp?.let { time ->
                if (start == null || time < start!!) start = time
                if (end == null || time > end!!) end = time
            }

            val size = packet.originalLength
            info.netSource?.let {
                talkerPackets[it] = (talkerPackets[it] ?: 0) + 1
                talkerBytes[it] = (talkerBytes[it] ?: 0) + size
            }
            info.netDestination?.let {
                talkerPackets[it] = (talkerPackets[it] ?: 0) + 1
                talkerBytes[it] = (talkerBytes[it] ?: 0) + size
            }
            val source = info.netSource
            val destination = info.netDestination
            if (source != null && destination != null && source != destination) {
                val ends = listOf(source, destination).sorted()
                val key = ends.joinToString("|")
                conversationPackets[key] = (conversationPackets[key] ?: 0) + 1
                conversationBytes[key] = (conversationBytes[key] ?: 0) + size
                conversationPair.getOrPut(key) { ends[0] to ends[1] }
            }

            if (rows.size < maxRows) {
                rows.add(
                    PacketRow(
                        index, packet.timestamp, size, info.transport.label,
                        info.source, info.destination, info.info,
                    ),
                )
            }
        }

        val protocols = protocolCounts.entries
            .map { NamedCount(it.key.label, it.value) }
            .sortedByDescending { it.count }
        val apps = appCounts.entries
            .map { NamedCount(it.key, it.value) }
            .sortedByDescending { it.count }
        val talkers = talkerPackets.keys
            .map { Talker(it, talkerPackets[it] ?: 0, talkerBytes[it] ?: 0) }
            .sortedByDescending { it.bytes }
            .take(12)
        val conversations = conversationPair.entries
            .map { (key, pair) ->
                Conversation(pair.first, pair.second, conversationPackets[key] ?: 0, conversationBytes[key] ?: 0)
            }
            .sortedByDescending { it.bytes }
            .take(12)

        val summary = CaptureSummary(
            packetCount = file.packets.size,
            capturedBytes = capturedBytes,
            wireBytes = wireBytes,
            start = start,
            end = end,
            linkTypes = file.packets.map { it.linkType.displayName }.distinct().sorted(),
            protocols = protocols,
            apps = apps,
            talkers = talkers,
            conversations = conversations,
        )
        return CaptureAnalysis(file.format.label, summary, rows, file.packets.size > maxRows)
    }
}
