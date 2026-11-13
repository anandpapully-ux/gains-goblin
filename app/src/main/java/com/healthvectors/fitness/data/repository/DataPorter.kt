package com.healthvectors.fitness.data.repository

import androidx.room.withTransaction
import com.healthvectors.fitness.data.local.FitnessDatabase
import com.healthvectors.fitness.data.local.entity.*
import com.healthvectors.fitness.domain.model.FoodSource
import com.healthvectors.fitness.domain.model.MealType
import com.healthvectors.fitness.domain.model.SessionStatus
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** JSON export/import of user-generated data and CSV export for spreadsheets. */
@Singleton
class DataPorter @Inject constructor(private val db: FitnessDatabase) {

    suspend fun exportJson(): String {
        val w = db.workoutDao(); val n = db.nutritionDao(); val p = db.profileDao()
        return JSONObject().apply {
            put("version", 1)
            put("weights", JSONArray(p.allWeights().map {
                JSONObject().put("id", it.id).put("date", it.date.toString()).put("weightKg", it.weightKg)
            }))
            put("sessions", JSONArray(w.allSessions().map {
                JSONObject().put("id", it.id).put("routineDayId", it.routineDayId ?: JSONObject.NULL)
                    .put("startedAt", it.startedAt).put("endedAt", it.endedAt ?: JSONObject.NULL)
                    .put("status", it.status.name).put("caloriesBurned", it.caloriesBurned ?: JSONObject.NULL)
            }))
            put("sets", JSONArray(w.allSets().map {
                JSONObject().put("id", it.id).put("sessionId", it.sessionId).put("exerciseId", it.exerciseId)
                    .put("setIndex", it.setIndex).put("weightKg", it.weightKg).put("reps", it.reps)
                    .put("rpe", it.rpe ?: JSONObject.NULL).put("isWarmup", it.isWarmup)
                    .put("isPr", it.isPr).put("completedAt", it.completedAt)
            }))
            put("foods", JSONArray(n.allFoods().map {
                JSONObject().put("id", it.id).put("barcode", it.barcode ?: JSONObject.NULL).put("name", it.name)
                    .put("brand", it.brand ?: JSONObject.NULL).put("servingSize", it.servingSize)
                    .put("servingUnit", it.servingUnit).put("kcal", it.kcal).put("protein", it.protein)
                    .put("carbs", it.carbs).put("fat", it.fat).put("source", it.source.name)
                    .put("isFavorite", it.isFavorite)
            }))
            put("meals", JSONArray(n.allMeals().map {
                JSONObject().put("id", it.id).put("date", it.date.toString()).put("mealType", it.mealType.name)
                    .put("foodId", it.foodId).put("foodName", it.foodName).put("servings", it.servings)
                    .put("kcal", it.kcal).put("protein", it.protein).put("carbs", it.carbs).put("fat", it.fat)
                    .put("loggedAt", it.loggedAt)
            }))
            put("water", JSONArray(n.allWater().map {
                JSONObject().put("id", it.id).put("date", it.date.toString())
                    .put("amountMl", it.amountMl).put("loggedAt", it.loggedAt)
            }))
        }.toString(2)
    }

    /** Merges the file into the database (rows with matching ids are replaced). Returns row count. */
    suspend fun importJson(text: String): Int {
        val j = JSONObject(text)
        var count = 0
        db.withTransaction {
            val foods = j.optJSONArray("foods").objs().map {
                FoodEntity(
                    it.getLong("id"), it.optStringOrNull("barcode"), it.getString("name"), it.optStringOrNull("brand"),
                    it.getDouble("servingSize"), it.getString("servingUnit"), it.getDouble("kcal"),
                    it.getDouble("protein"), it.getDouble("carbs"), it.getDouble("fat"),
                    FoodSource.valueOf(it.getString("source")), it.optBoolean("isFavorite")
                )
            }
            db.nutritionDao().restoreFoods(foods)
            val meals = j.optJSONArray("meals").objs().map {
                MealEntryEntity(
                    it.getLong("id"), LocalDate.parse(it.getString("date")), MealType.valueOf(it.getString("mealType")),
                    it.getLong("foodId"), it.getString("foodName"), it.getDouble("servings"), it.getDouble("kcal"),
                    it.getDouble("protein"), it.getDouble("carbs"), it.getDouble("fat"), it.getLong("loggedAt")
                )
            }
            db.nutritionDao().restoreMeals(meals)
            val water = j.optJSONArray("water").objs().map {
                WaterEntryEntity(it.getLong("id"), LocalDate.parse(it.getString("date")), it.getInt("amountMl"), it.getLong("loggedAt"))
            }
            db.nutritionDao().restoreWater(water)
            val weights = j.optJSONArray("weights").objs().map {
                BodyWeightEntity(it.getLong("id"), LocalDate.parse(it.getString("date")), it.getDouble("weightKg"))
            }
            db.nutritionDao().restoreWeights(weights)
            val sessions = j.optJSONArray("sessions").objs().map {
                WorkoutSessionEntity(
                    it.getLong("id"), if (it.isNull("routineDayId")) null else it.getLong("routineDayId"),
                    it.getLong("startedAt"), if (it.isNull("endedAt")) null else it.getLong("endedAt"),
                    SessionStatus.valueOf(it.getString("status")), "",
                    if (it.isNull("caloriesBurned")) null else it.getDouble("caloriesBurned")
                )
            }
            db.workoutDao().restoreSessions(sessions)
            val sets = j.optJSONArray("sets").objs().map {
                WorkoutSetEntity(
                    it.getLong("id"), it.getLong("sessionId"), it.getLong("exerciseId"), it.getInt("setIndex"),
                    it.getDouble("weightKg"), it.getInt("reps"), if (it.isNull("rpe")) null else it.getDouble("rpe"),
                    it.getBoolean("isWarmup"), it.getBoolean("isPr"), it.getLong("completedAt")
                )
            }
            db.workoutDao().restoreSets(sets)
            count = foods.size + meals.size + water.size + weights.size + sessions.size + sets.size
        }
        return count
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
    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else getString(k)
}
