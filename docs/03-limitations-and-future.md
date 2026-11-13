# Known limitations and future ideas

## Verification status
- Verified here: `assembleDebug`, `lintDebug` (0 errors), and 45 JVM unit tests (1RM, progression, deload, calorie math,
  PR detection, plates, warm-ups, voice parsing, streaks) all pass. The androidTest sources compile.
- NOT verified: the app has never been launched. This machine has no emulator or device, so the UI flows, SQLCipher
  open/Keystore wrapping, the seeding test, Compose tests, voice input, barcode scanner and reminders are untested at runtime.
  Expect to find small runtime bugs on first launch.

## Not implemented (from your brief)
- AI photo meal logging, Wear OS companion, home-screen widgets, Health Connect (steps/HR/weight/workouts/sleep)
- Recovery score, injury/pain log with substitutions, badges/weekly challenges (only streak + XP/level exist)
- Progress photo timeline and side-by-side comparison, encrypted backup file (the live database is encrypted at rest; exports are plain JSON/CSV)
- Smart reminders only skip rest days / already-trained days; they do not learn your usual workout time
- Muscle "heatmap" is colored tiles per muscle, not a body diagram
- Heart-rate-based calorie burn (MET x weight x time only); calorie goal ignores logged workout burn
- Barcode scanning uses Google's Play-services code scanner rather than CameraX + ML Kit directly
- Adaptive coach is rule-based (double progression + a deload hint from RPE); no automatic plan rewriting
- Domain models: repositories expose Room entities (see docs/02)
- Instrumented UI tests cover building blocks only, not whole flows (no Hilt test harness yet)

## Known behaviors to be aware of
- Open Food Facts values are per 100 g; log "1.5 servings" = 150 g.
- Importing a JSON backup merges by id and can overwrite rows with the same id.
- Release builds enable R8; it has not been exercised, so check SQLCipher/Hilt on a release build.

## Future ideas
Health Connect read/write, widgets (Glance), Wear OS rest timer, AI meal photo estimates (opt-in, own API key),
body-diagram heatmap, exercise demo media, superset support, per-exercise dumbbell increments, tempo/notes per set,
adaptive reminder time, encrypted cloud-free backup (file + passphrase), localisation.
