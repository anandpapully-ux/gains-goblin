package com.healthvectors.fitness.data.plan

import android.content.Context
import com.healthvectors.fitness.domain.plan.Plan
import com.healthvectors.fitness.domain.plan.PlanCodec
import com.healthvectors.fitness.domain.plan.PlanExercise
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PlanTemplate(val id: String, val plan: Plan)

/** Built-in exercise library and starter plans shipped in `assets/`. */
@Singleton
class PlanAssets @Inject constructor(@ApplicationContext private val context: Context) {
    private fun read(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }

    /** The original 4-day dumbbell plan the app was first built around. */
    fun original(): Plan = PlanCodec.decode(read(ORIGINAL))

    /** Every exercise the app knows out of the box: the library file plus the original plan's exercises. */
    fun library(): List<PlanExercise> =
        (original().exercises + PlanCodec.decodeLibrary(read(LIBRARY))).distinctBy { it.slug }

    /** Starter plans, the original plan first, the blank plan last. */
    fun templates(): List<PlanTemplate> {
        val files = context.assets.list(TEMPLATES).orEmpty().filter { it.endsWith(".json") }.sorted()
        return listOf(PlanTemplate(ORIGINAL, original())) +
            files.map { PlanTemplate(it, PlanCodec.decode(read("$TEMPLATES/$it"))) }
    }

    companion object {
        const val ORIGINAL = "workout_plan.json"
        const val LIBRARY = "exercise_library.json"
        const val TEMPLATES = "templates"
    }
}
