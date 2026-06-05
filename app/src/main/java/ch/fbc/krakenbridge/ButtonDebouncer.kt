package ch.fbc.krakenbridge

/**
 * Suppresses the duplicate delivery of a single housing button event: on
 * API < 33 vs 33+ both BLE characteristic-changed callbacks can fire for
 * the same notification, ~simultaneously, with the same code.
 *
 * The clock is injected so the window logic is JVM-unit-testable;
 * production passes System::currentTimeMillis.
 */
class ButtonDebouncer(
    private val windowMs: Long,
    private val clock: () -> Long
) {

    private var lastCode = -1
    private var lastTime = 0L

    /**
     * True when [code] is a fresh event; false when it repeats the previous
     * code within the window. Each accepted event re-arms the window.
     */
    @Synchronized
    fun shouldProcess(code: Int): Boolean {
        val now = clock()
        if (code == lastCode && (now - lastTime) < windowMs) {
            return false
        }
        lastCode = code
        lastTime = now
        return true
    }

    /** Forget the last event so the next one is never silently dropped. */
    @Synchronized
    fun reset() {
        lastCode = -1
        lastTime = 0L
    }
}
