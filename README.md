# Kraken Dive Photo

Android app that connects a [Kraken](https://www.krakenunderwatersystems.com/) dive housing's BLE remote to the phone's default camera and gallery apps. Divers control the camera entirely through the housing buttons — no touchscreen interaction needed underwater.

The shutter button opens whichever camera app the system launches via `android.media.action.STILL_IMAGE_CAMERA` (Google Camera on Pixel, Samsung Camera on Galaxy, etc.) and the back button opens the latest capture in whichever gallery app the system uses for the captured media. Per-vendor behaviour quirks (button identifiers, mode-switch swipes, delete confirmations) live in dedicated adapters under `vendor/`. Two adapters ship today: stock-Android (Google Camera + Photos, tuned on Pixel) and Samsung (Samsung Camera + Gallery, tuned on Galaxy S20+ / One UI 5.1).

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

- Android 8.0+ (API 26)
- A camera app and a gallery app set as the system defaults for capture and image viewing (Google Camera + Photos on Pixel, Samsung Camera + Gallery on Galaxy, …)
- Kraken dive housing with BLE remote

## Setup

1. Install from Google Play or sideload the APK
2. Grant permissions when prompted: Bluetooth, Notifications, battery optimization exemption (plus Location on Android 11 and older); Photos/Videos only if you turn on Gallery
3. Turn on **Camera** in the app's Settings page. It walks through the permissions and, after the in-app disclosure, opens Android's Accessibility settings — turn on **Kraken Dive Photo** there
4. Optional but recommended: turn on **Dive Mode** and allow display over other apps (keeps the screen reachable underwater — see below)
5. Open the main screen and tap the circle to connect
6. Wait for "Ready" status, then place the phone in the housing

### Permissions

| Permission | Why |
|---|---|
| Bluetooth Scan/Connect | Discover and connect to the Kraken housing |
| Location (Android 11 and older only) | Required for BLE scanning before Android 12 introduced a dedicated Bluetooth Scan permission. The app does not access your location. |
| Notifications | Foreground service notification (connection status) |
| Battery optimization exemption | Keep the BLE connection alive for the whole dive |
| Photos & Videos | Query MediaStore to open the latest capture in gallery mode |
| Display over other apps | Keep the screen on without hitting the lockscreen, while dimming to save battery |

On Android 14+, grant **full** photo access ("Allow all") rather than "Select photos" — partial access prevents the app from finding your latest capture.

### Why does Kraken Dive Photo need an accessibility service?

Android provides no public API to trigger the camera shutter or navigate a gallery app from a third-party app. The accessibility service performs taps and swipes in the foreground camera/gallery on your behalf — necessary because the phone is sealed inside a dive housing and the touchscreen is inaccessible. To find the right button it reads the on-screen controls of that camera or gallery app; nothing is stored or transmitted. It acts only after you agree to the in-app disclosure.

### Why does Kraken Dive Photo need to display over other apps?

A secure lockscreen (PIN, fingerprint, face unlock) cannot be cleared underwater, and on most modern Android phones it cannot be disabled either (stored credentials, work profiles, OEM policy). The lockscreen only engages after the screen turns off — so with Dive Mode on, while you are connected, the app keeps the screen on with a fully transparent overlay that blocks no touches. After ~45 seconds without input the overlay dims itself to the hardware minimum to save battery. The brightness comes back instantly on a housing button press, or when the screen turns on again after an unlock. A touch on the screen does not restore it. When the diver wakes the screen with a housing button, that first press only restores brightness — it does not take a photo, start a recording, or switch modes. The next press performs the actual action, so a composed shot is never lost to a wake-tap. The idle dimmer is also suspended for the duration of a video recording so the diver can frame longer shots without the screen going dark mid-take. The overlay attaches when you connect and detaches when you disconnect or swipe the app from Recents.

## Privacy

[Privacy policy](https://pafech.github.io/kraken-bridge/privacy-policy.html)

## License

MIT
