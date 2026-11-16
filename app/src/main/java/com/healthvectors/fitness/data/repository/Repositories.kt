package com.healthvectors.fitness.data.repository

import androidx.room.withTransaction
import com.healthvectors.fitness.data.local.FitnessDatabase
import com.healthvectors.fitness.data.local.dao.*
import com.healthvectors.fitness.data.local.entity.*
import com.healthvectors.fitness.data.plan.PlanAssets
import com.healthvectors.fitness.domain.logic.*
import com.healthvectors.fitness.domain.model.*
import com.healthvectors.fitness.domain.plan.*
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
    suspend fun deleteWeight(id: Long) = dao.deleteWeight(id)
    suspend fun deleteMeasurement(id: Long) = dao.deleteMeasurement(id)
    suspend fun logMeasurement(type: String, cm: Double) =
        dao.insertMeasurement(BodyMeasurementEntity(date = LocalDate.now(), type = type, valueCm = cm))
}

@Singleton
class RoutineRepository @Inject constructor(
    private val db: FitnessDatabase,
    private val dao: RoutineDao,
    private val assets: PlanAssets
) {
    val days: Flow<List<RoutineDayEntity>> = dao.observeDays()
    val exercises: Flow<List<ExerciseEntity>> = dao.observeExercises()
    val routines: Flow<List<RoutineEntity>> = dao.observeRoutines()
    val activeRoutine: Flow<RoutineEntity?> = dao.observeActiveRoutine()

    fun day(id: Long) = dao.observeDay(id)
    fun dayOfWeek(dow: Int) = dao.observeDayOfWeek(dow)
    fun items(dayId: Long) = dao.observeItems(dayId)
    suspend fun getItems(dayId: Long) = dao.getItems(dayId)
    suspend fun update(item: RoutineExerciseEntity) = dao.updateItem(item)
    suspend fun remove(id: Long) = dao.deleteItem(id)
    suspend fun swap(a: RoutineExerciseEntity, b: RoutineExerciseEntity) = dao.swapPositions(a, b)
    suspend fun secondaryMuscles(exerciseId: Long) = dao.secondaryMuscles(exerciseId)
    suspend fun hasPlan() = dao.routineCount() > 0
    fun templates() = assets.templates()

    /** Adds built-in exercises that are missing (new installs and app updates); never touches existing rows. */
    suspend fun syncLibrary() {
        val have = dao.allSlugs().toSet()
        val missing = assets.library().filter { it.slug !in have }
        if (missing.isEmpty()) return
        db.withTransaction { missing.forEach { insertExercise(it, custom = false) } }
    }

    private suspend fun insertExercise(e: PlanExercise, custom: Boolean): Long {
        dao.exerciseIdBySlug(e.slug)?.let { return it }
        val id = dao.insertExercise(
            ExerciseEntity(
                slug = e.slug, name = e.name, primaryMuscle = e.primaryMuscle, equipment = e.equipment,
                instructions = e.instructions, progressionRule = e.rule, repUnit = e.repUnit, isUnilateral = e.unilateral,
                alternatives = e.alternatives.joinToString("|"), isCustom = custom, incrementKg = e.incrementKg
            )
        )
        dao.insertMuscles(
            listOf(ExerciseMuscleEntity(id, e.primaryMuscle, "PRIMARY")) +
                e.secondaryMuscles.filter { it != e.primaryMuscle }.distinct().map { ExerciseMuscleEntity(id, it, "SECONDARY") }
        )
        return id
    }

    /**
     * Creates a routine from a plan (template or imported file). Exercises already on the device are
     * matched by slug so history carries over; unknown ones are added from the file's definitions.
     */
    suspend fun createFromPlan(plan: Plan, activate: Boolean = true, name: String = plan.name): Long {
        syncLibrary() // templates rely on built-in exercises; make sure they exist even on a first run
        return insertPlan(plan, activate, name)
    }

    private suspend fun insertPlan(plan: Plan, activate: Boolean, name: String): Long = db.withTransaction {
        val known = dao.allSlugs().toSet()
        val missing = PlanCodec.missingExercises(plan, known)
        if (missing.isNotEmpty()) throw PlanFormatException("The plan uses exercises it doesn't define: ${missing.joinToString()}")
        val defs = plan.exercises.associateBy { it.slug }
        val ids = plan.referencedSlugs.associateWith { slug ->
            dao.exerciseIdBySlug(slug) ?: insertExercise(defs.getValue(slug), custom = true)
        }
        val routineId = dao.insertRoutine(RoutineEntity(name = name, description = plan.description, isActive = false))
        plan.fullWeek().forEach { d ->
            val dayId = dao.insertDay(
                RoutineDayEntity(routineId = routineId, dayOfWeek = d.weekday, title = d.title, type = d.type, estimatedMinutes = d.minutes)
            )
            d.items.forEachIndexed { i, it ->
                dao.insertItem(
                    RoutineExerciseEntity(
                        dayId = dayId, exerciseId = ids.getValue(it.slug), position = i + 1, targetSets = it.sets,
                        repMin = it.repMin, repMax = it.repMax, restMinSec = it.restMinSec, restMaxSec = it.restMaxSec,
                        rirMin = it.rirMin, rirMax = it.rirMax, perSide = it.perSide, notes = it.notes
                    )
                )
            }
        }
        if (activate) dao.activate(routineId)
        routineId
    }

    /** Parses a shared plan file and adds it as the active plan. */
    suspend fun importPlan(text: String): Long = createFromPlan(PlanCodec.decode(text))

    /** The routine as a self-contained plan, including definitions of every exercise it uses. */
    suspend fun exportPlan(routineId: Long): Plan {
        val r = dao.getRoutine(routineId) ?: throw PlanFormatException("Plan not found")
        val days = dao.daysOf(routineId)
        val itemsByDay = days.associate { it.id to dao.getItems(it.id) }
        val exercises = itemsByDay.values.flatten().map { it.exercise }.distinctBy { it.id }.map { e ->
            PlanExercise(
                slug = e.slug, name = e.name, primaryMuscle = e.primaryMuscle,
                secondaryMuscles = dao.musclesOf(e.id).filter { it.role == "SECONDARY" }.map { it.muscle },
                equipment = e.equipment, rule = e.progressionRule, repUnit = e.repUnit, unilateral = e.isUnilateral,
                alternatives = e.alternatives.split('|').filter { it.isNotBlank() }, instructions = e.instructions,
                incrementKg = e.incrementKg
            )
        }
        return Plan(
            name = r.name, description = r.description, exercises = exercises,
            days = days.filter { it.type != DayType.REST || itemsByDay[it.id].orEmpty().isNotEmpty() || it.title != "Rest" }.map { d ->
                PlanDay(d.dayOfWeek, d.title, d.type, d.estimatedMinutes, itemsByDay[d.id].orEmpty().map {
                    val i = it.item
                    PlanItem(it.exercise.slug, i.targetSets, i.repMin, i.repMax, i.restMinSec, i.restMaxSec, i.rirMin, i.rirMax, i.perSide, i.notes)
                })
            }.ifEmpty { listOf(PlanDay(1, "Rest", DayType.REST, 0, emptyList())) }
        )
    }

    suspend fun activate(routineId: Long) = dao.activate(routineId)
    suspend fun rename(routineId: Long, name: String) = dao.rename(routineId, name.trim())
    suspend fun duplicate(routineId: Long): Long {
        val p = exportPlan(routineId)
        return createFromPlan(p, activate = false, name = p.name + " (copy)")
    }

    /** Deletes a plan that is not active. Logged workouts are kept. */
    suspend fun delete(routineId: Long): Boolean {
        val r = dao.getRoutine(routineId) ?: return false
        if (r.isActive) return false
        dao.deleteRoutine(routineId)
        return true
    }

    suspend fun updateDay(day: RoutineDayEntity) = dao.updateDay(day)

    /** Moves a day to another weekday by trading places with the day already there. */
    suspend fun moveDay(day: RoutineDayEntity, weekday: Int) {
        if (weekday == day.dayOfWeek) return
        val other = dao.daysOf(day.routineId).firstOrNull { it.dayOfWeek == weekday }
        if (other == null) dao.updateDay(day.copy(dayOfWeek = weekday)) else dao.swapDays(day, other)
    }

    suspend fun replaceExercise(item: RoutineExerciseEntity, exercise: ExerciseEntity) =
        dao.updateItem(item.copy(exerciseId = exercise.id, perSide = exercise.isUnilateral))

    suspend fun setIncrement(exercise: ExerciseEntity, kg: Double?) = dao.updateExercise(exercise.copy(incrementKg = kg))

    /** Adds a library exercise to a day with sensible defaults for its progression rule. */
    suspend fun addToDay(dayId: Long, exercise: ExerciseEntity) {
        val isolation = exercise.progressionRule == ProgressionRule.ISOLATION_HIGH_REP_PROGRESSION
        val (min, max) = when {
            exercise.repUnit == "minutes" -> 20 to 40
            exercise.repUnit != "reps" -> 30 to 60
            isolation -> 10 to 20
            else -> 8 to 12
        }
        val timed = exercise.repUnit != "reps"
        dao.insertItem(
            RoutineExerciseEntity(
                dayId = dayId, exerciseId = exercise.id, position = dao.nextPosition(dayId),
                targetSets = if (exercise.repUnit == "minutes") 1 else 3, repMin = min, repMax = max,
                restMinSec = when { exercise.repUnit == "minutes" -> 0; isolation || timed -> 60; else -> 120 },
                restMaxSec = when { exercise.repUnit == "minutes" -> 0; isolation || timed -> 90; else -> 180 },
                rirMin = if (isolation || timed) 0 else 1, rirMax = if (timed) 0 else 2, perSide = exercise.isUnilateral
            )
        )
    }

    suspend fun createCustomExercise(
        name: String, muscle: String, equipment: String,
        rule: ProgressionRule = ProgressionRule.COMPOUND_DOUBLE_PROGRESSION, unilateral: Boolean = false
    ): ExerciseEntity {
        val unit = when (rule) {
            ProgressionRule.TIMED_CORE -> "seconds"
            ProgressionRule.DURATION_DISTANCE -> "minutes"
            else -> "reps"
        }
        val e = ExerciseEntity(
            slug = "custom_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') + "_" + System.currentTimeMillis(),
            name = name, primaryMuscle = muscle, equipment = equipment, instructions = "",
            progressionRule = rule, repUnit = unit, isUnilateral = unilateral, isCustom = true
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
    suspend fun planPrimaryMuscles() = dao.planPrimaryMuscles()
    val chartExercises = dao.observeChartExercises()
    val history = dao.observeHistory()
    val lastDoneDay = dao.observeLastDoneDay()
    fun sessionSets(sessionId: Long) = dao.observeSessionSets(sessionId)
    suspend fun deleteSession(sessionId: Long) = dao.deleteSession(sessionId)
    suspend fun deleteSet(setId: Long) = dao.deleteSet(setId)

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
        val sets = dao.setsForSession(sessionId)
        return SessionSummary(minutes.toInt(), sets.size, dao.sessionVolume(sessionId), sets.count { it.isPr }, kcal)
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
