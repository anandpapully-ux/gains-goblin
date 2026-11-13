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

    fun targetsText(): String? {
        val p = profile.value ?: return null
        val w = weight.value ?: return null
        val t = targets.base(p, w.weightKg)
        return "${t.kcal} kcal - P ${t.proteinG} g - C ${t.carbsG} g - F ${t.fatG} g"
    }

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
            porter.importJson(text)
        }.onSuccess { _message.tryEmit("Imported $it records") }.onFailure { _message.tryEmit("Import failed: ${it.message}") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(vm: ProfileViewModel = hiltViewModel()) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val weight by vm.weight.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
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
                        Text("Daily target: " + (vm.targetsText() ?: "-"))
                        Text("Targets are adjusted automatically on training vs. rest days.", style = MaterialTheme.typography.labelSmall)
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
            }

            SectionTitle("Appearance")
            ChoiceRow(ThemeMode.entries, s.themeMode, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, vm::setTheme)
            SwitchRow("Dynamic color (Android 12+)", s.dynamicColor, vm::setDynamic)

            SectionTitle("Training")
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
                Text("Skipped on rest days and when you have already trained.", style = MaterialTheme.typography.labelSmall)
            }
            var water by remember(s.waterGoalMl) { mutableStateOf(s.waterGoalMl.toString()) }
            NumField(water, { water = it; it.toIntOrNull()?.takeIf { v -> v in 500..6000 }?.let(vm::setWater) }, "Daily water goal", Modifier.fillMaxWidth(), decimal = false, suffix = "ml")

            SectionTitle("Your data")
            Text("All data stays on this device in an encrypted database. Nothing is uploaded; the only network calls are optional food searches and barcode lookups (Open Food Facts).", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportKind = "json"; create.launch("fitness-backup.json") }) { Text("Export JSON") }
                OutlinedButton(onClick = { open.launch(arrayOf("application/json", "text/*")) }) { Text("Import JSON") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportKind = "workouts"; create.launch("workouts.csv") }) { Text("Workouts CSV") }
                OutlinedButton(onClick = { exportKind = "meals"; create.launch("meals.csv") }) { Text("Meals CSV") }
            }
        }
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
