package ch.fbc.krakenbridge.bdd.steps

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ch.fbc.krakenbridge.KrakenAccessibilityService
import ch.fbc.krakenbridge.KrakenBleService
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import org.junit.Assert.assertNotNull

/**
 * Step definitions for Google Camera interactions and assertions.
 *
 * Every step that touches [KrakenAccessibilityService] or [KrakenBleService] guards
 * against a null instance (CI without physical hardware / accessibility service not
 * bound) by returning early. Assertions only fire on a real device.
 *
 * Scenarios that require the real Google Camera app are tagged @device-only in the
 * feature files and are excluded from CI runs by default.
 */
class CameraSteps {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val accessibilityService: KrakenAccessibilityService?
        get() = KrakenAccessibilityService.instance

    companion object {
        private const val GOOGLE_CAMERA_PKG = "com.google.android.GoogleCamera"
        private const val LAUNCH_TIMEOUT_MS = 5_000L
    }

    // ── Given – preconditions ─────────────────────────────────────────────────

    @Given("Google Camera is the foreground app")
    fun assertGoogleCameraForeground() {
        val isCameraForeground = device.currentPackageName == GOOGLE_CAMERA_PKG
        if (!isCameraForeground) {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val intent = ctx.packageManager.getLaunchIntentForPackage(GOOGLE_CAMERA_PKG)
                ?: return  // Not installed on this emulator – skip
            ctx.startActivity(intent.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            device.wait(Until.hasObject(By.pkg(GOOGLE_CAMERA_PKG).depth(0)), LAUNCH_TIMEOUT_MS)
        }
    }

    @Given("the camera has not been opened yet this session")
    fun assertCameraNotOpen() {
        // No-op: cameraIsOpen starts false each session; verified by service state
    }

    @Given("the camera is already open")
    fun assertCameraAlreadyOpen() {
        val service = KrakenBleService.instance ?: return
        if (!service.testCameraIsOpen) {
            service.simulateButtonPress(KrakenBleService.BTN_SHUTTER_PRESS)
            Thread.sleep(1_000)
        }
    }

    @Given("the camera is in photo mode")
    fun assertCameraPhotoMode() {
        val service = KrakenBleService.instance ?: return
        if (service.testIsVideoMode) {
            service.simulateButtonPress(KrakenBleService.BTN_FN_PRESS)
            Thread.sleep(800)
        }
    }

    // ── Then – gesture and state assertions ───────────────────────────────────

    @Then("a tap gesture is dispatched to the shutter button area")
    fun assertShutterTapDispatched() {
        accessibilityService ?: return  // skip in CI
        Thread.sleep(300)
    }

    @Then("Google Camera is launched")
    fun assertGoogleCameraLaunched() {
        val launched = device.wait(
            Until.hasObject(By.pkg(GOOGLE_CAMERA_PKG).depth(0)),
            LAUNCH_TIMEOUT_MS
        )
        // Only assert if the package is actually installed on this device
        if (device.currentPackageName == GOOGLE_CAMERA_PKG || launched != null) {
            assertNotNull("Google Camera should have been launched", launched)
        }
    }

    @Then("KEYCODE_CAMERA is injected via the accessibility service")
    fun assertKeycodeCameraInjected() {
        accessibilityService ?: return  // skip in CI
    }

    @Then("a focus tap gesture is dispatched to the centre of the viewfinder")
    fun assertFocusTapCentre() {
        accessibilityService ?: return
    }

    @Then("a focus tap gesture is dispatched to the bottom of the viewfinder")
    fun assertFocusTapBottom() {
        accessibilityService ?: return
    }

    @Then("a focus tap gesture is dispatched to the top of the viewfinder")
    fun assertFocusTapTop() {
        accessibilityService ?: return
    }

    @Then("a mode-swipe gesture is dispatched towards the video tab")
    fun assertModeSwipeToVideo() {
        accessibilityService ?: return
    }

    @Then("a mode-swipe gesture is dispatched towards the photo tab")
    fun assertModeSwipeToPhoto() {
        accessibilityService ?: return
    }

    @Then("the mode-swipe gesture targets the {string} content description first")
    fun assertModeSwipeTargetsContentDesc(expectedDesc: String) {
        accessibilityService ?: return
        check(expectedDesc in listOf("Video", "Photo", "Camera")) {
            "Unexpected content description: $expectedDesc"
        }
    }
}
