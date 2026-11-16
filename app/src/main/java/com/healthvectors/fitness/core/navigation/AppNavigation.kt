package com.healthvectors.fitness.core.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.healthvectors.fitness.data.datastore.AppSettings
import com.healthvectors.fitness.data.datastore.SettingsStore
import com.healthvectors.fitness.data.repository.RoutineRepository
import com.healthvectors.fitness.feature.history.HistoryScreen
import com.healthvectors.fitness.feature.nutrition.AddFoodScreen
import com.healthvectors.fitness.feature.plans.PlansScreen
import com.healthvectors.fitness.feature.nutrition.NutritionScreen
import com.healthvectors.fitness.feature.onboarding.OnboardingScreen
import com.healthvectors.fitness.feature.profile.ProfileScreen
import com.healthvectors.fitness.feature.progress.ProgressScreen
import com.healthvectors.fitness.feature.today.TodayScreen
import com.healthvectors.fitness.feature.workout.DayEditorScreen
import com.healthvectors.fitness.feature.workout.SessionScreen
import com.healthvectors.fitness.feature.workout.WorkoutScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    store: SettingsStore,
    routines: RoutineRepository
) : ViewModel() {
    /** Null until DataStore has produced its first value, so the UI never flashes the wrong start screen. */
    val settings: StateFlow<AppSettings?> =
        store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Adds any built-in exercises a new install or app update brings. Plans are chosen during onboarding. */
    init { viewModelScope.launch { routines.syncLibrary() } }
}

object Routes {
    const val ONBOARDING = "onboarding"
    const val TODAY = "today"
    const val WORKOUT = "workout"
    const val NUTRITION = "nutrition"
    const val PROGRESS = "progress"
    const val PROFILE = "profile"
    const val DAY = "day/{dayId}"
    const val SESSION = "session/{sessionId}"
    const val ADD_FOOD = "addfood/{date}/{meal}"
    const val PLANS = "plans"
    const val HISTORY = "history"
    fun day(id: Long) = "day/$id"
    fun session(id: Long) = "session/$id"
    fun addFood(date: String, meal: String) = "addfood/$date/$meal"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.TODAY, "Today", Icons.Filled.Today),
    Tab(Routes.WORKOUT, "Workout", Icons.Filled.FitnessCenter),
    Tab(Routes.NUTRITION, "Nutrition", Icons.Filled.Restaurant),
    Tab(Routes.PROGRESS, "Progress", Icons.Filled.ShowChart),
    Tab(Routes.PROFILE, "Profile", Icons.Filled.Person)
)

@Composable
fun AppNavHost(startOnboarding: Boolean) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBar = tabs.any { it.route == current }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { inner ->
        NavHost(
            nav,
            startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.TODAY,
            modifier = Modifier.padding(inner).consumeWindowInsets(inner)
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onDone = {
                    nav.navigate(Routes.TODAY) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                })
            }
            composable(Routes.TODAY) {
                TodayScreen(
                    onOpenSession = { nav.navigate(Routes.session(it)) },
                    onOpenNutrition = { nav.navigate(Routes.NUTRITION) }
                )
            }
            composable(Routes.WORKOUT) {
                WorkoutScreen(
                    onOpenDay = { nav.navigate(Routes.day(it)) },
                    onOpenPlans = { nav.navigate(Routes.PLANS) },
                    onOpenHistory = { nav.navigate(Routes.HISTORY) }
                )
            }
            composable(Routes.PLANS) { PlansScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.HISTORY) { HistoryScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.NUTRITION) { NutritionScreen(onAddFood = { d, m -> nav.navigate(Routes.addFood(d, m)) }) }
            composable(Routes.PROGRESS) { ProgressScreen() }
            composable(Routes.PROFILE) { ProfileScreen() }
            composable(Routes.DAY, listOf(navArgument("dayId") { type = NavType.LongType })) {
                DayEditorScreen(onBack = { nav.popBackStack() }, onStart = { nav.navigate(Routes.session(it)) })
            }
            composable(Routes.SESSION, listOf(navArgument("sessionId") { type = NavType.LongType })) {
                SessionScreen(onExit = { nav.popBackStack() })
            }
            composable(
                Routes.ADD_FOOD,
                listOf(navArgument("date") { type = NavType.StringType }, navArgument("meal") { type = NavType.StringType })
            ) { AddFoodScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
