package cattivole.spectra

import cattivole.spectra.core.AuthMode
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiJoinTest {
    @Test
    fun openAndOweNeedNoPassword() {
        assertEquals(AuthMode.JoinMethod.OPEN, AuthMode.OPEN.joinMethod)
        assertEquals(AuthMode.JoinMethod.OPEN, AuthMode.OWE.joinMethod)
    }

    @Test
    fun wepIsItsOwnMethod() {
        assertEquals(AuthMode.JoinMethod.WEP, AuthMode.WEP.joinMethod)
    }

    @Test
    fun pskCoversWpaFamilies() {
        assertEquals(AuthMode.JoinMethod.PSK, AuthMode.WPA_PSK.joinMethod)
        assertEquals(AuthMode.JoinMethod.PSK, AuthMode.WPA2_PSK.joinMethod)
        assertEquals(AuthMode.JoinMethod.PSK, AuthMode.WPA_WPA2_PSK.joinMethod)
        assertEquals(AuthMode.JoinMethod.PSK, AuthMode.WPA3_PSK.joinMethod)
        assertEquals(AuthMode.JoinMethod.PSK, AuthMode.WPA2_WPA3_PSK.joinMethod)
    }

    @Test
    fun enterpriseAndUnknownAreUnsupported() {
        assertEquals(AuthMode.JoinMethod.UNSUPPORTED, AuthMode.WPA2_ENTERPRISE.joinMethod)
        assertEquals(AuthMode.JoinMethod.UNSUPPORTED, AuthMode(8).joinMethod)  // WAPI
        assertEquals(AuthMode.JoinMethod.UNSUPPORTED, AuthMode(13).joinMethod) // DPP
        assertEquals(AuthMode.JoinMethod.UNSUPPORTED, AuthMode(99).joinMethod)
    }
}
