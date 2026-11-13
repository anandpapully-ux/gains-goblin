package com.healthvectors.fitness.domain.logic

import com.healthvectors.fitness.domain.model.ActivityLevel
import com.healthvectors.fitness.domain.model.DayType
import com.healthvectors.fitness.domain.model.Goal
import com.healthvectors.fitness.domain.model.Sex
import kotlin.math.roundToInt

data class Macros(val kcal: Int, val proteinG: Int, val carbsG: Int, val fatG: Int)

object CalorieCalculator {
    const val KCAL_PER_KG_FAT = 7700.0
    const val MET_STRENGTH = 5.0
    const val MET_CARDIO_EASY = 5.0
    const val DEFAULT_CUT_RATE_PCT = 0.6   // 0.5-0.7 % body weight per week (from the plan)
    private const val BULK_SURPLUS = 300.0

    /** Mifflin-St Jeor basal metabolic rate. */
    fun bmr(sex: Sex, weightKg: Double, heightCm: Double, ageYears: Int): Double =
        10 * weightKg + 6.25 * heightCm - 5 * ageYears + if (sex == Sex.MALE) 5 else -161

    fun tdee(bmr: Double, activity: ActivityLevel) = bmr * activity.factor

    /**
     * Daily calorie target for the goal. A cut uses [weeklyRatePct] of body weight per week, capped at
     * 25 % below TDEE and never below a safety floor.
     */
    fun targetKcal(
        tdee: Double, goal: Goal, weightKg: Double, weeklyRatePct: Double, sex: Sex
    ): Double = when (goal) {
        Goal.MAINTAIN -> tdee
        Goal.BULK -> tdee + BULK_SURPLUS
        Goal.CUT -> {
            val deficit = (weightKg * weeklyRatePct / 100.0 * KCAL_PER_KG_FAT / 7.0).coerceAtMost(tdee * 0.25)
            val floor = if (sex == Sex.MALE) 1500.0 else 1200.0
            (tdee - deficit).coerceAtLeast(floor)
        }
    }

    /** Protein 2.0 g/kg while cutting, 1.8 g/kg otherwise; fat 25 % of kcal; carbs fill the rest. */
    fun macros(kcal: Double, weightKg: Double, goal: Goal): Macros {
        val protein = weightKg * if (goal == Goal.CUT) 2.0 else 1.8
        val fat = (kcal * 0.25 / 9.0).coerceAtLeast(weightKg * 0.6)
        val carbs = ((kcal - protein * 4 - fat * 9) / 4.0).coerceAtLeast(0.0)
        return Macros(kcal.roundToInt(), protein.roundToInt(), carbs.roundToInt(), fat.roundToInt())
    }

    /**
     * Workout-aware targets: training days eat ~6 % more, rest/cardio days ~8 % less (net ~0 over a
     * 4-train/3-off week). Protein and fat stay fixed; the delta is carbs.
     */
    fun adjustForDay(base: Macros, type: DayType): Macros {
        val factor = when (type) {
            DayType.HYPERTROPHY -> 1.06
            DayType.CARDIO, DayType.REST -> 0.92
        }
        val newKcal = (base.kcal * factor).roundToInt()
        val deltaCarbs = ((newKcal - base.kcal) / 4.0).roundToInt()
        return base.copy(kcal = newKcal, carbsG = (base.carbsG + deltaCarbs).coerceAtLeast(0))
    }

    fun workoutBurn(met: Double, weightKg: Double, minutes: Double): Double = met * weightKg * minutes / 60.0
}
