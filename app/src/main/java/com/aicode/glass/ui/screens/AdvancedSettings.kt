package com.aicode.glass.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.glass.R
import com.aicode.glass.core.network.ProviderType
import com.aicode.glass.ui.components.SettingsGroup
import com.aicode.glass.ui.model.SettingsViewModel

@Composable
fun ConnectionSettings(vm: SettingsViewModel) {
    val prefs by vm.preferences.collectAsStateWithLifecycle(); val testing by vm.testing.collectAsStateWithLifecycle()
    var retries by rememberSaveable(prefs.connectionRetries) { mutableStateOf(prefs.connectionRetries.toFloat()) }
    var fallback by rememberSaveable(prefs.allowFallbacks) { mutableStateOf(prefs.allowFallbacks) }
    var providers by rememberSaveable(prefs.fallbackProviders) { mutableStateOf(prefs.fallbackProviders) }
    SettingsGroup(stringResource(R.string.connection_resilience)) {
        Text(stringResource(R.string.retry_detail), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.retry_count, retries.toInt()), style = MaterialTheme.typography.labelLarge)
        Slider(value = retries, onValueChange = { retries = it }, onValueChangeFinished = { vm.connection(retries.toInt(), fallback, providers) }, valueRange = 0f..3f, steps = 2)
        ToggleRow(stringResource(R.string.allow_fallbacks), fallback) { fallback = it; vm.connection(retries.toInt(), it, providers) }
        Text(stringResource(R.string.fallback_privacy), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { ProviderType.entries.forEach { provider -> FilterChip(selected = provider in providers, onClick = { providers = if(provider in providers) providers-provider else providers+provider; vm.connection(retries.toInt(), fallback, providers) }, label = { Text(provider.name.lowercase().replaceFirstChar { it.uppercase() }) }) } }
        OutlinedButton(onClick = vm::testConnection, enabled = !testing) { Icon(Icons.Outlined.NetworkCheck, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(if(testing) R.string.testing_connection else R.string.test_connection)) }
        Text(stringResource(R.string.connection_test_detail), style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
fun McpSettings(vm: SettingsViewModel) {
    val servers by vm.mcpServers.collectAsStateWithLifecycle(); val state by vm.mcpState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }; var id by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }; var endpoint by remember { mutableStateOf("http://10.0.2.2:3000/mcp") }
    var token by remember { mutableStateOf("") }; var local by remember { mutableStateOf(true) }
    SettingsGroup(stringResource(R.string.mcp_title)) {
        Text(stringResource(R.string.mcp_detail), style = MaterialTheme.typography.bodySmall)
        servers.forEach { server ->
            Column { Text(server.name, style = MaterialTheme.typography.titleSmall); Text(server.endpoint, style = MaterialTheme.typography.labelSmall); Row { TextButton(onClick = { id = server.id; name = server.name; endpoint = server.endpoint; local = server.allowLocalHttp; token = ""; editing = true }) { Text(stringResource(R.string.edit)) }; TextButton(onClick = { vm.removeMcp(server.id) }) { Text(stringResource(R.string.delete)) } } }
            HorizontalDivider()
        }
        state.connections.forEach { connection -> Text(connection.server.name + " · MCP " + connection.protocol, style = MaterialTheme.typography.labelMedium); connection.tools.forEach { Text("• " + it.name + " — " + it.description, style = MaterialTheme.typography.bodySmall) } }
        if(state.error != null) Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { id = null; name = ""; endpoint = "http://10.0.2.2:3000/mcp"; token = ""; local = true; editing = true }, enabled = servers.size < 4) { Text(stringResource(R.string.add_mcp)) }
            Button(onClick = vm::refreshMcp, enabled = !state.busy && servers.isNotEmpty()) { Text(stringResource(if(state.busy) R.string.connecting else R.string.discover_tools)) }
        }
        Text(stringResource(R.string.mcp_security), style = MaterialTheme.typography.bodySmall)
    }
    if(editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.mcp_server)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.server_name)) }, singleLine = true)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(R.string.mcp_endpoint)) }, singleLine = true) }
        OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.mcp_token)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        ToggleRow(stringResource(R.string.local_http), local) { local = it }
        Text(stringResource(R.string.mcp_token_detail), style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton(onClick = { vm.saveMcp(name, endpoint, token, local, id); editing = false }, enabled = name.isNotBlank() && endpoint.isNotBlank()) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } })
}
@Composable fun ToggleRow(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(checked = checked, onCheckedChange = changed) }
}
