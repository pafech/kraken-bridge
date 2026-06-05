package ch.fbc.krakenbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {

    @Test
    fun `schedule doubles per attempt`() {
        val backoff = ReconnectBackoff()
        assertEquals(2000L, backoff.nextDelayMs())
        assertEquals(4000L, backoff.nextDelayMs())
        assertEquals(8000L, backoff.nextDelayMs())
        assertEquals(16000L, backoff.nextDelayMs())
        assertEquals(32000L, backoff.nextDelayMs())
    }

    @Test
    fun `exhausted exactly after max attempts`() {
        val backoff = ReconnectBackoff()
        repeat(ReconnectBackoff.MAX_ATTEMPTS - 1) {
            backoff.nextDelayMs()
            assertFalse("attempt ${backoff.attempts} must not exhaust", backoff.isExhausted)
        }
        backoff.nextDelayMs()
        assertTrue(backoff.isExhausted)
    }

    @Test
    fun `attempts counts consumed delays`() {
        val backoff = ReconnectBackoff()
        assertEquals(0, backoff.attempts)
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        assertEquals(2, backoff.attempts)
    }

    @Test
    fun `reset restarts the schedule from the base delay`() {
        val backoff = ReconnectBackoff()
        repeat(ReconnectBackoff.MAX_ATTEMPTS) { backoff.nextDelayMs() }
        backoff.reset()
        assertEquals(0, backoff.attempts)
        assertFalse(backoff.isExhausted)
        assertEquals(2000L, backoff.nextDelayMs())
    }

    @Test
    fun `delay plateaus at the exponent cap beyond five attempts`() {
        // Custom budget larger than the exponent cap: the 6th and 7th delays
        // must stay at 32 s, not keep doubling.
        val backoff = ReconnectBackoff(baseDelayMs = 2000L, maxAttempts = 7)
        repeat(5) { backoff.nextDelayMs() }
        assertEquals(32000L, backoff.nextDelayMs())
        assertEquals(32000L, backoff.nextDelayMs())
        assertTrue(backoff.isExhausted)
    }
}
