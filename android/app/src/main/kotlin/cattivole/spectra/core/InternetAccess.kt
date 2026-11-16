package cattivole.spectra.core

import org.json.JSONArray
import org.json.JSONObject

/** Which connection a check goes out through. */
enum class ProbeRoute {
    /** Wi-Fi or Ethernet, around any VPN. */
    WIFI,

    /** The mobile network, even while Wi-Fi is joined. */
    CELLULAR,

    /** Whatever the system uses, VPN included. */
    VPN,
}

/** Whether one site answered over HTTPS, and how fast. */
data class ResourceReach(val host: String, val ok: Boolean, val millis: Int?)

/** The traffic light: what this connection lets through. */
enum class InternetAccess {
    /** Foreign sites open and so do the services that are often blocked. */
    FULL,

    /** The internet works, but services that are often blocked do not open. */
    FILTERED,

    /** Only sites from the provider's allow-list open; the rest of the internet does not. */
    WHITELIST_ONLY,

    /** Nothing answers. */
    NONE,
}

/**
 * What the reference sites said. [open] are sites outside any allow-list;
 * [listed] are the ones a Russian allow-list keeps reachable when mobile
 * internet is restricted; [blocked] are services that are often blocked one
 * by one. Mirrors the iOS `AccessReport`.
 */
data class AccessReport(
    val open: List<ResourceReach>,
    val listed: List<ResourceReach>,
    /** Null in checks saved before blocked services were asked. */
    val blocked: List<ResourceReach>? = null,
) {
    val verdict: InternetAccess
        get() = AccessCheck.verdict(open.count { it.ok }, listed.count { it.ok }, blocked.orEmpty().count { !it.ok })

    /** The often-blocked services that did not open. */
    val unreachable: List<String> get() = blocked.orEmpty().filter { !it.ok }.map { it.host }

    fun toJson(): JSONObject = JSONObject().put("open", rows(open)).put("listed", rows(listed))
        .apply { blocked?.let { put("blocked", rows(it)) } }

    companion object {
        fun fromJson(json: JSONObject): AccessReport = AccessReport(
            reaches(json.optJSONArray("open")),
            reaches(json.optJSONArray("listed")),
            json.optJSONArray("blocked")?.let(::reaches),
        )

        private fun rows(items: List<ResourceReach>) = JSONArray().apply {
            items.forEach { put(JSONObject().put("host", it.host).put("ok", it.ok).putOpt("millis", it.millis)) }
        }

        private fun reaches(array: JSONArray?): List<ResourceReach> = (0 until (array?.length() ?: 0)).mapNotNull { index ->
            val item = array?.optJSONObject(index) ?: return@mapNotNull null
            ResourceReach(item.optString("host"), item.optBoolean("ok"), if (item.has("millis") && !item.isNull("millis")) item.optInt("millis") else null)
        }
    }
}

/** Keep the hosts and the rule in step with the iOS `AccessCheck`. */
object AccessCheck {
    /** Large foreign sites that an allow-list does not carry. */
    val openHosts = listOf("www.google.com", "www.cloudflare.com", "www.wikipedia.org", "github.com", "www.microsoft.com")

    /** Sites the allow-list keeps reachable. */
    val listedHosts = listOf("ya.ru", "vk.com", "www.gosuslugi.ru", "www.ozon.ru", "mail.ru")

    /**
     * Services that are often blocked one by one while the rest of the
     * internet works. A blocked one fails at the TLS handshake.
     */
    val blockedHosts = listOf("www.youtube.com", "www.instagram.com", "www.facebook.com", "x.com", "discord.com", "www.linkedin.com")

    /** The sites a new list of resources starts with. */
    val defaultResources = listOf("youtube.com", "telegram.org", "whatsapp.com", "instagram.com", "chatgpt.com", "github.com")

    /**
     * Two foreign sites answering is the internet: one alone can be a leak or
     * a cache. Then two often-blocked services failing means it is filtered;
     * one can be a site having a bad minute. With fewer than two foreign
     * answers, any allow-listed site answering means the connection is up but
     * fenced in.
     */
    fun verdict(openReached: Int, listedReached: Int, blockedFailed: Int = 0): InternetAccess = when {
        openReached >= 2 -> if (blockedFailed >= 2) InternetAccess.FILTERED else InternetAccess.FULL
        listedReached >= 1 -> InternetAccess.WHITELIST_ONLY
        openReached == 1 -> if (blockedFailed >= 2) InternetAccess.FILTERED else InternetAccess.FULL
        else -> InternetAccess.NONE
    }

    /**
     * The host of whatever the person typed: a bare name, a link, or a name
     * with a path. Null when there is no usable name in it.
     */
    fun normalize(input: String): String? {
        var text = input.trim().lowercase()
        val scheme = text.indexOf("://")
        if (scheme >= 0) text = text.substring(scheme + 3)
        val at = text.indexOf('@')
        if (at >= 0) text = text.substring(at + 1)
        text = text.takeWhile { it !in "/?#" }
        val colon = text.lastIndexOf(':')
        if (colon >= 0 && text.substring(colon + 1).all { it.isDigit() }) text = text.substring(0, colon)
        text = text.trimEnd('.')
        if ('.' !in text || text.startsWith(".") || text.any { it.isWhitespace() } || text.length > 253) return null
        return text
    }

    /** The saved list: one host per line. */
    fun parseList(saved: String?): List<String> =
        saved?.lines()?.mapNotNull { normalize(it) }?.distinct() ?: defaultResources
}
