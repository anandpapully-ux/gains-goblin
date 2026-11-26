package com.healthvectors.fitness.feature.workout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.healthvectors.fitness.core.designsystem.*
import com.healthvectors.fitness.core.util.Units
import com.healthvectors.fitness.data.datastore.AppSettings
import com.healthvectors.fitness.data.datastore.SettingsStore
import com.healthvectors.fitness.data.local.entity.RoutineDayEntity
import com.healthvectors.fitness.data.local.entity.RoutineEntity
import com.healthvectors.fitness.data.repository.ProfileRepository
import com.healthvectors.fitness.data.repository.RoutineRepository
import com.healthvectors.fitness.data.repository.WorkoutRepository
import com.healthvectors.fitness.domain.logic.Schedule
import com.healthvectors.fitness.domain.model.ScheduleMode
import com.healthvectors.fitness.domain.model.UnitSystem
import com.healthvectors.fitness.domain.logic.PlateCalculator
import com.healthvectors.fitness.domain.logic.WarmupGenerator
import com.healthvectors.fitness.domain.model.DayType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkoutViewModel @Inject constructor(routines: RoutineRepository, workouts: WorkoutRepository, settings: SettingsStore) : ViewModel() {
    private fun <T> Flow<T>.state(init: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)
    val days: StateFlow<List<RoutineDayEntity>> = routines.days.state(emptyList())
    val plan: StateFlow<RoutineEntity?> = routines.activeRoutine.state(null)
    val settings: StateFlow<AppSettings> = settings.settings.state(AppSettings())
    /** Next day in rotation mode (highlighted instead of "today"). */
    val nextId: StateFlow<Long?> = combine(routines.days, workouts.lastDoneDay) { days, last ->
        Schedule.nextInRotation(days.map { Schedule.Slot(it.id, it.dayOfWeek, it.type) }, last?.id)?.id
    }.state(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    onOpenDay: (Long) -> Unit, onOpenPlans: () -> Unit, onOpenHistory: () -> Unit,
    vm: WorkoutViewModel = hiltViewModel()
) {
    val days by vm.days.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nextId by vm.nextId.collectAsStateWithLifecycle()
    var tool by remember { mutableStateOf<String?>(null) }
    val todayDow = LocalDate.now().dayOfWeek.value
    val rotation = settings.scheduleMode == ScheduleMode.ROTATION
    val trainingOrder = days.filter { it.type != DayType.REST }.map { it.id }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(plan?.name ?: "Workout", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (days.isNotEmpty()) Text(
                        "${trainingOrder.size} training days · " + if (rotation) "rotation" else "fixed weekdays",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            actions = {
                IconButton(onClick = onOpenHistory) { Icon(Icons.Filled.History, "Workout history") }
                IconButton(onClick = onOpenPlans) { Icon(Icons.AutoMirrored.Filled.ListAlt, "My plans") }
            }
        )
    }) { pad ->
        if (days.isEmpty()) {
            EmptyState(
                Icons.Filled.FitnessCenter, "No plan yet", "Pick a starter plan, import one from a friend, or build your own.",
                Modifier.padding(pad)
            ) { Button(onClick = onOpenPlans) { Text("Choose a plan") } }
        } else {
            LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                item { SectionTitle(if (rotation) "Rotation order" else "Your week", Modifier.padding(top = 0.dp)) }
                itemsIndexed(days, key = { _, d -> d.id }) { i, d ->
                    val highlight = if (rotation) d.id == nextId else d.dayOfWeek == todayDow
                    GroupItem(i, days.size, dividerInset = 76.dp) { DayRow(d, rotation, highlight, trainingOrder.indexOf(d.id) + 1) { onOpenDay(d.id) } }
                }
                item { SectionTitle("Tools") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionTile(Icons.Outlined.Calculate, "Plate calculator", "What to load on each side", Modifier.weight(1f)) { tool = "plate" }
                        ActionTile(Icons.Outlined.Whatshot, "Warm-up sets", "Ramp up to your working weight", Modifier.weight(1f)) { tool = "warmup" }
                    }
                }
            }
        }
    }
    if (tool == "plate") PlateDialog(null) { tool = null }
    if (tool == "warmup") WarmupDialog(null) { tool = null }
}

@Composable
private fun DayRow(d: RoutineDayEntity, rotation: Boolean, highlight: Boolean, position: Int, onClick: () -> Unit) {
    val rest = d.type == DayType.REST
    val (container, content) = when {
        highlight -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        rest -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
        d.type == DayType.CARDIO -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
            Text(
                if (rotation) (if (rest) "–" else "$position") else DayOfWeek.of(d.dayOfWeek).name.take(3).lowercase().replaceFirstChar(Char::uppercase),
                style = MaterialTheme.typography.labelLarge, color = content
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (highlight) Pill(if (rotation) "Next" else "Today")
            }
            Text(
                when (d.type) {
                    DayType.REST -> if (rotation) "Rest · skipped in rotation" else "Rest day"
                    DayType.CARDIO -> "Cardio" + if (d.estimatedMinutes > 0) " · about ${d.estimatedMinutes} min" else ""
                    DayType.HYPERTROPHY -> "Strength" + if (d.estimatedMinutes > 0) " · about ${d.estimatedMinutes} min" else ""
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(Icons.Filled.ChevronRight, "Open ${d.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shared state for the calculators: the user's unit system and bar weight. */
@HiltViewModel
class GymToolsViewModel @Inject constructor(profiles: ProfileRepository, private val store: SettingsStore) : ViewModel() {
    val unit: StateFlow<UnitSystem> = profiles.profile.map { it?.unitSystem ?: UnitSystem.METRIC }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UnitSystem.METRIC)
    val barKg: StateFlow<Double?> = store.settings.map { it.barKg }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun saveBar(kg: Double?) = viewModelScope.launch { store.setBarKg(kg) }
}

/** [initialKg] is the target in kg; everything on screen is in the user's units. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlateDialog(initialKg: Double?, vm: GymToolsViewModel = hiltViewModel(), onDismiss: () -> Unit) {
    val unit by vm.unit.collectAsStateWithLifecycle()
    val barKg by vm.barKg.collectAsStateWithLifecycle()
    val label = Units.weightLabel(unit)
    var target by remember(unit) { mutableStateOf(initialKg?.let { Units.fmt(Units.weightToDisplay(it, unit)) } ?: "") }
    var bar by remember(unit, barKg) { mutableStateOf(Units.fmt(barKg?.let { Units.weightToDisplay(it, unit) } ?: Units.defaultBar(unit))) }
    val t = target.toDoubleOrNull()
    val b = bar.toDoubleOrNull() ?: Units.defaultBar(unit)
    FormSheet("Plate calculator", onDismiss, primaryLabel = "Done", onPrimary = onDismiss, subtitle = "What to put on each side of the bar.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(target, { target = it }, "Total weight", Modifier.weight(1f), suffix = label)
            NumField(bar, { v ->
                bar = v
                v.toDoubleOrNull()?.takeIf { it > 0 }?.let { vm.saveBar(Units.weightFromDisplay(it, unit)) }
            }, "Bar (saved)", Modifier.weight(1f), suffix = label)
        }
        ResultCard {
            if (t != null && t >= b) {
                val r = PlateCalculator.platesPerSide(t, b, Units.defaultPlates(unit))
                Text("Each side", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.platesPerSide.isEmpty()) Text("Just the bar", style = MaterialTheme.typography.titleLarge)
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.platesPerSide.forEach { Pill("${Units.fmt(it)} $label", container = MaterialTheme.colorScheme.primary) }
                }
                if (r.remainderKg > 0) Text("Closest you can load: ${Units.fmt(t - r.remainderKg)} $label", style = MaterialTheme.typography.bodySmall)
            } else Text("Enter a total at or above the bar weight.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [initialKg] is the working weight in kg; the warm-up is shown in the user's units. */
@Composable
fun WarmupDialog(initialKg: Double?, vm: GymToolsViewModel = hiltViewModel(), onDismiss: () -> Unit) {
    val unit by vm.unit.collectAsStateWithLifecycle()
    val label = Units.weightLabel(unit)
    var w by remember(unit) { mutableStateOf(initialKg?.let { Units.fmt(Units.weightToDisplay(it, unit)) } ?: "") }
    val value = w.toDoubleOrNull()
    val step = if (unit == UnitSystem.METRIC) 2.0 else 5.0
    FormSheet("Warm-up sets", onDismiss, primaryLabel = "Done", onPrimary = onDismiss, subtitle = "Lighter sets that ramp up to your first working set.") {
        NumField(w, { w = it }, "Working weight", Modifier.fillMaxWidth(), suffix = label)
        val sets = if (value != null) WarmupGenerator.generate(value, step) else emptyList()
        ResultCard {
            if (value == null) Text("Enter your working weight.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (sets.isEmpty()) Text("No warm-up needed for very light loads.", style = MaterialTheme.typography.bodyMedium)
            sets.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Set ${i + 1}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${Units.fmt(s.weightKg)} $label × ${s.reps}", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
