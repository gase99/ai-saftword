@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.aicode.glass.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.glass.R
import com.aicode.glass.terminalview.TerminalView
import com.aicode.glass.ui.components.*
import com.aicode.glass.ui.model.WorkspaceViewModel
import com.aicode.glass.ui.theme.GlassCard
import java.text.DateFormat

@Composable
fun TerminalScreen(filesInitially: Boolean = false, vm: WorkspaceViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(if (filesInitially) 1 else 0) }
    val editor by vm.editor.collectAsStateWithLifecycle(); var discard by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle(); val error by vm.error.collectAsStateWithLifecycle(); val notice by vm.notice.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importZip) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::exportZip) }
    BackHandler(enabled = editor != null) { if (editor?.dirty == true) discard = true else vm.closeEditor() }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        if (editor != null) EditorPane(vm) else {
            SectionTitle(stringResource(R.string.workspace_title), stringResource(R.string.workspace_subtitle))
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                listOf(R.string.tab_terminal to Icons.AutoMirrored.Outlined.Terminal, R.string.files to Icons.Outlined.FolderOpen, R.string.checkpoints to Icons.Outlined.History).forEachIndexed { index, (label, icon) ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label), fontSize = 12.sp) }, icon = { Icon(icon, null, Modifier.size(19.dp)) })
                }
            }
            when (tab) { 0 -> TerminalPane(vm); 1 -> FilesPane(vm, { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }, { exportLauncher.launch("aicode-workspace.zip") }); else -> CheckpointsPane(vm) }
        }
    }
    ErrorNotice(error, vm::dismissError); ErrorNotice(notice, vm::dismissNotice)
    if (discard) ConfirmDialog(stringResource(R.string.discard_changes), stringResource(R.string.discard_detail), onConfirm = { vm.closeEditor(); discard = false }, onDismiss = { discard = false })
}
@Composable private fun TerminalPane(vm: WorkspaceViewModel) {
    val session by vm.session.collectAsStateWithLifecycle(); val lines by session.lines.collectAsStateWithLifecycle(); val running by session.running.collectAsStateWithLifecycle()
    var command by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f), shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(9.dp)); Text(stringResource(R.string.terminal_disclosure), style = MaterialTheme.typography.bodySmall) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("WORKSPACE SHELL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            if (running) TextButton(onClick = vm::stopTerminal) { Text(stringResource(R.string.stop)) }
            IconButton(onClick = session::clear) { Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.clear_terminal)) }
        }
        GlassCard(Modifier.fillMaxWidth().weight(1f), RoundedCornerShape(18.dp)) { TerminalView(lines, Modifier.fillMaxSize()) }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = command, onValueChange = { command = it }, modifier = Modifier.weight(1f), singleLine = true, enabled = !running, placeholder = { Text("help", fontFamily = FontFamily.Monospace) }, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp), shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (command.isNotBlank()) { vm.execute(command); command = "" } }), leadingIcon = { Text("$", Modifier.padding(start = 14.dp), color = MaterialTheme.colorScheme.primary) })
            Spacer(Modifier.width(8.dp)); FilledIconButton(enabled = !running && command.isNotBlank(), onClick = { vm.execute(command); command = "" }) { Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.run_command)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("help", "ls -la", "git status", "find .").forEach { AssistChip(onClick = { command = it }, label = { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }) } }
    }
}
@Composable private fun FilesPane(vm: WorkspaceViewModel, import: () -> Unit, export: () -> Unit) {
    val files by vm.files.collectAsStateWithLifecycle(); val directory by vm.directory.collectAsStateWithLifecycle(); val busy by vm.busy.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }; var path by remember { mutableStateOf("") }; var delete by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::upDirectory, enabled = directory.isNotEmpty()) { Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.parent_directory)) }
            Text("/$directory", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            IconButton(onClick = { create = true }, enabled = !busy) { Icon(Icons.Outlined.NoteAdd, stringResource(R.string.new_file)) }
            IconButton(onClick = vm::refresh) { Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh)) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (files.isEmpty()) item { EmptyState(Icons.Outlined.FolderOpen, stringResource(R.string.empty_folder), stringResource(R.string.empty_folder_detail), Modifier.fillMaxWidth()) }
            items(files, key = { it.path }) { file -> GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp)) { Row(Modifier.clickable { if (file.directory) vm.directory(file.path) else vm.open(file.path) }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (file.directory) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = if (file.directory) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium); if (!file.directory) Text("${file.size} B", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!file.directory) IconButton(onClick = { delete = file.path }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete), Modifier.size(18.dp)) }
                else Icon(Icons.Outlined.ChevronRight, null)
            } } }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = import, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.import_zip)) }
            Button(onClick = export, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.export_zip)) }
        }
        Text(stringResource(R.string.workspace_limits), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
    }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text(stringResource(R.string.new_file)) }, text = { OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text(stringResource(R.string.relative_path)) }, placeholder = { Text("src/main.py") }, singleLine = true) }, confirmButton = { TextButton(onClick = { vm.createFile(path); create = false; path = "" }, enabled = path.isNotBlank()) { Text(stringResource(R.string.create)) } }, dismissButton = { TextButton(onClick = { create = false }) { Text(stringResource(R.string.cancel)) } })
    delete?.let { ConfirmDialog(stringResource(R.string.delete_file), it, onConfirm = { vm.deleteFile(it); delete = null }, onDismiss = { delete = null }) }
}
@Composable private fun EditorPane(vm: WorkspaceViewModel) {
    val editor by vm.editor.collectAsStateWithLifecycle(); val current = editor ?: return; val busy by vm.busy.collectAsStateWithLifecycle()
    var discard by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val transformation = remember(colors) { SyntaxTransformation(mapOf("keyword" to colors.secondary, "comment" to colors.onSurfaceVariant, "string" to colors.primary)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (current.dirty) discard = true else vm.closeEditor() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) { Text(current.path, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text(stringResource(if (current.dirty) R.string.unsaved else R.string.saved), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            Button(onClick = vm::save, enabled = current.dirty && !busy) { Text(stringResource(R.string.save)) }
        }
        Text(stringResource(R.string.editor_tip), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            GlassCard(Modifier.fillMaxWidth().weight(1f).padding(bottom = 14.dp), RoundedCornerShape(16.dp)) {
                BasicCodeEditor(current.content, vm::edit, transformation, Modifier.fillMaxSize())
            }
        }
    }
    if (discard) ConfirmDialog(stringResource(R.string.discard_changes), stringResource(R.string.discard_detail), onConfirm = { vm.closeEditor(); discard = false }, onDismiss = { discard = false })
}
@Composable private fun BasicCodeEditor(value: String, change: (String) -> Unit, transformation: SyntaxTransformation, modifier: Modifier) {
    androidx.compose.foundation.text.BasicTextField(value = value, onValueChange = { if (it.length <= 1_000_000) change(it) }, modifier = modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp), textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 22.sp), visualTransformation = transformation, cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { inner -> if (value.isEmpty()) Text("// Start writing…", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() })
}
@Composable private fun CheckpointsPane(vm: WorkspaceViewModel) {
    val snapshots by vm.snapshots.collectAsStateWithLifecycle(); val busy by vm.busy.collectAsStateWithLifecycle(); var restore by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.checkpoint_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 14.dp))
        Button(onClick = { vm.checkpoint("Manual checkpoint") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.AddAPhoto, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.create_checkpoint)) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (snapshots.isEmpty()) item { EmptyState(Icons.Outlined.History, stringResource(R.string.no_checkpoints), stringResource(R.string.no_checkpoints_detail), Modifier.fillMaxWidth()) }
            items(snapshots, key = { it.id }) { checkpoint -> GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Restore, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(checkpoint.label, style = MaterialTheme.typography.bodyMedium); Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(checkpoint.createdAt), style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { restore = checkpoint.id }, enabled = !busy) { Text(stringResource(R.string.restore)) }
            } } }
        }
    }
    restore?.let { ConfirmDialog(stringResource(R.string.restore_title), stringResource(R.string.restore_detail), onConfirm = { vm.restore(it); restore = null }, onDismiss = { restore = null }) }
}
