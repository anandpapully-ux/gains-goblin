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

    @Query("DELETE FROM body_measurement WHERE id = :id")
    suspend fun deleteMeasurement(id: Long)
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

    @Query("SELECT * FROM routine ORDER BY id") fun observeRoutines(): Flow<List<RoutineEntity>>
    @Query("SELECT * FROM routine WHERE isActive = 1 LIMIT 1") fun observeActiveRoutine(): Flow<RoutineEntity?>
    @Query("SELECT * FROM routine WHERE id = :id") suspend fun getRoutine(id: Long): RoutineEntity?
    /** Makes exactly one routine active. */
    @Query("UPDATE routine SET isActive = (id = :id)") suspend fun activate(id: Long)
    @Query("UPDATE routine SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("DELETE FROM routine WHERE id = :id") suspend fun deleteRoutine(id: Long)
    @Query("SELECT * FROM routine_day WHERE routineId = :routineId ORDER BY dayOfWeek") suspend fun daysOf(routineId: Long): List<RoutineDayEntity>
    @Update suspend fun updateDay(d: RoutineDayEntity)
    @Update suspend fun updateExercise(e: ExerciseEntity)
    @Query("SELECT * FROM exercise WHERE slug = :slug") suspend fun exerciseBySlug(slug: String): ExerciseEntity?
    @Query("SELECT slug FROM exercise") suspend fun allSlugs(): List<String>
    @Query("SELECT * FROM exercise_muscle WHERE exerciseId = :id") suspend fun musclesOf(id: Long): List<ExerciseMuscleEntity>

    /** Exchanges the weekday (or rotation position) of two days of the same plan. */
    @Transaction
    suspend fun swapDays(a: RoutineDayEntity, b: RoutineDayEntity) {
        updateDay(a.copy(dayOfWeek = b.dayOfWeek))
        updateDay(b.copy(dayOfWeek = a.dayOfWeek))
    }

    /** Swaps the order of two items in one transaction (used for move up/down). */
    @Transaction
    suspend fun swapPositions(a: RoutineExerciseEntity, b: RoutineExerciseEntity) {
        updateItem(a.copy(position = b.position))
        updateItem(b.copy(position = a.position))
    }
}

data class MuscleSets(val muscle: String, val sets: Int)
data class SessionRow(
    val id: Long, val startedAt: Long, val endedAt: Long?, val caloriesBurned: Double?, val dayTitle: String?,
    val setCount: Int, val volumeKg: Double, val prCount: Int
)
data class SetWithName(@Embedded val set: WorkoutSetEntity, val exerciseName: String, val repUnit: String)
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

    /** Primary muscles of the exercises in the active plan (what the heatmap should cover). */
    @Query(
        "SELECT DISTINCT e.primaryMuscle FROM exercise e JOIN routine_exercise ri ON ri.exerciseId = e.id " +
            "JOIN routine_day d ON d.id = ri.dayId JOIN routine r ON r.id = d.routineId " +
            "WHERE r.isActive = 1 AND e.repUnit = 'reps'"
    )
    suspend fun planPrimaryMuscles(): List<String>

    /** Rep-based exercises that are in the active plan or have logged history (for the strength chart). */
    @Query(
        "SELECT * FROM exercise WHERE repUnit = 'reps' AND (id IN (SELECT DISTINCT exerciseId FROM workout_set) OR id IN " +
            "(SELECT ri.exerciseId FROM routine_exercise ri JOIN routine_day d ON d.id = ri.dayId JOIN routine r ON r.id = d.routineId WHERE r.isActive = 1)) " +
            "ORDER BY name"
    )
    fun observeChartExercises(): Flow<List<ExerciseEntity>>

    @Query(
        "SELECT s.id AS id, s.startedAt AS startedAt, s.endedAt AS endedAt, s.caloriesBurned AS caloriesBurned, d.title AS dayTitle, " +
            "(SELECT COUNT(*) FROM workout_set w WHERE w.sessionId = s.id) AS setCount, " +
            "(SELECT COALESCE(SUM(w.weightKg * w.reps), 0) FROM workout_set w JOIN exercise e ON e.id = w.exerciseId " +
            "WHERE w.sessionId = s.id AND w.isWarmup = 0 AND e.repUnit = 'reps') AS volumeKg, " +
            "(SELECT COUNT(*) FROM workout_set w WHERE w.sessionId = s.id AND w.isPr = 1) AS prCount " +
            "FROM workout_session s LEFT JOIN routine_day d ON d.id = s.routineDayId WHERE s.status = 'DONE' ORDER BY s.startedAt DESC"
    )
    fun observeHistory(): Flow<List<SessionRow>>

    @Query(
        "SELECT w.*, e.name AS exerciseName, e.repUnit AS repUnit FROM workout_set w JOIN exercise e ON e.id = w.exerciseId " +
            "WHERE w.sessionId = :sessionId ORDER BY w.completedAt"
    )
    fun observeSessionSets(sessionId: Long): Flow<List<SetWithName>>

    @Query("SELECT * FROM workout_set WHERE sessionId = :sessionId") suspend fun setsForSession(sessionId: Long): List<WorkoutSetEntity>

    @Query(
        "SELECT COALESCE(SUM(w.weightKg * w.reps), 0) FROM workout_set w JOIN exercise e ON e.id = w.exerciseId " +
            "WHERE w.sessionId = :sessionId AND w.isWarmup = 0 AND e.repUnit = 'reps'"
    )
    suspend fun sessionVolume(sessionId: Long): Double

    /** Day of the most recent finished session in the active plan (drives rotation mode). */
    @Query(
        "SELECT d.* FROM workout_session s JOIN routine_day d ON d.id = s.routineDayId " +
            "WHERE s.status = 'DONE' AND d.routineId = (SELECT id FROM routine WHERE isActive = 1 LIMIT 1) " +
            "ORDER BY s.startedAt DESC LIMIT 1"
    )
    fun observeLastDoneDay(): Flow<RoutineDayEntity?>

    @Query("SELECT d.* FROM workout_session s JOIN routine_day d ON d.id = s.routineDayId WHERE s.id = :sessionId")
    suspend fun dayOfSession(sessionId: Long): RoutineDayEntity?

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

/** Whole-database backup and restore. Deletes run children-first so foreign keys never dangle. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM user_profile") suspend fun profiles(): List<UserProfileEntity>
    @Query("SELECT * FROM body_measurement ORDER BY date") suspend fun measurements(): List<BodyMeasurementEntity>
    @Query("SELECT * FROM exercise") suspend fun exercises(): List<ExerciseEntity>
    @Query("SELECT * FROM exercise_muscle") suspend fun exerciseMuscles(): List<ExerciseMuscleEntity>
    @Query("SELECT * FROM routine") suspend fun routines(): List<RoutineEntity>
    @Query("SELECT * FROM routine_day") suspend fun days(): List<RoutineDayEntity>
    @Query("SELECT * FROM routine_exercise") suspend fun items(): List<RoutineExerciseEntity>
    @Query("SELECT id FROM exercise") suspend fun exerciseIds(): List<Long>
    @Query("SELECT id FROM food") suspend fun foodIds(): List<Long>

    @Query("DELETE FROM workout_set") suspend fun clearSets()
    @Query("DELETE FROM workout_session") suspend fun clearSessions()
    @Query("DELETE FROM routine_exercise") suspend fun clearItems()
    @Query("DELETE FROM routine_day") suspend fun clearDays()
    @Query("DELETE FROM routine") suspend fun clearRoutines()
    @Query("DELETE FROM exercise_muscle") suspend fun clearMuscles()
    @Query("DELETE FROM exercise") suspend fun clearExercises()
    @Query("DELETE FROM meal_entry") suspend fun clearMeals()
    @Query("DELETE FROM food") suspend fun clearFoods()
    @Query("DELETE FROM water_entry") suspend fun clearWater()
    @Query("DELETE FROM body_weight_entry") suspend fun clearWeights()
    @Query("DELETE FROM body_measurement") suspend fun clearMeasurements()
    @Query("DELETE FROM user_profile") suspend fun clearProfile()

    @Insert suspend fun insertProfiles(l: List<UserProfileEntity>)
    @Insert suspend fun insertMeasurements(l: List<BodyMeasurementEntity>)
    @Insert suspend fun insertExercises(l: List<ExerciseEntity>)
    @Insert suspend fun insertMuscles(l: List<ExerciseMuscleEntity>)
    @Insert suspend fun insertRoutines(l: List<RoutineEntity>)
    @Insert suspend fun insertDays(l: List<RoutineDayEntity>)
    @Insert suspend fun insertItems(l: List<RoutineExerciseEntity>)
    @Insert suspend fun insertSessions(l: List<WorkoutSessionEntity>)
    @Insert suspend fun insertSets(l: List<WorkoutSetEntity>)
    @Insert suspend fun insertFoods(l: List<FoodEntity>)
    @Insert suspend fun insertMeals(l: List<MealEntryEntity>)
    @Insert suspend fun insertWater(l: List<WaterEntryEntity>)
    @Insert suspend fun insertWeights(l: List<BodyWeightEntity>)

    suspend fun clearAll() {
        clearSets(); clearSessions(); clearItems(); clearDays(); clearRoutines(); clearMuscles(); clearExercises()
        clearMeals(); clearFoods(); clearWater(); clearWeights(); clearMeasurements(); clearProfile()
    }
}
