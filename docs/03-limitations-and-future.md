# Known limitations and future ideas

## Verification status (1.1.0)
- 71 JVM unit tests and 15 instrumented tests (Room 1 -> 2 migration, plan create/switch/move/import/export,
  full backup restore, v1 backup merge, Compose components) pass on an Android 15 emulator. Lint: 0 errors.
- The signed release APK was installed over the published 1.0.0 with real data (profile, a finished workout,
  water): everything carried over and targets were unchanged. Onboarding, template plans, day editing, replacing
  exercises, rotation mode, custom targets, history and the share sheet were exercised on the emulator.
- Not exercised on a device: voice input, the barcode scanner, and the reminder notification.

## Verification status (food database, 1.2.0)
- 77 JVM unit tests (6 new for the food database: data sanity, serving sizes, frying/stock corrections, search
  ranking and Hindi names) and the 15 instrumented tests pass on the Android 15 emulator. Lint: 0 errors.
- `tools/generate_food_db.py` reproduces the bundled `food_db.json` byte for byte from the pinned sources.
- On the emulator: database search, starring a database food, and logging a banana by grams (200 g = 177 kcal).
- UI refresh: every screen uses the shared building blocks in `core/designsystem/Components.kt` (cards, grouped
  lists, section titles, stat tiles, form sheets). The app's own teal palette is now the default; "Match wallpaper
  colours" (Android 12+ dynamic colour) is opt-in under Profile > Appearance. Checked on the emulator in light and
  dark mode: onboarding, Today, Workout, day editor, a full session (sets, rest timer, PR, summary), history, plans,
  Nutrition, Add food, Progress and Profile.

## Upgrade check (1.2.0)
- The signed 1.2.0 release (same certificate as 1.0.0 and 1.1.0, SHA-256 4081...267b; versionCode 10200 > 10100)
  was installed with `adb install -r` over the published 1.1.0 holding a custom food log, a finished workout, water
  and a weight entry. Everything carried over (database 2 -> 3 migration); food search and progress photos then
  worked in that R8-minified build.

## Verification status (progress photos)
- Unit tests for pose suggestion, default comparison pairs, the reminder, image sizing and the ZIP manifest; device
  tests for the database 2 -> 3 migration and for `PhotoStore` (EXIF rotation applied, long edge 1600 px, GPS and
  dates stripped, file on disk is not a JPEG, round trip, delete).
- On the emulator: five gallery photos added with "Save and add another" (dates read from EXIF, side poses
  suggested automatically), grid with pose filters, viewer, slider and side-by-side compare, share sheet, ZIP export
  (plain JPEGs, no EXIF) and import after deleting one (only the missing photo came back).
- Not exercised: the camera path on a real phone (it uses the system camera app through `TakePicture`).

## Not implemented (from your brief)
- AI photo meal logging, Wear OS companion, home-screen widgets, Health Connect (steps/HR/weight/workouts/sleep)
- Recovery score, injury/pain log with substitutions, badges/weekly challenges (only streak + XP/level exist)
- Encrypted backup file (the live database is encrypted at rest; exports are plain JSON/CSV)
- Smart reminders only skip rest days / already-trained days; they do not learn your usual workout time
- Muscle "heatmap" is colored tiles per muscle, not a body diagram
- Heart-rate-based calorie burn (MET x weight x time only); calorie goal ignores logged workout burn
- Barcode scanning uses Google's Play-services code scanner rather than CameraX + ML Kit directly
- Adaptive coach is rule-based (double progression + a deload hint from RPE); no automatic plan rewriting
- Domain models: repositories expose Room entities (see docs/02)
- Swapping an exercise only for one session (without changing the plan) is not supported; replace it in the day editor
- Plans are capped at 7 days (one per weekday slot); rotation mode uses the same slots in order
- Instrumented UI tests cover building blocks only, not whole flows (no Hilt test harness yet)

## Known behaviors to be aware of
- Progress photos are not in the JSON backup (they would make it huge); use Export all (ZIP) on the Photos screen.
  The ZIP holds plain JPEGs, so keep it somewhere private. Restoring a JSON backup leaves photos untouched.
- A photo taken with the camera is written by the camera app to the app's cache as a plain JPEG and deleted as soon
  as it has been read and encrypted (normally within a second).
- No pose-guide overlay while shooting and no pinch-zoom in the viewer (both need an in-app camera/zoom view).
- Open Food Facts values are per 100 g; log "1.5 servings" = 150 g.
- Food database dishes (INDB) are per serving and cannot be logged in grams: INDB weighs raw ingredients (cooking
  water included, evaporation ignored), so a gram of cooked dish does not match its numbers. Dishes without a
  believable serving show "100 g", which is 100 g of that raw-ingredient mix, not of the cooked dish.
- INDB values are recipe calculations, not lab measurements, and some recipes are rich: a few servings still look
  high (whole pies or cakes as "1 pie", sugar syrup counted in full for gulab jamun). Deep-frying oil, liquid
  stock and whole-batch guessed servings are corrected by the generator; anything else is INDB as published.
- Synonyms (dahi/curd, chawal/rice...) apply to the bundled database only; your saved foods are matched by name.
- A backup holding foods from the database (1.2.0+) cannot be restored on 1.1.0 or earlier, which reject the
  unknown food source; restore it on 1.2.0 or later. From 1.2.0 on, an unknown food source restores as a custom food.
- A version 2 backup (1.1.0+) replaces all data on restore, after a confirmation. Version 1 backups are merged by id;
  sets for exercises the device does not know are skipped instead of failing.
- Release builds enable R8; it has not been exercised, so check SQLCipher/Hilt on a release build.

## Future ideas
Health Connect read/write, widgets (Glance), Wear OS rest timer, AI meal photo estimates (opt-in, own API key),
body-diagram heatmap, exercise demo media, superset support, tempo/notes per set, session-only exercise swaps,
adaptive reminder time, encrypted cloud-free backup (file + passphrase), opening shared plan files directly from
chat apps, localisation.
