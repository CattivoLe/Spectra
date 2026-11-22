package cattivole.spectra

import cattivole.spectra.core.HostIdentity
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.Miio
import cattivole.spectra.core.ServiceAdvertisement
import cattivole.spectra.ui.niceCeiling
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** Xiaomi Mi Home appliances: the UDP hello and the model in the mDNS name; and the speed chart's scale. */
class MiioTest {
    private val original = Locale.getDefault()

    @Before
    fun english() = Locale.setDefault(Locale.ENGLISH)

    @After
    fun restore() = Locale.setDefault(original)

    @Test
    fun helloIsThirtyTwoBytesWithTheMagic() {
        assertEquals(32, Miio.HELLO.size)
        assertEquals(0x21.toByte(), Miio.HELLO[0])
        assertEquals(0x31.toByte(), Miio.HELLO[1])
        assertEquals(0x20.toByte(), Miio.HELLO[3])
        // Our own hello, heard back on a broadcast, is not a device.
        assertNull(Miio.parseHello(Miio.HELLO))
    }

    @Test
    fun aReplyCarriesTheDeviceId() {
        val reply = ByteArray(32).also {
            it[0] = 0x21; it[1] = 0x31; it[2] = 0x00; it[3] = 0x20
            it[8] = 0x12; it[9] = 0x34; it[10] = 0x56; it[11] = 0x78
        }
        assertEquals(0x12345678L, Miio.parseHello(reply))
        assertNull(Miio.parseHello(reply.copyOf(20)))
        assertNull(Miio.parseHello(ByteArray(32)))
    }

    @Test
    fun theModelComesFromTheInstanceName() {
        assertEquals("careli.fryer.maf02", Miio.modelFromInstance("careli-fryer-maf02_miio123456789"))
        assertEquals("zhimi.airpurifier.ma2", Miio.modelFromInstance("zhimi-airpurifier-ma2_miio98765"))
        assertNull(Miio.modelFromInstance("Living Room TV"))
        assertTrue(Miio.isInstanceName("careli-fryer-maf02_miio123456789"))
        assertEquals("Air fryer", Miio.kind("careli.fryer.maf02"))
        assertEquals("Air purifier", Miio.kind("zhimi.airpurifier.ma2"))
        assertEquals("Robot vacuum", Miio.kind("roborock.vacuum.s5"))
        assertNull(Miio.kind("xiaomi.unknownthing.v1"))
    }

    @Test
    fun aMiHomeApplianceIsNamedByWhatItIs() {
        val service = ServiceAdvertisement(Miio.SERVICE_TYPE, "Mi Home", "careli.fryer.maf02")
        val found = HostIdentity.recognize(names = emptyList(), services = listOf(service))
        assertEquals("Xiaomi", found.manufacturer)
        assertEquals("Air fryer", found.model)
        Locale.setDefault(Locale.forLanguageTag("ru"))
        assertEquals("Аэрогриль", HostIdentity.recognize(names = emptyList(), services = listOf(service)).model)
        // Only the hello answered (the user's air fryer): a Xiaomi appliance, kind unknown.
        val hello = HostIdentity.recognize(names = emptyList(), ports = listOf(Miio.PORT))
        assertEquals("Xiaomi", hello.manufacturer)
        assertEquals("Устройство Mi Home", hello.model)
    }

    @Test
    fun theInstanceNameIsNeverShownAsTheHostName() {
        assertNull(LanDevice.usefulHostname("careli-fryer-maf02_miio123456789", "192.168.1.95"))
        val device = LanDevice("192.168.1.95", services = listOf(ServiceAdvertisement(Miio.SERVICE_TYPE, "Mi Home", "careli.fryer.maf02")))
        assertEquals("Air fryer", device.displayName)
        assertFalse(device.allPortsScanned)
    }

    @Test
    fun theSpeedScaleEndsOnARoundNumber() {
        assertEquals(1.0, niceCeiling(0.7), 1e-9)
        assertEquals(2.0, niceCeiling(1.3), 1e-9)
        assertEquals(50.0, niceCeiling(23.0), 1e-9)
        assertEquals(100.0, niceCeiling(87.5), 1e-9)
        assertEquals(500.0, niceCeiling(412.0), 1e-9)
    }
}
