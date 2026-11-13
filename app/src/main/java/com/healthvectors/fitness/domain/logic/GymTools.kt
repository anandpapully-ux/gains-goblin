package com.healthvectors.fitness.domain.logic

import kotlin.math.abs
import kotlin.math.roundToInt

data class PlateResult(val platesPerSide: List<Double>, val remainderKg: Double)

object PlateCalculator {
    val DEFAULT_PLATES_KG = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)

    /** Greedy plate loading. Returns plates for ONE side of the bar. */
    fun platesPerSide(
        targetKg: Double, barKg: Double = 20.0, plates: List<Double> = DEFAULT_PLATES_KG
    ): PlateResult {
        var left = ((targetKg - barKg) / 2.0).coerceAtLeast(0.0)
        val out = mutableListOf<Double>()
        for (p in plates.sortedDescending()) {
            while (left + 1e-9 >= p) { out += p; left -= p }
        }
        return PlateResult(out, if (abs(left) < 1e-9) 0.0 else left * 2)
    }
}

data class WarmupSet(val weightKg: Double, val reps: Int)

object WarmupGenerator {
    /** 50 % x 8, 70 % x 5, 85 % x 3, rounded to [stepKg]. Skips duplicates and very light loads. */
    fun generate(workingKg: Double, stepKg: Double = 2.0): List<WarmupSet> {
        if (workingKg < 2 * stepKg) return emptyList()
        val scheme = listOf(0.5 to 8, 0.7 to 5, 0.85 to 3)
        return scheme
            .map { (pct, reps) -> WarmupSet(((workingKg * pct) / stepKg).roundToInt() * stepKg, reps) }
            .filter { it.weightKg in 0.01..(workingKg - 1e-6) }
            .distinctBy { it.weightKg }
    }
}

enum class PrType { ESTIMATED_1RM, WEIGHT }

data class ExerciseBest(val maxWeightKg: Double?, val maxE1rmKg: Double?)

object PrDetector {
    /** The very first logged set is never flagged, so a new exercise does not trigger a celebration. */
    fun detect(weightKg: Double, reps: Int, best: ExerciseBest?): List<PrType> {
        if (best == null || (best.maxWeightKg == null && best.maxE1rmKg == null)) return emptyList()
        val result = mutableListOf<PrType>()
        val e1rm = OneRepMax.estimate(weightKg, reps)
        if (e1rm != null && best.maxE1rmKg != null && e1rm > best.maxE1rmKg + 0.01) result += PrType.ESTIMATED_1RM
        if (best.maxWeightKg != null && weightKg > best.maxWeightKg + 0.01 && reps >= 1) result += PrType.WEIGHT
        return result
    }
}
