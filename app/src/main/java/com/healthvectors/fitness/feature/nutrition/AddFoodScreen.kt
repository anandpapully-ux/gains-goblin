package com.healthvectors.fitness.feature.nutrition

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.healthvectors.fitness.core.designsystem.ActionTile
import com.healthvectors.fitness.core.designsystem.ChoiceRow
import com.healthvectors.fitness.core.designsystem.GroupItem
import com.healthvectors.fitness.core.designsystem.MacroColors
import com.healthvectors.fitness.core.designsystem.SectionTitle
import com.healthvectors.fitness.core.designsystem.NumField
import com.healthvectors.fitness.core.util.Units
import com.healthvectors.fitness.data.catalog.FoodCatalog
import com.healthvectors.fitness.data.catalog.FoodIndex
import com.healthvectors.fitness.data.local.entity.FoodEntity
import com.healthvectors.fitness.data.remote.OpenFoodFacts
import com.healthvectors.fitness.data.repository.NutritionRepository
import com.healthvectors.fitness.domain.model.FoodSource
import com.healthvectors.fitness.domain.model.MealType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

data class AddFoodUi(
    val local: List<FoodEntity> = emptyList(), val catalog: List<FoodEntity> = emptyList(),
    val online: List<FoodEntity> = emptyList(),
    /** The query Open Food Facts was last searched for (null: not searched since the query changed). */
    val onlineQuery: String? = null,
    val searching: Boolean = false
)

sealed interface AddFoodEvent {
    data class Added(val entryId: Long, val text: String) : AddFoodEvent
    data class Message(val text: String) : AddFoodEvent
}

@HiltViewModel
class AddFoodViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val nutrition: NutritionRepository,
    private val catalog: FoodCatalog,
    private val off: OpenFoodFacts
) : ViewModel() {
    val date: LocalDate = LocalDate.parse(checkNotNull(saved["date"]))
    private val meal = MealType.valueOf(checkNotNull(saved["meal"]))

    val title = meal.name.lowercase().replaceFirstChar(Char::uppercase)
    val favorites = nutrition.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recents = nutrition.recents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** What is already logged to this meal on this day, for the header. */
    val logged = nutrition.meals(date).map { l -> l.filter { it.mealType == meal } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ui = MutableStateFlow(AddFoodUi())
    val ui: StateFlow<AddFoodUi> = _ui
    private val _events = MutableSharedFlow<AddFoodEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AddFoodEvent> = _events

    private var query = ""
    private var searchJob: Job? = null

    /** Your saved foods plus the bundled food database. Runs on every keystroke, so a newer query cancels the last. */
    fun searchLocal(q: String) {
        if (q.trim() != query.trim()) _ui.update { it.copy(online = emptyList(), onlineQuery = null) }
        query = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (q.isBlank()) { _ui.update { it.copy(local = emptyList(), catalog = emptyList()) }; return@launch }
            val local = nutrition.search(q.trim())
            val saved = local.mapTo(HashSet()) { it.source to it.name }
            val db = catalog.search(q).filter { (it.source to it.name) !in saved }
            _ui.update { it.copy(local = local, catalog = db) }
        }
    }

    fun searchOnline() = viewModelScope.launch {
        val q = query.trim()
        if (q.isEmpty()) return@launch
        _ui.update { it.copy(searching = true) }
        val r = off.search(q)
        _ui.update { if (query.trim() == q) it.copy(online = r, onlineQuery = q, searching = false) else it.copy(searching = false) }
    }

    fun barcode(code: String, onFound: (FoodEntity) -> Unit) = viewModelScope.launch {
        _ui.update { it.copy(searching = true) }
        val f = off.byBarcode(code)
        _ui.update { it.copy(searching = false) }
        if (f == null) _events.emit(AddFoodEvent.Message("Barcode $code not found (or you are offline)")) else onFound(f)
    }

    /** Saves the food if new and logs it to the meal. The screen stays open so several foods can be added. */
    fun log(food: FoodEntity, servings: Double) = viewModelScope.launch {
        val id = if (food.id != 0L) food.id else nutrition.saveFood(food)
        val entry = nutrition.logMeal(id, servings, meal, date) ?: return@launch
        _events.emit(AddFoodEvent.Added(entry, "Added ${food.name} · ${amountLabel(food, servings)}"))
    }

    fun undo(entryId: Long) = viewModelScope.launch { nutrition.deleteMeal(entryId) }

    fun createCustom(f: FoodEntity) = viewModelScope.launch {
        val id = nutrition.saveFood(f)
        val entry = nutrition.logMeal(id, 1.0, meal, date) ?: return@launch
        _events.emit(AddFoodEvent.Added(entry, "Added ${f.name}"))
        searchLocal(query)
    }

    fun toggleFavorite(f: FoodEntity, onDone: (FoodEntity) -> Unit = {}) = viewModelScope.launch {
        val id = if (f.id != 0L) f.id else nutrition.saveFood(f)
        nutrition.setFavorite(id, !f.isFavorite)
        // Search results are a snapshot, so re-run the search to show the new star (and a database food as saved).
        searchLocal(query)
        _ui.update { s -> s.copy(online = s.online.map { if (it.barcode != null && it.barcode == f.barcode) it.copy(isFavorite = !f.isFavorite) else it }) }
        onDone(f.copy(id = id, isFavorite = !f.isFavorite))
    }
}

private val SUGGESTIONS = listOf("Roti", "Dal", "Rice", "Idli", "Egg", "Paneer", "Banana", "Milk")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddFoodScreen(onBack: () -> Unit, vm: AddFoodViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val logged by vm.logged.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<FoodEntity?>(null) }
    var creating by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (q.isNotBlank()) vm.searchLocal(q) }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            snackbar.currentSnackbarData?.dismiss()
            scope.launch {
                when (e) {
                    is AddFoodEvent.Added ->
                        if (snackbar.showSnackbar(e.text, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) vm.undo(e.entryId)
                    is AddFoodEvent.Message -> snackbar.showSnackbar(e.text)
                }
            }
        }
    }
    fun search(text: String) { q = text; vm.searchLocal(text) }
    val open: (FoodEntity) -> Unit = { focus.clearFocus(); selected = it }
    val quickAdd: (FoodEntity) -> Unit = { vm.log(it, 1.0) } // one serving (100 g for foods measured per 100 g)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Add to ${vm.title}")
                        val kcal = logged.sumOf { it.kcal }.toInt() // truncated, like the Nutrition screen
                        val day = dayLabel(vm.date)
                        Text(
                            if (logged.isEmpty()) day else "$day · ${logged.size} ${if (logged.size == 1) "item" else "items"} · $kcal kcal",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (logged.isNotEmpty()) TextButton(onClick = onBack) { Text("Done") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            SearchField(q, ::search, onSearch = { focus.clearFocus() }, Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp))
            if (ui.searching && q.isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
                if (q.isBlank()) {
                    item(key = "actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionTile(Icons.Outlined.QrCodeScanner, "Scan barcode", "Packaged food", Modifier.weight(1f)) {
                                scan(ctx) { code -> vm.barcode(code) { selected = it } }
                            }
                            ActionTile(Icons.Outlined.EditNote, "Create food", "From a label or recipe", Modifier.weight(1f)) { creating = "" }
                        }
                    }
                    val favIds = favorites.mapTo(HashSet()) { it.id }
                    val recent = recents.filter { it.id !in favIds }
                    if (favorites.isNotEmpty()) { header("Favorites"); foodGroup("f", favorites, "", open, quickAdd) }
                    if (recent.isNotEmpty()) { header("Recent"); foodGroup("r", recent, "", open, quickAdd) }
                    if (favorites.isEmpty() && recent.isEmpty()) item(key = "welcome") {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 40.dp, start = 8.dp, end = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.RestaurantMenu, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Text("What did you eat?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                            Text(
                                "Search over 1,000 Indian dishes and everyday foods. It works offline, and foods you log show up here for one-tap adding.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                            )
                            FlowRow(
                                Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) { SUGGESTIONS.forEach { s -> SuggestionChip(onClick = { search(s) }, label = { Text(s) }) } }
                        }
                    }
                } else {
                    val term = q.trim()
                    if (ui.local.isNotEmpty()) { header("Your foods"); foodGroup("l", ui.local, term, open, quickAdd) }
                    if (ui.catalog.isNotEmpty()) { header("Food database"); foodGroup("c", ui.catalog, term, open, quickAdd) }
                    if (ui.online.isNotEmpty()) { header("Packaged foods"); foodGroup("o", ui.online, term, open, quickAdd) }
                    val nothing = ui.local.isEmpty() && ui.catalog.isEmpty() && ui.online.isEmpty()
                    if (nothing) item(key = "none") {
                        Text(
                            "No matches for “$term”", Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center
                        )
                    }
                    item(key = "online") {
                        val searched = ui.onlineQuery == term
                        OnlineCard(
                            searching = ui.searching, searched = searched, found = ui.online.size,
                            onSearch = { focus.clearFocus(); vm.searchOnline() }, modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                    item(key = "create") {
                        TextButton(onClick = { creating = term }, Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Create “$term” as a new food")
                        }
                    }
                    if (ui.catalog.isNotEmpty()) item(key = "credit") {
                        Text(
                            DATA_CREDIT, Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    selected?.let { f ->
        LogSheet(
            f, vm.title,
            onFavorite = { vm.toggleFavorite(f) { updated -> if (selected?.name == updated.name) selected = updated } },
            onAdd = { servings -> vm.log(f, servings); selected = null },
            onDismiss = { selected = null }
        )
    }
    creating?.let { name ->
        CreateFoodSheet(name, vm.title, onDismiss = { creating = null }, onSave = { vm.createCustom(it); creating = null })
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Search list

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value, onChange, modifier.fillMaxWidth(),
        placeholder = { Text("Search foods, e.g. dal, roti, banana") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear search") } },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
        )
    )
}

private fun LazyListScope.header(text: String) = item(key = "h$text") { SectionTitle(text) }

/** Foods as one rounded group with dividers, the way settings lists read. */
private fun LazyListScope.foodGroup(
    prefix: String, foods: List<FoodEntity>, query: String, onOpen: (FoodEntity) -> Unit, onQuickAdd: (FoodEntity) -> Unit
) = itemsIndexed(foods, key = { i, f -> prefix + if (prefix == "o") i.toString() else if (f.id != 0L) f.id.toString() else f.source.name + f.name }) { i, f ->
    GroupItem(i, foods.size) { FoodRow(f, query, { onOpen(f) }, { onQuickAdd(f) }) }
}

@Composable
private fun FoodRow(f: FoodEntity, query: String, onOpen: () -> Unit, onQuickAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (f.isFavorite) Icon(Icons.Filled.Star, "Favorite", Modifier.size(16.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text(highlight(f.name, query), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(servingLabel(f), f.brand).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "P ${grams(f.protein)}  ·  C ${grams(f.carbs)}  ·  F ${grams(f.fat)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("${f.kcal.toInt()}", style = MaterialTheme.typography.titleMedium)
            Text("kcal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton(onClick = onQuickAdd, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Add, "Add ${amountLabel(f, 1.0)} ${f.name}")
        }
    }
}

@Composable
private fun OnlineCard(searching: Boolean, searched: Boolean, found: Int, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val (title, subtitle) = when {
        searching -> "Searching Open Food Facts…" to "Packaged foods from around the world"
        searched && found == 0 -> "Nothing found online" to "No packaged foods matched, or you are offline"
        searched -> "Showing $found packaged ${if (found == 1) "food" else "foods"}" to "From Open Food Facts"
        else -> "Search packaged foods online" to "Brands and barcodes from Open Food Facts · needs internet"
    }
    OutlinedCard(onClick = onSearch, enabled = !searching && !searched, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (searching) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
            else Icon(Icons.Outlined.TravelExplore, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!searching && !searched) Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Bolds the parts of [name] that match words of the query. */
@Composable
private fun highlight(name: String, query: String): AnnotatedString {
    val words = FoodIndex.normalize(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return AnnotatedString(name)
    val lower = name.lowercase()
    val marks = BooleanArray(name.length)
    for (w in words) {
        var i = lower.indexOf(w)
        while (i >= 0) {
            if (i == 0 || !lower[i - 1].isLetterOrDigit()) { for (k in i until (i + w.length).coerceAtMost(name.length)) marks[k] = true; break }
            i = lower.indexOf(w, i + 1)
        }
    }
    val strong = SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    return buildAnnotatedString {
        name.forEachIndexed { k, ch -> if (marks[k]) withStyle(strong) { append(ch) } else append(ch) }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Log sheet

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LogSheet(f: FoodEntity, mealTitle: String, onFavorite: () -> Unit, onAdd: (Double) -> Unit, onDismiss: () -> Unit) {
    val gramsPer = gramsPerServing(f)
    val key = f.source.name + f.name
    var inGrams by remember(key) { mutableStateOf(f.servingUnit == "g") }
    var amount by remember(key) { mutableStateOf(if (f.servingUnit == "g") Units.fmt(f.servingSize) else "1") }
    val typed = amount.toDoubleOrNull() ?: 0.0
    val servings = if (inGrams && gramsPer != null) typed / gramsPer else typed

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(f.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        listOfNotNull("1 serving = ${servingLabel(f)}", f.brand).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onFavorite) {
                    if (f.isFavorite) Icon(Icons.Filled.Star, "Remove from favorites", tint = MaterialTheme.colorScheme.tertiary)
                    else Icon(Icons.Filled.StarBorder, "Add to favorites")
                }
            }

            NutritionCard(f.kcal * servings, f.protein * servings, f.carbs * servings, f.fat * servings)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (gramsPer != null && f.servingUnit != "g") ChoiceRow(listOf(false, true), inGrams, { if (it) "Grams" else "Servings" }) { g ->
                    if (g != inGrams) {
                        amount = if (g) Units.fmt((servings * gramsPer).roundToInt().toDouble()) else Units.fmt((servings * 4).roundToInt() / 4.0)
                        inGrams = g
                    }
                }
                val step = if (inGrams) 10.0 else 0.5
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalIconButton(onClick = { amount = Units.fmt(max(0.0, typed - step)) }, enabled = typed > 0, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Remove, "Less")
                    }
                    NumField(amount, { amount = it }, if (inGrams) "Grams" else "Servings", Modifier.weight(1f), suffix = if (inGrams) "g" else null)
                    FilledTonalIconButton(onClick = { amount = Units.fmt(typed + step) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Add, "More")
                    }
                }
                val presets = if (inGrams) listOf(50.0, 100.0, 150.0, 200.0) else listOf(0.5, 1.0, 1.5, 2.0)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { p ->
                        FilterChip(
                            selected = typed == p, onClick = { amount = Units.fmt(p) },
                            label = { Text(if (inGrams) "${Units.fmt(p)} g" else fraction(p)) }
                        )
                    }
                }
                if (!inGrams && gramsPer != null && servings > 0)
                    Text("≈ ${(servings * gramsPer).roundToInt()} g", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(onClick = { onAdd(servings) }, enabled = servings > 0, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Add to $mealTitle")
            }
        }
    }
}

@Composable
private fun NutritionCard(kcal: Double, protein: Double, carbs: Double, fat: Double) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.1f)) {
                Text("${kcal.toInt()}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Text("calories", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MacroStat("Protein", protein, MacroColors.protein, Modifier.weight(1f))
            MacroStat("Carbs", carbs, MacroColors.carbs, Modifier.weight(1f))
            MacroStat("Fat", fat, MacroColors.fat, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MacroStat(name: String, g: Double, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(grams(g), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(4.dp))
            Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Create food sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateFoodSheet(initialName: String, mealTitle: String, onDismiss: () -> Unit, onSave: (FoodEntity) -> Unit) {
    var name by remember { mutableStateOf(initialName.replaceFirstChar(Char::uppercase)) }
    var serving by remember { mutableStateOf("1 serving") }
    var kcal by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && kcal.toDoubleOrNull() != null && serving.isNotBlank()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Create food", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Copy the numbers from a label or work them out for your recipe. It's saved to your foods for next time.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                serving, { serving = it }, Modifier.fillMaxWidth(), label = { Text("Serving") }, singleLine = true,
                supportingText = { Text("For example 1 plate, 1 cup or 100 g. The numbers below are for this amount.") }
            )
            NumField(kcal, { kcal = it }, "Calories", Modifier.fillMaxWidth(), suffix = "kcal")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(p, { p = it }, "Protein", Modifier.weight(1f), suffix = "g")
                NumField(c, { c = it }, "Carbs", Modifier.weight(1f), suffix = "g")
                NumField(fat, { fat = it }, "Fat", Modifier.weight(1f), suffix = "g")
            }
            Button(
                enabled = valid, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
                onClick = {
                    val (size, unit) = parseServing(serving)
                    onSave(
                        FoodEntity(
                            name = name.trim(), servingSize = size, servingUnit = unit, kcal = kcal.toDouble(),
                            protein = p.toDoubleOrNull() ?: 0.0, carbs = c.toDoubleOrNull() ?: 0.0, fat = fat.toDoubleOrNull() ?: 0.0,
                            source = FoodSource.CUSTOM
                        )
                    )
                }
            ) { Text("Save and add to $mealTitle") }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Labels

private const val DATA_CREDIT = "Food database: Indian Nutrient Databank (Vijayakumar et al. 2024, CC BY 4.0; " +
    "frying oil and stock corrected) and USDA FoodData Central."

private fun dayLabel(d: LocalDate): String = when (d) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM"))
}

private fun grams(g: Double): String = if (g < 10 && g > 0) "${Units.fmt((g * 10).roundToInt() / 10.0)} g" else "${g.roundToInt()} g"

private fun fraction(x: Double): String = when (x) { 0.5 -> "½"; 1.5 -> "1½"; else -> Units.fmt(x) }

/** Unit text without its weight: "medium" for "medium (118 g)". */
private fun unitName(f: FoodEntity) = f.servingUnit.replace(Regex("""\s*\(\d+(?:\.\d+)?\s*g\)$"""), "")

/**
 * Grams in one serving when the food says so: "100 g", or a unit ending in the serving's weight such as
 * "medium (118 g)" or "0.5 x cup arils (87 g)". Null for dishes logged by the bowl or piece.
 */
internal fun gramsPerServing(f: FoodEntity): Double? {
    if (f.servingUnit == "g") return f.servingSize.takeIf { it > 0 }
    return Regex("""\((\d+(?:\.\d+)?) g\)$""").find(f.servingUnit)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }
}

/** One serving as people say it: "1 bowl", "100 g", "1 medium · 118 g". */
internal fun servingLabel(f: FoodEntity): String {
    val g = gramsPerServing(f)
    return when {
        f.servingUnit == "g" -> "${Units.fmt(f.servingSize)} g"
        g != null -> "${Units.fmt(f.servingSize)} ${unitName(f)} · ${Units.fmt(g)} g"
        else -> "${Units.fmt(f.servingSize)} ${f.servingUnit}"
    }
}

/**
 * How much was eaten, in the food's own terms: "1 medium", "2 chapati", "1.5 bowl", or grams when the amount is
 * not a whole or half serving of a food with a known weight ("200 g").
 */
internal fun amountLabel(f: FoodEntity?, servings: Double): String {
    if (f == null) return "${Units.fmt(servings)} ${if (servings == 1.0) "serving" else "servings"}"
    val g = gramsPerServing(f)
    val halves = (servings * 2).roundToInt() / 2.0
    return when {
        f.servingUnit == "g" -> "${(servings * f.servingSize).roundToInt()} g"
        g != null && kotlin.math.abs(servings - halves) > 0.01 -> "${(servings * g).roundToInt()} g"
        f.servingUnit == "serving" -> "${Units.fmt(servings * f.servingSize)} ${if (servings * f.servingSize == 1.0) "serving" else "servings"}"
        else -> "${Units.fmt(servings * f.servingSize)} ${unitName(f)}"
    }
}

/** "100 g" -> (100, "g"), "2 cups" -> (2, "cups"), "plate" -> (1, "plate"). */
internal fun parseServing(text: String): Pair<Double, String> {
    val t = text.trim()
    val m = Regex("""^(\d+(?:[.,]\d+)?)\s*(.+)$""").find(t)
    val size = m?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
    return if (m != null && size != null && size > 0) size to m.groupValues[2].trim() else 1.0 to t.ifEmpty { "serving" }
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
