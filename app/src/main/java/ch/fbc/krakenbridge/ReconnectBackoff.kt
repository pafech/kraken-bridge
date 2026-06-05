package ch.fbc.krakenbridge

/**
 * Exponential-backoff schedule for BLE reconnect attempts:
 * 2 s → 4 s → 8 s → 16 s → 32 s, then exhausted (the caller falls back to
 * a fresh scan).
 *
 * Pure state machine, no timers — [BleConnectionManager] owns the actual
 * scheduling. Extracted so the retry arithmetic is unit-testable without a
 * service or a Handler. (Debt item 4 asked for a coroutine Flow + retryWhen
 * rewrite; that was rejected as a decision because restructuring the GATT
 * callback flow cannot be validated without housing hardware. The
 * testability and readability goals land here instead.)
 */
class ReconnectBackoff(
    private val baseDelayMs: Long = BASE_DELAY_MS,
    private val maxAttempts: Int = MAX_ATTEMPTS
) {

    /** Attempts consumed since the last [reset]; exposed for log/status text. */
    var attempts: Int = 0
        private set

    val isExhausted: Boolean get() = attempts >= maxAttempts

    /**
     * Delay for the next attempt, doubling per attempt (exponent capped at 4
     * so the schedule plateaus at 32 s). Consumes one attempt.
     */
    fun nextDelayMs(): Long {
        val delay = baseDelayMs * (1L shl attempts.coerceAtMost(4))
        attempts++
        return delay
    }

    fun reset() {
        attempts = 0
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val BASE_DELAY_MS = 2000L
    }
}
