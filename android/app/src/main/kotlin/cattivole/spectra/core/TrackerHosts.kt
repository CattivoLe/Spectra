package cattivole.spectra.core

/**
 * Servers of known analytics, advertising and crash-reporting services, from the bundled `trackers.tsv` (shared with
 * iOS). A name matches itself and everything under it. Mirrors the iOS `TrackerHosts`.
 */
object TrackerHosts {
    enum class Kind { ANALYTICS, ADS, ATTRIBUTION, CRASH, PUSH }

    data class Service(val name: String, val kind: Kind)

    fun serviceOf(host: String): Service? {
        var name = host.lowercase().substringBefore(':')
        while ('.' in name) {
            table[name]?.let { return it }
            name = name.substringAfter('.')
        }
        return null
    }

    private val table: Map<String, Service> by lazy {
        val stream = TrackerHosts::class.java.classLoader?.getResourceAsStream("spectra/trackers.tsv") ?: return@lazy emptyMap()
        stream.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val fields = line.split('\t')
                val kind = fields.getOrNull(2)?.let { text -> Kind.entries.firstOrNull { it.name.equals(text.trim(), true) } }
                if (fields.size == 3 && kind != null) fields[0] to Service(fields[1], kind) else null
            }.toMap()
        }
    }
}
