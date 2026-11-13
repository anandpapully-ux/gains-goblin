package com.healthvectors.fitness.feature.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.core.designsystem.EmptyState
import com.healthvectors.fitness.core.designsystem.NumField
import com.healthvectors.fitness.data.local.dao.RoutineExerciseWithExercise
import com.healthvectors.fitness.data.local.entity.ExerciseEntity
import com.healthvectors.fitness.data.local.entity.RoutineDayEntity
import com.healthvectors.fitness.data.repository.RoutineRepository
import com.healthvectors.fitness.data.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DayEditorViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val routines: RoutineRepository,
    private val workouts: WorkoutRepository
) : ViewModel() {
    val dayId: Long = checkNotNull(saved["dayId"])
    val day: StateFlow<RoutineDayEntity?> = routines.day(dayId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val items: StateFlow<List<RoutineExerciseWithExercise>> =
        routines.items(dayId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val library: StateFlow<List<ExerciseEntity>> =
        routines.exercises.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun move(index: Int, delta: Int) = viewModelScope.launch {
        val list = items.value
        val j = index + delta
        if (j in list.indices) routines.swap(list[index].item, list[j].item)
    }
    fun remove(id: Long) = viewModelScope.launch { routines.remove(id) }
    fun updateTargets(row: RoutineExerciseWithExercise, sets: Int, min: Int, max: Int) = viewModelScope.launch {
        routines.update(row.item.copy(targetSets = sets.coerceIn(1, 10), repMin = min, repMax = maxOf(min, max)))
    }
    fun add(e: ExerciseEntity) = viewModelScope.launch { routines.addToDay(dayId, e) }
    fun createCustom(name: String, muscle: String, equipment: String) = viewModelScope.launch {
        val e = routines.createCustomExercise(name, muscle, equipment)
        routines.addToDay(dayId, e)
    }
    fun start(onReady: (Long) -> Unit) = viewModelScope.launch { onReady(workouts.startOrResume(dayId)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayEditorScreen(onBack: () -> Unit, onStart: (Long) -> Unit, vm: DayEditorViewModel = hiltViewModel()) {
    val day by vm.day.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RoutineExerciseWithExercise?>(null) }
    var picking by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(day?.title ?: "Day", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { picking = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add exercise") })
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = { vm.start(onStart) }, enabled = items.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)
                ) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start workout") }
            }
        }
    ) { pad ->
        if (items.isEmpty()) {
            EmptyState(Icons.Filled.FitnessCenter, "No exercises", "Tap Add exercise to build this day.", Modifier.padding(pad))
        } else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.item.id }) { row ->
                val idx = items.indexOf(row)
                ElevatedCard {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable_(onClick = { editing = row })) {
                            Text(row.exercise.name, style = MaterialTheme.typography.titleMedium)
                            val unit = if (row.exercise.repUnit == "reps") "reps" else row.exercise.repUnit
                            Text("${row.item.targetSets} x ${row.item.repMin}-${row.item.repMax} $unit" + if (row.item.perSide) " per side" else "",
                                style = MaterialTheme.typography.bodyMedium)
                            Text("Rest ${row.item.restMinSec}-${row.item.restMaxSec}s - RIR ${row.item.rirMin}-${row.item.rirMax}",
                                style = MaterialTheme.typography.labelSmall)
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
        var sets by remember(row) { mutableStateOf(row.item.targetSets.toString()) }
        var min by remember(row) { mutableStateOf(row.item.repMin.toString()) }
        var max by remember(row) { mutableStateOf(row.item.repMax.toString()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(row.exercise.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(sets, { sets = it }, "Sets", Modifier.fillMaxWidth(), decimal = false)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField(min, { min = it }, "Min", Modifier.weight(1f), decimal = false)
                        NumField(max, { max = it }, "Max", Modifier.weight(1f), decimal = false)
                    }
                    if (row.exercise.alternatives.isNotBlank()) Text("Alternatives: " + row.exercise.alternatives.replace("|", ", "))
                    if (row.item.notes.isNotBlank()) Text(row.item.notes, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateTargets(row, sets.toIntOrNull() ?: row.item.targetSets, min.toIntOrNull() ?: row.item.repMin, max.toIntOrNull() ?: row.item.repMax)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }

    if (picking) ExercisePicker(library, onPick = { vm.add(it); picking = false }, onCreate = { n, m, e -> vm.createCustom(n, m, e); picking = false }, onDismiss = { picking = false })
}

private fun Modifier.clickable_(onClick: () -> Unit) = this.then(Modifier.clickable(onClick = onClick))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExercisePicker(
    library: List<ExerciseEntity>, onPick: (ExerciseEntity) -> Unit,
    onCreate: (String, String, String) -> Unit, onDismiss: () -> Unit
) {
    var q by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (creating) {
                var name by remember { mutableStateOf("") }
                var muscle by remember { mutableStateOf("") }
                var equip by remember { mutableStateOf("") }
                Text("New exercise", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(muscle, { muscle = it }, label = { Text("Primary muscle") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(equip, { equip = it }, label = { Text("Equipment") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onCreate(name.trim(), muscle.trim().ifBlank { "Other" }, equip.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Create and add") }
            } else {
                OutlinedTextField(q, { q = it }, label = { Text("Search exercises") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { creating = true }) { Text("Create custom exercise") }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(library.filter { q.isBlank() || it.name.contains(q, true) || it.primaryMuscle.contains(q, true) }, key = { it.id }) { e ->
                        ListItem(
                            headlineContent = { Text(e.name) },
                            supportingContent = { Text("${e.primaryMuscle} - ${e.equipment}") },
                            modifier = Modifier.clickable { onPick(e) }
                        )
                    }
                }
            }
        }
    }
}
