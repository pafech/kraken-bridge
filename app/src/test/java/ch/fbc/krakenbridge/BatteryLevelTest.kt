package ch.fbc.krakenbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryLevelTest {

    @Test
    fun `null or empty payload yields no level`() {
        assertNull(batteryPercentFrom(null))
        assertNull(batteryPercentFrom(ByteArray(0)))
    }

    @Test
    fun `first byte is the percentage`() {
        assertEquals(0, batteryPercentFrom(byteArrayOf(0)))
        assertEquals(73, batteryPercentFrom(byteArrayOf(73, 1)))
        assertEquals(100, batteryPercentFrom(byteArrayOf(100)))
    }

    @Test
    fun `values above 100 are rejected`() {
        assertNull(batteryPercentFrom(byteArrayOf(101)))
        assertNull(batteryPercentFrom(byteArrayOf(-1)))  // 0xFF unsigned
    }
}
