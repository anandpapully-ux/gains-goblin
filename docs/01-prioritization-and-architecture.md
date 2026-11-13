# Step 2: Feature Prioritization and Architecture

Decisions: 6 images/notes resolved. Plan defaults: Day 2 Bulgarian split squat starts at 3 sets, with a
"drop to 2" option when RPE trends high. Primary exercises are the first-named option, and alternatives
appear as swap options in the UI. Cardio is Wed and Sat, rest is Sun.

## Feature prioritization

### MVP (milestones M1-M6)
| # | Feature |
|---|---|
| M1 | Project skeleton, Hilt, Room, DataStore, Material 3 theme (light/dark/dynamic), bottom nav |
| M2 | Onboarding (units, height, weight, goal, experience) and seeding of the routine from `workout_plan.json` |
| M3 | Workout plan view/edit (add, remove, reorder, change sets/reps), exercise library, plate calculator, warm-up generator |
| M4 | Live session: set logging (weight/reps/RPE), rest timer with vibration and sound, auto-fill last session, previous best, PR detection, e1RM, volume, overload suggestion, gym mode (wake lock, large buttons) |
| M5 | Nutrition: custom foods, daily log, favorites/recents, calorie target (Mifflin-St Jeor + goal), macros, training/rest-day adjustment, hydration |
| M6 | Progress: weight chart, strength per lift, weekly volume, streaks, calories in vs. out; JSON/CSV export and import |

Domain unit tests (1RM, calorie math, overload rules, PR detection, plate math) ship with the milestone that adds each rule.

### v2
Barcode scan (CameraX + ML Kit + Open Food Facts), Health Connect (steps, HR, weight, workouts, sleep),
adaptive coach/deload, recovery score, muscle heatmap, widgets (Glance), smart reminders (WorkManager),
voice logging, progress photos and compare, weekly/monthly reports, injury log with substitutions,
gamification (opt-in), encrypted backup.

### v3 / stretch
AI photo meal logging (opt-in, user-supplied API key, the only network feature that sends personal
data, with an explicit consent screen), Wear OS companion.

## Architecture

MVVM + Clean Architecture, single Gradle app module at first, split by package. Package boundaries
are enforced so the layers can be extracted into modules later.

```
presentation (Compose screens, ViewModels, UI state)  ->  domain (models, use cases, repository interfaces, pure logic)
data (Room, DataStore, Health Connect, network)       ->  implements domain interfaces
```

- Unidirectional data flow: `UiState` exposed as `StateFlow`, `UiEvent` in, one-shot effects via `Channel`.
- Domain layer is pure Kotlin (no Android imports), so the logic is plain JUnit tests.
- Offline-first: Room is the single source of truth. Network (Open Food Facts) only caches into Room.
- Privacy: Room encrypted via SQLCipher with a key held in Android Keystore. DataStore for settings.
  `allowBackup=false`. No account and no analytics.
- Hilt modules: Database, Repositories, Dispatchers, Clock (injectable for tests).
- Navigation: Navigation-Compose, 5 bottom-bar destinations with nested graphs.
- WorkManager (v2): reminders, Health Connect sync.

### Key domain rules (tested)
- **e1RM:** Epley `w * (1 + reps/30)`; Brzycki used for reps <= 10 as a cross-check. e1RM is not computed above 12 reps.
- **Double progression (compound):** all sets at the rep max at RIR <= target, then +1 dumbbell step. Otherwise keep the load.
- **Isolation progression:** rep ceiling 20 (arms/legs) or 25 (delts/rear delts), then step up. Matches the rules in your plan.
- **Calories:** BMR Mifflin-St Jeor, TDEE = BMR x activity. Cut = 0.5-0.7% bodyweight/week deficit (kcal = kg * 7700 / 7), clamped to a safe floor. Protein 1.8-2.2 g/kg.
- **Workout burn:** MET x kg x hours, optionally HR-refined later.

## Known constraints
- This machine has JDK 21 and adb but **no Android SDK or Gradle**. I can write all the code and tests,
  but can't compile here until the SDK is installed (command-line tools + `ANDROID_HOME`). Domain tests
  could still run as plain JVM tests. I'll flag any code I couldn't compile.

Next: Step 3 (database schema and folder structure).
