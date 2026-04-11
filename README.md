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

## CI / CD Pipeline

APK generation is fully automated — no manual Android Studio build required.

### How it works

Two GitHub Actions workflows handle the entire build and release process:

```
Every push / PR              Tag push (v*)
─────────────────            ──────────────────────────────
ci.yml                       release.yml
  │                            │
  ├─ Checkout                  ├─ Checkout
  ├─ JDK 17 + Gradle cache     ├─ JDK 17 + Gradle cache
  ├─ assembleDebug             ├─ Derive version from tag
  └─ Upload artifact           ├─ Decode keystore secret
       (7-day retention)       ├─ assembleRelease (signed)
                               ├─ Clean up keystore
                               ├─ Upload artifact (30-day)
                               └─ Create GitHub Release
                                    └─ APK attached
```

### Creating a release

1. Commit and push your changes to `main`
2. Tag the commit with a semantic version:
   ```bash
   git tag v1.2.3
   git push origin v1.2.3
   ```
3. The release workflow starts automatically. Within a few minutes a GitHub
   Release appears with the signed APK attached and auto-generated release notes.

The tag drives both the APK filename and the Android `versionName` (e.g. `v1.2.3`
→ `versionName = "1.2.3"`). The `versionCode` is set to `github.run_number`, a
monotonically increasing integer that Android requires for over-the-air upgrades.

You can also trigger a signed build manually without creating a release via
**Actions → Release → Run workflow** in the GitHub UI.

### First-time secrets setup

The release workflow requires four repository secrets
(**Settings → Secrets and variables → Actions → New repository secret**):

| Secret | How to get it |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w 0 kraken-release.jks` — paste the output |
| `KEYSTORE_PASSWORD` | Password chosen when creating the keystore |
| `KEY_ALIAS` | Alias chosen when creating the keystore |
| `KEY_PASSWORD` | Key password (often same as keystore password) |

If you don't have a keystore yet, create one (keep the `.jks` file safe and
**never commit it**):

```bash
keytool -genkeypair -v \
  -keystore kraken-release.jks \
  -alias kraken \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

### Security notes

- The decoded keystore is written to `$RUNNER_TEMP` (an ephemeral, isolated
  directory on the GitHub-hosted runner) and deleted immediately after the build
  via an `if: always()` cleanup step.
- `permissions: contents: write` is scoped only to the release job and is required
  solely to create the GitHub Release — it maps to a short-lived `GITHUB_TOKEN`.
- For stricter supply-chain security, consider pinning `softprops/action-gh-release`
  to a commit SHA instead of the `@v2` tag. Find the latest SHA at
  `github.com/softprops/action-gh-release/releases`.

### APK format — why not AAB?

AAB (Android App Bundle) is a Play Store-only publishing format. It cannot be
installed directly on a device — the Play Store unbundles it at install time.
Since Kraken Bridge is distributed via GitHub Releases and sideloaded, APK is the
correct and only viable format.

## License

MIT
