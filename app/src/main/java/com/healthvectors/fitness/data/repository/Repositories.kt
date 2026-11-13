package com.healthvectors.fitness.data.repository

import androidx.room.withTransaction
import com.healthvectors.fitness.data.local.FitnessDatabase
import com.healthvectors.fitness.data.local.dao.*
import com.healthvectors.fitness.data.local.entity.*
import com.healthvectors.fitness.data.local.seed.PlanSeeder
import com.healthvectors.fitness.domain.logic.*
import com.healthvectors.fitness.domain.model.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(private val dao: ProfileDao) {
    val profile: Flow<UserProfileEntity?> = dao.observe()
    val weights: Flow<List<BodyWeightEntity>> = dao.observeWeights()
    val latestWeight: Flow<BodyWeightEntity?> = dao.observeLatestWeight()

    suspend fun saveProfile(p: UserProfileEntity) = dao.upsert(p)
    suspend fun getProfile() = dao.get()
    suspend fun logWeight(kg: Double, date: LocalDate = LocalDate.now()) =
        dao.upsertWeight(BodyWeightEntity(date = date, weightKg = kg))
    suspend fun latestWeightKg(): Double? = dao.latestWeight()?.weightKg
    fun measurements(type: String) = dao.observeMeasurements(type)
    suspend fun logMeasurement(type: String, cm: Double) =
        dao.insertMeasurement(BodyMeasurementEntity(date = LocalDate.now(), type = type, valueCm = cm))
}

@Singleton
class RoutineRepository @Inject constructor(
    private val dao: RoutineDao,
    private val seeder: PlanSeeder
) {
    val days: Flow<List<RoutineDayEntity>> = dao.observeDays()
    val exercises: Flow<List<ExerciseEntity>> = dao.observeExercises()

    suspend fun ensureSeeded() = seeder.seedIfEmpty()
    fun day(id: Long) = dao.observeDay(id)
    fun dayOfWeek(dow: Int) = dao.observeDayOfWeek(dow)
    fun items(dayId: Long) = dao.observeItems(dayId)
    suspend fun getItems(dayId: Long) = dao.getItems(dayId)
    suspend fun update(item: RoutineExerciseEntity) = dao.updateItem(item)
    suspend fun remove(id: Long) = dao.deleteItem(id)
    suspend fun swap(a: RoutineExerciseEntity, b: RoutineExerciseEntity) = dao.swapPositions(a, b)
    suspend fun secondaryMuscles(exerciseId: Long) = dao.secondaryMuscles(exerciseId)

    /** Adds a library exercise to a day with sensible defaults for its progression rule. */
    suspend fun addToDay(dayId: Long, exercise: ExerciseEntity) {
        val isolation = exercise.progressionRule == ProgressionRule.ISOLATION_HIGH_REP_PROGRESSION
        val (min, max) = when {
            exercise.repUnit != "reps" -> 30 to 60
            isolation -> 10 to 20
            else -> 8 to 12
        }
        dao.insertItem(
            RoutineExerciseEntity(
                dayId = dayId, exerciseId = exercise.id, position = dao.nextPosition(dayId),
                targetSets = 3, repMin = min, repMax = max,
                restMinSec = if (isolation) 60 else 120, restMaxSec = if (isolation) 90 else 120,
                rirMin = if (isolation) 0 else 1, rirMax = 2, perSide = exercise.isUnilateral
            )
        )
    }

    suspend fun createCustomExercise(name: String, muscle: String, equipment: String): ExerciseEntity {
        val e = ExerciseEntity(
            slug = "custom_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_") + "_" + System.currentTimeMillis(),
            name = name, primaryMuscle = muscle, equipment = equipment, instructions = "",
            progressionRule = ProgressionRule.COMPOUND_DOUBLE_PROGRESSION, repUnit = "reps",
            isUnilateral = false, isCustom = true
        )
        val id = dao.insertExercise(e)
        dao.insertMuscles(listOf(ExerciseMuscleEntity(id, muscle, "PRIMARY")))
        return e.copy(id = id)
    }
}

data class LogResult(val setId: Long, val prs: List<PrType>)
data class SessionSummary(
    val durationMin: Int, val sets: Int, val volumeKg: Double, val prCount: Int, val kcal: Double
)

@Singleton
class WorkoutRepository @Inject constructor(
    private val db: FitnessDatabase,
    private val dao: WorkoutDao,
    private val routineDao: RoutineDao
) {
    val activeSession = dao.observeActiveSession()
    fun sets(sessionId: Long) = dao.observeSets(sessionId)
    fun session(id: Long) = dao.observeSession(id)
    fun doneSessions() = dao.observeAllDoneSessions()
    fun doneSessionsSince(from: Long) = dao.observeDoneSessionsSince(from)
    fun repSetsSince(from: Long) = dao.observeRepSetsSince(from)
    fun muscleSetsSince(from: Long) = dao.observeMuscleSetsSince(from)
    fun exerciseHistory(id: Long) = dao.observeExerciseHistory(id)
    suspend fun lastSessionSets(exerciseId: Long, sessionId: Long) = dao.lastSessionSets(exerciseId, sessionId)
    suspend fun allPrimaryMuscles() = dao.allPrimaryMuscles()

    suspend fun startOrResume(dayId: Long): Long {
        dao.activeSessionForDay(dayId)?.let { return it.id }
        return dao.insertSession(WorkoutSessionEntity(routineDayId = dayId, startedAt = System.currentTimeMillis()))
    }

    suspend fun logSet(
        sessionId: Long, exerciseId: Long, setIndex: Int, weightKg: Double, reps: Int, rpe: Double?, warmup: Boolean = false
    ): LogResult {
        val exercise = routineDao.getExercise(exerciseId)
        var prs = emptyList<PrType>()
        if (!warmup && exercise?.repUnit == "reps") {
            val best = dao.best(exerciseId)?.let { ExerciseBest(it.maxWeight, it.maxE1rm) }
            prs = PrDetector.detect(weightKg, reps, best)
        }
        val id = dao.insertSet(
            WorkoutSetEntity(
                sessionId = sessionId, exerciseId = exerciseId, setIndex = setIndex, weightKg = weightKg,
                reps = reps, rpe = rpe, isWarmup = warmup, isPr = prs.isNotEmpty(),
                completedAt = System.currentTimeMillis()
            )
        )
        return LogResult(id, prs)
    }

    suspend fun undoSet(setId: Long) = dao.deleteSet(setId)

    /** Closes the session; an empty session is discarded. Returns null when discarded. */
    suspend fun finish(sessionId: Long, weightKg: Double, met: Double): SessionSummary? {
        val s = dao.getSession(sessionId) ?: return null
        if (dao.setCount(sessionId) == 0) { dao.deleteSession(sessionId); return null }
        val end = System.currentTimeMillis()
        val minutes = ((end - s.startedAt) / 60000.0).coerceAtLeast(1.0)
        val kcal = CalorieCalculator.workoutBurn(met, weightKg, minutes)
        dao.updateSession(s.copy(endedAt = end, status = SessionStatus.DONE, caloriesBurned = kcal))
        val sets = db.workoutDao().allSets().filter { it.sessionId == sessionId }
        val repExercises = routineDao.allExercises().filter { it.repUnit == "reps" }.map { it.id }.toSet()
        val volume = sets.filter { !it.isWarmup && it.exerciseId in repExercises }.sumOf { it.weightKg * it.reps }
        return SessionSummary(minutes.toInt(), sets.size, volume, sets.count { it.isPr }, kcal)
    }

    suspend fun discard(sessionId: Long) = dao.deleteSession(sessionId)
    suspend fun avgRpe(sessionId: Long) = dao.avgRpe(sessionId)
}

@Singleton
class NutritionRepository @Inject constructor(private val dao: NutritionDao) {
    fun meals(date: LocalDate) = dao.observeMeals(date)
    fun mealsSince(from: LocalDate) = dao.observeMealsSince(from)
    fun water(date: LocalDate) = dao.observeWater(date)
    val favorites = dao.observeFavorites()
    val recents = dao.observeRecents()

    suspend fun search(q: String) = dao.searchFoods(q)
    suspend fun addWater(ml: Int, date: LocalDate = LocalDate.now()) =
        dao.insertWater(WaterEntryEntity(date = date, amountMl = ml, loggedAt = System.currentTimeMillis()))
    suspend fun undoWater(date: LocalDate = LocalDate.now()) = dao.undoLastWater(date)
    suspend fun setFavorite(id: Long, fav: Boolean) = dao.setFavorite(id, fav)
    suspend fun deleteMeal(id: Long) = dao.deleteMeal(id)

    /** Saves a food (deduplicated by barcode) and returns its row id. */
    suspend fun saveFood(f: FoodEntity): Long {
        f.barcode?.let { code -> dao.foodByBarcode(code)?.let { return it.id } }
        return dao.insertFood(f)
    }

    suspend fun logMeal(foodId: Long, servings: Double, type: MealType, date: LocalDate) {
        val f = dao.getFood(foodId) ?: return
        val now = System.currentTimeMillis()
        dao.insertMeal(
            MealEntryEntity(
                date = date, mealType = type, foodId = f.id, foodName = f.name, servings = servings,
                kcal = f.kcal * servings, protein = f.protein * servings, carbs = f.carbs * servings,
                fat = f.fat * servings, loggedAt = now
            )
        )
        dao.updateFood(f.copy(lastUsedAt = now))
    }
}
