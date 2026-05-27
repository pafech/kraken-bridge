package ch.fbc.krakenbridge.bdd.steps

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/**
 * Drives the AccessibilityService prominent-disclosure gate
 * (AccessibilityConsentScreen) through real UI interaction with UiAutomator.
 *
 * These scenarios are the BDD definition of "done" for the Google Play
 * prominent-disclosure requirement: two clearly visible consent buttons, a
 * decline path that keeps the app open and records no consent, and an accept
 * path that persists consent. The in-flow dialog reuses the same
 * ConsentActionButtons, so this also covers that surface's button contract.
 */
class AccessibilityDisclosureSteps {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun consentRecorded(): Boolean =
        targetContext
            .getSharedPreferences(PREFS_UI_HINTS, Context.MODE_PRIVATE)
            .getBoolean(KEY_A11Y_DISCLOSURE_ACCEPTED, false)

    // ── Given ────────────────────────────────────────────────────────────────

    @Given("the prominent-disclosure consent has not yet been given")
    fun clearConsent() {
        targetContext
            .getSharedPreferences(PREFS_UI_HINTS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    // ── When ─────────────────────────────────────────────────────────────────

    @When("the app is launched")
    fun launchApp() {
        val intent = targetContext.packageManager
            .getLaunchIntentForPackage(targetContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: error("No launch intent for ${targetContext.packageName}")
        targetContext.startActivity(intent)
        device.wait(Until.hasObject(By.text(GATE_TITLE)), LAUNCH_TIMEOUT_MS)
    }

    @When("the user taps {string} on the disclosure gate")
    fun tapButton(label: String) {
        check(device.wait(Until.hasObject(By.text(label)), UI_TIMEOUT_MS)) {
            "Disclosure button \"$label\" never appeared"
        }
        device.findObject(By.text(label)).click()
    }

    // ── Then ─────────────────────────────────────────────────────────────────

    @Then("the accessibility disclosure gate is shown")
    fun gateShown() {
        check(device.wait(Until.hasObject(By.text(GATE_TITLE)), UI_TIMEOUT_MS)) {
            "Disclosure gate (\"$GATE_TITLE\") was not shown after launch"
        }
    }

    @Then("the {string} button is visible")
    fun buttonVisible(label: String) {
        check(device.wait(Until.hasObject(By.text(label)), UI_TIMEOUT_MS)) {
            "Consent button \"$label\" is not visible on the gate"
        }
    }

    @Then("the app is still in the foreground")
    fun appStillForeground() {
        // If Decline had finished the Activity, the foreground package would
        // fall back to the launcher. Staying on our package proves the decline
        // path no longer closes the app.
        check(device.wait(Until.hasObject(By.pkg(targetContext.packageName).depth(0)), UI_TIMEOUT_MS)) {
            "App left the foreground after declining (current: ${device.currentPackageName})"
        }
    }

    @Then("the disclosure gate is no longer shown")
    fun gateDismissed() {
        check(device.wait(Until.gone(By.text(GATE_TITLE)), UI_TIMEOUT_MS)) {
            "Disclosure gate is still shown; it should have been dismissed"
        }
    }

    @Then("no accessibility consent has been recorded")
    fun noConsentRecorded() {
        check(!consentRecorded()) { "Consent was recorded even though the user declined" }
    }

    @Then("accessibility consent has been recorded")
    fun consentHasBeenRecorded() {
        // The consent flag is written via SharedPreferences.apply() (async), so
        // poll briefly rather than reading once.
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && !consentRecorded()) {
            Thread.sleep(100)
        }
        check(consentRecorded()) { "Consent was not recorded after the user agreed" }
    }

    private companion object {
        const val PREFS_UI_HINTS = "kraken_ui_hints"
        const val KEY_A11Y_DISCLOSURE_ACCEPTED = "a11y_disclosure_accepted"
        const val GATE_TITLE = "Accessibility access"
        const val LAUNCH_TIMEOUT_MS = 10_000L
        const val UI_TIMEOUT_MS = 5_000L
    }
}
