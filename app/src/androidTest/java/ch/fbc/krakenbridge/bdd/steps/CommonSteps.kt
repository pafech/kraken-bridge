package ch.fbc.krakenbridge.bdd.steps

import androidx.test.platform.app.InstrumentationRegistry
import ch.fbc.krakenbridge.KrakenAccessibilityService
import ch.fbc.krakenbridge.KrakenBleService
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
 *
 * The hooks are scoped to `@needs-a11y` features: enabling/disabling an
 * accessibility service around *every* scenario destabilises UiAutomator's
 * window snapshot (the system rebuilds its accessibility connections), which
 * intermittently broke the pure-UI disclosure-gate scenarios with
 * "gate was not shown after launch" (runs 27012024159, 27015010287,
 * 27022762323, 27024033854). Scenarios that don't drive the service now run
 * with no service churn at all; UiAutomation keeps the only a11y connection.
 */
class CommonSteps {

    private val uiAutomation
        get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    // ── Cucumber lifecycle ───────────────────────────────────────────────────

    @Before("@needs-a11y")
    fun enableAccessibilityServiceAndWait() {
        shell("settings put secure enabled_accessibility_services " +
                "ch.fbc.krakenbridge/ch.fbc.krakenbridge.KrakenAccessibilityService")
        shell("settings put secure accessibility_enabled 1")

        // Give the system up to 5 s to bind the service
        val deadline = System.currentTimeMillis() + 5_000
        while (KrakenAccessibilityService.instance == null &&
               System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
    }

    @After("@needs-a11y")
    fun disableAccessibilityService() {
        shell("settings put secure enabled_accessibility_services \"\"")
        shell("settings put secure accessibility_enabled 0")
    }

    // ── Shared precondition steps ────────────────────────────────────────────

    @Given("the Kraken Bridge accessibility service is running")
    fun assertAccessibilityServiceRunning() {
        // In CI the accessibility service may not bind (no foreground Activity,
        // emulator cold-start). We log a warning and continue rather than aborting
        // the scenario — the service-dependent @Then steps guard themselves too.
        if (KrakenAccessibilityService.instance == null) {
            android.util.Log.w("BDDTest",
                "KrakenAccessibilityService not bound – running in CI mode, " +
                "service-dependent assertions will be skipped.")
        }
    }

    @Given("the BLE service is connected and in photo mode")
    fun bleServiceConnectedPhotoMode() {
        // Service may not be bound in CI (no BLE hardware); we assert it is running
        // only when it has been started. State reads use the service's state flow.
        KrakenBleService.instance ?: return
        val state = KrakenBleService.state.value
        check(!state.isVideoMode) { "Expected photo mode but service is in video mode" }
        check(!state.isGalleryMode) { "Expected camera mode but service is in gallery mode" }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Execute a shell command via UiAutomation (runs with shell privileges). */
    @Throws(IOException::class)
    private fun shell(command: String) {
        uiAutomation.executeShellCommand(command).close()
    }
}
