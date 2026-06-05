#!/usr/bin/env bash
# Run BDD instrumented tests on an Android emulator.
# Called from the CI workflow after the emulator has booted.
set -euo pipefail

./gradlew assembleDebug assembleDebugAndroidTest --no-daemon

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Grant the accessibility service permission
adb shell settings put secure enabled_accessibility_services \
  ch.fbc.krakenbridge/ch.fbc.krakenbridge.KrakenAccessibilityService
adb shell settings put secure accessibility_enabled 1
sleep 2

# Pin the screen on for the whole suite. The screen-off timer starts at
# emulator boot and nothing else resets it; once the panel sleeps, the
# foreground activity stops and its accessibility tree empties, so every
# UiAutomator By.text lookup fails — the UI-driven scenarios go red while
# service-reflection scenarios still pass (seen as the intermittent
# disclosure-gate failures in runs 27012024159 / 27015010287).
# stayon=true keeps the screen awake while powered (an emulator always is);
# wakeup + dismiss-keyguard recover if it already slept during the build.
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
# Diagnostic breadcrumb for any future flake hunt: expect Awake here.
adb shell dumpsys power | grep -E "mWakefulness=" || true

# Run Cucumber scenarios – exclude device-only and manual tags.
# Important: pass the entire `am instrument` invocation as ONE single-quoted
# string. `adb shell` does not preserve client-side quoting — args are
# concatenated and re-tokenised by the device shell, which would split
# `not @device-only and not @manual` into bare tokens and trip am's parser
# (manifests as `Error: Invalid userId -2`). The single-quoted form keeps
# the quoting intact for the device-side shell.
# Capture the full instrumentation output host-side. This is the BDD
# report artifact: cucumber-android prints every scenario with its result
# here, and unlike an on-device XML file it survives the emulator teardown
# that android-emulator-runner performs as soon as this script exits
# (which is why a post-step `adb pull` always found nothing).
mkdir -p bdd-reports

set +e
# `optionsAnnotationPackage` tells cucumber-android where to find the
# @CucumberOptions class (CucumberRunner lives in `ch.fbc.krakenbridge.bdd`,
# not the testApplicationId package `ch.fbc.krakenbridge.test`). The Gradle
# `testInstrumentationRunnerArguments` only flow through the
# `connectedDebugAndroidTest` task, not direct `am instrument`, so we
# duplicate the argument here.
adb shell "am instrument -w \
  -e tags 'not @device-only and not @manual' \
  -e optionsAnnotationPackage ch.fbc.krakenbridge.bdd \
  ch.fbc.krakenbridge.test/ch.fbc.krakenbridge.bdd.CucumberRunner" \
  | tee bdd-reports/instrument-output.txt
# `$?` after a pipe is the exit code of the last pipe member (tee), which
# always succeeds. PIPESTATUS[0] is what we actually care about.
INSTRUMENT_EXIT=${PIPESTATUS[0]}
set -e

# 1. `am instrument` itself must exit 0.
if [ "$INSTRUMENT_EXIT" -ne 0 ]; then
  echo "::error::Instrumentation exited with code $INSTRUMENT_EXIT"
  exit 1
fi

# 2. The output must contain a JUnit-style summary. Without it, the test
#    runner never actually executed — e.g. `Error: Invalid userId -2` on
#    certain emulator images causes `am` to print its help and bail with
#    a non-zero status that `tee` masks. Belt-and-braces.
if ! grep -qE 'OK \([0-9]+ test|Tests run: [0-9]+' bdd-reports/instrument-output.txt; then
  echo "::error::No JUnit summary in BDD output — instrumentation did not run."
  echo "----- last 30 lines of bdd-reports/instrument-output.txt -----"
  tail -30 bdd-reports/instrument-output.txt
  echo "------------------------------------------------"
  exit 1
fi

# 3. JUnit summary exists — fail on any reported failure.
if grep -qE 'FAILURES|Error in' bdd-reports/instrument-output.txt; then
  echo "::error::BDD test failures detected in output"
  exit 1
fi
