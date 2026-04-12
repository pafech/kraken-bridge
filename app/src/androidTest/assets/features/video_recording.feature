@video-recording
Feature: Video recording mode via Kraken housing
  As a diver using a Kraken underwater housing
  I want to switch to video mode and control recording
  So that I can capture video footage during dives

  Background:
    Given the Kraken Bridge accessibility service is running
    And the BLE service is connected and in photo mode

  @smoke
  Scenario: Fn press switches from photo mode to video mode
    Given the BLE service is in photo mode
    When the Fn button is pressed
    Then the BLE service is in video mode
    And a mode-swipe gesture is dispatched towards the video tab

  @smoke
  Scenario: Shutter press starts video recording in video mode
    Given the BLE service is in video mode
    And no recording is in progress
    When the shutter button is pressed
    Then the recording flag is set to true
    And a tap gesture is dispatched to the shutter button area

  @smoke
  Scenario: Shutter press stops an ongoing video recording
    Given the BLE service is in video mode
    And a recording is in progress
    When the shutter button is pressed
    Then the recording flag is set to false
    And a tap gesture is dispatched to the shutter button area

  Scenario: Switching back to photo mode stops any active recording
    Given the BLE service is in video mode
    And a recording is in progress
    When the Fn button is pressed
    Then the recording flag is set to false
    And the BLE service is in photo mode

  Scenario: Fn press in video mode switches back to photo mode
    Given the BLE service is in video mode
    And no recording is in progress
    When the Fn button is pressed
    Then the BLE service is in photo mode
    And a mode-swipe gesture is dispatched towards the photo tab

  @smoke
  Scenario Outline: Mode toggle content description is tried before resource ID
    Given the BLE service is in <start_mode> mode
    When the Fn button is pressed
    Then the mode-swipe gesture targets the <target_desc> content description first

    Examples:
      | start_mode | target_desc |
      | photo      | Video       |
      | video      | Photo       |
