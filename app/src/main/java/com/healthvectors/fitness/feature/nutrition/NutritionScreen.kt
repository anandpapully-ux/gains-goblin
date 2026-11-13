package com.healthvectors.fitness.feature.nutrition

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
import com.healthvectors.fitness.core.designsystem.CalorieRing
import com.healthvectors.fitness.core.designsystem.MacroBar
import com.healthvectors.fitness.data.local.entity.MealEntryEntity
import com.healthvectors.fitness.data.repository.NutritionRepository
import com.healthvectors.fitness.data.repository.ProfileRepository
import com.healthvectors.fitness.data.repository.RoutineRepository
import com.healthvectors.fitness.domain.logic.Macros
import com.healthvectors.fitness.domain.model.DayType
import com.healthvectors.fitness.domain.model.MealType
import com.healthvectors.fitness.domain.usecase.DailyTargets
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class NutritionUi(val date: LocalDate, val meals: List<MealEntryEntity>, val targets: Macros?, val dayType: DayType)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NutritionViewModel @Inject constructor(
    private val nutrition: NutritionRepository,
    private val profiles: ProfileRepository,
    private val routines: RoutineRepository,
    private val targets: DailyTargets
) : ViewModel() {
    private val date = MutableStateFlow(LocalDate.now())

    val ui: StateFlow<NutritionUi> = date.flatMapLatest { d ->
        combine(nutrition.meals(d), profiles.profile, profiles.latestWeight, routines.dayOfWeek(d.dayOfWeek.value)) { meals, p, w, day ->
            val type = day?.type ?: DayType.REST
            NutritionUi(d, meals, if (p != null && w != null) targets.forDay(p, w.weightKg, type, d) else null, type)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NutritionUi(LocalDate.now(), emptyList(), null, DayType.REST))

    fun shift(days: Long) = date.update { it.plusDays(days).coerceAtMost(LocalDate.now()) }
    fun delete(id: Long) = viewModelScope.launch { nutrition.deleteMeal(id) }
}

private fun LocalDate.coerceAtMost(max: LocalDate) = if (isAfter(max)) max else this

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionScreen(onAddFood: (String, String) -> Unit, vm: NutritionViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val total = Macros(ui.meals.sumOf { it.kcal }.toInt(), ui.meals.sumOf { it.protein }.toInt(), ui.meals.sumOf { it.carbs }.toInt(), ui.meals.sumOf { it.fat }.toInt())

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Nutrition") },
            actions = {
                IconButton(onClick = { vm.shift(-1) }) { Icon(Icons.Filled.ChevronLeft, "Previous day") }
                Text(if (ui.date == LocalDate.now()) "Today" else ui.date.toString(), Modifier.align(Alignment.CenterVertically))
                IconButton(onClick = { vm.shift(1) }, enabled = ui.date.isBefore(LocalDate.now())) { Icon(Icons.Filled.ChevronRight, "Next day") }
            }
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val t = ui.targets
                        CalorieRing(total.kcal.toDouble(), t?.kcal?.toDouble() ?: 0.0)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MacroBar("Protein", total.proteinG.toDouble(), t?.proteinG?.toDouble() ?: 0.0)
                            MacroBar("Carbs", total.carbsG.toDouble(), t?.carbsG?.toDouble() ?: 0.0)
                            MacroBar("Fat", total.fatG.toDouble(), t?.fatG?.toDouble() ?: 0.0)
                            Text("${ui.dayType.name.lowercase()} day target", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            MealType.entries.forEach { type ->
                val entries = ui.meals.filter { it.mealType == type }
                item(key = type.name) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(type.name.lowercase().replaceFirstChar(Char::uppercase), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Text("${entries.sumOf { it.kcal }.toInt()} kcal", style = MaterialTheme.typography.labelLarge)
                                IconButton(onClick = { onAddFood(ui.date.toString(), type.name) }) { Icon(Icons.Filled.AddCircle, "Add food to ${type.name.lowercase()}") }
                            }
                            if (entries.isEmpty()) Text("Nothing logged", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            entries.forEach { m ->
                                ListItem(
                                    headlineContent = { Text(m.foodName) },
                                    supportingContent = { Text("${com.healthvectors.fitness.core.util.Units.fmt(m.servings)} serving - P ${m.protein.toInt()} C ${m.carbs.toInt()} F ${m.fat.toInt()}") },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("${m.kcal.toInt()}")
                                            IconButton(onClick = { vm.delete(m.id) }) { Icon(Icons.Filled.Delete, "Delete ${m.foodName}") }
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
