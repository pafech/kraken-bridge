@screen-overlay
Feature: Screen overlay keeps the dive accessible without a lockscreen
  As a diver with the phone sealed inside a Kraken housing
  I want the screen never to truly turn off during a dive session
  So that I never get blocked by a secure keyguard I cannot enter underwater

  Background:
    Given the Kraken Bridge accessibility service is running
    And the BLE service is connected and in photo mode

  @manual
  Scenario: Overlay attaches when the service connects
    Given the SYSTEM_ALERT_WINDOW permission is granted
    When the BLE service is started with ACTION_CONNECT
    Then the overlay window is attached

  @manual
  Scenario: Overlay dims after the idle timeout expires
    Given the overlay is attached
    And the idle timeout is set to 500 milliseconds
    When 1 second passes without a button event
    Then the overlay brightness is at the dim level

  @manual
  Scenario: A button event restores full brightness
    Given the overlay is attached and dimmed
    When the shutter button is pressed
    Then the overlay brightness is back at the bright level
    And the idle timer has restarted

  @manual
  Scenario: Overlay detaches when the user disconnects
    Given the overlay is attached
    When the user disconnects via the Disconnect action
    Then the overlay window is detached

  @manual
  Scenario: Overlay detaches when the app is swiped from Recents
    Given the overlay is attached
    When the user swipes the app from Recents
    Then the overlay window is detached

  @manual
  Scenario: Overlay stays bright during video recording
    Given the overlay is attached
    And the idle timeout is set to 500 milliseconds
    When a video recording starts
    And 1 second passes without a button event
    Then the overlay brightness is at the bright level
    And the keep-bright flag is set

  @manual
  Scenario: Idle dimmer resumes when video recording stops
    Given the overlay is attached
    And a video recording is in progress
    When the video recording stops
    Then the keep-bright flag is cleared
    And the idle timer is running

  @manual
  Scenario: First button press on a dimmed overlay only wakes
    Given the overlay is attached and dimmed
    And the camera has not been opened yet this session
    When the shutter button is pressed
    Then the overlay brightness is back at the bright level
    And the camera-open flag is still cleared
