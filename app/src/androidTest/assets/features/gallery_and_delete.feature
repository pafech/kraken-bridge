@gallery @delete
Feature: Gallery browsing and photo deletion via Kraken housing
  As a diver using a Kraken underwater housing
  I want to browse photos in Google Photos and delete unwanted shots
  So that I can manage my dive footage without touching the phone

  Background:
    Given the Kraken Bridge accessibility service is running
    And the BLE service is connected and in camera mode

  # ── Mode switching ──────────────────────────────────────────────────────────

  @smoke
  Scenario: Back button switches from camera mode to gallery mode
    Given the BLE service is in camera mode
    When the Back button is pressed
    Then the BLE service is in gallery mode
    And Google Photos is launched

  Scenario: Shutter or Back button in gallery mode returns to camera
    Given the BLE service is in gallery mode
    When the Back button is pressed
    Then the BLE service is in camera mode
    And Google Camera is launched

  Scenario: Switching to gallery stops any active video recording
    Given the BLE service is in video mode
    And a recording is in progress
    When the Back button is pressed
    Then the recording flag is set to false
    And the BLE service is in gallery mode

  # ── Navigation ──────────────────────────────────────────────────────────────

  @smoke
  Scenario: Plus button swipes left to show the next photo
    Given the BLE service is in gallery mode
    When the Plus button is pressed
    Then a swipe-left gesture is dispatched to navigate to the next photo

  @smoke
  Scenario: Minus button swipes right to show the previous photo
    Given the BLE service is in gallery mode
    When the Minus button is pressed
    Then a swipe-right gesture is dispatched to navigate to the previous photo

  # ── Deletion – version-agnostic scenarios ───────────────────────────────────

  @smoke @photos-delete
  Scenario: OK button triggers the quick-delete sequence
    Given the BLE service is in gallery mode
    When the OK button is pressed
    Then the quick-delete sequence is dispatched to the accessibility service

  @photos-delete
  Scenario: Trash button is located by content description before resource ID
    Given the BLE service is in gallery mode
    And Google Photos is the foreground app
    When the OK button is pressed
    Then the trash button detection tries content description strategies first
    And resource ID strategies are used only as a fallback

  @photos-delete
  Scenario: Delete confirmation dialog is confirmed after trash is tapped
    Given the BLE service is in gallery mode
    And Google Photos is the foreground app
    When the OK button is pressed
    Then the trash button tap is followed by a confirmation click
    And the confirmation button is identified by text before coordinates

  # ── Deletion – version-specific scenarios ───────────────────────────────────
  # These scenarios are tagged @device-only because they require a real device
  # with a specific version of Google Photos installed. They are excluded from CI.

  @photos-delete @photos-legacy @device-only
  Scenario: Trash button directly visible in action bar (Google Photos < 6.90)
    Given the BLE service is in gallery mode
    And the installed Google Photos version code is less than 690000000
    And Google Photos is the foreground app with a photo open
    When the OK button is pressed
    Then the trash button is found in the bottom action bar without opening any menu
    And the delete confirmation "Move to bin" is tapped

  @photos-delete @photos-modern @device-only
  Scenario: Trash button behind overflow menu (Google Photos >= 6.90)
    Given the BLE service is in gallery mode
    And the installed Google Photos version code is at least 690000000
    And Google Photos is the foreground app with a photo open
    When the OK button is pressed
    Then the overflow "More options" menu is tapped first
    And the "Move to bin" option is found inside the overflow menu
    And the delete confirmation is tapped

  @photos-delete @device-only
  Scenario: Delete flow completes end-to-end on a real device
    Given the BLE service is in gallery mode
    And Google Photos is the foreground app with a photo open
    When the OK button is pressed
    Then the photo is moved to the trash within 5 seconds
