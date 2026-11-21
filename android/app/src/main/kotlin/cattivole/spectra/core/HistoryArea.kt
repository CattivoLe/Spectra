package cattivole.spectra.core

/**
 * The tab a saved record belongs to. Each tab keeps its own history: a
 * session shows there only the records of that tab, and deleting from a tab
 * removes only those.
 *
 * A LAN session stores its devices and the connection checks made on that
 * network together. The devices are the LAN tab's, the checks the Internet
 * tab's; the file keeps them as one session.
 */
enum class HistoryArea {
    WIFI, LAN, INTERNET, BLUETOOTH;

    companion object {
        /** The tab a session's sensor lines belong to, by the session's kind. */
        fun ofKind(kind: String): HistoryArea = when (kind) {
            "lan" -> LAN
            "bluetooth" -> BLUETOOTH
            else -> WIFI
        }
    }
}

/** True when this session holds anything shown on [area]'s tab. */
fun HistoryEntry.holds(area: HistoryArea): Boolean = when (area) {
    HistoryArea.WIFI -> networks.isNotEmpty() || probes.isNotEmpty() || deauths.isNotEmpty()
    HistoryArea.LAN -> lan.isNotEmpty()
    HistoryArea.INTERNET -> connections.isNotEmpty()
    HistoryArea.BLUETOOTH -> bluetooth.isNotEmpty()
} || (lines.isNotEmpty() && HistoryArea.ofKind(kind) == area)

/** The session as [area]'s tab shows it: only that tab's records. Null when there are none. */
fun HistoryEntry.only(area: HistoryArea): HistoryEntry? {
    if (!holds(area)) return null
    return rebuilt { it == area }
}

/** The session without [area]'s records. The same object when it has none, null when nothing is left. */
fun HistoryEntry.without(area: HistoryArea): HistoryEntry? {
    if (!holds(area)) return this
    return rebuilt { it != area }.takeIf { rest -> HistoryArea.entries.any { rest.holds(it) } }
}

/** [entries] as [area]'s tab lists them. */
fun List<HistoryEntry>.forArea(area: HistoryArea): List<HistoryEntry> = mapNotNull { it.only(area) }

private fun HistoryEntry.rebuilt(keep: (HistoryArea) -> Boolean): HistoryEntry = HistoryEntry(
    id = id,
    kind = kind,
    savedAt = savedAt,
    source = source,
    captureId = captureId,
    placeKey = placeKey,
    placeLabel = placeLabel,
    lines = if (keep(HistoryArea.ofKind(kind))) lines else emptyList(),
    networks = if (keep(HistoryArea.WIFI)) networks else emptyList(),
    probes = if (keep(HistoryArea.WIFI)) probes else emptyList(),
    deauths = if (keep(HistoryArea.WIFI)) deauths else emptyList(),
    lan = if (keep(HistoryArea.LAN)) lan else emptyList(),
    bluetooth = if (keep(HistoryArea.BLUETOOTH)) bluetooth else emptyList(),
    connections = if (keep(HistoryArea.INTERNET)) connections else emptyList(),
    latitude = latitude,
    longitude = longitude,
    importedFrom = importedFrom,
)
