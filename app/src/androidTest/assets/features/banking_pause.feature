@banking-pause
Feature: Pause accessibility service for banking apps
  As a diver who also uses Swiss banking apps (UBS, Twint, PostFinance, …)
  I want to pause the Kraken accessibility service in one tap
  So that I can log in to my bank without uninstalling the Kraken app

  Banking apps enumerate enabled accessibility services and refuse to launch
  while any non-whitelisted one is active. Android offers no per-app exemption,
  so the only sanctioned escape is `AccessibilityService.disableSelf()` —
  surfaced here as a single action row in the Settings page Camera card.

  Tagged @manual throughout: enabling and verifying the system accessibility
  service requires the real Settings UI which cannot be driven on a clean
  emulator.

  Background:
    Given the Kraken accessibility service is enabled by the user

  @manual
  Scenario: Pause action is visible while the service is enabled
    When the user opens the Settings page
    Then the Camera card shows a "Pause for banking apps" action row
    And the row explains that banking apps block the service

  @manual
  Scenario: Tapping the action disables the service
    When the user taps "Pause for banking apps"
    Then the Kraken accessibility service is disabled
    And the Accessibility permission row turns amber
    And a toast confirms the service is paused

  @manual
  Scenario: Pause action hides while the service is already off
    Given the Kraken accessibility service is disabled
    When the user opens the Settings page
    Then the "Pause for banking apps" action row is not shown
    And the Accessibility permission row prompts to grant it

  @manual
  Scenario: Re-enabling uses the existing permission row
    Given the Kraken accessibility service was paused for banking
    When the user taps the Accessibility permission row
    Then the system Accessibility settings open
    And after enabling Kraken the row turns green
    And the "Pause for banking apps" action row reappears
