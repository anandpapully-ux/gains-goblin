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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.*
import com.healthvectors.fitness.core.designsystem.*
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
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)) {
            if (p != null) {
                val unit = p.unitSystem
                val age = java.time.Period.between(p.birthDate, java.time.LocalDate.now()).years
                val h = if (unit == UnitSystem.METRIC) "${Units.fmt(p.heightCm)} cm" else "${Units.fmt(p.heightCm / Units.CM_PER_IN)} in"
                AppCard(color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        IconBadge(Icons.Filled.Person, size = 56.dp, container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                        Column(Modifier.weight(1f)) {
                            Text(weight?.let { Units.fmtWeight(it.weightKg, unit) } ?: "–", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                "${p.sex.name.lowercase().replaceFirstChar(Char::uppercase)} · $age years · $h",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        FilledTonalButton(onClick = { bodyDialog = true }) { Text("Edit") }
                    }
                    vm.baseTargets()?.let { t ->
                        Text("Daily target", Modifier.padding(top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Row(Modifier.fillMaxWidth()) {
                            TargetNum(Units.thousands(t.kcal), "kcal", Modifier.weight(1f))
                            TargetNum("${t.proteinG} g", "Protein", Modifier.weight(1f))
                            TargetNum("${t.carbsG} g", "Carbs", Modifier.weight(1f))
                            TargetNum("${t.fatG} g", "Fat", Modifier.weight(1f))
                        }
                    }
                }

                SectionTitle("Goal and activity")
                GroupCard {
                    GroupField("Goal") { ChoiceRow(Goal.entries, p.goal, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { vm.save(p.copy(goal = it)) } }
                    if (p.goal == Goal.CUT) {
                        GroupDivider()
                        GroupField("Weekly loss", "${p.weeklyRatePct}% of body weight a week") {
                            Slider(p.weeklyRatePct.toFloat(), { vm.save(p.copy(weeklyRatePct = Math.round(it * 10) / 10.0)) }, valueRange = 0.3f..1.0f)
                            if (p.weeklyRatePct > 0.8) Text("Losing more than about 0.8% a week risks losing muscle.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    GroupDivider()
                    GroupField("Activity outside workouts") {
                        ChoiceRow(ActivityLevel.entries, p.activityLevel, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { vm.save(p.copy(activityLevel = it)) }
                    }
                    GroupDivider()
                    GroupField("Units") { ChoiceRow(UnitSystem.entries, p.unitSystem, { if (it == UnitSystem.METRIC) "kg · cm" else "lb · in" }) { vm.save(p.copy(unitSystem = it)) } }
                }

                SectionTitle("Nutrition")
                GroupCard {
                    val custom = p.customKcal != null || p.customProteinG != null || p.customFatG != null
                    SwitchSetting("Set my own targets", custom, { on ->
                        if (on) vm.calculatedTargets()?.let { vm.setCustomTargets(it.kcal, it.proteinG, it.fatG) }
                        else vm.setCustomTargets(null, null, null)
                    }, subtitle = if (custom) "Carbs fill the remaining calories. Empty fields use the calculated value." else "Targets are calculated from your body and goal.",
                        icon = Icons.Outlined.Tune)
                    if (custom) {
                        var kcal by remember(p.customKcal) { mutableStateOf(p.customKcal?.toString() ?: "") }
                        var prot by remember(p.customProteinG) { mutableStateOf(p.customProteinG?.toString() ?: "") }
                        var fat by remember(p.customFatG) { mutableStateOf(p.customFatG?.toString() ?: "") }
                        fun push() = vm.setCustomTargets(
                            kcal.toIntOrNull()?.takeIf { it in 800..8000 }, prot.toIntOrNull()?.takeIf { it in 0..500 }, fat.toIntOrNull()?.takeIf { it in 0..400 }
                        )
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField(kcal, { kcal = it; push() }, "Calories", Modifier.weight(1f), decimal = false)
                            NumField(prot, { prot = it; push() }, "Protein", Modifier.weight(1f), decimal = false, suffix = "g")
                            NumField(fat, { fat = it; push() }, "Fat", Modifier.weight(1f), decimal = false, suffix = "g")
                        }
                    }
                    GroupDivider()
                    SwitchSetting(
                        "Eat more on lifting days", s.calorieCycling, vm::setCycling,
                        subtitle = "Moves carbs from rest days to lifting days; the weekly total stays the same. Off in rotation mode.",
                        icon = Icons.Outlined.SwapVert
                    )
                    GroupDivider()
                    var water by remember(s.waterGoalMl) { mutableStateOf(s.waterGoalMl.toString()) }
                    SettingRow("Daily water goal", icon = Icons.Outlined.WaterDrop) {
                        NumField(water, { water = it; it.toIntOrNull()?.takeIf { v -> v in 500..6000 }?.let(vm::setWater) }, "Goal", Modifier.width(130.dp), decimal = false, suffix = "ml")
                    }
                }
            }

            SectionTitle("Training")
            GroupCard {
                GroupField(
                    "Schedule",
                    if (s.scheduleMode == ScheduleMode.WEEKLY) "Each plan day belongs to a weekday."
                    else "Today shows the next day in your plan after the last one you finished. Rest days are skipped."
                ) { ChoiceRow(ScheduleMode.entries, s.scheduleMode, { if (it == ScheduleMode.WEEKLY) "Fixed weekdays" else "Rotation" }, vm::setSchedule) }
                GroupDivider()
                SwitchSetting("Gym mode", s.gymMode, vm::setGym, subtitle = "Keeps the screen on and enlarges text during workouts", icon = Icons.Outlined.ScreenLockPortrait)
                GroupDivider()
                SwitchSetting("Rest timer sound", s.restSound, vm::setSound, icon = Icons.Outlined.NotificationsActive)
                GroupDivider()
                SwitchSetting("Streaks and XP", s.gamification, vm::setGamification, icon = Icons.Outlined.EmojiEvents)
                GroupDivider()
                SwitchSetting("Daily workout reminder", s.reminderEnabled, { on ->
                    if (!on) vm.setReminder(false, s.reminderHour)
                    else if (Build.VERSION.SDK_INT >= 33) rationale = true else vm.setReminder(true, s.reminderHour)
                }, subtitle = if (!s.reminderEnabled) "Off" else "At %02d:00 · ".format(s.reminderHour) +
                    if (s.scheduleMode == ScheduleMode.WEEKLY) "skipped on rest days and once you've trained" else "skipped once you've trained that day",
                    icon = Icons.Outlined.Alarm)
                if (s.reminderEnabled) Slider(
                    s.reminderHour.toFloat(), { vm.setReminder(true, it.toInt()) }, valueRange = 5f..22f, steps = 16,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)
                )
            }

            SectionTitle("Appearance")
            GroupCard {
                GroupField("Theme") { ChoiceRow(ThemeMode.entries, s.themeMode, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, vm::setTheme) }
                GroupDivider()
                SwitchSetting("Match wallpaper colours", s.dynamicColor, vm::setDynamic, subtitle = "Android 12 and newer", icon = Icons.Outlined.Palette)
            }

            SectionTitle("Your data")
            GroupCard {
                SettingRow(
                    "Back up everything", subtitle = "Profile, plans, workouts and food in one file. It is not encrypted, so keep it somewhere safe. Progress photos are exported separately from their screen.",
                    icon = Icons.Outlined.Backup, onClick = { exportKind = "json"; create.launch("gains-goblin-backup-${java.time.LocalDate.now()}.json") }
                )
                GroupDivider(56.dp)
                SettingRow(
                    "Restore or import", subtitle = "From a backup, for example when moving to a new phone",
                    icon = Icons.Outlined.SettingsBackupRestore, onClick = { open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                )
                GroupDivider(56.dp)
                SettingRow("Export workouts", subtitle = "CSV for spreadsheets", icon = Icons.Outlined.TableChart, onClick = { exportKind = "workouts"; create.launch("workouts.csv") })
                GroupDivider(56.dp)
                SettingRow("Export meals", subtitle = "CSV for spreadsheets", icon = Icons.Outlined.TableChart, onClick = { exportKind = "meals"; create.launch("meals.csv") })
            }
            Row(Modifier.padding(top = 16.dp, start = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Everything stays on this device in an encrypted database. Nothing is uploaded; the only network calls are optional food searches and barcode lookups (Open Food Facts).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
private fun TargetNum(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
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
    FormSheet(
        "About you", onDismiss, primaryLabel = "Save", primaryEnabled = a != null && a in 14..90 && h != null && h > 0,
        onPrimary = { onSave(sex, a!!, if (metric) h!! else h!! * Units.CM_PER_IN) },
        subtitle = "Used to calculate your calorie and protein targets."
    ) {
        Text("Sex", style = MaterialTheme.typography.labelLarge)
        ChoiceRow(Sex.entries, sex, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { sex = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            NumField(age, { age = it }, "Age", Modifier.weight(1f), decimal = false, suffix = "years")
            NumField(height, { height = it }, "Height", Modifier.weight(1f), suffix = if (metric) "cm" else "in")
        }
    }
}
