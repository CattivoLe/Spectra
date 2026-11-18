package cattivole.spectra.assistant

import cattivole.spectra.core.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything kept under History, searchable by the assistant (`search_history`): every network, host, Bluetooth
 * device, connection check and sensor summary, merged per thing across sessions, so "was this device here before?"
 * has an answer. Names come from the air and the network, so every one goes through [AssistantDigest.clean].
 * Mirrors HistoryDigest.swift.
 */
object HistoryDigest {
    const val BUDGET = 2600

    /** One thing (a network, a host, a device…) as seen across every session that has it. */
    class Item(
        val kind: String,
        /** What identifies it across sessions: a BSSID, MAC, IP, device address, or a summary line's title. */
        val key: String,
        var name: String,
        /** What was recorded the last time it was seen. */
        var detail: String,
        /** Lower-cased name, key and maker: what a search is matched against. */
        var haystack: String,
    ) {
        val dates = mutableListOf<Long>()
        val places = mutableListOf<String>()
        val sessions: Int get() = dates.size
        val first: Long get() = dates.minOrNull() ?: 0L
        val last: Long get() = dates.maxOrNull() ?: 0L
    }

    fun items(entries: List<HistoryEntry>): List<Item> {
        val byKey = LinkedHashMap<String, Item>()
        // Oldest first, so the detail that stays is the latest.
        for (entry in entries.sortedBy { it.savedAt }) {
            val place = entry.placeLabel ?: entry.importedFrom?.let { "from $it" }
            for (record in records(entry)) {
                val id = record.kind + "|" + record.key
                val item = byKey[id]?.also {
                    it.name = record.name
                    it.detail = record.detail
                    it.haystack = record.haystack
                } ?: record
                item.dates.add(entry.savedAt)
                if (place != null && place !in item.places) item.places.add(place)
                byKey[id] = item
            }
        }
        return byKey.values.toList()
    }

    private fun records(entry: HistoryEntry): List<Item> {
        val found = mutableListOf<Item>()
        fun add(kind: String, key: String, name: String, detail: String, maker: String? = null, aliases: List<String> = emptyList()) {
            val haystack = (listOf(name, key, maker.orEmpty()) + aliases).joinToString(" ").lowercase()
            found.add(Item(kind, key, name, detail, haystack))
        }
        for (network in entry.networks) {
            val reading = network.latest
            val name = network.revealedSsid ?: reading.ssid ?: "(hidden)"
            val parts = mutableListOf(reading.auth.name, "ch ${reading.channel}", "${reading.rssi} dBm")
            if (network.clients.isNotEmpty()) parts.add("${network.clients.size} clients")
            add("Wi‑Fi", reading.bssid.text, name, parts.joinToString(" · "))
        }
        for (probe in entry.probes) {
            val looking = probe.ssids.take(5).joinToString(", ")
            add("Wi‑Fi probe", probe.mac.text, probe.displayName, if (looking.isEmpty()) "probing for any network" else "looking for $looking", probe.manufacturer)
        }
        for (deauth in entry.deauths) {
            add(
                "Deauth", "${deauth.source.text}>${deauth.target.text}", "deauthentication from ${deauth.source.text}",
                "network ${deauth.bssid.text}, ${deauth.count} frames, reason ${deauth.reason}${if (deauth.isBroadcast) ", to everyone" else ""}",
            )
        }
        for (device in entry.lan) {
            val parts = mutableListOf<String>()
            device.manufacturer?.let(parts::add)
            if (device.isGateway) parts.add("router")
            if (device.isNew) parts.add("new on the network that day")
            if (device.portsScanned) {
                parts.add(if (device.openPorts.isEmpty()) "no open ports" else "open " + device.openPorts.take(8).joinToString(",") { "${it.number}/${it.service}" })
            }
            val network = entry.placeLabel?.let { " on $it" } ?: ""
            add(
                "LAN", device.macAddress?.text ?: device.ipAddress, "${device.displayName} ${device.ipAddress}",
                parts.joinToString(" · ") + network, device.manufacturer, device.hostnames + device.ipAddress,
            )
        }
        for (device in entry.bluetooth) {
            val parts = mutableListOf("${device.rssi} dBm")
            device.companyName?.let(parts::add)
            if (device.lastSeen > device.firstSeen) parts.add("nearby ${(device.lastSeen - device.firstSeen) / 60_000} min")
            add("Bluetooth", device.address, device.displayName, parts.joinToString(" · "), device.companyName)
        }
        for (connection in entry.connections) {
            val parts = mutableListOf(connection.status.name.lowercase())
            connection.external?.let { external ->
                parts.add("public ${external.ip}")
                external.isp?.let(parts::add)
                external.countryCode?.let(parts::add)
            }
            connection.speed?.downBps?.let { parts.add(String.format(Locale.US, "%.0f Mbit/s down", it / 1_000_000)) }
            if (connection.viaVpn) parts.add("via VPN")
            add("Connection check", "check ${connection.checkedAt}", "connection check ${clock(connection.checkedAt)}", parts.joinToString(" · "))
        }
        for (line in entry.lines) {
            add(
                if (entry.kind == "lora") "LoRa" else "${entry.kindTitle} (sensor)", line.title, line.title,
                listOf(line.detail, line.trailing).filter { it.isNotEmpty() }.joinToString(" · "),
            )
        }
        return found
    }

    /** What the history holds about [query]: the things it names, or else an overview. */
    fun text(entries: List<HistoryEntry>, query: String): String {
        if (entries.isEmpty()) return "History is empty: nothing has been saved yet. Scans are saved there automatically, or with Save to History."
        val all = items(entries)
        val kinds = kindsAsked(query)
        val pool = if (kinds.isEmpty()) all else all.filter { it.kind in kinds }
        val words = searchWords(query)
        val found = pool.mapNotNull { item ->
            val hits = words.count { item.haystack.contains(it) }
            if (hits > 0) item to hits else null
        }.sortedWith(compareByDescending<Pair<Item, Int>> { it.second }.thenByDescending { it.first.last }).map { it.first }

        val lines: MutableList<String>
        if (found.isNotEmpty()) {
            lines = mutableListOf("History matching the question (${found.size} of ${all.size} things saved across ${entries.size} sessions):")
            for (item in found.take(15)) lines.add(describe(item))
            if (found.size > 15) lines.add("- …and ${found.size - 15} more.")
        } else {
            lines = overview(entries, pool, kinds).toMutableList()
            if (words.isNotEmpty()) {
                lines.add(0, "Nothing saved matches ${words.take(5).joinToString(", ") { "\"${AssistantDigest.clean(it, 30)}\"" }}.")
            }
        }
        var text = lines.joinToString("\n")
        if (text.length > BUDGET) text = text.take(BUDGET) + "\n…(cut)"
        return text
    }

    private fun describe(item: Item): String {
        var line = "- [${item.kind}] ${AssistantDigest.clean(item.name, 50)}"
        if (!item.key.startsWith("check ") && item.kind != "Bluetooth" && !item.name.contains(item.key)) line += " ${item.key}"
        if (item.detail.isNotEmpty()) line += " · ${AssistantDigest.clean(item.detail, 140)}"
        line += if (item.sessions == 1) " · seen once, ${clock(item.last)}" else " · in ${item.sessions} sessions, ${clock(item.first)} – ${clock(item.last)}"
        if (item.places.isNotEmpty()) line += " · at " + item.places.take(3).joinToString("; ") { AssistantDigest.clean(it, 40) }
        return line
    }

    private fun overview(entries: List<HistoryEntry>, items: List<Item>, kinds: Set<String>): List<String> {
        val sessions = entries.sortedByDescending { it.savedAt }
        val shown = if (kinds.isEmpty()) sessions else sessions.filter { entry -> records(entry).any { it.kind in kinds } }
        val lines = mutableListOf(
            "History: ${sessions.size} saved sessions, ${clock(sessions.last().savedAt)} – ${clock(sessions.first().savedAt)}. Newest first:",
        )
        for (entry in shown.take(12)) {
            var line = "- ${clock(entry.savedAt)} · ${entry.kindTitle} · ${entry.itemCount} items"
            entry.placeLabel?.let { line += " · ${AssistantDigest.clean(it, 40)}" }
            val newHosts = entry.lan.count { it.isNew }
            if (newHosts > 0) line += " · $newHosts new hosts"
            entry.importedFrom?.let { line += " · imported from ${AssistantDigest.clean(it, 30)}" }
            lines.add(line)
        }
        if (shown.size > 12) lines.add("- …and ${shown.size - 12} older.")
        val recurring = items.filter { it.sessions > 1 }.sortedByDescending { it.sessions }
        if (recurring.isNotEmpty()) {
            lines.add("Seen again and again:")
            recurring.take(10).forEach { lines.add(describe(it)) }
        }
        val once = items.filter { it.sessions == 1 }.sortedByDescending { it.last }
        if (once.isNotEmpty()) {
            lines.add("Seen only once, newest first:")
            once.take(8).forEach { lines.add(describe(it)) }
        }
        lines.add("Name a network, device, address or MAC to get everything saved about it.")
        return lines
    }

    /** The kinds the question limits itself to, if any. */
    fun kindsAsked(query: String): Set<String> {
        val text = query.lowercase()
        val kinds = mutableSetOf<String>()
        if (AssistantDigest.matches(listOf("wi-fi", "wi‑fi", "wifi", "вай", "ssid", "точк", "bssid"), text)) {
            kinds += listOf("Wi‑Fi", "Wi‑Fi probe", "Deauth", "Wi‑Fi (sensor)")
        }
        if (AssistantDigest.matches(listOf("lan", "локальн", "хост", "host", "router", "роутер", "шлюз"), text)) kinds += listOf("LAN", "LAN (sensor)")
        if (AssistantDigest.matches(listOf("bluetooth", "блютуз", "блютус", "ble", "трекер", "tracker", "наушник", "airtag"), text)) {
            kinds += listOf("Bluetooth", "Bluetooth (sensor)")
        }
        if (AssistantDigest.matches(listOf("internet", "интернет", "connection check", "провайдер", "provider", "скорост", "speed"), text)) kinds += "Connection check"
        if (AssistantDigest.matches(listOf("lora", "лора", "meshtastic"), text)) kinds += "LoRa"
        return kinds
    }

    /** The words worth looking for: names, addresses, MACs. Steering words are dropped. */
    fun searchWords(query: String): List<String> =
        query.lowercase().split(Regex("[^\\p{L}\\p{N}.:\\-]+"))
            .map { it.trim('.', ':', '-') }
            .filter { it.length >= 3 && it !in IGNORED }
            .distinct()

    private val IGNORED = setOf(
        "что", "как", "где", "когда", "был", "была", "было", "были", "есть", "это", "этот", "эта", "эти", "мой", "моя",
        "мои", "моей", "мою", "моих", "все", "всё", "всех", "весь", "там", "тут", "уже", "ещё", "еще", "или", "при", "для",
        "истории", "история", "историю", "историй", "раньше", "прошлый", "прошлой", "прошлом", "прошлые", "вчера",
        "сохранённых", "сохраненных", "сохранённые", "сохраненные", "сессии", "сессий", "сессия", "найди", "покажи",
        "посмотри", "проверь", "расскажи", "видел", "видели", "встречал", "встречалось", "устройство", "устройства",
        "устройств", "сеть", "сети", "сетей", "сетью", "найден", "найдено", "какие", "какой", "какая", "сколько",
        "the", "and", "was", "were", "have", "has", "had", "this", "that", "what", "when", "where", "which", "seen",
        "history", "saved", "session", "sessions", "before", "earlier", "previous", "last", "time", "device", "devices",
        "network", "networks", "show", "find", "all", "any", "from", "with", "about", "did", "ever",
        "wifi", "wi-fi", "lan", "bluetooth", "блютуз", "блютус", "вайфай", "трекер", "трекеры", "трекеров",
    )

    private fun clock(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(time))
}
