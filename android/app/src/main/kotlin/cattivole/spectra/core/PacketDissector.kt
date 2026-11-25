package cattivole.spectra.core

/**
 * A best-effort reading of one packet: enough to summarise it in a list and
 * count it in the stats. Deliberately shallow — common link, network and
 * transport layers, plus a guess at the application from well-known ports.
 * Malformed offsets fall back to a short reading rather than throwing.
 * Mirrors the iOS `PacketDissector`.
 */
class PacketInfo(
    val transport: Transport,
    val source: String,
    val destination: String,
    val app: String?,
    val info: String,
    val netSource: String?,
    val netDestination: String?,
    /** The WPA/WPA2 4-way handshake message this frame carries (1..4), when it is
     *  an EAPOL-Key frame of a pairwise handshake; null otherwise. */
    val eapolMessage: Int? = null,
)

enum class Transport(val label: String) {
    TCP("TCP"),
    UDP("UDP"),
    ICMP("ICMP"),
    ICMPV6("ICMPv6"),
    IGMP("IGMP"),
    ARP("ARP"),
    IP_OTHER("IP"),
    DOT11_MGMT("802.11 mgmt"),
    DOT11_CTRL("802.11 ctrl"),
    DOT11_DATA("802.11 data"),
    OTHER("Other"),
}

object PacketDissector {
    fun dissect(linkType: LinkType, packet: ByteArray): PacketInfo = when (linkType) {
        LinkType.ETHERNET -> ethernet(packet)
        LinkType.IEEE802_11 -> dot11(packet)
        LinkType.IEEE802_11_RADIOTAP -> radiotap(packet)
        LinkType.RAW, LinkType.IPV4, LinkType.IPV6 -> rawIP(packet)
        LinkType.NULL -> nullLoopback(packet)
        LinkType.LINUX_SLL -> linuxSll(packet)
        LinkType.LINUX_SLL2 -> linuxSll2(packet)
        else -> PacketInfo(Transport.OTHER, "—", "—", null, linkType.displayName, null, null)
    }

    // Link layer

    private fun ethernet(p: ByteArray): PacketInfo {
        if (p.size < 14) return short()
        val dst = mac(p, 0)
        val src = mac(p, 6)
        var etherType = p.be16(12)
        var l3 = 14
        while (etherType == 0x8100 || etherType == 0x88A8) { // VLAN / Q-in-Q
            if (p.size < l3 + 4) break
            etherType = p.be16(l3 + 2)
            l3 += 4
        }
        return afterL2(etherType, p, l3, src, dst)
    }

    private fun linuxSll(p: ByteArray): PacketInfo {
        if (p.size < 16) return short()
        return afterL2(p.be16(14), p, 16, "—", "—")
    }

    private fun linuxSll2(p: ByteArray): PacketInfo {
        if (p.size < 20) return short()
        return afterL2(p.be16(0), p, 20, "—", "—")
    }

    private fun nullLoopback(p: ByteArray): PacketInfo {
        if (p.size < 4) return short()
        val host = p.u8(0).toLong() or (p.u8(1).toLong() shl 8) or (p.u8(2).toLong() shl 16) or (p.u8(3).toLong() shl 24)
        return when (host) {
            2L -> ipv4(p, 4)
            24L, 28L, 30L -> ipv6(p, 4)
            else -> if (p.be32(0) == 2L) ipv4(p, 4) else rawIP(p.copyOfRange(4, p.size))
        }
    }

    private fun radiotap(p: ByteArray): PacketInfo {
        if (p.size < 4) return short()
        val length = p.u8(2) or (p.u8(3) shl 8) // radiotap length is little-endian
        if (length < 4 || length >= p.size) return short()
        return dot11(p.copyOfRange(length, p.size))
    }

    private fun afterL2(etherType: Int, p: ByteArray, l3: Int, l2src: String, l2dst: String): PacketInfo =
        when (etherType) {
            0x0800 -> ipv4(p, l3)
            0x86DD -> ipv6(p, l3)
            0x0806 -> arp(p, l3, l2src)
            0x888E -> eapol(p, l3, l2src, l2dst) // 802.1X / EAPOL, e.g. a wired WPA key exchange
                ?: PacketInfo(Transport.OTHER, l2src, l2dst, "EAPOL", "EAPOL $l2src → $l2dst", l2src, l2dst)
            else -> PacketInfo(Transport.OTHER, l2src, l2dst, null, "EtherType 0x%04X".format(etherType), null, null)
        }

    private fun rawIP(p: ByteArray): PacketInfo {
        val first = p.firstOrNull()?.toInt()?.and(0xFF) ?: return short()
        return when (first shr 4) {
            4 -> ipv4(p, 0)
            6 -> ipv6(p, 0)
            else -> short()
        }
    }

    // 802.11

    private fun dot11(p: ByteArray): PacketInfo {
        if (p.size < 2) return short()
        val frameControl = p.u8(0)
        val type = (frameControl shr 2) and 0x3
        val subtype = (frameControl shr 4) and 0xF
        val addr1 = if (p.size >= 10) mac(p, 4) else "—"
        val addr2 = if (p.size >= 16) mac(p, 10) else "—"
        return when (type) {
            0 -> {
                val name = dot11MgmtName(subtype)
                PacketInfo(Transport.DOT11_MGMT, addr2, addr1, name, "802.11 $name $addr2 → $addr1", addr2, addr1)
            }
            1 -> {
                val name = dot11CtrlName(subtype)
                PacketInfo(Transport.DOT11_CTRL, addr2, addr1, name, "802.11 $name", null, null)
            }
            2 ->
                // A data frame may carry an EAPOL key exchange (the WPA handshake). The
                // EAPOL-Key frames are not link-encrypted, so the SNAP header before them
                // is in the clear and can be spotted.
                eapolInDataFrame(p, addr2, addr1)
                    ?: PacketInfo(Transport.DOT11_DATA, addr2, addr1, null, "802.11 data $addr2 → $addr1", addr2, addr1)
            else -> PacketInfo(Transport.OTHER, addr2, addr1, null, "802.11", null, null)
        }
    }

    private fun dot11MgmtName(subtype: Int): String = when (subtype) {
        0 -> "assoc req"
        1 -> "assoc resp"
        2 -> "reassoc req"
        3 -> "reassoc resp"
        4 -> "probe req"
        5 -> "probe resp"
        8 -> "beacon"
        9 -> "ATIM"
        10 -> "disassoc"
        11 -> "auth"
        12 -> "deauth"
        13 -> "action"
        else -> "mgmt $subtype"
    }

    private fun dot11CtrlName(subtype: Int): String = when (subtype) {
        8 -> "block ack req"
        9 -> "block ack"
        10 -> "PS-poll"
        11 -> "RTS"
        12 -> "CTS"
        13 -> "ACK"
        14 -> "CF-end"
        else -> "ctrl $subtype"
    }

    // EAPOL (WPA handshake)

    private val eapolSnap = byteArrayOf(
        0xAA.toByte(), 0xAA.toByte(), 0x03, 0x00, 0x00, 0x00, 0x88.toByte(), 0x8E.toByte(),
    )

    /**
     * An 802.11 data frame may carry an EAPOL key exchange under an LLC/SNAP
     * header. The SNAP position shifts with QoS and the address count, and the
     * handshake frames are not link-encrypted, so a short scan for the SNAP
     * signature finds it without walking every header variant.
     */
    private fun eapolInDataFrame(p: ByteArray, addr2: String, addr1: String): PacketInfo? {
        val snapAt = findBytes(eapolSnap, p) ?: return null
        if (snapAt > 48) return null // only the header region, like the iOS search bound
        val found = classifyEapol(p, snapAt + eapolSnap.size) ?: return null
        return PacketInfo(
            Transport.DOT11_DATA, addr2, addr1, "EAPOL",
            "802.11 ${found.second} $addr2 → $addr1", addr2, addr1, found.first,
        )
    }

    /** EAPOL carried straight over Ethernet/SLL (EtherType 0x888E), e.g. a wired 802.1X exchange. */
    private fun eapol(p: ByteArray, offset: Int, src: String, dst: String): PacketInfo? {
        val found = classifyEapol(p, offset) ?: return null
        return PacketInfo(Transport.OTHER, src, dst, "EAPOL", "${found.second} $src → $dst", src, dst, found.first)
    }

    /**
     * Reads the 802.1X header at [offset] and, for an EAPOL-Key frame of a
     * pairwise (4-way) handshake, says which message it is (1..4). Returns null
     * when the bytes are not a readable 802.1X frame; the message is null for an
     * 802.1X frame that is not a recognisable 4-way message.
     */
    private fun classifyEapol(p: ByteArray, offset: Int): Pair<Int?, String>? {
        // 802.1X header: version(1) type(1) length(2).
        if (offset < 0 || offset + 4 > p.size) return null
        if (p.u8(offset + 1) != 0x03) return Pair(null, "EAPOL") // not an EAPOL-Key (EAP, Start, Logoff…)
        if (offset + 7 > p.size) return Pair(null, "EAPOL-Key")
        val keyInfo = p.be16(offset + 5)
        val pairwise = keyInfo and 0x0008 != 0 // bit 3: 1 = pairwise, 0 = group
        val ack = keyInfo and 0x0080 != 0 // bit 7: Key ACK
        val mic = keyInfo and 0x0100 != 0 // bit 8: Key MIC
        val secure = keyInfo and 0x0200 != 0 // bit 9: Secure
        if (!pairwise) return Pair(null, "EAPOL-Key (group)")
        val message = when {
            ack && !mic -> 1 // M1: ACK, no MIC
            ack && mic -> 3 // M3: ACK + MIC (+ Install/Secure)
            !ack && mic && !secure -> 2 // M2: MIC, not yet secure
            !ack && mic && secure -> 4 // M4: MIC, secure
            else -> return Pair(null, "EAPOL-Key")
        }
        return Pair(message, "EAPOL M$message")
    }

    // Network layer

    private fun ipv4(p: ByteArray, o: Int): PacketInfo {
        if (p.size < o + 20) return short()
        val ihl = (p.u8(o) and 0x0F) * 4
        val proto = p.u8(o + 9)
        val src = ipv4Addr(p, o + 12)
        val dst = ipv4Addr(p, o + 16)
        return afterL3(proto, p, o + maxOf(ihl, 20), src, dst)
    }

    private fun ipv6(p: ByteArray, o: Int): PacketInfo {
        if (p.size < o + 40) return short()
        // Extension headers are not walked; ports past them stay approximate.
        return afterL3(p.u8(o + 6), p, o + 40, ipv6Addr(p, o + 8), ipv6Addr(p, o + 24))
    }

    private fun afterL3(proto: Int, p: ByteArray, l4: Int, src: String, dst: String): PacketInfo = when (proto) {
        6 -> ports(Transport.TCP, p, l4, src, dst)
        17 -> ports(Transport.UDP, p, l4, src, dst)
        1 -> simple(Transport.ICMP, "ICMP $src → $dst", src, dst)
        2 -> simple(Transport.IGMP, "IGMP $src → $dst", src, dst)
        58 -> simple(Transport.ICMPV6, "ICMPv6 $src → $dst", src, dst)
        else -> simple(Transport.IP_OTHER, "IP proto $proto $src → $dst", src, dst)
    }

    private fun arp(p: ByteArray, o: Int, l2src: String): PacketInfo {
        if (p.size < o + 28) return short()
        val op = p.be16(o + 6)
        val sender = ipv4Addr(p, o + 14)
        val target = ipv4Addr(p, o + 24)
        val verb = when (op) {
            1 -> "who has $target? tell $sender"
            2 -> "$sender is at $l2src"
            else -> "op $op"
        }
        return PacketInfo(Transport.ARP, sender, target, "ARP", "ARP $verb", sender, target)
    }

    // Transport layer

    private fun ports(transport: Transport, p: ByteArray, o: Int, src: String, dst: String): PacketInfo {
        if (p.size < o + 4) return simple(transport, "${transport.label} $src → $dst", src, dst)
        val sp = p.be16(o)
        val dp = p.be16(o + 2)
        val app = appName(sp, dp, transport == Transport.UDP)
        val suffix = app?.let { " [$it]" } ?: ""
        return PacketInfo(
            transport, "$src:$sp", "$dst:$dp", app,
            "${transport.label} $src:$sp → $dst:$dp$suffix", src, dst,
        )
    }

    /** Guesses the application from the well-known side of the port pair. */
    private fun appName(a: Int, b: Int, udp: Boolean): String? {
        fun has(port: Int) = a == port || b == port
        return when {
            has(443) -> if (udp) "QUIC/HTTP3" else "HTTPS/TLS"
            has(80) || has(8080) -> "HTTP"
            has(53) -> "DNS"
            udp && (has(67) || has(68)) -> "DHCP"
            has(5353) -> "mDNS"
            udp && has(1900) -> "SSDP"
            has(123) -> "NTP"
            has(22) -> "SSH"
            has(445) -> "SMB"
            has(137) || has(138) || has(139) -> "NetBIOS"
            has(993) -> "IMAPS"
            has(995) -> "POP3S"
            has(25) || has(465) || has(587) -> "SMTP"
            has(3478) || has(3479) -> "STUN/TURN"
            has(51820) -> "WireGuard"
            has(1883) || has(8883) -> "MQTT"
            has(554) -> "RTSP"
            has(5060) || has(5061) -> "SIP"
            has(5228) -> "Google services"
            has(3306) -> "MySQL"
            has(5432) -> "PostgreSQL"
            has(6379) -> "Redis"
            else -> null
        }
    }

    // Helpers

    private fun simple(transport: Transport, info: String, src: String, dst: String) =
        PacketInfo(transport, src, dst, null, info, src, dst)

    private fun short() = PacketInfo(Transport.OTHER, "—", "—", null, "truncated", null, null)

    private fun mac(p: ByteArray, o: Int): String {
        if (o + 6 > p.size) return "—"
        return (o until o + 6).joinToString(":") { "%02X".format(p.u8(it)) }
    }

    private fun ipv4Addr(p: ByteArray, o: Int): String {
        if (o + 4 > p.size) return "?"
        return "${p.u8(o)}.${p.u8(o + 1)}.${p.u8(o + 2)}.${p.u8(o + 3)}"
    }

    private fun ipv6Addr(p: ByteArray, o: Int): String {
        if (o + 16 > p.size) return "?"
        return (o until o + 16 step 2).joinToString(":") { "%x".format(p.be16(it)) }
    }
}
