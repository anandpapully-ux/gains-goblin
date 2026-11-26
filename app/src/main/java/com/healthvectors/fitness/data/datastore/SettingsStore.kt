package com.healthvectors.fitness.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.healthvectors.fitness.domain.model.ScheduleMode
import com.healthvectors.fitness.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val onboardingDone: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val gymMode: Boolean = false,
    val restSound: Boolean = true,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 18,
    val waterGoalMl: Int = 2500,
    val gamification: Boolean = true,
    val scheduleMode: ScheduleMode = ScheduleMode.WEEKLY,
    /** Eat a little more on lifting days and less on the others (weekly total unchanged). */
    val calorieCycling: Boolean = true,
    /** Bar weight for the plate calculator, in kg; null uses 20 kg / 45 lb. */
    val barKg: Double? = null
)

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val gym = booleanPreferencesKey("gym_mode")
        val sound = booleanPreferencesKey("rest_sound")
        val reminder = booleanPreferencesKey("reminder")
        val reminderHour = intPreferencesKey("reminder_hour")
        val water = intPreferencesKey("water_goal")
        val gamification = booleanPreferencesKey("gamification")
        val schedule = stringPreferencesKey("schedule_mode")
        val cycling = booleanPreferencesKey("calorie_cycling")
        val bar = doublePreferencesKey("bar_kg")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingDone = p[K.onboarding] ?: false,
            themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[K.dynamic] ?: false,
            gymMode = p[K.gym] ?: false,
            restSound = p[K.sound] ?: true,
            reminderEnabled = p[K.reminder] ?: false,
            reminderHour = p[K.reminderHour] ?: 18,
            waterGoalMl = p[K.water] ?: 2500,
            gamification = p[K.gamification] ?: true,
            scheduleMode = p[K.schedule]?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() } ?: ScheduleMode.WEEKLY,
            calorieCycling = p[K.cycling] ?: true,
            barKg = p[K.bar]
        )
    }

    suspend fun setOnboardingDone(v: Boolean) = context.dataStore.edit { it[K.onboarding] = v }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[K.dynamic] = v }
    suspend fun setGymMode(v: Boolean) = context.dataStore.edit { it[K.gym] = v }
    suspend fun setRestSound(v: Boolean) = context.dataStore.edit { it[K.sound] = v }
    suspend fun setReminder(enabled: Boolean, hour: Int) = context.dataStore.edit {
        it[K.reminder] = enabled; it[K.reminderHour] = hour
    }
    suspend fun setWaterGoal(v: Int) = context.dataStore.edit { it[K.water] = v }
    suspend fun setGamification(v: Boolean) = context.dataStore.edit { it[K.gamification] = v }
    suspend fun setScheduleMode(v: ScheduleMode) = context.dataStore.edit { it[K.schedule] = v.name }
    suspend fun setCalorieCycling(v: Boolean) = context.dataStore.edit { it[K.cycling] = v }
    suspend fun setBarKg(v: Double?) = context.dataStore.edit { if (v == null) it.remove(K.bar) else it[K.bar] = v }
}
