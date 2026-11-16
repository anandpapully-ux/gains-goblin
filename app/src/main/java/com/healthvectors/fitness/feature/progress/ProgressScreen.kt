package com.healthvectors.fitness.feature.progress

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.core.designsystem.*
import com.healthvectors.fitness.core.util.Units
import com.healthvectors.fitness.data.local.entity.ExerciseEntity
import com.healthvectors.fitness.data.repository.*
import com.healthvectors.fitness.domain.logic.OneRepMax
import com.healthvectors.fitness.domain.logic.Schedule
import com.healthvectors.fitness.domain.logic.StreakCalculator
import com.healthvectors.fitness.domain.model.DayType
import androidx.compose.material.icons.filled.Delete
import com.healthvectors.fitness.domain.model.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

val MEASUREMENTS = listOf("Waist", "Chest", "Arm", "Thigh", "Hip")

data class MuscleHeat(val muscle: String, val sets: Int)
data class Summary(val sessions: Int, val volumeKg: Double, val avgKcal: Int, val monthSessions: Int)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val workouts: WorkoutRepository,
    private val nutrition: NutritionRepository,
    private val routines: RoutineRepository
) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    private fun millis(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    private val today = LocalDate.now()
    private val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private fun <T> Flow<T>.state(init: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)

    val unit = profiles.profile.map { it?.unitSystem ?: UnitSystem.METRIC }.state(UnitSystem.METRIC)
    val weights = profiles.weights.state(emptyList())
    val measureType = MutableStateFlow(MEASUREMENTS.first())
    val measurements = measureType.flatMapLatest { profiles.measurements(it.lowercase()) }.state(emptyList())

    /** Exercises in the current plan or with logged sets; the full library would bury them. */
    val exercises: StateFlow<List<ExerciseEntity>> = workouts.chartExercises.state(emptyList())
    val selectedExercise = MutableStateFlow<Long?>(null)
    val strength = combine(exercises, selectedExercise) { ex, sel -> sel ?: ex.firstOrNull()?.id }
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else workouts.exerciseHistory(id) }
        .map { sets ->
            sets.groupBy { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
                .toSortedMap()
                .mapNotNull { (d, l) -> l.mapNotNull { OneRepMax.estimate(it.weightKg, it.reps) }.maxOrNull()?.let { d to it } }
        }.state(emptyList())

    /** Training volume (kg x reps) for each of the last 8 weeks, oldest first. */
    val weeklyVolume = workouts.repSetsSince(millis(weekStart.minusWeeks(7))).map { sets ->
        (7 downTo 0).map { w ->
            val start = weekStart.minusWeeks(w.toLong())
            val from = millis(start); val to = millis(start.plusWeeks(1))
            start to sets.filter { it.completedAt in from until to }.sumOf { it.weightKg * it.reps }
        }
    }.state(emptyList())

    /** Calories eaten vs. estimated workout burn for the last 7 days. */
    val energy = combine(nutrition.mealsSince(today.minusDays(6)), workouts.doneSessionsSince(millis(today.minusDays(6)))) { meals, sessions ->
        (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            Triple(d, meals.filter { it.date == d }.sumOf { it.kcal }, sessions.filter {
                Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() == d
            }.sumOf { it.caloriesBurned ?: 0.0 })
        }
    }.state(emptyList())

    /** Sets trained per primary muscle this week; a muscle your plan targets with zero sets is neglected. */
    val heatmap = combine(workouts.muscleSetsSince(millis(weekStart)), routines.days) { rows, _ ->
        val trained = rows.associate { it.muscle to it.sets }
        (workouts.planPrimaryMuscles() + trained.keys).distinct().map { MuscleHeat(it, trained[it] ?: 0) }.sortedByDescending { it.sets }
    }.state(emptyList())

    val streak = combine(workouts.doneSessions(), routines.days) { s, days ->
        val gap = Schedule.streakGapDays(days.filter { it.type != DayType.REST }.map { it.dayOfWeek }.toSet())
        StreakCalculator.streak(s.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }, today, gap)
    }.state(0)

    val summary = combine(workouts.doneSessionsSince(millis(today.minusDays(29))), workouts.repSetsSince(millis(weekStart)), nutrition.mealsSince(today.minusDays(6))) { sessions, sets, meals ->
        val fromWeek = millis(weekStart)
        val days = meals.map { it.date }.distinct().size.coerceAtLeast(1)
        Summary(sessions.count { it.startedAt >= fromWeek }, sets.sumOf { it.weightKg * it.reps }, (meals.sumOf { it.kcal } / days).toInt(), sessions.size)
    }.state(Summary(0, 0.0, 0, 0))

    fun logWeight(kg: Double) = viewModelScope.launch { profiles.logWeight(kg) }
    fun deleteWeight(id: Long) = viewModelScope.launch { profiles.deleteWeight(id) }
    fun deleteMeasurement(id: Long) = viewModelScope.launch { profiles.deleteMeasurement(id) }
    fun logMeasurement(cm: Double) = viewModelScope.launch { profiles.logMeasurement(measureType.value.lowercase(), cm) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProgressScreen(vm: ProgressViewModel = hiltViewModel()) {
    val unit by vm.unit.collectAsStateWithLifecycle()
    val weights by vm.weights.collectAsStateWithLifecycle()
    val measureType by vm.measureType.collectAsStateWithLifecycle()
    val measurements by vm.measurements.collectAsStateWithLifecycle()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val selected by vm.selectedExercise.collectAsStateWithLifecycle()
    val strength by vm.strength.collectAsStateWithLifecycle()
    val volume by vm.weeklyVolume.collectAsStateWithLifecycle()
    val energy by vm.energy.collectAsStateWithLifecycle()
    val heat by vm.heatmap.collectAsStateWithLifecycle()
    val streak by vm.streak.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    var weightDialog by remember { mutableStateOf(false) }
    var measureDialog by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val label = Units.weightLabel(unit)

    Scaffold(topBar = { TopAppBar(title = { Text("Progress") }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("This week", style = MaterialTheme.typography.titleMedium)
                        Text("${summary.sessions} sessions - ${Units.fmt(Units.weightToDisplay(summary.volumeKg, unit))} $label lifted")
                        Text("Avg intake ${summary.avgKcal} kcal/day - ${summary.monthSessions} sessions in 30 days - streak $streak")
                    }
                }
            }
            item {
                ChartCard("Body weight ($label)", onAction = { weightDialog = true }, actionLabel = "Log weight") {
                    LineChart(weights.map { ChartPoint(it.date.toString().substring(5), Units.weightToDisplay(it.weightKg, unit)) }, description = "Body weight trend")
                    if (weights.isNotEmpty()) TextButton(onClick = { entries = "weight" }) { Text("Edit entries (${weights.size})") }
                }
            }
            item {
                ChartCard("Measurements (cm)", onAction = { measureDialog = true }, actionLabel = "Log") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MEASUREMENTS.forEach { m -> FilterChip(measureType == m, { vm.measureType.value = m }, { Text(m) }) }
                    }
                    LineChart(measurements.map { ChartPoint(it.date.toString().substring(5), it.valueCm) }, description = "$measureType trend")
                    if (measurements.isNotEmpty()) TextButton(onClick = { entries = "measure" }) { Text("Edit entries (${measurements.size})") }
                }
            }
            item {
                ChartCard("Strength: estimated 1RM ($label)") {
                    if (exercises.isEmpty()) Text("No exercises yet")
                    else {
                        var open by remember { mutableStateOf(false) }
                        val current = exercises.firstOrNull { it.id == (selected ?: exercises.first().id) } ?: exercises.first()
                        ExposedDropdownMenuBox(open, { open = it }) {
                            OutlinedTextField(current.name, {}, readOnly = true, label = { Text("Exercise") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                                modifier = Modifier.menuAnchor().fillMaxWidth())
                            ExposedDropdownMenu(open, { open = false }) {
                                exercises.forEach { e -> DropdownMenuItem(text = { Text(e.name) }, onClick = { vm.selectedExercise.value = e.id; open = false }) }
                            }
                        }
                        LineChart(strength.map { ChartPoint(it.first.toString().substring(5), Units.weightToDisplay(it.second, unit)) }, description = "Estimated one rep max for ${current.name}")
                    }
                }
            }
            item {
                ChartCard("Weekly volume ($label)") {
                    BarChart(volume.map { ChartPoint(it.first.toString().substring(5), Units.weightToDisplay(it.second, unit)) }, description = "Training volume by week")
                }
            }
            item {
                ChartCard("Calories: in vs. out (last 7 days)") {
                    BarChart(energy.map { ChartPoint(it.first.dayOfWeek.name.take(1), it.second) }, description = "Calories eaten per day")
                    Text("Estimated workout burn: " + energy.joinToString(", ") { it.third.toInt().toString() } + " kcal", style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                ChartCard("Muscle heatmap (sets this week)") {
                    if (heat.isEmpty()) Text("Train to see your weekly muscle coverage")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val max = (heat.maxOfOrNull { it.sets } ?: 1).coerceAtLeast(1)
                        heat.forEach { h ->
                            val alpha = if (h.sets == 0) 0f else 0.25f + 0.75f * h.sets / max
                            Surface(
                                color = if (h.sets == 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    "${h.muscle}: ${h.sets}" + if (h.sets == 0) " (neglected)" else "",
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
                                    color = if (h.sets == 0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    entries?.let { kind ->
        val rows: List<Triple<Long, String, String>> = if (kind == "weight") weights.reversed().map { Triple(it.id, it.date.toString(), Units.fmtWeight(it.weightKg, unit)) }
        else measurements.reversed().map { Triple(it.id, it.date.toString(), "${Units.fmt(it.valueCm)} cm") }
        AlertDialog(
            onDismissRequest = { entries = null },
            title = { Text(if (kind == "weight") "Weight entries" else "$measureType entries") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(rows.size) { i ->
                        val (id, date, value) = rows[i]
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("$date   $value", Modifier.weight(1f))
                            IconButton(onClick = { if (kind == "weight") vm.deleteWeight(id) else vm.deleteMeasurement(id) }) {
                                Icon(androidx.compose.material.icons.Icons.Filled.Delete, "Delete entry from $date")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { entries = null }) { Text("Done") } }
        )
    }
    if (weightDialog) ValueDialog("Log weight", label, onDismiss = { weightDialog = false }) { vm.logWeight(Units.weightFromDisplay(it, unit)) }
    if (measureDialog) ValueDialog("Log $measureType", "cm", onDismiss = { measureDialog = false }) { vm.logMeasurement(it) }
}

@Composable
private fun ChartCard(title: String, onAction: (() -> Unit)? = null, actionLabel: String = "", content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
            }
            content()
        }
    }
}

@Composable
private fun ValueDialog(title: String, suffix: String, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var v by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { NumField(v, { v = it }, "Value", Modifier.fillMaxWidth(), suffix = suffix) },
        confirmButton = { TextButton(enabled = (v.toDoubleOrNull() ?: 0.0) > 0, onClick = { onSave(v.toDouble()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
