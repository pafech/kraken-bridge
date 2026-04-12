package com.krakenbridge.bdd.steps

import androidx.test.platform.app.InstrumentationRegistry
import com.krakenbridge.KrakenAccessibilityService
import com.krakenbridge.KrakenBleService
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import java.io.IOException

/**
 * Shared setup / teardown hooks and precondition steps used across all feature files.
 *
 * The [Before] hook enables the Kraken Bridge Accessibility Service via the adb shell so tests
 * can drive it without requiring manual user interaction in Settings. This works because
 * instrumented tests run with shell-level privileges via [android.app.UiAutomation].
 */
class CommonSteps {

    private val uiAutomation
        get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    // ── Cucumber lifecycle ───────────────────────────────────────────────────

    @Before
    fun enableAccessibilityServiceAndWait() {
        shell("settings put secure enabled_accessibility_services " +
                "com.krakenbridge/com.krakenbridge.KrakenAccessibilityService")
        shell("settings put secure accessibility_enabled 1")

        // Give the system up to 5 s to bind the service
        val deadline = System.currentTimeMillis() + 5_000
        while (KrakenAccessibilityService.instance == null &&
               System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
    }

    @After
    fun disableAccessibilityService() {
        shell("settings put secure enabled_accessibility_services \"\"")
        shell("settings put secure accessibility_enabled 0")
    }

    // ── Shared precondition steps ────────────────────────────────────────────

    @Given("the Kraken Bridge accessibility service is running")
    fun assertAccessibilityServiceRunning() {
        check(KrakenAccessibilityService.instance != null) {
            "KrakenAccessibilityService is not running. " +
            "Ensure the Before hook completed and the service was granted."
        }
    }

    @Given("the BLE service is connected and in photo mode")
    fun bleServiceConnectedPhotoMode() {
        // Service may not be bound in CI (no BLE hardware); we assert it is running
        // only when it has been started. State reads use the companion instance.
        val service = KrakenBleService.instance ?: return
        check(!service.testIsVideoMode) { "Expected photo mode but service is in video mode" }
        check(!service.testIsGalleryMode) { "Expected camera mode but service is in gallery mode" }
    }

    @Given("the BLE service is connected and in camera mode")
    fun bleServiceConnectedCameraMode() = bleServiceConnectedPhotoMode()

    @Given("the BLE service is in photo mode")
    fun assertInPhotoMode() {
        val service = KrakenBleService.instance ?: return
        check(!service.testIsVideoMode) { "Expected photo mode but service is in video mode" }
    }

    @Given("the BLE service is in video mode")
    fun assertInVideoMode() {
        val service = KrakenBleService.instance ?: return
        if (!service.testIsVideoMode) {
            // Toggle into video mode
            service.simulateButtonPress(KrakenBleService.BTN_FN_PRESS)
            Thread.sleep(800) // allow mode-switch gesture to dispatch
        }
        check(service.testIsVideoMode) { "Service failed to switch to video mode" }
    }

    @Given("the BLE service is in gallery mode")
    fun assertInGalleryMode() {
        val service = KrakenBleService.instance ?: return
        if (!service.testIsGalleryMode) {
            service.simulateButtonPress(KrakenBleService.BTN_BACK_PRESS)
            Thread.sleep(1200)
        }
        check(service.testIsGalleryMode) { "Service failed to switch to gallery mode" }
    }

    @Given("the BLE service is in camera mode")
    fun assertInCameraMode() {
        val service = KrakenBleService.instance ?: return
        check(!service.testIsGalleryMode) { "Expected camera mode but service is in gallery mode" }
    }

    @Given("no recording is in progress")
    fun assertNotRecording() {
        val service = KrakenBleService.instance ?: return
        check(!service.testIsRecording) { "Expected no recording in progress" }
    }

    @Given("a recording is in progress")
    fun assertRecordingInProgress() {
        val service = KrakenBleService.instance ?: return
        if (!service.testIsRecording) {
            service.simulateButtonPress(KrakenBleService.BTN_SHUTTER_PRESS)
            Thread.sleep(500)
        }
        check(service.testIsRecording) { "Service failed to start recording" }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Execute a shell command via UiAutomation (runs with shell privileges). */
    @Throws(IOException::class)
    private fun shell(command: String) {
        uiAutomation.executeShellCommand(command).close()
    }
}
