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
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.healthvectors.fitness.core.designsystem.*
import com.healthvectors.fitness.core.util.Units
import java.time.format.DateTimeFormatter
import com.healthvectors.fitness.data.local.entity.FoodEntity
import com.healthvectors.fitness.data.local.entity.MealEntryEntity
import com.healthvectors.fitness.data.repository.NutritionRepository
import com.healthvectors.fitness.domain.logic.Macros
import com.healthvectors.fitness.domain.model.MealType
import com.healthvectors.fitness.domain.usecase.DailyTargets
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class NutritionUi(
    val date: LocalDate, val meals: List<MealEntryEntity>, val targets: Macros?, val note: String,
    /** The logged foods by id, to describe amounts in their own units ("1 medium", "200 g"). */
    val foods: Map<Long, FoodEntity> = emptyMap()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NutritionViewModel @Inject constructor(
    private val nutrition: NutritionRepository,
    private val targets: DailyTargets
) : ViewModel() {
    private val date = MutableStateFlow(LocalDate.now())

    val ui: StateFlow<NutritionUi> = date.flatMapLatest { d ->
        val withFoods = nutrition.meals(d).mapLatest { meals -> meals to nutrition.foods(meals.mapTo(HashSet()) { it.foodId }).associateBy { it.id } }
        combine(withFoods, targets.observe(d)) { (meals, foods), t -> NutritionUi(d, meals, t?.macros, t?.note.orEmpty(), foods) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NutritionUi(LocalDate.now(), emptyList(), null, ""))

    fun shift(days: Long) = date.update { it.plusDays(days).coerceAtMost(LocalDate.now()) }
    fun delete(id: Long) = viewModelScope.launch { nutrition.deleteMeal(id) }
}

private fun LocalDate.coerceAtMost(max: LocalDate) = if (isAfter(max)) max else this

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionScreen(onAddFood: (String, String) -> Unit, vm: NutritionViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val total = Macros(ui.meals.sumOf { it.kcal }.toInt(), ui.meals.sumOf { it.protein }.toInt(), ui.meals.sumOf { it.carbs }.toInt(), ui.meals.sumOf { it.fat }.toInt())
    val today = LocalDate.now()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Nutrition") },
            actions = {
                IconButton(onClick = { vm.shift(-1) }) { Icon(Icons.Filled.ChevronLeft, "Previous day") }
                Text(
                    when (ui.date) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> ui.date.format(DateTimeFormatter.ofPattern("EEE d MMM")) },
                    Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleSmall
                )
                IconButton(onClick = { vm.shift(1) }, enabled = ui.date.isBefore(today)) { Icon(Icons.Filled.ChevronRight, "Next day") }
            }
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard {
                    val t = ui.targets
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CalorieRing(total.kcal.toDouble(), t?.kcal?.toDouble() ?: 0.0, size = 124.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            MacroBar("Protein", total.proteinG.toDouble(), t?.proteinG?.toDouble() ?: 0.0)
                            MacroBar("Carbs", total.carbsG.toDouble(), t?.carbsG?.toDouble() ?: 0.0)
                            MacroBar("Fat", total.fatG.toDouble(), t?.fatG?.toDouble() ?: 0.0)
                        }
                    }
                    Text(
                        if (t == null) "Complete your profile to see your targets."
                        else "${Units.thousands(total.kcal)} of ${Units.thousands(t.kcal)} kcal eaten" + if (ui.note.isNotBlank()) " · ${ui.note}" else "",
                        Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            MealType.entries.forEach { type ->
                val entries = ui.meals.filter { it.mealType == type }
                val name = type.name.lowercase().replaceFirstChar(Char::uppercase)
                item(key = type.name) {
                    GroupCard {
                        Row(Modifier.padding(start = 16.dp, end = 20.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            IconBadge(mealIcon(type), size = 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (entries.isEmpty()) "Nothing logged yet" else "${entries.size} ${if (entries.size == 1) "item" else "items"}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("${entries.sumOf { it.kcal }.toInt()}", style = MaterialTheme.typography.titleMedium)
                            Text(" kcal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        entries.forEach { m ->
                            GroupDivider()
                            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.foodName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${amountLabel(ui.foods[m.foodId], m.servings)} · P ${m.protein.toInt()}  C ${m.carbs.toInt()}  F ${m.fat.toInt()}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text("${m.kcal.toInt()}", style = MaterialTheme.typography.bodyLarge)
                                IconButton(onClick = { vm.delete(m.id) }) {
                                    Icon(Icons.Outlined.Close, "Delete ${m.foodName}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        GroupDivider(0.dp)
                        TextButton(
                            onClick = { onAddFood(ui.date.toString(), type.name) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                                .semantics { contentDescription = "Add food to ${type.name.lowercase()}" }
                        ) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add food")
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun mealIcon(t: MealType) = when (t) {
    MealType.BREAKFAST -> Icons.Outlined.FreeBreakfast
    MealType.LUNCH -> Icons.Outlined.LunchDining
    MealType.DINNER -> Icons.Outlined.DinnerDining
    MealType.SNACK -> Icons.Outlined.Cookie
}
