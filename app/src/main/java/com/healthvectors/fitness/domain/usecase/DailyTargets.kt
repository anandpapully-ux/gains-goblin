package com.healthvectors.fitness.domain.usecase

import com.healthvectors.fitness.data.local.entity.UserProfileEntity
import com.healthvectors.fitness.domain.logic.CalorieCalculator
import com.healthvectors.fitness.domain.logic.Macros
import com.healthvectors.fitness.domain.model.DayType
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject

/** Computes the calorie/macro target for a given day type from the user's profile and latest weight. */
class DailyTargets @Inject constructor() {
    fun base(profile: UserProfileEntity, weightKg: Double, today: LocalDate = LocalDate.now()): Macros {
        val age = Period.between(profile.birthDate, today).years
        val bmr = CalorieCalculator.bmr(profile.sex, weightKg, profile.heightCm, age)
        val tdee = CalorieCalculator.tdee(bmr, profile.activityLevel)
        val kcal = CalorieCalculator.targetKcal(tdee, profile.goal, weightKg, profile.weeklyRatePct, profile.sex)
        return CalorieCalculator.macros(kcal, weightKg, profile.goal)
    }

    fun forDay(profile: UserProfileEntity, weightKg: Double, type: DayType, today: LocalDate = LocalDate.now()) =
        CalorieCalculator.adjustForDay(base(profile, weightKg, today), type)
}
