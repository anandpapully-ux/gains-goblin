package com.healthvectors.fitness.domain.logic

/** Estimated one-rep-max formulas. */
object OneRepMax {
    /** Estimates are unreliable above this many reps, so no estimate is produced. */
    const val MAX_RELIABLE_REPS = 12

    fun epley(weight: Double, reps: Int): Double =
        if (reps <= 1) weight else weight * (1 + reps / 30.0)

    fun brzycki(weight: Double, reps: Int): Double =
        if (reps <= 1) weight else weight * 36.0 / (37.0 - reps)

    /** Returns null for invalid input or sets above [MAX_RELIABLE_REPS]. */
    fun estimate(weight: Double, reps: Int): Double? {
        if (weight <= 0.0 || reps <= 0 || reps > MAX_RELIABLE_REPS) return null
        return epley(weight, reps)
    }

    /** Weight expected to be liftable for [reps] given a 1RM (inverse Epley). */
    fun weightForReps(oneRm: Double, reps: Int): Double = oneRm / (1 + reps / 30.0)
}
