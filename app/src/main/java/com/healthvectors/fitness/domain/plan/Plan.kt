package com.healthvectors.fitness.domain.plan

import com.healthvectors.fitness.domain.model.DayType
import com.healthvectors.fitness.domain.model.ProgressionRule

/** A library exercise as it appears in plan files, templates and the built-in library. */
data class PlanExercise(
    val slug: String,
    val name: String,
    val primaryMuscle: String,
    val secondaryMuscles: List<String> = emptyList(),
    val equipment: String = "",
    val rule: ProgressionRule = ProgressionRule.COMPOUND_DOUBLE_PROGRESSION,
    /** "reps", "seconds" or "minutes". */
    val repUnit: String = "reps",
    val unilateral: Boolean = false,
    val alternatives: List<String> = emptyList(),
    val instructions: String = "",
    /** Smallest weight jump for this exercise; null means "use the app-wide setting". */
    val incrementKg: Double? = null
)

data class PlanItem(
    val slug: String,
    val sets: Int,
    val repMin: Int,
    val repMax: Int,
    val restMinSec: Int,
    val restMaxSec: Int,
    val rirMin: Int,
    val rirMax: Int,
    val perSide: Boolean = false,
    val notes: String = ""
)

/** [weekday] is 1 = Monday ... 7 = Sunday; in rotation mode it is only the order. */
data class PlanDay(
    val weekday: Int,
    val title: String,
    val type: DayType,
    val minutes: Int,
    val items: List<PlanItem>
)

data class Plan(
    val name: String,
    val description: String,
    val days: List<PlanDay>,
    /** Definitions for exercises the plan uses; may be empty for templates that use the built-in library. */
    val exercises: List<PlanExercise> = emptyList()
) {
    /** Always seven slots; weekdays the plan leaves out become rest days. */
    fun fullWeek(): List<PlanDay> = (1..7).map { wd ->
        days.firstOrNull { it.weekday == wd } ?: PlanDay(wd, "Rest", DayType.REST, 0, emptyList())
    }

    val trainingDays: Int get() = days.count { it.type != DayType.REST && it.items.isNotEmpty() }

    val referencedSlugs: Set<String> get() = days.flatMap { d -> d.items.map { it.slug } }.toSet()
}

class PlanFormatException(message: String) : Exception(message)
