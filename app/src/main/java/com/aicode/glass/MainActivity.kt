package com.aicode.glass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.glass.core.data.SettingsRepository
import com.aicode.glass.ui.theme.AiCodeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences by settings.preferences.collectAsStateWithLifecycle()
            AiCodeTheme(darkTheme = preferences.darkTheme) {
                CompositionLocalProvider(LocalLayoutDirection provides if (preferences.language == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.padding(24.dp)) { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge) }
                    }
                }
            }
        }
    }
}
