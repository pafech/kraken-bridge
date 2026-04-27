package ch.fbc.krakenbridge.bdd.steps

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import ch.fbc.krakenbridge.KrakenBleService
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/**
 * Steps for screen_overlay.feature.
 *
 * All scenarios in that feature are tagged @manual: they exercise the
 * SYSTEM_ALERT_WINDOW overlay, which depends on a special permission the
 * emulator cannot grant non-interactively. The step definitions below are
 * still useful when running scenarios on a maintainer device with the
 * permission already granted, and they document the contract.
 */
class ScreenOverlaySteps {

    private val context get() =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Given("the SYSTEM_ALERT_WINDOW permission is granted")
    fun assertOverlayPermission() {
        check(android.provider.Settings.canDrawOverlays(context)) {
            "SYSTEM_ALERT_WINDOW not granted — grant it via Settings before running"
        }
    }

    @When("the BLE service is started with ACTION_CONNECT")
    fun startBleService() {
        val intent = Intent(context, KrakenBleService::class.java).apply {
            action = KrakenBleService.ACTION_CONNECT
        }
        context.startForegroundService(intent)
        Thread.sleep(500)
    }

    @Then("the overlay window is attached")
    fun assertOverlayAttached() {
        val service = KrakenBleService.instance ?: error("Service not running")
        val overlay = service.testOverlayManager ?: error("Overlay manager not initialised")
        check(overlay.testIsAttached) { "Overlay window is not attached" }
    }

    @Then("the overlay window is detached")
    fun assertOverlayDetached() {
        val service = KrakenBleService.instance
        if (service == null) return // Service teardown also detaches the overlay
        val overlay = service.testOverlayManager ?: return
        check(!overlay.testIsAttached) { "Overlay window is still attached" }
    }

    @Given("the overlay is attached")
    fun ensureOverlayAttached() {
        startBleService()
        assertOverlayAttached()
    }

    @Given("the overlay is attached and dimmed")
    fun ensureOverlayDimmed() {
        ensureOverlayAttached()
        val overlay = KrakenBleService.instance!!.testOverlayManager!!
        overlay.testForceDim()
    }

    @Given("the idle timeout is set to {int} milliseconds")
    fun setIdleTimeout(ms: Int) {
        val overlay = KrakenBleService.instance!!.testOverlayManager!!
        overlay.testSetIdleTimeoutMs(ms.toLong())
    }

    @When("{int} second passes without a button event")
    fun waitOneSecond(seconds: Int) {
        Thread.sleep(seconds * 1000L)
    }

    @When("{int} seconds pass without a button event")
    fun waitSeconds(seconds: Int) {
        Thread.sleep(seconds * 1000L)
    }

    @Then("the overlay brightness is at the dim level")
    fun assertDimmed() {
        val overlay = KrakenBleService.instance!!.testOverlayManager!!
        val brightness = overlay.testCurrentBrightness
            ?: error("Overlay not attached, cannot read brightness")
        check(brightness == 0f) { "Expected dim brightness 0f but was $brightness" }
    }

    @Then("the overlay brightness is back at the bright level")
    fun assertBright() {
        val overlay = KrakenBleService.instance!!.testOverlayManager!!
        val brightness = overlay.testCurrentBrightness
            ?: error("Overlay not attached, cannot read brightness")
        check(brightness < 0f) {
            "Expected BRIGHTNESS_OVERRIDE_NONE (-1) but was $brightness"
        }
    }

    @Then("the idle timer has restarted")
    fun assertTimerRestarted() {
        // Implicit: onUserActivity restarts the timer. We assert behaviour
        // (brightness stays bright after a short wait shorter than the
        // timeout) rather than introspecting the Handler directly.
        Thread.sleep(100)
        assertBright()
    }

    @When("the user disconnects via the Disconnect action")
    fun userDisconnect() {
        val intent = Intent(context, KrakenBleService::class.java).apply {
            action = KrakenBleService.ACTION_DISCONNECT
        }
        context.startService(intent)
        Thread.sleep(500)
    }

    @When("the user swipes the app from Recents") fun userSwipesFromRecents() {
        // onTaskRemoved is the same code path as ACTION_DISCONNECT for our
        // purposes (both call userDisconnect()). Reuse it here rather than
        // simulating the actual swipe gesture.
        userDisconnect()
    }

    @When("a video recording starts")
    fun videoRecordingStarts() {
        val service = KrakenBleService.instance ?: error("Service not running")
        // Switch into video mode if needed, then trigger record-start.
        if (!service.testIsVideoMode) {
            service.simulateButtonPress(KrakenBleService.BTN_FN_PRESS)
            Thread.sleep(800)
        }
        if (!service.testIsRecording) {
            service.simulateButtonPress(KrakenBleService.BTN_SHUTTER_PRESS)
            Thread.sleep(500)
        }
    }

    @Given("a video recording is in progress")
    fun ensureRecordingInProgress() {
        videoRecordingStarts()
    }

    @When("the video recording stops")
    fun videoRecordingStops() {
        val service = KrakenBleService.instance ?: error("Service not running")
        if (service.testIsRecording) {
            service.simulateButtonPress(KrakenBleService.BTN_SHUTTER_PRESS)
            Thread.sleep(500)
        }
    }

    @Then("the keep-bright flag is set")
    fun assertKeepBrightSet() {
        val overlay = KrakenBleService.instance!!.testOverlayManager!!
        check(overlay.testIsKeepBright) { "keep-bright flag is not set" }
    }

    @Then("the keep-bright flag is cleared")
    fun assertKeepBrightCleared() {
        val overlay = KrakenBleService.instance!!.testOverlayManager!!
        check(!overlay.testIsKeepBright) { "keep-bright flag is still set" }
    }

    @Then("the idle timer is running")
    fun assertIdleTimerRunning() {
        // Behavioural assertion: keep-bright is cleared, so scheduleDim has
        // re-armed the runnable. We verify by waiting just past the (test)
        // timeout and observing that the brightness drops.
        Thread.sleep(700)
        assertDimmed()
    }

    @Given("the camera has not been opened yet this session")
    fun ensureCameraNotOpened() {
        val service = KrakenBleService.instance ?: error("Service not running")
        check(!service.testCameraIsOpen) { "Camera was already opened this session" }
    }

    @Then("the camera-open flag is still cleared")
    fun assertCameraOpenFlagCleared() {
        val service = KrakenBleService.instance ?: error("Service not running")
        check(!service.testCameraIsOpen) {
            "Camera-open flag was set — wake-tap was not absorbed"
        }
    }
}
