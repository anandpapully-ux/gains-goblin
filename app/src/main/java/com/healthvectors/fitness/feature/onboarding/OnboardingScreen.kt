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
import com.healthvectors.fitness.core.designsystem.ChoiceRow
import com.healthvectors.fitness.core.designsystem.NumField
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
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Welcome", style = MaterialTheme.typography.headlineLarge)
        Text(
            "A few details let us set your calorie and protein targets. Everything stays on this device, " +
                "encrypted, and no account is needed.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, enabled = !busy) {
            Text("Moving phones? Restore a backup")
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Units", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(UnitSystem.entries, unit, { if (it == UnitSystem.METRIC) "kg / cm" else "lb / in" }) { unit = it }
        Text("Sex (used for the calorie formula)", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(Sex.entries, sex, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { sex = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(age, { age = it }, "Age", Modifier.weight(1f), decimal = false)
            NumField(height, { height = it }, "Height", Modifier.weight(1f), suffix = if (unit == UnitSystem.METRIC) "cm" else "in")
            NumField(weight, { weight = it }, "Weight", Modifier.weight(1f), suffix = Units.weightLabel(unit))
        }
        Text("Goal", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(Goal.entries, goal, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { goal = it }
        Text("Training experience", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(Experience.entries, exp, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { exp = it }
        Text("Daily activity outside workouts", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(ActivityLevel.entries, activity, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { activity = it }

        Text("Pick a starting plan", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            "You can rename days, move them to other weekdays, swap exercises and change sets, reps and rest later. " +
                "You can also import a plan a friend shared from the Workout tab.",
            style = MaterialTheme.typography.bodySmall
        )
        if (templates.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        templates.forEach { t -> TemplateCard(t, selected = t.id == templateId) { templateId = t.id } }

        Button(
            onClick = { vm.finish(unit, sex, ageN!!, hN!!, wN!!, goal, exp, activity, templates.firstOrNull { it.id == templateId }, onDone) },
            enabled = valid && !busy, modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (templateId == null) "Pick a plan to continue" else "Get started") }
    }
}
