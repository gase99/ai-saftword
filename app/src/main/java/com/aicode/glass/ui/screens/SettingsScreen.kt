package com.aicode.glass.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.glass.R
import com.aicode.glass.core.network.ProviderConfig
import com.aicode.glass.core.network.ProviderType
import com.aicode.glass.ui.components.*
import com.aicode.glass.ui.model.SettingsViewModel

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val prefs by vm.preferences.collectAsStateWithLifecycle(); val configured by vm.configured.collectAsStateWithLifecycle()
    val gitToken by vm.gitTokenConfigured.collectAsStateWithLifecycle(); val notice by vm.notice.collectAsStateWithLifecycle(); val error by vm.error.collectAsStateWithLifecycle()
    val config = prefs.configs.getValue(prefs.provider)
    var model by rememberSaveable(prefs.provider) { mutableStateOf(config.model) }
    var endpoint by rememberSaveable(prefs.provider) { mutableStateOf(config.endpoint) }
    var key by rememberSaveable(prefs.provider) { mutableStateOf("") }; var reveal by remember { mutableStateOf(false) }
    var author by rememberSaveable(prefs.authorName) { mutableStateOf(prefs.authorName) }
    var email by rememberSaveable(prefs.authorEmail) { mutableStateOf(prefs.authorEmail) }
    var token by rememberSaveable { mutableStateOf("") }
    var removeKey by remember { mutableStateOf(false) }; var removeToken by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle(stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle))
        SettingsGroup(stringResource(R.string.appearance)) {
            Text(stringResource(R.string.language), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = prefs.language == "en", onClick = { vm.language("en") }, label = { Text("English") })
                FilterChip(selected = prefs.language == "fa", onClick = { vm.language("fa") }, label = { Text("فارسی") })
            }
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { listOf("system" to R.string.system_theme, "light" to R.string.light, "dark" to R.string.dark).forEachIndexed { index, (value, label) ->
                SegmentedButton(selected = prefs.theme == value, onClick = { vm.theme(value) }, shape = SegmentedButtonDefaults.itemShape(index, 3), label = { Text(stringResource(label), style = MaterialTheme.typography.labelMedium) })
            } }
        }
        SettingsGroup(stringResource(R.string.ai_provider)) {
            Text(stringResource(R.string.provider_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProviderType.entries.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { provider ->
                FilterChip(selected = prefs.provider == provider, onClick = { vm.provider(provider) }, label = { Text(when(provider) { ProviderType.OPENAI -> "OpenAI"; ProviderType.ANTHROPIC -> "Anthropic"; ProviderType.GEMINI -> "Gemini"; ProviderType.CUSTOM -> stringResource(R.string.custom) }) }, leadingIcon = { if(configured[provider] == true) Icon(Icons.Outlined.CheckCircle, null, Modifier.size(15.dp)) }, modifier = Modifier.weight(1f))
            } } }
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text(stringResource(R.string.model_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            if (prefs.provider == ProviderType.CUSTOM) OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text(stringResource(R.string.base_url)) }, placeholder = { Text("https://your-provider.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(stringResource(if (configured[prefs.provider] == true) R.string.replace_key else R.string.api_key)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(R.string.toggle_key)) } }, shape = RoundedCornerShape(16.dp))
            Text(stringResource(if (configured[prefs.provider] == true) R.string.key_saved else R.string.key_missing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveProvider(ProviderConfig(prefs.provider, model, endpoint), key); key = ""; reveal = false }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save_provider)) }
                if (configured[prefs.provider] == true) OutlinedButton(onClick = { removeKey = true }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.remove_key), Modifier.size(19.dp)) }
            }
            Text(stringResource(R.string.key_security), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsGroup(stringResource(R.string.git_identity)) {
            OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text(stringResource(R.string.author_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text(stringResource(R.string.author_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text(stringResource(if (gitToken) R.string.replace_token else R.string.github_token)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            Text(stringResource(R.string.token_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { vm.saveGit(author, email, token); token = "" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save_git)) }; if (gitToken) OutlinedButton(onClick = { removeToken = true }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.remove_token), Modifier.size(19.dp)) } }
        }
        SettingsGroup(stringResource(R.string.about_title)) {
            Text("AiCode Glass · 1.0", style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.capability_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.privacy_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
    ErrorNotice(notice, vm::dismissNotice); ErrorNotice(error, vm::dismissError)
    if (removeKey) ConfirmDialog(stringResource(R.string.remove_key), stringResource(R.string.remove_key_detail), onConfirm = { vm.removeKey(prefs.provider); removeKey = false }, onDismiss = { removeKey = false })
    if (removeToken) ConfirmDialog(stringResource(R.string.remove_token), stringResource(R.string.remove_token_detail), onConfirm = { vm.removeGitToken(); removeToken = false }, onDismiss = { removeToken = false })
}
