package cattivole.spectra.core

/**
 * An icon a kept web page can have instead of its device's own, and what it is
 * suggested for. Mirrors the iOS `WebLinkIcon`; keep the lists in step.
 */
data class WebLinkIcon(
    /** Kept with the link, so it stays the same across versions. */
    val id: String,
    /** English; the UI translates it. */
    val title: String,
    val keywords: List<String> = emptyList(),
    val ports: Set<Int> = emptySet(),
    /** What else such a device could be shown as. */
    val related: List<String> = emptyList(),
) {
    companion object {
        val all = listOf(
            WebLinkIcon(
                "router", "Router",
                listOf("router", "gateway", "keenetic", "mikrotik", "routeros", "openwrt", "tp-link", "asuswrt", "tenda", "zyxel", "fritz", "роутер", "маршрутизатор", "шлюз"),
                related = listOf("wifi", "network", "shield"),
            ),
            WebLinkIcon(
                "wifi", "Wi-Fi",
                listOf("wifi", "wi-fi", "unifi", "mesh", "omada", "access point", "точка доступа"),
                related = listOf("router", "network"),
            ),
            WebLinkIcon(
                "network", "Network",
                listOf("switch", "ubiquiti", "коммутатор", "свитч"),
                related = listOf("router", "server"),
            ),
            WebLinkIcon(
                "storage", "Storage",
                listOf("nas", "synology", "diskstation", "qnap", "truenas", "freenas", "unraid", "openmediavault", "omv", "nextcloud", "storage", "хранилище", "сетевой диск"),
                setOf(5000, 5001),
                related = listOf("server", "media", "download"),
            ),
            WebLinkIcon(
                "server", "Server",
                listOf("server", "proxmox", "esxi", "docker", "portainer", "webmin", "cockpit", "kubernetes", "vmware", "сервер"),
                setOf(9000, 10000),
                related = listOf("storage", "stats", "terminal"),
            ),
            WebLinkIcon(
                "camera", "Camera",
                listOf("camera", "ipcam", "webcam", "cam", "dvr", "nvr", "hikvision", "dahua", "reolink", "axis", "frigate", "network camera", "камера", "видеонаблюдение"),
                setOf(8000),
                related = listOf("doorbell", "lock", "smartHome"),
            ),
            WebLinkIcon(
                "doorbell", "Doorbell",
                listOf("doorbell", "bell", "intercom", "звонок", "домофон"),
                related = listOf("camera", "lock"),
            ),
            WebLinkIcon(
                "lock", "Door lock",
                listOf("lock", "door", "замок", "дверь", "ворота", "калитка"),
                related = listOf("doorbell", "camera"),
            ),
            WebLinkIcon(
                "smartHome", "Smart home",
                listOf("home assistant", "homeassistant", "hass", "domoticz", "openhab", "homebridge", "zigbee", "smart home", "умный дом"),
                setOf(8123),
                related = listOf("light", "plug", "sensor"),
            ),
            WebLinkIcon(
                "light", "Light",
                listOf("light", "lamp", "bulb", "hue", "wled", "yeelight", "свет", "светильник", "лампа", "люстра", "подсветка"),
                related = listOf("plug", "smartHome"),
            ),
            WebLinkIcon(
                "plug", "Socket",
                listOf("plug", "socket", "outlet", "relay", "shelly", "tasmota", "sonoff", "розетка", "реле", "выключатель"),
                related = listOf("light", "energy", "smartHome"),
            ),
            WebLinkIcon(
                "thermostat", "Thermostat",
                listOf("thermostat", "thermo", "heating", "boiler", "climate", "heatpump", "термостат", "термо", "отопление", "котёл", "котел", "климат"),
                related = listOf("fan", "water", "sensor"),
            ),
            WebLinkIcon(
                "fan", "Fan",
                listOf("fan", "ventilation", "purifier", "вентилятор", "вентиляция", "кондиционер", "бризер", "очиститель"),
                related = listOf("thermostat", "sensor"),
            ),
            WebLinkIcon(
                "water", "Water",
                listOf("water", "pump", "irrigation", "sprinkler", "leak", "вода", "насос", "полив", "протечка", "бойлер"),
                related = listOf("sensor", "garden"),
            ),
            WebLinkIcon(
                "energy", "Energy",
                listOf("solar", "inverter", "ups", "battery", "meter", "energy", "инвертор", "ибп", "счётчик", "счетчик", "батарея", "солнечный"),
                related = listOf("charger", "plug"),
            ),
            WebLinkIcon(
                "charger", "Charger",
                listOf("charger", "wallbox", "evse", "зарядка"),
                related = listOf("car", "energy"),
            ),
            WebLinkIcon(
                "sensor", "Sensor",
                listOf("sensor", "датчик", "сенсор"),
                related = listOf("thermostat", "smartHome", "board"),
            ),
            WebLinkIcon(
                "vacuum", "Robot vacuum",
                listOf("vacuum", "valetudo", "roborock", "robot", "пылесос", "робот"),
                related = listOf("smartHome", "kitchen"),
            ),
            WebLinkIcon(
                "washer", "Washing machine",
                listOf("washer", "washing", "dryer", "стиральная", "сушильная"),
                related = listOf("kitchen", "water"),
            ),
            WebLinkIcon(
                "kitchen", "Kitchen",
                listOf("fridge", "refrigerator", "oven", "kitchen", "fryer", "kettle", "холодильник", "духовка", "кухня", "аэрогриль", "чайник"),
                related = listOf("washer", "smartHome"),
            ),
            WebLinkIcon(
                "tv", "TV",
                listOf("tv", "television", "bravia", "tizen", "webos", "телевизор", "телек"),
                related = listOf("media", "speaker"),
            ),
            WebLinkIcon(
                "media", "Media player",
                listOf("media", "plex", "jellyfin", "emby", "kodi", "chromecast", "player", "медиа", "плеер", "кино"),
                setOf(8096, 32400),
                related = listOf("tv", "speaker", "storage"),
            ),
            WebLinkIcon(
                "speaker", "Speaker",
                listOf("speaker", "sonos", "audio", "hifi", "music", "eversolo", "volumio", "moode", "колонка", "музыка", "аудио", "усилитель"),
                setOf(1400),
                related = listOf("media", "tv"),
            ),
            WebLinkIcon(
                "printer", "Printer",
                listOf("printer", "print", "laserjet", "officejet", "epson", "brother", "canon", "cups", "принтер", "мфу"),
                setOf(631),
                related = listOf("computer", "printer3d"),
            ),
            WebLinkIcon(
                "printer3d", "3D printer",
                listOf("3d", "octoprint", "klipper", "mainsail", "fluidd", "prusa", "creality", "bambu"),
                related = listOf("board", "camera"),
            ),
            WebLinkIcon(
                "computer", "Computer",
                listOf("pc", "desktop", "workstation", "imac", "computer", "компьютер", "пк"),
                related = listOf("server", "laptop"),
            ),
            WebLinkIcon(
                "laptop", "Laptop",
                listOf("laptop", "macbook", "notebook", "ноутбук"),
                related = listOf("computer", "terminal"),
            ),
            WebLinkIcon(
                "board", "Board",
                listOf("esp", "esp32", "esp8266", "espressif", "arduino", "raspberry", "raspberrypi", "pi", "плата"),
                related = listOf("sensor", "plug", "smartHome"),
            ),
            WebLinkIcon(
                "phone", "Phone",
                listOf("phone", "voip", "sip", "asterisk", "freepbx", "телефон"),
                related = listOf("network"),
            ),
            WebLinkIcon(
                "game", "Game console",
                listOf("xbox", "playstation", "nintendo", "game", "консоль", "приставка", "игра", "игровой"),
                related = listOf("tv", "media"),
            ),
            WebLinkIcon(
                "download", "Downloads",
                listOf("torrent", "transmission", "qbittorrent", "deluge", "download", "aria2", "sonarr", "radarr", "загрузки", "торрент"),
                setOf(9091),
                related = listOf("storage", "media"),
            ),
            WebLinkIcon(
                "shield", "Protection",
                listOf("pfsense", "opnsense", "adguard", "pihole", "pi-hole", "firewall", "vpn", "wireguard", "защита", "фаервол"),
                related = listOf("router", "network"),
            ),
            WebLinkIcon(
                "stats", "Statistics",
                listOf("grafana", "prometheus", "netdata", "zabbix", "kuma", "monitor", "monitoring", "stats", "мониторинг", "статистика", "график"),
                setOf(3000, 9090),
                related = listOf("server", "network"),
            ),
            WebLinkIcon(
                "terminal", "Terminal",
                listOf("terminal", "git", "gitea", "gitlab", "code", "jenkins", "терминал"),
                related = listOf("server", "computer"),
            ),
            WebLinkIcon(
                "car", "Car",
                listOf("car", "garage", "машина", "гараж", "авто"),
                related = listOf("charger", "garden"),
            ),
            WebLinkIcon(
                "garden", "Garden",
                listOf("garden", "greenhouse", "mower", "сад", "теплица", "газон"),
                related = listOf("water", "sensor"),
            ),
            WebLinkIcon(
                "web", "Web page",
            ),
            WebLinkIcon(
                "star", "Star",
            ),
            WebLinkIcon(
                "heart", "Heart",
            ),
        )

        fun with(id: String?): WebLinkIcon? = all.firstOrNull { it.id == id }

        /**
         * The icons that fit what is known about a page: [texts] go from the most
         * telling to the least, then the port, then icons close to those and to
         * the device's [own], which is left out itself.
         */
        fun suggestions(texts: List<String>, port: Int, own: String?, limit: Int = 5): List<WebLinkIcon> {
            val prepared = texts.map(::Words)
            val fitting = all.mapNotNull { icon ->
                val rank = prepared.indexOfFirst { words -> icon.keywords.any(words::contain) }
                when {
                    rank >= 0 -> rank to icon.id
                    port in icon.ports -> prepared.size to icon.id
                    else -> null
                }
            }
                // A stable sort: the list's own order for equally good ones.
                .sortedBy { (rank, _) -> rank }
                .map { it.second }
            val seeds = fitting + listOfNotNull(own)
            val close = if (seeds.isEmpty()) listOf("smartHome", "server", "web") else seeds.flatMap { with(it)?.related.orEmpty() }
            return (fitting + close).distinct().filter { it != own }.take(limit).mapNotNull(::with)
        }
    }

    /**
     * A text cut into words. A short keyword must be a whole word, with a number
     * after it at most ("cam2"); a longer one may start a word.
     */
    private class Words(text: String) {
        private val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        private val line = words.joinToString(" ", prefix = " ", postfix = " ")

        fun contain(keyword: String): Boolean = when {
            keyword.any { it == ' ' || it == '-' } -> " ${keyword.replace('-', ' ')}" in line
            keyword.length > 3 -> words.any { it.startsWith(keyword) }
            else -> words.any { word -> word.startsWith(keyword) && word.drop(keyword.length).all { it.isDigit() } }
        }
    }
}
