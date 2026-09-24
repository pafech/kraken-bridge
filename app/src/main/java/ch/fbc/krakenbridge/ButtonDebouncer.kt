package ch.fbc.krakenbridge

/**
 * Drops a repeat of the same housing button code within a short window.
 * It was added because both BLE characteristic-changed callbacks were
 * thought to fire for one notification. They do not: BleConnectionManager
 * overrides both overloads without calling super, so each notification
 * arrives once (a Pixel 9 Pro on Android 17 logged 136 events and no
 * duplicate). It stays as a cheap guard against a repeated notification.
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
