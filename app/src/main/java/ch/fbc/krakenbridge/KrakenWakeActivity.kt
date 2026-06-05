package ch.fbc.krakenbridge

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Transparent, no-UI activity used to wake the screen and dismiss the
 * (insecure) keyguard so the camera app launched right after a housing
 * button press is actually visible. Replaces the deprecated
 * FULL_WAKE_LOCK / ACQUIRE_CAUSES_WAKEUP path, which is a no-op on
 * Android 10+.
 *
 * Lifetime: finishes the moment its window gains focus — focus gain *is*
 * the system's signal that turn-screen-on and keyguard dismissal have been
 * processed, which a fixed delay could only guess at (slow devices missed
 * a 600 ms window; fast ones wasted most of it). A fallback timer covers
 * the pathological case where focus never arrives (e.g. a secure keyguard
 * that refuses to dismiss), so the invisible activity can't linger forever.
 */
class KrakenWakeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val fallbackFinish = Runnable {
        if (!isFinishing) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        km?.requestDismissKeyguard(this, null)

        handler.postDelayed(fallbackFinish, FALLBACK_FINISH_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Focus means the screen is on and our window is frontmost — hand
        // control back to whatever activity (camera) the BLE service started.
        if (hasFocus && !isFinishing) {
            handler.removeCallbacks(fallbackFinish)
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(fallbackFinish)
        super.onDestroy()
    }

    private companion object {
        /** Generous upper bound — only reached when focus never arrives. */
        const val FALLBACK_FINISH_MS = 3000L
    }
}
