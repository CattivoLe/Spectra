package cattivole.spectra.core

import org.json.JSONObject

/**
 * Which servers the speed test runs against. Cloudflare is anycast: one name,
 * and the network picks the nearest edge, so there is nothing to choose.
 * Measurement Lab runs its own machines in many cities and names the nearest
 * few, so a specific one can be picked. Mirrors the iOS `SpeedServerChoice`.
 */
sealed class SpeedServerChoice {
    data object Cloudflare : SpeedServerChoice()

    /** A null machine picks the nearest one M-Lab offers at test time. */
    data class MLab(val machine: String?) : SpeedServerChoice()

    val raw: String
        get() = when (this) {
            Cloudflare -> "cloudflare"
            is MLab -> if (machine == null) "mlab" else "mlab:$machine"
        }

    companion object {
        fun fromRaw(raw: String?): SpeedServerChoice = when {
            raw == "mlab" -> MLab(null)
            raw != null && raw.startsWith("mlab:") -> MLab(raw.removePrefix("mlab:"))
            else -> Cloudflare
        }
    }
}

/** One M-Lab machine offered by its locate service, with single-use test URLs. */
data class MLabServer(
    /** Like "mlab1-beg01.mlab-oti.measurement-lab.org". */
    val machine: String,
    val city: String,
    val country: String,
    val downloadUrl: String,
    val uploadUrl: String,
) {
    /** The host the test URLs point at, for latency probes. */
    val host: String get() = runCatching { java.net.URI(downloadUrl).host }.getOrNull() ?: machine

    /** "mlab1-beg01", the part that tells machines in one city apart. */
    val shortName: String get() = machine.substringBefore('.')
}

/** The parts of Measurement Lab's ndt7 protocol that need no network. */
object MLabNdt7 {
    const val SUBPROTOCOL = "net.measurementlab.ndt.v7"
    const val LOCATE_URL = "https://locate.measurementlab.net/v2/nearest/ndt/ndt7?client_name=spectra-android"

    fun parseLocate(json: String): List<MLabServer> {
        val results = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val entry = results.optJSONObject(index) ?: return@mapNotNull null
            val machine = entry.optString("machine").ifEmpty { return@mapNotNull null }
            val urls = entry.optJSONObject("urls") ?: return@mapNotNull null
            val down = urls.optString("wss:///ndt/v7/download").ifEmpty { return@mapNotNull null }
            val up = urls.optString("wss:///ndt/v7/upload").ifEmpty { return@mapNotNull null }
            val location = entry.optJSONObject("location")
            MLabServer(
                machine = machine,
                city = location?.optString("city").orEmpty(),
                country = location?.optString("country").orEmpty(),
                downloadUrl = down,
                uploadUrl = up,
            )
        }
    }

    /** Bytes the server says it has received: TCPInfo.BytesReceived or AppInfo.NumBytes, whichever is larger. */
    fun serverReceived(text: String): Long? {
        val measurement = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val tcp = measurement.optJSONObject("TCPInfo")?.takeIf { it.has("BytesReceived") }?.optLong("BytesReceived")
        val app = measurement.optJSONObject("AppInfo")?.takeIf { it.has("NumBytes") }?.optLong("NumBytes")
        return listOfNotNull(tcp, app).maxOrNull()
    }
}
