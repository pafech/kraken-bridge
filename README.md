# Kraken Bridge

A minimal Android app that bridges Kraken dive housing button presses to Google Camera via media key events.

## Features

- Connects to Kraken housing via BLE
- Runs as background service with wake lock (stays awake during dive)
- Uses AccessibilityService for reliable button dispatch
- **Auto-wake**: Any button press wakes device and opens camera if screen is off
- Maps hardware buttons to camera controls:
  - **Shutter (red)** → Take photo (`VOLUME_UP`)
  - **OK** → Take photo (`VOLUME_UP`)
  - **Minus (-)** → Zoom out (`VOLUME_DOWN`)
  - **Back** → Back navigation
  - **Fn** → Toggle video recording

**Important**: Configure Google Camera to use **volume buttons as shutter** (Settings → Gestures)

## Technical Details

### BLE Protocol

- **Device Name:** `Kraken`
- **Button Service UUID:** `00001523-1212-efde-1523-785feabcd123`
- **Button Characteristic UUID:** `00001524-1212-efde-1523-785feabcd123`

### Button Codes

| Button | Press | Release |
|--------|-------|---------|
| Shutter | 0x21 | 0x20 |
| Fn | 0x62 | 0x61 |
| Back | 0x11 | 0x10 |
| Plus | 0x41 | 0x40 |
| OK | 0x31 | 0x30 |
| Minus | 0x51 | 0x50 |

Pattern: High nibble = button ID, low nibble = state (0=released, 1=pressed)

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34
- Kotlin 1.9.20+

### Steps

1. Open project in Android Studio
2. Sync Gradle
3. Build → Build APK

Or via command line:
```bash
./gradlew assembleDebug
```

APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

## Installation

1. Enable "Install from unknown sources" on your Pixel
2. Transfer APK to phone
3. Install APK
4. Grant Bluetooth and Location permissions when prompted
5. **Enable Accessibility Service**: Settings → Accessibility → Kraken Bridge → Enable

## Setup Google Camera

1. Open Google Camera
2. Go to Settings → Gestures
3. Set **Volume key action** to "Shutter" (take photo)

## Usage

1. Turn on Kraken housing (battery inserted)
2. Open Kraken Bridge app
3. Tap "Connect to Kraken"
4. Wait for "Ready" status
5. Tap "Open Camera" or manually open Google Camera
6. **Put phone in housing** - buttons will now control camera
7. If screen turns off, any button press will wake it and reopen camera

## Known Limitations

- Requires Accessibility Service to be enabled (no root needed)
- Wake lock keeps device awake while connected (battery drain during dive is intentional)
- Plus (+) button is disabled to avoid conflict with shutter (both would trigger Volume Up)

## Troubleshooting

**Buttons not working:**
1. Check Accessibility Service is enabled in system settings
2. Make sure Google Camera is set to use volume as shutter

**Screen stays off:**
1. Press any button to wake - camera will open automatically

**Device sleeps during dive:**
1. The app holds a wake lock while connected, but aggressive battery savers may interfere
2. Disable battery optimization for Kraken Bridge

## License

MIT - Do whatever you want with this code.
