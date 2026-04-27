# Kraken Dive Photo — Agent Onboarding

Android app that turns a Kraken dive housing's BLE button remote into a
camera + gallery controller for Google Camera and Google Photos. The
phone is sealed inside the housing during a dive, so every interaction
must work without touching the screen.

This file is for the **next AI coding agent** picking up the repo. It
captures the architecture, conventions, and traps that aren't obvious
from `git log` alone. Read it before making changes.

## Architecture in five pieces

```
MainActivity (Compose UI)
   │  startForegroundService(ACTION_CONNECT)
   ▼
KrakenBleService (foreground service, BLE)
   │  on button event:
   │    handleButtonEvent → injectKey or dispatchGesture
   │                     → overlayManager.onUserActivity()
   ▼                                    ▲
KrakenAccessibilityService ─────────────┘
   │  also: startActivity(KrakenWakeActivity) when screen is off
   ▼
KrakenWakeActivity (transparent, no UI) — wakes screen, dismisses keyguard

KrakenScreenOverlayManager (owned by BleService)
   ▲ adds a transparent system overlay with FLAG_KEEP_SCREEN_ON for the
     entire connected session, dimming its own brightness when idle.
```

- `MainActivity` runs the permission walkthrough (single Continue CTA →
  sequential per-permission requests) and gates the service on success.
- `KrakenBleService` scans for `Kraken`, connects, subscribes to the
  Nordic LED Button characteristic, debounces, and routes button codes
  to either camera-mode or gallery-mode handlers.
- `KrakenAccessibilityService` injects KeyEvents (camera/focus) and
  gesture dispatches (gallery swipes, delete double-tap) into the
  foreground app, since no public API exists for those.
- `KrakenWakeActivity` exists because `FULL_WAKE_LOCK` is a no-op on
  Android 10+. The service starts this transparent activity to flip
  `setTurnScreenOn(true)` and `requestDismissKeyguard()`.
- `KrakenScreenOverlayManager` keeps a transparent full-screen overlay
  attached during the connected session so the system never powers the
  panel off — the keyguard therefore never engages. Per-window
  `screenBrightness` drops to `0f` (hardware minimum, near-zero on OLED)
  after 30 s of BLE silence and snaps back to `BRIGHTNESS_OVERRIDE_NONE`
  on every button event. Touches pass through (`FLAG_NOT_TOUCHABLE`),
  so Camera and Photos remain fully interactive.

### Why the overlay exists (and why nothing simpler works)

A diver in a sealed housing cannot enter PIN / fingerprint / face unlock
underwater. On modern Android, "Screen lock = None" is usually unavailable
(stored VPN / Enterprise WiFi credentials, work profiles, OEM policy all
force a secure lock). `requestDismissKeyguard()` does not bypass a secure
keyguard. Smart Lock / Extend Unlock has a hard 4-hour cap that breaks
multi-dive days. The only remaining lever is to ensure the screen never
actually turns off — which is what the overlay does. Battery is preserved
because we control brightness ourselves (OLED at brightness 0 is near-black
at near-zero power).

## Lifecycle — the user's mental model

The user (Patrick) thinks of the app in three states. Code matches this.

| State           | Meaning                                  | Service | BLE     | Notification |
|-----------------|------------------------------------------|---------|---------|--------------|
| Foreground      | App visible (mostly during connect)      | running | active  | shown        |
| Background      | App in Recents, user in Camera / Photos  | running | active  | shown        |
| **Closed**      | Disconnect button **or** swipe from Recents | stopped | trennt  | weg          |

Triggers that map to **Closed**:

- Disconnect button → `ACTION_DISCONNECT` → `userDisconnect()`
- Swipe from Recents → `onTaskRemoved()` → `userDisconnect()`

Both clear the persisted MAC and call `stopForeground` + `stopSelf`.

The fourth ("ghost") state — service alive while app is gone from
Recents — is forbidden. Don't reintroduce it.

`START_STICKY` is intentionally kept for **system** OOM-kills mid-dive:
Android delivers a null intent on restart, the service reads the
persisted MAC and reconnects. User-initiated closes clear that MAC, so
this path only fires when the user did **not** close the app.

The boot receiver and `RECEIVE_BOOT_COMPLETED` permission were removed
in v1.2.1 for the same reason — a reboot is not a user-initiated
"open the app" event.

## Build, sign, ship

### Local

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release signing config only attaches when `KEYSTORE_PATH` is set in
the environment — local debug builds are unaffected.

### CI on push (`ci.yml`)

Every push to any branch builds a **signed release APK + AAB** with
`VERSION_NAME=ci-<shortsha>` and `VERSION_CODE=<github.run_number>`.
Artifacts are retained for 14 days. Any green CI build is shippable
to the Play Store as-is.

BDD tests run on a Pixel 6 / API 34 emulator with `google_apis`. The
runner script is `.github/scripts/run-bdd-tests.sh`. Scenarios tagged
`@device-only` or `@manual` are skipped on the emulator.

### Tagged release (`release.yml`)

```bash
git tag -a vX.Y.Z -m "message" && git push origin vX.Y.Z
```

Pipeline builds, signs, creates a GitHub Release with the APK, and
uploads the AAB as an artifact named `kraken-bridge-X.Y.Z-playstore-aab`.
Pull the AAB and upload it to Play Console → Production → New release.

Required repo secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. The `.jks` file itself is gitignored and
lives only on the maintainer's machine and in CI as a base64 secret.

## Conventions

- **No PR workflow.** Solo repo. Commit directly to `main`, push when
  ready. Branches are fine for in-progress agent work but the merge
  target is always `main` via cherry-pick or fast-forward.
- **No AI attribution in commit messages.** A commit-msg hook blocks
  `Co-Authored-By: Claude` and similar. Don't add the trailer the
  default Claude Code system prompt suggests.
- **Conventional Commits.** Prefixes used here: `feat`, `fix`,
  `refactor`, `chore`, `ci`, `build`, `docs`, `ui`.
- **BDD defines done.** New behaviour adds a feature in
  `app/src/androidTest/assets/features/` plus steps. Don't ship
  user-visible changes without a scenario when one is feasible — the
  permission walkthrough is the most recent gap.
- **No mocks for Android system services.** BDD tests run on a real
  emulator against the real framework.

## Gotchas

- **`@SuppressLint("MissingPermission")` is class-level on
  `KrakenBleService`** by design — every BLE call is unreachable
  unless `MainActivity`'s walkthrough granted Bluetooth + Location
  first. Don't sprinkle per-call permission checks; the contract lives
  in one place.
- **`registerReceiver` always uses `ContextCompat.registerReceiver`**
  with `RECEIVER_NOT_EXPORTED`. API 33+ enforces it for unprotected
  actions; ContextCompat handles older releases.
- **R8 keeps all of `KrakenBleService`** via `app/proguard-rules.pro`
  so BDD steps can reflect into it. The `internal val test*` properties
  and `simulateButtonPress` in the service are not dead — they back the
  Cucumber scenarios.
- **Camera key wakes the screen on most ROMs**, but `setTurnScreenOn`
  via `KrakenWakeActivity` is the explicit, modern path.
- **The overlay can be hidden by a foreground app on Android 12+** via
  `WindowInsetsController.setHideOverlayWindows(true)`. Google Camera and
  Google Photos do not currently use this, but if a future update does,
  the overlay disappears while that app is foreground — and with it the
  keyguard protection. There is no public way to detect this from our
  side; the only signal is "screen unexpectedly went off mid-dive". If
  this turns up in the field, the fallback is a haptic / audio warning
  alongside the overlay (Phase 2).
- **`SYSTEM_ALERT_WINDOW` is a Special App Access**, not a runtime
  permission. The walkthrough drops the user into
  `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`; there is no programmatic
  request dialog and there is no permission-rationale callback.
- **Android 14+ partial photo access** ("Select photos") returns an
  empty MediaStore for newest captures. `hasPartialMediaAccess()`
  detects this and routes the user to app settings.
- **versionCode comes from `github.run_number`**, not from a literal in
  `build.gradle.kts`. Local builds fall back to `1`. Don't hard-code a
  versionCode — it'd collide with CI builds installed on the same device.

## What NOT to do

- Don't add a debug-only CI job. Release-capable on every push is the
  rule. Patrick stated this explicitly: "das soll immer
  releasebefähigend sein zum play store!"
- Don't reintroduce the boot receiver or `RECEIVE_BOOT_COMPLETED`.
  Reboot ≠ user opened the app.
- Don't add `@SuppressLint` to silence individual BLE calls. Use the
  class-level suppression already in place.
- Don't enable lint in CI without first cleaning the 18 pre-existing
  infrastructure warnings (launcher icon shapes, AGP version warnings,
  Gradle dep upgrades). Those are known and intentionally deferred.
- Don't bump dependencies opportunistically. The Compose BOM pins the
  whole UI stack. Bumping individual UI artefacts breaks the BOM
  contract; bump the BOM version and validate.

## Repo layout

```
app/src/main/java/ch/fbc/krakenbridge/
   MainActivity.kt              — Compose host + permission walkthrough
   KrakenBleService.kt          — BLE foreground service
   KrakenAccessibilityService.kt — key/gesture injection
   KrakenWakeActivity.kt        — screen wake (transparent, no UI)
   KrakenScreenOverlayManager.kt — keep-screen-on overlay + idle dimmer
   ui/
     MainScreen.kt              — Compose home (status hero + Connect CTA)
     PermissionScreen.kt        — single-CTA walkthrough screen
     HelpDialog.kt              — button-mapping help sheet
     Theme.kt, WaveBackground.kt — ocean palette + animated waves

app/src/androidTest/
   assets/features/             — Gherkin scenarios
   java/.../bdd/CucumberRunner.kt
   java/.../bdd/steps/          — Kotlin step definitions

.github/workflows/ci.yml        — push CI (signed release + BDD)
.github/workflows/release.yml   — tag-driven release pipeline
.github/scripts/run-bdd-tests.sh — emulator BDD runner

app/proguard-rules.pro          — R8 keep rules
docs/privacy-policy.html        — published privacy policy
```

## Known optimisation debt

Captured 2026-04-26 by an honest self-review after a cleanup pass that
fixed lifecycle, dropped unused deps, and replaced launcher icons.
These are **real** architectural debts, not lint pedantry. Tackle in
order of impact when the next session has a fresh head.

### High impact (architecture)

1. **`KrakenBleService` does too much (1003 lines, ~7 responsibilities).**
   BLE connection + button routing + MediaStore queries + 3 wake locks
   + notification + state machine (`isVideoMode`, `isGalleryMode`,
   `isRecording`, `cameraIsOpen`) + SharedPreferences + reconnect
   backoff. Decompose along SRP: `BleConnectionManager`,
   `ButtonEventRouter`, `CameraController` / `GalleryController`,
   `WakeLockHolder`, `KrakenPreferences`. The current class-level
   `@SuppressLint("MissingPermission")` then naturally narrows to a
   single `requireBlePermissions()` guard at the orchestrator boundary.

2. **`internal val test*` hooks leak test concerns into production.**
   `testIsVideoMode`, `testIsGalleryMode`, `testIsRecording`,
   `testCameraIsOpen`, `testQueryLatestMedia`, `simulateButtonPress`
   exist solely so BDD step definitions can introspect the service.
   Replace with observable state (StateFlow / SharedFlow) that
   production code also consumes — UI binds to it, tests read from it,
   no test-only properties survive in main.

3. **`KrakenAccessibilityService` ~500 lines, similar SRP issue.**
   Service lifecycle + key injection + gesture dispatch + a11y-tree
   dump + coordinate maths. Same decomposition argument.

### Medium impact (correctness, ergonomics)

4. **Reconnect backoff is hand-rolled** (`Handler.postDelayed` +
   `reconnectAttempts` counter). With kotlinx-coroutines already on
   the classpath transitively, a `Flow` with `retryWhen` + delay
   strategy reads more declaratively and is unit-testable without a
   service.

5. **`KrakenWakeActivity` `finish()` after a 600 ms heuristic delay.**
   Brittle on slow devices and wasteful on fast ones. The correct
   trigger is "the camera activity has gained focus" — bind to its
   `onResume` lifecycle (LifecycleObserver) or use
   `ActivityManager.getRunningAppProcesses` polling for the camera
   package, then finish.

6. **Hardcoded `com.google.android.GoogleCamera` and
   `com.google.android.apps.photos`.** Breaks on non-Pixel phones,
   which is half the Android market. Make the camera + gallery
   package configurable (preference + fallback to ACTION_IMAGE_CAPTURE
   resolver).

7. **No JVM unit tests.** Button code parsing (high/low nibble),
   debounce window, camera/gallery mode toggle, video-recording state
   machine — all pure logic, all testable without an emulator.
   Reintroduce the `unit-tests` CI job once tests exist.

8. **No BDD scenario for the permission walkthrough.** System
   permission dialogs are awkward to drive on the emulator, but
   UiAutomator can target the Settings app. Either add scenarios with
   that approach, or document explicitly in the feature file that the
   walkthrough is hand-tested.

### Low impact (polish, defer until you're touching the area)

9. **`gradle.properties` carries six deprecated AGP flags.**
   `r8.optimizedResourceShrinking=false`,
   `defaults.buildfeatures.resvalues=true`,
   `r8.strictFullModeForKeepRules=false`,
   `enableAppCompileTimeRClass=false`,
   `usesSdkInManifest.disallowed=false`,
   `uniquePackageNames=false`,
   `dependency.useConstraints=true` (some are still meaningful, some
   parrot defaults, some hold AGP back from improved behaviour).
   Verify each individually against AGP 9 defaults and drop the
   redundant ones; leave only what's intentionally non-default.

10. **`WaveBackground` computes three layered sine waves per frame.**
    Probably negligible, but on a 1 CPU / 1 GB device during a 4-hour
    dive the foreground service is fighting for the battery. If
    `dumpsys batterystats` ever flags the app, the wave background is
    where to look first. Consider running it only when status changes
    or pausing it when the app is not visible.

11. **`@SuppressLint("BatteryLife")` is correct but the Play Console
    declaration must match.** When updating the Play Store listing,
    fill out the "Acceptable use" form for
    REQUEST_IGNORE_BATTERY_OPTIMIZATIONS with the dive-companion
    rationale, otherwise Play review may flag it on a future submission.

## Quick recipes

```bash
# Watch the latest CI run
gh run watch $(gh run list --workflow=ci.yml --limit 1 --json databaseId -q '.[0].databaseId') --exit-status

# Pull a CI build's AAB to install on the maintainer's test device
gh run download <run-id> -R pafech/kraken-bridge --name kraken-bridge-ci-<sha>-apk

# Cut a release
git tag -a v1.2.4 -F /tmp/tag-msg.txt && git push origin v1.2.4

# Smoke-test the BLE service after install (with a Kraken paired)
adb logcat -s KrakenBLE:* KrakenA11y:*
```
