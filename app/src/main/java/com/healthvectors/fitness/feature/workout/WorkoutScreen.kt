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
import com.healthvectors.fitness.core.designsystem.EmptyState
import com.healthvectors.fitness.core.designsystem.NumField
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
            title = { Column { Text(plan?.name ?: "Workout", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); Text(if (rotation) "Rotation: next day in order" else "Weekly plan", style = MaterialTheme.typography.labelMedium) } },
            actions = {
                IconButton(onClick = onOpenHistory) { Icon(Icons.Filled.History, "Workout history") }
                IconButton(onClick = onOpenPlans) { Icon(Icons.Filled.ListAlt, "My plans") }
            }
        )
    }) { pad ->
        if (days.isEmpty()) {
            Column(Modifier.padding(pad).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(Icons.Filled.FitnessCenter, "No plan yet", "Pick a starter plan, import one from a friend, or build your own.")
                Button(onClick = onOpenPlans) { Text("Choose a plan") }
            }
        } else {
            LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(days, key = { it.id }) { d ->
                    val highlight = if (rotation) d.id == nextId else d.dayOfWeek == todayDow
                    val rest = d.type == DayType.REST
                    val label = when {
                        rotation && rest -> "Rest (skipped in rotation)"
                        rotation -> "Day ${trainingOrder.indexOf(d.id) + 1}" + if (highlight) " - next up" else ""
                        else -> DayOfWeek.of(d.dayOfWeek).name.lowercase().replaceFirstChar(Char::uppercase) + if (highlight) " - today" else ""
                    }
                    ElevatedCard(
                        onClick = { onOpenDay(d.id) },
                        colors = if (highlight) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else CardDefaults.elevatedCardColors()
                    ) {
                        ListItem(
                            headlineContent = { Text(d.title) },
                            overlineContent = { Text(label) },
                            supportingContent = {
                                Text(
                                    when (d.type) {
                                        DayType.REST -> "Rest - tap to edit"
                                        DayType.CARDIO -> "Cardio" + if (d.estimatedMinutes > 0) " - ~${d.estimatedMinutes} min" else ""
                                        DayType.HYPERTROPHY -> "Strength" + if (d.estimatedMinutes > 0) " - ~${d.estimatedMinutes} min" else ""
                                    }
                                )
                            },
                            trailingContent = { Icon(Icons.Filled.ChevronRight, "Open ${d.title}") },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                        )
                    }
                }
                item {
                    Text("Tools", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { tool = "plate" }) { Text("Plate calculator") }
                        OutlinedButton(onClick = { tool = "warmup" }) { Text("Warm-up sets") }
                    }
                }
            }
        }
    }
    if (tool == "plate") PlateDialog(null) { tool = null }
    if (tool == "warmup") WarmupDialog(null) { tool = null }
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
@Composable
fun PlateDialog(initialKg: Double?, vm: GymToolsViewModel = hiltViewModel(), onDismiss: () -> Unit) {
    val unit by vm.unit.collectAsStateWithLifecycle()
    val barKg by vm.barKg.collectAsStateWithLifecycle()
    val label = Units.weightLabel(unit)
    var target by remember(unit) { mutableStateOf(initialKg?.let { Units.fmt(Units.weightToDisplay(it, unit)) } ?: "") }
    var bar by remember(unit, barKg) { mutableStateOf(Units.fmt(barKg?.let { Units.weightToDisplay(it, unit) } ?: Units.defaultBar(unit))) }
    val t = target.toDoubleOrNull()
    val b = bar.toDoubleOrNull() ?: Units.defaultBar(unit)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Plate calculator ($label)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(target, { target = it }, "Target total", Modifier.fillMaxWidth(), suffix = label)
                NumField(bar, { v ->
                    bar = v
                    v.toDoubleOrNull()?.takeIf { it > 0 }?.let { vm.saveBar(Units.weightFromDisplay(it, unit)) }
                }, "Bar weight (saved)", Modifier.fillMaxWidth(), suffix = label)
                if (t != null && t >= b) {
                    val r = PlateCalculator.platesPerSide(t, b, Units.defaultPlates(unit))
                    Text(
                        if (r.platesPerSide.isEmpty()) "Just the bar." else "Per side: " + r.platesPerSide.joinToString(" + ") { Units.fmt(it) },
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (r.remainderKg > 0) Text("Closest loadable is ${Units.fmt(t - r.remainderKg)} $label")
                } else Text("Enter a target at or above the bar weight.")
            }
        }
    )
}

/** [initialKg] is the working weight in kg; the warm-up is shown in the user's units. */
@Composable
fun WarmupDialog(initialKg: Double?, vm: GymToolsViewModel = hiltViewModel(), onDismiss: () -> Unit) {
    val unit by vm.unit.collectAsStateWithLifecycle()
    val label = Units.weightLabel(unit)
    var w by remember(unit) { mutableStateOf(initialKg?.let { Units.fmt(Units.weightToDisplay(it, unit)) } ?: "") }
    val value = w.toDoubleOrNull()
    val step = if (unit == UnitSystem.METRIC) 2.0 else 5.0
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Warm-up sets ($label)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(w, { w = it }, "Working weight", Modifier.fillMaxWidth(), suffix = label)
                val sets = if (value != null) WarmupGenerator.generate(value, step) else emptyList()
                if (sets.isEmpty()) Text("No warm-up needed for very light loads.")
                sets.forEach { Text("${Units.fmt(it.weightKg)} $label x ${it.reps}") }
            }
        }
    )
}
