package com.krakenbridge.bdd.steps

import com.krakenbridge.KrakenBleService
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/**
 * Step definitions that simulate Kraken housing button presses and assert
 * the resulting state changes inside [KrakenBleService].
 *
 * When the BLE service is not running (CI without physical hardware) every step
 * returns early without asserting — the scenario passes vacuously. Assertions
 * are only exercised on a real device where the service is connected.
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
    // All assertions guard against a null service (CI without BLE hardware) by
    // returning early. On a real device the service is running and assertions fire.

    @Then("the BLE service is in video mode")
    fun assertVideoMode() {
        val s = service ?: return
        check(s.testIsVideoMode) { "Expected video mode but service is in photo mode" }
    }

    @Then("the BLE service is in photo mode")
    fun assertPhotoMode() {
        val s = service ?: return
        check(!s.testIsVideoMode) { "Expected photo mode but service is in video mode" }
    }

    @Then("the BLE service is in gallery mode")
    fun assertGalleryMode() {
        val s = service ?: return
        check(s.testIsGalleryMode) { "Expected gallery mode but service is in camera mode" }
    }

    @Then("the BLE service is in camera mode")
    fun assertCameraMode() {
        val s = service ?: return
        check(!s.testIsGalleryMode) { "Expected camera mode but service is in gallery mode" }
    }

    @Then("the camera-open flag is set")
    fun assertCameraOpen() {
        val s = service ?: return
        check(s.testCameraIsOpen) { "Expected cameraIsOpen flag to be set" }
    }

    @Then("the recording flag is set to true")
    fun assertRecordingTrue() {
        val s = service ?: return
        check(s.testIsRecording) { "Expected recording to be in progress" }
    }

    @Then("the recording flag is set to false")
    fun assertRecordingFalse() {
        val s = service ?: return
        check(!s.testIsRecording) { "Expected recording to have stopped" }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun simulateAndWait(buttonCode: Int, waitMs: Long) {
        service?.simulateButtonPress(buttonCode)
        Thread.sleep(waitMs)
    }
}
