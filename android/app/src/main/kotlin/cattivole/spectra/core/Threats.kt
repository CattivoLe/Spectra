package cattivole.spectra.core

/**
 * Live security findings from the latest Wi-Fi scan: a deauth attack, a
 * possible evil twin (one network name on several access points), or a device
 * generating steady traffic (a camera or microphone). Pure and UI-free so the
 * rules can be checked by tests; the screen formats the text.
 */
object Threats {
    /** Bytes for one AP during the ~3 s sniff window that look like a stream. */
    const val STREAMING_BYTE_THRESHOLD = 120_000L

    enum class Severity { WARNING, CRITICAL }

    enum class Kind { DEAUTH_ATTACK, EVIL_TWIN, STREAMING }

    data class Finding(
        val id: String,
        val kind: Kind,
        val severity: Severity,
        val ssid: String? = null,
        val apCount: Int = 0,
        val differByAuth: Boolean = false,
        val frames: Int = 0,
        val channel: Int = 0,
        val broadcast: Boolean = false,
        val targetBssid: String? = null,
        val name: String? = null,
    )

    fun assess(networks: List<TrackedNetwork>, deauths: List<DeauthEvent>): List<Finding> {
        val findings = mutableListOf<Finding>()
        findings += deauthFindings(deauths)
        findings += evilTwinFindings(networks)
        findings += streamingFindings(networks)
        return findings.sortedByDescending { it.severity }
    }

    private fun deauthFindings(deauths: List<DeauthEvent>): List<Finding> =
        deauths.filter { it.severity >= DeauthEvent.Severity.WARNING }.map { event ->
            Finding(
                id = "deauth-${event.id}",
                kind = Kind.DEAUTH_ATTACK,
                severity = if (event.severity == DeauthEvent.Severity.CRITICAL) Severity.CRITICAL else Severity.WARNING,
                frames = event.count,
                channel = event.channel,
                broadcast = event.isBroadcast,
                targetBssid = event.bssid.text,
            )
        }

    /**
     * One SSID on several BSSIDs is normal for mesh and enterprise Wi-Fi, so
     * only flag it when the copies differ in security or in maker — the shape
     * of an impostor standing up a lookalike network.
     */
    private fun evilTwinFindings(networks: List<TrackedNetwork>): List<Finding> {
        val named = networks.mapNotNull { net ->
            val ssid = net.revealedSsid ?: net.latest.ssid
            if (ssid.isNullOrEmpty()) null else ssid to net
        }
        return named.groupBy({ it.first }, { it.second })
            .filter { it.value.size >= 2 }
            .mapNotNull { (ssid, members) ->
                val auths = members.map { it.latest.auth }.toSet()
                val ouis = members.map { it.latest.bssid.oui }.toSet()
                if (auths.size <= 1 && ouis.size <= 1) return@mapNotNull null
                Finding(
                    id = "twin-$ssid",
                    kind = Kind.EVIL_TWIN,
                    severity = if (auths.size > 1) Severity.CRITICAL else Severity.WARNING,
                    ssid = ssid,
                    apCount = members.size,
                    differByAuth = auths.size > 1,
                )
            }
    }

    private fun streamingFindings(networks: List<TrackedNetwork>): List<Finding> =
        networks.mapNotNull { net ->
            val traffic = net.traffic ?: return@mapNotNull null
            if (traffic.bytes < STREAMING_BYTE_THRESHOLD) return@mapNotNull null
            Finding(
                id = "stream-${net.latest.bssid.text}",
                kind = Kind.STREAMING,
                severity = Severity.WARNING,
                name = net.revealedSsid ?: net.latest.ssid ?: net.latest.bssid.text,
            )
        }
}
