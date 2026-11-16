package com.healthvectors.fitness.feature.history

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.core.designsystem.EmptyState
import com.healthvectors.fitness.core.util.Units
import com.healthvectors.fitness.data.local.dao.SessionRow
import com.healthvectors.fitness.data.local.dao.SetWithName
import com.healthvectors.fitness.data.repository.ProfileRepository
import com.healthvectors.fitness.data.repository.WorkoutRepository
import com.healthvectors.fitness.domain.model.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(private val workouts: WorkoutRepository, profiles: ProfileRepository) : ViewModel() {
    private fun <T> Flow<T>.state(init: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)
    val sessions: StateFlow<List<SessionRow>> = workouts.history.state(emptyList())
    val unit: StateFlow<UnitSystem> = profiles.profile.map { it?.unitSystem ?: UnitSystem.METRIC }.state(UnitSystem.METRIC)
    val expanded = MutableStateFlow<Long?>(null)
    val sets: StateFlow<List<SetWithName>> = expanded.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else workouts.sessionSets(id) }.state(emptyList())

    fun toggle(id: Long) = expanded.update { if (it == id) null else id }
    fun deleteSession(id: Long) = viewModelScope.launch { workouts.deleteSession(id); expanded.value = null }
    fun deleteSet(id: Long) = viewModelScope.launch { workouts.deleteSet(id) }
}

private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()
    val expanded by vm.expanded.collectAsStateWithLifecycle()
    val sets by vm.sets.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<SessionRow?>(null) }
    val zone = ZoneId.systemDefault()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Workout history") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        if (sessions.isEmpty()) {
            EmptyState(Icons.Filled.History, "No workouts yet", "Finished workouts show up here. Tap one to see or fix its sets.", Modifier.padding(pad))
        } else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { s ->
                val date = Instant.ofEpochMilli(s.startedAt).atZone(zone).toLocalDate()
                val minutes = s.endedAt?.let { ((it - s.startedAt) / 60000).toInt() }
                ElevatedCard(onClick = { vm.toggle(s.id) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(date.format(dateFmt) + " - " + date.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelLarge)
                                Text(s.dayTitle ?: "Workout", style = MaterialTheme.typography.titleMedium)
                            }
                            Icon(if (expanded == s.id) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded == s.id) "Hide sets" else "Show sets")
                        }
                        Text(
                            listOfNotNull(
                                minutes?.let { "$it min" }, "${s.setCount} sets",
                                if (s.volumeKg > 0) "${Units.fmt(Units.weightToDisplay(s.volumeKg, unit))} ${Units.weightLabel(unit)} volume" else null,
                                if (s.prCount > 0) "${s.prCount} PR" else null
                            ).joinToString(" - "),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (expanded == s.id) {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            sets.groupBy { it.exerciseName }.forEach { (name, list) ->
                                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                                list.sortedBy { it.set.setIndex }.forEach { w ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val v = if (w.repUnit == "reps") "${Units.fmt(Units.weightToDisplay(w.set.weightKg, unit))} ${Units.weightLabel(unit)} x ${w.set.reps}"
                                        else "${w.set.reps} ${w.repUnit}"
                                        Text(
                                            "Set ${w.set.setIndex + 1}: $v" + (w.set.rpe?.let { " @ RPE ${Units.fmt(it)}" } ?: "") +
                                                (if (w.set.isWarmup) " (warm-up)" else "") + if (w.set.isPr) "  PR" else "",
                                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
                                        )
                                        IconButton(onClick = { vm.deleteSet(w.set.id) }) { Icon(Icons.Filled.Close, "Delete set ${w.set.setIndex + 1} of $name") }
                                    }
                                }
                            }
                            TextButton(onClick = { confirm = s }) { Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete this workout") }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { s ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete this workout?") },
            text = { Text("All ${s.setCount} sets are removed from your history, charts and records.") },
            confirmButton = { TextButton(onClick = { vm.deleteSession(s.id); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}
