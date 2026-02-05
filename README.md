# Kraken Bridge

Android app that bridges Kraken dive housing buttons to Google Camera and Google Photos.

## Features

- BLE connection to Kraken housing
- Background service with wake lock (stays awake during dive)
- Auto-wake: any button press wakes device and opens camera

## Button Mapping

### Camera Mode

| Button | Action |
|--------|--------|
| Shutter (red) | Take photo / Start-stop video |
| Fn | Toggle Photo ↔ Video mode |
| Plus (+) | Focus closer |
| Minus (-) | Focus farther |
| OK | Auto-focus (center) |
| Back | Switch to Gallery |

### Gallery Mode

| Button | Action |
|--------|--------|
| Plus (+) | Next photo/video |
| Minus (-) | Previous photo/video |
| OK | Delete |
| Back / Fn / Shutter | Return to Camera |

## Installation

1. Install APK and grant Bluetooth/Location permissions
2. Enable Accessibility Service: Settings → Accessibility → Kraken Bridge

## Usage

1. Open app, tap "Connect to Kraken"
2. Wait for "Ready" status
3. Open Camera and put phone in housing

## Building

```bash
./gradlew assembleDebug
```

## License

MIT
