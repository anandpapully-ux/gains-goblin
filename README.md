# Gains Goblin 🏋️

[![CI](https://github.com/anandpapully-ux/gains-goblin/actions/workflows/ci.yml/badge.svg)](https://github.com/anandpapully-ux/gains-goblin/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/anandpapully-ux/gains-goblin)](https://github.com/anandpapully-ux/gains-goblin/releases/latest)

## Download
**[Download the latest APK](https://github.com/anandpapully-ux/gains-goblin/releases/latest/download/gains-goblin.apk)** (Android 8.0+).
Open it on your phone and allow "Install unknown apps" for your browser when asked. Every release also lists
`SHA256SUMS.txt` so you can verify the file. Older versions: [Releases](https://github.com/anandpapully-ux/gains-goblin/releases).

## About

Offline-first workout and calorie tracker. Kotlin, Jetpack Compose, Material 3, Hilt, Room (SQLCipher
encrypted), DataStore, WorkManager. Seeded from your own 4-day upper/lower dumbbell plan
(`app/src/main/assets/workout_plan.json`, transcribed from your screenshots).

## Build and run
Requirements: JDK 17 (a full JDK, not a JRE), Android SDK with platform 35 and build-tools 35.0.0.

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # to a connected device/emulator
./gradlew :app:testDebugUnitTest    # domain-logic unit tests (45)
./gradlew :app:connectedDebugAndroidTest   # Room/seed + Compose tests (needs a device/emulator)
```
Or open the folder in Android Studio (Ladybug or newer) and press Run.

No API keys are needed. Network is used only for the optional Open Food Facts search/barcode lookup.

## Permissions
| Permission | Why |
|---|---|
| `INTERNET` | Optional Open Food Facts food search and barcode lookup |
| `POST_NOTIFICATIONS` | Optional daily workout reminder (rationale dialog shown first) |
| `VIBRATE` | Rest-timer alert |
Barcode scanning uses Google's code scanner (Play services), so the app itself needs no camera permission.
Voice logging uses the device's speech recognizer.

## Layout
`domain/` pure Kotlin logic (1RM, progression, calories, PR, plates, warm-ups, voice parsing, streaks) and
use cases; `data/` Room, DataStore, repositories, Open Food Facts client, export/import; `feature/*` Compose
screens + ViewModels per tab; `core/` theme, charts, navigation. Schema: `docs/02-schema-and-structure.md`.

## Releasing a new version
1. Commit to `main` (CI runs unit tests, lint and a debug build).
2. Tag and push: `git tag v1.2.3 && git push origin v1.2.3`
3. The `Release` workflow tests, builds and signs the APK and publishes a GitHub Release with
   `gains-goblin-1.2.3.apk`, a stable `gains-goblin.apk` (used by the download link above) and checksums.

Signing uses four repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Every release must be signed with the same key, or phones will refuse to update over an installed version.
