package cattivole.spectra

import cattivole.spectra.core.LoraDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraFlagsTest {
    private fun dev(flags: Int) = LoraDevice(868_100, 7, -100, 2, flags, 0x1234_5678)

    @Test
    fun decodesDownlinkAndConfirmed() {
        val d = dev(0x01 or 0x08 or 0x10)
        assertTrue(d.lorawan)
        assertTrue(d.downlink)
        assertTrue(d.confirmed)
        assertFalse(d.join)
        assertTrue(d.title().startsWith("↓"))
        assertTrue(d.detail().contains("confirmed"))
    }

    @Test
    fun plainUplinkHasNoExtraFlags() {
        val d = dev(0x01)
        assertFalse(d.downlink)
        assertFalse(d.confirmed)
        assertTrue(d.title().startsWith("↑"))
    }

    @Test
    fun privateSyncWordIsShown() {
        assertTrue(dev(0x01 or 0x02).privateNetwork)
    }
}
