@feature-selection @device-only
Feature: Feature selection drives permissions and button mapping
  As a diver opening the app for the first time
  I want to choose which features I need
  So that I am not asked for permissions I do not require

  Tagged @device-only because the flow exercises real system permission
  dialogs and SharedPreferences persistence — driven manually on a
  device. CI emulator does not run these.

  Background:
    Given the app is freshly installed

  Scenario: Fresh install lands on the FeatureSelectionScreen
    When the user opens the app
    Then the FeatureSelectionScreen is shown
    And only the Camera card is marked Required
    And Gallery and Dive Mode toggles are off by default

  Scenario: Camera-only selection asks for the minimal permission set
    Given the user is on the FeatureSelectionScreen
    And Gallery is off
    And Dive Mode is off
    When the user taps Continue
    Then the permission walkthrough does not request Photos & Videos
    And the permission walkthrough does not request Display Overlay
    And the permission walkthrough requests Bluetooth, Location, Notifications, Battery, Accessibility

  Scenario: Enabling Gallery adds the Photos & Videos permission step
    Given the user is on the FeatureSelectionScreen
    When the user enables Gallery
    And the user taps Continue
    Then the permission walkthrough requests Photos & Videos

  Scenario: Enabling Dive Mode adds the Display Overlay step
    Given the user is on the FeatureSelectionScreen
    When the user enables Dive Mode
    And the user taps Continue
    Then the permission walkthrough requests Display Overlay

  Scenario: Disabled Gallery feature ignores the Back button
    Given Gallery is disabled
    And the BLE service is connected
    When the housing Back button is pressed
    Then the service does not switch to gallery mode
    And a toast hint is shown that Gallery is disabled

  Scenario: HelpDialog hides Gallery section when Gallery is off
    Given Gallery is disabled
    When the user opens the Button Mapping help
    Then the Gallery Mode section is not shown
    And the Back button row reads "Unmapped — enable Gallery in Settings"

  Scenario: Disabled Dive Mode skips overlay startup on connect
    Given Dive Mode is disabled
    When the BLE service starts a connection
    Then the screen overlay is not attached
    And the screen behaves as the system default
