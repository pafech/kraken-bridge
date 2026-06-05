@feature-selection @device-only
Feature: Feature toggles drive permissions and button mapping
  As a diver opening the app for the first time
  I want one place to toggle features and grant the permissions they need
  So that I am not asked for permissions I do not require

  Tagged @device-only because the flow exercises real system permission
  dialogs and SharedPreferences persistence — driven manually on a
  device. CI emulator does not run these.

  Background:
    Given the app is freshly installed

  Scenario: Fresh install lands on Settings until permissions are granted
    When the user opens the app
    Then the Settings page is shown as the initial pager page
    And the Camera card is marked Required and locked on
    And Gallery and Dive Mode toggles are off by default
    And every Camera permission row appears under the Camera card

  Scenario: Tapping a Camera permission row fires that single dialog
    Given the user is on the Settings page
    When the user taps the Bluetooth permission row
    Then the system Bluetooth permission dialog appears
    And no other permission dialog is queued behind it

  # The two scenarios below are the permission walkthrough — the sequential
  # Camera setup chain started by the Camera toggle (MainActivity:
  # startCameraSetup → advanceCameraSetup). Hand-tested on a device:
  # system permission dialogs are one-shot per install (a second denial
  # locks the permission), so an emulator run is not idempotent.

  Scenario: Camera toggle walks every missing permission in sequence
    Given a fresh install with no permissions granted
    When the user toggles Camera on
    Then the system Bluetooth permission dialog appears first
    And on Allow the system Notifications dialog follows
    And on Allow the Battery exemption dialog follows
    And on Allow the Accessibility consent dialog follows
    And on I agree the system Accessibility settings open
    And after enabling the service the Camera toggle locks on

  Scenario: Denying mid-chain stops the walkthrough at that permission
    Given a fresh install with no permissions granted
    When the user toggles Camera on
    And the user denies the system Bluetooth permission dialog
    Then no further permission dialog appears
    And the Camera toggle stays off
    And the Bluetooth permission row remains tappable for repair

  Scenario: Enabling Gallery fires the Photos & Videos dialog and stays on if granted
    Given the user is on the Settings page
    When the user toggles Gallery on
    Then the system Photos & Videos permission dialog appears
    And on Allow the Gallery toggle stays on
    And the Photos & Videos row turns green under the Gallery card

  Scenario: Denying the Gallery permission reverts the toggle
    Given the user is on the Settings page
    When the user toggles Gallery on
    And the user denies the Photos & Videos dialog
    Then the Gallery toggle reverts to off

  Scenario: Enabling Dive Mode deep-links to the overlay setting
    Given the user is on the Settings page
    When the user toggles Dive Mode on
    Then the system Display Overlay setting opens
    And on grant the Dive Mode toggle stays on
    And on back without granting the Dive Mode toggle reverts to off

  Scenario: All permissions granted → Main is the initial pager page on relaunch
    Given every Camera permission has been granted
    When the user kills and relaunches the app
    Then the Main page is shown as the initial pager page
    And swiping left still reveals the Settings page

  Scenario: Disabled Gallery feature ignores the Back button
    Given Gallery is disabled
    And the BLE service is connected
    When the housing Back button is pressed
    Then the service does not switch to gallery mode
    And a toast hint is shown that Gallery is disabled

  Scenario: HelpScreen hides Gallery section when Gallery is off
    Given Gallery is disabled
    When the user swipes right from Main to the Help page
    Then the Gallery Mode section is not shown
    And the Back button row reads "Unmapped — enable Gallery in Settings"

  Scenario: Disabled Dive Mode skips overlay startup on connect
    Given Dive Mode is disabled
    When the BLE service starts a connection
    Then the screen overlay is not attached
    And the screen behaves as the system default
