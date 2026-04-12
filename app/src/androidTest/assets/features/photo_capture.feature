@photo-capture
Feature: Photo capture via Kraken housing shutter button
  As a diver using a Kraken underwater housing
  I want to press the shutter button on the housing to take photos
  So that I can capture underwater moments hands-free

  Background:
    Given the Kraken Bridge accessibility service is running
    And the BLE service is connected and in photo mode

  @smoke
  Scenario: Shutter press dispatches a capture gesture to Google Camera
    Given Google Camera is the foreground app
    When the shutter button is pressed
    Then a tap gesture is dispatched to the shutter button area

  @smoke
  Scenario: First shutter press opens Google Camera when not yet open
    Given the camera has not been opened yet this session
    When the shutter button is pressed
    Then Google Camera is launched
    And the camera-open flag is set

  Scenario: Second shutter press in photo mode takes a photo
    Given the camera is already open
    And the camera is in photo mode
    When the shutter button is pressed
    Then KEYCODE_CAMERA is injected via the accessibility service

  Scenario: OK button triggers auto-focus tap at centre of viewfinder
    Given Google Camera is the foreground app
    When the OK button is pressed
    Then a focus tap gesture is dispatched to the centre of the viewfinder

  Scenario: Plus button focuses closer (bottom of viewfinder)
    Given Google Camera is the foreground app
    When the Plus button is pressed
    Then a focus tap gesture is dispatched to the bottom of the viewfinder

  Scenario: Minus button focuses farther (top of viewfinder)
    Given Google Camera is the foreground app
    When the Minus button is pressed
    Then a focus tap gesture is dispatched to the top of the viewfinder
