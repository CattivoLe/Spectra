package cattivole.spectra.core

/**
 * Xiaomi's miIO / Mi Home devices: air fryers, vacuums, purifiers, lamps. Most of them open no TCP port at all,
 * so a TCP sweep finds an address and nothing else. They do answer a 32-byte "hello" on UDP 54321, and many
 * announce `_miio._udp` over mDNS with the model in the instance name: `careli-fryer-maf02_miio123456789`.
 */
object Miio {
    const val PORT = 54321
    const val SERVICE_TYPE = "_miio._udp"
    const val PORT_NAME = "miIO (UDP)"

    /** The hello every miIO device answers: magic 0x2131, length 32, the rest 0xFF. */
    val HELLO: ByteArray = ByteArray(32) { 0xFF.toByte() }.also {
        it[0] = 0x21
        it[1] = 0x31
        it[2] = 0x00
        it[3] = 0x20
    }

    /** The device id in a hello reply, or null when [data] is not one. */
    fun parseHello(data: ByteArray): Long? {
        if (data.size < 32) return null
        if (data[0] != 0x21.toByte() || data[1] != 0x31.toByte()) return null
        val length = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if (length != 32) return null
        var id = 0L
        for (index in 8 until 12) id = (id shl 8) or (data[index].toLong() and 0xFF)
        // Our own hello coming back on a broadcast carries 0xFFFFFFFF.
        return id.takeIf { it != 0xFFFF_FFFFL }
    }

    private val INSTANCE = Regex("""^([a-z0-9]+)-([a-z0-9]+)-([a-z0-9-]+?)_miio(\d+)$""", RegexOption.IGNORE_CASE)

    /** `careli-fryer-maf02_miio123456789` → `careli.fryer.maf02`; null for any other name. */
    fun modelFromInstance(name: String): String? {
        val match = INSTANCE.find(name.trim()) ?: return null
        val (vendor, type, version) = match.destructured
        return "$vendor.$type.$version".lowercase()
    }

    /** Whether [name] is a miIO instance name, which is an identifier rather than something to show. */
    fun isInstanceName(name: String): Boolean = INSTANCE.containsMatchIn(name.trim())

    /** What kind of appliance a model id such as `careli.fryer.maf02` is, in English (shown through `tr`). */
    fun kind(model: String): String? {
        val type = model.lowercase().split('.').getOrNull(1) ?: return null
        return KINDS.firstOrNull { (stem, _) -> type.startsWith(stem) }?.second
    }

    /** Longest stems first, so `airpurifier` is not read as `air…`. */
    private val KINDS = listOf(
        "airfryer" to "Air fryer",
        "fryer" to "Air fryer",
        "airpurifier" to "Air purifier",
        "airp" to "Air purifier",
        "airfresh" to "Air freshener",
        "aircondition" to "Air conditioner",
        "acpartner" to "Air conditioner",
        "humidifier" to "Humidifier",
        "dehumidifier" to "Dehumidifier",
        "vacuum" to "Robot vacuum",
        "sweeper" to "Robot vacuum",
        "mop" to "Robot vacuum",
        "cooker" to "Multicooker",
        "ihcooker" to "Induction cooker",
        "kettle" to "Kettle",
        "oven" to "Oven",
        "microwave" to "Microwave",
        "dishwasher" to "Dishwasher",
        "washer" to "Washing machine",
        "fridge" to "Fridge",
        "heater" to "Heater",
        "fan" to "Fan",
        "light" to "Lamp",
        "lamp" to "Lamp",
        "bulb" to "Lamp",
        "ceiling" to "Lamp",
        "plug" to "Smart plug",
        "switch" to "Switch",
        "gateway" to "Smart home hub",
        "camera" to "Camera",
        "wifispeaker" to "Smart speaker",
        "speaker" to "Smart speaker",
        "tv" to "TV",
        "curtain" to "Curtain motor",
        "waterpuri" to "Water purifier",
        "feeder" to "Pet feeder",
        "toilet" to "Smart toilet",
        "lock" to "Door lock",
    ).sortedByDescending { it.first.length }
}
