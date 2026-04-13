# Kraken Bridge

Android app that bridges a [Kraken](https://www.krakenunderwatersystems.com/) dive housing's BLE remote to Google Camera and Google Photos on an Android phone. Divers control the camera entirely through the housing buttons — no touchscreen interaction needed underwater.

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
- Google Camera and Google Photos installed
- Kraken dive housing with BLE remote

## Setup

1. Install from Google Play or sideload the APK
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

On Android 14+, grant **full** photo access ("Allow all") rather than "Select photos" — partial access prevents the app from finding your latest capture.

### Why does Kraken Bridge need an accessibility service?

Android provides no public API to trigger the camera shutter or navigate Google Photos from a third-party app. The accessibility service injects tap gestures and key events into these apps on your behalf — necessary because the phone is sealed inside a dive housing and the touchscreen is inaccessible. No user data is collected, read, or transmitted.

## Privacy

[Privacy policy](https://pafech.github.io/kraken-bridge/privacy-policy.html)

## License

MIT
