# Google Play — AccessibilityService Prominent Disclosure Compliance

This document maps every Google Play prominent-disclosure requirement to its
concrete implementation in this app, and gives the script for the Play Console
declaration video. It exists because v141 was rejected for an
"incompliant prominent disclosure design … missing a negative consent option",
and we want the next submission to pass with certainty.

## Why the rejection happened (root cause)

The decline option *existed* in v141 but was easy to miss, for two reasons:

1. It was visually too faint to read as a real button — a low-contrast
   `surface`-coloured button next to a bright `primary` one.
2. The disclosure text was long enough that on a typical phone **both buttons
   sat below the fold**, so a reviewer who didn't scroll saw only the
   affirmative area.

Either way Google's review — likely an automated visual check — read it as
"only affirmative options offered". The dismissal behaviour
(back / tap-away ≠ consent) was already compliant; the gaps were the
**visual prominence of the negative option**, the disclosure **fitting on one
screen** so both options are visible without scrolling, and the
**declaration video**.

## Policy → implementation

| Requirement (Play policy) | How we satisfy it | Where |
|---|---|---|
| Disclosure is **in-app**, shown during normal use without menu navigation | Full-screen gate at first launch | `MainActivity` gate block; `ui/AccessibilityConsentScreen.kt` |
| Disclosure appears **right before requesting** the capability | In-flow dialog before opening system Accessibility settings | `MainActivity.AccessibilityDisclosureDialog` |
| States **why** the capability is needed | "What Kraken Dive Photo does" section | `AccessibilityConsentScreen.kt` |
| States **what** data is accessed and **how** it is used / shared | Bullet list of what the service reads/does + "does NOT do" section (no collection, no transmission) | `AccessibilityConsentScreen.kt` |
| **Two clear options**, one affirmative, one to decline | `ConsentActionButtons` — two equally-sized **filled** buttons, "I agree" / "Decline" | `ui/ConsentActionButtons.kt` |
| Negative option is **clearly visible** (not a ghost/text-only control) | Both buttons filled, equal weight; decline is a solid light-fill button, accept a solid aqua button | `ui/ConsentActionButtons.kt` |
| Both options visible **without scrolling** (above the fold) | Disclosure text condensed so the full gate — text and both buttons — fits one screen on a typical phone | `ui/AccessibilityConsentScreen.kt` |
| **Affirmative action** required; navigating away ≠ consent | Gate: back press leaves the app and is not treated as consent. Dialog: `dismissOnBackPress = false`, `dismissOnClickOutside = false`, and `onDismissRequest` routes to *declined* | `AccessibilityConsentScreen.kt`, `MainActivity.AccessibilityDisclosureDialog` |
| **No auto-dismiss / expiry** | Neither surface uses a timer | both |
| **Graceful degradation** on decline (no coercive consent wall) | Declining the gate does **not** close the app; it stays usable with the service off, and the disclosure re-appears next launch / when the user tries to enable the service | `MainActivity` (`a11yDisclosureDismissedThisSession`) |
| Consent recorded only on affirmative action | `a11yDisclosureAccepted` persisted on "I agree" in **both** surfaces | `MainActivity`, `Features.kt` (`UiHints`) |

Behaviour is locked by BDD: `app/src/androidTest/assets/features/accessibility_disclosure.feature`.

## Play Console declaration video — required shot list

Google requires the declaration video to demonstrate the **non-consent flow and
re-triggering**, not just the happy path. Record one continuous take showing,
in order:

1. **App opens** on the device (cold start).
2. **Full flow to the disclosure**, scrolling slowly through the disclosure text
   so all of it is legible on screen.
3. **Decline path:** tap **Decline**. Show that the app stays open and usable
   (the service is simply off) — i.e. declining is a real, non-coercive choice.
4. **Re-trigger:** reach the disclosure again (relaunch the app, or tap the
   in-app Accessibility row) to show it can be brought back after declining.
5. **Consent path:** tap **I agree**, then enable the Kraken Bridge service in
   the system Accessibility settings.
6. **Core feature in action:** with the service enabled, a Kraken housing button
   press drives the camera/gallery (add a caption/voice-over, since the BLE
   button press isn't visible on screen).

Upload the video link in Play Console → App content → the AccessibilityService
declaration, and submit with an incremented version code (CI sets the version
code from the run number automatically).

## Known follow-up (not required for approval)

`KrakenAccessibilityService` does not currently check `a11yDisclosureAccepted`
before acting. A user could enable the service directly from Android Settings
after declining in-app, and it would still act. This is defense-in-depth, not a
review blocker, and is tracked for a separate change.
