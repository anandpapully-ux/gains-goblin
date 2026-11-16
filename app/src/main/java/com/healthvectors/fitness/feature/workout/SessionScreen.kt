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
import com.healthvectors.fitness.core.designsystem.NumField
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
                    title = { Column { Text(ui.title, maxLines = 1); Text(elapsed, style = MaterialTheme.typography.labelMedium) } },
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
                        }) { Icon(Icons.Filled.Mic, "Log a set by voice") }
                        IconButton(onClick = { plate = true }) { Icon(Icons.Filled.Calculate, "Plate calculator") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Discard workout") }, onClick = { menu = false; confirmDiscard = true })
                            }
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.navigationBarsPadding()) {
                        rest?.let { RestBar(it, onAdd = { vm.addRest(15) }, onSkip = vm::skipRest) }
                        Button(
                            onClick = { confirmFinish = true },
                            modifier = Modifier.fillMaxWidth().padding(12.dp).height(if (settings.gymMode) 64.dp else 52.dp)
                        ) { Icon(Icons.Filled.Check, null); Spacer(Modifier.width(8.dp)); Text("Finish workout") }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                if (ui.loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ui.blocks, key = { it.item.item.id }) { block ->
                        ExerciseCard(block, ui.weightUnit, vm, onWarmup = { warmupFor = block })
                    }
                }
                AnimatedVisibility(celebrate != null, Modifier.align(Alignment.TopCenter)) {
                    Card(
                        Modifier.padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.EmojiEvents, null)
                            Spacer(Modifier.width(12.dp))
                            Text(celebrate.orEmpty(), style = MaterialTheme.typography.titleMedium)
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
        text = { Text("Logged sets are saved. Unlogged rows are ignored.") },
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
            title = { Text(if (f.summary == null) "Nothing logged" else "Workout complete") },
            text = {
                val s = f.summary
                if (s == null) Text("No sets were logged, so this session was not saved.")
                else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Duration: ${s.durationMin} min")
                    Text("Sets: ${s.sets}")
                    Text("Volume: ${Units.fmt(Units.weightToDisplay(s.volumeKg, unitOf(ui.weightUnit)))} ${ui.weightUnit}")
                    Text("Estimated burn: ${s.kcal.toInt()} kcal")
                    if (s.prCount > 0) Text("Personal records: ${s.prCount}", style = MaterialTheme.typography.titleMedium)
                }
            },
            confirmButton = { TextButton(onClick = onExit) { Text("Done") } }
        )
    }
}

@Composable
private fun RestBar(rest: RestState, onAdd: () -> Unit, onSkip: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "Rest timer, ${rest.remaining} seconds remaining" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Filled.Timer, null)
        Column(Modifier.weight(1f)) {
            Text("Rest  %d:%02d".format(rest.remaining / 60, rest.remaining % 60), style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { 1f - rest.remaining.toFloat() / rest.total }, Modifier.fillMaxWidth())
        }
        FilledTonalButton(onClick = onAdd) { Text("+15s") }
        TextButton(onClick = onSkip) { Text("Skip") }
    }
}

@Composable
private fun ExerciseCard(block: BlockUi, unitLabel: String, vm: SessionViewModel, onWarmup: () -> Unit) {
    val r = block.item
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.exercise.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${r.item.targetSets} x ${r.item.repMin}-${r.item.repMax} ${block.unitLabel}" + (if (r.item.perSide) " per side" else "") +
                    " - RIR ${r.item.rirMin}-${r.item.rirMax} - rest ${r.item.restMinSec}-${r.item.restMaxSec}s",
                style = MaterialTheme.typography.bodySmall
            )
            Text(block.previousText + if (block.bestText.isNotEmpty()) "  |  ${block.bestText}" else "", style = MaterialTheme.typography.labelMedium)
            if (block.suggestion.isNotEmpty()) {
                Text(block.suggestion, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            block.rows.forEach { row -> SetRow(block, row, unitLabel, vm) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.addSet(r) }) { Icon(Icons.Filled.Add, null); Text("Add set") }
                if (block.showWeight) TextButton(onClick = onWarmup) { Text("Warm-up") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetRow(block: BlockUi, row: SetRowUi, unitLabel: String, vm: SessionViewModel) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.StartToEnd && !row.done) vm.toggle(block, row)
        false // always snap back; completion is shown by the row state
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = false,
        enableDismissFromStartToEnd = !row.done,
        backgroundContent = {
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                Icon(Icons.Filled.Check, "Swipe to complete set", tint = MaterialTheme.colorScheme.primary)
            }
        }
    ) {
        Surface(
            color = if (row.done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${row.index + 1}", Modifier.width(20.dp), style = MaterialTheme.typography.titleSmall)
                if (block.showWeight) {
                    NumField(row.weight, { vm.edit(block.item, row, weight = it) }, unitLabel, Modifier.weight(1f))
                }
                NumField(row.reps, { vm.edit(block.item, row, reps = it) }, block.unitLabel, Modifier.weight(1f), decimal = false)
                if (block.showWeight) {
                    NumField(row.rpe, { vm.edit(block.item, row, rpe = it) }, "RPE", Modifier.weight(0.8f))
                }
                IconButton(onClick = { vm.toggle(block, row) }, Modifier.size(48.dp)) {
                    Icon(
                        if (row.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        if (row.done) "Set ${row.index + 1} done, tap to undo" else "Complete set ${row.index + 1}",
                        tint = if (row.isPr) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun unitOf(label: String) =
    if (label == "kg") com.healthvectors.fitness.domain.model.UnitSystem.METRIC else com.healthvectors.fitness.domain.model.UnitSystem.IMPERIAL
