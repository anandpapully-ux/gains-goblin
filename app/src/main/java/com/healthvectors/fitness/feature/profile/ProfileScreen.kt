package com.healthvectors.fitness.feature.profile

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.core.designsystem.ChoiceRow
import com.healthvectors.fitness.core.designsystem.NumField
import com.healthvectors.fitness.core.designsystem.SectionTitle
import com.healthvectors.fitness.core.util.Units
import com.healthvectors.fitness.data.datastore.AppSettings
import com.healthvectors.fitness.data.datastore.SettingsStore
import com.healthvectors.fitness.data.local.entity.UserProfileEntity
import com.healthvectors.fitness.data.repository.DataPorter
import com.healthvectors.fitness.data.repository.ProfileRepository
import com.healthvectors.fitness.domain.model.*
import com.healthvectors.fitness.domain.usecase.DailyTargets
import com.healthvectors.fitness.domain.logic.Macros
import com.healthvectors.fitness.worker.ReminderWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
    private val store: SettingsStore,
    private val porter: DataPorter,
    private val targets: DailyTargets
) : ViewModel() {
    val profile = profiles.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val weight = profiles.latestWeight.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message: SharedFlow<String> = _message

    fun baseTargets(): Macros? {
        val p = profile.value ?: return null
        val w = weight.value ?: return null
        return targets.base(p, w.weightKg)
    }

    /** Calculated targets without the user's overrides (shown as the starting point). */
    fun calculatedTargets(): Macros? {
        val p = profile.value ?: return null
        val w = weight.value ?: return null
        return targets.base(p.copy(customKcal = null, customProteinG = null, customFatG = null), w.weightKg)
    }

    fun saveBody(sex: Sex, age: Int, heightCm: Double) = viewModelScope.launch {
        val p = profiles.getProfile() ?: return@launch
        profiles.saveProfile(p.copy(sex = sex, heightCm = heightCm, birthDate = java.time.LocalDate.now().minusYears(age.toLong())))
    }
    fun setCustomTargets(kcal: Int?, protein: Int?, fat: Int?) = viewModelScope.launch {
        val p = profiles.getProfile() ?: return@launch
        profiles.saveProfile(p.copy(customKcal = kcal, customProteinG = protein, customFatG = fat))
    }
    fun setCycling(v: Boolean) = viewModelScope.launch { store.setCalorieCycling(v) }
    fun setSchedule(m: ScheduleMode) = viewModelScope.launch { store.setScheduleMode(m) }

    /** A full backup replaces everything, so it waits for confirmation; holds the file text meanwhile. */
    private val _pendingRestore = MutableStateFlow<String?>(null)
    val pendingRestore: StateFlow<String?> = _pendingRestore
    fun cancelRestore() { _pendingRestore.value = null }

    fun save(p: UserProfileEntity) = viewModelScope.launch { profiles.saveProfile(p) }
    fun setTheme(m: ThemeMode) = viewModelScope.launch { store.setTheme(m) }
    fun setDynamic(v: Boolean) = viewModelScope.launch { store.setDynamicColor(v) }
    fun setGym(v: Boolean) = viewModelScope.launch { store.setGymMode(v) }
    fun setSound(v: Boolean) = viewModelScope.launch { store.setRestSound(v) }
    fun setGamification(v: Boolean) = viewModelScope.launch { store.setGamification(v) }
    fun setWater(v: Int) = viewModelScope.launch { store.setWaterGoal(v) }
    fun setReminder(enabled: Boolean, hour: Int) = viewModelScope.launch {
        store.setReminder(enabled, hour)
        if (enabled) ReminderWorker.schedule(context, hour) else ReminderWorker.cancel(context)
    }

    fun export(uri: Uri, kind: String) = viewModelScope.launch {
        runCatching {
            val text = when (kind) { "json" -> porter.exportJson(); "workouts" -> porter.exportWorkoutsCsv(); else -> porter.exportMealsCsv() }
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } ?: error("Cannot open file")
        }.onSuccess { _message.tryEmit("Export saved") }.onFailure { _message.tryEmit("Export failed: ${it.message}") }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Cannot open file")
            if (porter.isFullBackup(text)) { _pendingRestore.value = text; null } else porter.importJson(text)
        }.onSuccess { r ->
            if (r != null) _message.tryEmit("Imported ${r.records} records" + if (r.skipped > 0) " (${r.skipped} skipped: unknown exercise or food)" else "")
        }.onFailure { _message.tryEmit("Import failed: ${it.message}") }
    }

    fun confirmRestore() = viewModelScope.launch {
        val text = _pendingRestore.value ?: return@launch
        _pendingRestore.value = null
        runCatching { porter.importJson(text) }
            .onSuccess { _message.tryEmit("Backup restored (${it.records} records)") }
            .onFailure { _message.tryEmit("Restore failed, nothing was changed: ${it.message}") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(vm: ProfileViewModel = hiltViewModel()) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val weight by vm.weight.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    var bodyDialog by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    var exportKind by remember { mutableStateOf("json") }
    var rationale by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.message.collect { snack.showSnackbar(it) } }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri -> uri?.let { vm.export(it, exportKind) } }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setReminder(granted || Build.VERSION.SDK_INT < 33, s.reminderHour)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Profile") }) }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        val p = profile
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p != null) {
                val unit = p.unitSystem
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Current weight: " + (weight?.let { Units.fmtWeight(it.weightKg, unit) } ?: "-"), style = MaterialTheme.typography.titleMedium)
                        val t = vm.baseTargets()
                        Text("Daily target: " + (t?.let { "${it.kcal} kcal - P ${it.proteinG} g - C ${it.carbsG} g - F ${it.fatG} g" } ?: "-"))
                        val age = java.time.Period.between(p.birthDate, java.time.LocalDate.now()).years
                        val h = if (unit == UnitSystem.METRIC) "${Units.fmt(p.heightCm)} cm" else "${Units.fmt(p.heightCm / Units.CM_PER_IN)} in"
                        Text("${p.sex.name.lowercase().replaceFirstChar(Char::uppercase)}, $age years, $h", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { bodyDialog = true }, contentPadding = PaddingValues(0.dp)) { Text("Edit age, height, sex") }
                    }
                }
                SectionTitle("Goal")
                ChoiceRow(Goal.entries, p.goal, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { vm.save(p.copy(goal = it)) }
                if (p.goal == Goal.CUT) {
                    Text("Weekly loss rate: ${p.weeklyRatePct}% of body weight", style = MaterialTheme.typography.bodyMedium)
                    Slider(p.weeklyRatePct.toFloat(), { vm.save(p.copy(weeklyRatePct = Math.round(it * 10) / 10.0)) }, valueRange = 0.3f..1.0f)
                    if (p.weeklyRatePct > 0.8) Text("Above ~0.8% per week risks muscle loss.", color = MaterialTheme.colorScheme.error)
                }
                Text("Activity outside workouts")
                ChoiceRow(ActivityLevel.entries, p.activityLevel, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { vm.save(p.copy(activityLevel = it)) }
                Text("Units")
                ChoiceRow(UnitSystem.entries, p.unitSystem, { if (it == UnitSystem.METRIC) "kg" else "lb" }) { vm.save(p.copy(unitSystem = it)) }

                SectionTitle("Nutrition targets")
                val custom = p.customKcal != null || p.customProteinG != null || p.customFatG != null
                SwitchRow("Set my own targets", custom) { on ->
                    if (on) vm.calculatedTargets()?.let { vm.setCustomTargets(it.kcal, it.proteinG, it.fatG) }
                    else vm.setCustomTargets(null, null, null)
                }
                if (custom) {
                    var kcal by remember(p.customKcal) { mutableStateOf(p.customKcal?.toString() ?: "") }
                    var prot by remember(p.customProteinG) { mutableStateOf(p.customProteinG?.toString() ?: "") }
                    var fat by remember(p.customFatG) { mutableStateOf(p.customFatG?.toString() ?: "") }
                    fun push() = vm.setCustomTargets(
                        kcal.toIntOrNull()?.takeIf { it in 800..8000 }, prot.toIntOrNull()?.takeIf { it in 0..500 }, fat.toIntOrNull()?.takeIf { it in 0..400 }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField(kcal, { kcal = it; push() }, "Calories", Modifier.weight(1f), decimal = false)
                        NumField(prot, { prot = it; push() }, "Protein", Modifier.weight(1f), decimal = false, suffix = "g")
                        NumField(fat, { fat = it; push() }, "Fat", Modifier.weight(1f), decimal = false, suffix = "g")
                    }
                    Text("Carbs fill the remaining calories. Empty fields use the calculated value.", style = MaterialTheme.typography.labelSmall)
                }
                SwitchRow("Eat more on lifting days", s.calorieCycling, vm::setCycling)
                Text(
                    "Shifts carbs from rest days to lifting days; the weekly total stays the same. Balanced for the number of lifting days in your plan. Off in rotation mode.",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            SectionTitle("Appearance")
            ChoiceRow(ThemeMode.entries, s.themeMode, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, vm::setTheme)
            SwitchRow("Dynamic color (Android 12+)", s.dynamicColor, vm::setDynamic)

            SectionTitle("Training")
            Text("Schedule")
            ChoiceRow(ScheduleMode.entries, s.scheduleMode, { if (it == ScheduleMode.WEEKLY) "Fixed weekdays" else "Rotation" }, vm::setSchedule)
            Text(
                if (s.scheduleMode == ScheduleMode.WEEKLY) "Each plan day belongs to a weekday."
                else "Today shows the next day in your plan after the last one you finished, whenever you train. Rest days are skipped.",
                style = MaterialTheme.typography.labelSmall
            )
            SwitchRow("Gym mode (screen on, larger text)", s.gymMode, vm::setGym)
            SwitchRow("Rest timer sound", s.restSound, vm::setSound)
            SwitchRow("Streaks and XP", s.gamification, vm::setGamification)

            SectionTitle("Reminders")
            SwitchRow("Daily workout reminder (${s.reminderHour}:00)", s.reminderEnabled) { on ->
                if (!on) vm.setReminder(false, s.reminderHour)
                else if (Build.VERSION.SDK_INT >= 33) rationale = true else vm.setReminder(true, s.reminderHour)
            }
            if (s.reminderEnabled) {
                Slider(s.reminderHour.toFloat(), { vm.setReminder(true, it.toInt()) }, valueRange = 5f..22f, steps = 16)
                Text(
                    if (s.scheduleMode == ScheduleMode.WEEKLY) "Skipped on rest days and when you have already trained."
                    else "Skipped when you have already trained that day.", style = MaterialTheme.typography.labelSmall
                )
            }
            var water by remember(s.waterGoalMl) { mutableStateOf(s.waterGoalMl.toString()) }
            NumField(water, { water = it; it.toIntOrNull()?.takeIf { v -> v in 500..6000 }?.let(vm::setWater) }, "Daily water goal", Modifier.fillMaxWidth(), decimal = false, suffix = "ml")

            SectionTitle("Your data")
            Text("All data stays on this device in an encrypted database. Nothing is uploaded; the only network calls are optional food searches and barcode lookups (Open Food Facts).", style = MaterialTheme.typography.bodySmall)
            Text("A JSON backup holds everything (profile, plans, workouts, food). Keep it somewhere safe: it is not encrypted, and it is how you move to a new phone.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportKind = "json"; create.launch("gains-goblin-backup-${java.time.LocalDate.now()}.json") }) { Text("Back up") }
                OutlinedButton(onClick = { open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("Restore / import") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportKind = "workouts"; create.launch("workouts.csv") }) { Text("Workouts CSV") }
                OutlinedButton(onClick = { exportKind = "meals"; create.launch("meals.csv") }) { Text("Meals CSV") }
            }
        }
    }

    if (pendingRestore != null) AlertDialog(
        onDismissRequest = vm::cancelRestore,
        title = { Text("Replace all data?") },
        text = { Text("This backup replaces everything on this device (profile, plans, workouts, food and body data) with its contents. Back up first if you are unsure.") },
        confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("Replace") } },
        dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } }
    )
    if (bodyDialog) profile?.let { p ->
        BodyDialog(p, onDismiss = { bodyDialog = false }) { sex, age, cm -> vm.saveBody(sex, age, cm); bodyDialog = false }
    }
    if (rationale) AlertDialog(
        onDismissRequest = { rationale = false },
        title = { Text("Allow notifications?") },
        text = { Text("Notifications are used only for your daily workout reminder. They are generated on this device; no data leaves it.") },
        confirmButton = { TextButton(onClick = { rationale = false; notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { rationale = false }) { Text("Not now") } }
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun BodyDialog(p: UserProfileEntity, onDismiss: () -> Unit, onSave: (Sex, Int, Double) -> Unit) {
    val metric = p.unitSystem == UnitSystem.METRIC
    var sex by remember { mutableStateOf(p.sex) }
    var age by remember { mutableStateOf(java.time.Period.between(p.birthDate, java.time.LocalDate.now()).years.toString()) }
    var height by remember { mutableStateOf(Units.fmt(if (metric) p.heightCm else p.heightCm / Units.CM_PER_IN)) }
    val a = age.toIntOrNull()
    val h = height.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About you") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceRow(Sex.entries, sex, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { sex = it }
                NumField(age, { age = it }, "Age", Modifier.fillMaxWidth(), decimal = false)
                NumField(height, { height = it }, "Height", Modifier.fillMaxWidth(), suffix = if (metric) "cm" else "in")
            }
        },
        confirmButton = {
            TextButton(
                enabled = a != null && a in 14..90 && h != null && h > 0,
                onClick = { onSave(sex, a!!, if (metric) h!! else h!! * Units.CM_PER_IN) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
