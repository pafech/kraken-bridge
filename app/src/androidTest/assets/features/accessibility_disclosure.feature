Feature: Accessibility prominent-disclosure consent gate
  Google Play's prominent-disclosure policy requires a clear in-app disclosure
  with two equally-prominent options — one to grant consent, one to decline —
  before the AccessibilityService may be enabled. Declining must never be read
  as consent, and it must not force the app to close.

  These scenarios drive the full-screen first-launch gate
  (AccessibilityConsentScreen), which renders the shared ConsentActionButtons
  also used by the in-flow disclosure dialog.

  Background:
    Given the prominent-disclosure consent has not yet been given

  Scenario: The gate offers two clearly visible consent buttons
    When the app is launched
    Then the accessibility disclosure gate is shown
    And the "I agree" button is visible
    And the "Decline" button is visible

  Scenario: Declining keeps the app open and records no consent
    When the app is launched
    And the user taps "Decline" on the disclosure gate
    Then the app is still in the foreground
    And the disclosure gate is no longer shown
    And no accessibility consent has been recorded

  Scenario: Agreeing records consent and dismisses the gate
    When the app is launched
    And the user taps "I agree" on the disclosure gate
    Then the disclosure gate is no longer shown
    And accessibility consent has been recorded
