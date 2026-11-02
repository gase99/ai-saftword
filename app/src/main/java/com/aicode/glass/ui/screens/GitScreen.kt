@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.aicode.glass.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.glass.R
import com.aicode.glass.ui.components.*
import com.aicode.glass.ui.model.GitViewModel
import com.aicode.glass.ui.theme.GlassCard
import java.text.DateFormat

@Composable
fun GitScreen(vm: GitViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle(); val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle(); val notice by vm.notice.collectAsStateWithLifecycle(); val diff by vm.diff.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }; var message by rememberSaveable { mutableStateOf("") }
    var branches by remember { mutableStateOf(false) }; var newBranch by remember { mutableStateOf("") }; var create by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf(false) }; var remoteUrl by remember { mutableStateOf("") }; var push by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        SectionTitle(stringResource(R.string.git_title), stringResource(R.string.git_subtitle))
        if (!state.initialized) {
            EmptyState(Icons.Outlined.AccountTree, stringResource(R.string.git_start), stringResource(R.string.git_start_detail), Modifier.fillMaxWidth().padding(top = 36.dp))
            Button(onClick = vm::initialize, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.initialize_git)) }
        } else {
            GlassCard(Modifier.fillMaxWidth().padding(vertical = 10.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountTree, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(state.branch, fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.changed_files, state.files.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = vm::refresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh)) }
                TextButton(onClick = { branches = true }) { Text(stringResource(R.string.branches)) }
            } }
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.changes)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.history)) })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.remote)) })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when(tab) {
                0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.commit_message)) }, minLines = 2, maxLines = 4, shape = RoundedCornerShape(18.dp)) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = vm::stageAll, enabled = !busy && state.files.any { !it.staged }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.stage_all)) }
                        Button(onClick = { vm.commit(message); message = "" }, enabled = !busy && message.isNotBlank() && state.files.any { it.staged }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Check, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.commit)) }
                    } }
                    if (state.files.isEmpty()) item { EmptyState(Icons.Outlined.CheckCircleOutline, stringResource(R.string.clean_tree), stringResource(R.string.clean_tree_detail), Modifier.fillMaxWidth()) }
                    items(state.files, key = { it.path + it.staged }) { file -> GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp)) { Row(Modifier.clickable { vm.showDiff(file.path, file.staged) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (file.staged) Icons.Outlined.CheckCircle else Icons.Outlined.EditNote, null, tint = if (file.staged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(file.path, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${file.status} · ${stringResource(if (file.staged) R.string.staged else R.string.unstaged)}", style = MaterialTheme.typography.labelSmall) }
                        TextButton(onClick = { vm.stage(file.path, !file.staged) }, enabled = !busy) { Text(stringResource(if (file.staged) R.string.unstage else R.string.stage)) }
                    } } }
                }
                1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.commits.isEmpty()) item { EmptyState(Icons.Outlined.History, stringResource(R.string.no_commits), stringResource(R.string.no_commits_detail), Modifier.fillMaxWidth()) }
                    items(state.commits, key = { it.hash }) { commit -> GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp)) { Column(Modifier.clickable { vm.showCommit(commit.hash) }.padding(16.dp)) {
                        Text(commit.message, style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(8.dp)); Text("${commit.hash.take(7)} · ${commit.author}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary); Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(commit.time), style = MaterialTheme.typography.labelSmall)
                    } } }
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingsGroup(stringResource(R.string.remote_origin)) {
                        Text(state.remote.ifBlank { stringResource(R.string.no_remote) }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        OutlinedButton(onClick = { remoteUrl = state.remote; remote = true }, enabled = !busy) { Text(stringResource(R.string.configure_remote)) }
                        Text(stringResource(R.string.remote_security), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.sync("fetch") }, enabled = !busy && state.remote.isNotBlank(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.fetch)) }
                        OutlinedButton(onClick = { vm.sync("pull") }, enabled = !busy && state.remote.isNotBlank(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pull)) }
                        Button(onClick = { push = true }, enabled = !busy && state.remote.isNotBlank(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.push)) }
                    }
                    Text(stringResource(R.string.git_remote_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    ErrorNotice(error, vm::dismissError); ErrorNotice(notice, vm::dismissNotice)
    if (branches) AlertDialog(onDismissRequest = { branches = false }, title = { Text(stringResource(R.string.branches)) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { state.branches.forEach { branch -> TextButton(onClick = { vm.switch(branch); branches = false }, enabled = !busy && branch != state.branch, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.AccountTree, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(branch) } }; TextButton(onClick = { create = true; branches = false }) { Text(stringResource(R.string.new_branch)) } } }, confirmButton = { TextButton(onClick = { branches = false }) { Text(stringResource(R.string.close)) } })
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text(stringResource(R.string.new_branch)) }, text = { OutlinedTextField(value = newBranch, onValueChange = { newBranch = it }, label = { Text("feature/my-change") }, singleLine = true) }, confirmButton = { TextButton(enabled = newBranch.isNotBlank(), onClick = { vm.branch(newBranch, true); newBranch = ""; create = false }) { Text(stringResource(R.string.create_switch)) } }, dismissButton = { TextButton(onClick = { create = false }) { Text(stringResource(R.string.cancel)) } })
    if (remote) AlertDialog(onDismissRequest = { remote = false }, title = { Text(stringResource(R.string.configure_remote)) }, text = { OutlinedTextField(value = remoteUrl, onValueChange = { remoteUrl = it }, label = { Text("https://github.com/user/repo.git") }, singleLine = true) }, confirmButton = { TextButton(onClick = { vm.remote(remoteUrl); remote = false }, enabled = remoteUrl.isNotBlank()) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { remote = false }) { Text(stringResource(R.string.cancel)) } })
    if (push) ConfirmDialog(stringResource(R.string.push_title), stringResource(R.string.push_detail, state.branch), onConfirm = { vm.sync("push"); push = false }, onDismiss = { push = false })
    if (diff != null) ModalBottomSheet(onDismissRequest = vm::dismissDiff) { Column(Modifier.padding(20.dp).navigationBarsPadding()) { Text(stringResource(R.string.diff_viewer), style = MaterialTheme.typography.titleLarge); CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Text(highlightedCode(diff!!), modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 540.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(vertical = 16.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 18.sp, softWrap = false) } } }
}
