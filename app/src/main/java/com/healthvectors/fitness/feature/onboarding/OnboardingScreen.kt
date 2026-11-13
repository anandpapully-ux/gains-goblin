package com.healthvectors.fitness.feature.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.core.designsystem.ChoiceRow
import com.healthvectors.fitness.core.designsystem.NumField
import com.healthvectors.fitness.core.util.Units
import com.healthvectors.fitness.data.datastore.SettingsStore
import com.healthvectors.fitness.data.local.entity.UserProfileEntity
import com.healthvectors.fitness.data.repository.ProfileRepository
import com.healthvectors.fitness.domain.logic.CalorieCalculator
import com.healthvectors.fitness.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val settings: SettingsStore
) : ViewModel() {
    fun finish(
        unit: UnitSystem, sex: Sex, age: Int, heightDisplay: Double, weightDisplay: Double,
        goal: Goal, experience: Experience, activity: ActivityLevel, onDone: () -> Unit
    ) = viewModelScope.launch {
        val heightCm = if (unit == UnitSystem.METRIC) heightDisplay else heightDisplay * Units.CM_PER_IN
        profiles.saveProfile(
            UserProfileEntity(
                heightCm = heightCm, birthDate = LocalDate.now().minusYears(age.toLong()), sex = sex,
                activityLevel = activity, goal = goal, experience = experience, unitSystem = unit,
                weeklyRatePct = CalorieCalculator.DEFAULT_CUT_RATE_PCT, createdAt = System.currentTimeMillis()
            )
        )
        profiles.logWeight(Units.weightFromDisplay(weightDisplay, unit))
        settings.setOnboardingDone(true)
        onDone()
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    var unit by remember { mutableStateOf(UnitSystem.METRIC) }
    var sex by remember { mutableStateOf(Sex.MALE) }
    var age by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf(Goal.CUT) }
    var exp by remember { mutableStateOf(Experience.INTERMEDIATE) }
    var activity by remember { mutableStateOf(ActivityLevel.LIGHT) }

    val ageN = age.toIntOrNull()
    val hN = height.toDoubleOrNull()
    val wN = weight.toDoubleOrNull()
    val valid = ageN != null && ageN in 14..90 && hN != null && hN > 0 && wN != null && wN > 0

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
        Button(
            onClick = { vm.finish(unit, sex, ageN!!, hN!!, wN!!, goal, exp, activity, onDone) },
            enabled = valid, modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Get started") }
    }
}
