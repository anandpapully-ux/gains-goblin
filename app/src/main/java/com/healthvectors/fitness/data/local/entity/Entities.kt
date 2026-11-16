package com.healthvectors.fitness.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.healthvectors.fitness.domain.model.*
import java.time.LocalDate

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    val heightCm: Double,
    val birthDate: LocalDate,
    val sex: Sex,
    val activityLevel: ActivityLevel,
    val goal: Goal,
    val experience: Experience,
    val unitSystem: UnitSystem,
    val weeklyRatePct: Double,
    val createdAt: Long,
    /** The user's own daily targets; null keeps the calculated value. */
    val customKcal: Int? = null,
    val customProteinG: Int? = null,
    val customFatG: Int? = null
)

@Entity(tableName = "body_weight_entry", indices = [Index("date", unique = true)])
data class BodyWeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val weightKg: Double,
    val source: String = "MANUAL"
)

@Entity(tableName = "body_measurement", indices = [Index("type", "date")])
data class BodyMeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val type: String,
    val valueCm: Double
)

/**
 * [repUnit] is "reps", "seconds" or "minutes"; it defines what the `reps` field of a logged set means.
 * [alternatives] holds swap options as a pipe-separated list.
 */
@Entity(tableName = "exercise", indices = [Index("slug", unique = true)])
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slug: String,
    val name: String,
    val primaryMuscle: String,
    val equipment: String,
    val instructions: String,
    val progressionRule: ProgressionRule,
    val repUnit: String,
    val isUnilateral: Boolean,
    val alternatives: String = "",
    val isCustom: Boolean = false,
    /** Smallest weight jump for progression; null uses the default for its equipment. */
    val incrementKg: Double? = null
)

@Entity(
    tableName = "exercise_muscle",
    primaryKeys = ["exerciseId", "muscle"],
    foreignKeys = [ForeignKey(ExerciseEntity::class, ["id"], ["exerciseId"], onDelete = ForeignKey.CASCADE)]
)
data class ExerciseMuscleEntity(val exerciseId: Long, val muscle: String, val role: String)

@Entity(tableName = "routine")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val isActive: Boolean = true
)

@Entity(
    tableName = "routine_day",
    foreignKeys = [ForeignKey(RoutineEntity::class, ["id"], ["routineId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("routineId")]
)
data class RoutineDayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val dayOfWeek: Int, // 1 = Monday ... 7 = Sunday
    val title: String,
    val type: DayType,
    val estimatedMinutes: Int
)

@Entity(
    tableName = "routine_exercise",
    foreignKeys = [
        ForeignKey(RoutineDayEntity::class, ["id"], ["dayId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExerciseEntity::class, ["id"], ["exerciseId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("dayId", "position"), Index("exerciseId")]
)
data class RoutineExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: Long,
    val exerciseId: Long,
    val position: Int,
    val targetSets: Int,
    val repMin: Int,
    val repMax: Int,
    val restMinSec: Int,
    val restMaxSec: Int,
    val rirMin: Int,
    val rirMax: Int,
    val perSide: Boolean,
    val notes: String = ""
)

@Entity(tableName = "workout_session", indices = [Index("startedAt")])
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineDayId: Long?,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val notes: String = "",
    val caloriesBurned: Double? = null
)

@Entity(
    tableName = "workout_set",
    foreignKeys = [
        ForeignKey(WorkoutSessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExerciseEntity::class, ["id"], ["exerciseId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("sessionId"), Index("exerciseId", "completedAt")]
)
data class WorkoutSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Long,
    val setIndex: Int,
    val weightKg: Double,
    /** Reps, seconds or minutes depending on the exercise's repUnit. */
    val reps: Int,
    val rpe: Double? = null,
    val isWarmup: Boolean = false,
    val isPr: Boolean = false,
    val completedAt: Long
)

@Entity(tableName = "food", indices = [Index("barcode")])
data class FoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val barcode: String? = null,
    val name: String,
    val brand: String? = null,
    val servingSize: Double,
    val servingUnit: String,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val source: FoodSource = FoodSource.CUSTOM,
    val isFavorite: Boolean = false,
    val lastUsedAt: Long? = null
)

/** Macros are snapshotted so later edits to a food do not rewrite history. */
@Entity(
    tableName = "meal_entry",
    foreignKeys = [ForeignKey(FoodEntity::class, ["id"], ["foodId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("date"), Index("foodId")]
)
data class MealEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val mealType: MealType,
    val foodId: Long,
    val foodName: String,
    val servings: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val loggedAt: Long
)

@Entity(tableName = "water_entry", indices = [Index("date")])
data class WaterEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val amountMl: Int,
    val loggedAt: Long
)
