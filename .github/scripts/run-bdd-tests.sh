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

# Run Cucumber scenarios – exclude device-only and manual tags.
# Important: pass the entire `am instrument` invocation as ONE single-quoted
# string. `adb shell` does not preserve client-side quoting — args are
# concatenated and re-tokenised by the device shell, which would split
# `not @device-only and not @manual` into bare tokens and trip am's parser
# (manifests as `Error: Invalid userId -2`). The single-quoted form keeps
# the quoting intact for the device-side shell.
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
  | tee /tmp/bdd-output.txt
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
if ! grep -qE 'OK \([0-9]+ test|Tests run: [0-9]+' /tmp/bdd-output.txt; then
  echo "::error::No JUnit summary in BDD output — instrumentation did not run."
  echo "----- last 30 lines of /tmp/bdd-output.txt -----"
  tail -30 /tmp/bdd-output.txt
  echo "------------------------------------------------"
  exit 1
fi

# 3. JUnit summary exists — fail on any reported failure.
if grep -qE 'FAILURES|Error in' /tmp/bdd-output.txt; then
  echo "::error::BDD test failures detected in output"
  exit 1
fi
