package cattivole.spectra

import cattivole.spectra.core.CaptureFormat
import cattivole.spectra.core.LinkType
import cattivole.spectra.core.PacketDissector
import cattivole.spectra.core.PcapAnalyzer
import cattivole.spectra.core.PcapError
import cattivole.spectra.core.PcapException
import cattivole.spectra.core.PcapParser
import cattivole.spectra.core.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcapTest {
    // Byte builders (values are unsigned 0..255, packed at the end).

    private fun be16(v: Int) = listOf((v shr 8) and 0xFF, v and 0xFF)
    private fun le16(v: Int) = listOf(v and 0xFF, (v shr 8) and 0xFF)
    private fun le32(v: Long) =
        listOf((v and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(), ((v shr 24) and 0xFF).toInt())

    private fun ethernet(etherType: Int, payload: List<Int>) =
        listOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66) + listOf(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF) + be16(etherType) + payload

    private fun ipv4(proto: Int, src: List<Int>, dst: List<Int>, payload: List<Int>) =
        listOf(0x45, 0x00) + be16(20 + payload.size) + listOf(0, 0, 0, 0) + listOf(0x40, proto, 0, 0) + src + dst + payload

    private fun udp(sp: Int, dp: Int) = be16(sp) + be16(dp) + be16(8) + be16(0)
    private fun tcp(sp: Int, dp: Int) = be16(sp) + be16(dp) + List(16) { 0 }
    private fun arp() =
        be16(1) + be16(0x0800) + listOf(6, 4) + be16(1) +
            listOf(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF) + listOf(192, 168, 1, 10) +
            listOf(0, 0, 0, 0, 0, 0) + listOf(192, 168, 1, 1)

    private val dns get() = ethernet(0x0800, ipv4(17, listOf(192, 168, 1, 10), listOf(192, 168, 1, 1), udp(50000, 53)))
    private val https get() = ethernet(0x0800, ipv4(6, listOf(192, 168, 1, 10), listOf(93, 184, 216, 34), tcp(50001, 443)))
    private val arpPacket get() = ethernet(0x0806, arp())

    // An 802.1X EAPOL-Key body with the given 16-bit Key Information field.
    private fun eapolKey(keyInfo: Int) = listOf(0x02, 0x03) + be16(0x005F) + listOf(0x02) + be16(keyInfo)

    // An 802.11 data frame (24-byte MAC header) carrying EAPOL under LLC/SNAP.
    private fun dot11EapolData(keyInfo: Int): List<Int> =
        listOf(0x08, 0x00, 0x00, 0x00) + List(6) { 0x01 } + List(6) { 0x02 } + List(6) { 0x03 } + listOf(0x00, 0x00) +
            listOf(0xAA, 0xAA, 0x03, 0x00, 0x00, 0x00, 0x88, 0x8E) + eapolKey(keyInfo)

    // Key Information fields that identify each 4-way handshake message.
    private val keyInfoM1 = 0x0088 // pairwise + ACK
    private val keyInfoM2 = 0x0108 // pairwise + MIC
    private val keyInfoM3 = 0x03C8 // pairwise + ACK + MIC + install + secure
    private val keyInfoM4 = 0x0308 // pairwise + MIC + secure

    private fun be64(v: Long) = (7 downTo 0).map { ((v shr (it * 8)) and 0xFF).toInt() }

    private fun beaconFrame(bssid: List<Int>, ssid: List<Int>) =
        listOf(0x80, 0x00, 0x00, 0x00) + List(6) { 0xFF } + bssid + bssid + listOf(0x00, 0x00) +
            List(8) { 0 } + listOf(0x64, 0x00) + listOf(0x01, 0x04) + listOf(0x00, ssid.size) + ssid

    private fun eapolKeyFrame(
        ap: List<Int>, sta: List<Int>, fromAP: Boolean, keyInfo: Int, replay: Long,
        nonce: List<Int>, mic: List<Int>, keyData: List<Int> = emptyList(),
    ): List<Int> {
        val addr1 = if (fromAP) sta else ap
        val addr2 = if (fromAP) ap else sta
        val body = listOf(0x02) + be16(keyInfo) + listOf(0x00, 0x10) + be64(replay) + nonce +
            List(16) { 0 } + List(8) { 0 } + List(8) { 0 } + mic + be16(keyData.size) + keyData
        return listOf(0x08, 0x00, 0x00, 0x00) + addr1 + addr2 + ap + listOf(0x00, 0x00) +
            listOf(0xAA, 0xAA, 0x03, 0x00, 0x00, 0x00, 0x88, 0x8E) + listOf(0x02, 0x03) + be16(body.size) + body
    }

    private fun pcapFile(packets: List<List<Int>>, network: Long = 1): ByteArray {
        val out = ArrayList<Int>()
        out += listOf(0xD4, 0xC3, 0xB2, 0xA1)       // magic (LE, microsecond)
        out += le16(2) + le16(4)
        out += le32(0) + le32(0)
        out += le32(65535)
        out += le32(network)                        // link type (1 = Ethernet, 105 = 802.11)
        packets.forEachIndexed { index, packet ->
            out += le32(1_700_000_000L + index)
            out += le32(0)
            out += le32(packet.size.toLong())
            out += le32(packet.size.toLong())
            out += packet
        }
        return out.map { it.toByte() }.toByteArray()
    }

    private fun block(type: Long, body: List<Int>): List<Int> {
        val pad = (4 - body.size % 4) % 4
        val total = (12 + body.size + pad).toLong()
        return le32(type) + le32(total) + body + List(pad) { 0 } + le32(total)
    }

    private fun pcapngFile(packets: List<List<Int>>): ByteArray {
        val out = ArrayList<Int>()
        out += block(0x0A0D0D0AL, le32(0x1A2B3C4DL) + le16(1) + le16(0) + le32(0xFFFFFFFFL) + le32(0xFFFFFFFFL))
        out += block(0x00000001L, le16(1) + le16(0) + le32(65535)) // IDB: Ethernet
        packets.forEach { packet ->
            val body = le32(0) + le32(0) + le32(0) + le32(packet.size.toLong()) + le32(packet.size.toLong()) + packet
            out += block(0x00000006L, body)
        }
        return out.map { it.toByte() }.toByteArray()
    }

    @Test
    fun parsesClassicPcap() {
        val file = PcapParser.parse(pcapFile(listOf(dns, https, arpPacket)))
        assertEquals(CaptureFormat.PCAP, file.format)
        assertEquals(3, file.packets.size)
        assertEquals(LinkType.ETHERNET, file.packets[0].linkType)
        assertNotNull(file.packets[0].timestamp)
    }

    @Test
    fun parsesPcapng() {
        val file = PcapParser.parse(pcapngFile(listOf(dns, https)))
        assertEquals(CaptureFormat.PCAPNG, file.format)
        assertEquals(2, file.packets.size)
        assertEquals(LinkType.ETHERNET, file.packets[0].linkType)
    }

    @Test
    fun rejectsGarbage() {
        val error = runCatching { PcapParser.parse(byteArrayOf(0, 1, 2, 3, 4, 5)) }.exceptionOrNull()
        assertTrue(error is PcapException && error.kind == PcapError.NOT_A_CAPTURE)
        val shortError = runCatching { PcapParser.parse(byteArrayOf(0)) }.exceptionOrNull()
        assertTrue(shortError is PcapException && shortError.kind == PcapError.TOO_SHORT)
    }

    @Test
    fun dissectsTransportAndApp() {
        val dnsInfo = PacketDissector.dissect(LinkType.ETHERNET, dns.map { it.toByte() }.toByteArray())
        assertEquals(Transport.UDP, dnsInfo.transport)
        assertEquals("DNS", dnsInfo.app)
        assertEquals("192.168.1.10", dnsInfo.netSource)

        val httpsInfo = PacketDissector.dissect(LinkType.ETHERNET, https.map { it.toByte() }.toByteArray())
        assertEquals(Transport.TCP, httpsInfo.transport)
        assertEquals("HTTPS/TLS", httpsInfo.app)
        assertEquals("93.184.216.34:443", httpsInfo.destination)

        val arpInfo = PacketDissector.dissect(LinkType.ETHERNET, arpPacket.map { it.toByte() }.toByteArray())
        assertEquals(Transport.ARP, arpInfo.transport)
    }

    @Test
    fun dissectsRadiotapBeacon() {
        val radiotap = listOf(0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00)
        val beacon = listOf(0x80, 0x00) + List(22) { 0 }
        val info = PacketDissector.dissect(LinkType.IEEE802_11_RADIOTAP, (radiotap + beacon).map { it.toByte() }.toByteArray())
        assertEquals(Transport.DOT11_MGMT, info.transport)
        assertEquals("beacon", info.app)
    }

    @Test
    fun analysisAggregates() {
        val analysis = PcapAnalyzer.analyze(pcapFile(listOf(dns, https, arpPacket)))
        assertEquals("pcap", analysis.format)
        assertEquals(3, analysis.summary.packetCount)
        assertNotNull(analysis.summary.duration)
        val protocols = analysis.summary.protocols.associate { it.name to it.count }
        assertEquals(1, protocols["TCP"])
        assertEquals(1, protocols["UDP"])
        assertEquals(1, protocols["ARP"])
        val apps = analysis.summary.apps.map { it.name }.toSet()
        assertTrue(apps.contains("DNS"))
        assertTrue(apps.contains("HTTPS/TLS"))
        assertTrue(analysis.summary.talkers.any { it.endpoint == "192.168.1.10" })
        assertTrue(analysis.summary.eapolMessages.isEmpty())
        assertFalse(analysis.summary.hasRadio)
    }

    @Test
    fun classifiesEapolHandshakeMessages() {
        fun msg(keyInfo: Int) =
            PacketDissector.dissect(LinkType.IEEE802_11, dot11EapolData(keyInfo).map { it.toByte() }.toByteArray()).eapolMessage
        assertEquals(1, msg(keyInfoM1))
        assertEquals(2, msg(keyInfoM2))
        assertEquals(3, msg(keyInfoM3))
        assertEquals(4, msg(keyInfoM4))

        // Carried straight over Ethernet (EtherType 0x888E).
        val wired = PacketDissector.dissect(LinkType.ETHERNET, ethernet(0x888E, eapolKey(keyInfoM1)).map { it.toByte() }.toByteArray())
        assertEquals(1, wired.eapolMessage)
        assertEquals("EAPOL", wired.app)

        // A group-key frame (pairwise bit clear) is EAPOL but not a 4-way message.
        assertNull(msg(0x0380))
        // An ordinary data frame carries no handshake.
        val data = (listOf(0x08, 0x00) + List(22) { 0 }).map { it.toByte() }.toByteArray()
        assertNull(PacketDissector.dissect(LinkType.IEEE802_11, data).eapolMessage)
    }

    @Test
    fun analysisTalliesHandshakeMessages() {
        val frames = listOf(keyInfoM1, keyInfoM2, keyInfoM3, keyInfoM4).map { ethernet(0x888E, eapolKey(it)) } + listOf(dns)
        val summary = PcapAnalyzer.analyze(pcapFile(frames)).summary
        assertEquals(setOf(1, 2, 3, 4), summary.eapolMessages.keys)
        assertTrue(summary.eapolMessages.values.all { it == 1 })
    }

    @Test
    fun exportsHc22000HandshakeAndPmkid() {
        val ap = listOf(0xA0, 0xB1, 0xC2, 0xD3, 0xE4, 0xF5)
        val sta = listOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val anonce = List(32) { 0xAA }
        val snonce = List(32) { 0x55 }
        val mic = List(16) { 0xCD }
        val pmkid = List(16) { 0x9A }
        val pmkidKde = listOf(0xDD, 0x14, 0x00, 0x0F, 0xAC, 0x04) + pmkid

        val beacon = beaconFrame(ap, "spectra".map { it.code })
        val m1 = eapolKeyFrame(ap, sta, fromAP = true, keyInfo = keyInfoM1, replay = 7L, nonce = anonce, mic = List(16) { 0 }, keyData = pmkidKde)
        val m2 = eapolKeyFrame(ap, sta, fromAP = false, keyInfo = keyInfoM2, replay = 7L, nonce = snonce, mic = mic)

        val analysis = PcapAnalyzer.analyze(pcapFile(listOf(beacon, m1, m2), network = 105L))
        assertEquals(2, analysis.hc22000.size)

        val pmkidLine = analysis.hc22000.first { it.startsWith("WPA*01") }
        assertEquals("WPA*01*9a9a9a9a9a9a9a9a9a9a9a9a9a9a9a9a*a0b1c2d3e4f5*112233445566*73706563747261***", pmkidLine)

        val fields = analysis.hc22000.first { it.startsWith("WPA*02") }.split("*")
        assertEquals(9, fields.size)
        assertEquals("cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd", fields[2]) // MIC
        assertEquals("a0b1c2d3e4f5", fields[3]) // AP
        assertEquals("112233445566", fields[4]) // STA
        assertEquals("73706563747261", fields[5]) // ESSID "spectra"
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", fields[6]) // ANonce
        assertEquals("00", fields[8]) // M1+M2, replay matched
        assertTrue(fields[7].contains("5555555555555555555555555555555555555555555555555555555555555555")) // SNonce in EAPOL
        assertFalse(fields[7].contains("cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd")) // MIC zeroed in EAPOL
    }
}

class PacketFilterTest {
    private fun row(index: Int, transport: String, src: String, dst: String, app: String? = null, info: String = "") =
        cattivole.spectra.core.PacketRow(index, null, 100, transport, src, dst, info, app, src.substringBeforeLast(':'), dst.substringBeforeLast(':'))

    private val rows = listOf(
        row(0, "TCP", "10.0.0.2:51000", "1.1.1.1:443", "HTTPS", "TCP 10.0.0.2:51000 → 1.1.1.1:443"),
        row(1, "UDP", "10.0.0.2:53000", "8.8.8.8:53", "DNS", "DNS query example.com"),
        row(2, "UDP", "10.0.0.3:53001", "8.8.8.8:53", "DNS", "DNS query github.com"),
        row(3, "ARP", "aa:bb", "ff:ff", null, "who has 10.0.0.1"),
    )

    @Test
    fun filtersNarrowThePacketList() {
        val none = cattivole.spectra.core.PacketFilter()
        org.junit.Assert.assertFalse(none.isActive)
        org.junit.Assert.assertEquals(4, rows.count(none::matches))
        org.junit.Assert.assertEquals(listOf(1, 2, 3), rows.filter(none.toggle("UDP").toggle("ARP")::matches).map { it.index })
        org.junit.Assert.assertEquals(listOf(1, 2), rows.filter(none.copy(app = "DNS")::matches).map { it.index })
        org.junit.Assert.assertEquals(listOf(2), rows.filter(none.copy(query = "GITHUB")::matches).map { it.index })
        // One host: either end. A conversation: exactly those two ends.
        org.junit.Assert.assertEquals(listOf(0, 1), rows.filter(none.copy(endpoints = listOf("10.0.0.2"))::matches).map { it.index })
        org.junit.Assert.assertEquals(listOf(1), rows.filter(none.copy(endpoints = listOf("8.8.8.8", "10.0.0.2"))::matches).map { it.index })
        org.junit.Assert.assertTrue(none.toggle("TCP").isActive)
        org.junit.Assert.assertFalse(none.toggle("TCP").toggle("TCP").isActive)
    }
}
