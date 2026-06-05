package ch.fbc.krakenbridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import ch.fbc.krakenbridge.KrakenBleService.Companion.BTN_BACK_PRESS
import ch.fbc.krakenbridge.KrakenBleService.Companion.BTN_FN_PRESS
import ch.fbc.krakenbridge.KrakenBleService.Companion.BTN_MINUS_PRESS
import ch.fbc.krakenbridge.KrakenBleService.Companion.BTN_OK_PRESS
import ch.fbc.krakenbridge.KrakenBleService.Companion.BTN_PLUS_PRESS
import ch.fbc.krakenbridge.KrakenBleService.Companion.BTN_SHUTTER_PRESS
import ch.fbc.krakenbridge.KrakenBleService.Companion.TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Camera-mode button handling: shutter / focus taps, photo↔video mode
 * switching, the recording state machine (with its wake lock), and
 * launching the user's default camera app.
 *
 * Runs on the BLE Binder thread (button events) and the main thread
 * (delayed mode swipes) — all shared state lives in the atomic [state]
 * flow, mirroring the pre-decomposition service exactly.
 */
class CameraController(
    private val context: Context,
    private val state: MutableStateFlow<KrakenServiceState>,
    private val handler: Handler,
    private val wakeLocks: WakeLockHolder,
    private val features: () -> Features,
    private val updateNotification: (String) -> Unit,
    private val onToggleGallery: () -> Unit
) {

    // Packages that can handle STILL_IMAGE_CAMERA — used to detect when the
    // foreground is something else (e.g. our own MainActivity) so a button
    // press can refocus the camera instead of being dispatched into the
    // wrong app's accessibility tree. Resolved once at construction; install /
    // uninstall of a camera app mid-session is an edge case we don't handle.
    private val cameraPackages: Set<String> = resolveCameraPackages()

    fun handleButton(code: Int) {
        when (code) {
            BTN_SHUTTER_PRESS -> {
                val current = state.value
                if (!current.isCameraOpen) {
                    // First press: just open camera
                    openCamera()
                    state.update { it.withCameraOpened() }
                    Log.i(TAG, "Shutter pressed -> opened camera (first press)")
                } else if (current.isVideoMode) {
                    // Video mode: toggle recording
                    val nowRecording = state
                        .updateAndGet { it.withRecordingToggled() }
                        .isRecording
                    if (nowRecording) {
                        // Starting video recording - keep screen on
                        wakeLocks.acquireVideo()
                        Log.i(TAG, "Shutter pressed -> starting video recording")
                    } else {
                        // Stopping video recording - release wake lock
                        wakeLocks.releaseVideo()
                        Log.i(TAG, "Shutter pressed -> stopping video recording")
                    }
                    injectKeyEvent(KeyEvent.KEYCODE_CAMERA)
                } else {
                    // Photo mode: take photo
                    injectKeyEvent(KeyEvent.KEYCODE_CAMERA)
                    Log.i(TAG, "Shutter pressed -> take photo")
                }
            }
            BTN_OK_PRESS -> {
                // OK = auto-focus (tap center of viewfinder)
                injectKeyEvent(KeyEvent.KEYCODE_FOCUS)
                Log.i(TAG, "OK pressed -> AUTO-FOCUS (center)")
            }
            BTN_PLUS_PRESS -> {
                // Plus = focus closer (tap bottom of viewfinder)
                injectKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN)
                Log.i(TAG, "Plus pressed -> focus CLOSER")
            }
            BTN_MINUS_PRESS -> {
                // Minus = focus farther (tap top of viewfinder)
                injectKeyEvent(KeyEvent.KEYCODE_DPAD_UP)
                Log.i(TAG, "Minus pressed -> focus FARTHER")
            }
            BTN_BACK_PRESS -> {
                if (features().gallery) {
                    onToggleGallery()
                } else {
                    Log.i(TAG, "Back pressed -> gallery feature disabled, ignoring")
                    handler.post {
                        Toast.makeText(
                            context,
                            "Gallery is disabled — enable it in app settings",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            BTN_FN_PRESS -> {
                // Fn = toggle between photo and video mode
                toggleCameraMode()
            }
        }
    }

    /**
     * Release the recording wake lock when leaving the recording context
     * (mode switch, gallery switch, session reset) — a held SCREEN_DIM lock
     * with nobody recording just burns battery.
     */
    fun stopRecordingIfActive() {
        if (state.value.isRecording) {
            state.update { it.withRecordingStopped() }
            wakeLocks.releaseVideo()
        }
    }

    /**
     * Re-entry from gallery mode: reopen the camera and re-apply the
     * previously active capture mode after the camera resumes. A
     * freshly-opened camera typically lands in photo mode even when the
     * diver left from video — without this re-sync, the next Fn press would
     * toggle isVideoMode against a wrong assumption and need two presses to
     * land on video. The adapter checks the toggle's checked state and
     * no-ops when already in the target mode, so this is safe when modes
     * happen to align.
     */
    fun resumeFromGallery() {
        openCamera()
        val modeName = if (state.value.isVideoMode) "VIDEO" else "PHOTO"
        updateNotification("Ready - $modeName mode")
        Log.i(TAG, "Switched back to CAMERA mode")
        handler.postDelayed({
            swipeToSwitchCameraMode(state.value.isVideoMode)
        }, 600)
    }

    fun isCameraForeground(): Boolean {
        val foreground = KrakenAccessibilityService.instance?.currentForegroundPackage
            ?: return false
        return foreground in cameraPackages
    }

    fun openCamera() {
        // INTENT_ACTION_STILL_IMAGE_CAMERA opens the user's default camera app
        // in normal capture mode. Unlike ACTION_IMAGE_CAPTURE (which is the
        // "capture for caller" contract and hands a single photo back to us),
        // this leaves the camera in free-shooting mode with full UI — the
        // experience the diver expects on every BLE shutter press.
        // No setPackage: the system honours whichever camera app the user
        // has chosen as default.
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Opened default camera via STILL_IMAGE_CAMERA")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open default camera: ${e.message}")
        }
    }

    private fun toggleCameraMode() {
        // If switching away from video mode while recording, release wake lock
        stopRecordingIfActive()

        val toVideo = state
            .updateAndGet { it.withCameraModeToggled() }
            .isVideoMode
        val modeName = if (toVideo) "VIDEO" else "PHOTO"
        Log.i(TAG, "Fn pressed -> switching to $modeName mode")

        // Open camera first
        openCamera()

        // Then swipe to switch mode
        handler.postDelayed({
            swipeToSwitchCameraMode(toVideo)
            updateNotification("Ready - $modeName mode")
        }, 600)
    }

    private fun swipeToSwitchCameraMode(toVideo: Boolean) {
        // Send swipe gesture via accessibility service to switch photo/video
        KrakenAccessibilityService.instance?.dispatchModeSwipeGesture(toVideo)
    }

    private fun injectKeyEvent(keyCode: Int) {
        // Try to use AccessibilityService directly if available
        val accessibilityService = KrakenAccessibilityService.instance
        if (accessibilityService != null) {
            Log.d(TAG, "Using AccessibilityService for key injection")
            accessibilityService.injectKey(keyCode)
        } else {
            // Fallback: broadcast intent (service might be running but instance not yet set)
            Log.d(TAG, "Broadcasting key injection request")
            val intent = Intent(KrakenAccessibilityService.ACTION_INJECT_KEY).apply {
                putExtra(KrakenAccessibilityService.EXTRA_KEY_CODE, keyCode)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    private fun resolveCameraPackages(): Set<String> {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .toSet()
    }
}
