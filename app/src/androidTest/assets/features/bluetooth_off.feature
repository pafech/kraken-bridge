@bluetooth-off @device-only
Feature: Connect when the Bluetooth adapter is off
  As a diver who just installed the app
  I want a clear path to connect even if Bluetooth is currently off
  So that I never see an unexpected crash or a dead "Connect" button

  Tagged @device-only because BluetoothAdapter state cannot be reliably
  toggled on the API 34 emulator used in CI — verified manually on a real
  device (Pixel 9 Pro / Android 16).

  Background:
    Given all permissions are granted
    And the Bluetooth adapter is off

  Scenario: Tapping Connect prompts the user to enable Bluetooth
    When the user taps the Connect circle
    Then the system "Turn on Bluetooth?" dialog appears
    And the BLE service is not started yet

  Scenario: Accepting the prompt starts a connection attempt
    Given the user tapped Connect and the system prompt is shown
    When the user accepts the Bluetooth enable prompt
    Then the Bluetooth adapter becomes enabled
    And the BLE service starts and broadcasts "scanning"

  Scenario: Declining the prompt leaves the app idle without crashing
    Given the user tapped Connect and the system prompt is shown
    When the user declines the Bluetooth enable prompt
    Then the app shows a hint that Bluetooth is required
    And the BLE service is not started
    And the app does not crash

  Scenario: Bluetooth being switched off mid-session is handled gracefully
    Given the BLE service is connected to a Kraken
    When the user disables Bluetooth from the system settings
    Then the BLE service broadcasts an error and stops itself
    And the app does not crash
