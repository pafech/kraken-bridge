# Kraken Bridge

Android app that bridges a [Kraken](https://www.krakenunderwatersystems.com/) dive housing's BLE remote to Google Camera and Google Photos on an Android phone. Divers control the camera entirely through the housing buttons — no touchscreen interaction needed underwater.

## How it works

```
Kraken housing  ──BLE──>  KrakenBleService  ──intent/gesture──>  Google Camera
   buttons                (foreground service)                    Google Photos
```

1. **KrakenBleService** runs as a foreground service, maintains a BLE connection to the Kraken housing, and translates button presses into actions.
2. **KrakenAccessibilityService** injects tap gestures and key events into Google Camera and Google Photos (required because third-party apps can't programmatically control the camera shutter or navigate Photos).
3. The service survives process death (SharedPreferences + `START_STICKY`) and device reboots (`BOOT_COMPLETED` receiver).

## Button mapping

### Camera mode

| Button | Action |
|--------|--------|
| Shutter (red) | First press: open camera. Subsequent: take photo / start-stop video |
| Fn | Toggle photo / video mode |
| Plus (+) | Focus closer |
| Minus (-) | Focus farther |
| OK | Auto-focus (center) |
| Back | Switch to gallery |

### Gallery mode

| Button | Action |
|--------|--------|
| Plus (+) | Next photo/video (swipe left) |
| Minus (-) | Previous photo/video (swipe right) |
| OK | Delete current photo/video |
| Back / Shutter | Return to camera |

Gallery mode opens the most recently captured photo or video directly in single-item view — designed for divers reviewing shots during safety stops.

## Requirements

- Android 8.0+ (API 26), tested up to Android 15 (API 36)
- Google Camera and Google Photos installed
- Kraken dive housing with BLE remote

## Setup

1. Install the APK (sideload or via Google Play)
2. Grant permissions when prompted: Bluetooth, Location, Notifications, Photos/Videos
3. Enable the accessibility service: **Settings > Accessibility > Kraken Bridge**
4. Open the app, tap **Connect to Kraken**
5. Wait for "Ready" status, then place the phone in the housing

### Permissions

| Permission | Why |
|---|---|
| Bluetooth Scan/Connect | Discover and connect to the Kraken housing |
| Location | Required by Android for BLE scanning |
| Notifications | Foreground service notification (connection status) |
| Photos & Videos | Query MediaStore to open the latest capture in gallery mode |
| Wake Lock | Keep the BLE connection alive during a dive |
| Battery Optimization Exemption | Prevent Android from killing the service |

On Android 14+, grant **full** photo access ("Allow all") rather than "Select photos" — partial access prevents the app from finding your latest capture.

## Architecture

```
app/src/main/java/com/krakenbridge/
├── MainActivity.kt                  # Compose UI, permissions, status display
├── KrakenBleService.kt              # Foreground BLE service, button event handling
├── KrakenAccessibilityService.kt    # Gesture/key injection into Camera and Photos
├── BootReceiver.kt                  # Auto-reconnect after device reboot
├── BuildInfo.kt                     # Shared version constant
└── ui/
    ├── MainScreen.kt                # Main Compose screen
    ├── HelpDialog.kt                # In-app help overlay
    └── Theme.kt                     # Material 3 theme
```

### Key design decisions

- **Foreground service, not a bound service** — the BLE connection must outlive the activity. `START_STICKY` ensures Android restarts it after process death.
- **Accessibility service for camera control** — Android provides no public API to trigger the shutter or navigate Google Photos. The accessibility service dispatches tap gestures at the shutter button coordinates and swipe gestures for photo navigation.
- **MediaStore query for gallery** — instead of opening Google Photos home and navigating to the latest photo, the app queries MediaStore for the newest image/video and opens it directly with `ACTION_VIEW` + MIME type, landing in single-item view.
- **No `autoConnect`** — the app manages all reconnection logic (exponential backoff, 5 attempts, then fallback to scan) rather than relying on the OS Bluetooth stack's unreliable auto-reconnect.

## Building

```bash
# Debug APK
./gradlew assembleDebug

# Run BDD tests on connected device or emulator
./gradlew connectedDebugAndroidTest
```

Requires JDK 17.

## CI/CD

Two GitHub Actions workflows automate everything:

| Workflow | Trigger | Output |
|---|---|---|
| `ci.yml` | Push to `main`, PRs | Debug APK + BDD tests on emulator |
| `release.yml` | Tag push (`v*`) or manual | Signed APK + AAB + GitHub Release |

### Creating a release

```bash
git tag v1.3.0
git push origin v1.3.0
```

The release workflow builds a signed APK (for GitHub Releases / sideloading) and a signed AAB (for Google Play Store upload). The tag drives `versionName`; `versionCode` is `github.run_number` (monotonically increasing).

### Repository secrets

| Secret | Description |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w 0 kraken-release.jks` |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Create a keystore if you don't have one:

```bash
keytool -genkeypair -v \
  -keystore kraken-release.jks \
  -alias kraken \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

## BDD test suite

Gherkin feature files define the user-facing behavior:

| Feature | Scenarios | CI-safe |
|---|---|---|
| `photo_capture.feature` | Shutter press, camera launch, focus | Partial (state assertions run, gesture assertions skip without device) |
| `video_recording.feature` | Mode switching, start/stop recording | Partial |
| `gallery_and_delete.feature` | Gallery navigation, photo deletion | `@device-only` scenarios require real Google Photos |
| `gallery_intent.feature` | MediaStore query, intent construction | Full (seeds emulator MediaStore, asserts URI + MIME type) |

Scenarios tagged `@device-only` or `@manual` are excluded from CI. Run them on a physical device:

```bash
adb shell am instrument -w \
  -e tags '@device-only' \
  com.krakenbridge.test/io.cucumber.android.runner.CucumberAndroidJUnitRunner
```

## Publishing to Google Play

The release workflow produces a signed AAB artifact (`kraken-bridge-<version>-playstore-aab`). To publish:

1. Create a release tag and wait for CI to complete
2. Download the AAB artifact from the GitHub Actions run
3. Go to [Google Play Console](https://play.google.com/console) > **Production** > **Create new release**
4. Upload the `.aab` file
5. Fill in release notes and submit for review

### Play Store listing prerequisites

- [x] App icon (512x512 PNG)
- [x] Feature graphic (1024x500 PNG)
- [x] At least 2 screenshots (phone)
- [x] Short description (80 chars)
- [x] Full description
- [x] Privacy policy URL: `https://pafech.github.io/kraken-bridge/privacy-policy.html`
- [ ] Content rating questionnaire (IARC, completed in Play Console)
- [ ] Target audience and content declaration (18+, no ads, no IAP)
- [x] App category: Photography

### Accessibility service declaration

Google Play requires a declaration explaining why the app uses an accessibility service. The justification: Kraken Bridge uses the accessibility service to inject tap gestures and key events into Google Camera and Google Photos on behalf of the user, who cannot touch the screen while the phone is sealed inside a dive housing. No user data is collected, read, or transmitted.

## License

MIT
