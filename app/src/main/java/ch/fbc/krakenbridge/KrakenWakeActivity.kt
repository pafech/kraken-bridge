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
 */
class KrakenWakeActivity : Activity() {

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

        // Hand control back to whatever activity (camera) was started by
        // the BLE service moments before. Short delay lets the system
        // process the wake + keyguard dismiss before we finish.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) finish()
        }, 600)
    }
}
