package ch.fbc.krakenbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonDebouncerTest {

    private var now = 0L
    private val debouncer = ButtonDebouncer(windowMs = 100L) { now }

    @Test
    fun `first event always passes`() {
        assertTrue(debouncer.shouldProcess(0x21))
    }

    @Test
    fun `same code within the window is dropped`() {
        debouncer.shouldProcess(0x21)
        now = 99L
        assertFalse(debouncer.shouldProcess(0x21))
    }

    @Test
    fun `same code at the window boundary passes`() {
        debouncer.shouldProcess(0x21)
        now = 100L
        assertTrue(debouncer.shouldProcess(0x21))
    }

    @Test
    fun `different code within the window passes`() {
        debouncer.shouldProcess(0x21)
        now = 10L
        assertTrue(debouncer.shouldProcess(0x62))
    }

    @Test
    fun `accepted event re-arms the window`() {
        debouncer.shouldProcess(0x21)
        now = 150L
        assertTrue(debouncer.shouldProcess(0x21))   // window expired → accepted
        now = 200L
        assertFalse(debouncer.shouldProcess(0x21))  // 50ms after re-arm → dropped
    }

    @Test
    fun `reset forgets the last event`() {
        debouncer.shouldProcess(0x21)
        debouncer.reset()
        now = 1L
        assertTrue(debouncer.shouldProcess(0x21))
    }
}
