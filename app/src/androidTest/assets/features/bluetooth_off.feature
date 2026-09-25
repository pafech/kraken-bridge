@bluetooth-off @device-only
Feature: Bluetooth off before or during a session
  As a diver who has Bluetooth off, or loses it during a dive
  I want a clear path to connect, and a session that recovers on its own
  So that I never face a dead "Connect" circle or dead housing buttons

  Tagged @device-only because the Bluetooth adapter cannot be switched
  reliably on the emulator used in CI. Hand-tested on a Pixel 9 Pro
  (Android 17); the mid-session scenario on 2026-09-25 with the housing.

  Scenario: Tapping the circle with Bluetooth off points at the Bluetooth chip
    Given all Camera permissions are granted
    And the Bluetooth adapter is off
    When the user taps the circle on the main screen
    Then the Bluetooth chip flashes
    And the BLE service is not started

  Scenario: The Bluetooth chip asks to turn Bluetooth on
    Given the Bluetooth adapter is off
    When the user taps the Bluetooth chip
    Then the system "Turn on Bluetooth?" dialog appears
    And on Allow the Bluetooth chip shows Bluetooth on
    And the BLE service is not started until the user taps the circle

  Scenario: Declining the Bluetooth prompt leaves the app idle
    Given the system "Turn on Bluetooth?" dialog is shown
    When the user declines it
    Then the Bluetooth chip still shows Bluetooth off
    And the app does not crash

  Scenario: Bluetooth switched off and on during a session reconnects by itself
    Given the BLE service is connected to the Kraken housing
    When the user switches Bluetooth off
    Then within 15 seconds the service treats the link as lost
    And it keeps the session running
    When the user switches Bluetooth on again
    Then the service reconnects to the housing within seconds
    And a shutter press takes a photo without touching the phone
