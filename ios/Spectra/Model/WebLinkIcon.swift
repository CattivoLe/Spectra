import Foundation

/// An icon a kept web page can have instead of its device's own, and what it
/// is suggested for. Mirrors WebLinkIcon.kt; keep the lists in step.
nonisolated struct WebLinkIcon: Identifiable, Equatable, Sendable {
    /// Kept with the link, so it stays the same across versions.
    var id: String
    var symbol: String
    var title: String
    var keywords: [String] = []
    var ports: Set<Int> = []
    /// What else such a device could be shown as.
    var related: [String] = []

    static let all: [WebLinkIcon] = [
        WebLinkIcon(id: "router", symbol: "wifi.router", title: String(localized: "Router"),
                    keywords: ["router", "gateway", "keenetic", "mikrotik", "routeros", "openwrt", "tp-link", "asuswrt", "tenda", "zyxel", "fritz", "роутер", "маршрутизатор", "шлюз"],
                    related: ["wifi", "network", "shield"]),
        WebLinkIcon(id: "wifi", symbol: "wifi", title: String(localized: "Wi-Fi"),
                    keywords: ["wifi", "wi-fi", "unifi", "mesh", "omada", "access point", "точка доступа"],
                    related: ["router", "network"]),
        WebLinkIcon(id: "network", symbol: "network", title: String(localized: "Network"),
                    keywords: ["switch", "ubiquiti", "коммутатор", "свитч"],
                    related: ["router", "server"]),
        WebLinkIcon(id: "storage", symbol: "externaldrive", title: String(localized: "Storage"),
                    keywords: ["nas", "synology", "diskstation", "qnap", "truenas", "freenas", "unraid", "openmediavault", "omv", "nextcloud", "storage", "хранилище", "сетевой диск"], ports: [5000, 5001],
                    related: ["server", "media", "download"]),
        WebLinkIcon(id: "server", symbol: "server.rack", title: String(localized: "Server"),
                    keywords: ["server", "proxmox", "esxi", "docker", "portainer", "webmin", "cockpit", "kubernetes", "vmware", "сервер"], ports: [9000, 10000],
                    related: ["storage", "stats", "terminal"]),
        WebLinkIcon(id: "camera", symbol: "video", title: String(localized: "Camera"),
                    keywords: ["camera", "ipcam", "webcam", "cam", "dvr", "nvr", "hikvision", "dahua", "reolink", "axis", "frigate", "network camera", "камера", "видеонаблюдение"], ports: [8000],
                    related: ["doorbell", "lock", "smartHome"]),
        WebLinkIcon(id: "doorbell", symbol: "bell", title: String(localized: "Doorbell"),
                    keywords: ["doorbell", "bell", "intercom", "звонок", "домофон"],
                    related: ["camera", "lock"]),
        WebLinkIcon(id: "lock", symbol: "lock", title: String(localized: "Door lock"),
                    keywords: ["lock", "door", "замок", "дверь", "ворота", "калитка"],
                    related: ["doorbell", "camera"]),
        WebLinkIcon(id: "smartHome", symbol: "house", title: String(localized: "Smart home"),
                    keywords: ["home assistant", "homeassistant", "hass", "domoticz", "openhab", "homebridge", "zigbee", "smart home", "умный дом"], ports: [8123],
                    related: ["light", "plug", "sensor"]),
        WebLinkIcon(id: "light", symbol: "lightbulb", title: String(localized: "Light"),
                    keywords: ["light", "lamp", "bulb", "hue", "wled", "yeelight", "свет", "светильник", "лампа", "люстра", "подсветка"],
                    related: ["plug", "smartHome"]),
        WebLinkIcon(id: "plug", symbol: "powerplug", title: String(localized: "Socket"),
                    keywords: ["plug", "socket", "outlet", "relay", "shelly", "tasmota", "sonoff", "розетка", "реле", "выключатель"],
                    related: ["light", "energy", "smartHome"]),
        WebLinkIcon(id: "thermostat", symbol: "thermometer.medium", title: String(localized: "Thermostat"),
                    keywords: ["thermostat", "thermo", "heating", "boiler", "climate", "heatpump", "термостат", "термо", "отопление", "котёл", "котел", "климат"],
                    related: ["fan", "water", "sensor"]),
        WebLinkIcon(id: "fan", symbol: "fan", title: String(localized: "Fan"),
                    keywords: ["fan", "ventilation", "purifier", "вентилятор", "вентиляция", "кондиционер", "бризер", "очиститель"],
                    related: ["thermostat", "sensor"]),
        WebLinkIcon(id: "water", symbol: "drop", title: String(localized: "Water"),
                    keywords: ["water", "pump", "irrigation", "sprinkler", "leak", "вода", "насос", "полив", "протечка", "бойлер"],
                    related: ["sensor", "garden"]),
        WebLinkIcon(id: "energy", symbol: "bolt", title: String(localized: "Energy"),
                    keywords: ["solar", "inverter", "ups", "battery", "meter", "energy", "инвертор", "ибп", "счётчик", "счетчик", "батарея", "солнечный"],
                    related: ["charger", "plug"]),
        WebLinkIcon(id: "charger", symbol: "ev.charger", title: String(localized: "Charger"),
                    keywords: ["charger", "wallbox", "evse", "зарядка"],
                    related: ["car", "energy"]),
        WebLinkIcon(id: "sensor", symbol: "sensor", title: String(localized: "Sensor"),
                    keywords: ["sensor", "датчик", "сенсор"],
                    related: ["thermostat", "smartHome", "board"]),
        WebLinkIcon(id: "vacuum", symbol: "robotic.vacuum", title: String(localized: "Robot vacuum"),
                    keywords: ["vacuum", "valetudo", "roborock", "robot", "пылесос", "робот"],
                    related: ["smartHome", "kitchen"]),
        WebLinkIcon(id: "washer", symbol: "washer", title: String(localized: "Washing machine"),
                    keywords: ["washer", "washing", "dryer", "стиральная", "сушильная"],
                    related: ["kitchen", "water"]),
        WebLinkIcon(id: "kitchen", symbol: "refrigerator", title: String(localized: "Kitchen"),
                    keywords: ["fridge", "refrigerator", "oven", "kitchen", "fryer", "kettle", "холодильник", "духовка", "кухня", "аэрогриль", "чайник"],
                    related: ["washer", "smartHome"]),
        WebLinkIcon(id: "tv", symbol: "tv", title: String(localized: "TV"),
                    keywords: ["tv", "television", "bravia", "tizen", "webos", "телевизор", "телек"],
                    related: ["media", "speaker"]),
        WebLinkIcon(id: "media", symbol: "play.rectangle", title: String(localized: "Media player"),
                    keywords: ["media", "plex", "jellyfin", "emby", "kodi", "chromecast", "player", "медиа", "плеер", "кино"], ports: [8096, 32400],
                    related: ["tv", "speaker", "storage"]),
        WebLinkIcon(id: "speaker", symbol: "hifispeaker", title: String(localized: "Speaker"),
                    keywords: ["speaker", "sonos", "audio", "hifi", "music", "eversolo", "volumio", "moode", "колонка", "музыка", "аудио", "усилитель"], ports: [1400],
                    related: ["media", "tv"]),
        WebLinkIcon(id: "printer", symbol: "printer", title: String(localized: "Printer"),
                    keywords: ["printer", "print", "laserjet", "officejet", "epson", "brother", "canon", "cups", "принтер", "мфу"], ports: [631],
                    related: ["computer", "printer3d"]),
        WebLinkIcon(id: "printer3d", symbol: "cube", title: String(localized: "3D printer"),
                    keywords: ["3d", "octoprint", "klipper", "mainsail", "fluidd", "prusa", "creality", "bambu"],
                    related: ["board", "camera"]),
        WebLinkIcon(id: "computer", symbol: "desktopcomputer", title: String(localized: "Computer"),
                    keywords: ["pc", "desktop", "workstation", "imac", "computer", "компьютер", "пк"],
                    related: ["server", "laptop"]),
        WebLinkIcon(id: "laptop", symbol: "laptopcomputer", title: String(localized: "Laptop"),
                    keywords: ["laptop", "macbook", "notebook", "ноутбук"],
                    related: ["computer", "terminal"]),
        WebLinkIcon(id: "board", symbol: "cpu", title: String(localized: "Board"),
                    keywords: ["esp", "esp32", "esp8266", "espressif", "arduino", "raspberry", "raspberrypi", "pi", "плата"],
                    related: ["sensor", "plug", "smartHome"]),
        WebLinkIcon(id: "phone", symbol: "phone", title: String(localized: "Phone"),
                    keywords: ["phone", "voip", "sip", "asterisk", "freepbx", "телефон"],
                    related: ["network"]),
        WebLinkIcon(id: "game", symbol: "gamecontroller", title: String(localized: "Game console"),
                    keywords: ["xbox", "playstation", "nintendo", "game", "консоль", "приставка", "игра", "игровой"],
                    related: ["tv", "media"]),
        WebLinkIcon(id: "download", symbol: "arrow.down.circle", title: String(localized: "Downloads"),
                    keywords: ["torrent", "transmission", "qbittorrent", "deluge", "download", "aria2", "sonarr", "radarr", "загрузки", "торрент"], ports: [9091],
                    related: ["storage", "media"]),
        WebLinkIcon(id: "shield", symbol: "shield", title: String(localized: "Protection"),
                    keywords: ["pfsense", "opnsense", "adguard", "pihole", "pi-hole", "firewall", "vpn", "wireguard", "защита", "фаервол"],
                    related: ["router", "network"]),
        WebLinkIcon(id: "stats", symbol: "chart.xyaxis.line", title: String(localized: "Statistics"),
                    keywords: ["grafana", "prometheus", "netdata", "zabbix", "kuma", "monitor", "monitoring", "stats", "мониторинг", "статистика", "график"], ports: [3000, 9090],
                    related: ["server", "network"]),
        WebLinkIcon(id: "terminal", symbol: "terminal", title: String(localized: "Terminal"),
                    keywords: ["terminal", "git", "gitea", "gitlab", "code", "jenkins", "терминал"],
                    related: ["server", "computer"]),
        WebLinkIcon(id: "car", symbol: "car", title: String(localized: "Car"),
                    keywords: ["car", "garage", "машина", "гараж", "авто"],
                    related: ["charger", "garden"]),
        WebLinkIcon(id: "garden", symbol: "leaf", title: String(localized: "Garden"),
                    keywords: ["garden", "greenhouse", "mower", "сад", "теплица", "газон"],
                    related: ["water", "sensor"]),
        WebLinkIcon(id: "web", symbol: "globe", title: String(localized: "Web page")),
        WebLinkIcon(id: "star", symbol: "star", title: String(localized: "Star")),
        WebLinkIcon(id: "heart", symbol: "heart", title: String(localized: "Heart")),
    ]

    static func with(id: String?) -> WebLinkIcon? {
        guard let id else { return nil }
        return all.first { $0.id == id }
    }

    /// The icon of the list that looks like the device's own, if one does.
    static func own(_ symbol: String) -> WebLinkIcon? { all.first { $0.symbol == symbol } }

    /// The icons that fit what is known about a page: `texts` go from the most
    /// telling to the least, then the port, then icons close to those and to
    /// the device's own, which is left out itself.
    static func suggestions(texts: [String], port: Int, own: String?, limit: Int = 5) -> [WebLinkIcon] {
        let prepared = texts.map(Words.init)
        let ranked: [(rank: Int, icon: WebLinkIcon)] = all.compactMap { icon in
            if let rank = prepared.firstIndex(where: { words in icon.keywords.contains(where: words.contain) }) {
                return (rank, icon)
            }
            return icon.ports.contains(port) ? (prepared.count, icon) : nil
        }
        // A stable order: the list's own for equally good ones.
        let fitting = ranked.enumerated()
            .sorted { ($0.element.rank, $0.offset) < ($1.element.rank, $1.offset) }
            .map(\.element.icon.id)
        let seeds = fitting + [own].compactMap { $0 }
        let close = seeds.isEmpty ? ["smartHome", "server", "web"] : seeds.flatMap { with(id: $0)?.related ?? [] }
        var taken: Set<String> = [own ?? ""]
        return (fitting + close).filter { taken.insert($0).inserted }.prefix(limit).compactMap { with(id: $0) }
    }

    /// A text cut into words. A short keyword must be a whole word, with
    /// a number after it at most ("cam2"); a longer one may start a word.
    private struct Words {
        let words: [String]
        let line: String

        init(_ text: String) {
            words = text.lowercased().split { !$0.isLetter && !$0.isNumber }.map(String.init)
            line = " " + words.joined(separator: " ") + " "
        }

        func contain(_ keyword: String) -> Bool {
            if keyword.contains(where: { $0 == " " || $0 == "-" }) {
                return line.contains(" " + keyword.replacingOccurrences(of: "-", with: " "))
            }
            if keyword.count > 3 { return words.contains { $0.hasPrefix(keyword) } }
            return words.contains { word in
                word == keyword || (word.hasPrefix(keyword) && word.dropFirst(keyword.count).allSatisfy(\.isNumber))
            }
        }
    }
}
