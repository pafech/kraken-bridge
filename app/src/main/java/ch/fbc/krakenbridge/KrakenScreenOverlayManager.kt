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
        handler.postDelayed(dimRunnable, idleTimeoutMs)
    }

    private fun dim() {
        val params = layoutParams ?: return
        val v = view ?: return
        params.screenBrightness = dimBrightness
        try {
            windowManager.updateViewLayout(v, params)
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
    internal fun testForceDim() = handler.post { dim() }
    internal fun testSetIdleTimeoutMs(ms: Long) {
        idleTimeoutMs = ms
        handler.post { if (view != null) scheduleDim() }
    }

    companion object {
        private const val TAG = "KrakenOverlay"

        /** Default time of BLE silence before we drop the brightness. */
        const val DEFAULT_IDLE_TIMEOUT_MS = 30_000L

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
