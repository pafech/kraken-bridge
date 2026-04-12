package com.krakenbridge.bdd

import io.cucumber.android.runner.CucumberAndroidJUnitRunner

/**
 * Entry-point for Cucumber-Android.
 *
 * The Gradle test runner is already set to [CucumberAndroidJUnitRunner] in build.gradle.kts,
 * so this class primarily documents where features and glue live and provides an IDE hook for
 * running individual scenarios from Android Studio.
 *
 * Features  : src/androidTest/assets/features/
 * Glue code : com.krakenbridge.bdd.steps  (auto-discovered by annotation scanning)
 *
 * Tag filtering is configured in build.gradle.kts via
 *   testInstrumentationRunnerArguments["tags"] = "not @device-only and not @manual"
 * Override on the command line with:
 *   ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.tags="@smoke"
 */
@Suppress("unused") // referenced in build.gradle.kts testInstrumentationRunner
class CucumberRunner : CucumberAndroidJUnitRunner()
