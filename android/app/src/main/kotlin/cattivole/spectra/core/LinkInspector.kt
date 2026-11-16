package cattivole.spectra.core

import java.net.URLDecoder

/** What a link looks like before anyone opens it. Mirrors the iOS `LinkInspection`. */
data class LinkInspection(
    /** The link as it was found in the text. */
    val link: String,
    val scheme: String,
    /** The host as a person reads it, with punycode turned back into letters. */
    val host: String,
    /** The host as the address bar and DNS see it, when it differs from [host]. */
    val asciiHost: String?,
    /** Who owns the site: the name registered under its public suffix, or the account on a free hosting platform. */
    val site: String,
    val findings: List<Finding>,
    /** Links carried inside this one, like the `?url=` of a mail filter or a tracker. */
    val nested: List<LinkInspection>,
) {
    enum class Severity { INFO, CAUTION, DANGER }

    enum class Verdict { NO_FLAGS, CAUTION, SUSPICIOUS, DANGEROUS }

    enum class Kind {
        NOT_WEB, INSECURE, IP_ADDRESS, USER_INFO, ENCODED_HOST,
        MIXED_SCRIPTS, LOOKALIKE_LETTERS, FOREIGN_SCRIPT, PUNYCODE,
        LOOKALIKE, BRAND_IN_SUBDOMAIN, BRAND_IN_DOMAIN, OFFICIAL,
        SHORTENER, FREE_HOSTING, RISKY_ZONE, MANY_SUBDOMAINS, BAIT_WORDS, UNUSUAL_PORT, LEADS_ON,

        // From the on-demand certificate and registry check.
        CERTIFICATE_UNAVAILABLE, CERTIFICATE_UNTRUSTED, CERTIFICATE_EXPIRED, CERTIFICATE_NEW, CERTIFICATE_INFO,
        DOMAIN_NEW, DOMAIN_YOUNG, DOMAIN_INFO, DOMAIN_MISSING, DOMAIN_UNKNOWN,
    }

    /** [text] is the English wording with `%s` places; [args] fill them. */
    data class Finding(val kind: Kind, val severity: Severity, val text: String, val args: List<String> = emptyList())

    val ownVerdict: Verdict
        get() {
            if (findings.any { it.severity == Severity.DANGER }) return Verdict.DANGEROUS
            return when (findings.count { it.severity == Severity.CAUTION }) {
                0 -> Verdict.NO_FLAGS
                1 -> Verdict.CAUTION
                else -> Verdict.SUSPICIOUS
            }
        }

    val verdict: Verdict get() = maxOf(ownVerdict, nested.maxOfOrNull { it.verdict } ?: Verdict.NO_FLAGS)
}

/**
 * Reads a link the way a careful person would: who really owns the site, and
 * whether the address borrows a brand, hides behind look-alike letters or a
 * shortener, or carries the usual phishing bait.
 *
 * Pure and offline. Nothing is fetched, so checking a link never tells the
 * sender that the message was opened. Keep the rules and lists in step with
 * the iOS `LinkInspector`.
 */
object LinkInspector {
    /** The first link in [text], inspected. Null when the text holds no link. */
    fun inspect(text: String): LinkInspection? = extractLink(text)?.let { inspect(it, 0) }

    // Finding the link

    private val linkInText = Regex("""(?i)\b(?:[a-z][a-z0-9+.-]*://|www\.)\S+|\b[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*\.\p{L}{2,}(?:[/?#:]\S*)?""")

    fun extractLink(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if (trimmed.none { it.isWhitespace() }) {
            trimmed
        } else {
            linkInText.find(trimmed)?.value ?: return null
        }
        var link = candidate.trim { it in "<>\"'«»“”‘’()[]" }
        while (link.isNotEmpty() && link.last() in ".,;:!?") link = link.dropLast(1)
        if (link.isEmpty()) return null
        if (schemeOf(link) != null) return link
        // A bare address needs a dot in its host to count as one.
        val host = link.takeWhile { it !in "/?#" }
        return if ('.' in host) link else null
    }

    /** The scheme of [link], when it starts with one. "example.com:8080" has none. */
    private fun schemeOf(link: String): String? {
        val colon = link.indexOf(':')
        if (colon <= 0) return null
        val head = link.substring(0, colon)
        if (!head[0].isAsciiLetter()) return null
        if (!head.all { it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' }) return null
        // "example.com:8080": a dot in front of the colon is a host, not a scheme.
        return head.lowercase()
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    // Inspecting

    private fun inspect(link: String, depth: Int): LinkInspection {
        val findings = mutableListOf<LinkInspection.Finding>()
        fun note(kind: LinkInspection.Kind, severity: LinkInspection.Severity, text: String, vararg args: String) {
            if (findings.any { it.kind == kind }) return
            findings.add(LinkInspection.Finding(kind, severity, text, args.toList()))
        }

        val scheme = schemeOf(link) ?: "https"
        if (scheme != "http" && scheme != "https") {
            if (scheme in listOf("javascript", "data", "vbscript", "file")) {
                note(LinkInspection.Kind.NOT_WEB, LinkInspection.Severity.DANGER, "This is not a web page: a %s: link runs code or opens a file.", scheme)
            } else {
                note(LinkInspection.Kind.NOT_WEB, LinkInspection.Severity.INFO, "This is not a web link but a %s: link.", scheme)
            }
            return LinkInspection(link, scheme, "", null, "", findings, emptyList())
        }

        // Authority: everything up to the first path, query or fragment.
        // Browsers read a backslash as a slash.
        val afterScheme = link.indexOf("://").let { if (it >= 0) link.substring(it + 3) else link }
        val authority = afterScheme.takeWhile { it !in "/?#\\" }
        var hostPort = authority
        val at = authority.lastIndexOf('@')
        if (at >= 0) {
            hostPort = authority.substring(at + 1)
            note(
                LinkInspection.Kind.USER_INFO, LinkInspection.Severity.DANGER,
                "The browser skips everything before the @ (%s) and opens %s.", authority.substring(0, at), hostPort,
            )
        }
        var rawHost = hostPort
        var port: String? = null
        val close = rawHost.indexOf(']')
        if (rawHost.startsWith("[") && close > 0) {
            val rest = rawHost.substring(close + 1)
            if (rest.startsWith(":")) port = rest.drop(1)
            rawHost = rawHost.substring(0, close + 1)
        } else {
            val colon = rawHost.lastIndexOf(':')
            if (colon >= 0) {
                val tail = rawHost.substring(colon + 1)
                if (tail.all { it.isDigit() }) {
                    port = tail.ifEmpty { null }
                    rawHost = rawHost.substring(0, colon)
                }
            }
        }
        if ('%' in rawHost) {
            note(LinkInspection.Kind.ENCODED_HOST, LinkInspection.Severity.DANGER, "The site name contains encoded characters, which real sites do not use.")
            rawHost = runCatching { URLDecoder.decode(rawHost.replace("+", "%2B"), "UTF-8") }.getOrDefault(rawHost)
        }
        rawHost = rawHost.trimEnd('.')

        val asciiLabels = rawHost.lowercase().split('.')
        val labels = asciiLabels.map { label ->
            if (label.startsWith("xn--")) punycodeDecode(label.drop(4)) ?: label else label
        }
        val host = labels.joinToString(".")
        val asciiHost = if (asciiLabels.any { it.startsWith("xn--") }) asciiLabels.joinToString(".") else null
        val full = if ("://" in link) link else "$scheme://$link"

        if (scheme == "http") {
            note(LinkInspection.Kind.INSECURE, LinkInspection.Severity.CAUTION, "The page is not encrypted (http): anything typed on it can be read on the way.")
        }
        if (port != null && port != "80" && port != "443") {
            note(LinkInspection.Kind.UNUSUAL_PORT, LinkInspection.Severity.CAUTION, "The address uses an unusual port, %s.", port)
        }

        if (isIpAddress(rawHost)) {
            note(LinkInspection.Kind.IP_ADDRESS, LinkInspection.Severity.DANGER, "A bare IP address (%s) stands where a site name should be.", rawHost)
            return LinkInspection(link, scheme, rawHost, null, rawHost, findings, nestedLinks(full, depth))
        }

        val (site, platform) = siteOf(labels)
        val siteLabels = site.split('.').filter { it.isNotEmpty() }
        val siteName = siteLabels.firstOrNull() ?: site
        val zone = labels.lastOrNull().orEmpty()
        val subdomains = labels.dropLast(siteLabels.size).filter { it != "www" }

        // Letters
        if (asciiHost != null) {
            note(LinkInspection.Kind.PUNYCODE, LinkInspection.Severity.INFO, "In the address bar this shows as %s.", asciiHost)
        }
        for (label in labels) {
            val scripts = label.mapNotNull { script(it) }.toSet()
            if (scripts.size > 1) {
                note(
                    LinkInspection.Kind.MIXED_SCRIPTS, LinkInspection.Severity.DANGER,
                    "“%s” mixes letters from different alphabets, a classic way to fake a familiar name.", label,
                )
            } else if (scripts.size == 1 && scripts.first() != Script.LATIN &&
                label.all { confusables.containsKey(it) || !it.isLetter() } && zone !in cyrillicZones
            ) {
                note(LinkInspection.Kind.LOOKALIKE_LETTERS, LinkInspection.Severity.DANGER, "“%s” is written in letters that only look Latin.", label)
            }
        }
        if ((asciiHost != null || host.any { it.code > 0x7F }) &&
            findings.none { it.kind == LinkInspection.Kind.MIXED_SCRIPTS || it.kind == LinkInspection.Kind.LOOKALIKE_LETTERS }
        ) {
            note(LinkInspection.Kind.FOREIGN_SCRIPT, LinkInspection.Severity.INFO, "The site name uses non-Latin letters.")
        }

        // Brands
        val officialBrand = brands.firstOrNull { isOfficial(site, it) }
        if (officialBrand != null) {
            note(LinkInspection.Kind.OFFICIAL, LinkInspection.Severity.INFO, "%s belongs to %s.", site, officialBrand.display)
        } else {
            val siteSkeleton = skeleton(siteName)
            for (brand in brands) {
                val typo = brand.name.length >= 6 && siteName.firstOrNull() == brand.name.first() &&
                    siteName.lastOrNull() == brand.name.last() && levenshtein(siteName, brand.name) == 1
                if (siteName == brand.name || siteSkeleton == skeleton(brand.name) || typo) {
                    note(LinkInspection.Kind.LOOKALIKE, LinkInspection.Severity.DANGER, "Pretends to be %s. Its real site is %s.", brand.display, brand.sites[0])
                    break
                }
            }
            brands.firstOrNull { brand -> subdomains.any { mentions(it, brand) } }?.let { brand ->
                note(
                    LinkInspection.Kind.BRAND_IN_SUBDOMAIN, LinkInspection.Severity.DANGER,
                    "The address starts with %s, but the site belongs to %s.", brand.display, site,
                )
            }
            if (findings.none { it.kind == LinkInspection.Kind.LOOKALIKE }) {
                brands.firstOrNull { mentions(siteName, it) }?.let { brand ->
                    note(
                        LinkInspection.Kind.BRAND_IN_DOMAIN, LinkInspection.Severity.CAUTION,
                        "Uses the name %s, but it is not its site (%s).", brand.display, brand.sites[0],
                    )
                }
            }
        }

        // Where the site lives
        if (site in shorteners) {
            note(LinkInspection.Kind.SHORTENER, LinkInspection.Severity.CAUTION, "A short link: the real address is hidden.")
        }
        if (platform != null) {
            note(LinkInspection.Kind.FREE_HOSTING, LinkInspection.Severity.CAUTION, "The site sits on %s, where anyone can make an address like this.", platform)
        }
        if (zone in riskyZones) {
            note(LinkInspection.Kind.RISKY_ZONE, LinkInspection.Severity.CAUTION, "The .%s zone is common in phishing mail.", zone)
        }
        if (subdomains.size >= 4) {
            note(
                LinkInspection.Kind.MANY_SUBDOMAINS, LinkInspection.Severity.CAUTION,
                "An unusually long name: %s levels in front of %s.", subdomains.size.toString(), site,
            )
        }
        if (officialBrand == null) {
            val tokens = labels.dropLast(1).flatMap { label -> label.split(Regex("[-_0-9]")).filter { it.isNotEmpty() } }.toSet()
            val bait = baitWords.filter { it in tokens }.sorted()
            if (bait.isNotEmpty()) {
                note(
                    LinkInspection.Kind.BAIT_WORDS, LinkInspection.Severity.CAUTION,
                    "The address has words phishing likes to use: %s.", bait.joinToString(", "),
                )
            }
        }

        val nested = nestedLinks(full, depth)
        nested.firstOrNull()?.let { first ->
            note(LinkInspection.Kind.LEADS_ON, LinkInspection.Severity.INFO, "The link passes on another address: %s.", first.host.ifEmpty { first.link })
        }
        return LinkInspection(link, scheme, host, asciiHost, site, findings, nested)
    }

    /** Web addresses carried in the query, as mail filters and trackers wrap them. */
    private fun nestedLinks(url: String, depth: Int): List<LinkInspection> {
        if (depth >= 2) return emptyList()
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return emptyList()
        for (item in query.split('&')) {
            val name = item.substringBefore('=').lowercase()
            if (name !in redirectParameters || '=' !in item) continue
            val value = runCatching { URLDecoder.decode(item.substringAfter('='), "UTF-8") }.getOrNull()?.trim() ?: continue
            val lower = value.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) continue
            return listOf(inspect(value, depth + 1))
        }
        return emptyList()
    }

    // Sites

    /**
     * The registered name: the last two labels, or three under a public suffix
     * of two labels, or the account under a free hosting platform.
     */
    fun siteOf(labels: List<String>): Pair<String, String?> {
        if (labels.size <= 2) return labels.joinToString(".") to null
        val lastTwo = labels.takeLast(2).joinToString(".")
        if (lastTwo in hostingPlatforms) return labels.takeLast(3).joinToString(".") to lastTwo
        if (lastTwo in twoLabelSuffixes) return labels.takeLast(3).joinToString(".") to null
        return lastTwo to null
    }

    private fun isOfficial(site: String, brand: Brand): Boolean {
        if (site in brand.sites) return true
        // The brand's own name in a country zone, like amazon.de or yandex.kz.
        val parts = site.split('.')
        if (parts.size != 2 || parts[0] != brand.name) return false
        return parts[1].length == 2 || parts[1] in listOf("com", "net", "org")
    }

    /**
     * Whether a label carries the brand as a word of its own: "paypal",
     * "paypal-login", "appleid". Short names only count as whole words.
     */
    private fun mentions(label: String, brand: Brand): Boolean {
        val name = skeleton(brand.name)
        return label.split('-', '_').filter { it.isNotEmpty() }.map { skeleton(it) }
            .any { it == name || (name.length >= 5 && it.startsWith(name)) }
    }

    // Letters

    private enum class Script { LATIN, CYRILLIC, GREEK, OTHER }

    private fun script(char: Char): Script? {
        if (!char.isLetter()) return null
        return when (char.code) {
            in 0x41..0x24F -> Script.LATIN
            in 0x370..0x3FF -> Script.GREEK
            in 0x400..0x52F -> Script.CYRILLIC
            else -> Script.OTHER
        }
    }

    /** Letters that pass for Latin ones, and digits that pass for letters. */
    private val confusables: Map<Char, Char> = mapOf(
        'а' to 'a', 'с' to 'c', 'е' to 'e', 'ё' to 'e', 'о' to 'o', 'р' to 'p', 'х' to 'x', 'у' to 'y', 'ѕ' to 's', 'і' to 'l', 'ї' to 'l', 'ј' to 'j', 'ӏ' to 'l', 'ԁ' to 'd', 'һ' to 'h', 'ԛ' to 'q', 'ԝ' to 'w', 'ү' to 'y', 'к' to 'k', 'α' to 'a', 'ο' to 'o', 'ν' to 'v', 'ρ' to 'p', 'ι' to 'l', 'κ' to 'k', 'υ' to 'u', 'χ' to 'x', 'ε' to 'e', '0' to 'o', '1' to 'l', '3' to 'e', '5' to 's', 'i' to 'l', '|' to 'l',
    )

    /** A name reduced to the shapes a reader sees: "pаypa1" and "paypal" match. */
    fun skeleton(text: String): String =
        text.lowercase().map { confusables[it] ?: it }.joinToString("").replace("rn", "m").replace("vv", "w")

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun isIpAddress(host: String): Boolean {
        if (host.startsWith("[")) return true
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } }) return true
        // A whole address as one number, decimal or hex: http://3232235777/
        if (parts.size == 1 && host.isNotEmpty()) {
            val hex = host.lowercase().startsWith("0x") && host.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            return host.all { it.isDigit() } || hex
        }
        return false
    }

    /** RFC 3492. Takes a label without its "xn--" prefix. */
    fun punycodeDecode(input: String): String? {
        val base = 36
        val tMin = 1
        val tMax = 26
        val skew = 38
        val damp = 700
        var n = 128L
        var i = 0L
        var bias = 72
        val output = mutableListOf<Int>()
        var position = 0
        val delimiter = input.lastIndexOf('-')
        if (delimiter >= 0) {
            for (char in input.substring(0, delimiter)) {
                if (char.code > 0x7F) return null
                output.add(char.code)
            }
            position = delimiter + 1
        }
        fun adapt(delta: Long, points: Int, first: Boolean): Int {
            var d = if (first) delta / damp else delta / 2
            d += d / points
            var k = 0
            while (d > ((base - tMin) * tMax) / 2) {
                d /= (base - tMin)
                k += base
            }
            return (k + (base - tMin + 1) * d / (d + skew)).toInt()
        }
        while (position < input.length) {
            val oldI = i
            var w = 1L
            var k = base
            while (true) {
                if (position >= input.length) return null
                val value = input[position].code
                position += 1
                val digit = when (value) {
                    in 0x30..0x39 -> value - 22
                    in 0x41..0x5A -> value - 0x41
                    in 0x61..0x7A -> value - 0x61
                    else -> return null
                }
                i += digit * w
                val t = if (k <= bias) tMin else if (k >= bias + tMax) tMax else k - bias
                if (digit < t) break
                w *= (base - t)
                k += base
                if (w >= 1L shl 40 || i >= 1L shl 40) return null
            }
            bias = adapt(i - oldI, output.size + 1, oldI == 0L)
            n += i / (output.size + 1)
            i %= (output.size + 1)
            if (n > 0x10FFFF || n in 0xD800..0xDFFF) return null
            output.add(i.toInt(), n.toInt())
            i += 1
        }
        return buildString { output.forEach { appendCodePoint(it) } }
    }

    // Lists

    class Brand(val name: String, val display: String, val sites: List<String>)

    val brands: List<Brand> = listOf(
        Brand("apple", "Apple", listOf("apple.com", "icloud.com", "me.com", "apple.news")),
        Brand("icloud", "iCloud", listOf("icloud.com", "apple.com")),
        Brand("google", "Google", listOf("google.com", "gmail.com", "youtube.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "google-analytics.com", "googlemail.com", "g.co", "goo.gl")),
        Brand("gmail", "Gmail", listOf("gmail.com", "google.com")),
        Brand("youtube", "YouTube", listOf("youtube.com", "youtu.be", "google.com")),
        Brand("microsoft", "Microsoft", listOf("microsoft.com", "microsoftonline.com", "live.com", "office.com", "outlook.com", "office365.com", "sharepoint.com", "onmicrosoft.com", "msn.com", "bing.com", "azure.com", "windows.net")),
        Brand("outlook", "Outlook", listOf("outlook.com", "live.com", "office.com", "microsoft.com")),
        Brand("office365", "Microsoft 365", listOf("office.com", "office365.com", "microsoft.com")),
        Brand("sharepoint", "SharePoint", listOf("sharepoint.com", "microsoft.com")),
        Brand("paypal", "PayPal", listOf("paypal.com", "paypal.me", "paypalobjects.com")),
        Brand("amazon", "Amazon", listOf("amazon.com", "amazon.co.uk", "amazonaws.com")),
        Brand("netflix", "Netflix", listOf("netflix.com")),
        Brand("facebook", "Facebook", listOf("facebook.com", "fb.com", "fb.me", "messenger.com", "meta.com")),
        Brand("instagram", "Instagram", listOf("instagram.com")),
        Brand("whatsapp", "WhatsApp", listOf("whatsapp.com", "whatsapp.net", "wa.me")),
        Brand("telegram", "Telegram", listOf("telegram.org", "t.me", "telegram.me", "telegra.ph")),
        Brand("linkedin", "LinkedIn", listOf("linkedin.com", "lnkd.in")),
        Brand("dropbox", "Dropbox", listOf("dropbox.com", "dropboxusercontent.com")),
        Brand("docusign", "DocuSign", listOf("docusign.com", "docusign.net")),
        Brand("adobe", "Adobe", listOf("adobe.com")),
        Brand("binance", "Binance", listOf("binance.com")),
        Brand("coinbase", "Coinbase", listOf("coinbase.com")),
        Brand("metamask", "MetaMask", listOf("metamask.io")),
        Brand("steamcommunity", "Steam", listOf("steamcommunity.com", "steampowered.com")),
        Brand("steampowered", "Steam", listOf("steampowered.com", "steamcommunity.com")),
        Brand("spotify", "Spotify", listOf("spotify.com")),
        Brand("tiktok", "TikTok", listOf("tiktok.com")),
        Brand("booking", "Booking.com", listOf("booking.com")),
        Brand("airbnb", "Airbnb", listOf("airbnb.com")),
        Brand("dhl", "DHL", listOf("dhl.com", "dhl.de")),
        Brand("fedex", "FedEx", listOf("fedex.com")),
        Brand("yandex", "Yandex", listOf("yandex.ru", "yandex.com", "ya.ru", "yandex.net")),
        Brand("gosuslugi", "Gosuslugi", listOf("gosuslugi.ru")),
        Brand("sberbank", "Sberbank", listOf("sberbank.ru", "sber.ru", "sberbank.com")),
        Brand("sber", "Sber", listOf("sber.ru", "sberbank.ru", "sberbank.com")),
        Brand("tinkoff", "T-Bank", listOf("tinkoff.ru", "tbank.ru")),
        Brand("tbank", "T-Bank", listOf("tbank.ru", "tinkoff.ru")),
        Brand("alfabank", "Alfa-Bank", listOf("alfabank.ru", "alfabank.com")),
        Brand("vtb", "VTB", listOf("vtb.ru", "vtb.com")),
        Brand("raiffeisen", "Raiffeisen", listOf("raiffeisen.ru", "raiffeisen.com")),
        Brand("ozon", "Ozon", listOf("ozon.ru", "ozon.com")),
        Brand("wildberries", "Wildberries", listOf("wildberries.ru", "wb.ru")),
        Brand("avito", "Avito", listOf("avito.ru")),
        Brand("pochta", "Russian Post", listOf("pochta.ru")),
        Brand("cdek", "CDEK", listOf("cdek.ru")),
        Brand("nalog", "the Federal Tax Service", listOf("nalog.gov.ru", "nalog.ru")),
        Brand("vkontakte", "VK", listOf("vk.com", "vk.ru", "vkontakte.ru")),
    )

    private val shorteners = setOf(
        "bit.ly", "bitly.com", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "cutt.ly", "rebrand.ly",
        "clck.ru", "vk.cc", "u.to", "goo.su", "tiny.one", "shorturl.at", "rb.gy", "t.ly", "s.id", "surl.li", "tiny.cc",
        "v.gd", "qps.ru", "bit.do", "shorturl.com", "urlr.me", "lnk.to",
    )

    /** Hosts where every subdomain is a different owner, for free. */
    private val hostingPlatforms = setOf(
        "github.io", "gitlab.io", "herokuapp.com", "vercel.app", "netlify.app", "pages.dev", "workers.dev", "web.app",
        "firebaseapp.com", "appspot.com", "blogspot.com", "glitch.me", "repl.co", "replit.app", "wixsite.com",
        "weebly.com", "tilda.ws", "webflow.io", "framer.app", "framer.website", "notion.site", "square.site",
        "godaddysites.com", "mystrikingly.com", "carrd.co", "r2.dev", "ngrok-free.app", "ngrok.io", "ngrok.app",
        "azurewebsites.net", "cloudfront.net", "onrender.com", "fly.dev", "surge.sh", "000webhostapp.com", "ucoz.ru",
        "narod.ru", "ipfs.dweb.link", "w3s.link",
    )

    /** Public suffixes of two labels; the full list has thousands, these are the common ones. */
    private val twoLabelSuffixes = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp", "ne.jp",
        "or.jp", "com.br", "com.cn", "com.hk", "com.tw", "com.sg", "com.my", "com.tr", "com.ua", "kiev.ua", "com.kz",
        "org.kz", "co.in", "net.in", "co.za", "com.mx", "com.ar", "com.co", "co.kr", "co.il", "com.ru", "net.ru",
        "org.ru", "pp.ru", "msk.ru", "spb.ru", "gov.ru", "com.by", "gov.by", "com.pl", "co.th", "com.vn", "com.ph",
        "com.pk", "com.eg", "com.sa", "com.ng", "co.ke",
    )

    private val riskyZones = setOf(
        "zip", "mov", "xyz", "top", "icu", "click", "link", "cam", "rest", "sbs", "cfd", "quest", "gq", "ml", "cf",
        "tk", "ga", "buzz", "monster", "cyou", "lol", "bond", "win", "loan", "work", "country", "kim", "men", "date",
        "faith", "racing", "review", "science", "stream", "party", "trade", "accountant", "cricket", "download", "gdn",
        "mom", "bar", "beauty", "hair", "skin", "makeup", "autos", "boats", "homes", "yachts",
    )

    private val cyrillicZones = setOf(
        "рф", "рус", "бел", "укр", "қаз", "срб", "мкд", "мон", "москва", "онлайн", "сайт", "login", "logon", "signin",
        "signon", "verify", "verification", "secure", "security", "account", "accounts", "update", "confirm", "unlock",
        "wallet", "bonus", "bonuses", "prize", "gift", "giftcard", "reward", "rewards", "refund", "claim", "support",
        "auth", "password", "billing", "invoice", "payment", "pay", "payout", "recover", "recovery", "airdrop",
        "kompensaciya", "kompensacia", "vyplata", "vyplaty", "vozvrat", "priz", "podarok",
    )

    private val baitWords = setOf(
        "login", "logon", "signin", "signon", "verify", "verification", "secure", "security", "account", "accounts",
        "update", "confirm", "unlock", "wallet", "bonus", "bonuses", "prize", "gift", "giftcard", "reward", "rewards",
        "refund", "claim", "support", "auth", "password", "billing", "invoice", "payment", "pay", "payout", "recover",
        "recovery", "airdrop", "kompensaciya", "kompensacia", "vyplata", "vyplaty", "vozvrat", "priz", "podarok",
    )

    private val redirectParameters = setOf(
        "url", "u", "q", "to", "target", "redirect", "redirect_uri", "redirect_url", "redirecturl", "return",
        "returnurl", "return_to", "next", "continue", "dest", "destination", "link", "goto", "r", "out", "away",
    )
}
