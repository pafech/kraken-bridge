package com.krakenbridge.bdd.steps

import com.krakenbridge.KrakenBleService
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Step definitions that simulate Kraken housing button presses and assert
 * the resulting state changes inside [KrakenBleService].
 *
 * These steps bypass the BLE layer intentionally: they call [KrakenBleService.simulateButtonPress]
 * so that the full routing logic (mode guards, debouncing, wake locks, etc.) is exercised without
 * requiring physical hardware.
 */
class ButtonSteps {

    private val service: KrakenBleService?
        get() = KrakenBleService.instance

    // ── When – button presses ─────────────────────────────────────────────────

    @When("the shutter button is pressed")
    fun pressShutter() = simulateAndWait(KrakenBleService.BTN_SHUTTER_PRESS, 800)

    @When("the Fn button is pressed")
    fun pressFn() = simulateAndWait(KrakenBleService.BTN_FN_PRESS, 800)

    @When("the Back button is pressed")
    fun pressBack() = simulateAndWait(KrakenBleService.BTN_BACK_PRESS, 1200)

    @When("the Plus button is pressed")
    fun pressPlus() = simulateAndWait(KrakenBleService.BTN_PLUS_PRESS, 500)

    @When("the Minus button is pressed")
    fun pressMinus() = simulateAndWait(KrakenBleService.BTN_MINUS_PRESS, 500)

    @When("the OK button is pressed")
    fun pressOk() = simulateAndWait(KrakenBleService.BTN_OK_PRESS, 2500)

    // ── Then – BLE service state assertions ───────────────────────────────────

    @Then("the BLE service is in video mode")
    fun assertVideoMode() {
        assertTrue("Expected video mode", service?.testIsVideoMode ?: false)
    }

    @Then("the BLE service is in photo mode")
    fun assertPhotoMode() {
        assertFalse("Expected photo mode (not video)", service?.testIsVideoMode ?: false)
    }

    @Then("the BLE service is in gallery mode")
    fun assertGalleryMode() {
        assertTrue("Expected gallery mode", service?.testIsGalleryMode ?: false)
    }

    @Then("the BLE service is in camera mode")
    fun assertCameraMode() {
        assertFalse("Expected camera mode (not gallery)", service?.testIsGalleryMode ?: false)
    }

    @Then("the camera-open flag is set")
    fun assertCameraOpen() {
        assertTrue("Expected cameraIsOpen flag to be set", service?.testCameraIsOpen ?: false)
    }

    @Then("the recording flag is set to true")
    fun assertRecordingTrue() {
        assertTrue("Expected recording to be in progress", service?.testIsRecording ?: false)
    }

    @Then("the recording flag is set to false")
    fun assertRecordingFalse() {
        assertFalse("Expected recording to have stopped", service?.testIsRecording ?: false)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun simulateAndWait(buttonCode: Int, waitMs: Long) {
        service?.simulateButtonPress(buttonCode)
        Thread.sleep(waitMs)
    }
}
