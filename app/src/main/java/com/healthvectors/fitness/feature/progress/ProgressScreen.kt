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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import java.time.format.DateTimeFormatter
import com.healthvectors.fitness.data.local.entity.ProgressPhotoEntity
import com.healthvectors.fitness.data.photos.PhotoRepository
import com.healthvectors.fitness.domain.logic.PhotoLogic
import com.healthvectors.fitness.feature.photos.PhotoTile
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
    private val routines: RoutineRepository,
    photoRepo: PhotoRepository
) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    private fun millis(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    private val today = LocalDate.now()
    private val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private fun <T> Flow<T>.state(init: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)

    /** Newest first; null until loaded. */
    val photos = photoRepo.photos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
fun ProgressScreen(onPhotos: (add: Boolean) -> Unit, onPhoto: (Long) -> Unit, onCompare: () -> Unit, vm: ProgressViewModel = hiltViewModel()) {
    val photos by vm.photos.collectAsStateWithLifecycle()
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

    val dm = DateTimeFormatter.ofPattern("d MMM")
    Scaffold(topBar = { TopAppBar(title = { Text("Progress") }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("${summary.sessions}", "Workouts this week", Modifier.weight(1f), icon = Icons.Outlined.FitnessCenter)
                        StatTile("$streak", "Session streak", Modifier.weight(1f), icon = Icons.Outlined.LocalFireDepartment, tint = MaterialTheme.colorScheme.tertiary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("${Units.compact(Units.weightToDisplay(summary.volumeKg, unit))} $label", "Lifted this week", Modifier.weight(1f), icon = Icons.Outlined.BarChart)
                        StatTile(Units.thousands(summary.avgKcal), "Avg kcal a day", Modifier.weight(1f), icon = Icons.Outlined.Restaurant, tint = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            item { PhotosCard(photos, onPhotos, onPhoto, onCompare) }
            item {
                val latest = weights.lastOrNull()
                val change = if (weights.size >= 2) Units.weightToDisplay(weights.last().weightKg - weights.first().weightKg, unit) else null
                ChartCard(
                    "Body weight",
                    headline = latest?.let { Units.fmtWeight(it.weightKg, unit) },
                    detail = change?.let { (if (it > 0) "+" else "") + "${Units.fmt(it)} $label since ${weights.first().date.format(dm)}" },
                    action = "Log", onAction = { weightDialog = true }
                ) {
                    LineChart(weights.map { ChartPoint(it.date.format(dm), Units.weightToDisplay(it.weightKg, unit)) }, description = "Body weight trend",
                        emptyText = "Log your weight a couple of times to see the trend")
                    if (weights.isNotEmpty()) TextButton(onClick = { entries = "weight" }, contentPadding = PaddingValues(0.dp)) { Text("Edit entries (${weights.size})") }
                }
            }
            item {
                ChartCard(
                    "Measurements", headline = measurements.lastOrNull()?.let { "${Units.fmt(it.valueCm)} cm" },
                    detail = measurements.lastOrNull()?.let { "$measureType · ${it.date.format(dm)}" },
                    action = "Log", onAction = { measureDialog = true }
                ) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MEASUREMENTS.forEach { m -> FilterChip(measureType == m, { vm.measureType.value = m }, { Text(m) }) }
                    }
                    LineChart(measurements.map { ChartPoint(it.date.format(dm), it.valueCm) }, description = "$measureType trend",
                        emptyText = "Log your ${measureType.lowercase()} a couple of times to see the trend")
                    if (measurements.isNotEmpty()) TextButton(onClick = { entries = "measure" }, contentPadding = PaddingValues(0.dp)) { Text("Edit entries (${measurements.size})") }
                }
            }
            item {
                ChartCard(
                    "Strength", headline = strength.lastOrNull()?.let { Units.fmtWeight(it.second, unit) },
                    detail = if (strength.isNotEmpty()) "Estimated one-rep max" else null
                ) {
                    if (exercises.isEmpty()) Text("Finish a workout to track your lifts.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        var open by remember { mutableStateOf(false) }
                        val current = exercises.firstOrNull { it.id == (selected ?: exercises.first().id) } ?: exercises.first()
                        ExposedDropdownMenuBox(open, { open = it }) {
                            OutlinedTextField(current.name, {}, readOnly = true, label = { Text("Exercise") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                            ExposedDropdownMenu(open, { open = false }) {
                                exercises.forEach { e -> DropdownMenuItem(text = { Text(e.name) }, onClick = { vm.selectedExercise.value = e.id; open = false }) }
                            }
                        }
                        LineChart(strength.map { ChartPoint(it.first.format(dm), Units.weightToDisplay(it.second, unit)) }, description = "Estimated one rep max for ${current.name}",
                            emptyText = "Do this exercise in two workouts to see your trend")
                    }
                }
            }
            item {
                ChartCard("Weekly volume", headline = volume.lastOrNull()?.let { "${Units.compact(Units.weightToDisplay(it.second, unit))} $label" }, detail = "This week, weight × reps") {
                    BarChart(volume.map { ChartPoint(it.first.format(dm), Units.weightToDisplay(it.second, unit)) }, description = "Training volume by week")
                }
            }
            item {
                val burn = energy.sumOf { it.third }.toInt()
                ChartCard("Calories eaten", headline = energy.lastOrNull()?.let { "${Units.thousands(it.second.toInt())} kcal" }, detail = "Today · last 7 days below") {
                    BarChart(energy.map { ChartPoint(it.first.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase), it.second) }, description = "Calories eaten per day")
                    if (burn > 0) Text("Workouts burned about ${Units.thousands(burn)} kcal this week", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                ChartCard("Muscles trained", detail = "Sets per muscle this week") {
                    if (heat.isEmpty()) Text("Train to see which muscles you've worked this week.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val max = (heat.maxOfOrNull { it.sets } ?: 1).coerceAtLeast(1)
                        heat.forEach { h ->
                            val alpha = if (h.sets == 0) 0f else 0.2f + 0.8f * h.sets / max
                            val strong = alpha > 0.55f
                            Surface(
                                color = if (h.sets == 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    val fg = when { h.sets == 0 -> MaterialTheme.colorScheme.onErrorContainer; strong -> MaterialTheme.colorScheme.onPrimary; else -> MaterialTheme.colorScheme.onSurface }
                                    Text(h.muscle, style = MaterialTheme.typography.labelLarge, color = fg)
                                    Text(if (h.sets == 0) "Not trained" else "${h.sets} sets", style = MaterialTheme.typography.labelSmall, color = fg)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    entries?.let { kind ->
        val rows: List<Triple<Long, String, String>> = if (kind == "weight") weights.reversed().map { Triple(it.id, it.date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")), Units.fmtWeight(it.weightKg, unit)) }
        else measurements.reversed().map { Triple(it.id, it.date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")), "${Units.fmt(it.valueCm)} cm") }
        AlertDialog(
            onDismissRequest = { entries = null },
            title = { Text(if (kind == "weight") "Weight entries" else "$measureType entries") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(rows.size) { i ->
                        val (id, date, value) = rows[i]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(date, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { if (kind == "weight") vm.deleteWeight(id) else vm.deleteMeasurement(id) }) {
                                Icon(Icons.Outlined.Delete, "Delete entry from $date", Modifier.size(20.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { entries = null }) { Text("Done") } }
        )
    }
    if (weightDialog) ValueDialog("Log weight", label, onDismiss = { weightDialog = false }) { vm.logWeight(Units.weightFromDisplay(it, unit)) }
    if (measureDialog) ValueDialog("Log ${measureType.lowercase()}", "cm", onDismiss = { measureDialog = false }) { vm.logMeasurement(it) }
}

@Composable
private fun PhotosCard(photos: List<ProgressPhotoEntity>?, onPhotos: (Boolean) -> Unit, onPhoto: (Long) -> Unit, onCompare: () -> Unit) {
    val list = photos ?: return
    val shots = list.map { PhotoLogic.Shot(it.id, it.takenOn, it.pose, it.weightKg) }
    val today = LocalDate.now()
    val since = PhotoLogic.daysSinceLast(shots, today)
    ChartCard(
        "Progress photos",
        headline = if (list.isEmpty()) null else "${list.size} ${if (list.size == 1) "photo" else "photos"}",
        detail = when (since) { null -> "See the changes the scale can't show"; 0L -> "Last one today"; 1L -> "Last one yesterday"; else -> "Last one $since days ago" },
        action = "Add", onAction = { onPhotos(true) }
    ) {
        if (list.isEmpty()) {
            Text(
                "Front, side and back photos every couple of weeks, compared side by side. Encrypted on this phone and never in your gallery.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                list.take(4).forEach { p -> PhotoTile(p, Modifier.weight(1f), showPose = false) { onPhoto(p.id) } }
                repeat(4 - list.take(4).size) { Spacer(Modifier.weight(1f)) }
            }
            if (PhotoLogic.shouldNudge(shots, today)) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text("It's been $since days. Time for a new set?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onPhotos(false) }, Modifier.weight(1f)) { Text("See all") }
                OutlinedButton(onClick = onCompare, Modifier.weight(1f), enabled = PhotoLogic.defaultPair(shots) != null) {
                    Icon(Icons.Outlined.Compare, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Compare")
                }
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String, headline: String? = null, detail: String? = null,
    action: String? = null, onAction: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit
) {
    AppCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (headline != null) Text(headline, style = MaterialTheme.typography.headlineSmall)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (action != null && onAction != null) FilledTonalButton(onClick = onAction) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(action)
            }
        }
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ValueDialog(title: String, suffix: String, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var v by remember { mutableStateOf("") }
    FormSheet(
        title, onDismiss, primaryLabel = "Save", primaryEnabled = (v.toDoubleOrNull() ?: 0.0) > 0,
        onPrimary = { onSave(v.toDouble()); onDismiss() }, subtitle = "Logged for today."
    ) { NumField(v, { v = it }, "Value", Modifier.fillMaxWidth(), suffix = suffix) }
}
