package com.healthvectors.fitness.domain.usecase

import com.healthvectors.fitness.data.datastore.SettingsStore
import com.healthvectors.fitness.data.local.entity.UserProfileEntity
import com.healthvectors.fitness.data.repository.ProfileRepository
import com.healthvectors.fitness.data.repository.RoutineRepository
import com.healthvectors.fitness.domain.logic.CalorieCalculator
import com.healthvectors.fitness.domain.logic.Macros
import com.healthvectors.fitness.domain.model.DayType
import com.healthvectors.fitness.domain.model.ScheduleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject

data class DayTargets(val macros: Macros, val note: String)

/** Calorie/macro targets from the profile, the user's own overrides, and the active plan. */
class DailyTargets @Inject constructor(
    private val profiles: ProfileRepository,
    private val routines: RoutineRepository,
    private val settings: SettingsStore
) {
    /** Daily target before training-day cycling; the user's own values replace calculated ones. */
    fun base(profile: UserProfileEntity, weightKg: Double, today: LocalDate = LocalDate.now()): Macros {
        val age = Period.between(profile.birthDate, today).years
        val bmr = CalorieCalculator.bmr(profile.sex, weightKg, profile.heightCm, age)
        val tdee = CalorieCalculator.tdee(bmr, profile.activityLevel)
        val kcal = CalorieCalculator.targetKcal(tdee, profile.goal, weightKg, profile.weeklyRatePct, profile.sex)
        val computed = CalorieCalculator.macros(kcal, weightKg, profile.goal)
        return CalorieCalculator.withOverrides(computed, profile.customKcal, profile.customProteinG, profile.customFatG)
    }

    /**
     * Targets for [date]. Cycling only applies with fixed weekdays (in rotation mode the app cannot know
     * in advance whether you will train), and it is balanced for the number of lifting days in the plan.
     */
    fun observe(date: LocalDate): Flow<DayTargets?> =
        combine(profiles.profile, profiles.latestWeight, settings.settings, routines.days) { p, w, s, days ->
            if (p == null || w == null) return@combine null
            val base = base(p, w.weightKg, date)
            val lifting = days.count { it.type == DayType.HYPERTROPHY }
            if (!s.calorieCycling || s.scheduleMode == ScheduleMode.ROTATION || lifting == 0 || lifting == 7) {
                return@combine DayTargets(base, "Same target every day")
            }
            val type = days.firstOrNull { it.dayOfWeek == date.dayOfWeek.value }?.type ?: DayType.REST
            val m = CalorieCalculator.adjustForDay(base, type, lifting)
            DayTargets(m, if (type == DayType.HYPERTROPHY) "Training-day target (more carbs)" else "Lighter ${type.name.lowercase()}-day target")
        }
}
