package com.offlineagent.app.ui.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlineagent.app.ui.canvas.CanvasPanel
import com.offlineagent.app.ui.canvas.CanvasPreviewCard
import com.offlineagent.app.ui.canvas.UiActions
import com.offlineagent.app.ui.markdown.MarkdownActions
import com.offlineagent.app.ui.markdown.MarkdownView
import com.offlineagent.core.data.MessageEntity
import com.offlineagent.core.inference.EngineState
import com.offlineagent.core.runtime.skills.Skill
import com.offlineagent.core.runtime.ui.AnswerMode
import com.offlineagent.core.runtime.ui.OutputPreference
import com.offlineagent.core.runtime.ui.UiDoc
import com.offlineagent.core.runtime.ui.UiParseResult
import com.offlineagent.core.runtime.ui.UiParser
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onShare: (String) -> Unit, onOpenModels: () -> Unit, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val versions by vm.canvasVersions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val live by vm.agentLive.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showChats by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val uiActions = remember(vm) { UiActions(onPrompt = { p -> vm.send(p) }, resolveImage = { vm.resolveImage(it) }) }
    val currentDoc: UiDoc? = remember(chat?.canvasVersion, versions) {
        versions.firstOrNull { it.version == chat?.canvasVersion }?.let { (UiParser.parse(it.docJson) as? UiParseResult.Success)?.doc }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column(Modifier.clickable { showChats = true }) {
                        Text(chat?.title ?: "New chat", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(engineLabel(engine), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { showChats = true }) { Icon(Icons.Filled.Menu, "Chats") } },
                actions = {
                    if (versions.isNotEmpty()) TextButton(onClick = { vm.setCanvasOpen(true) }) { Text("Canvas") }
                    IconButton(onClick = { vm.newChat() }) { Icon(Icons.Filled.Create, "New chat") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Thinking (slower, Qwen3)") }, trailingIcon = { Switch(settings.thinking, { vm.setThinking(it) }) }, onClick = { vm.setThinking(!settings.thinking) })
                        DropdownMenuItem(text = { Text("Use my documents (RAG)") }, trailingIcon = { Switch(settings.useDocuments, { vm.setUseDocuments(it) }) }, onClick = { vm.setUseDocuments(!settings.useDocuments) })
                    }
                },
            )
            ui.unfinishedRun?.let { r ->
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Unfinished agent run: “${r.request.take(40)}” (step ${r.iteration})", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.resumeUnfinished() }) { Text("Resume") }
                        TextButton(onClick = { vm.discardUnfinished() }) { Text("Discard") }
                    }
                }
            }
            if (engine is EngineState.Unloaded && messages.isEmpty() && ui.streaming == null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable { onOpenModels() }) {
                    Text("The model loads automatically when you send a message. Tap to manage models.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            val listState = rememberLazyListState()
            LaunchedEffect(messages.size, ui.streaming?.text?.length, ui.streaming?.partialDoc) {
                val n = messages.size + (if (ui.streaming != null) 1 else 0)
                if (n > 0) listState.scrollToItem(n - 1)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (messages.isEmpty() && ui.streaming == null) item { EmptyHint() }
                items(messages, key = { it.id }) { m ->
                    MessageItem(
                        m, vm, uiActions,
                        onOpenCanvas = { v -> if (v != null) vm.selectCanvasVersion(v); vm.setCanvasOpen(true) },
                        onCopy = { clipboard.setText(AnnotatedString(it)) },
                        live = m.runId?.let { live[it] },
                    )
                }
                ui.streaming?.let { s -> item(key = "streaming") { StreamingItem(s, uiActions, onOpenCanvas = { vm.setCanvasOpen(true) }) } }
            }
            Composer(vm, ui, settings.output, busy = ui.streaming != null || live.isNotEmpty() || vm.busy)
        }

        AnimatedVisibility(ui.canvasOpen, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
            val s = ui.streaming
            val streamingCanvas = s != null && (s.mode == AnswerMode.CANVAS_NEW || s.mode == AnswerMode.CANVAS_UPDATE)
            CanvasPanel(
                doc = currentDoc,
                streamingDoc = if (streamingCanvas) s?.partialDoc else null,
                streamingLabel = if (streamingCanvas) "${s!!.phase} ${s.tokens} tok" else null,
                versions = versions, currentVersion = chat?.canvasVersion ?: 0, actions = uiActions,
                onClose = { vm.setCanvasOpen(false) }, onUndo = vm::undoCanvas, onRedo = vm::redoCanvas,
                onSelectVersion = vm::selectCanvasVersion, onShare = onShare,
            )
            BackHandler { vm.setCanvasOpen(false) }
        }
    }

    if (showChats) ChatsSheet(vm, onDismiss = { showChats = false })
    ui.citation?.let { c ->
        AlertDialog(
            onDismissRequest = { vm.showCitation(null) },
            title = { Text("[${c.n}] ${c.title}") },
            text = { SelectionContainer { Text(c.text, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { vm.showCitation(null) }) { Text("Close") } },
        )
    }
}

private fun engineLabel(e: EngineState) = when (e) {
    is EngineState.Unloaded -> "No model loaded · offline"
    is EngineState.Loading -> "Loading model…"
    is EngineState.Ready -> "Ready · ${e.model.description.take(30)}${if (e.model.supportsImages) " · vision" else ""}"
    is EngineState.Generating -> "Generating · ${e.model.description.take(30)}"
    is EngineState.Failed -> "Model error: ${e.message.take(40)}"
}

@Composable
private fun EmptyHint() {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ask anything — fully offline.", style = MaterialTheme.typography.titleMedium)
        Text("Try: “Compare Python and Java for a beginner in a table with a chart”, “Make a study plan from my PDFs” " +
            "(/study-plan), or attach a photo and ask about it. Answers that need structure open in the Canvas.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MessageItem(m: MessageEntity, vm: ChatViewModel, actions: UiActions, onOpenCanvas: (Int?) -> Unit, onCopy: (String) -> Unit, live: String?) {
    if (m.role == "user") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            val atts = remember(m.id) { vm.attachmentsOf(m) }
            atts.forEach { f -> Thumbnail(f, 160) }
            if (m.text.isNotBlank()) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 320.dp)) {
                SelectionContainer { Text(m.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (m.kind) {
            "canvas" -> {
                val doc = remember(m.id) { m.uiJson?.let { (UiParser.parse(it) as? UiParseResult.Success)?.doc } }
                if (doc != null) CanvasPreviewCard(doc, m.canvasVersion, onOpen = { onOpenCanvas(m.canvasVersion) }, actions = actions)
            }
            "agent" -> AgentRunCard(m, vm, live, onOpenCanvas)
            else -> {
                var showReasoning by rememberSaveable(m.id) { mutableStateOf(false) }
                if (m.reasoning.isNotBlank()) {
                    Text(if (showReasoning) "Hide reasoning" else "Show reasoning", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { showReasoning = !showReasoning })
                    if (showReasoning) Text(m.reasoning, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val cites = remember(m.id) { vm.citationsOf(m) }
                SelectionContainer {
                    MarkdownView(m.text, actions = MarkdownActions(
                        onCitation = { n -> cites.firstOrNull { it.n == n }?.let { vm.showCitation(it) } },
                        onLink = { url -> onCopy(url); vm.notice("Links don't open (offline app). URL copied.") },
                    ))
                }
                if (cites.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cites.forEach { c -> AssistChip(onClick = { vm.showCitation(c) }, label = { Text("[${c.n}] ${c.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                }
            }
        }
        m.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            m.statsText?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f))
            if (m.text.isNotBlank() && m.kind != "canvas") TextButton(onClick = { onCopy(m.text) }) { Text("Copy") }
        }
    }
}

@Composable
private fun AgentRunCard(m: MessageEntity, vm: ChatViewModel, live: String?, onOpenCanvas: (Int?) -> Unit) {
    var expanded by rememberSaveable(m.id) { mutableStateOf(false) }
    val steps by (m.runId?.let { vm.observeSteps(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚙ Skill: ${m.reasoning}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide steps" else "Steps (${steps.size})") }
            }
            if (live != null) Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("  $live", style = MaterialTheme.typography.bodySmall)
            }
            steps.firstOrNull { it.kind == "PLAN" }?.let { p ->
                Text("Plan", style = MaterialTheme.typography.labelMedium)
                p.output.lines().filter { it.isNotBlank() }.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
            }
            if (expanded) steps.filter { it.kind != "PLAN" }.forEach { s ->
                HorizontalDivider()
                Text("${s.kind.lowercase()} ${s.tool ?: ""} · ${s.durationMs} ms", style = MaterialTheme.typography.labelMedium)
                if (s.input.isNotBlank()) Text("in: " + s.input.take(300), style = MaterialTheme.typography.bodySmall)
                if (s.output.isNotBlank()) Text("out: " + s.output.take(400), style = MaterialTheme.typography.bodySmall)
                s.error?.let { Text("error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (m.text.isNotBlank()) MarkdownView(m.text)
            if (m.canvasVersion != null) TextButton(onClick = { onOpenCanvas(m.canvasVersion) }) { Text("Open canvas v${m.canvasVersion}") }
        }
    }
}

@Composable
private fun StreamingItem(s: Streaming, actions: UiActions, onOpenCanvas: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (s.mode) {
            AnswerMode.MARKDOWN -> {
                if (s.reasoning.isNotBlank() && s.text.isBlank()) Text("Thinking… (${s.reasoning.length} chars)", fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                if (s.text.isBlank()) Text(s.phase, color = MaterialTheme.colorScheme.onSurfaceVariant) else MarkdownView(s.text)
            }
            else -> Card(onClick = onOpenCanvas, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("▦ ${s.phase}", style = MaterialTheme.typography.titleSmall)
                    Text("${s.partialDoc?.blocks?.size ?: 0} components ready · tap to watch", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (s.tokens > 0) Text("%d tok · %.1f tok/s".format(s.tokens, s.tokensPerSecond), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Thumbnail(f: File, sizeDp: Int, onRemove: (() -> Unit)? = null) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, f) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
        }
    }
    Box(Modifier.padding(2.dp)) {
        bmp?.let { Image(it, "attached photo", contentScale = ContentScale.Crop, modifier = Modifier.size(sizeDp.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))) }
            ?: Box(Modifier.size(sizeDp.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
        if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(24.dp).align(Alignment.TopEnd)) { Icon(Icons.Filled.Close, "Remove") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(vm: ChatViewModel, ui: ChatUi, output: OutputPreference, busy: Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    var plusMenu by remember { mutableStateOf(false) }
    var skillPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.attachImage(uri) }
    var camFile by remember { mutableStateOf<File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> camFile?.let { if (ok) vm.attachCameraFile(it) else it.delete() } }

    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().imePadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                OutputPreference.entries.forEachIndexed { i, p ->
                    SegmentedButton(selected = output == p, onClick = { vm.setOutput(p) }, shape = SegmentedButtonDefaults.itemShape(i, OutputPreference.entries.size)) {
                        Text(when (p) { OutputPreference.AUTO -> "Auto"; OutputPreference.CHAT -> "Chat"; OutputPreference.CANVAS -> "Canvas" })
                    }
                }
            }
            if (ui.pendingImages.isNotEmpty()) Row { ui.pendingImages.forEach { f -> Thumbnail(f, 64) { vm.removePendingImage(f) } } }
            ui.skillSuggestion?.let { s ->
                AssistChip(onClick = { vm.runSkill(s, text); text = "" }, label = { Text("Run skill: ${s.name}") })
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { plusMenu = true }, enabled = !busy) { Icon(Icons.Filled.Add, "Attach or run a skill") }
                    DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                        DropdownMenuItem(text = { Text("Photo from gallery") }, onClick = {
                            plusMenu = false
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        DropdownMenuItem(text = { Text("Take a photo") }, onClick = {
                            plusMenu = false
                            val f = vm.cameraTarget(); camFile = f
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                            runCatching { takePhoto.launch(uri) }.onFailure { vm.notice("No camera app available") }
                        })
                        DropdownMenuItem(text = { Text("Run a skill…") }, onClick = { plusMenu = false; skillPicker = true })
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it; vm.onTextChanged(it) },
                    modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                    placeholder = { Text(if (ui.pendingImages.isNotEmpty()) "Ask about the photo…" else "Message (offline)") },
                    maxLines = 6,
                )
                if (busy) {
                    FilledIconButton(onClick = { vm.stop() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.onError))
                    }
                } else {
                    FilledIconButton(onClick = { vm.send(text); text = "" }, enabled = text.isNotBlank() || ui.pendingImages.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
    if (skillPicker) SkillPickerSheet(vm, onPick = { s -> skillPicker = false; vm.runSkill(s, text); text = "" }, onDismiss = { skillPicker = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillPickerSheet(vm: ChatViewModel, onPick: (Skill) -> Unit, onDismiss: () -> Unit) {
    val skills by produceState(emptyList<Skill>()) { value = vm.getApplication<com.offlineagent.app.OfflineAgentApp>().skills.parsed() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Run a skill", style = MaterialTheme.typography.titleMedium)
            Text("The text you typed (if any) becomes the request.", style = MaterialTheme.typography.bodySmall)
            skills.forEach { s ->
                Card(onClick = { onPick(s) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall)
                        Text(s.description, style = MaterialTheme.typography.bodySmall)
                        Text("/${s.id} · tools: ${s.tools.joinToString()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val chats by vm.chatList.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chats", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.newChat(); onDismiss() }) { Text("New chat") }
            }
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(chats, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { vm.openChat(c.id); onDismiss() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(c.updatedAt)) +
                                if (c.canvasVersion > 0) " · canvas" else "", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { vm.deleteChat(c.id) }) { Icon(Icons.Filled.Delete, "Delete chat") }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
}
