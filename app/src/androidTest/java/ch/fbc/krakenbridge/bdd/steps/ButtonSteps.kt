package ch.fbc.krakenbridge.bdd.steps

import ch.fbc.krakenbridge.KrakenBleService
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
    // "the BLE service is in {photo,video,gallery,camera} mode" is owned by
    // CommonSteps. Cucumber matches step text regardless of Given/When/Then
    // keyword, so the @Given there also serves @Then usage in feature files.
    // Cucumber 7.18+ rejects duplicate step definitions strictly (tolerated in 7.14).

    @Then("the camera-open flag is set")
    fun assertCameraOpen() {
        service ?: return
        check(KrakenBleService.state.value.isCameraOpen) { "Expected isCameraOpen flag to be set" }
    }

    @Then("the recording flag is set to true")
    fun assertRecordingTrue() {
        service ?: return
        check(KrakenBleService.state.value.isRecording) { "Expected recording to be in progress" }
    }

    @Then("the recording flag is set to false")
    fun assertRecordingFalse() {
        service ?: return
        check(!KrakenBleService.state.value.isRecording) { "Expected recording to have stopped" }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun simulateAndWait(buttonCode: Int, waitMs: Long) {
        service?.simulateButtonPress(buttonCode)
        Thread.sleep(waitMs)
    }
}
