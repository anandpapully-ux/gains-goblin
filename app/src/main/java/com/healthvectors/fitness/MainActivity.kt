package com.healthvectors.fitness

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthvectors.fitness.core.designsystem.FitnessTheme
import com.healthvectors.fitness.core.navigation.AppNavHost
import com.healthvectors.fitness.core.navigation.AppViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = hiltViewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            FitnessTheme(settings?.themeMode ?: com.healthvectors.fitness.domain.model.ThemeMode.SYSTEM,
                settings?.dynamicColor ?: false) {
                settings?.let { AppNavHost(startOnboarding = !it.onboardingDone) }
            }
        }
    }
}
