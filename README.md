# Gains Goblin 🏋️

[![CI](https://github.com/anandpapully-ux/gains-goblin/actions/workflows/ci.yml/badge.svg)](https://github.com/anandpapully-ux/gains-goblin/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/anandpapully-ux/gains-goblin)](https://github.com/anandpapully-ux/gains-goblin/releases/latest)

## Download
**[Download the latest APK](https://github.com/anandpapully-ux/gains-goblin/releases/latest/download/gains-goblin.apk)** (Android 8.0+).
Open it on your phone and allow "Install unknown apps" for your browser when asked. Every release also lists
`SHA256SUMS.txt` so you can verify the file. Older versions: [Releases](https://github.com/anandpapully-ux/gains-goblin/releases).

## About

Offline-first workout and calorie tracker. Kotlin, Jetpack Compose, Material 3, Hilt, Room (SQLCipher
encrypted), DataStore, WorkManager.

### Make it your own
- **Pick a starting plan** on first launch: Upper/Lower dumbbell + cardio (the original plan), Full Body 3-day,
  Upper/Lower 4-day and Push/Pull/Legs 6-day for the gym, Home dumbbells 3-day, or a blank week.
- **Edit everything**: rename days, change their type (strength / cardio / rest), move them to another weekday,
  add, remove, reorder or replace exercises, and set sets, reps, rest, RIR, notes and the weight jump per exercise.
- **80+ built-in exercises** (barbell, dumbbell, machine, cable, bodyweight, cardio) plus your own custom ones.
- **Several plans**: switch, duplicate, rename or delete them; history and records are shared across plans.
- **Share plans** as a `.json` file (Share or Save to file); a friend imports it from Workout > My plans.
- **Fixed weekdays or rotation**: in rotation mode Today shows the next day in your plan, whenever you train.
- **Your own targets**: override calories, protein and fat; training-day calorie cycling is balanced for your
  plan's number of lifting days, or can be switched off. Age, height and sex can be edited any time.
- **Workout history** with per-set detail; delete wrong sets, workouts, weight or measurement entries.
- **Full backup** (Profile > Back up) holds profile, plans, workouts and food, and restores on a new phone
  (also offered during setup).

### Progress photos
- **Front, side and back photos** from the camera or gallery, on a timeline grouped by month (Progress > Progress
  photos). The pose is suggested (front, then side, then back), the date comes from the photo, and the body weight
  you logged that day is attached.
- **Compare** any two dates of the same pose with a drag slider or side by side, with days apart and weight change,
  and share the comparison as one image.
- **Private by design**: photos are shrunk, stripped of location and camera metadata, encrypted on the device and
  never added to the phone's gallery. Export all as a ZIP (and import it on a new phone) from the Photos screen.
- A gentle nudge on the Progress tab when the last photo is more than two weeks old.

### Food logging
- **Offline food database**: about 1,000 cooked Indian dishes (dal makhani, idli, poori, rajma, biryani...) and
  180 common ingredients (rice, atta, dal, milk, eggs, chicken, fruit, nuts, oils), searchable without a network.
  Hindi and English names both work (dahi / curd, chawal / rice, aloo / potato).
- Dishes are logged per serving (1 bowl, 1 dosa, 1 roti); ingredients with a known weight can be logged in grams.
- Add several foods to a meal in one go: **+** adds a serving in one tap (with Undo), or open a food to pick the
  amount. Favorites and recent foods are one tap away.
- **Open Food Facts** search and barcode scan for packaged food (online), and custom foods.

## Build and run
Requirements: JDK 17 (a full JDK, not a JRE), Android SDK with platform 35 and build-tools 35.0.0.

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # to a connected device/emulator
./gradlew :app:testDebugUnitTest    # JVM unit tests (82): logic, plan format, bundled templates, food database, photos
./gradlew :app:connectedDebugAndroidTest   # 17 device tests: migrations, plans, backup, photo encryption, Compose (needs an emulator)
```
Or open the folder in Android Studio (Ladybug or newer) and press Run.

No API keys are needed. Network is used only for the optional Open Food Facts search/barcode lookup; the bundled
food database works offline.

## Permissions
| Permission | Why |
|---|---|
| `INTERNET` | Optional Open Food Facts food search and barcode lookup |
| `POST_NOTIFICATIONS` | Optional daily workout reminder (rationale dialog shown first) |
| `VIBRATE` | Rest-timer alert |
Barcode scanning uses Google's code scanner (Play services) and progress photos use your camera app or the system
photo picker, so the app itself needs no camera or storage permission.
Voice logging uses the device's speech recognizer.

## Plan files
A shared plan is JSON (`"format": "gains-goblin-plan"`); see `PlanCodec.kt` for the full description. Days left out
are rest days, and the file carries the definitions of every exercise it uses, so custom exercises travel with it.
The bundled library and templates are generated by `tools/generate_plan_assets.py`.

## Food database
`app/src/main/assets/food_db.json` is built by `tools/generate_food_db.py` (see its header for the source downloads
and every change made to the data) from:
- **Indian Nutrient Databank (INDB)**: Vijayakumar A, Dubasi HB, Awasthi A, Jaacks LM. *Development of an Indian Food
  Composition Database.* Current Developments in Nutrition 2024;8(7):103790, licensed
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Changed: deep-frying oil reduced to the amount absorbed,
  liquid stock (coded as dry stock cubes in the source) counted as water, implausible guessed servings replaced by
  100 g, five crisp fried snacks left out.
- **USDA FoodData Central**, SR Legacy (2018), public domain.

## Layout
`domain/` pure Kotlin logic (1RM, progression, calories, PR, plates, warm-ups, voice parsing, streaks, schedule,
plan file format) and use cases; `data/` Room, DataStore, repositories, plan assets, food database (`catalog/`), Open Food Facts client, backup; `feature/*` Compose
screens + ViewModels per tab; `core/` theme, charts, navigation. Schema: `docs/02-schema-and-structure.md`.

## Releasing a new version
1. Commit to `main` (CI runs unit tests, lint and a debug build).
2. Tag and push: `git tag v1.2.3 && git push origin v1.2.3`
3. The `Release` workflow tests, builds and signs the APK and publishes a GitHub Release with
   `gains-goblin-1.2.3.apk`, a stable `gains-goblin.apk` (used by the download link above) and checksums.

Signing uses four repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Every release must be signed with the same key, or phones will refuse to update over an installed version.
