package com.healthvectors.fitness.data.local.seed

import android.content.Context
import androidx.room.withTransaction
import com.healthvectors.fitness.data.local.FitnessDatabase
import com.healthvectors.fitness.data.local.entity.*
import com.healthvectors.fitness.domain.model.DayType
import com.healthvectors.fitness.domain.model.ProgressionRule
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds the default routine from `assets/workout_plan.json` the first time the app runs.
 * Duplicated exercises (e.g. `..._day1` / `..._day4`) are collapsed to one library entry so history
 * and PRs are shared across days.
 */
@Singleton
class PlanSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FitnessDatabase
) {
    suspend fun seedIfEmpty() {
        val dao = db.routineDao()
        if (dao.routineCount() > 0) return
        val root = JSONObject(context.assets.open("workout_plan.json").bufferedReader().use { it.readText() })
            .getJSONObject("routine")
        db.withTransaction {
            val routineId = dao.insertRoutine(
                RoutineEntity(name = root.getString("name"), description = root.getString("description"))
            )
            val days = root.getJSONArray("splitDays")
            for (i in 0 until days.length()) {
                val d = days.getJSONObject(i)
                val dayId = dao.insertDay(
                    RoutineDayEntity(
                        routineId = routineId,
                        dayOfWeek = d.getInt("dayNumber"),
                        title = d.getString("title"),
                        type = DayType.valueOf(d.getString("type")),
                        estimatedMinutes = d.optInt("estimatedDurationMinutes", 0)
                    )
                )
                val exs = d.getJSONArray("exercises")
                for (j in 0 until exs.length()) {
                    val e = exs.getJSONObject(j)
                    val exerciseId = upsertExercise(e)
                    val reps = e.getJSONObject("repRange")
                    val rest = e.getJSONObject("restTimeSeconds")
                    val rir = e.getJSONObject("targetRIR")
                    dao.insertItem(
                        RoutineExerciseEntity(
                            dayId = dayId,
                            exerciseId = exerciseId,
                            position = e.getInt("order"),
                            targetSets = e.getInt("targetSets"),
                            repMin = reps.getInt("min"),
                            repMax = reps.getInt("max"),
                            restMinSec = rest.getInt("min"),
                            restMaxSec = rest.getInt("max"),
                            rirMin = rir.getInt("min"),
                            rirMax = rir.getInt("max"),
                            perSide = reps.optBoolean("perSide", false),
                            notes = e.optString("notes", "")
                        )
                    )
                }
            }
        }
    }

    private suspend fun upsertExercise(e: JSONObject): Long {
        val dao = db.routineDao()
        val slug = e.getString("id").replace(Regex("_day\\d+$"), "")
        dao.exerciseIdBySlug(slug)?.let { return it }
        val unit = e.getJSONObject("repRange").getString("unit")
        val alts = e.optJSONArray("alternatives")?.let { a -> (0 until a.length()).joinToString("|") { a.getString(it) } } ?: ""
        val id = dao.insertExercise(
            ExerciseEntity(
                slug = slug,
                name = e.getString("name"),
                primaryMuscle = e.getString("primaryMuscle"),
                equipment = e.optString("equipment", ""),
                instructions = e.optString("notes", ""),
                progressionRule = ProgressionRule.valueOf(e.getString("progressionRule")),
                repUnit = unit,
                isUnilateral = e.getJSONObject("repRange").optBoolean("perSide", false),
                alternatives = alts
            )
        )
        val muscles = mutableListOf(ExerciseMuscleEntity(id, e.getString("primaryMuscle"), "PRIMARY"))
        e.optJSONArray("secondaryMuscles")?.let { a ->
            for (k in 0 until a.length()) muscles += ExerciseMuscleEntity(id, a.getString(k), "SECONDARY")
        }
        dao.insertMuscles(muscles)
        return id
    }
}
