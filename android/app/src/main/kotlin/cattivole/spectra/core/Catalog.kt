package cattivole.spectra.core

/** OUI names and Bluetooth SIG assigned numbers, loaded from the bundled tables. */
object Catalog {
    /**
     * The IEEE MA-L, MA-M and MA-S registries, built by data/tools/build_oui.py.
     * Keys are 24-bit prefixes ("AA:BB:CC"), 28-bit ("AA:BB:CC:D") and 36-bit
     * ("AA:BB:CC:DD:E").
     */
    private val oui: Map<String, String> by lazy { load("spectra/oui.tsv") { it.uppercase() } }
    private val companies: Map<Int, String> by lazy {
        load("spectra/ble-companies.tsv") { it.toIntOrNull(16) }
    }
    private val services: Map<String, String> by lazy {
        load("spectra/ble-services.tsv") { canonicalUuid(it) }
    }
    private val characteristics: Map<String, String> by lazy {
        load("spectra/ble-characteristics.tsv") { canonicalUuid(it) }
    }

    private val suspiciousOui = mapOf(
        "00:40:8C" to "Axis Communications",
        "18:68:CB" to "Hikvision",
        "44:19:B6" to "Hikvision",
        "4C:BD:8F" to "Hikvision",
        "BC:AD:28" to "Hikvision",
        "C0:56:E3" to "Hikvision",
        "3C:EF:8C" to "Dahua",
        "90:02:A9" to "Dahua",
        "A0:BD:1D" to "Dahua",
        "E0:50:8B" to "Dahua",
        "EC:71:DB" to "Reolink",
    )

    val cameraMakers = listOf(
        "hikvision", "dahua", "reolink", "amcrest", "foscam", "ezviz", "uniview",
        "hanwha", "vivotek", "geovision", "wyze", "arlo", "imou", "tapo", "ring",
        "axis communications",
    )
    val iotMakers = listOf(
        "espressif", "tuya", "philips hue", "shelly", "sonoff", "broadlink",
        "aqara", "meross", "switchbot", "sonos", "wiz",
    )

    fun oui(prefix: String): String? = oui[prefix.uppercase()]

    /**
     * The maker of a whole address: the most specific registry block wins,
     * since a 36-bit block is carved out of a 24-bit one that belongs to the
     * registration authority.
     */
    fun oui(bytes: ByteArray): String? {
        if (bytes.size < 5) return null
        val hex = bytes.take(5).map { "%02X".format(it.toInt() and 0xFF) }
        val base = "${hex[0]}:${hex[1]}:${hex[2]}"
        return oui["$base:${hex[3]}:${hex[4][0]}"] ?: oui["$base:${hex[3][0]}"] ?: oui[base]
    }

    fun suspiciousOui(prefix: String): String? = suspiciousOui[prefix.uppercase()]

    fun company(id: Int): String? = companies[id]

    fun serviceName(uuid: String): String? = services[canonicalUuid(uuid)]

    fun characteristicName(uuid: String): String? = characteristics[canonicalUuid(uuid)]

    fun displayUuid(uuid: String): String {
        val canonical = canonicalUuid(uuid)
        if (canonical.length != 32) return canonical
        return buildString {
            append(canonical.substring(0, 8))
            append('-')
            append(canonical.substring(8, 12))
            append('-')
            append(canonical.substring(12, 16))
            append('-')
            append(canonical.substring(16, 20))
            append('-')
            append(canonical.substring(20))
        }
    }

    /** 16-bit and 32-bit UUIDs collapse onto the Bluetooth base UUID. */
    fun canonicalUuid(uuid: String): String {
        val stripped = uuid.uppercase().filter { it != '-' }
        if (stripped.length == 32 &&
            stripped.startsWith("0000") &&
            stripped.endsWith("00001000800000805F9B34FB")
        ) {
            return stripped.substring(4, 8)
        }
        if (stripped.length == 8 && stripped.startsWith("0000")) return stripped.takeLast(4)
        return stripped
    }

    private fun <K> load(name: String, key: (String) -> K?): Map<K, String> {
        val stream = Catalog::class.java.classLoader?.getResourceAsStream(name) ?: return emptyMap()
        return stream.bufferedReader().useLines { lines ->
            val map = HashMap<K, String>(1024)
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val id = key(line.substring(0, tab)) ?: continue
                val value = line.substring(tab + 1)
                if (value.isNotEmpty()) map[id] = value
            }
            map
        }
    }
}
