package cattivole.spectra.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** What the joined network can reach, filled in as a check runs. */
class LinkReport {
    var checkedAt: Long = System.currentTimeMillis()
    var status: LinkStatus? = null
    /** English sentence; the UI translates it. */
    var detail: String? = null
    var gateway: ProbeResult? = null
    var dns: ProbeResult? = null
    var web: ProbeResult? = null
    var external: ExternalAddress? = null
    var speed: SpeedSample? = null
    var speedFailed: Boolean = false
    var regions: List<RegionReach> = emptyList()
    var abroad: List<AbroadView> = emptyList()
    var abroadNote: RemoteNote = RemoteNote.NONE
    var localTrace: LocalTrace? = null
    var remoteTraces: List<RemoteTrace> = emptyList()
    var traceNote: RemoteNote = RemoteNote.NONE
    var tls: TlsCheck? = null
    var resolver: DnsReport? = null
    var exposure: ExposureReport? = null

    /** The traffic light: full access, allow-list only, or nothing. */
    var access: AccessReport? = null
}

/** [VPN_ONLY]: the VPN does not let the check leave by the chosen network. Like [NO_NETWORK], it is never saved. */
enum class LinkStatus { ONLINE, CAPTIVE, LAN, OFFLINE, NO_NETWORK, VPN_ONLY }

/** A finished connection check, kept on the LAN history session for that network. */
data class SavedConnection(
    val checkedAt: Long,
    val viaVpn: Boolean,
    val status: LinkStatus,
    val detail: String?,
    val gateway: ProbeResult?,
    val dns: ProbeResult?,
    val web: ProbeResult?,
    val external: ExternalAddress?,
    val speed: SpeedSample?,
    val speedFailed: Boolean,
    val regions: List<RegionReach>,
    val abroad: List<AbroadView>,
    val abroadNote: RemoteNote,
    val localTrace: LocalTrace?,
    val remoteTraces: List<RemoteTrace>,
    val traceNote: RemoteNote,
    val tls: TlsCheck? = null,
    val resolver: DnsReport? = null,
    val exposure: ExposureReport? = null,
    val access: AccessReport? = null,
    /** The check went out through the mobile network. */
    val viaCellular: Boolean = false,
) {
    fun toReport(): LinkReport = LinkReport().also {
        it.checkedAt = checkedAt
        it.status = status
        it.detail = detail
        it.gateway = gateway
        it.dns = dns
        it.web = web
        it.external = external
        it.speed = speed
        it.speedFailed = speedFailed
        it.regions = regions
        it.abroad = abroad
        it.abroadNote = abroadNote
        it.localTrace = localTrace
        it.remoteTraces = remoteTraces
        it.traceNote = traceNote
        it.tls = tls
        it.resolver = resolver
        it.exposure = exposure
        it.access = access
    }

    fun toJson(): JSONObject = JSONObject()
        .put("checkedAt", checkedAt)
        .put("viaVpn", viaVpn)
        .put("status", status.name)
        .putOpt("detail", detail)
        .putOpt("gateway", gateway?.let(::probeJson))
        .putOpt("dns", dns?.let(::probeJson))
        .putOpt("web", web?.let(::probeJson))
        .putOpt("external", external?.let(::externalJson))
        .putOpt("speed", speed?.let(::speedJson))
        .put("speedFailed", speedFailed)
        .put("regions", JSONArray().apply { regions.forEach { put(regionJson(it)) } })
        .put("abroad", JSONArray().apply { abroad.forEach { put(abroadJson(it)) } })
        .put("abroadNote", abroadNote.name)
        .putOpt("localTrace", localTrace?.let(::localJson))
        .put("remoteTraces", JSONArray().apply { remoteTraces.forEach { put(remoteJson(it)) } })
        .put("traceNote", traceNote.name)
        .putOpt("tls", tls?.let(InspectJson::tls))
        .putOpt("resolver", resolver?.let(InspectJson::dns))
        .putOpt("exposure", exposure?.let(InspectJson::exposure))
        .putOpt("access", access?.toJson())
        .put("viaCellular", viaCellular)

    companion object {
        fun from(report: LinkReport, viaVpn: Boolean, viaCellular: Boolean = false): SavedConnection? {
            val status = report.status ?: return null
            return SavedConnection(
                checkedAt = report.checkedAt,
                viaVpn = viaVpn,
                status = status,
                detail = report.detail,
                gateway = report.gateway,
                dns = report.dns,
                web = report.web,
                external = report.external,
                speed = report.speed,
                speedFailed = report.speedFailed,
                regions = report.regions,
                abroad = report.abroad,
                abroadNote = report.abroadNote,
                localTrace = report.localTrace,
                remoteTraces = report.remoteTraces,
                traceNote = report.traceNote,
                tls = report.tls,
                resolver = report.resolver,
                exposure = report.exposure,
                access = report.access,
                viaCellular = viaCellular,
            )
        }

        fun fromJson(json: JSONObject): SavedConnection? {
            val status = enumOrNull<LinkStatus>(json.optString("status")) ?: return null
            return SavedConnection(
                checkedAt = json.optLong("checkedAt"),
                viaVpn = json.optBoolean("viaVpn"),
                status = status,
                detail = json.optStringOrNull("detail"),
                gateway = json.optJSONObject("gateway")?.let(::probeOf),
                dns = json.optJSONObject("dns")?.let(::probeOf),
                web = json.optJSONObject("web")?.let(::probeOf),
                external = json.optJSONObject("external")?.let(::externalOf),
                speed = json.optJSONObject("speed")?.let(::speedOf),
                speedFailed = json.optBoolean("speedFailed"),
                regions = json.optJSONArray("regions").objects().map(::regionOf),
                abroad = json.optJSONArray("abroad").objects().map(::abroadOf),
                abroadNote = enumOrNull<RemoteNote>(json.optString("abroadNote")) ?: RemoteNote.NONE,
                localTrace = json.optJSONObject("localTrace")?.let(::localOf),
                remoteTraces = json.optJSONArray("remoteTraces").objects().map(::remoteOf),
                traceNote = enumOrNull<RemoteNote>(json.optString("traceNote")) ?: RemoteNote.NONE,
                tls = json.optJSONObject("tls")?.let(InspectJson::tls),
                resolver = json.optJSONObject("resolver")?.let(InspectJson::dns),
                exposure = json.optJSONObject("exposure")?.let(InspectJson::exposure),
                access = json.optJSONObject("access")?.let(AccessReport::fromJson),
                viaCellular = json.optBoolean("viaCellular"),
            )
        }
    }
}

enum class RemoteNote { NONE, BUSY, FAILED }

data class ProbeResult(val name: String, val ok: Boolean, val millis: Int?)

data class ExternalAddress(
    val ip: String,
    val countryCode: String? = null,
    val city: String? = null,
    val isp: String? = null,
    val asn: String? = null,
    val colo: String? = null,
    val warp: Boolean = false,
)

data class SpeedSample(val latencyMs: Int?, val downBps: Double?, val upBps: Double?)

data class RegionReach(val code: String, val host: String, val ok: Boolean, val millis: Int?)

data class AbroadView(val code: String, val city: String, val ok: Boolean, val millis: Int?)

data class TraceHop(
    val address: String?,
    val millis: Int?,
    /** Reverse DNS of the address, when it has one. */
    val name: String? = null,
)

data class LocalTrace(val target: String, val hops: List<TraceHop>, val unavailable: Boolean)

data class RemoteTrace(val code: String, val city: String, val hops: List<TraceHop>, val reached: Boolean)

data class CheckNode(val id: String, val code: String, val city: String)

sealed class CheckStart {
    data class Ok(val id: String, val nodes: List<CheckNode>) : CheckStart()
    data object Busy : CheckStart()
    data object Failed : CheckStart()
}

data class PingReply(
    val address: String?,
    val millis: Int?,
    val reached: Boolean,
    val denied: Boolean,
    val unreachable: Boolean,
)

/** Parsers for the replies a connection check collects. No network of their own. */
object LinkParse {
    /**
     * A check-tcp result for one node: true when it connected, false when it
     * could not, null while the node is still working.
     */
    fun tcpOpen(json: String, node: String): Boolean? {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        if (!root.has(node) || root.isNull(node)) return null
        val first = root.optJSONArray(node)?.optJSONObject(0) ?: return false
        return first.has("time") && !first.has("error")
    }

    /** `https://1.1.1.1/cdn-cgi/trace` — ip, loc, colo, warp. */
    fun externalFromTrace(text: String): ExternalAddress? {
        val map = text.lineSequence().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1).trim()
        }.toMap()
        val ip = map["ip"]?.takeIf { it.isNotBlank() } ?: return null
        return ExternalAddress(
            ip = ip,
            countryCode = map["loc"]?.takeIf { it.length == 2 },
            colo = map["colo"]?.takeIf { it.isNotBlank() },
            warp = map["warp"] == "on",
        )
    }

    /** Fills city, provider and country from an ipwho.is object, keeping the trace fields. */
    fun mergeWhois(json: String, current: ExternalAddress): ExternalAddress {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return current
        }
        if (root.has("success") && !root.optBoolean("success")) return current
        val connection = root.optJSONObject("connection")
        val asn = connection?.optInt("asn")?.takeIf { it > 0 }?.let { "AS$it" }
        val isp = connection?.optString("isp")?.takeIf { it.isNotBlank() }
            ?: connection?.optString("org")?.takeIf { it.isNotBlank() }
        return current.copy(
            countryCode = root.optString("country_code").takeIf { it.length == 2 } ?: current.countryCode,
            city = root.optString("city").takeIf { it.isNotBlank() } ?: current.city,
            isp = isp ?: current.isp,
            asn = asn ?: current.asn,
        )
    }

    fun checkStart(json: String): CheckStart {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return CheckStart.Failed
        }
        if (root.optString("error") == "limit_exceeded") return CheckStart.Busy
        val id = root.optString("request_id")
        if (id.isBlank() || root.optInt("ok") != 1) return CheckStart.Failed
        val nodesJson = root.optJSONObject("nodes") ?: return CheckStart.Ok(id, emptyList())
        val nodes = nodesJson.keys().asSequence().map { key ->
            val place = nodesJson.optJSONArray(key)
            CheckNode(
                id = key,
                code = place?.optString(0).orEmpty(),
                city = place?.optString(2).orEmpty(),
            )
        }.toList()
        return CheckStart.Ok(id, nodes)
    }

    /**
     * Ping results from check-host. Times in that API are seconds.
     * A null node is still working; `complete` is false until every node has answered.
     */
    fun abroad(json: String, nodes: List<CheckNode>): Pair<Boolean, List<AbroadView>>? {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        var complete = true
        val views = mutableListOf<AbroadView>()
        for (node in nodes) {
            if (!root.has(node.id) || root.isNull(node.id)) {
                complete = false
                continue
            }
            val pings = root.optJSONArray(node.id)?.optJSONArray(0)
            if (pings == null) {
                views += AbroadView(node.code, node.city, ok = false, millis = null)
                continue
            }
            var best: Int? = null
            var ok = false
            for (i in 0 until pings.length()) {
                val ping = pings.optJSONArray(i) ?: continue
                if (ping.optString(0) != "OK") continue
                ok = true
                val ms = secondsToMillis(ping.opt(1)) ?: continue
                best = minOf(best ?: ms, ms)
            }
            views += AbroadView(node.code, node.city, ok, if (ok) best else null)
        }
        return complete to views
    }

    /**
     * Traceroutes from check-host. `query_times` are already milliseconds, unlike ping.
     * [target] is the address the trace was aimed at.
     */
    fun traces(json: String, nodes: List<CheckNode>, target: String): Pair<Boolean, List<RemoteTrace>>? {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        var complete = true
        val traces = mutableListOf<RemoteTrace>()
        for (node in nodes) {
            if (!root.has(node.id) || root.isNull(node.id)) {
                complete = false
                continue
            }
            val hopsArray = root.optJSONArray(node.id)?.optJSONArray(0)
            val hops = hopsOf(hopsArray)
            val reached = hops.any { it.address == target }
            traces += RemoteTrace(node.code, node.city, hops, reached)
        }
        return complete to traces
    }

    fun compactPath(hops: List<TraceHop>, limit: Int = 5): String {
        val collapsed = mutableListOf<String>()
        for (hop in hops) {
            val part = hop.address ?: "*"
            if (part == "*" && collapsed.lastOrNull() == "*") continue
            collapsed += part
        }
        if (collapsed.isEmpty()) return ""
        if (collapsed.size <= limit) return collapsed.joinToString(" → ")
        return (collapsed.take(2) + "…" + collapsed.takeLast(2)).joinToString(" → ")
    }

    /** One hop of `ping -c 1 -t <ttl>`. A missing address is a silent hop. */
    fun pingOutput(output: String): PingReply {
        val denied = output.contains("not permitted", ignoreCase = true) ||
            output.contains("permission denied", ignoreCase = true) ||
            output.contains("unknown option", ignoreCase = true) ||
            output.contains("invalid option", ignoreCase = true) ||
            output.contains("bad option", ignoreCase = true)
        if (denied) return PingReply(null, null, reached = false, denied = true, unreachable = false)
        val millis = Regex("""time[=<]\s*([0-9.]+)\s*ms""", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.get(1)?.toDoubleOrNull()?.roundToInt()
        // The header names the phone's own address when ping is bound to an interface
        // ("PING 1.1.1.1 (1.1.1.1) from 192.168.1.114 wlan0: …"): only the reply lines name the hop.
        val replies = output.lineSequence().filterNot { it.trimStart().startsWith("PING", ignoreCase = true) }.joinToString("\n")
        val address = Regex("""(?i)\bfrom\s+(\d{1,3}(?:\.\d{1,3}){3})""")
            .find(replies)?.groupValues?.get(1)
        val reached = output.contains("bytes from", ignoreCase = true) &&
            !output.contains("time to live exceeded", ignoreCase = true)
        val unreachable = output.contains("unreachable", ignoreCase = true)
        return PingReply(address, millis, reached, denied = false, unreachable)
    }

    private fun hopsOf(hops: JSONArray?): List<TraceHop> {
        if (hops == null) return emptyList()
        return buildList {
            for (i in 0 until hops.length()) {
                val replies = hops.optJSONArray(i)
                var address: String? = null
                var millis: Int? = null
                if (replies != null) {
                    for (j in 0 until replies.length()) {
                        val reply = replies.optJSONObject(j) ?: continue
                        val host = reply.optString("host").takeIf { it.isNotBlank() } ?: continue
                        address = host
                        millis = firstMillis(reply.optJSONArray("query_times"))
                        break
                    }
                }
                add(TraceHop(address, millis))
            }
        }
    }

    private fun firstMillis(times: JSONArray?): Int? {
        if (times == null) return null
        for (i in 0 until times.length()) {
            val ms = millisValue(times.opt(i)) ?: continue
            return ms
        }
        return null
    }

    /** check-host traceroute times are milliseconds. */
    private fun millisValue(raw: Any?): Int? = when (raw) {
        is Number -> raw.toDouble().roundToInt()
        is String -> raw.toDoubleOrNull()?.roundToInt()
        else -> null
    }

    /** check-host ping times are seconds. */
    private fun secondsToMillis(raw: Any?): Int? {
        val seconds = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        } ?: return null
        return (seconds * 1000).roundToInt().coerceAtLeast(1)
    }
}

private fun probeJson(probe: ProbeResult) = JSONObject()
    .put("name", probe.name)
    .put("ok", probe.ok)
    .apply { probe.millis?.let { put("millis", it) } }

private fun probeOf(json: JSONObject) = ProbeResult(
    json.optString("name"),
    json.optBoolean("ok"),
    if (json.has("millis")) json.optInt("millis") else null,
)

private fun externalJson(address: ExternalAddress) = JSONObject()
    .put("ip", address.ip)
    .put("warp", address.warp)
    .putOpt("countryCode", address.countryCode)
    .putOpt("city", address.city)
    .putOpt("isp", address.isp)
    .putOpt("asn", address.asn)
    .putOpt("colo", address.colo)

private fun externalOf(json: JSONObject): ExternalAddress? {
    val ip = json.optString("ip").takeIf { it.isNotBlank() } ?: return null
    return ExternalAddress(
        ip = ip,
        countryCode = json.optStringOrNull("countryCode"),
        city = json.optStringOrNull("city"),
        isp = json.optStringOrNull("isp"),
        asn = json.optStringOrNull("asn"),
        colo = json.optStringOrNull("colo"),
        warp = json.optBoolean("warp"),
    )
}

private fun speedJson(speed: SpeedSample) = JSONObject().apply {
    speed.latencyMs?.let { put("latencyMs", it) }
    speed.downBps?.let { put("downBps", it) }
    speed.upBps?.let { put("upBps", it) }
}

private fun speedOf(json: JSONObject) = SpeedSample(
    if (json.has("latencyMs")) json.optInt("latencyMs") else null,
    if (json.has("downBps")) json.optDouble("downBps") else null,
    if (json.has("upBps")) json.optDouble("upBps") else null,
)

private fun regionJson(region: RegionReach) = JSONObject()
    .put("code", region.code)
    .put("host", region.host)
    .put("ok", region.ok)
    .apply { region.millis?.let { put("millis", it) } }

private fun regionOf(json: JSONObject) = RegionReach(
    json.optString("code"),
    json.optString("host"),
    json.optBoolean("ok"),
    if (json.has("millis")) json.optInt("millis") else null,
)

private fun abroadJson(view: AbroadView) = JSONObject()
    .put("code", view.code)
    .put("city", view.city)
    .put("ok", view.ok)
    .apply { view.millis?.let { put("millis", it) } }

private fun abroadOf(json: JSONObject) = AbroadView(
    json.optString("code"),
    json.optString("city"),
    json.optBoolean("ok"),
    if (json.has("millis")) json.optInt("millis") else null,
)

private fun hopJson(hop: TraceHop) = JSONObject().apply {
    hop.address?.let { put("address", it) }
    hop.millis?.let { put("millis", it) }
    hop.name?.let { put("name", it) }
}

private fun hopOf(json: JSONObject) = TraceHop(
    json.optStringOrNull("address"),
    if (json.has("millis")) json.optInt("millis") else null,
    json.optStringOrNull("name"),
)

private fun localJson(trace: LocalTrace) = JSONObject()
    .put("target", trace.target)
    .put("unavailable", trace.unavailable)
    .put("hops", JSONArray().apply { trace.hops.forEach { put(hopJson(it)) } })

private fun localOf(json: JSONObject) = LocalTrace(
    json.optString("target"),
    json.optJSONArray("hops").objects().map(::hopOf),
    json.optBoolean("unavailable"),
)

private fun remoteJson(trace: RemoteTrace) = JSONObject()
    .put("code", trace.code)
    .put("city", trace.city)
    .put("reached", trace.reached)
    .put("hops", JSONArray().apply { trace.hops.forEach { put(hopJson(it)) } })

private fun remoteOf(json: JSONObject) = RemoteTrace(
    json.optString("code"),
    json.optString("city"),
    json.optJSONArray("hops").objects().map(::hopOf),
    json.optBoolean("reached"),
)

private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
