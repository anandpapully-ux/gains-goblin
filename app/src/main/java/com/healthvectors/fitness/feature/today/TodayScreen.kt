package com.healthvectors.fitness.feature.today

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.core.designsystem.CalorieRing
import com.healthvectors.fitness.core.designsystem.MacroBar
import com.healthvectors.fitness.data.datastore.SettingsStore
import com.healthvectors.fitness.data.local.dao.RoutineExerciseWithExercise
import com.healthvectors.fitness.data.local.entity.RoutineDayEntity
import com.healthvectors.fitness.data.repository.*
import com.healthvectors.fitness.domain.logic.DeloadDetector
import com.healthvectors.fitness.domain.logic.Macros
import com.healthvectors.fitness.domain.logic.StreakCalculator
import com.healthvectors.fitness.domain.model.DayType
import com.healthvectors.fitness.domain.usecase.DailyTargets
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class TodayUi(
    val day: RoutineDayEntity? = null,
    val exerciseCount: Int = 0,
    val activeSessionId: Long? = null,
    val targets: Macros? = null,
    val eaten: Macros = Macros(0, 0, 0, 0),
    val waterMl: Int = 0,
    val waterGoalMl: Int = 2500,
    val streak: Int = 0,
    val xp: Int = 0,
    val gamification: Boolean = true,
    val deload: Boolean = false,
    val loading: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val routines: RoutineRepository,
    private val workouts: WorkoutRepository,
    private val nutrition: NutritionRepository,
    private val profiles: ProfileRepository,
    private val targets: DailyTargets,
    settings: SettingsStore
) : ViewModel() {
    private val today = LocalDate.now()
    private val dow = today.dayOfWeek.value

    private val dayFlow = routines.dayOfWeek(dow)
    private val itemsFlow = dayFlow.flatMapLatest { d -> if (d == null) flowOf(emptyList<RoutineExerciseWithExercise>()) else routines.items(d.id) }

    private val workoutPart = combine(dayFlow, itemsFlow, workouts.activeSession, workouts.doneSessions()) { day, items, active, done ->
        listOf(day, items.size, active?.id, done)
    }
    private val foodPart = combine(nutrition.meals(today), nutrition.water(today), settings.settings) { meals, water, s ->
        Triple(meals, water, s)
    }

    val ui: StateFlow<TodayUi> = combine(workoutPart, foodPart, profiles.profile, profiles.latestWeight) { w, f, profile, weight ->
        val day = w[0] as RoutineDayEntity?
        @Suppress("UNCHECKED_CAST")
        val done = w[3] as List<com.healthvectors.fitness.data.local.entity.WorkoutSessionEntity>
        val (meals, water, s) = f
        val zone = ZoneId.systemDefault()
        val days = done.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
        val tgt = if (profile != null && weight != null) targets.forDay(profile, weight.weightKg, day?.type ?: DayType.REST, today) else null
        TodayUi(
            day = day, exerciseCount = w[1] as Int, activeSessionId = w[2] as Long?, targets = tgt,
            eaten = Macros(meals.sumOf { it.kcal }.toInt(), meals.sumOf { it.protein }.toInt(),
                meals.sumOf { it.carbs }.toInt(), meals.sumOf { it.fat }.toInt()),
            waterMl = water, waterGoalMl = s.waterGoalMl, streak = StreakCalculator.streak(days, today),
            xp = done.size * 100, gamification = s.gamification, loading = false
        )
    }.combine(deloadFlow()) { ui, deload -> ui.copy(deload = deload) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodayUi())

    /** Average RPE of the last few finished sessions feeds the adaptive coach's deload rule. */
    private fun deloadFlow(): Flow<Boolean> = workouts.doneSessions().map { sessions ->
        val rpes = sessions.takeLast(4).mapNotNull { workouts.avgRpe(it.id) }
        DeloadDetector.needsDeload(rpes)
    }

    fun start(dayId: Long, onReady: (Long) -> Unit) = viewModelScope.launch { onReady(workouts.startOrResume(dayId)) }
    fun addWater(ml: Int) = viewModelScope.launch { nutrition.addWater(ml) }
    fun undoWater() = viewModelScope.launch { nutrition.undoWater() }
}

@Composable
fun TodayScreen(
    onOpenSession: (Long) -> Unit,
    onOpenNutrition: () -> Unit,
    vm: TodayViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            LocalDate.now().dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase),
            style = MaterialTheme.typography.headlineLarge
        )
        if (ui.gamification && ui.streak > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("${ui.streak}-session streak") },
                    leadingIcon = { Icon(Icons.Filled.LocalFireDepartment, null) })
                AssistChip(onClick = {}, label = { Text("Level ${ui.xp / 500 + 1} - ${ui.xp} XP") },
                    leadingIcon = { Icon(Icons.Filled.EmojiEvents, null) })
            }
        }
        if (ui.deload) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text(
                    "Coach: your recent sessions have been at very high effort (RPE 9+). " +
                        "Consider a deload week: same exercises, ~60% of your usual weight, fewer sets.",
                    Modifier.padding(16.dp)
                )
            }
        }
        WorkoutCard(ui, vm, onOpenSession)

        Card(onClick = onOpenNutrition, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nutrition", style = MaterialTheme.typography.titleMedium)
                val t = ui.targets
                if (t == null) {
                    Text("Complete your profile to see targets.")
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CalorieRing(ui.eaten.kcal.toDouble(), t.kcal.toDouble())
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MacroBar("Protein", ui.eaten.proteinG.toDouble(), t.proteinG.toDouble())
                            MacroBar("Carbs", ui.eaten.carbsG.toDouble(), t.carbsG.toDouble())
                            MacroBar("Fat", ui.eaten.fatG.toDouble(), t.fatG.toDouble())
                        }
                    }
                    Text(
                        "Target adjusted for ${ui.day?.type?.name?.lowercase() ?: "rest"} day",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hydration  ${ui.waterMl} / ${ui.waterGoalMl} ml", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { (ui.waterMl.toFloat() / ui.waterGoalMl).coerceIn(0f, 1f) },
                    Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.addWater(250) }) { Text("+250 ml") }
                    FilledTonalButton(onClick = { vm.addWater(500) }) { Text("+500 ml") }
                    TextButton(onClick = vm::undoWater) { Text("Undo") }
                }
            }
        }
    }
}

@Composable
private fun WorkoutCard(ui: TodayUi, vm: TodayViewModel, onOpenSession: (Long) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val day = ui.day
            when {
                ui.loading -> CircularProgressIndicator()
                day == null || day.type == DayType.REST -> {
                    Text("Rest day", style = MaterialTheme.typography.titleLarge)
                    Text("Recovery is where the muscle is built. Walk, stretch, hydrate.")
                }
                else -> {
                    Text(day.title, style = MaterialTheme.typography.titleLarge)
                    Text("${ui.exerciseCount} exercises - about ${day.estimatedMinutes} min")
                    Button(
                        onClick = { vm.start(day.id, onOpenSession) },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (ui.activeSessionId != null) "Resume workout" else "Start workout")
                    }
                }
            }
        }
    }
}
