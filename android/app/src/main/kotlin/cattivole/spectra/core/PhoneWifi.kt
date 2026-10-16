package cattivole.spectra.core

/**
 * Turns an Android [android.net.wifi.ScanResult] into the same reading the
 * sensor produces. The phone does not report the 40 MHz secondary-channel
 * direction, HT/VHT flags are often missing, and a missing location grant
 * used to replace the SSID with `<unknown ssid>`.
 */
object PhoneWifi {
    fun band(frequencyMHz: Int): Band = when {
        frequencyMHz < 3000 -> Band.TWO_FOUR
        frequencyMHz < 5925 -> Band.FIVE
        else -> Band.SIX
    }

    fun channel(frequencyMHz: Int): Int = when {
        frequencyMHz == 2484 -> 14
        frequencyMHz in 2412..2472 -> (frequencyMHz - 2407) / 5
        frequencyMHz in 5000..5885 -> (frequencyMHz - 5000) / 5
        frequencyMHz >= 5955 -> (frequencyMHz - 5950) / 5
        else -> 0
    }

    fun visibility(ssid: String?): SsidVisibility = when {
        ssid.isNullOrEmpty() -> SsidVisibility.HIDDEN
        ssid == "<unknown ssid>" -> SsidVisibility.REDACTED
        else -> SsidVisibility.NAMED
    }

    fun reading(
        bssid: String,
        ssid: String?,
        rssi: Int,
        frequencyMHz: Int,
        capabilities: String,
        widthMHz: Int,
    ): AccessPointReading? {
        val mac = MacAddress.parse(bssid) ?: return null
        val shown = visibility(ssid)
        val parsed = parseCapabilities(capabilities)
        return AccessPointReading(
            bssid = mac,
            rssi = rssi,
            channel = channel(frequencyMHz),
            secondaryChannel = SecondaryChannel.NONE,
            auth = parsed.auth,
            pairwiseCipher = parsed.pairwise,
            groupCipher = parsed.group,
            capabilities = parsed.flags,
            countryCode = null,
            ssidBytes = if (shown == SsidVisibility.NAMED) ssid!!.encodeToByteArray() else ByteArray(0),
            band = band(frequencyMHz),
            widthMHz = widthMHz,
            visibility = shown,
            phyNote = parsed.phyNote,
        )
    }

    data class ParsedCapabilities(
        val auth: AuthMode,
        val pairwise: Cipher,
        val group: Cipher,
        val flags: Int,
        val phyNote: String?,
    )

    fun parseCapabilities(raw: String): ParsedCapabilities {
        val text = raw.uppercase()
        val sae = "SAE" in text || "WPA3" in text
        val owe = "OWE" in text
        val eap = "EAP" in text || "ENTERPRISE" in text
        val wpa = "WPA-PSK" in text || "WPA-EAP" in text
        val wpa2 = "WPA2" in text || "RSN-" in text
        val wep = "WEP" in text
        val auth = when {
            owe -> AuthMode.OWE
            sae && (wpa2 || wpa) && !eap -> AuthMode.WPA2_WPA3_PSK
            sae && eap -> AuthMode(14)
            sae -> AuthMode.WPA3_PSK
            eap && wpa2 -> AuthMode.WPA2_ENTERPRISE
            wpa && wpa2 -> AuthMode.WPA_WPA2_PSK
            wpa2 -> AuthMode.WPA2_PSK
            wpa -> AuthMode.WPA_PSK
            wep -> AuthMode.WEP
            else -> AuthMode.OPEN
        }
        val ccmp = "CCMP" in text
        val tkip = "TKIP" in text
        val gcmp256 = "GCMP-256" in text || "GCMP256" in text
        val gcmp = "GCMP" in text && !gcmp256
        val pairwise = when {
            gcmp256 -> Cipher.GCMP_256
            gcmp -> Cipher(8)
            ccmp && tkip -> Cipher(5)
            ccmp -> Cipher.CCMP
            tkip -> Cipher.TKIP
            wep -> Cipher(2)
            else -> Cipher.NONE
        }
        val group = when {
            tkip && ccmp -> Cipher.TKIP
            else -> pairwise
        }
        var flags = 0
        if ("WPS" in text) flags = flags or Capabilities.WPS
        if ("EHT" in text) {
            flags = flags or Capabilities.AX or Capabilities.N
        } else if ("HE" in text || "WFA-HE" in text) {
            flags = flags or Capabilities.AX or Capabilities.N
        } else if ("VHT" in text || "HT" in text) {
            flags = flags or Capabilities.N
        }
        val phyNote = when {
            "EHT" in text -> "Wi-Fi 7"
            "VHT" in text && "HE" !in text && "EHT" !in text -> "Wi-Fi 5"
            else -> null
        }
        return ParsedCapabilities(auth, pairwise, group, flags, phyNote)
    }
}
