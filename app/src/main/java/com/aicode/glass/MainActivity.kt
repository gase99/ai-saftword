@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.aicode.glass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.aicode.glass.core.data.AppLocale
import com.aicode.glass.ui.components.*
import com.aicode.glass.ui.model.AppViewModel
import com.aicode.glass.ui.screens.*
import com.aicode.glass.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var appliedLanguage: String = AppLocale.ENGLISH

    /**
     * Applies the persisted UI language to this activity's own resources.
     *
     * This must not be done by overriding `LocalContext` in Compose: the value returned by
     * `createConfigurationContext` is a plain `ContextImpl`, and `hiltViewModel()` inside NavHost
     * requires an Activity context. That mismatch crashed every destination with
     * "Expected an activity context for creating a HiltViewModelFactory".
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        appliedLanguage = AppLocale.read(newBase)
        super.attachBaseContext(
            newBase.createConfigurationContext(
                AppLocale.localize(newBase.resources.configuration, appliedLanguage)
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs by vm.preferences.collectAsStateWithLifecycle()
            val dark = if (prefs.theme == "system") isSystemInDarkTheme() else prefs.darkTheme
            // Language switches still apply immediately: recreating the activity re-runs
            // attachBaseContext, so resources and every stringResource use the new locale.
            LaunchedEffect(prefs.language) {
                if (prefs.language != appliedLanguage) {
                    // Keep the synchronous mirror in sync before the recreated activity reads it.
                    AppLocale.write(this@MainActivity, prefs.language)
                    appliedLanguage = prefs.language
                    recreate()
                }
            }
            AiCodeTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalReducedMotion provides prefs.reducedMotion, LocalLayoutDirection provides if (prefs.language == AppLocale.PERSIAN) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    GlassBackground(Modifier.fillMaxSize()) { AppShell(vm) }
                }
            }
        }
    }
}

@Composable private fun AppShell(vm: AppViewModel) {
    val ready by vm.ready.collectAsStateWithLifecycle(); val prefs by vm.preferences.collectAsStateWithLifecycle()
    val workspaces by vm.workspaceList.collectAsStateWithLifecycle(); val error by vm.error.collectAsStateWithLifecycle()
    val navigation = rememberNavController(); val backstack by navigation.currentBackStackEntryAsState()
    val route = backstack?.destination?.route ?: "chat"
    var workspacePicker by remember { mutableStateOf(false) }; var create by remember { mutableStateOf(false) }; var name by remember { mutableStateOf("") }
    var filesInitially by rememberSaveable { mutableStateOf(false) }
    val tabs = listOf(Triple("chat", R.string.tab_chat, Icons.AutoMirrored.Outlined.Chat), Triple("terminal", R.string.tab_terminal, Icons.Outlined.Terminal), Triple("git", R.string.tab_git, Icons.Outlined.AccountTree), Triple("settings", R.string.tab_settings, Icons.Outlined.Settings))
    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(39.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Code, null, tint = MaterialTheme.colorScheme.primary) }
                Column(Modifier.padding(horizontal = 11.dp).weight(1f)) {
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = .7.sp)
                }
                Surface(onClick = { workspacePicker = true }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .7f), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .25f))) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp)); Text(workspaces.find { it.id == prefs.activeWorkspace }?.name ?: stringResource(R.string.workspace), modifier = Modifier.widthIn(max = 94.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(15.dp)) }
                }
            }
        },
        bottomBar = {
            GlassCard(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), RoundedCornerShape(27.dp)) {
                NavigationBar(containerColor = androidx.compose.ui.graphics.Color.Transparent, tonalElevation = 0.dp, modifier = Modifier.height(68.dp)) {
                    tabs.forEach { (destination, label, icon) -> NavigationBarItem(selected = route == destination, onClick = {
                        if (destination != "terminal") filesInitially = false
                        navigation.navigate(destination) { popUpTo("chat") { saveState = true }; launchSingleTop = true; restoreState = true }
                    }, icon = { Icon(icon, null, Modifier.size(22.dp)) }, label = { Text(stringResource(label), fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer)) }
                }
            }
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!ready) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.loading_workspace)) }
            else NavHost(navigation, "chat", Modifier.fillMaxSize()) {
                composable("chat") { ChatScreen(onSettings = { navigation.navigate("settings") { launchSingleTop = true } }, onFiles = { filesInitially = true; navigation.navigate("terminal") { launchSingleTop = true } }) }
                composable("terminal") { TerminalScreen(filesInitially) }
                composable("git") { GitScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    }
    ErrorNotice(error, vm::dismissError)
    if (workspacePicker) ModalBottomSheet(onDismissRequest = { workspacePicker = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 30.dp).navigationBarsPadding()) {
            SectionTitle(stringResource(R.string.workspaces), stringResource(R.string.workspaces_detail))
            workspaces.forEach { workspace -> Surface(onClick = { vm.selectWorkspace(workspace.id); workspacePicker = false }, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Text(workspace.name, Modifier.weight(1f)); if(workspace.id == prefs.activeWorkspace) Icon(Icons.Outlined.Check, null) } }; HorizontalDivider() }
            TextButton(onClick = { create = true; workspacePicker = false }) { Icon(Icons.Outlined.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.new_workspace)) }
        }
    }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text(stringResource(R.string.new_workspace)) }, text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.workspace_name)) }, singleLine = true) }, confirmButton = { TextButton(onClick = { vm.createWorkspace(name); name = ""; create = false }, enabled = name.isNotBlank()) { Text(stringResource(R.string.create)) } }, dismissButton = { TextButton(onClick = { create = false }) { Text(stringResource(R.string.cancel)) } })
}
