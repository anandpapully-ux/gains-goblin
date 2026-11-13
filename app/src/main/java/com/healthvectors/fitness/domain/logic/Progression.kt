package com.healthvectors.fitness.domain.logic

import com.healthvectors.fitness.domain.model.ProgressionRule

data class SetResult(val weightKg: Double, val reps: Int, val rir: Int? = null)

data class ProgressionTarget(
    val sets: Int,
    val repMin: Int,
    val repMax: Int,
    val rule: ProgressionRule
)

sealed interface Suggestion {
    /** No history for this exercise yet. */
    data object NoHistory : Suggestion
    data class IncreaseWeight(val newWeightKg: Double) : Suggestion
    data class KeepWeight(val targetReps: Int) : Suggestion
    data class DecreaseWeight(val newWeightKg: Double) : Suggestion
}

/**
 * Double-progression rules from the plan:
 *  - Stay at a weight until every working set reaches the top of the rep range, then step up.
 *  - If any set falls below the bottom of the range, step the weight back down.
 *  - Isolation lifts use the same logic with their wider (e.g. 12-25) rep ceilings.
 */
object Progression {
    fun suggest(last: List<SetResult>, target: ProgressionTarget, stepKg: Double): Suggestion {
        if (target.rule == ProgressionRule.TIMED_CORE || target.rule == ProgressionRule.DURATION_DISTANCE) {
            return Suggestion.NoHistory
        }
        if (last.isEmpty()) return Suggestion.NoHistory
        val weight = last.maxOf { it.weightKg }
        val working = last.filter { it.weightKg >= weight - 1e-6 }
        val allAtTop = working.size >= target.sets && working.all { it.reps >= target.repMax }
        val anyBelowMin = working.any { it.reps < target.repMin }
        return when {
            allAtTop -> Suggestion.IncreaseWeight(weight + stepKg)
            anyBelowMin && weight - stepKg > 0 -> Suggestion.DecreaseWeight(weight - stepKg)
            else -> {
                val minReps = working.minOf { it.reps }
                Suggestion.KeepWeight((minReps + 1).coerceAtMost(target.repMax))
            }
        }
    }
}

/** Simple fatigue heuristic for the adaptive coach: sustained high RPE means a deload week. */
object DeloadDetector {
    /**
     * @param avgRpePerSession average RPE of recent sessions, oldest first.
     */
    fun needsDeload(avgRpePerSession: List<Double>): Boolean {
        if (avgRpePerSession.size >= 2 && avgRpePerSession.takeLast(2).all { it >= 9.5 }) return true
        if (avgRpePerSession.size >= 3) {
            val l = avgRpePerSession.takeLast(3)
            if (l.all { it >= 9.0 } && l[0] <= l[1] && l[1] <= l[2]) return true
        }
        return false
    }
}
