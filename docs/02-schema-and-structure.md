# Step 3: Database Schema and Project Structure

Conventions: Room + SQLCipher. Ids are `Long` autogenerate unless noted. Timestamps are epoch millis (UTC).
Dates are `LocalDate` stored as ISO text (`yyyy-MM-dd`). Weights are stored in **kg** and converted for display.
Enums are stored as text via TypeConverters.

## Tables

### Profile and settings
| Table | Columns |
|---|---|
| `user_profile` (single row, id=1) | heightCm, birthDate, sex, activityLevel, goal (CUT/MAINTAIN/BULK), experience, unitSystem, targetWeightKg?, weeklyRatePct, createdAt |
| `body_weight_entry` | id, date (unique), weightKg, source (MANUAL/HEALTH_CONNECT) |
| `body_measurement` | id, date, type (WAIST/CHEST/ARM/THIGH/HIP/...), valueCm |

UI preferences (theme, gym mode, rest-timer sound, reminders) live in DataStore, not Room.

Schema version 2 (app 1.1.0) adds, via Room auto-migration: `user_profile.customKcal/customProteinG/customFatG`
(user's own targets, null = calculated) and `exercise.incrementKg` (weight jump, null = equipment default).
Several routines can exist; exactly one has `isActive = 1`.

### Exercises and routine
| Table | Columns |
|---|---|
| `exercise` | id, slug (unique, e.g. `flat_dumbbell_bench_press`), name, primaryMuscle, equipment, instructions, progressionRule (COMPOUND/ISOLATION_ARM_LEG/ISOLATION_DELT/TIMED_CORE/DURATION), isTimed, isUnilateral, isCustom |
| `exercise_muscle` | exerciseId FK, muscle, role (PRIMARY/SECONDARY). PK (exerciseId, muscle). Drives the heatmap and volume per muscle |
| `exercise_alternative` | exerciseId FK, alternativeId FK. Drives swaps and injury substitution |
| `routine` | id, name, description, isActive |
| `routine_day` | id, routineId FK, dayOfWeek (1-7), title, type (HYPERTROPHY/CARDIO/REST), estimatedMinutes |
| `routine_exercise` | id, dayId FK, exerciseId FK, position, targetSets, repMin, repMax, restMinSec, restMaxSec, rirMin, rirMax, perSide, targetWeightKg?, notes |

`routine_exercise.position` is what reordering updates. Index: `(dayId, position)`.

### Workout logging
| Table | Columns |
|---|---|
| `workout_session` | id, routineDayId?, startedAt, endedAt?, status (ACTIVE/DONE/DISCARDED), notes, caloriesBurned?, avgHeartRate?, perceivedFatigue? |
| `workout_set` | id, sessionId FK, exerciseId FK, setIndex, weightKg, reps?, durationSec?, rpe?, rir?, isWarmup, isPr, completedAt. Index: `(exerciseId, completedAt)` for previous best and last session |
| `personal_record` | id, exerciseId FK, type (E1RM/WEIGHT/REPS_AT_WEIGHT/VOLUME), value, setId FK, achievedAt |

### Nutrition
| Table | Columns |
|---|---|
| `food` | id, barcode? (unique), name, brand?, servingSize, servingUnit, kcal, protein, carbs, fat, fiber?, source (CUSTOM/OFF/INDB/USDA), isFavorite, lastUsedAt? |
| `meal_entry` | id, date, mealType (BREAKFAST/LUNCH/DINNER/SNACK), foodId FK, servings, kcal, protein, carbs, fat (snapshot, so later food edits don't rewrite history), loggedAt |
| `water_entry` | id, date, amountMl, loggedAt |
| `nutrition_target` | id, date (unique), kcal, protein, carbs, fat, dayType (TRAINING/REST/CARDIO) |

### Progress photos (database version 3)
| Table | Columns |
|---|---|
| `progress_photo` | id, takenOn (indexed), pose (FRONT/SIDE/BACK), fileName, width, height, weightKg? (body weight on or before takenOn, copied), note, createdAt |

The image is not in the database. `PhotoStore` re-encodes every photo (upright, long edge 1600 px, which drops all
EXIF metadata including GPS) and writes `files/photos/<fileName>.img` plus a 360 px `<fileName>.thumb`, each
AES-GCM encrypted (12-byte IV prefix) with a 256-bit key that `DbKeyProvider` wraps with a Keystore key, like the
database passphrase. Photos are not in the JSON backup; the Photos screen exports a ZIP of plain JPEGs with a
`photos.json` manifest (`"format": "gains-goblin-photos"`) and imports it back.

### Later (v2)
| Table | Columns |
|---|---|
| `soreness_checkin` | id, date, muscle, level 0-3 |
| `injury_log` | id, muscle/joint, severity, startedAt, resolvedAt?, note |
| `recovery_score` | date (PK), score, sleepHours?, restingHr?, loadIndex, recommendation |
| `achievement` | id (string), unlockedAt; `xp_event` id, amount, reason, at |

## Relations
```
routine 1-* routine_day 1-* routine_exercise *-1 exercise
exercise 1-* exercise_muscle | exercise *-* exercise (alternatives)
routine_day 1-* workout_session 1-* workout_set *-1 exercise
workout_set 1-0..1 personal_record
food 1-* meal_entry
```
Seeding: every launch adds any missing built-in exercises (`assets/exercise_library.json` plus the exercises of
`assets/workout_plan.json`) by slug, never changing existing rows. The routine is created when the user picks a
starter plan during onboarding (or later under My plans); `RoutineRepository.createFromPlan` inserts the routine,
its 7 day slots and their exercises in one transaction, matching exercises by slug so history and PRs are shared
across plans. The original plan's `..._day1`/`..._day4` duplicates map to one canonical slug.

## Folder structure
```
myfitnesspal144p/
  settings.gradle.kts  build.gradle.kts  gradle/libs.versions.toml  gradle.properties
  docs/
  app/
    build.gradle.kts  proguard-rules.pro
    src/main/
      AndroidManifest.xml
      assets/workout_plan.json
      res/ (values, xml/backup_rules, drawable, mipmap)
      java/com/healthvectors/fitness/
        FitnessApp.kt  MainActivity.kt
        di/            DatabaseModule, RepositoryModule, DispatcherModule
        core/          util (Result, Clock, units), designsystem (theme, components), navigation
        domain/
          model/       Exercise, Routine, WorkoutSet, Food, Targets ...
          repository/  interfaces
          logic/       OneRepMax, Progression, CalorieCalculator, PlateCalculator, WarmupGenerator, PrDetector
          usecase/     per feature
        data/
          local/       FitnessDatabase, dao/, entity/, converters/; plan/PlanAssets (library + templates)
          repository/  *Impl
          datastore/   SettingsStore
          catalog/     FoodCatalog: bundled offline food database (assets/food_db.json)
          photos/      PhotoStore (encrypted image files), PhotoRepository (rows, ZIP export/import, share image)
          remote/      OpenFoodFacts (v2)
          health/      HealthConnect (v2)
        feature/
          onboarding/  today/  workout/ (plan, editor, session, library)
          nutrition/   progress/  profile/
        worker/        (v2)   widget/ (v2)
    src/test/          domain logic unit tests
    src/androidTest/   Room DAO tests, Compose UI flows
```
Package ID: `com.healthvectors.fitness` (change if you prefer another).

## Decisions to be aware of
- Meal entries snapshot macros, because editing a food shouldn't rewrite past logs.
- `workout_set.isPr` is denormalised for fast celebration display; `personal_record` is the source for history.
- Room schema export is on, and migrations are tested from v1 onward.

Next: Step 4, source code one milestone at a time, starting with M1 (Gradle setup, theme, DI, database, navigation shell).

## As built (deviations from the plan above)
- `exercise.alternatives` is a pipe-separated text column; there is no `exercise_alternative` table.
- `exercise.repUnit` ("reps"/"seconds"/"minutes") replaces `isTimed`; `workout_set.reps` holds reps, seconds or minutes accordingly.
- No `nutrition_target` table: targets are computed from the profile, latest weight and day type (`DailyTargets`).
- Tables not built: `personal_record` (PRs are flagged on `workout_set.isPr`), soreness, injury, recovery, achievements.
- Repositories return Room entities directly instead of separate domain models; business rules live in `domain/logic`.
