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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.ui.graphics.StrokeCap
import com.healthvectors.fitness.core.designsystem.*
import com.healthvectors.fitness.core.util.Units
import java.time.format.DateTimeFormatter
import com.healthvectors.fitness.data.datastore.SettingsStore
import com.healthvectors.fitness.data.local.dao.RoutineExerciseWithExercise
import com.healthvectors.fitness.data.local.entity.RoutineDayEntity
import com.healthvectors.fitness.data.repository.*
import com.healthvectors.fitness.domain.logic.DeloadDetector
import com.healthvectors.fitness.domain.logic.Macros
import com.healthvectors.fitness.domain.logic.Schedule
import com.healthvectors.fitness.domain.logic.StreakCalculator
import com.healthvectors.fitness.data.local.entity.WorkoutSessionEntity
import com.healthvectors.fitness.domain.model.DayType
import com.healthvectors.fitness.domain.model.ScheduleMode
import com.healthvectors.fitness.domain.usecase.DailyTargets
import com.healthvectors.fitness.domain.usecase.DayTargets
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
    val targetNote: String = "",
    val eaten: Macros = Macros(0, 0, 0, 0),
    val waterMl: Int = 0,
    val waterGoalMl: Int = 2500,
    val streak: Int = 0,
    val xp: Int = 0,
    val gamification: Boolean = true,
    val deload: Boolean = false,
    val rotation: Boolean = false,
    val trainedToday: Boolean = false,
    val loading: Boolean = true
)

private data class WorkoutPart(
    val day: RoutineDayEntity?, val count: Int, val activeId: Long?, val done: List<WorkoutSessionEntity>,
    val rotation: Boolean, val gapDays: Long
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val routines: RoutineRepository,
    private val workouts: WorkoutRepository,
    private val nutrition: NutritionRepository,
    targets: DailyTargets,
    settings: SettingsStore
) : ViewModel() {
    private val today = LocalDate.now()
    private val dow = today.dayOfWeek.value
    private val zone = ZoneId.systemDefault()

    /** Weekly mode: today's weekday. Rotation mode: the next training day after the last one finished. */
    private val dayFlow: Flow<Pair<RoutineDayEntity?, Boolean>> = settings.settings.map { it.scheduleMode }.distinctUntilChanged()
        .flatMapLatest { mode ->
            if (mode == ScheduleMode.ROTATION) {
                combine(routines.days, workouts.lastDoneDay) { days, last ->
                    val next = Schedule.nextInRotation(days.map { Schedule.Slot(it.id, it.dayOfWeek, it.type) }, last?.id)
                    days.firstOrNull { it.id == next?.id } to true
                }
            } else routines.dayOfWeek(dow).map { it to false }
        }
    private val itemsFlow = dayFlow.flatMapLatest { (d, _) -> if (d == null) flowOf(emptyList<RoutineExerciseWithExercise>()) else routines.items(d.id) }

    private val workoutPart = combine(dayFlow, itemsFlow, workouts.activeSession, workouts.doneSessions(), routines.days) { day, items, active, done, days ->
        val gap = Schedule.streakGapDays(days.filter { it.type != DayType.REST }.map { it.dayOfWeek }.toSet())
        WorkoutPart(day.first, items.size, active?.id, done, day.second, gap)
    }
    private val foodPart = combine(nutrition.meals(today), nutrition.water(today), settings.settings, targets.observe(today)) { meals, water, s, t ->
        listOf(meals, water, s, t)
    }

    val ui: StateFlow<TodayUi> = combine(workoutPart, foodPart) { w, f ->
        @Suppress("UNCHECKED_CAST") val meals = f[0] as List<com.healthvectors.fitness.data.local.entity.MealEntryEntity>
        val water = f[1] as Int
        val s = f[2] as com.healthvectors.fitness.data.datastore.AppSettings
        val t = f[3] as DayTargets?
        val sessionDays = w.done.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
        TodayUi(
            day = w.day, exerciseCount = w.count, activeSessionId = w.activeId, targets = t?.macros, targetNote = t?.note.orEmpty(),
            eaten = Macros(meals.sumOf { it.kcal }.toInt(), meals.sumOf { it.protein }.toInt(),
                meals.sumOf { it.carbs }.toInt(), meals.sumOf { it.fat }.toInt()),
            waterMl = water, waterGoalMl = s.waterGoalMl, streak = StreakCalculator.streak(sessionDays, today, w.gapDays),
            xp = w.done.size * 100, gamification = s.gamification, rotation = w.rotation,
            trainedToday = today in sessionDays, loading = false
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
    val today = LocalDate.now()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(greeting(), style = MaterialTheme.typography.headlineLarge)
        }
        if (ui.gamification && (ui.streak > 0 || ui.xp > 0)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                if (ui.streak > 0) StatPill(Icons.Filled.LocalFireDepartment, "${ui.streak}-session streak", MaterialTheme.colorScheme.tertiary)
                StatPill(Icons.Filled.EmojiEvents, "Level ${ui.xp / 500 + 1} · ${Units.thousands(ui.xp)} XP", MaterialTheme.colorScheme.primary)
            }
        }
        if (ui.deload) {
            AppCard(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IconBadge(Icons.Filled.TipsAndUpdates, container = MaterialTheme.colorScheme.tertiary, content = MaterialTheme.colorScheme.onTertiary)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Time for a deload?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(
                            "Your recent sessions have been at very high effort (RPE 9+). Try a lighter week: same exercises, " +
                                "about 60% of your usual weight, fewer sets.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
        WorkoutCard(ui, vm, onOpenSession)

        SectionTitle("Nutrition", action = "Open", onAction = onOpenNutrition)
        AppCard(onClick = onOpenNutrition) {
            val t = ui.targets
            if (t == null) {
                Text("Complete your profile to see your targets.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CalorieRing(ui.eaten.kcal.toDouble(), t.kcal.toDouble(), size = 124.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MacroBar("Protein", ui.eaten.proteinG.toDouble(), t.proteinG.toDouble())
                        MacroBar("Carbs", ui.eaten.carbsG.toDouble(), t.carbsG.toDouble())
                        MacroBar("Fat", ui.eaten.fatG.toDouble(), t.fatG.toDouble())
                    }
                }
                Text(
                    "${Units.thousands(ui.eaten.kcal)} of ${Units.thousands(t.kcal)} kcal eaten" + if (ui.targetNote.isNotBlank()) " · ${ui.targetNote}" else "",
                    Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionTitle("Water")
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconBadge(Icons.Filled.WaterDrop, container = MaterialTheme.colorScheme.secondaryContainer, content = MaterialTheme.colorScheme.onSecondaryContainer)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(Units.thousands(ui.waterMl), style = MaterialTheme.typography.headlineSmall)
                        Text(" / ${Units.thousands(ui.waterGoalMl)} ml", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                    }
                    val glasses = ((ui.waterGoalMl - ui.waterMl).coerceAtLeast(0) + 249) / 250
                    Text(
                        if (glasses == 0) "Goal reached. Nice." else "$glasses more ${if (glasses == 1) "glass" else "glasses"} to go",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = vm::undoWater, enabled = ui.waterMl > 0) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo last water") }
            }
            LinearProgressIndicator(
                progress = { (ui.waterMl.toFloat() / ui.waterGoalMl).coerceIn(0f, 1f) },
                Modifier.fillMaxWidth().padding(vertical = 16.dp).height(8.dp),
                color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {}
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.addWater(250) }, Modifier.weight(1f)) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Glass · 250 ml") }
                FilledTonalButton(onClick = { vm.addWater(500) }, Modifier.weight(1f)) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Bottle · 500 ml") }
            }
        }
    }
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 4..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun StatPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = tint.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun WorkoutCard(ui: TodayUi, vm: TodayViewModel, onOpenSession: (Long) -> Unit) {
    val day = ui.day
    when {
        ui.loading -> AppCard { Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        day == null || day.type == DayType.REST -> AppCard {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconBadge(Icons.Filled.SelfImprovement, container = MaterialTheme.colorScheme.secondaryContainer, content = MaterialTheme.colorScheme.onSecondaryContainer)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (ui.rotation) "No training days yet" else "Rest day", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (ui.rotation) "Add exercises to a day in the Workout tab."
                        else "Recovery is where muscle is built. Walk, stretch and drink water. Want to train anyway? Pick any day in the Workout tab.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        else -> Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val overline = when {
                    ui.activeSessionId != null -> "In progress"
                    ui.rotation && ui.trainedToday -> "Done today · next up"
                    ui.rotation -> "Next in your rotation"
                    else -> "Today's workout"
                }
                Text(overline.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                Text(day.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)) {
                    Meta(Icons.Filled.FitnessCenter, "${ui.exerciseCount} exercises")
                    if (day.estimatedMinutes > 0) Meta(Icons.Filled.Schedule, "About ${day.estimatedMinutes} min")
                }
                Button(onClick = { vm.start(day.id, onOpenSession) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (ui.activeSessionId != null) "Resume workout" else "Start workout")
                }
            }
        }
    }
}

@Composable
private fun Meta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
