package cattivole.spectra.core

/** What the list of requests in a recording is narrowed to. Mirrors the iOS `RequestFilter`. */
data class RequestFilter(
    /** Methods shown; empty shows all. */
    val methods: Set<String> = emptySet(),
    /** Only answers of 400 and above, and requests that got none. */
    val failedOnly: Boolean = false,
    /** Only connections that went over to WebSocket. */
    val webSocketOnly: Boolean = false,
    val query: String = "",
) {
    /** True when a chip is on, so an empty list is the filter's doing. */
    val isNarrowing: Boolean get() = methods.isNotEmpty() || failedOnly || webSocketOnly

    fun toggled(method: String): RequestFilter = copy(methods = if (method in methods) methods - method else methods + method)

    fun passes(exchange: HttpExchange, flow: TrafficFlow): Boolean {
        if (methods.isNotEmpty() && exchange.method.uppercase() !in methods) return false
        if (failedOnly && !failed(exchange, flow)) return false
        if (webSocketOnly && exchange.messages.isEmpty()) return false
        val needle = query.trim()
        return needle.isEmpty() ||
            exchange.summary.contains(needle, ignoreCase = true) ||
            exchange.status?.toString() == needle ||
            (exchange.query + exchange.parameters).any { it.name.contains(needle, true) || it.value.contains(needle, true) } ||
            flow.matches(needle)
    }

    companion object {
        /** The methods with a chip of their own whatever was recorded; others get one when they occur. */
        val commonMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE")

        /** An error answer, or none although the connection is over. One still on its way has not failed. */
        fun failed(exchange: HttpExchange, flow: TrafficFlow): Boolean = exchange.status?.let { it >= 400 } ?: flow.closed

        /** The methods to offer: the common ones, then whatever else was recorded. */
        fun methods(flows: List<TrafficFlow>): List<String> {
            val recorded = flows.flatMapTo(sortedSetOf()) { flow -> flow.exchanges.map { it.method.uppercase() } }
            return commonMethods + (recorded - commonMethods.toSet())
        }
    }
}

/**
 * What the list of connections is narrowed to: what was read in them (HTTP, TLS, DNS), or the transport they went
 * over. The transport takes in everything on it: nearly every TCP connection is also TLS or HTTP, so TCP without
 * them was always empty, and so was UDP without DNS.
 */
enum class ConnectionFilter {
    HTTP, TLS, DNS, TCP, UDP;

    fun passes(flow: TrafficFlow): Boolean = when (this) {
        HTTP -> flow.kind() == TrafficKind.HTTP
        TLS -> flow.kind() == TrafficKind.TLS
        DNS -> flow.kind() == TrafficKind.DNS
        TCP -> flow.protocol == "TCP"
        UDP -> flow.protocol == "UDP"
    }

    companion object {
        /** With no filter DNS lookups stay out: they outnumber everything else and say little. */
        fun passes(filter: ConnectionFilter?, flow: TrafficFlow): Boolean = filter?.passes(flow) ?: (flow.kind() != TrafficKind.DNS)
    }
}

/** Whether a search over a connection finds [needle] in it. */
fun TrafficFlow.matches(needle: String): Boolean = needle.isEmpty() ||
    title.contains(needle, true) ||
    destination.contains(needle, true) ||
    app?.contains(needle, true) == true ||
    request?.contains(needle, true) == true ||
    protocol.contains(needle, true) ||
    destinationPort.toString() == needle
