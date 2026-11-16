package cattivole.spectra.core

import java.net.URI

/**
 * One rule in the router's port-forwarding table: a port on the public
 * address that leads to a host on this network. Mirrors the iOS `PortMapping`.
 */
data class PortMapping(
    val externalPort: Int,
    val internalPort: Int,
    val internalClient: String,
    /** "TCP" or "UDP". */
    val proto: String,
    val description: String? = null,
    val enabled: Boolean = true,
    /** Seconds left on the rule; 0 means it stays until removed. */
    val leaseSeconds: Int? = null,
    /** Only this remote address may use the rule, when set. */
    val remoteHost: String? = null,
)

/** What the router said when asked for its forwardings over UPnP. */
data class PortForwarding(
    val checkedAt: Long,
    /** The router answered UPnP and exposed a WAN connection service. */
    val supported: Boolean,
    val externalIp: String? = null,
    val mappings: List<PortMapping> = emptyList(),
)

/** The UPnP gateway conversation, as bytes and parsers. Pure, for tests. */
object GatewayControl {
    /** A WAN connection service found in a gateway description. */
    data class Service(val serviceType: String, val controlUrl: String)

    data class Outcome(val status: Int?, val errorCode: Int?, val body: String)

    /** UPnP's "no such entry": the end of the table. */
    const val INDEX_INVALID = 713

    /**
     * M-SEARCH for a gateway only, so a router that answers ssdp:all with a
     * dozen replies gives the one that matters.
     */
    fun ssdpGatewaySearch(host: String): ByteArray =
        ("M-SEARCH * HTTP/1.1\r\nHOST: $host:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
            "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n").encodeToByteArray()

    /** WANIPConnection and WANPPPConnection services in a device description, in document order. */
    fun services(xml: String): List<Service> {
        val found = mutableListOf<Service>()
        for (block in xml.split("<service>").drop(1)) {
            val service = block.substringBefore("</service>")
            val type = LanIdentity.tag("serviceType", service) ?: continue
            if ("WANIPConnection" !in type && "WANPPPConnection" !in type) continue
            val control = LanIdentity.tag("controlURL", service) ?: continue
            found.add(Service(type, control))
        }
        return found
    }

    /** The control URL made absolute against the description's location, or the document's URLBase. */
    fun resolveControlUrl(control: String, location: String, xml: String): URI? = try {
        val absolute = URI(control)
        if (absolute.scheme != null) {
            absolute
        } else {
            val base = LanIdentity.tag("URLBase", xml)?.let { runCatching { URI(it) }.getOrNull() } ?: URI(location)
            base.resolve(control)
        }
    } catch (_: Exception) {
        null
    }

    /** A SOAP action for a WAN connection service, as the bytes to send. */
    fun soapRequest(url: URI, serviceType: String, action: String, arguments: List<Pair<String, String>> = emptyList()): ByteArray {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$serviceType\">" +
            arguments.joinToString("") { "<${it.first}>${it.second}</${it.first}>" } +
            "</u:$action></s:Body></s:Envelope>"
        val port = if (url.port > 0) ":${url.port}" else ""
        val path = (url.rawPath?.ifEmpty { "/" } ?: "/") + (url.rawQuery?.let { "?$it" } ?: "")
        val head = "POST $path HTTP/1.1\r\nHost: ${url.host.orEmpty()}$port\r\nUser-Agent: Spectra\r\n" +
            "Content-Type: text/xml; charset=\"utf-8\"\r\nSOAPAction: \"$serviceType#$action\"\r\n" +
            "Content-Length: ${body.encodeToByteArray().size}\r\nConnection: close\r\n\r\n"
        return (head + body).encodeToByteArray()
    }

    /** The HTTP status of a SOAP reply and its UPnP error code, when it has one. */
    fun soapOutcome(data: ByteArray): Outcome {
        val message = HeaderText.parse(data.toString(Charsets.UTF_8))
        val status = message.start.split(' ').getOrNull(1)?.toIntOrNull()
        return Outcome(status, LanIdentity.tag("errorCode", message.body)?.toIntOrNull(), message.body)
    }

    /** One GetGenericPortMappingEntry reply, or null when it is not one. */
    fun parsePortMapping(body: String): PortMapping? {
        val external = LanIdentity.tag("NewExternalPort", body)?.toIntOrNull() ?: return null
        val client = LanIdentity.tag("NewInternalClient", body) ?: return null
        return PortMapping(
            externalPort = external,
            internalPort = LanIdentity.tag("NewInternalPort", body)?.toIntOrNull() ?: external,
            internalClient = client,
            proto = LanIdentity.tag("NewProtocol", body)?.uppercase() ?: "TCP",
            description = LanIdentity.tag("NewPortMappingDescription", body),
            enabled = LanIdentity.tag("NewEnabled", body)?.let { it != "0" && it.lowercase() != "false" } ?: true,
            leaseSeconds = LanIdentity.tag("NewLeaseDuration", body)?.toIntOrNull(),
            remoteHost = LanIdentity.tag("NewRemoteHost", body),
        )
    }
}
