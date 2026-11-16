package com.healthvectors.fitness.feature.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.core.designsystem.ChoiceRow
import com.healthvectors.fitness.core.designsystem.EmptyState
import com.healthvectors.fitness.core.designsystem.NumField
import com.healthvectors.fitness.core.util.Units
import com.healthvectors.fitness.data.datastore.AppSettings
import com.healthvectors.fitness.data.datastore.SettingsStore
import com.healthvectors.fitness.data.local.dao.RoutineExerciseWithExercise
import com.healthvectors.fitness.data.local.entity.ExerciseEntity
import com.healthvectors.fitness.data.local.entity.RoutineDayEntity
import com.healthvectors.fitness.data.local.entity.RoutineExerciseEntity
import com.healthvectors.fitness.data.repository.ProfileRepository
import com.healthvectors.fitness.data.repository.RoutineRepository
import com.healthvectors.fitness.data.repository.WorkoutRepository
import com.healthvectors.fitness.domain.model.DayType
import com.healthvectors.fitness.domain.model.ProgressionRule
import com.healthvectors.fitness.domain.model.ScheduleMode
import com.healthvectors.fitness.domain.model.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

@HiltViewModel
class DayEditorViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val routines: RoutineRepository,
    private val workouts: WorkoutRepository,
    profiles: ProfileRepository,
    store: SettingsStore
) : ViewModel() {
    private fun <T> Flow<T>.state(init: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)
    val dayId: Long = checkNotNull(saved["dayId"])
    val day: StateFlow<RoutineDayEntity?> = routines.day(dayId).state(null)
    val items: StateFlow<List<RoutineExerciseWithExercise>> = routines.items(dayId).state(emptyList())
    val library: StateFlow<List<ExerciseEntity>> = routines.exercises.state(emptyList())
    val settings: StateFlow<AppSettings> = store.settings.state(AppSettings())
    val unit: StateFlow<UnitSystem> = profiles.profile.map { it?.unitSystem ?: UnitSystem.METRIC }.state(UnitSystem.METRIC)
    val weekDays: StateFlow<List<RoutineDayEntity>> = routines.days.state(emptyList())

    fun move(index: Int, delta: Int) = viewModelScope.launch {
        val list = items.value
        val j = index + delta
        if (j in list.indices) routines.swap(list[index].item, list[j].item)
    }
    fun remove(id: Long) = viewModelScope.launch { routines.remove(id) }
    fun saveItem(item: RoutineExerciseEntity) = viewModelScope.launch { routines.update(item) }
    fun setIncrement(e: ExerciseEntity, kg: Double?) = viewModelScope.launch { routines.setIncrement(e, kg) }
    fun replace(row: RoutineExerciseWithExercise, e: ExerciseEntity) = viewModelScope.launch { routines.replaceExercise(row.item, e) }
    fun add(e: ExerciseEntity) = viewModelScope.launch { routines.addToDay(dayId, e) }
    fun createCustom(name: String, muscle: String, equipment: String, rule: ProgressionRule, unilateral: Boolean, replacing: RoutineExerciseWithExercise?) =
        viewModelScope.launch {
            val e = routines.createCustomExercise(name, muscle, equipment, rule, unilateral)
            if (replacing != null) routines.replaceExercise(replacing.item, e) else routines.addToDay(dayId, e)
        }
    fun saveDay(title: String, type: DayType, minutes: Int, weekday: Int) = viewModelScope.launch {
        val d = day.value ?: return@launch
        val updated = d.copy(title = title.trim().ifBlank { d.title }, type = type, estimatedMinutes = minutes.coerceIn(0, 600))
        routines.updateDay(updated)
        routines.moveDay(updated, weekday)
    }
    fun start(onReady: (Long) -> Unit) = viewModelScope.launch { onReady(workouts.startOrResume(dayId)) }
}

private fun unitWord(repUnit: String) = if (repUnit == "reps") "reps" else repUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayEditorScreen(onBack: () -> Unit, onStart: (Long) -> Unit, vm: DayEditorViewModel = hiltViewModel()) {
    val day by vm.day.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()
    val weekDays by vm.weekDays.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RoutineExerciseWithExercise?>(null) }
    var picking by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<RoutineExerciseWithExercise?>(null) }
    var daySettings by remember { mutableStateOf(false) }
    val rotation = settings.scheduleMode == ScheduleMode.ROTATION

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(day?.title ?: "Day", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        day?.let { d ->
                            Text(
                                (if (rotation) "Position ${d.dayOfWeek}" else DayOfWeek.of(d.dayOfWeek).name.lowercase().replaceFirstChar(Char::uppercase)) +
                                    " - " + d.type.label(), style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { daySettings = true }) { Icon(Icons.Filled.Edit, "Edit day name, type and weekday") } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { replacing = null; picking = true },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add exercise") },
                modifier = Modifier.padding(bottom = 8.dp)
            )
        },
        bottomBar = {
            if (items.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = { vm.start(onStart) },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp)
                ) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start workout") }
            }
        }
    ) { pad ->
        if (items.isEmpty()) {
            Column(Modifier.padding(pad).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    Icons.Filled.FitnessCenter, "No exercises",
                    if (day?.type == DayType.REST) "This is a rest day. Add exercises, or tap the pencil to rename it or change its type."
                    else "Tap Add exercise to build this day."
                )
                OutlinedButton(onClick = { daySettings = true }) { Text("Edit day") }
            }
        } else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.item.id }) { row ->
                val idx = items.indexOf(row)
                ElevatedCard {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { editing = row }) {
                            Text(row.exercise.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${row.item.targetSets} x ${row.item.repMin}-${row.item.repMax} ${unitWord(row.exercise.repUnit)}" + if (row.item.perSide) " per side" else "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            val rest = if (row.item.restMaxSec > 0) "Rest ${row.item.restMinSec}-${row.item.restMaxSec}s" else "No rest timer"
                            val rir = if (row.exercise.repUnit == "reps") " - RIR ${row.item.rirMin}-${row.item.rirMax}" else ""
                            Text("$rest$rir - tap to edit", style = MaterialTheme.typography.labelSmall)
                        }
                        Column {
                            IconButton(onClick = { vm.move(idx, -1) }, enabled = idx > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move ${row.exercise.name} up") }
                            IconButton(onClick = { vm.move(idx, 1) }, enabled = idx < items.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Move ${row.exercise.name} down") }
                        }
                        IconButton(onClick = { vm.remove(row.item.id) }) { Icon(Icons.Filled.Delete, "Remove ${row.exercise.name}") }
                    }
                }
            }
        }
    }

    editing?.let { row ->
        ItemEditor(
            row = row, unit = unit,
            onSave = { item, incrementKg ->
                vm.saveItem(item)
                if (incrementKg != row.exercise.incrementKg) vm.setIncrement(row.exercise, incrementKg)
                editing = null
            },
            onReplace = { replacing = row; editing = null; picking = true },
            onDismiss = { editing = null }
        )
    }

    if (picking) ExercisePicker(
        library = library,
        title = replacing?.let { "Replace ${it.exercise.name}" } ?: "Add exercise",
        preferredMuscle = replacing?.exercise?.primaryMuscle,
        onPick = { e -> replacing?.let { vm.replace(it, e) } ?: vm.add(e); picking = false; replacing = null },
        onCreate = { n, m, eq, rule, uni -> vm.createCustom(n, m, eq, rule, uni, replacing); picking = false; replacing = null },
        onDismiss = { picking = false; replacing = null }
    )

    if (daySettings) day?.let { d ->
        DaySettingsDialog(d, rotation, weekDays, onDismiss = { daySettings = false }) { title, type, minutes, weekday ->
            vm.saveDay(title, type, minutes, weekday); daySettings = false
        }
    }
}

fun DayType.label() = when (this) {
    DayType.HYPERTROPHY -> "Strength"
    DayType.CARDIO -> "Cardio"
    DayType.REST -> "Rest"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DaySettingsDialog(
    day: RoutineDayEntity, rotation: Boolean, weekDays: List<RoutineDayEntity>,
    onDismiss: () -> Unit, onSave: (String, DayType, Int, Int) -> Unit
) {
    var title by remember { mutableStateOf(day.title) }
    var type by remember { mutableStateOf(day.type) }
    var minutes by remember { mutableStateOf(day.estimatedMinutes.toString()) }
    var weekday by remember { mutableStateOf(day.dayOfWeek) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit day") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Type", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(DayType.entries, type, { it.label() }) { type = it }
                NumField(minutes, { minutes = it }, "Estimated minutes", Modifier.fillMaxWidth(), decimal = false)
                Text(if (rotation) "Position in rotation" else "Weekday", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Moving swaps places with the day that is there now.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { wd ->
                        FilterChip(
                            selected = weekday == wd, onClick = { weekday = wd },
                            label = { Text(if (rotation) "$wd" else DayOfWeek.of(wd).name.take(3).lowercase().replaceFirstChar(Char::uppercase)) }
                        )
                    }
                }
                weekDays.firstOrNull { it.dayOfWeek == weekday && it.id != day.id }?.let {
                    Text("\"${it.title}\" will move to ${if (rotation) "position ${day.dayOfWeek}" else DayOfWeek.of(day.dayOfWeek).name.lowercase().replaceFirstChar(Char::uppercase)}.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, type, minutes.toIntOrNull() ?: 0, weekday) }, enabled = title.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ItemEditor(
    row: RoutineExerciseWithExercise, unit: UnitSystem,
    onSave: (RoutineExerciseEntity, Double?) -> Unit, onReplace: () -> Unit, onDismiss: () -> Unit
) {
    val i = row.item
    val e = row.exercise
    val reps = e.repUnit == "reps"
    var sets by remember { mutableStateOf(i.targetSets.toString()) }
    var min by remember { mutableStateOf(i.repMin.toString()) }
    var max by remember { mutableStateOf(i.repMax.toString()) }
    var restMin by remember { mutableStateOf(i.restMinSec.toString()) }
    var restMax by remember { mutableStateOf(i.restMaxSec.toString()) }
    var rirMin by remember { mutableStateOf(i.rirMin.toString()) }
    var rirMax by remember { mutableStateOf(i.rirMax.toString()) }
    var perSide by remember { mutableStateOf(i.perSide) }
    var notes by remember { mutableStateOf(i.notes) }
    var step by remember { mutableStateOf(e.incrementKg?.let { Units.fmt(Units.weightToDisplay(it, unit)) } ?: "") }
    val label = Units.weightLabel(unit)
    val defaultStep = Units.fmt(Units.weightToDisplay(Units.stepKg(unit, e.equipment), unit))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (e.equipment.isNotBlank()) Text(e.equipment, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NumField(sets, { sets = it }, "Sets", Modifier.fillMaxWidth(), decimal = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(min, { min = it }, "Min ${unitWord(e.repUnit)}", Modifier.weight(1f), decimal = false)
                    NumField(max, { max = it }, "Max ${unitWord(e.repUnit)}", Modifier.weight(1f), decimal = false)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(restMin, { restMin = it }, "Rest min", Modifier.weight(1f), decimal = false, suffix = "s")
                    NumField(restMax, { restMax = it }, "Rest max", Modifier.weight(1f), decimal = false, suffix = "s")
                }
                Text("The rest timer runs for the max rest; 0 turns it off.", style = MaterialTheme.typography.bodySmall)
                if (reps) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField(rirMin, { rirMin = it }, "RIR min", Modifier.weight(1f), decimal = false)
                        NumField(rirMax, { rirMax = it }, "RIR max", Modifier.weight(1f), decimal = false)
                    }
                    NumField(step, { step = it }, "Weight jump (default $defaultStep)", Modifier.fillMaxWidth(), suffix = label)
                    Text("How much to add when you hit the top of the range. Leave empty for the default.", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Counted per side", Modifier.weight(1f))
                    Switch(perSide, { perSide = it })
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes / cues") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                if (e.alternatives.isNotBlank()) Text("Alternatives: " + e.alternatives.replace("|", ", "), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onReplace, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.SwapHoriz, null); Spacer(Modifier.width(6.dp)); Text("Replace exercise")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val lo = min.toIntOrNull() ?: i.repMin
                val rLo = restMin.toIntOrNull() ?: i.restMinSec
                val rirLo = (rirMin.toIntOrNull() ?: i.rirMin).coerceIn(0, 10)
                onSave(
                    i.copy(
                        targetSets = (sets.toIntOrNull() ?: i.targetSets).coerceIn(1, 20),
                        repMin = lo, repMax = maxOf(lo, max.toIntOrNull() ?: i.repMax),
                        restMinSec = rLo, restMaxSec = maxOf(rLo, restMax.toIntOrNull() ?: i.restMaxSec),
                        rirMin = rirLo, rirMax = maxOf(rirLo, (rirMax.toIntOrNull() ?: i.rirMax).coerceIn(0, 10)),
                        perSide = perSide, notes = notes.trim()
                    ),
                    step.toDoubleOrNull()?.takeIf { it > 0 }?.let { Units.weightFromDisplay(it, unit) }
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private enum class ExerciseKind(val label: String, val rule: ProgressionRule) {
    COMPOUND("Compound", ProgressionRule.COMPOUND_DOUBLE_PROGRESSION),
    ISOLATION("Isolation", ProgressionRule.ISOLATION_HIGH_REP_PROGRESSION),
    TIMED("Timed", ProgressionRule.TIMED_CORE),
    CARDIO("Cardio", ProgressionRule.DURATION_DISTANCE)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ExercisePicker(
    library: List<ExerciseEntity>, title: String, preferredMuscle: String?,
    onPick: (ExerciseEntity) -> Unit,
    onCreate: (String, String, String, ProgressionRule, Boolean) -> Unit, onDismiss: () -> Unit
) {
    var q by remember { mutableStateOf("") }
    var muscle by remember { mutableStateOf(preferredMuscle) }
    var creating by remember { mutableStateOf(false) }
    // The muscle being replaced comes first so the active filter is visible without scrolling.
    val muscles = remember(library) { library.map { it.primaryMuscle }.distinct().sortedWith(compareBy({ it != preferredMuscle }, { it })) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (creating) "New exercise" else title, style = MaterialTheme.typography.titleMedium)
            if (creating) {
                var name by remember { mutableStateOf("") }
                var m by remember { mutableStateOf(preferredMuscle ?: "") }
                var equip by remember { mutableStateOf("") }
                var kind by remember { mutableStateOf(ExerciseKind.COMPOUND) }
                var unilateral by remember { mutableStateOf(false) }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(m, { m = it }, label = { Text("Main muscle") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(muscles) { mu -> SuggestionChip(onClick = { m = mu }, label = { Text(mu) }) }
                }
                OutlinedTextField(equip, { equip = it }, label = { Text("Equipment (e.g. Barbell, Cable Machine)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChoiceRow(ExerciseKind.entries, kind, { it.label }) { kind = it }
                Text(
                    when (kind) {
                        ExerciseKind.COMPOUND -> "Weight x reps, progresses in a moderate rep range."
                        ExerciseKind.ISOLATION -> "Weight x reps, higher rep ranges."
                        ExerciseKind.TIMED -> "Logged in seconds (planks, carries)."
                        ExerciseKind.CARDIO -> "Logged in minutes."
                    }, style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("One side at a time", Modifier.weight(1f)); Switch(unilateral, { unilateral = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { creating = false }, Modifier.weight(1f)) { Text("Back") }
                    Button(
                        onClick = { onCreate(name.trim(), m.trim().ifBlank { "Other" }, equip.trim(), kind.rule, unilateral) },
                        enabled = name.isNotBlank(), modifier = Modifier.weight(1f)
                    ) { Text("Create") }
                }
            } else {
                OutlinedTextField(q, { q = it }, label = { Text("Search ${library.size} exercises") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(muscle == null, { muscle = null }, { Text("All") }) }
                    items(muscles) { mu -> FilterChip(muscle == mu, { muscle = if (muscle == mu) null else mu }, { Text(mu) }) }
                }
                TextButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, null); Text("Create custom exercise") }
                val filtered = library.filter {
                    (muscle == null || it.primaryMuscle == muscle) &&
                        (q.isBlank() || it.name.contains(q, true) || it.primaryMuscle.contains(q, true) || it.equipment.contains(q, true))
                }
                if (filtered.isEmpty()) Text("No matches. Try another muscle or create your own.", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(filtered, key = { it.id }) { e ->
                        ListItem(
                            headlineContent = { Text(e.name) },
                            supportingContent = { Text("${e.primaryMuscle} - ${e.equipment.ifBlank { "Any" }}" + if (e.isCustom) " - custom" else "") },
                            modifier = Modifier.clickable { onPick(e) }
                        )
                    }
                }
            }
        }
    }
}
