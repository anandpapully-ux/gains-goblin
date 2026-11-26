package com.healthvectors.fitness.feature.onboarding

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import com.healthvectors.fitness.core.designsystem.*
import com.healthvectors.fitness.core.util.Units
import com.healthvectors.fitness.data.datastore.SettingsStore
import com.healthvectors.fitness.data.local.entity.UserProfileEntity
import com.healthvectors.fitness.data.plan.PlanTemplate
import com.healthvectors.fitness.data.repository.DataPorter
import com.healthvectors.fitness.data.repository.ProfileRepository
import com.healthvectors.fitness.data.repository.RoutineRepository
import com.healthvectors.fitness.domain.logic.CalorieCalculator
import com.healthvectors.fitness.domain.model.*
import com.healthvectors.fitness.feature.plans.TemplateCard
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
    private val routines: RoutineRepository,
    private val porter: DataPorter,
    private val settings: SettingsStore
) : ViewModel() {
    private val _templates = MutableStateFlow<List<PlanTemplate>>(emptyList())
    val templates: StateFlow<List<PlanTemplate>> = _templates
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    init {
        viewModelScope.launch { _templates.value = withContext(Dispatchers.IO) { routines.templates() } }
    }

    fun finish(
        unit: UnitSystem, sex: Sex, age: Int, heightDisplay: Double, weightDisplay: Double,
        goal: Goal, experience: Experience, activity: ActivityLevel, template: PlanTemplate?, onDone: () -> Unit
    ) = viewModelScope.launch {
        _busy.value = true
        runCatching {
            val heightCm = if (unit == UnitSystem.METRIC) heightDisplay else heightDisplay * Units.CM_PER_IN
            profiles.saveProfile(
                UserProfileEntity(
                    heightCm = heightCm, birthDate = LocalDate.now().minusYears(age.toLong()), sex = sex,
                    activityLevel = activity, goal = goal, experience = experience, unitSystem = unit,
                    weeklyRatePct = CalorieCalculator.DEFAULT_CUT_RATE_PCT, createdAt = System.currentTimeMillis()
                )
            )
            profiles.logWeight(Units.weightFromDisplay(weightDisplay, unit))
            if (template != null) routines.createFromPlan(template.plan)
            settings.setOnboardingDone(true)
        }.onSuccess { onDone() }.onFailure { _message.value = "Setup failed: ${it.message}" }
        _busy.value = false
    }

    /** Restores a full backup from another phone; skips the rest of setup when it contains a profile. */
    fun restore(uri: Uri, onDone: () -> Unit) = viewModelScope.launch {
        _busy.value = true
        runCatching {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Cannot open file")
            }
            if (!porter.isFullBackup(text)) error("This is an older backup without your profile. Finish setup, then import it under Profile > Your data.")
            porter.importJson(text)
            if (profiles.getProfile() == null) error("The backup has no profile; finish setup below.")
            settings.setOnboardingDone(true)
        }.onSuccess { onDone() }.onFailure { _message.value = it.message }
        _busy.value = false
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val templates by vm.templates.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var unit by remember { mutableStateOf(UnitSystem.METRIC) }
    var sex by remember { mutableStateOf(Sex.MALE) }
    var age by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf(Goal.CUT) }
    var exp by remember { mutableStateOf(Experience.INTERMEDIATE) }
    var activity by remember { mutableStateOf(ActivityLevel.LIGHT) }
    var templateId by remember { mutableStateOf<String?>(null) }

    val ageN = age.toIntOrNull()
    val hN = height.toDoubleOrNull()
    val wN = weight.toDoubleOrNull()
    val valid = ageN != null && ageN in 14..90 && hN != null && hN > 0 && wN != null && wN > 0 && templateId != null
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.restore(it, onDone) } }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBadge(Icons.Filled.FitnessCenter, size = 64.dp, container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
            Text("Welcome", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 12.dp))
            Text(
                "A few details let us set your calorie and protein targets. Everything stays on this device, " +
                    "encrypted, and no account is needed.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = { restore.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, enabled = !busy,
                contentPadding = PaddingValues(horizontal = 0.dp)
            ) { Icon(Icons.Outlined.SettingsBackupRestore, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Moving phones? Restore a backup") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        SectionTitle("About you")
        GroupCard {
            GroupField("Units") { ChoiceRow(UnitSystem.entries, unit, { if (it == UnitSystem.METRIC) "kg / cm" else "lb / in" }) { unit = it } }
            GroupDivider()
            GroupField("Sex", "Used for the calorie formula") { ChoiceRow(Sex.entries, sex, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { sex = it } }
            GroupDivider()
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(age, { age = it }, "Age", Modifier.weight(1f), decimal = false)
                NumField(height, { height = it }, "Height", Modifier.weight(1f), suffix = if (unit == UnitSystem.METRIC) "cm" else "in")
                NumField(weight, { weight = it }, "Weight", Modifier.weight(1f), suffix = Units.weightLabel(unit))
            }
        }

        SectionTitle("Your goal")
        GroupCard {
            GroupField("Goal") { ChoiceRow(Goal.entries, goal, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { goal = it } }
            GroupDivider()
            GroupField("Training experience") { ChoiceRow(Experience.entries, exp, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { exp = it } }
            GroupDivider()
            GroupField("Daily activity outside workouts") { ChoiceRow(ActivityLevel.entries, activity, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { activity = it } }
        }

        SectionTitle("Pick a starting plan")
        Text(
            "You can rename days, move them, swap exercises and change sets, reps and rest later. " +
                "You can also import a plan a friend shared from the Workout tab.",
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (templates.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            templates.forEach { t -> TemplateCard(t, selected = t.id == templateId) { templateId = t.id } }
        }

        Button(
            onClick = { vm.finish(unit, sex, ageN!!, hN!!, wN!!, goal, exp, activity, templates.firstOrNull { it.id == templateId }, onDone) },
            enabled = valid && !busy, modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(56.dp)
        ) {
            Text(
                when {
                    ageN == null || hN == null || wN == null -> "Enter your age, height and weight"
                    templateId == null -> "Pick a plan to continue"
                    else -> "Get started"
                }
            )
        }
    }
}
