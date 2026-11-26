package com.healthvectors.fitness.feature.plans

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import com.healthvectors.fitness.core.designsystem.*
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
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.data.local.entity.RoutineEntity
import com.healthvectors.fitness.data.plan.PlanTemplate
import com.healthvectors.fitness.data.repository.RoutineRepository
import com.healthvectors.fitness.domain.model.DayType
import com.healthvectors.fitness.domain.plan.PlanCodec
import com.healthvectors.fitness.domain.plan.PlanFormatException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PlansViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routines: RoutineRepository
) : ViewModel() {
    val plans: StateFlow<List<RoutineEntity>> = routines.routines.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _templates = MutableStateFlow<List<PlanTemplate>>(emptyList())
    val templates: StateFlow<List<PlanTemplate>> = _templates
    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message: SharedFlow<String> = _message

    init {
        viewModelScope.launch { _templates.value = withContext(Dispatchers.IO) { routines.templates() } }
    }

    private fun run(success: String? = null, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { success?.let(_message::tryEmit) }
            .onFailure { _message.tryEmit(if (it is PlanFormatException) it.message.orEmpty() else "Something went wrong: ${it.message}") }
    }

    fun useTemplate(t: PlanTemplate) = run("\"${t.plan.name}\" is now your plan") { routines.createFromPlan(t.plan) }
    fun activate(r: RoutineEntity) = run("Switched to \"${r.name}\"") { routines.activate(r.id) }
    fun rename(r: RoutineEntity, name: String) = run { routines.rename(r.id, name) }
    fun duplicate(r: RoutineEntity) = run("Copy created") { routines.duplicate(r.id) }
    fun delete(r: RoutineEntity) = run("Plan deleted. Your logged workouts are kept.") {
        if (!routines.delete(r.id)) throw PlanFormatException("Switch to another plan before deleting this one.")
    }

    fun import(uri: Uri) = run("Plan imported and set as your plan") {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Cannot open file")
        }
        routines.importPlan(text)
    }

    fun exportTo(uri: Uri, r: RoutineEntity) = run("Plan saved") {
        val text = PlanCodec.encode(routines.exportPlan(r.id))
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } ?: error("Cannot open file")
        }
    }

    /** Writes the plan to the app cache and hands a content:// link to the share sheet. */
    fun share(r: RoutineEntity, launch: (Intent) -> Unit) = run {
        val text = PlanCodec.encode(routines.exportPlan(r.id))
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "shared").apply { mkdirs() }.resolve(fileName(r.name)).apply { writeText(text) }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Workout plan: ${r.name}")
            .putExtra(Intent.EXTRA_TEXT, "My workout plan \"${r.name}\". Import it in Gains Goblin: Workout > My plans > Import.")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(Intent.createChooser(send, "Share plan"))
    }

    companion object {
        fun fileName(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "plan" } + ".json"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(onBack: () -> Unit, vm: PlansViewModel = hiltViewModel()) {
    val plans by vm.plans.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RoutineEntity?>(null) }
    var deleting by remember { mutableStateOf<RoutineEntity?>(null) }
    var exporting by remember { mutableStateOf<RoutineEntity?>(null) }

    LaunchedEffect(Unit) { vm.message.collect { snack.showSnackbar(it) } }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val r = exporting
        if (uri != null && r != null) vm.exportTo(uri, r)
        exporting = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My plans") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New plan") })
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Your workouts, records and history are shared across plans, so switching never loses progress.",
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(plans, key = { it.id }) { r ->
                var menu by remember { mutableStateOf(false) }
                AppCard(
                    onClick = if (r.isActive) null else ({ vm.activate(r) }),
                    color = if (r.isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    padding = 16.dp
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (r.isActive) Pill("Current plan")
                            Text(r.name, style = MaterialTheme.typography.titleMedium)
                            if (r.description.isNotBlank()) Text(r.description, maxLines = 3, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!r.isActive) Text("Tap to use this plan", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options for ${r.name}") }
                            DropdownMenu(menu, { menu = false }) {
                                if (!r.isActive) DropdownMenuItem(text = { Text("Use this plan") }, leadingIcon = { Icon(Icons.Filled.CheckCircle, null) },
                                    onClick = { menu = false; vm.activate(r) })
                                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                    onClick = { menu = false; renaming = r })
                                DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                    onClick = { menu = false; vm.duplicate(r) })
                                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                    onClick = { menu = false; vm.share(r) { ctx.startActivity(it) } })
                                DropdownMenuItem(text = { Text("Save to file") }, leadingIcon = { Icon(Icons.Outlined.Save, null) },
                                    onClick = { menu = false; exporting = r; create.launch(PlansViewModel.fileName(r.name)) })
                                if (!r.isActive) DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { menu = false; deleting = r })
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) NewPlanSheet(
        templates = templates,
        onTemplate = { vm.useTemplate(it); adding = false },
        onImport = { adding = false; open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
        onDismiss = { adding = false }
    )
    renaming?.let { r ->
        var name by remember(r) { mutableStateOf(r.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename plan") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.rename(r, name); renaming = null }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${r.name}\"?") },
            text = { Text("The plan is removed. Workouts you logged with it stay in your history and charts.") },
            confirmButton = { TextButton(onClick = { vm.delete(r); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewPlanSheet(templates: List<PlanTemplate>, onTemplate: (PlanTemplate) -> Unit, onImport: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.statusBarsPadding()) {
        LazyColumn(Modifier.padding(horizontal = 24.dp), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("New plan", style = MaterialTheme.typography.headlineSmall) }
            item {
                ActionTile(Icons.Outlined.FileOpen, "Import a plan file", "A .json plan someone shared with you, or one you saved earlier.", Modifier.fillMaxWidth(), onImport)
            }
            item { SectionTitle("Starter plans · you can edit everything afterwards") }
            items(templates, key = { it.id }) { t -> TemplateCard(t, selected = false) { onTemplate(t) } }
        }
    }
}

@Composable
fun TemplateCard(t: PlanTemplate, selected: Boolean, onClick: () -> Unit) {
    val lifting = t.plan.days.count { it.type == DayType.HYPERTROPHY && it.items.isNotEmpty() }
    val cardio = t.plan.days.count { it.type == DayType.CARDIO && it.items.isNotEmpty() }
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(
                when { lifting == 0 && cardio == 0 -> Icons.Outlined.EditNote; t.plan.name.contains("Home", true) -> Icons.Outlined.Home; else -> Icons.Outlined.FitnessCenter },
                container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.plan.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        lifting == 0 && cardio == 0 -> "Empty week"
                        cardio == 0 -> "$lifting strength days a week"
                        else -> "$lifting strength + $cardio cardio days a week"
                    },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary
                )
                if (t.plan.description.isNotBlank()) Text(t.plan.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Filled.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
