package com.healthvectors.fitness.feature.nutrition

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.healthvectors.fitness.core.designsystem.NumField
import com.healthvectors.fitness.data.local.entity.FoodEntity
import com.healthvectors.fitness.data.remote.OpenFoodFacts
import com.healthvectors.fitness.data.repository.NutritionRepository
import com.healthvectors.fitness.domain.model.FoodSource
import com.healthvectors.fitness.domain.model.MealType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class AddFoodUi(
    val local: List<FoodEntity> = emptyList(), val online: List<FoodEntity> = emptyList(),
    val searching: Boolean = false, val message: String? = null
)

@HiltViewModel
class AddFoodViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val nutrition: NutritionRepository,
    private val off: OpenFoodFacts
) : ViewModel() {
    private val date = LocalDate.parse(checkNotNull(saved["date"]))
    private val meal = MealType.valueOf(checkNotNull(saved["meal"]))

    val title = meal.name.lowercase().replaceFirstChar(Char::uppercase)
    val favorites = nutrition.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recents = nutrition.recents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ui = MutableStateFlow(AddFoodUi())
    val ui: StateFlow<AddFoodUi> = _ui

    fun searchLocal(q: String) = viewModelScope.launch {
        _ui.update { it.copy(local = if (q.isBlank()) emptyList() else nutrition.search(q.trim()), message = null) }
    }

    fun searchOnline(q: String) = viewModelScope.launch {
        if (q.isBlank()) return@launch
        _ui.update { it.copy(searching = true, message = null) }
        val r = off.search(q.trim())
        _ui.update { it.copy(online = r, searching = false, message = if (r.isEmpty()) "No online results (or you are offline)" else null) }
    }

    fun barcode(code: String, onFound: (FoodEntity) -> Unit) = viewModelScope.launch {
        _ui.update { it.copy(searching = true, message = null) }
        val f = off.byBarcode(code)
        _ui.update { it.copy(searching = false, message = if (f == null) "Barcode $code not found (or you are offline)" else null) }
        f?.let(onFound)
    }

    /** Saves the food if new, logs the serving count to the meal, then reports completion. */
    fun log(food: FoodEntity, servings: Double, onDone: () -> Unit) = viewModelScope.launch {
        val id = if (food.id != 0L) food.id else nutrition.saveFood(food)
        nutrition.logMeal(id, servings, meal, date)
        onDone()
    }

    fun createCustom(f: FoodEntity, servings: Double, onDone: () -> Unit) = viewModelScope.launch {
        val id = nutrition.saveFood(f)
        nutrition.logMeal(id, servings, meal, date)
        onDone()
    }

    fun toggleFavorite(f: FoodEntity) = viewModelScope.launch {
        val id = if (f.id != 0L) f.id else nutrition.saveFood(f)
        nutrition.setFavorite(id, !f.isFavorite)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodScreen(onBack: () -> Unit, vm: AddFoodViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<FoodEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Add to ${vm.title}") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(
                    q, { q = it; vm.searchLocal(it) }, Modifier.fillMaxWidth(),
                    label = { Text("Search foods") }, singleLine = true,
                    trailingIcon = { IconButton(onClick = { vm.searchOnline(q) }) { Icon(Icons.Filled.Search, "Search Open Food Facts online") } }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilledTonalButton(onClick = { scan(ctx) { code -> vm.barcode(code) { selected = it } } }) {
                        Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text("Scan barcode")
                    }
                    OutlinedButton(onClick = { creating = true }) { Text("Custom food") }
                }
                if (ui.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                ui.message?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
            }
            if (q.isBlank()) {
                if (favorites.isNotEmpty()) { item { Text("Favorites", style = MaterialTheme.typography.titleSmall) }; items(favorites, key = { "f" + it.id }) { FoodRow(it, { selected = it }, vm) } }
                if (recents.isNotEmpty()) { item { Text("Recent", style = MaterialTheme.typography.titleSmall) }; items(recents, key = { "r" + it.id }) { FoodRow(it, { selected = it }, vm) } }
                if (favorites.isEmpty() && recents.isEmpty()) item { Text("Search, scan a barcode, or create a custom food. Recent and favorite foods appear here for 1-tap logging.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(ui.local, key = { "l" + it.id }) { FoodRow(it, { selected = it }, vm) }
                if (ui.online.isNotEmpty()) item { Text("Open Food Facts", style = MaterialTheme.typography.titleSmall) }
                items(ui.online) { FoodRow(it, { selected = it }, vm) }
            }
        }
    }

    selected?.let { f ->
        var servings by remember(f) { mutableStateOf("1") }
        val s = servings.toDoubleOrNull() ?: 0.0
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(f.name, maxLines = 2) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1 serving = ${com.healthvectors.fitness.core.util.Units.fmt(f.servingSize)} ${f.servingUnit}")
                    NumField(servings, { servings = it }, "Servings", Modifier.fillMaxWidth())
                    Text("${(f.kcal * s).toInt()} kcal - P ${(f.protein * s).toInt()} C ${(f.carbs * s).toInt()} F ${(f.fat * s).toInt()}")
                }
            },
            confirmButton = { TextButton(onClick = { vm.log(f, s, onBack) }, enabled = s > 0) { Text("Log") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } }
        )
    }
    if (creating) CustomFoodDialog(onDismiss = { creating = false }, onSave = { f, s -> vm.createCustom(f, s, onBack) })
}

private fun scan(ctx: Context, onCode: (String) -> Unit) {
    val opts = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E).build()
    GmsBarcodeScanning.getClient(ctx, opts).startScan()
        .addOnSuccessListener { b -> b.rawValue?.let(onCode) }
        .addOnFailureListener {
            android.widget.Toast.makeText(ctx, "Scanner unavailable (needs Google Play services)", android.widget.Toast.LENGTH_LONG).show()
        }
}

@Composable
private fun FoodRow(f: FoodEntity, onClick: () -> Unit, vm: AddFoodViewModel) {
    ListItem(
        headlineContent = { Text(f.name, maxLines = 1) },
        supportingContent = { Text("${f.kcal.toInt()} kcal / ${com.healthvectors.fitness.core.util.Units.fmt(f.servingSize)} ${f.servingUnit}" + (f.brand?.let { " - $it" } ?: "")) },
        trailingContent = {
            IconButton(onClick = { vm.toggleFavorite(f) }) {
                Icon(if (f.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder, if (f.isFavorite) "Remove ${f.name} from favorites" else "Favorite ${f.name}")
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun CustomFoodDialog(onDismiss: () -> Unit, onSave: (FoodEntity, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom food (per serving)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                NumField(kcal, { kcal = it }, "Calories", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField(p, { p = it }, "Protein", Modifier.weight(1f))
                    NumField(c, { c = it }, "Carbs", Modifier.weight(1f))
                    NumField(fat, { fat = it }, "Fat", Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && kcal.toDoubleOrNull() != null,
                onClick = {
                    onSave(
                        FoodEntity(name = name.trim(), servingSize = 1.0, servingUnit = "serving", kcal = kcal.toDouble(),
                            protein = p.toDoubleOrNull() ?: 0.0, carbs = c.toDoubleOrNull() ?: 0.0, fat = fat.toDoubleOrNull() ?: 0.0,
                            source = FoodSource.CUSTOM), 1.0
                    )
                }
            ) { Text("Save and log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
