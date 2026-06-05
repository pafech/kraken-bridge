package ch.fbc.krakenbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ButtonCodesTest {

    @Test
    fun `null payload yields no code`() {
        assertNull(buttonCodeFrom(null))
    }

    @Test
    fun `empty payload yields no code`() {
        assertNull(buttonCodeFrom(ByteArray(0)))
    }

    @Test
    fun `first byte is the code`() {
        assertEquals(KrakenBleService.BTN_SHUTTER_PRESS, buttonCodeFrom(byteArrayOf(0x21)))
        assertEquals(KrakenBleService.BTN_FN_PRESS, buttonCodeFrom(byteArrayOf(0x62)))
    }

    @Test
    fun `high-bit bytes parse unsigned, not negative`() {
        assertEquals(0xFF, buttonCodeFrom(byteArrayOf(-1)))
        assertEquals(0x80, buttonCodeFrom(byteArrayOf(Byte.MIN_VALUE)))
    }

    @Test
    fun `trailing bytes are ignored`() {
        assertEquals(0x21, buttonCodeFrom(byteArrayOf(0x21, 0x00, 0x7F)))
    }
}
