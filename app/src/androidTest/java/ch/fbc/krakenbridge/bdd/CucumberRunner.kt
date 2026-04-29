package ch.fbc.krakenbridge.bdd

import io.cucumber.android.runner.CucumberAndroidJUnitRunner
import io.cucumber.junit.CucumberOptions

/**
 * Entry-point for Cucumber-Android.
 *
 * Wired in build.gradle.kts as the testInstrumentationRunner.
 *
 * Features  : src/androidTest/assets/features/
 * Glue code : ch.fbc.krakenbridge.bdd.steps  (annotation scanning)
 *
 * The @CucumberOptions annotation is mandatory from cucumber-android 7.15+ —
 * the runner walks the test APK looking for exactly one annotated class and
 * throws "No CucumberOptions annotated class present" otherwise. This class
 * lives in `ch.fbc.krakenbridge.bdd` rather than the testApplicationId
 * package (`ch.fbc.krakenbridge.test`), so the Gradle config also passes
 * `optionsAnnotationPackage = ch.fbc.krakenbridge.bdd` as an instrumentation
 * argument so the runner knows where to look.
 *
 * Tag filtering is configured in build.gradle.kts via
 *   testInstrumentationRunnerArguments["tags"] = "not @device-only and not @manual"
 * Override on the command line with:
 *   ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.tags="@smoke"
 */
@CucumberOptions(
    features = ["features"],
    glue = ["ch.fbc.krakenbridge.bdd.steps"]
)
@Suppress("unused") // referenced in build.gradle.kts testInstrumentationRunner
class CucumberRunner : CucumberAndroidJUnitRunner()
