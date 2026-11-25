package cattivole.spectra.core

/**
 * Lifts WPA/WPA2 handshakes out of a capture and writes them as hashcat's 22000
 * hash lines — `WPA*02` for a 4-way handshake and `WPA*01` for a PMKID — the
 * format `hcxpcapngtool` produces and hashcat mode 22000 cracks. Mirrors the iOS
 * `Hc22000`.
 *
 * A line is only emitted when the network name (ESSID) is known, since the hash
 * is useless without it.
 */
object Hc22000 {
    /** One 802.1X EAPOL-Key message, with the fields the hash needs. */
    private class KeyMessage(
        val ap: ByteArray,
        val sta: ByteArray,
        val message: Int,
        val replayCounter: Long,
        val nonce: ByteArray, // ANonce for M1/M3, SNonce for M2/M4
        val mic: ByteArray,
        val eapol: ByteArray,
        val micOffset: Int,
        val pmkid: ByteArray?,
    )

    /** The hashcat 22000 lines for every handshake and PMKID found, or empty. */
    fun lines(file: CaptureFile): List<String> {
        val ssidByBssid = HashMap<String, ByteArray>()
        val messages = ArrayList<KeyMessage>()

        for (packet in file.packets) {
            val frame = dot11Frame(packet.linkType, packet.data) ?: continue
            val frameControl = frame.u8(0)
            val type = (frameControl shr 2) and 0x3
            val subtype = (frameControl shr 4) and 0xF
            when (type) {
                0 -> { // management: pick up the SSID a BSSID advertises or is asked for
                    val found = ssid(frame, subtype)
                    if (found != null && found.second.isNotEmpty()) ssidByBssid[hex(found.first)] = found.second
                }
                2 -> keyMessage(frame)?.let { messages.add(it) } // data: may carry EAPOL-Key
            }
        }

        val groups = LinkedHashMap<String, MutableList<KeyMessage>>()
        for (message in messages) {
            val key = hex(message.ap) + "|" + hex(message.sta)
            groups.getOrPut(key) { ArrayList() }.add(message)
        }

        val lines = ArrayList<String>()
        for ((_, group) in groups) {
            val ap = group.first().ap
            val sta = group.first().sta
            val ssid = ssidByBssid[hex(ap)] ?: continue // the hash needs the ESSID
            if (ssid.isEmpty()) continue
            val apHex = hex(ap)
            val staHex = hex(sta)
            val essidHex = hex(ssid)

            group.firstNotNullOfOrNull { it.pmkid }?.let { pmkid ->
                lines.add("WPA*01*${hex(pmkid)}*$apHex*$staHex*$essidHex***")
            }

            val m1 = group.firstOrNull { it.message == 1 }
            val m2 = group.firstOrNull { it.message == 2 }
            val m3 = group.firstOrNull { it.message == 3 }
            val m4 = group.firstOrNull { it.message == 4 }

            fun eapolLine(mic: ByteArray, anonce: ByteArray, carrier: KeyMessage, pair: Int, matched: Boolean): String {
                val eapol = carrier.eapol.copyOf()
                for (i in 0 until 16) if (carrier.micOffset + i < eapol.size) eapol[carrier.micOffset + i] = 0
                val messagePair = pair or (if (matched) 0 else 0x80) // 0x80: replay counter not verified
                return "WPA*02*${hex(mic)}*$apHex*$staHex*$essidHex*${hex(anonce)}*${hex(eapol)}*" + "%02x".format(messagePair)
            }

            when {
                m1 != null && m2 != null ->
                    lines.add(eapolLine(m2.mic, m1.nonce, m2, 0x00, m1.replayCounter == m2.replayCounter))
                m2 != null && m3 != null ->
                    lines.add(eapolLine(m2.mic, m3.nonce, m2, 0x02, m3.replayCounter == m2.replayCounter + 1))
                m3 != null && m4 != null && m4.nonce.any { it.toInt() != 0 } ->
                    lines.add(eapolLine(m4.mic, m3.nonce, m4, 0x05, m3.replayCounter == m4.replayCounter))
            }
        }
        return lines
    }

    /** The bare 802.11 frame for a packet, stripping a radiotap header when present. */
    private fun dot11Frame(linkType: LinkType, data: ByteArray): ByteArray? = when (linkType) {
        LinkType.IEEE802_11 -> if (data.size >= 24) data else null
        LinkType.IEEE802_11_RADIOTAP -> {
            if (data.size < 4) {
                null
            } else {
                val length = data.u8(2) or (data.u8(3) shl 8) // radiotap it_len is little-endian
                if (length < 4 || length >= data.size) {
                    null
                } else {
                    data.copyOfRange(length, data.size).let { if (it.size >= 24) it else null }
                }
            }
        }
        else -> null
    }

    /** The BSSID and SSID a management frame carries, when it is one that names a network. */
    private fun ssid(frame: ByteArray, subtype: Int): Pair<ByteArray, ByteArray>? {
        if (frame.size < 22) return null
        val tagStart = when (subtype) {
            8, 5 -> 36 // beacon / probe response: 24 header + 12 fixed
            0 -> 28 // association request: 24 + 4 fixed
            2 -> 34 // reassociation request: 24 + 10 fixed
            else -> return null
        }
        val bssid = frame.copyOfRange(16, 22) // addr3 is the BSSID in these frames
        var i = tagStart
        while (i + 2 <= frame.size) {
            val id = frame.u8(i)
            val len = frame.u8(i + 1)
            if (i + 2 + len > frame.size) break
            if (id == 0) return bssid to frame.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
        }
        return bssid to ByteArray(0)
    }

    private val snap = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03, 0x00, 0x00, 0x00, 0x88.toByte(), 0x8E.toByte())

    /** Reads the EAPOL-Key message inside a data frame, when there is one. */
    private fun keyMessage(frame: ByteArray): KeyMessage? {
        val snapAt = findBytes(snap, frame) ?: return null
        if (snapAt > 48) return null // only the header region, like the dissector's bound
        val o = snapAt + snap.size // start of the 802.1X frame
        if (o + 99 > frame.size || frame.u8(o + 1) != 0x03) return null
        val keyInfo = frame.be16(o + 5)
        val pairwise = keyInfo and 0x0008 != 0
        val ack = keyInfo and 0x0080 != 0
        val mic = keyInfo and 0x0100 != 0
        val secure = keyInfo and 0x0200 != 0
        if (!pairwise) return null
        val message = when {
            ack && !mic -> 1
            ack && mic -> 3
            !ack && mic && !secure -> 2
            !ack && mic && secure -> 4
            else -> return null
        }

        // The ACK bit tells apart the two directions: a message with ACK is from the AP.
        val addr1 = frame.copyOfRange(4, 10) // receiver
        val addr2 = frame.copyOfRange(10, 16) // transmitter
        val ap = if (ack) addr2 else addr1
        val sta = if (ack) addr1 else addr2

        val bodyLength = frame.be16(o + 2)
        val eapolEnd = minOf(o + 4 + bodyLength, frame.size)
        if (eapolEnd - o < 99) return null

        val nonce = frame.copyOfRange(o + 17, o + 49)
        val micBytes = frame.copyOfRange(o + 81, o + 97)
        val eapol = frame.copyOfRange(o, eapolEnd)

        var pmkid: ByteArray? = null
        if (message == 1) {
            val keyDataLength = frame.be16(o + 97)
            val keyDataStart = o + 99
            val keyDataEnd = minOf(keyDataStart + keyDataLength, frame.size)
            if (keyDataStart < keyDataEnd) {
                val keyData = frame.copyOfRange(keyDataStart, keyDataEnd)
                // RSN PMKID KDE: … 00 0F AC 04 <16-byte PMKID>.
                val at = findBytes(byteArrayOf(0x00, 0x0F, 0xAC.toByte(), 0x04), keyData)
                if (at != null && at + 4 + 16 <= keyData.size) {
                    val candidate = keyData.copyOfRange(at + 4, at + 4 + 16)
                    if (candidate.any { it.toInt() != 0 }) pmkid = candidate
                }
            }
        }

        return KeyMessage(ap, sta, message, be64(frame, o + 9), nonce, micBytes, eapol, 81, pmkid)
    }

    private fun be64(p: ByteArray, o: Int): Long {
        if (o < 0 || o + 8 > p.size) return 0
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or p.u8(o + i).toLong()
        return value
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
