#!/usr/bin/env bash
# Run BDD instrumented tests on an Android emulator.
# Called from the CI workflow after the emulator has booted.
set -euo pipefail

./gradlew assembleDebug assembleDebugAndroidTest --no-daemon

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Grant the accessibility service permission
adb shell settings put secure enabled_accessibility_services \
  com.krakenbridge/com.krakenbridge.KrakenAccessibilityService
adb shell settings put secure accessibility_enabled 1
sleep 2

# Run Cucumber scenarios – exclude device-only and manual tags
set +e
adb shell am instrument -w \
  -e tags 'not @device-only and not @manual' \
  com.krakenbridge.test/io.cucumber.android.runner.CucumberAndroidJUnitRunner \
  | tee /tmp/bdd-output.txt
INSTRUMENT_EXIT=$?
set -e

# Fail on instrument exit code OR on FAILURES in output
if [ "$INSTRUMENT_EXIT" -ne 0 ]; then
  echo "::error::Instrumentation exited with code $INSTRUMENT_EXIT"
  exit 1
fi
if grep -q -E 'FAILURES|Error in' /tmp/bdd-output.txt; then
  echo "::error::BDD test failures detected in output"
  exit 1
fi
