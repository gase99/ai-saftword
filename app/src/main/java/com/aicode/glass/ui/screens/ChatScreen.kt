@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.aicode.glass.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.glass.R
import com.aicode.glass.core.data.MessageEntity
import com.aicode.glass.ui.components.*
import com.aicode.glass.ui.model.ChatViewModel
import com.aicode.glass.ui.theme.GlassCard
import kotlinx.coroutines.launch
import java.text.DateFormat

@Composable
fun ChatScreen(onSettings: () -> Unit, onFiles: () -> Unit, vm: ChatViewModel = hiltViewModel()) {
    val prefs by vm.preferences.collectAsStateWithLifecycle(); val agent by vm.agent.state.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle(); val tools by vm.tools.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    var input by rememberSaveable(prefs.activeConversation) { mutableStateOf("") }
    val analysis by vm.analysts.state.collectAsStateWithLifecycle(); var showAnalysis by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }; var deleteId by remember { mutableStateOf<Long?>(null) }
    val list = rememberLazyListState(); val scope = rememberCoroutineScope()
    val busy = agent.busy && agent.conversationId == prefs.activeConversation
    val visible = messages.filter { it.role != "tool" && !(busy && it.id == agent.messageId) }
    LaunchedEffect(visible.size, agent.text, busy) { if (busy || visible.isNotEmpty()) list.scrollToItem((visible.size + if (busy) 1 else 0).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = prefs.agentMode == "build", onClick = { vm.mode("build") }, enabled = !agent.busy, label = { Text(stringResource(R.string.build_mode)) }, leadingIcon = { Icon(Icons.Outlined.Construction, null, Modifier.size(16.dp)) })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = prefs.agentMode == "ask", onClick = { vm.mode("ask") }, enabled = !agent.busy, label = { Text(stringResource(R.string.ask_mode)) }, leadingIcon = { Icon(Icons.Outlined.School, null, Modifier.size(16.dp)) })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showAnalysis = true }) { Icon(Icons.Outlined.AccountTree, stringResource(R.string.subagents)) }
            IconButton(onClick = { history = true }) { Icon(Icons.Outlined.History, stringResource(R.string.conversations)) }
            IconButton(onClick = vm::newConversation, enabled = !agent.busy) { Icon(Icons.Outlined.AddComment, stringResource(R.string.new_chat)) }
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(7.dp)); Text(prefs.configs.getValue(prefs.provider).model, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
            Spacer(Modifier.weight(1f)); TextButton(onClick = onFiles, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(stringResource(R.string.context_files), fontSize = 11.sp) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty() && !busy) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Column(Modifier.padding(bottom = 12.dp)) {
                            Text(stringResource(R.string.hero_eyebrow), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.hero_title), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.hero_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item { GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) {
                        Icon(Icons.Outlined.Key, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.connect_model), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                        Text(stringResource(R.string.connect_model_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                        TextButton(onClick = onSettings, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.open_settings)); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(16.dp)) }
                    } } }
                    items(listOf(R.string.prompt_review to Icons.Outlined.FactCheck, R.string.prompt_explain to Icons.Outlined.Code, R.string.prompt_plan to Icons.Outlined.AccountTree)) { (prompt, icon) ->
                        OutlinedCard(onClick = { input = "" }, modifier = Modifier.fillMaxWidth().clickable { input = when(prompt) { R.string.prompt_review -> "Review hello.py and suggest improvements."; R.string.prompt_explain -> "Read hello.py and explain how it works."; else -> "Plan a small Python project in this workspace." } }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .35f))) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(prompt), style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    item { Text(stringResource(R.string.privacy_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } else LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(visible, key = { it.id }) { message -> MessageBubble(message, tools.filter { it.messageId == message.id }) }
                if (busy) item { MessageBubble(MessageEntity(id = -1, conversationId = prefs.activeConversation, role = "assistant", content = agent.text.ifBlank { stringResource(if (agent.phase == "approval") R.string.waiting_approval else R.string.thinking) }, status = "streaming"), emptyList()) }
            }
        }
        if (agent.error != null) Card(Modifier.fillMaxWidth().padding(bottom = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(agent.error!!, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall); IconButton(onClick = vm.agent::clearError) { Icon(Icons.Outlined.Close, stringResource(R.string.dismiss)) } }
        }
        GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), RoundedCornerShape(26.dp)) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(value = input, onValueChange = { if (it.length <= 32000) input = it }, modifier = Modifier.weight(1f), placeholder = { Text(stringResource(R.string.chat_placeholder)) }, maxLines = 5, textStyle = MaterialTheme.typography.bodyMedium, colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
                if (busy) FilledIconButton(onClick = vm.agent::cancel, modifier = Modifier.padding(5.dp).size(46.dp)) { Icon(Icons.Outlined.Stop, stringResource(R.string.stop)) }
                else FilledIconButton(onClick = { vm.send(input); input = ""; scope.launch { list.scrollToItem(visible.size + 1) } }, enabled = input.isNotBlank() && !agent.busy && prefs.activeConversation > 0, modifier = Modifier.padding(5.dp).size(46.dp)) { Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.send)) }
            }
        }
    }
    if(showAnalysis) ModalBottomSheet(onDismissRequest = { showAnalysis = false }) {
        Column(Modifier.padding(20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.subagents), stringResource(R.string.analysis_detail))
            Button(onClick = { vm.analysts.start("Review this workspace for architecture, security and test coverage. Identify concrete findings and next steps.", prefs.activeWorkspace) }, enabled = !analysis.running && !agent.busy) { Text(stringResource(R.string.analyze_workspace)) }
            if(analysis.running) TextButton(onClick = vm.analysts::cancel) { Text(stringResource(R.string.cancel_analysis)) }
            analysis.tasks.forEach { task -> GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(task.role + " · " + task.status, style = MaterialTheme.typography.titleSmall); if(task.output.isNotBlank()) MarkdownText(task.output) } } }
        }
    }
    if (history) ModalBottomSheet(onDismissRequest = { history = false }) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 32.dp).navigationBarsPadding()) {
            SectionTitle(stringResource(R.string.conversations))
            LazyColumn(Modifier.heightIn(max = 440.dp)) { items(conversations, key = { it.id }) { conversation ->
                Row(Modifier.fillMaxWidth().clickable { vm.select(conversation.id); history = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.Chat, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(DateFormat.getDateInstance(DateFormat.SHORT).format(conversation.updatedAt), style = MaterialTheme.typography.labelSmall) }
                    IconButton(onClick = { deleteId = conversation.id }, enabled = !agent.busy) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete)) }
                }; HorizontalDivider()
            } }
        }
    }
    deleteId?.let { id -> ConfirmDialog(stringResource(R.string.delete_chat), stringResource(R.string.delete_chat_detail), onConfirm = { vm.delete(id); deleteId = null }, onDismiss = { deleteId = null }) }
    val pending = agent.pending
    if (pending != null) AlertDialog(onDismissRequest = { vm.agent.approve(false) }, icon = { Icon(Icons.Outlined.Shield, null) }, title = { Text(stringResource(R.string.approve_edit)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Text(pending.name + " · " + pending.preview.path, fontWeight = FontWeight.SemiBold) }
            Text(stringResource(R.string.approval_detail), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
            Text(stringResource(if(pending.name.startsWith("mcp_")) R.string.external_args else R.string.proposed_content), style = MaterialTheme.typography.labelMedium)
            Text(pending.preview.after.take(12000), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, softWrap = false)
        }
    }, confirmButton = { Button(onClick = { vm.agent.approve(true) }) { Text(stringResource(R.string.approve)) } }, dismissButton = { TextButton(onClick = { vm.agent.approve(false) }) { Text(stringResource(R.string.reject)) } })
}
@Composable private fun previewTitle(name: String, path: String) = CompositionLocalProvider(LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) { Text("$name · $path") }
@Composable private fun MessageBubble(message: MessageEntity, tools: List<com.aicode.glass.core.data.ToolInvocationEntity>) {
    val user = message.role == "user"; val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text(stringResource(if (user) R.string.you else R.string.assistant), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
        GlassCard(Modifier.fillMaxWidth(if (user) .92f else 1f), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = if (user) 22.dp else 5.dp, bottomEnd = if (user) 5.dp else 22.dp)) {
            Column(Modifier.padding(16.dp)) {
                if (user) Text(message.content, style = MaterialTheme.typography.bodyMedium)
                else if (message.content.isNotBlank()) MarkdownText(message.content)
                if (message.status == "streaming") LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = MaterialTheme.colorScheme.primary)
                tools.forEach { tool -> var expanded by remember(tool.id) { mutableStateOf(false) }
                    Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (tool.status == "error") Icons.Outlined.ErrorOutline else Icons.Outlined.BuildCircle, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(tool.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f)); Text(stringResource(when(tool.status) { "complete" -> R.string.tool_complete; "error" -> R.string.tool_error; "running" -> R.string.tool_running; "rejected" -> R.string.tool_rejected; else -> R.string.tool_pending }), style = MaterialTheme.typography.labelSmall) }
                        if (expanded) { Text(tool.arguments, style = MaterialTheme.typography.labelSmall, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)); if (tool.result.isNotBlank()) Text(tool.result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                    } }
                }
                message.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (message.status == "interrupted") Text(stringResource(R.string.interrupted), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        if (!user && message.status != "streaming") IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy), Modifier.size(14.dp)) }
    }
}
