package com.healthvectors.fitness.domain.model

/** Display unit system. All values are stored in kg / cm regardless of this setting. */
enum class UnitSystem { METRIC, IMPERIAL }

enum class Sex { MALE, FEMALE }

enum class Goal { CUT, MAINTAIN, BULK }

enum class Experience { BEGINNER, INTERMEDIATE, ADVANCED }

/** Activity multiplier applied to BMR to estimate TDEE (outside of logged workouts). */
enum class ActivityLevel(val factor: Double) {
    SEDENTARY(1.2), LIGHT(1.375), MODERATE(1.55), ACTIVE(1.725)
}

enum class DayType { HYPERTROPHY, CARDIO, REST }

/** Names match the `progressionRule` values in workout_plan.json. */
enum class ProgressionRule {
    COMPOUND_DOUBLE_PROGRESSION,
    ISOLATION_HIGH_REP_PROGRESSION,
    TIMED_CORE,
    DURATION_DISTANCE
}

enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }

enum class SessionStatus { ACTIVE, DONE, DISCARDED }

enum class FoodSource { CUSTOM, OFF }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** WEEKLY: each plan day belongs to a weekday. ROTATION: train the next day in order whenever you go. */
enum class ScheduleMode { WEEKLY, ROTATION }
