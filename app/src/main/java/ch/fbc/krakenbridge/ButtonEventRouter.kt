package ch.fbc.krakenbridge

import android.os.PowerManager
import android.util.Log
import ch.fbc.krakenbridge.KrakenBleService.Companion.BTN_MINUS_PRESS
import ch.fbc.krakenbridge.KrakenBleService.Companion.BTN_OK_PRESS
import ch.fbc.krakenbridge.KrakenBleService.Companion.BTN_PLUS_PRESS
import ch.fbc.krakenbridge.KrakenBleService.Companion.BTN_SHUTTER_PRESS
import ch.fbc.krakenbridge.KrakenBleService.Companion.DEBOUNCE_MS
import ch.fbc.krakenbridge.KrakenBleService.Companion.TAG
import kotlinx.coroutines.flow.StateFlow

/**
 * The single entry point for housing button events (real BLE notifications
 * and the BDD `simulateButtonPress` seam): deduplicates double-fired
 * callbacks, absorbs the wake-tap on a dimmed overlay, wakes the screen,
 * and dispatches to the camera or gallery controller depending on the
 * session mode. Runs on the BLE Binder thread.
 */
class ButtonEventRouter(
    private val state: StateFlow<KrakenServiceState>,
    private val overlayManager: KrakenScreenOverlayManager,
    private val powerManager: PowerManager,
    private val wakeScreen: () -> Unit,
    private val cameraController: CameraController,
    private val galleryController: GalleryController
) {

    // Deduplication for button events (both legacy and new BLE callbacks may fire)
    private var lastButtonCode = -1
    private var lastButtonTime = 0L

    fun route(code: Int) {
        // Deduplicate: both legacy and new BLE callbacks may fire for same event
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (code == lastButtonCode && (now - lastButtonTime) < DEBOUNCE_MS) {
                Log.d(TAG, "Button event: 0x${code.toString(16)} (deduplicated, ignoring)")
                return
            }
            lastButtonCode = code
            lastButtonTime = now
        }

        Log.d(TAG, "Button event: 0x${code.toString(16)}")

        // Wake the overlay first. If the screen was dimmed, swallow the
        // event: the diver tapped to wake, not to act. The next press
        // takes the photo / focuses / switches mode as usual. Without
        // this, the diver who's been observing a subject for a minute
        // would lose their composed shot to a wake-tap that fired the
        // shutter immediately.
        if (overlayManager.consumeWakeIfDim()) {
            Log.d(TAG, "Button absorbed as wake-tap (overlay was dim)")
            return
        }

        // Check if screen is off - if so, wake device first
        if (!powerManager.isInteractive) {
            Log.i(TAG, "Screen is off - waking device")
            wakeScreen()
        }

        if (state.value.isGalleryMode) {
            // Gallery mode: navigate and manage photos
            galleryController.handleButton(code)
        } else {
            // Only buttons that dispatch a tap into the camera's accessibility
            // tree need it to be foreground. Fn (mode toggle) and Back (open
            // gallery) re-launch their target app themselves, so they work
            // even if the diver opened a different app. Limiting the guard
            // here also avoids swallowing the first Fn press while the
            // accessibility tree is still catching up to the current window.
            val needsCameraForeground = code in CAMERA_TAP_BUTTONS
            if (needsCameraForeground && state.value.isCameraOpen &&
                !cameraController.isCameraForeground()
            ) {
                Log.i(TAG, "Button 0x${code.toString(16)} -> camera not foreground, refocusing")
                cameraController.openCamera()
                return
            }
            cameraController.handleButton(code)
        }
    }

    /** Reset dedup so the first event after a reconnect is never silently dropped. */
    fun resetDebounce() {
        synchronized(this) {
            lastButtonCode = -1
            lastButtonTime = 0L
        }
    }

    companion object {
        // Buttons whose handler injects a tap or key event into the camera's
        // accessibility tree. They require the camera to be the foreground
        // app; otherwise the dispatch lands in some other window and looks
        // like nothing happened to the diver.
        private val CAMERA_TAP_BUTTONS = setOf(
            BTN_SHUTTER_PRESS, BTN_OK_PRESS, BTN_PLUS_PRESS, BTN_MINUS_PRESS
        )
    }
}
