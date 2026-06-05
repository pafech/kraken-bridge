package ch.fbc.krakenbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent

/**
 * Write-side of the accessibility automation: coordinate taps, swipes,
 * the viewfinder focus-zone maths, and media-key dispatch.
 *
 * Owned by [KrakenAccessibilityService] — gestures can only be dispatched
 * through a connected AccessibilityService instance, so this class wraps
 * it rather than replacing it. Screen size comes through live providers
 * because it changes on configuration changes.
 */
class GestureDispatcher(
    private val service: AccessibilityService,
    private val audioManager: AudioManager,
    private val screenWidth: () -> Int,
    private val screenHeight: () -> Int
) {

    fun tap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "Tap gesture completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap gesture cancelled")
            }
        }, null)
    }

    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "Swipe completed ($startX,$startY)→($endX,$endY) in ${durationMs}ms")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe cancelled ($startX,$startY)→($endX,$endY)")
            }
        }, null)
    }

    /**
     * Swipe left/right to navigate photos in the foreground gallery.
     * Vendor-neutral coordinate gesture — works against any single-photo viewer
     * that uses a horizontal photo carousel.
     *
     * @param next true = swipe left (next photo), false = swipe right (previous photo)
     */
    fun gallerySwipe(next: Boolean) {
        val y = screenHeight() * 0.5f
        val startX = if (next) screenWidth() * 0.8f else screenWidth() * 0.2f
        val endX = if (next) screenWidth() * 0.2f else screenWidth() * 0.8f
        Log.i(TAG, "Gallery swipe (next=$next)")
        swipe(startX, y, endX, y, durationMs = 200L)
    }

    /**
     * Tap different areas of the viewfinder to set focus.
     * NOTE: This intentionally uses coordinates because we're tapping specific areas
     * of the camera viewfinder itself (not UI buttons). The viewfinder doesn't have
     * accessibility nodes for "top", "center", "bottom" - we tap arbitrary points
     * to trigger focus at different depths.
     */
    fun focusTap(zone: FocusZone) {
        val x = screenWidth() / 2f
        val viewfinderTop = screenHeight() * 0.05f
        val viewfinderBottom = screenHeight() * 0.58f
        val viewfinderHeight = viewfinderBottom - viewfinderTop

        val y = when (zone) {
            FocusZone.NEAR -> viewfinderBottom - (viewfinderHeight * 0.15f)   // Bottom of viewfinder
            FocusZone.CENTER -> viewfinderTop + (viewfinderHeight * 0.5f)     // Center of viewfinder
            FocusZone.FAR -> viewfinderTop + (viewfinderHeight * 0.15f)       // Top of viewfinder
        }

        Log.i(TAG, "Focus tap at zone $zone: ($x, $y)")
        tap(x, y)
    }

    /** Dispatch a media-button down/up pair (e.g. KEYCODE_MEDIA_RECORD). */
    fun mediaKey(keyCode: Int) {
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)

        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private companion object {
        const val TAG = KrakenAccessibilityService.TAG
    }
}
