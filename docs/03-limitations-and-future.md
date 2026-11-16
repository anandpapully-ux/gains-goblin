# Known limitations and future ideas

## Verification status (1.1.0)
- 71 JVM unit tests and 15 instrumented tests (Room 1 -> 2 migration, plan create/switch/move/import/export,
  full backup restore, v1 backup merge, Compose components) pass on an Android 15 emulator. Lint: 0 errors.
- The signed release APK was installed over the published 1.0.0 with real data (profile, a finished workout,
  water): everything carried over and targets were unchanged. Onboarding, template plans, day editing, replacing
  exercises, rotation mode, custom targets, history and the share sheet were exercised on the emulator.
- Not exercised on a device: voice input, the barcode scanner, and the reminder notification.

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
- Swapping an exercise only for one session (without changing the plan) is not supported; replace it in the day editor
- Plans are capped at 7 days (one per weekday slot); rotation mode uses the same slots in order
- Instrumented UI tests cover building blocks only, not whole flows (no Hilt test harness yet)

## Known behaviors to be aware of
- Open Food Facts values are per 100 g; log "1.5 servings" = 150 g.
- A version 2 backup (1.1.0+) replaces all data on restore, after a confirmation. Version 1 backups are merged by id;
  sets for exercises the device does not know are skipped instead of failing.
- Release builds enable R8; it has not been exercised, so check SQLCipher/Hilt on a release build.

## Future ideas
Health Connect read/write, widgets (Glance), Wear OS rest timer, AI meal photo estimates (opt-in, own API key),
body-diagram heatmap, exercise demo media, superset support, tempo/notes per set, session-only exercise swaps,
adaptive reminder time, encrypted cloud-free backup (file + passphrase), opening shared plan files directly from
chat apps, localisation.
