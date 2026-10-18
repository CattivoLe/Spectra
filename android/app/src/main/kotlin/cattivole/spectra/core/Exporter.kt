package cattivole.spectra.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns saved history into shareable files. Pure string builders, so the
 * output can be checked by tests without Android.
 *
 * Three formats:
 * - [csv]    one row per record across every kind, with a `kind` column.
 * - [kml]    a placemark per located session, for Google Earth / maps.
 * - [wigle]  WiGLE's `WigleWifi` CSV, for uploading finds to wigle.net.
 */
object Exporter {
    enum class Format(val fileExtension: String, val label: String) {
        CSV("csv", "CSV"),
        KML("kml", "KML (map)"),
        WIGLE("csv", "WiGLE CSV"),
    }

    class ExportFile(val name: String, val contents: String)

    /** Device fields for the WiGLE header. The UI passes the real values; tests leave them blank. */
    class Device(val appVersion: String = "0", val model: String = "Android", val release: String = "", val brand: String = "")

    fun file(format: Format, entries: List<HistoryEntry>, device: Device = Device(), now: Long = System.currentTimeMillis()): ExportFile {
        val stamp = fileStamp().format(Date(now))
        val body = when (format) {
            Format.CSV -> csv(entries)
            Format.KML -> kml(entries)
            Format.WIGLE -> wigle(entries, device)
        }
        return ExportFile("spectra-$stamp.${format.fileExtension}", body)
    }

    // MARK: - Generic CSV

    fun csv(entries: List<HistoryEntry>): String {
        val rows = mutableListOf(
            "kind,saved_at,source,latitude,longitude,identifier,name,rssi,channel,security,vendor,notes",
        )
        for (entry in entries.sortedBy { it.savedAt }) {
            val whenText = iso().format(Date(entry.savedAt))
            val lat = entry.latitude?.let { "%.7f".format(Locale.US, it) } ?: ""
            val lon = entry.longitude?.let { "%.7f".format(Locale.US, it) } ?: ""

            fun row(
                kind: String,
                identifier: String = "",
                name: String = "",
                rssi: String = "",
                channel: String = "",
                security: String = "",
                vendor: String = "",
                notes: String = "",
            ) {
                rows.add(
                    listOf(kind, whenText, entry.source, lat, lon, identifier, name, rssi, channel, security, vendor, notes)
                        .joinToString(",") { escape(it) },
                )
            }

            for (network in entry.networks) {
                val ap = network.latest
                row(
                    kind = "wifi",
                    identifier = ap.bssid.text,
                    name = network.revealedSsid ?: ap.ssid.orEmpty(),
                    rssi = ap.rssi.toString(),
                    channel = ap.channel.toString(),
                    security = ap.auth.name,
                    vendor = if (ap.bssid.isLocallyAdministered) "" else ap.bssid.vendor().orEmpty(),
                    notes = network.traffic?.let { "${it.bytes} bytes, ${it.packets} pkts" } ?: "",
                )
            }
            for (probe in entry.probes) {
                row(
                    kind = "probe",
                    identifier = probe.mac.text,
                    name = probe.ssids.joinToString(" | "),
                    rssi = probe.rssi.toString(),
                    channel = probe.channel.toString(),
                    vendor = probe.manufacturer.orEmpty(),
                )
            }
            for (event in entry.deauths) {
                row(
                    kind = "deauth",
                    identifier = event.bssid.text,
                    rssi = event.rssi.toString(),
                    channel = event.channel.toString(),
                    notes = "src ${event.source} → ${if (event.isBroadcast) "broadcast" else event.target.text}, ${event.reasonDescription}, ×${event.count}",
                )
            }
            for (host in entry.lan) {
                val notes = buildList {
                    add(host.ipAddress)
                    if (host.openPorts.isNotEmpty()) add("ports " + host.openPorts.joinToString("/") { it.number.toString() })
                    if (host.isGateway) add("gateway")
                }.joinToString("; ")
                row(
                    kind = "lan",
                    identifier = host.macAddress?.text ?: host.ipAddress,
                    name = host.hostnames.firstOrNull().orEmpty(),
                    notes = notes,
                )
            }
            for (device in entry.bluetooth) {
                row(
                    kind = "bluetooth",
                    identifier = device.address,
                    name = device.localName ?: device.peripheralName ?: "",
                    rssi = device.rssi.toString(),
                    vendor = device.companyName.orEmpty(),
                )
            }
            if (!entry.hasRecords) {
                for (line in entry.lines) {
                    row(
                        kind = entry.kind,
                        name = line.title,
                        notes = listOf(line.detail, line.trailing).filter { it.isNotEmpty() }.joinToString(" · "),
                    )
                }
            }
        }
        return rows.joinToString("\n") + "\n"
    }

    // MARK: - KML

    fun kml(entries: List<HistoryEntry>): String {
        val placemarks = entries
            .filter { it.latitude != null && it.longitude != null }
            .joinToString("\n") { entry ->
                val lat = entry.latitude!!
                val lon = entry.longitude!!
                val title = "${entry.rowTitle} · ${entry.itemCount}"
                """
                <Placemark>
                  <name>${xml(title)}</name>
                  <description>${xml(kmlDescription(entry))}</description>
                  <TimeStamp><when>${iso().format(Date(entry.savedAt))}</when></TimeStamp>
                  <Point><coordinates>${"%.7f,%.7f,0".format(Locale.US, lon, lat)}</coordinates></Point>
                </Placemark>
                """.trimIndent()
            }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
            <Document>
              <name>Spectra</name>
            $placemarks
            </Document>
            </kml>
        """.trimIndent()
    }

    private fun kmlDescription(entry: HistoryEntry): String {
        val lines = mutableListOf("${entry.source} · ${iso().format(Date(entry.savedAt))}")
        entry.networks.take(40).forEach {
            lines.add("${it.revealedSsid ?: it.latest.ssid ?: "(hidden)"} — ${it.latest.bssid} ${it.latest.rssi} dBm ${it.latest.auth.name}")
        }
        entry.lan.take(40).forEach { lines.add("${it.ipAddress} ${it.hostnames.firstOrNull().orEmpty()}") }
        entry.bluetooth.take(40).forEach { lines.add("${it.localName ?: it.peripheralName ?: "(unnamed)"} ${it.rssi} dBm") }
        if (entry.networks.isEmpty() && entry.lan.isEmpty() && entry.bluetooth.isEmpty()) {
            entry.lines.take(40).forEach { lines.add(it.title) }
        }
        return lines.joinToString("\n")
    }

    // MARK: - WiGLE

    /**
     * WiGLE's `WigleWifi-1.6` CSV. Only records that carry a fix are exported:
     * WiGLE places each row on the map. Wi-Fi is `WIFI`; Bluetooth carries its
     * hardware address, so it is exported as `BLE`.
     */
    fun wigle(entries: List<HistoryEntry>, device: Device = Device()): String {
        val header = "WigleWifi-1.6,appRelease=${device.appVersion},model=${device.model},release=${device.release},device=Spectra,display=,board=,brand=${device.brand}"
        val columns = "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type"
        val rows = mutableListOf(header, columns)
        for (entry in entries.sortedBy { it.savedAt }) {
            val lat = entry.latitude ?: continue
            val lon = entry.longitude ?: continue
            val seen = wigleStamp().format(Date(entry.savedAt))
            fun row(mac: String, ssid: String, auth: String, channel: String, rssi: Int, type: String) {
                rows.add(
                    listOf(mac, ssid, auth, seen, channel, rssi.toString(), "%.7f".format(Locale.US, lat), "%.7f".format(Locale.US, lon), "0", "0", type)
                        .joinToString(",") { escape(it) },
                )
            }
            for (network in entry.networks) {
                val ap = network.latest
                row(ap.bssid.text, network.revealedSsid ?: ap.ssid.orEmpty(), wigleAuth(ap), ap.channel.toString(), ap.rssi, "WIFI")
            }
            for (device in entry.bluetooth) {
                val name = device.localName ?: device.peripheralName ?: ""
                row(device.address, name, "[BLE]", "0", device.rssi, "BLE")
            }
        }
        return rows.joinToString("\n") + "\n"
    }

    private fun wigleAuth(ap: AccessPointReading): String {
        val cipher = if (ap.pairwiseCipher == Cipher.NONE) "" else "-" + wigleCipher(ap.pairwiseCipher)
        val tags = mutableListOf<String>()
        when (ap.auth.raw) {
            0 -> {} // open
            1 -> tags.add("WEP")
            2 -> tags.add("WPA-PSK$cipher")
            3 -> tags.add("WPA2-PSK$cipher")
            4 -> { tags.add("WPA-PSK$cipher"); tags.add("WPA2-PSK$cipher") }
            5 -> tags.add("WPA2-EAP$cipher")
            6, 11, 12 -> tags.add("WPA3-SAE$cipher")
            7 -> { tags.add("WPA2-PSK$cipher"); tags.add("WPA3-SAE$cipher") }
            9 -> tags.add("OWE")
            10, 14, 15 -> tags.add("WPA3-EAP$cipher")
            else -> tags.add(ap.auth.name)
        }
        tags.add("ESS")
        return tags.joinToString("") { "[$it]" }
    }

    private fun wigleCipher(cipher: Cipher): String = when (cipher.raw) {
        3 -> "TKIP"
        4, 5 -> "CCMP"
        9 -> "GCMP"
        else -> "CCMP"
    }

    // MARK: - Helpers

    /** RFC 4180: quote and double inner quotes when a cell has a comma, quote or newline. */
    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    private fun xml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun iso() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    private fun wigleStamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun fileStamp() = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
}
