package com.healthvectors.fitness.data.repository

import androidx.room.withTransaction
import com.healthvectors.fitness.data.local.FitnessDatabase
import com.healthvectors.fitness.data.local.entity.*
import com.healthvectors.fitness.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class ImportResult(val records: Int, val fullRestore: Boolean, val skipped: Int = 0)

/**
 * JSON backup/restore and CSV export.
 *
 * Version 2 backups hold everything (profile, plans, exercise library, workouts, food, body data) and
 * restore by replacing the device's data. Version 1 files (from 1.0.x) only held logs; they are still
 * merged in, skipping rows that point at exercises or foods that do not exist.
 */
@Singleton
class DataPorter @Inject constructor(private val db: FitnessDatabase) {

    suspend fun exportJson(): String {
        val w = db.workoutDao(); val n = db.nutritionDao(); val p = db.profileDao(); val b = db.backupDao()
        return JSONObject().apply {
            put("app", "gains-goblin")
            put("version", 2)
            put("exportedAt", System.currentTimeMillis())
            put("profile", arr(b.profiles()) {
                JSONObject().put("heightCm", it.heightCm).put("birthDate", it.birthDate.toString()).put("sex", it.sex.name)
                    .put("activityLevel", it.activityLevel.name).put("goal", it.goal.name).put("experience", it.experience.name)
                    .put("unitSystem", it.unitSystem.name).put("weeklyRatePct", it.weeklyRatePct).put("createdAt", it.createdAt)
                    .put("customKcal", it.customKcal.orNull()).put("customProteinG", it.customProteinG.orNull())
                    .put("customFatG", it.customFatG.orNull())
            })
            put("weights", arr(p.allWeights()) {
                JSONObject().put("id", it.id).put("date", it.date.toString()).put("weightKg", it.weightKg)
            })
            put("measurements", arr(b.measurements()) {
                JSONObject().put("id", it.id).put("date", it.date.toString()).put("type", it.type).put("valueCm", it.valueCm)
            })
            put("exercises", arr(b.exercises()) {
                JSONObject().put("id", it.id).put("slug", it.slug).put("name", it.name).put("primaryMuscle", it.primaryMuscle)
                    .put("equipment", it.equipment).put("instructions", it.instructions).put("progressionRule", it.progressionRule.name)
                    .put("repUnit", it.repUnit).put("isUnilateral", it.isUnilateral).put("alternatives", it.alternatives)
                    .put("isCustom", it.isCustom).put("incrementKg", it.incrementKg.orNull())
            })
            put("exerciseMuscles", arr(b.exerciseMuscles()) {
                JSONObject().put("exerciseId", it.exerciseId).put("muscle", it.muscle).put("role", it.role)
            })
            put("routines", arr(b.routines()) {
                JSONObject().put("id", it.id).put("name", it.name).put("description", it.description).put("isActive", it.isActive)
            })
            put("days", arr(b.days()) {
                JSONObject().put("id", it.id).put("routineId", it.routineId).put("dayOfWeek", it.dayOfWeek).put("title", it.title)
                    .put("type", it.type.name).put("estimatedMinutes", it.estimatedMinutes)
            })
            put("items", arr(b.items()) {
                JSONObject().put("id", it.id).put("dayId", it.dayId).put("exerciseId", it.exerciseId).put("position", it.position)
                    .put("targetSets", it.targetSets).put("repMin", it.repMin).put("repMax", it.repMax)
                    .put("restMinSec", it.restMinSec).put("restMaxSec", it.restMaxSec).put("rirMin", it.rirMin)
                    .put("rirMax", it.rirMax).put("perSide", it.perSide).put("notes", it.notes)
            })
            put("sessions", arr(w.allSessions()) {
                JSONObject().put("id", it.id).put("routineDayId", it.routineDayId.orNull())
                    .put("startedAt", it.startedAt).put("endedAt", it.endedAt.orNull())
                    .put("status", it.status.name).put("notes", it.notes).put("caloriesBurned", it.caloriesBurned.orNull())
            })
            put("sets", arr(w.allSets()) {
                JSONObject().put("id", it.id).put("sessionId", it.sessionId).put("exerciseId", it.exerciseId)
                    .put("setIndex", it.setIndex).put("weightKg", it.weightKg).put("reps", it.reps)
                    .put("rpe", it.rpe.orNull()).put("isWarmup", it.isWarmup)
                    .put("isPr", it.isPr).put("completedAt", it.completedAt)
            })
            put("foods", arr(n.allFoods()) {
                JSONObject().put("id", it.id).put("barcode", it.barcode.orNull()).put("name", it.name)
                    .put("brand", it.brand.orNull()).put("servingSize", it.servingSize)
                    .put("servingUnit", it.servingUnit).put("kcal", it.kcal).put("protein", it.protein)
                    .put("carbs", it.carbs).put("fat", it.fat).put("source", it.source.name)
                    .put("isFavorite", it.isFavorite).put("lastUsedAt", it.lastUsedAt.orNull())
            })
            put("meals", arr(n.allMeals()) {
                JSONObject().put("id", it.id).put("date", it.date.toString()).put("mealType", it.mealType.name)
                    .put("foodId", it.foodId).put("foodName", it.foodName).put("servings", it.servings)
                    .put("kcal", it.kcal).put("protein", it.protein).put("carbs", it.carbs).put("fat", it.fat)
                    .put("loggedAt", it.loggedAt)
            })
            put("water", arr(n.allWater()) {
                JSONObject().put("id", it.id).put("date", it.date.toString())
                    .put("amountMl", it.amountMl).put("loggedAt", it.loggedAt)
            })
        }.toString(2)
    }

    /** True when [text] is a full (version 2+) backup, which replaces all data on restore. */
    fun isFullBackup(text: String): Boolean = runCatching { JSONObject(text).optInt("version", 1) >= 2 }.getOrDefault(false)

    suspend fun importJson(text: String): ImportResult {
        val j = JSONObject(text)
        val version = j.optInt("version", 1)
        require(version <= 2) { "This backup was made by a newer version of the app" }
        return if (version >= 2) restoreFull(j) else mergeV1(j)
    }

    private suspend fun restoreFull(j: JSONObject): ImportResult {
        // Parse everything before touching the database so a bad file changes nothing.
        val profile = j.optJSONArray("profile").objs().map {
            UserProfileEntity(
                id = 1, heightCm = it.getDouble("heightCm"), birthDate = LocalDate.parse(it.getString("birthDate")),
                sex = Sex.valueOf(it.getString("sex")), activityLevel = ActivityLevel.valueOf(it.getString("activityLevel")),
                goal = Goal.valueOf(it.getString("goal")), experience = Experience.valueOf(it.getString("experience")),
                unitSystem = UnitSystem.valueOf(it.getString("unitSystem")), weeklyRatePct = it.getDouble("weeklyRatePct"),
                createdAt = it.getLong("createdAt"), customKcal = it.intOrNull("customKcal"),
                customProteinG = it.intOrNull("customProteinG"), customFatG = it.intOrNull("customFatG")
            )
        }
        val weights = parseWeights(j)
        val measurements = j.optJSONArray("measurements").objs().map {
            BodyMeasurementEntity(it.getLong("id"), LocalDate.parse(it.getString("date")), it.getString("type"), it.getDouble("valueCm"))
        }
        val exercises = j.optJSONArray("exercises").objs().map {
            ExerciseEntity(
                id = it.getLong("id"), slug = it.getString("slug"), name = it.getString("name"),
                primaryMuscle = it.getString("primaryMuscle"), equipment = it.optString("equipment"),
                instructions = it.optString("instructions"), progressionRule = ProgressionRule.valueOf(it.getString("progressionRule")),
                repUnit = it.getString("repUnit"), isUnilateral = it.optBoolean("isUnilateral"),
                alternatives = it.optString("alternatives"), isCustom = it.optBoolean("isCustom"),
                incrementKg = it.doubleOrNull("incrementKg")
            )
        }
        val muscles = j.optJSONArray("exerciseMuscles").objs().map {
            ExerciseMuscleEntity(it.getLong("exerciseId"), it.getString("muscle"), it.getString("role"))
        }
        val routines = j.optJSONArray("routines").objs().map {
            RoutineEntity(it.getLong("id"), it.getString("name"), it.optString("description"), it.optBoolean("isActive"))
        }
        val days = j.optJSONArray("days").objs().map {
            RoutineDayEntity(
                it.getLong("id"), it.getLong("routineId"), it.getInt("dayOfWeek"), it.getString("title"),
                DayType.valueOf(it.getString("type")), it.optInt("estimatedMinutes")
            )
        }
        val items = j.optJSONArray("items").objs().map {
            RoutineExerciseEntity(
                it.getLong("id"), it.getLong("dayId"), it.getLong("exerciseId"), it.getInt("position"), it.getInt("targetSets"),
                it.getInt("repMin"), it.getInt("repMax"), it.getInt("restMinSec"), it.getInt("restMaxSec"),
                it.getInt("rirMin"), it.getInt("rirMax"), it.optBoolean("perSide"), it.optString("notes")
            )
        }
        val sessions = parseSessions(j)
        val sets = parseSets(j)
        val foods = parseFoods(j)
        val meals = parseMeals(j)
        val water = parseWater(j)
        db.withTransaction {
            val b = db.backupDao()
            b.clearAll()
            b.insertProfiles(profile); b.insertWeights(weights); b.insertMeasurements(measurements)
            b.insertExercises(exercises); b.insertMuscles(muscles)
            b.insertRoutines(routines); b.insertDays(days); b.insertItems(items)
            b.insertSessions(sessions); b.insertSets(sets)
            b.insertFoods(foods); b.insertMeals(meals); b.insertWater(water)
        }
        val total = profile.size + weights.size + measurements.size + exercises.size + routines.size + days.size +
            items.size + sessions.size + sets.size + foods.size + meals.size + water.size
        return ImportResult(total, fullRestore = true)
    }

    /** Version 1 files: merge by id, skipping rows whose exercise or food is unknown on this device. */
    private suspend fun mergeV1(j: JSONObject): ImportResult {
        var count = 0
        var skipped = 0
        db.withTransaction {
            val n = db.nutritionDao(); val w = db.workoutDao(); val b = db.backupDao()
            val foods = parseFoods(j)
            n.restoreFoods(foods)
            val foodIds = b.foodIds().toSet()
            val meals = parseMeals(j).filter { it.foodId in foodIds }
            n.restoreMeals(meals)
            val water = parseWater(j)
            n.restoreWater(water)
            val weights = parseWeights(j)
            n.restoreWeights(weights)
            val sessions = parseSessions(j)
            w.restoreSessions(sessions)
            val exerciseIds = b.exerciseIds().toSet()
            val allSets = parseSets(j)
            val sets = allSets.filter { it.exerciseId in exerciseIds }
            w.restoreSets(sets)
            skipped = allSets.size - sets.size + (j.optJSONArray("meals")?.length() ?: 0) - meals.size
            count = foods.size + meals.size + water.size + weights.size + sessions.size + sets.size
        }
        return ImportResult(count, fullRestore = false, skipped = skipped)
    }

    private fun parseWeights(j: JSONObject) = j.optJSONArray("weights").objs().map {
        BodyWeightEntity(it.getLong("id"), LocalDate.parse(it.getString("date")), it.getDouble("weightKg"))
    }

    private fun parseSessions(j: JSONObject) = j.optJSONArray("sessions").objs().map {
        WorkoutSessionEntity(
            it.getLong("id"), it.longOrNull("routineDayId"), it.getLong("startedAt"), it.longOrNull("endedAt"),
            SessionStatus.valueOf(it.getString("status")), it.optString("notes", ""), it.doubleOrNull("caloriesBurned")
        )
    }

    private fun parseSets(j: JSONObject) = j.optJSONArray("sets").objs().map {
        WorkoutSetEntity(
            it.getLong("id"), it.getLong("sessionId"), it.getLong("exerciseId"), it.getInt("setIndex"),
            it.getDouble("weightKg"), it.getInt("reps"), it.doubleOrNull("rpe"),
            it.getBoolean("isWarmup"), it.getBoolean("isPr"), it.getLong("completedAt")
        )
    }

    private fun parseFoods(j: JSONObject) = j.optJSONArray("foods").objs().map {
        FoodEntity(
            it.getLong("id"), it.optStringOrNull("barcode"), it.getString("name"), it.optStringOrNull("brand"),
            it.getDouble("servingSize"), it.getString("servingUnit"), it.getDouble("kcal"),
            it.getDouble("protein"), it.getDouble("carbs"), it.getDouble("fat"),
            FoodSource.valueOf(it.getString("source")), it.optBoolean("isFavorite"), it.longOrNull("lastUsedAt")
        )
    }

    private fun parseMeals(j: JSONObject) = j.optJSONArray("meals").objs().map {
        MealEntryEntity(
            it.getLong("id"), LocalDate.parse(it.getString("date")), MealType.valueOf(it.getString("mealType")),
            it.getLong("foodId"), it.getString("foodName"), it.getDouble("servings"), it.getDouble("kcal"),
            it.getDouble("protein"), it.getDouble("carbs"), it.getDouble("fat"), it.getLong("loggedAt")
        )
    }

    private fun parseWater(j: JSONObject) = j.optJSONArray("water").objs().map {
        WaterEntryEntity(it.getLong("id"), LocalDate.parse(it.getString("date")), it.getInt("amountMl"), it.getLong("loggedAt"))
    }

    /** One row per logged set, with exercise names, for spreadsheet analysis. */
    suspend fun exportWorkoutsCsv(): String {
        val names = db.routineDao().allExercises().associate { it.id to it.name }
        val sb = StringBuilder("date,exercise,set,weight_kg,reps_or_duration,rpe,warmup,pr\n")
        for (s in db.workoutDao().allSets()) {
            val date = java.time.Instant.ofEpochMilli(s.completedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            sb.append("$date,${csv(names[s.exerciseId] ?: "?")},${s.setIndex + 1},${s.weightKg},${s.reps},${s.rpe ?: ""},${s.isWarmup},${s.isPr}\n")
        }
        return sb.toString()
    }

    suspend fun exportMealsCsv(): String {
        val sb = StringBuilder("date,meal,food,servings,kcal,protein_g,carbs_g,fat_g\n")
        for (m in db.nutritionDao().allMeals()) {
            sb.append("${m.date},${m.mealType},${csv(m.foodName)},${m.servings},${m.kcal.toInt()},${m.protein},${m.carbs},${m.fat}\n")
        }
        return sb.toString()
    }

    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    private fun <T> arr(list: List<T>, f: (T) -> JSONObject) = JSONArray().also { a -> list.forEach { a.put(f(it)) } }
    private fun Any?.orNull(): Any = this ?: JSONObject.NULL
    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.optStringOrNull(k: String): String? = if (!has(k) || isNull(k)) null else getString(k)
    private fun JSONObject.longOrNull(k: String): Long? = if (!has(k) || isNull(k)) null else getLong(k)
    private fun JSONObject.intOrNull(k: String): Int? = if (!has(k) || isNull(k)) null else getInt(k)
    private fun JSONObject.doubleOrNull(k: String): Double? = if (!has(k) || isNull(k)) null else getDouble(k)
}
