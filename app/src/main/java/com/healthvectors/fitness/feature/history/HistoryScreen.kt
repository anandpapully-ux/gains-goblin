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
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.draw.clip
import com.healthvectors.fitness.core.designsystem.*
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
        } else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sessions, key = { it.id }) { s ->
                val date = Instant.ofEpochMilli(s.startedAt).atZone(zone).toLocalDate()
                val minutes = s.endedAt?.let { ((it - s.startedAt) / 60000).toInt() }
                val open = expanded == s.id
                AppCard(onClick = { vm.toggle(s.id) }, padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(
                            Modifier.size(52.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                        ) {
                            Text("${date.dayOfMonth}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(date.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(s.dayTitle ?: "Workout", style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    date.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase),
                                    minutes?.let { "$it min" }, "${s.setCount} sets",
                                    if (s.volumeKg > 0) "${Units.compact(Units.weightToDisplay(s.volumeKg, unit))} ${Units.weightLabel(unit)}" else null
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (s.prCount > 0) Pill("${s.prCount} PR", container = MaterialTheme.colorScheme.tertiary, content = MaterialTheme.colorScheme.onTertiary)
                        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Hide sets" else "Show sets", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (open) {
                        sets.groupBy { it.exerciseName }.forEach { (name, list) ->
                            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                            list.sortedBy { it.set.setIndex }.forEach { w ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${w.set.setIndex + 1}", Modifier.width(28.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val v = if (w.repUnit == "reps") "${Units.fmt(Units.weightToDisplay(w.set.weightKg, unit))} ${Units.weightLabel(unit)} × ${w.set.reps}"
                                    else "${w.set.reps} ${w.repUnit}"
                                    Text(v, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        listOfNotNull(w.set.rpe?.let { "RPE ${Units.fmt(it)}" }, if (w.set.isWarmup) "warm-up" else null).joinToString(" · ").let { if (it.isEmpty()) "" else "  ·  $it" },
                                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (w.set.isPr) Pill("PR", container = MaterialTheme.colorScheme.tertiary, content = MaterialTheme.colorScheme.onTertiary)
                                    IconButton(onClick = { vm.deleteSet(w.set.id) }, Modifier.size(36.dp)) {
                                        Icon(Icons.Filled.Close, "Delete set ${w.set.setIndex + 1} of $name", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { confirm = s }, Modifier.padding(top = 8.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Delete this workout")
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
