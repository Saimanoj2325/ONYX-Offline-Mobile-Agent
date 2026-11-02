package com.offlineagent.app.ui.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.data.formatBytes
import com.offlineagent.app.models.DownloadState
import com.offlineagent.app.ui.markdown.MarkdownActions
import com.offlineagent.app.ui.markdown.MarkdownView
import com.offlineagent.app.ui.openui.OpenUiHost
import com.offlineagent.app.ui.openui.OpenUiMessageView
import com.offlineagent.app.ui.openui.OpenUiProgramView
import com.offlineagent.core.data.MessageEntity
import com.offlineagent.core.inference.EngineState
import com.offlineagent.core.models.HfUrls
import com.offlineagent.core.models.ModelCatalog
import com.offlineagent.core.models.ModelKind
import com.offlineagent.core.runtime.chat.ChatLogic
import com.offlineagent.core.runtime.chat.ComposerMode
import com.offlineagent.core.runtime.chat.ConversationRef
import com.offlineagent.core.runtime.skills.Skill
import com.offlineagent.core.runtime.ui.AnswerMode
import com.offlineagent.core.runtime.ui.OpenUiBridge
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import java.io.File

private fun tokenLabel(n: Int) = if (n >= 1000) "%.1fK".format(n / 1000.0) else n.toString()

private enum class Sheet { ATTACH, TOOLS, MODELS, DOCUMENTS, SKILLS, RUNS, MACROS, ONBOARDING }

/**
 * Phase 4: the whole app is this one ChatGPT-style chat. Drawer = conversations (+ Settings); top bar = model picker +
 * new chat; centre = thread (user bubbles right, assistant replies full width, OpenUI components inline, agent steps,
 * approvals, canvases, PDFs and automation logs as cards); bottom = composer with "+" attachments and a tools/mode toggle.
 * Documents, skills, runs, macros and model downloads open as bottom sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onShare: (String) -> Unit, onOpenSettings: () -> Unit, skillsHost: com.offlineagent.feature.workflow.SkillsHost,
               runsHost: com.offlineagent.feature.workflow.RunsHost, notify: (String) -> Unit, modifier: Modifier = Modifier) {
    val app = vm.getApplication<OfflineAgentApp>()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val live by vm.agentLive.collectAsStateWithLifecycle()
    val agentPartial by vm.agentPartial.collectAsStateWithLifecycle()
    val automationPartial by vm.automationPartial.collectAsStateWithLifecycle()
    val chatSettings by vm.settings.collectAsStateWithLifecycle()
    val chatModels by vm.chatModels.collectAsStateWithLifecycle()
    val downloads by app.modelDownloads.states.collectAsStateWithLifecycle()
    val pending by app.agents.pending.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    val onboardingDismissed by app.onyx.onboardingDismissed.collectAsStateWithLifecycle()
    val busy = ui.streaming != null || live.isNotEmpty() || vm.busy

    // First launch with no chat model: the onboarding sheet recommends one by RAM.
    LaunchedEffect(chatModels.isEmpty(), downloads.values.any { it.active }) {
        if (chatModels.isEmpty() && !onboardingDismissed && sheet == null && downloads.values.none { it.active }) {
            kotlinx.coroutines.delay(400)
            if (vm.chatModels.value.isEmpty()) sheet = Sheet.ONBOARDING
        }
    }

    val host = remember(vm) {
        OpenUiHost(
            send = { t -> vm.send(t) },
            openUrl = { url -> clipboard.setText(AnnotatedString(url)); vm.notice("Links don't open automatically (offline app). Link copied.") },
            resolveImage = { vm.resolveImage(it) },
        )
    }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ConversationDrawer(vm, onPick = { id -> vm.openChat(id); scope.launch { drawer.close() } },
            onNew = { vm.newChat(); scope.launch { drawer.close() } },
            onSettings = { scope.launch { drawer.close() }; onOpenSettings() })
    }) {
        Column(modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Conversations") } },
                title = { ModelPicker(vm, engine, ui, chatModels, onDownload = { sheet = Sheet.MODELS }) },
                actions = {
                    IconButton(onClick = { vm.newChat() }) { Icon(Icons.Filled.Create, "New chat") }
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        val settings by vm.settings.collectAsStateWithLifecycle()
                        DropdownMenuItem(text = { Text("Thinking (Qwen3, slower)") }, trailingIcon = { Switch(settings.thinking, { vm.setThinking(it) }) }, onClick = { vm.setThinking(!settings.thinking) })
                        DropdownMenuItem(text = { Text("Use my documents") }, trailingIcon = { Switch(settings.useDocuments, { vm.setUseDocuments(it) }) }, onClick = { vm.setUseDocuments(!settings.useDocuments) })
                        DropdownMenuItem(text = { Text("Export chat as PDF") }, enabled = messages.isNotEmpty(), onClick = { menu = false; vm.exportChatPdf() })
                        DropdownMenuItem(text = { Text("Rename chat") }, enabled = chat != null, onClick = { menu = false; renameTarget = chat?.let { ConversationRef(it.id, it.title, it.updatedAt) } })
                    }
                },
            )
            SafetyBanners(vm)
            ui.unfinishedRun?.let { r ->
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Unfinished agent run: “${r.request.take(40)}”", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.resumeUnfinished() }) { Text("Resume") }
                        TextButton(onClick = { vm.discardUnfinished() }) { Text("Discard") }
                    }
                }
            }
            downloads.values.firstOrNull { it.active }?.let { d -> DownloadStrip(d, onOpen = { sheet = Sheet.MODELS }) }

            val listState = rememberLazyListState()
            val count = messages.size + (if (ui.streaming != null) 1 else 0) + (if (pending != null) 1 else 0)
            // New message: jump to the end. Streaming: follow only if the user is at the bottom.
            LaunchedEffect(messages.size, pending != null) { if (count > 0) listState.animateScrollToItem(count - 1) }
            LaunchedEffect(ui.streaming?.text?.length, ui.streaming?.reasoning?.length) {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (ChatLogic.shouldAutoScroll(last, count, listState.isScrollInProgress) && count > 0) listState.scrollToItem(count - 1, Int.MAX_VALUE / 2)
            }
            // Phase 5b: follow the agents' live trace / streaming answer when the user is at the bottom.
            val onyxBoard by app.onyx.current.collectAsStateWithLifecycle()
            val onyxTick by remember(onyxBoard) { onyxBoard?.live?.map { m -> m.values.sumOf { it.text.length } } ?: kotlinx.coroutines.flow.flowOf(0) }.collectAsStateWithLifecycle(0)
            LaunchedEffect(onyxTick) {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (onyxTick > 0 && count > 0 && ChatLogic.shouldAutoScroll(last, count, listState.isScrollInProgress)) listState.scrollToItem(count - 1, Int.MAX_VALUE / 2)
            }
            if (messages.isEmpty() && ui.streaming == null) {
                EmptyState(Modifier.weight(1f), onPick = { s -> vm.setMode(s.mode); vm.send(s.prompt) })
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(messages, key = { it.id }) { m ->
                    MessageItem(m, vm, host.copyWith(interactive = !busy), busy, live = m.runId?.let { live[it] }, onCopy = { clipboard.setText(AnnotatedString(it)); vm.notice("Copied") },
                        partial = when {
                            m.kind == "agent" -> m.runId?.let { agentPartial[it] }
                            m.kind == ChatViewModel.KIND_AUTOMATION && m.text.isBlank() -> automationPartial.takeIf { it.isNotBlank() }
                            else -> null
                        })
                }
                ui.streaming?.let { s -> item(key = "streaming") { StreamingItem(s, host.copyWith(interactive = false)) } }
                pending?.let { p -> item(key = "approval") { ApprovalCard(p, onAnswer = { allow, always -> app.agents.answerApproval(allow, always) }) } }
            }
            Composer(vm, ui, busy, thinkingDefault = chatSettings.thinking, onPlus = { sheet = Sheet.ATTACH }, onTools = { sheet = Sheet.TOOLS })
        }
    }

    when (sheet) {
        Sheet.ATTACH -> AttachSheet(vm, onDismiss = { sheet = null }, onDocuments = { sheet = Sheet.DOCUMENTS })
        Sheet.TOOLS -> ToolsSheet(ui.mode, onPick = { vm.setMode(it); sheet = null }, onDismiss = { sheet = null },
            onOpen = { sheet = it }, onSkill = { s -> sheet = null; vm.runSkill(s, "") }, vm = vm)
        Sheet.MODELS -> com.offlineagent.app.ui.models.ModelsSheet(app, onDismiss = { sheet = null }, notify = notify)
        Sheet.ONBOARDING -> com.offlineagent.app.ui.models.OnboardingSheet(app, onDismiss = { sheet = null; app.onyx.onboardingDismissed.value = true }, onMore = { sheet = Sheet.MODELS })
        Sheet.DOCUMENTS -> FullSheet("Documents (RAG)", { sheet = null }) { com.offlineagent.app.ui.library.DocumentsScreen(app, notify, Modifier.fillMaxSize()) }
        Sheet.SKILLS -> FullSheet("Skills", { sheet = null }) { com.offlineagent.feature.workflow.SkillsScreen(skillsHost, notify, Modifier.fillMaxSize()) }
        Sheet.RUNS -> FullSheet("Agent runs", { sheet = null }) { com.offlineagent.feature.workflow.RunsScreen(runsHost, notify, Modifier.fillMaxSize()) }
        Sheet.MACROS -> MacrosSheet(app, vm, onDismiss = { sheet = null })
        null -> Unit
    }
    renameTarget?.let { c -> RenameDialog(c.title, onDismiss = { renameTarget = null }, onSave = { vm.renameChat(c.id, it); renameTarget = null }) }
    ui.pdf?.let { pdf -> com.offlineagent.app.ui.PdfPreviewDialog(pdf, onDismiss = { vm.closePdf() }) }
    ui.citation?.let { c ->
        AlertDialog(onDismissRequest = { vm.showCitation(null) }, title = { Text("[${c.n}] ${c.title}") },
            text = { SelectionContainer { Text(c.text, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { vm.showCitation(null) }) { Text("Close") } })
    }
}

private var renameTarget by mutableStateOf<ConversationRef?>(null)

private fun OpenUiHost.copyWith(interactive: Boolean) = OpenUiHost(send, openUrl, resolveImage, markdown, interactive)

// ---------------------------------------------------------------- top bar

@Composable
private fun ModelPicker(vm: ChatViewModel, engine: EngineState, ui: ChatUi, models: List<com.offlineagent.core.data.ModelEntity>, onDownload: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = models.firstOrNull { it.active }
    Box {
        Column(Modifier.clickable { open = true }.padding(vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(active?.name?.removeSuffix(".gguf") ?: "No model", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                Icon(Icons.Filled.ArrowDropDown, "Choose model")
            }
            val sub = when (engine) {
                is EngineState.Loading -> "Loading…"
                is EngineState.Generating -> "Generating"
                is EngineState.Failed -> "Load failed"
                is EngineState.Ready -> "Ready" + if (ui.contextSize > 0) " · ${tokenLabel(ui.contextUsed)}/${tokenLabel(ui.contextSize)}" else ""
                else -> "On-device · offline"
            }
            Text(sub, style = MaterialTheme.typography.labelSmall, color = if (engine is EngineState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }) {
            models.forEach { m ->
                DropdownMenuItem(text = { Text(m.name.removeSuffix(".gguf") + "  ·  " + formatBytes(m.sizeBytes)) }, leadingIcon = { RadioButton(m.active, null) },
                    onClick = { open = false; vm.selectModel(m) })
            }
            if (models.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(text = { Text("Download models…") }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = { open = false; onDownload() })
        }
    }
}

@Composable
private fun DownloadStrip(d: DownloadState, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("Downloading ${d.name}… " + (d.fraction?.let { "${(it * 100).toInt()}%" } ?: ""), style = MaterialTheme.typography.bodySmall)
            d.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- drawer

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationDrawer(vm: ChatViewModel, onPick: (Long) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val chats by vm.chatList.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<ConversationRef?>(null) }
    val refs = remember(chats, query) { ChatLogic.filterConversations(chats.map { ConversationRef(it.id, it.title, it.updatedAt) }, query) }
    val now = remember(chats) { System.currentTimeMillis() }
    ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
        Column(Modifier.fillMaxHeight()) {
            OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Search chats") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp))
            NavigationDrawerItem(label = { Text("New chat") }, icon = { Icon(Icons.Filled.Create, null) }, selected = false, onClick = onNew, modifier = Modifier.padding(horizontal = 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                var lastGroup = ""
                refs.forEach { c ->
                    val g = ChatLogic.group(c.updatedAt, now)
                    if (g != lastGroup) { lastGroup = g; item(key = "g-$g") { Text(g, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)) } }
                    item(key = c.id) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            NavigationDrawerItem(label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, selected = c.id == ui.chatId,
                                onClick = { onPick(c.id) }, badge = { IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.MoreVert, "Chat options") } },
                                modifier = Modifier.padding(horizontal = 8.dp))
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renameTarget = c })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = c })
                            }
                        }
                    }
                }
                if (refs.isEmpty()) item { Text(if (query.isBlank()) "No chats yet." else "No chats match “$query”.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider()
            NavigationDrawerItem(label = { Text("Settings") }, icon = { Icon(Icons.Filled.Settings, null) }, selected = false, onClick = onSettings, modifier = Modifier.padding(8.dp))
        }
    }
    confirmDelete?.let { c ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete chat?") }, text = { Text("“${c.title}” and its canvases will be deleted from this phone.") },
            confirmButton = { TextButton(onClick = { vm.deleteChat(c.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(current) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rename chat") }, text = { OutlinedTextField(t, { t = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(t.trim().ifBlank { current }) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ---------------------------------------------------------------- thread

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(modifier: Modifier, onPick: (ChatLogic.Suggestion) -> Unit) {
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(ChatLogic.greeting(hour), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Runs fully on this phone.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatLogic.SUGGESTIONS.forEach { s ->
                OutlinedCard(onClick = { onPick(s) }, modifier = Modifier.widthIn(max = 170.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall)
                        Text(s.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(m: MessageEntity, vm: ChatViewModel, host: OpenUiHost, busy: Boolean, live: String?, onCopy: (String) -> Unit, partial: String? = null) {
    if (m.role == "user") {
        var menu by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            val atts = remember(m.id) { vm.attachmentsOf(m) }
            Row { atts.forEach { f -> Thumbnail(f, 140) } }
            if (m.text.isNotBlank()) Box {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                    modifier = Modifier.widthIn(max = 320.dp).combinedClickable(onClick = {}, onLongClick = { menu = true })) {
                    Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy(m.text) })
                    DropdownMenuItem(text = { Text("Edit & resend") }, enabled = !busy, onClick = { menu = false; vm.startEdit(m) })
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (m.kind) {
            "canvas" -> CanvasCard(m, vm, host)
            "agent" -> AgentRunCard(m, vm, live, host, partial)
            ChatViewModel.KIND_ONYX -> OnyxMessage(m, vm, host)
            ChatViewModel.KIND_AUTOMATION -> AutomationCard(m, partial)
            else -> {
                if (m.reasoning.isNotBlank()) ThinkingSection(m.reasoning, live = false)
                val cites = remember(m.id) { vm.citationsOf(m) }
                val h = remember(m.id, host.interactive) {
                    OpenUiHost(host.send, host.openUrl, host.resolveImage, MarkdownActions(
                        onCitation = { n -> cites.firstOrNull { it.n == n }?.let { vm.showCitation(it) } },
                        onLink = { url -> host.openUrl(url) }), host.interactive)
                }
                SelectionContainer { OpenUiMessageView(m.text, streaming = false, host = h) }
                if (cites.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cites.forEach { c -> AssistChip(onClick = { vm.showCitation(c) }, label = { Text("[${c.n}] ${c.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                }
                if (m.text.contains("**PDF saved:**")) TextButton(onClick = { vm.previewLastPdf() }) { Text("Open PDF preview") }
            }
        }
        m.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m.text.isNotBlank()) TextButton(onClick = { onCopy(com.offlineagent.core.openui.OpenUiMessage.toPlainText(m.text)) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Copy", style = MaterialTheme.typography.labelMedium) }
            if (m.kind == "markdown" || m.kind == "canvas" || m.kind == ChatViewModel.KIND_ONYX || m.error != null) TextButton(onClick = { vm.regenerate(m.id) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Regenerate", style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.weight(1f))
            m.statsText?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * Collapsible reasoning. Phase 4b: while the model is thinking ([live]) it is expanded and streams the latest
 * reasoning; when the answer starts it collapses by itself (unless the user opened/closed it by hand).
 */
@Composable
private fun ThinkingSection(reasoning: String, live: Boolean) {
    var userChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val open = userChoice ?: live
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable { userChoice = !open }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp); Spacer(Modifier.width(6.dp)) }
                Text((if (live) "Thinking…" else "Thought") + " (${reasoning.length} chars) " + if (open) "▲" else "▼", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                // While streaming only the tail is shown (it keeps up with the model without long re-layouts).
                val shown = if (live && reasoning.length > 1500) "…" + reasoning.takeLast(1500).trimStart() else reasoning.trim()
                Text(shown, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusLine(s: Streaming) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
            Text(s.phase, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (s.promptTotal > 0 && s.promptDone < s.promptTotal) {
            androidx.compose.material3.LinearProgressIndicator(progress = { (s.promptDone.toFloat() / s.promptTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(0.6f))
        }
    }
}

@Composable
private fun StreamingItem(s: Streaming, host: OpenUiHost) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.reasoning.isNotBlank()) ThinkingSection(s.reasoning, live = s.thinking)
        when {
            // Nothing visible yet (loading, reading the prompt, or between </think> and the answer): status, never blank.
            s.text.isBlank() -> if (!s.thinking || s.reasoning.isBlank()) StatusLine(s)
            s.mode == AnswerMode.CANVAS_NEW || s.mode == AnswerMode.CANVAS_UPDATE -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) { Text("▦ ${s.phase}", style = MaterialTheme.typography.labelMedium); OpenUiProgramView(s.text, streaming = true, host = host) }
            }
            else -> OpenUiMessageView(s.text, streaming = true, host = host)
        }
        val footer = s.footer.ifBlank { if (s.tokens > 0) "%d tok · %.1f tok/s".format(s.tokens, s.tokensPerSecond) else "" }
        if (footer.isNotBlank()) Text(footer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CanvasCard(m: MessageEntity, vm: ChatViewModel, host: OpenUiHost) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val versions by vm.canvasVersions.collectAsStateWithLifecycle()
    val latestOfThisMessage = m.canvasVersion != null && m.canvasVersion == versions.maxOfOrNull { it.version }
    // The newest canvas message follows undo/redo; older messages show the version they created.
    val shownVersion = if (latestOfThisMessage) chat?.canvasVersion ?: m.canvasVersion else m.canvasVersion
    val stored = versions.firstOrNull { it.version == shownVersion }?.docJson ?: m.uiJson
    val source = remember(stored) { stored?.let { OpenUiBridge.toOpenUiSource(it) } }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("▦ Canvas v${shownVersion ?: "?"}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (latestOfThisMessage) {
                    TextButton(onClick = { vm.undoCanvas() }, enabled = (chat?.canvasVersion ?: 0) > 1) { Text("‹ Undo") }
                    TextButton(onClick = { vm.redoCanvas() }, enabled = (chat?.canvasVersion ?: 0) < (versions.maxOfOrNull { it.version } ?: 0)) { Text("Redo ›") }
                }
            }
            if (source != null) OpenUiProgramView(source, streaming = false, host = host) else Text(m.text)
            Row {
                TextButton(onClick = { vm.setMode(ComposerMode.CANVAS) }) { Text("Edit with a message") }
                TextButton(onClick = { vm.exportCanvasPdf() }) { Text("Export PDF") }
            }
        }
    }
}

@Composable
private fun AgentRunCard(m: MessageEntity, vm: ChatViewModel, live: String?, host: OpenUiHost, partial: String? = null) {
    var expanded by rememberSaveable(m.id) { mutableStateOf(false) }
    val stepsFlow = remember(m.runId) { m.runId?.let { vm.observeSteps(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
    val steps by stepsFlow.collectAsState(initial = emptyList())
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚙ ${m.reasoning.ifBlank { "Agent" }}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide steps" else "Steps (${steps.size})") }
            }
            if (live != null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Text("  $live", style = MaterialTheme.typography.bodySmall)
            }
            // Phase 4b: the current step's model output streams here while it is being written.
            if (live != null && !partial.isNullOrBlank()) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text(if (partial.length > 600) "…" + partial.takeLast(600) else partial, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            steps.firstOrNull { it.kind == "PLAN" }?.let { p ->
                Text("Plan", style = MaterialTheme.typography.labelMedium)
                p.output.lines().filter { it.isNotBlank() }.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
            }
            steps.filter { it.kind == "TOOL" || it.tool != null }.takeLast(if (expanded) 50 else 3).forEach { s ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text("🔧 ${s.tool ?: s.kind.lowercase()} · ${s.durationMs} ms", style = MaterialTheme.typography.labelMedium)
                        if (expanded && s.input.isNotBlank()) Text("in: " + s.input.take(300), style = MaterialTheme.typography.bodySmall)
                        if (s.output.isNotBlank()) Text("out: " + s.output.take(if (expanded) 600 else 120), style = MaterialTheme.typography.bodySmall)
                        s.error?.let { Text("error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (m.text.isNotBlank()) OpenUiMessageView(m.text, streaming = false, host = host)
            if (m.canvasVersion != null) Text("Canvas updated (v${m.canvasVersion}) — see the canvas card.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AutomationCard(m: MessageEntity, partial: String? = null) {
    var open by rememberSaveable(m.id) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("📱 ${m.reasoning}", style = MaterialTheme.typography.titleSmall)
            if (m.text.isBlank()) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Text("  Running on your screen… (stop from the overlay)", style = MaterialTheme.typography.bodySmall) }
            if (m.text.isBlank() && !partial.isNullOrBlank()) Text(if (partial.length > 400) "…" + partial.takeLast(400) else partial, style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text(m.text, style = MaterialTheme.typography.bodyMedium)
            val log = m.uiJson.orEmpty()
            if (log.isNotBlank()) {
                TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) { Text(if (open) "Hide action log" else "Action log (${log.lines().size})") }
                if (open) Text(log, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ApprovalCard(p: com.offlineagent.app.data.PendingApproval, onAnswer: (Boolean, Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Allow “${p.spec.name}”?", style = MaterialTheme.typography.titleSmall)
            Text(p.spec.description, style = MaterialTheme.typography.bodySmall)
            Text("Effect: ${p.reason}", style = MaterialTheme.typography.bodySmall)
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
                Text(p.call.args.entries.joinToString("\n") { (k, v) -> "$k: ${v.toString().trim('"').take(300)}" }, Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAnswer(true, false) }) { Text("Allow once") }
                if (p.spec.permission.canAlwaysAllow && p.spec.permission != com.offlineagent.core.runtime.tools.Permission.READ_ONLY)
                    OutlinedButton(onClick = { onAnswer(true, true) }) { Text("Always") }
                TextButton(onClick = { onAnswer(false, false) }) { Text("Deny") }
            }
        }
    }
}

@Composable
fun Thumbnail(f: File, sizeDp: Int, onRemove: (() -> Unit)? = null) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, f) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }
    }
    Box(Modifier.padding(2.dp)) {
        bmp?.let { Image(it, "attached photo", contentScale = ContentScale.Crop, modifier = Modifier.size(sizeDp.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))) }
            ?: Box(Modifier.size(sizeDp.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)))
        if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(24.dp).align(Alignment.TopEnd)) { Icon(Icons.Filled.Close, "Remove") }
    }
}

// ---------------------------------------------------------------- composer

@Composable
private fun Composer(vm: ChatViewModel, ui: ChatUi, busy: Boolean, thinkingDefault: Boolean, onPlus: () -> Unit, onTools: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(ui.editing?.id) { ui.editing?.let { text = it.text } }
    Surface(tonalElevation = 3.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.editing?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Editing your message — sending replaces it and the answers after it.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.cancelEdit(); text = "" }) { Text("Cancel") }
                }
            }
            if (ui.pendingImages.isNotEmpty()) Row { ui.pendingImages.forEach { f -> Thumbnail(f, 64) { vm.removePendingImage(f) } } }
            ui.skillSuggestion?.let { s -> AssistChip(onClick = { vm.runSkill(s, text); text = "" }, label = { Text("Run skill: ${s.name}") }) }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onPlus, enabled = !busy) { Icon(Icons.Filled.Add, "Attach") }
                OutlinedTextField(
                    value = text, onValueChange = { text = it; vm.onTextChanged(it) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 180.dp),
                    placeholder = { Text(if (ui.pendingImages.isNotEmpty()) "Ask about the photo…" else ui.mode.hint) },
                    maxLines = 6, shape = RoundedCornerShape(24.dp),
                )
                Spacer(Modifier.width(6.dp))
                if (busy) FilledIconButton(onClick = { vm.stop() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.onError))
                } else FilledIconButton(onClick = {
                    val t = text
                    text = ""
                    if (ui.editing != null) vm.submitEdit(t) else vm.send(t)
                }, enabled = text.isNotBlank() || ui.pendingImages.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = ui.mode != ComposerMode.CHAT, onClick = onTools, label = { Text(if (ui.mode == ComposerMode.CHAT) "Tools" else ui.mode.label) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) })
                if (ui.mode != ComposerMode.CHAT) TextButton(onClick = { vm.setMode(ComposerMode.CHAT) }) { Text("✕ back to chat") }
                if (ui.mode == ComposerMode.CHAT) {
                    // Phase 5b: multi-agent switch — auto (task-like requests use the agents), always, or never.
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = ui.agents != AgentsMode.OFF, onClick = { vm.cycleAgentsMode() }, label = { Text(ui.agents.label) })
                    // Phase 4b: per-message thinking toggle (Settings → Thinking is the default).
                    val think = ui.thinkOverride ?: thinkingDefault
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = think, onClick = { vm.toggleThinkOnce() }, label = { Text(if (think) "Think: on" else "Think: off") })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(vm: ChatViewModel, onDismiss: () -> Unit, onDocuments: () -> Unit) {
    val context = LocalContext.current
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.attachImage(uri); onDismiss() }
    var camFile by remember { mutableStateOf<File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> camFile?.let { if (ok) vm.attachCameraFile(it) else it.delete() }; onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding()) {
            ListItem(headlineContent = { Text("Photo") }, supportingContent = { Text("Ask about an image (needs a vision model)") },
                modifier = Modifier.clickable { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            ListItem(headlineContent = { Text("Camera") }, supportingContent = { Text("Take a photo now") }, modifier = Modifier.clickable {
                val f = vm.cameraTarget(); camFile = f
                runCatching { takePhoto.launch(FileProvider.getUriForFile(context, context.packageName + ".files", f)) }.onFailure { vm.notice("No camera app available"); onDismiss() }
            })
            ListItem(headlineContent = { Text("Document") }, supportingContent = { Text("PDF, text or Markdown for answers with citations (RAG)") }, modifier = Modifier.clickable { onDocuments() })
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ToolsSheet(mode: ComposerMode, onPick: (ComposerMode) -> Unit, onDismiss: () -> Unit, onOpen: (Sheet) -> Unit, onSkill: (Skill) -> Unit, vm: ChatViewModel) {
    val skills by produceState(emptyList<Skill>()) { value = vm.getApplication<OfflineAgentApp>().skills.parsed() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Mode", style = MaterialTheme.typography.titleMedium)
            ComposerMode.entries.forEach { m ->
                ListItem(headlineContent = { Text(m.label) }, supportingContent = { Text(modeHint(m)) }, leadingContent = { RadioButton(m == mode, null) },
                    modifier = Modifier.clickable { onPick(m) })
            }
            HorizontalDivider()
            Text("Open", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { onOpen(Sheet.DOCUMENTS) }, label = { Text("Documents") })
                AssistChip(onClick = { onOpen(Sheet.SKILLS) }, label = { Text("Skills") })
                AssistChip(onClick = { onOpen(Sheet.RUNS) }, label = { Text("Agent runs") })
                AssistChip(onClick = { onOpen(Sheet.MACROS) }, label = { Text("Macros") })
                AssistChip(onClick = { onOpen(Sheet.MODELS) }, label = { Text("Models") })
            }
            if (skills.isNotEmpty()) {
                Text("Run a skill", style = MaterialTheme.typography.titleMedium)
                skills.forEach { s -> ListItem(headlineContent = { Text(s.name) }, supportingContent = { Text(s.description, maxLines = 2) }, modifier = Modifier.clickable { onSkill(s) }) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun modeHint(m: ComposerMode) = when (m) {
    ComposerMode.CHAT -> "Normal answers; tables, charts and forms appear inline when they help"
    ComposerMode.AGENT -> "Plans and calls tools (documents, notes, calculator, MCP…), asks before acting"
    ComposerMode.CANVAS -> "Builds a UI you can refine with follow-ups (versions + undo)"
    ComposerMode.PDF_REPORT -> "Writes a multi-page report section by section and saves a PDF"
    ComposerMode.PHONE -> "Alarms, reminders, apps, message drafts, device settings — with approvals"
    ComposerMode.AUTOMATION -> "Does a task on your screen with the accessibility service (overlay to stop)"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MacrosSheet(app: OfflineAgentApp, vm: ChatViewModel, onDismiss: () -> Unit) {
    val macros by app.automation.macros.macros.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Macros", style = MaterialTheme.typography.titleLarge)
            Text("Recorded in Settings → Automation & macros. Runs show as a card in this chat.", style = MaterialTheme.typography.bodySmall)
            if (macros.isEmpty()) Text("No macros yet.", Modifier.padding(vertical = 16.dp))
            macros.forEach { m -> ListItem(headlineContent = { Text(m.name) }, supportingContent = { Text("${m.steps.size} steps" + if (m.description.isNotBlank()) " · ${m.description}" else "") },
                trailingContent = { TextButton(onClick = { onDismiss(); vm.runMacro(m) }) { Text("Run") } }) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Phase 5b: a multi-agent turn in the thread — the collapsible live trace (planner, agents, approvals, verification)
 * and below it the Writer's answer streaming token by token. Older messages load their trace from the task store.
 */
@Composable
private fun OnyxMessage(m: MessageEntity, vm: ChatViewModel, host: OpenUiHost) {
    val app = vm.getApplication<OfflineAgentApp>()
    val boards by app.onyx.boards.collectAsStateWithLifecycle()
    var saved by remember(m.runId) { mutableStateOf<com.offlineagent.core.agents.Blackboard?>(null) }
    LaunchedEffect(m.runId) { val id = m.runId; if (id != null && app.onyx.board(id) == null) saved = app.onyx.loadBoard(id) }
    val board = m.runId?.let { boards[it] } ?: saved
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (board != null) com.offlineagent.app.ui.onyx.AgentTraceCard(board, initiallyExpanded = !board.state.value.status.isTerminal,
            canAlwaysAllow = { app.onyx.pending.value?.canAlwaysAllow == true }, onApproval = { allow, always -> app.onyx.answer(allow, always) })
        else if (m.runId == null && m.text.isBlank() && m.error == null) Text("Starting the agents…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val streamed = board?.let { com.offlineagent.app.ui.onyx.liveAnswer(it) } ?: ""
        val text = m.text.ifBlank { streamed }
        if (text.isNotBlank()) SelectionContainer { OpenUiMessageView(text, streaming = m.text.isBlank(), host = host) }
    }
}

/** P0 (Phase 4): "last run crashed → safe mode" and self-test / compatibility-mode banners. */
@Composable
fun SafetyBanners(vm: ChatViewModel) {
    val app = vm.getApplication<OfflineAgentApp>()
    val crash by app.lastCrash.collectAsStateWithLifecycle()
    val health by app.engineController.health.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    crash?.let { c ->
        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Last run crashed — started in safe mode (no model loaded until you send a message).", style = MaterialTheme.typography.bodySmall)
                Text(c.reason.take(160), style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(onClick = { vm.shareDiagnostics() }) { Text("Share crash log") }
                    TextButton(onClick = { app.lastCrash.value = null }) { Text("Dismiss") }
                }
            }
        }
    }
    health.message?.let { msg ->
        Surface(color = if (health.needsRestart || health.selfTest?.ok == false) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(msg, style = MaterialTheme.typography.bodySmall)
                Row {
                    if (health.needsRestart) TextButton(onClick = {
                        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                        if (i != null) { ctx.startActivity(android.content.Intent.makeRestartActivityTask(i.component)); Runtime.getRuntime().exit(0) }
                    }) { Text("Restart now") }
                    if (health.safeMode) TextButton(onClick = { vm.exitSafeMode() }) { Text("Exit safe mode") }
                    TextButton(onClick = { vm.shareDiagnostics() }) { Text("Share log") }
                }
            }
        }
    }
}
