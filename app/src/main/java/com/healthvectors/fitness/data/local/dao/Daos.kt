package com.healthvectors.fitness.data.local.dao

import androidx.room.*
import com.healthvectors.fitness.data.local.entity.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface ProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun observe(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun get(): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWeight(entry: BodyWeightEntity)

    @Query("SELECT * FROM body_weight_entry ORDER BY date")
    fun observeWeights(): Flow<List<BodyWeightEntity>>

    @Query("SELECT * FROM body_weight_entry ORDER BY date DESC LIMIT 1")
    suspend fun latestWeight(): BodyWeightEntity?

    @Query("SELECT * FROM body_weight_entry ORDER BY date DESC LIMIT 1")
    fun observeLatestWeight(): Flow<BodyWeightEntity?>

    @Query("DELETE FROM body_weight_entry WHERE id = :id")
    suspend fun deleteWeight(id: Long)

    @Insert
    suspend fun insertMeasurement(m: BodyMeasurementEntity)

    @Query("SELECT * FROM body_measurement WHERE type = :type ORDER BY date")
    fun observeMeasurements(type: String): Flow<List<BodyMeasurementEntity>>

    @Query("SELECT * FROM body_weight_entry ORDER BY date")
    suspend fun allWeights(): List<BodyWeightEntity>
}

data class RoutineExerciseWithExercise(
    @Embedded val item: RoutineExerciseEntity,
    @Relation(parentColumn = "exerciseId", entityColumn = "id") val exercise: ExerciseEntity
)

@Dao
interface RoutineDao {
    @Insert suspend fun insertRoutine(r: RoutineEntity): Long
    @Insert suspend fun insertDay(d: RoutineDayEntity): Long
    @Insert suspend fun insertItem(i: RoutineExerciseEntity): Long
    @Insert suspend fun insertExercise(e: ExerciseEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMuscles(m: List<ExerciseMuscleEntity>)
    @Update suspend fun updateItem(i: RoutineExerciseEntity)
    @Query("DELETE FROM routine_exercise WHERE id = :id") suspend fun deleteItem(id: Long)

    @Query("SELECT COUNT(*) FROM routine") suspend fun routineCount(): Int
    @Query("SELECT id FROM exercise WHERE slug = :slug") suspend fun exerciseIdBySlug(slug: String): Long?

    @Query("SELECT * FROM routine_day WHERE routineId = (SELECT id FROM routine WHERE isActive = 1 LIMIT 1) ORDER BY dayOfWeek")
    fun observeDays(): Flow<List<RoutineDayEntity>>

    @Query("SELECT * FROM routine_day WHERE id = :id")
    suspend fun getDay(id: Long): RoutineDayEntity?

    @Query("SELECT * FROM routine_day WHERE dayOfWeek = :dow AND routineId = (SELECT id FROM routine WHERE isActive = 1 LIMIT 1)")
    suspend fun dayForWeekday(dow: Int): RoutineDayEntity?

    @Query("SELECT * FROM routine_day WHERE id = :id")
    fun observeDay(id: Long): Flow<RoutineDayEntity?>

    @Query("SELECT * FROM routine_day WHERE dayOfWeek = :dow AND routineId = (SELECT id FROM routine WHERE isActive = 1 LIMIT 1)")
    fun observeDayOfWeek(dow: Int): Flow<RoutineDayEntity?>

    @Transaction
    @Query("SELECT * FROM routine_exercise WHERE dayId = :dayId ORDER BY position")
    fun observeItems(dayId: Long): Flow<List<RoutineExerciseWithExercise>>

    @Transaction
    @Query("SELECT * FROM routine_exercise WHERE dayId = :dayId ORDER BY position")
    suspend fun getItems(dayId: Long): List<RoutineExerciseWithExercise>

    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM routine_exercise WHERE dayId = :dayId")
    suspend fun nextPosition(dayId: Long): Int

    @Query("SELECT * FROM exercise ORDER BY name")
    fun observeExercises(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercise ORDER BY name")
    suspend fun allExercises(): List<ExerciseEntity>

    @Query("SELECT * FROM exercise WHERE id = :id")
    suspend fun getExercise(id: Long): ExerciseEntity?

    @Query("SELECT muscle FROM exercise_muscle WHERE exerciseId = :id AND role = 'SECONDARY'")
    suspend fun secondaryMuscles(id: Long): List<String>

    /** Swaps the order of two items in one transaction (used for move up/down). */
    @Transaction
    suspend fun swapPositions(a: RoutineExerciseEntity, b: RoutineExerciseEntity) {
        updateItem(a.copy(position = b.position))
        updateItem(b.copy(position = a.position))
    }
}

data class MuscleSets(val muscle: String, val sets: Int)
data class BestRow(val maxWeight: Double?, val maxE1rm: Double?)

@Dao
interface WorkoutDao {
    @Insert suspend fun insertSession(s: WorkoutSessionEntity): Long
    @Update suspend fun updateSession(s: WorkoutSessionEntity)
    @Query("SELECT * FROM workout_session WHERE id = :id") suspend fun getSession(id: Long): WorkoutSessionEntity?
    @Query("SELECT * FROM workout_session WHERE id = :id") fun observeSession(id: Long): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_session WHERE status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    fun observeActiveSession(): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_session WHERE status = 'ACTIVE' AND routineDayId = :dayId LIMIT 1")
    suspend fun activeSessionForDay(dayId: Long): WorkoutSessionEntity?

    @Query("DELETE FROM workout_session WHERE id = :id") suspend fun deleteSession(id: Long)

    @Insert suspend fun insertSet(s: WorkoutSetEntity): Long
    @Update suspend fun updateSet(s: WorkoutSetEntity)
    @Query("DELETE FROM workout_set WHERE id = :id") suspend fun deleteSet(id: Long)

    @Query("SELECT * FROM workout_set WHERE sessionId = :sessionId ORDER BY completedAt")
    fun observeSets(sessionId: Long): Flow<List<WorkoutSetEntity>>

    @Query("SELECT COUNT(*) FROM workout_set WHERE sessionId = :sessionId")
    suspend fun setCount(sessionId: Long): Int

    /** Working sets from the most recent earlier session that included this exercise (for auto-fill). */
    @Query(
        "SELECT * FROM workout_set WHERE exerciseId = :exerciseId AND isWarmup = 0 AND sessionId = " +
            "(SELECT sessionId FROM workout_set WHERE exerciseId = :exerciseId AND sessionId != :currentSession " +
            "ORDER BY completedAt DESC LIMIT 1) ORDER BY setIndex"
    )
    suspend fun lastSessionSets(exerciseId: Long, currentSession: Long): List<WorkoutSetEntity>

    @Query(
        "SELECT MAX(weightKg) AS maxWeight, MAX(CASE WHEN reps BETWEEN 1 AND 12 THEN weightKg * (1 + reps / 30.0) END) AS maxE1rm " +
            "FROM workout_set WHERE exerciseId = :exerciseId AND isWarmup = 0"
    )
    suspend fun best(exerciseId: Long): BestRow?

    @Query("SELECT * FROM workout_set WHERE exerciseId = :exerciseId AND isWarmup = 0 ORDER BY completedAt")
    fun observeExerciseHistory(exerciseId: Long): Flow<List<WorkoutSetEntity>>

    @Query("SELECT * FROM workout_session WHERE status = 'DONE' AND startedAt >= :from ORDER BY startedAt")
    fun observeDoneSessionsSince(from: Long): Flow<List<WorkoutSessionEntity>>

    @Query("SELECT COUNT(*) > 0 FROM workout_session WHERE status = 'DONE' AND startedAt >= :from")
    suspend fun observeDoneSessionsSinceBlocking(from: Long): Boolean

    @Query("SELECT * FROM workout_session WHERE status = 'DONE' ORDER BY startedAt")
    fun observeAllDoneSessions(): Flow<List<WorkoutSessionEntity>>

    @Query(
        "SELECT s.* FROM workout_set s JOIN exercise e ON e.id = s.exerciseId " +
            "WHERE e.repUnit = 'reps' AND s.isWarmup = 0 AND s.completedAt >= :from"
    )
    fun observeRepSetsSince(from: Long): Flow<List<WorkoutSetEntity>>

    @Query(
        "SELECT em.muscle AS muscle, COUNT(*) AS sets FROM workout_set s " +
            "JOIN exercise_muscle em ON em.exerciseId = s.exerciseId AND em.role = 'PRIMARY' " +
            "WHERE s.completedAt >= :from AND s.isWarmup = 0 GROUP BY em.muscle"
    )
    fun observeMuscleSetsSince(from: Long): Flow<List<MuscleSets>>

    @Query("SELECT DISTINCT primaryMuscle FROM exercise")
    suspend fun allPrimaryMuscles(): List<String>

    @Query("SELECT AVG(rpe) FROM workout_set WHERE sessionId = :sessionId AND rpe IS NOT NULL")
    suspend fun avgRpe(sessionId: Long): Double?

    @Query("SELECT * FROM workout_session ORDER BY startedAt") suspend fun allSessions(): List<WorkoutSessionEntity>
    @Query("SELECT * FROM workout_set ORDER BY completedAt") suspend fun allSets(): List<WorkoutSetEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreSessions(l: List<WorkoutSessionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreSets(l: List<WorkoutSetEntity>)
}

@Dao
interface NutritionDao {
    @Insert suspend fun insertFood(f: FoodEntity): Long
    @Update suspend fun updateFood(f: FoodEntity)
    @Query("SELECT * FROM food WHERE id = :id") suspend fun getFood(id: Long): FoodEntity?
    @Query("SELECT * FROM food WHERE barcode = :barcode LIMIT 1") suspend fun foodByBarcode(barcode: String): FoodEntity?
    @Query("SELECT * FROM food WHERE name LIKE '%' || :q || '%' OR brand LIKE '%' || :q || '%' ORDER BY isFavorite DESC, lastUsedAt DESC LIMIT 40")
    suspend fun searchFoods(q: String): List<FoodEntity>
    @Query("SELECT * FROM food WHERE isFavorite = 1 ORDER BY name") fun observeFavorites(): Flow<List<FoodEntity>>
    @Query("SELECT * FROM food WHERE lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC LIMIT 12") fun observeRecents(): Flow<List<FoodEntity>>
    @Query("UPDATE food SET isFavorite = :fav WHERE id = :id") suspend fun setFavorite(id: Long, fav: Boolean)

    @Insert suspend fun insertMeal(m: MealEntryEntity): Long
    @Query("DELETE FROM meal_entry WHERE id = :id") suspend fun deleteMeal(id: Long)
    @Query("SELECT * FROM meal_entry WHERE date = :date ORDER BY loggedAt") fun observeMeals(date: LocalDate): Flow<List<MealEntryEntity>>
    @Query("SELECT * FROM meal_entry WHERE date >= :from ORDER BY date") fun observeMealsSince(from: LocalDate): Flow<List<MealEntryEntity>>

    @Insert suspend fun insertWater(w: WaterEntryEntity): Long
    @Query("SELECT COALESCE(SUM(amountMl), 0) FROM water_entry WHERE date = :date") fun observeWater(date: LocalDate): Flow<Int>
    @Query("DELETE FROM water_entry WHERE id = (SELECT id FROM water_entry WHERE date = :date ORDER BY loggedAt DESC LIMIT 1)")
    suspend fun undoLastWater(date: LocalDate)

    @Query("SELECT * FROM food") suspend fun allFoods(): List<FoodEntity>
    @Query("SELECT * FROM meal_entry ORDER BY date") suspend fun allMeals(): List<MealEntryEntity>
    @Query("SELECT * FROM water_entry ORDER BY date") suspend fun allWater(): List<WaterEntryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreFoods(l: List<FoodEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreMeals(l: List<MealEntryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreWater(l: List<WaterEntryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreWeights(l: List<BodyWeightEntity>)
}
