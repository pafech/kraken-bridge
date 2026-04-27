package ch.fbc.krakenbridge

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Maintains a transparent, full-screen system overlay over Camera / Photos
 * for the duration of a connected dive session.
 *
 * Why this class exists:
 *   The phone is sealed inside a Kraken housing for hours. A secure keyguard
 *   (PIN / biometrics) cannot be unlocked underwater, and on most modern
 *   Android builds the diver cannot disable it either (stored credentials,
 *   work profiles, OEM policy all force a secure lock). The lockscreen only
 *   appears after the screen actually turns off — so we keep the screen on,
 *   but dim it ourselves to save battery.
 *
 * How it works:
 *   - Adds a single [View] via [WindowManager] of type
 *     TYPE_APPLICATION_OVERLAY with FLAG_KEEP_SCREEN_ON. The system never
 *     fires its screen-off timer while the overlay is attached, so the
 *     keyguard never engages.
 *   - FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE means every touch event flows
 *     through to whatever app is on top (Camera, Photos), so the underlying
 *     UI stays fully interactive.
 *   - The per-window screenBrightness is the only knob we use to manage
 *     power: BRIGHTNESS_OVERRIDE_NONE (-1) follows the user's preferred
 *     brightness; 0f drops the backlight to its hardware minimum (on OLED
 *     this is effectively black at near-zero power).
 *   - [onUserActivity] should be called on every BLE button event. It
 *     restores brightness immediately and resets the idle timer.
 *
 * Lifecycle:
 *   Owned by [KrakenBleService]. Started when a connection is established,
 *   stopped on userDisconnect / onTaskRemoved / onDestroy. Safe to call
 *   start / stop repeatedly.
 *
 * Permission:
 *   Requires SYSTEM_ALERT_WINDOW (Settings.canDrawOverlays). The permission
 *   walkthrough in [MainActivity] requests it; [start] is a no-op if it
 *   was revoked.
 */
class KrakenScreenOverlayManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS
    private val dimBrightness: Float = DIM_BRIGHTNESS
    private val brightBrightness: Float = BRIGHT_BRIGHTNESS

    // Reflects the visible brightness state. Mutated only on the main
    // thread inside dim() / restoreBrightnessOnMain(); read from any
    // thread by [consumeWakeIfDim] so the BLE service can decide whether
    // to absorb the originating button event.
    @Volatile
    private var isDim: Boolean = false

    /**
     * When true the idle dimmer is suspended and the overlay stays at full
     * brightness no matter how long since the last user activity. Used by
     * the BLE service while a video recording is in progress — divers
     * routinely shoot longer than the 30 s idle window and need to keep an
     * eye on framing.
     */
    private var keepBright: Boolean = false

    private val dimRunnable = Runnable { dim() }

    /**
     * Public methods are callable from any thread (BLE callbacks fire on a
     * Binder thread). All WindowManager mutations are marshalled onto the
     * main looper, since `addView` / `updateViewLayout` / `removeView`
     * require the thread that owns the View — which for an overlay is the
     * main thread.
     */
    fun start() = handler.post { startOnMain() }

    fun stop() = handler.post { stopOnMain() }

    /**
     * Reset the idle timer and bring the screen back to full brightness.
     * Idempotent — safe to call on every BLE event.
     */
    fun onUserActivity() = handler.post { restoreBrightnessOnMain() }

    /**
     * Wake the overlay and tell the caller whether the visible brightness
     * was actually dim at the moment of the call. The BLE service uses
     * this to absorb the originating button press: a diver who taps a
     * housing button on a dimmed screen expects the first tap to *wake*,
     * not to take a photo / start a recording / switch modes. The next
     * press (after the dim is gone) does the real action.
     *
     * Returns the dim state synchronously even though the brightness
     * restore is dispatched onto the main thread, so the caller can act
     * on the answer right away.
     */
    fun consumeWakeIfDim(): Boolean {
        val wasDim = isDim
        handler.post { restoreBrightnessOnMain() }
        return wasDim
    }

    /**
     * Suspend (true) or resume (false) the idle dimmer. While suspended the
     * overlay is held at full brightness and no auto-dim is scheduled, so a
     * long video recording does not cut to black mid-shot.
     */
    fun setKeepBright(active: Boolean) = handler.post {
        if (keepBright == active) return@post
        keepBright = active
        if (active) {
            handler.removeCallbacks(dimRunnable)
            restoreBrightnessOnMain()
            Log.i(TAG, "Idle dimmer suspended (keep-bright)")
        } else {
            scheduleDim()
            Log.i(TAG, "Idle dimmer resumed")
        }
    }

    private fun startOnMain() {
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — overlay not started")
            return
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenBrightness = brightBrightness
        }

        val v = View(context)
        try {
            windowManager.addView(v, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            return
        }
        view = v
        layoutParams = params
        scheduleDim()
        Log.i(TAG, "Overlay attached, idle timeout=${idleTimeoutMs}ms")
    }

    private fun stopOnMain() {
        handler.removeCallbacks(dimRunnable)
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (e: IllegalArgumentException) {
                // View was never attached or already removed
            }
        }
        view = null
        layoutParams = null
        Log.i(TAG, "Overlay detached")
    }

    private fun restoreBrightnessOnMain() {
        val params = layoutParams ?: return
        val v = view ?: return
        if (params.screenBrightness != brightBrightness) {
            params.screenBrightness = brightBrightness
            try {
                windowManager.updateViewLayout(v, params)
                isDim = false
                Log.d(TAG, "Brightness restored on user activity")
            } catch (e: Exception) {
                // updateViewLayout may throw IllegalArgumentException if the
                // view detached between checks, or other RuntimeExceptions
                // from the WindowManager. Either way we are not the right
                // thread or the window is gone — log and move on.
                Log.w(TAG, "updateViewLayout failed on restore", e)
                return
            }
        }
        scheduleDim()
    }

    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        if (keepBright) return
        handler.postDelayed(dimRunnable, idleTimeoutMs)
    }

    private fun dim() {
        val params = layoutParams ?: return
        val v = view ?: return
        params.screenBrightness = dimBrightness
        try {
            windowManager.updateViewLayout(v, params)
            isDim = true
            Log.d(TAG, "Overlay dimmed (idle)")
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed on dim", e)
        }
    }

    // ── Test-only hooks ──────────────────────────────────────────────────────
    // Mirror the pattern used by KrakenBleService: internal accessors so BDD
    // step definitions can drive and observe the overlay without reflection.

    internal val testIsAttached: Boolean get() = view != null
    internal val testCurrentBrightness: Float? get() = layoutParams?.screenBrightness
    internal val testIsKeepBright: Boolean get() = keepBright
    internal fun testForceDim() = handler.post { dim() }
    internal fun testSetIdleTimeoutMs(ms: Long) {
        idleTimeoutMs = ms
        handler.post { if (view != null) scheduleDim() }
    }

    companion object {
        private const val TAG = "KrakenOverlay"

        /**
         * Default time of BLE / touch / system silence before the overlay
         * dims itself. Long enough to frame a shot, observe the subject,
         * and time the shutter without the screen going dark mid-wait.
         */
        const val DEFAULT_IDLE_TIMEOUT_MS = 45_000L

        /**
         * Hardware minimum brightness. OLED panels render this as near-black
         * at near-zero power; LCDs hold the backlight at its lowest level.
         * Not the same as BRIGHTNESS_OVERRIDE_OFF (which on some devices
         * triggers a real screen-off and would defeat the whole point).
         */
        const val DIM_BRIGHTNESS = 0f

        /**
         * BRIGHTNESS_OVERRIDE_NONE — relinquish the override and follow the
         * diver's system-wide brightness preference.
         */
        const val BRIGHT_BRIGHTNESS = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}
