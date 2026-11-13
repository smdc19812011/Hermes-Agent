package com.hermes.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.hermes.agent.data.repository.SettingsRepository
import com.hermes.agent.domain.model.AppTheme
import com.hermes.agent.ui.navigation.HermesNavGraph
import com.hermes.agent.ui.theme.HermesAgentTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = com.hermes.agent.domain.model.AgentSettings()
            )
            val useDark = when (settings.theme) {
                AppTheme.DARK -> true
                AppTheme.LIGHT -> false
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }

            HermesAgentTheme(darkTheme = useDark) {
                val navController = rememberNavController()
                HermesNavGraph(navController = navController)
            }
        }
    }
}
