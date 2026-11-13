package com.healthvectors.fitness.feature.workout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.core.designsystem.EmptyState
import com.healthvectors.fitness.core.designsystem.NumField
import com.healthvectors.fitness.data.local.entity.RoutineDayEntity
import com.healthvectors.fitness.data.repository.RoutineRepository
import com.healthvectors.fitness.domain.logic.PlateCalculator
import com.healthvectors.fitness.domain.logic.WarmupGenerator
import com.healthvectors.fitness.domain.model.DayType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class WorkoutViewModel @Inject constructor(routines: RoutineRepository) : ViewModel() {
    val days: StateFlow<List<RoutineDayEntity>> =
        routines.days.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(onOpenDay: (Long) -> Unit, vm: WorkoutViewModel = hiltViewModel()) {
    val days by vm.days.collectAsStateWithLifecycle()
    var tool by remember { mutableStateOf<String?>(null) }
    val todayDow = LocalDate.now().dayOfWeek.value

    Scaffold(topBar = { TopAppBar(title = { Text("Weekly plan") }) }) { pad ->
        if (days.isEmpty()) {
            EmptyState(Icons.Filled.FitnessCenter, "No routine yet", "Your plan is being set up...", Modifier.padding(pad))
        } else {
            LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(days, key = { it.id }) { d ->
                    val isToday = d.dayOfWeek == todayDow
                    ElevatedCard(
                        onClick = { if (d.type != DayType.REST) onOpenDay(d.id) },
                        colors = if (isToday) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else CardDefaults.elevatedCardColors()
                    ) {
                        ListItem(
                            headlineContent = { Text(d.title) },
                            overlineContent = { Text(DayOfWeek.of(d.dayOfWeek).name.lowercase().replaceFirstChar(Char::uppercase) + if (isToday) " - today" else "") },
                            supportingContent = { Text(if (d.type == DayType.REST) "Rest" else "${d.type.name.lowercase()} - ~${d.estimatedMinutes} min") },
                            trailingContent = { if (d.type != DayType.REST) Icon(Icons.Filled.ChevronRight, "Open ${d.title}") },
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

@Composable
fun PlateDialog(initialKg: Double?, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf(initialKg?.let { com.healthvectors.fitness.core.util.Units.fmt(it) } ?: "") }
    var bar by remember { mutableStateOf("20") }
    val t = target.toDoubleOrNull()
    val b = bar.toDoubleOrNull() ?: 20.0
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Plate calculator (kg)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(target, { target = it }, "Target total", Modifier.fillMaxWidth(), suffix = "kg")
                NumField(bar, { bar = it }, "Bar weight", Modifier.fillMaxWidth(), suffix = "kg")
                if (t != null && t >= b) {
                    val r = PlateCalculator.platesPerSide(t, b)
                    Text(
                        if (r.platesPerSide.isEmpty()) "Just the bar." else "Per side: " + r.platesPerSide.joinToString(" + ") { com.healthvectors.fitness.core.util.Units.fmt(it) },
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (r.remainderKg > 0) Text("Closest loadable is ${com.healthvectors.fitness.core.util.Units.fmt(t - r.remainderKg)} kg")
                } else Text("Enter a target at or above the bar weight.")
            }
        }
    )
}

@Composable
fun WarmupDialog(initialKg: Double?, onDismiss: () -> Unit) {
    var w by remember { mutableStateOf(initialKg?.let { com.healthvectors.fitness.core.util.Units.fmt(it) } ?: "") }
    val kg = w.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Warm-up sets (kg)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(w, { w = it }, "Working weight", Modifier.fillMaxWidth(), suffix = "kg")
                val sets = if (kg != null) WarmupGenerator.generate(kg) else emptyList()
                if (sets.isEmpty()) Text("No warm-up needed for very light loads.")
                sets.forEach { Text("${com.healthvectors.fitness.core.util.Units.fmt(it.weightKg)} kg x ${it.reps}") }
            }
        }
    )
}
