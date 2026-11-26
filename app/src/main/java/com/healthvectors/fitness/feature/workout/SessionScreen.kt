package com.healthvectors.fitness.feature.workout

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.healthvectors.fitness.core.designsystem.*
import com.healthvectors.fitness.core.util.Units
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(onExit: () -> Unit, vm: SessionViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val rest by vm.rest.collectAsStateWithLifecycle()
    val celebrate by vm.celebrate.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var plate by remember { mutableStateOf(false) }
    var warmupFor by remember { mutableStateOf<BlockUi?>(null) }

    // Gym mode: keep the screen awake and enlarge text for sweaty one-handed use.
    val view = LocalView.current
    DisposableEffect(settings.gymMode) {
        view.keepScreenOn = settings.gymMode
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(Unit) { vm.events.collect { snack.showSnackbar(it) } }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::voice)
    }

    var elapsed by remember { mutableStateOf("0:00") }
    LaunchedEffect(ui.startedAt) {
        while (true) {
            val secs = if (ui.startedAt > 0) (System.currentTimeMillis() - ui.startedAt) / 1000 else 0L
            elapsed = "%d:%02d".format(secs / 60, secs % 60)
            delay(1000)
        }
    }

    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides if (settings.gymMode) Density(d.density, d.fontScale * 1.25f) else d) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(ui.title, maxLines = 1)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Timer, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                val done = ui.blocks.sumOf { b -> b.rows.count { it.done } }
                                val total = ui.blocks.sumOf { it.rows.size }
                                Text("$elapsed · $done of $total sets", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Leave workout (progress is kept)") } },
                    actions = {
                        IconButton(onClick = {
                            try {
                                speech.launch(
                                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "e.g. bench press, 60 kilos, 8 reps")
                                )
                            } catch (_: ActivityNotFoundException) {
                                android.widget.Toast.makeText(ctx, "Voice input is not available on this device", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) { Icon(Icons.Outlined.Mic, "Log a set by voice") }
                        IconButton(onClick = { plate = true }) { Icon(Icons.Outlined.Calculate, "Plate calculator") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Discard workout") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; confirmDiscard = true })
                            }
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        rest?.let { RestBar(it, onAdd = { vm.addRest(15) }, onSkip = vm::skipRest) }
                        Button(
                            onClick = { confirmFinish = true },
                            modifier = Modifier.fillMaxWidth().height(if (settings.gymMode) 64.dp else 52.dp)
                        ) { Icon(Icons.Filled.Check, null); Spacer(Modifier.width(8.dp)); Text("Finish workout") }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                if (ui.loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ui.blocks, key = { it.item.item.id }) { block ->
                        ExerciseCard(block, ui.weightUnit, vm, onWarmup = { warmupFor = block })
                    }
                }
                AnimatedVisibility(celebrate != null, Modifier.align(Alignment.TopCenter)) {
                    Surface(
                        Modifier.padding(16.dp), shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.tertiaryContainer, shadowElevation = 6.dp
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Filled.EmojiEvents, container = MaterialTheme.colorScheme.tertiary, content = MaterialTheme.colorScheme.onTertiary)
                            Spacer(Modifier.width(12.dp))
                            Text(celebrate.orEmpty(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
            }
        }
    }

    if (plate) PlateDialog(null) { plate = false }
    warmupFor?.let { b ->
        val kg = b.rows.firstOrNull { !it.done }?.weight?.toDoubleOrNull()?.let { Units.weightFromDisplay(it, unitOf(ui.weightUnit)) }
        WarmupDialog(kg) { warmupFor = null }
    }
    if (confirmFinish) AlertDialog(
        onDismissRequest = { confirmFinish = false },
        title = { Text("Finish workout?") },
        text = { Text("Logged sets are saved. Rows you didn't tick are ignored.") },
        confirmButton = { TextButton(onClick = { confirmFinish = false; vm.finish() }) { Text("Finish") } },
        dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Keep going") } }
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard workout?") },
        text = { Text("All sets logged in this session will be deleted.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discard(onExit) }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } }
    )
    finished?.let { f ->
        AlertDialog(
            onDismissRequest = onExit,
            icon = { Icon(if (f.summary == null) Icons.Outlined.Info else Icons.Filled.EmojiEvents, null) },
            title = { Text(if (f.summary == null) "Nothing logged" else "Workout complete") },
            text = {
                val s = f.summary
                if (s == null) Text("No sets were logged, so this session was not saved.")
                else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("${s.durationMin} min", "Duration", Modifier.weight(1f))
                        StatTile("${s.sets}", "Sets", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("${Units.compact(Units.weightToDisplay(s.volumeKg, unitOf(ui.weightUnit)))} ${ui.weightUnit}", "Volume", Modifier.weight(1f))
                        StatTile("${s.kcal.toInt()}", "kcal burned", Modifier.weight(1f))
                    }
                    if (s.prCount > 0) Text(
                        "${s.prCount} personal ${if (s.prCount == 1) "record" else "records"}!", Modifier.fillMaxWidth().padding(top = 4.dp),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary, textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = { TextButton(onClick = onExit) { Text("Done") } }
        )
    }
}

@Composable
private fun RestBar(rest: RestState, onAdd: () -> Unit, onSkip: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { contentDescription = "Rest timer, ${rest.remaining} seconds remaining" },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Timer, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Rest", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "%d:%02d".format(rest.remaining / 60, rest.remaining % 60), Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                FilledTonalButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("+15 s") }
                TextButton(onClick = onSkip) { Text("Skip") }
            }
            LinearProgressIndicator(
                progress = { 1f - rest.remaining.toFloat() / rest.total }, Modifier.fillMaxWidth().height(6.dp),
                strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {}
            )
        }
    }
}

@Composable
private fun ExerciseCard(block: BlockUi, unitLabel: String, vm: SessionViewModel, onWarmup: () -> Unit) {
    val r = block.item
    var menu by remember { mutableStateOf(false) }
    val allDone = block.rows.isNotEmpty() && block.rows.all { it.done }
    AppCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.exercise.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    if (allDone) Icon(Icons.Filled.CheckCircle, "All sets done", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "${r.item.targetSets} × ${r.item.repMin}–${r.item.repMax} ${block.unitLabel}" + (if (r.item.perSide) " per side" else "") +
                        (if (block.showWeight) " · RIR ${r.item.rirMin}–${r.item.rirMax}" else "") +
                        if (r.item.restMaxSec > 0) " · " + Units.restRange(r.item.restMinSec, r.item.restMaxSec).lowercase() else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menu = true }, Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "Options for ${r.exercise.name}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Add set") }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = { menu = false; vm.addSet(r) })
                    if (block.showWeight) DropdownMenuItem(text = { Text("Warm-up sets") }, leadingIcon = { Icon(Icons.Outlined.Whatshot, null) }, onClick = { menu = false; onWarmup() })
                }
            }
        }
        if (r.item.notes.isNotBlank()) Text(r.item.notes, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Surface(Modifier.fillMaxWidth().padding(top = 12.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    block.previousText + if (block.bestText.isNotEmpty()) "  ·  ${block.bestText}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (block.suggestion.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.TrendingUp, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(block.suggestion, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Column headings once, instead of a label in every field.
        Row(Modifier.padding(top = 12.dp, bottom = 4.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Header("Set", Modifier.width(32.dp))
            if (block.showWeight) Header(unitLabel, Modifier.weight(1f))
            Header(block.unitLabel, Modifier.weight(1f))
            if (block.showWeight) Header("RPE", Modifier.weight(0.8f))
            Spacer(Modifier.width(44.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { block.rows.forEach { row -> SetRow(block, row, unitLabel, vm) } }
        TextButton(onClick = { vm.addSet(r) }, Modifier.padding(top = 4.dp)) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add set") }
    }
}

@Composable
private fun Header(text: String, modifier: Modifier) =
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetRow(block: BlockUi, row: SetRowUi, unitLabel: String, vm: SessionViewModel) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.StartToEnd && !row.done) vm.toggle(block, row)
        false // always snap back; completion is shown by the row state
    })
    val n = row.index + 1
    SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = false,
        enableDismissFromStartToEnd = !row.done,
        backgroundContent = {
            Box(Modifier.fillMaxSize().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                Icon(Icons.Filled.Check, "Swipe to complete set", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    ) {
        Surface(
            color = if (row.done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.small
        ) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$n", Modifier.width(32.dp), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
                    color = if (row.isPr) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
                if (block.showWeight) CellField(row.weight, { vm.edit(block.item, row, weight = it) }, true, "Set $n weight in $unitLabel", row.done, Modifier.weight(1f))
                CellField(row.reps, { vm.edit(block.item, row, reps = it) }, false, "Set $n ${block.unitLabel}", row.done, Modifier.weight(1f))
                if (block.showWeight) CellField(row.rpe, { vm.edit(block.item, row, rpe = it) }, true, "Set $n RPE", row.done, Modifier.weight(0.8f), placeholder = "–")
                IconButton(onClick = { vm.toggle(block, row) }, Modifier.size(44.dp)) {
                    Icon(
                        if (row.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        if (row.done) "Set $n done, tap to undo" else "Complete set $n",
                        tint = when {
                            row.isPr -> MaterialTheme.colorScheme.tertiary
                            row.done -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

/** A compact numeric cell for the set table: no floating label (the column has a heading), big centred digits. */
@Composable
private fun CellField(value: String, onChange: (String) -> Unit, decimal: Boolean, description: String, done: Boolean, modifier: Modifier, placeholder: String = "") {
    val color = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = value,
        onValueChange = { v -> if (numOk(v, decimal)) onChange(v.replace(',', '.')) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = color, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.semantics { contentDescription = description },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(MaterialTheme.shapes.small)
                    .background(if (done) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
                inner()
            }
        }
    )
}

private fun unitOf(label: String) =
    if (label == "kg") com.healthvectors.fitness.domain.model.UnitSystem.METRIC else com.healthvectors.fitness.domain.model.UnitSystem.IMPERIAL
