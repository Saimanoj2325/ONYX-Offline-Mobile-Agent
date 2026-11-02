package com.offlineagent.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlineagent.app.data.PendingApproval
import com.offlineagent.app.data.IntentRequest
import com.offlineagent.app.ui.chat.ChatScreen
import com.offlineagent.app.ui.chat.ChatViewModel
import com.offlineagent.app.ui.library.DocumentsScreen
import com.offlineagent.core.data.AgentRunEntity
import com.offlineagent.core.data.AgentStepEntity
import com.offlineagent.core.data.SkillEntity
import com.offlineagent.core.runtime.skills.SkillParser
import com.offlineagent.core.runtime.tools.Permission
import com.offlineagent.feature.workflow.RunsHost
import com.offlineagent.feature.workflow.RunsScreen
import com.offlineagent.feature.workflow.SkillsHost
import com.offlineagent.feature.workflow.SkillsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private val chatVm: ChatViewModel by viewModels()
    /** Notification taps ("open" extra): onyx → Home, onyx-tasks → Tasks. */
    private val openRequests = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private var speaker: com.offlineagent.app.onyx.Speaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OfflineAgentApp
        openRequests.value = intent?.getStringExtra("open")
        val voice = com.offlineagent.app.onyx.VoiceInput(this)
        val sp = com.offlineagent.app.onyx.Speaker(this).also { speaker = it }
        setContent {
            val prefs by app.onyx.prefs.values.collectAsStateWithLifecycle()
            com.offlineagent.app.ui.theme.OnyxTheme(prefs.theme, prefs.dynamicColor) { AppRoot(app, chatVm, ::launchIntent, voice, sp, openRequests) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequests.value = intent.getStringExtra("open")
    }

    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }

    /**
     * Outgoing intents. Every one was approved by the user in the tool-approval dialog (share, calendar, phone actions:
     * alarms, app launches, message drafts, settings panels, links) or is a file the user exported.
     */
    private fun launchIntent(r: IntentRequest) {
        val intent = when (r) {
            is IntentRequest.Raw -> r.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            is IntentRequest.ShareFile -> if (r.view) Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, r.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                else Intent.createChooser(Intent(Intent.ACTION_SEND).setType(r.mime).putExtra(Intent.EXTRA_STREAM, r.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")
            is IntentRequest.Share -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.text), "Share")
            is IntentRequest.Calendar -> Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, r.title).putExtra(CalendarContract.Events.DESCRIPTION, r.description)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, r.beginMillis).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, r.endMillis)
        }
        try { startActivity(intent) } catch (e: ActivityNotFoundException) { chatVm.notice("No app on this phone can handle that.") }
        catch (e: SecurityException) { chatVm.notice("Android blocked that action: ${e.message}") }
    }
}

private enum class Tab(val label: String) { HOME("Home"), CHAT("Chat"), TASKS("Tasks"), MEMORY("Memory"), AGENTS("Agents") }

@Composable
private fun TabIcon(t: Tab) = when (t) {
    Tab.HOME -> Icon(androidx.compose.material.icons.Icons.Default.Home, null)
    Tab.CHAT -> Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.Send, null)
    Tab.TASKS -> Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.List, null)
    Tab.MEMORY -> Icon(androidx.compose.material.icons.Icons.Default.Star, null)
    Tab.AGENTS -> Icon(androidx.compose.material.icons.Icons.Default.Settings, null)
}

@Composable
private fun AppRoot(
    app: OfflineAgentApp, vm: ChatViewModel, launch: (IntentRequest) -> Unit,
    voice: com.offlineagent.app.onyx.VoiceInput, speaker: com.offlineagent.app.onyx.Speaker,
    openRequests: kotlinx.coroutines.flow.MutableStateFlow<String?>,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pending by app.agents.pending.collectAsStateWithLifecycle()
    val onyxPending by app.onyx.pending.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    LaunchedEffect(ui.notice) { ui.notice?.let { snackbar.showSnackbar(it); vm.notice(null) } }
    LaunchedEffect(Unit) { app.intents.requests.collect { launch(it) } }
    LaunchedEffect(Unit) { app.launcher.requests.collect { launch(it) } }
    LaunchedEffect(Unit) { app.onyx.speakRequests.collect { speaker.speak(it) } }
    // take_photo tool: open the camera, attach the photo to the current chat.
    var camFile by remember { mutableStateOf<java.io.File?>(null) }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        camFile?.let { f -> if (ok) vm.attachCameraFile(f) }
        camFile = null
    }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        app.cameraRequests.collect {
            val f = vm.cameraTarget()
            camFile = f
            runCatching { camera.launch(androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)) }
        }
    }

    val skillsHost = remember {
        object : SkillsHost {
            override val skills: Flow<List<SkillEntity>> = app.skills.skills
            override suspend fun save(markdown: String, replacingId: String?) = app.skills.save(markdown, replacingId)
            override suspend fun delete(id: String) = app.skills.delete(id)
            override suspend fun setEnabled(id: String, enabled: Boolean) = app.skills.setEnabled(id, enabled)
            override fun run(skillId: String) {
                scope.launch {
                    val s = app.skills.parsed().firstOrNull { it.id == skillId } ?: return@launch
                    vm.runSkill(s, "")
                }
            }
            override val template = SKILL_TEMPLATE
        }
    }
    val runsHost = remember {
        object : RunsHost {
            override val runs: Flow<List<AgentRunEntity>> = app.agents.observeRuns()
            override fun steps(runId: String): Flow<List<AgentStepEntity>> = app.agents.observeSteps(runId)
            override suspend fun delete(runId: String) = app.agents.delete(runId)
            override fun resume(runId: String): Boolean = app.agents.resume(runId) { }
        }
    }

    // Phase 5: bottom navigation (Home · Chat · Tasks · Memory · Agents); the Phase 4 Settings screen opens on top.
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val open by openRequests.collectAsStateWithLifecycle()
    LaunchedEffect(open) {
        when (open) { "onyx" -> tab = Tab.HOME; "onyx-tasks" -> tab = Tab.TASKS }
        openRequests.value = null
    }
    androidx.activity.compose.BackHandler(enabled = !showSettings && tab != Tab.HOME) { tab = Tab.HOME }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!showSettings) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t -> NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { TabIcon(t) }, label = { Text(t.label) }) }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding).consumeWindowInsets(padding)
        if (showSettings) {
            androidx.activity.compose.BackHandler { showSettings = false }
            Column(m.fillMaxSize()) {
                TextButton(onClick = { showSettings = false }) { Text("‹ Back") }
                com.offlineagent.app.settings.SettingsScreen(app, notify, Modifier.weight(1f))
            }
        } else androidx.compose.animation.Crossfade(tab, label = "tabs") { t ->
            when (t) {
                Tab.HOME -> com.offlineagent.app.ui.onyx.HomeScreen(app, voice, speaker,
                    onContinueInChat = { q -> vm.newChat(); vm.send(q); tab = Tab.CHAT }, onOpenAgents = { showSettings = true }, notify = notify, modifier = m)
                Tab.CHAT -> ChatScreen(vm, onShare = { text -> launch(IntentRequest.Share(text)) }, onOpenSettings = { showSettings = true },
                    skillsHost = skillsHost, runsHost = runsHost, notify = notify, modifier = m)
                Tab.TASKS -> com.offlineagent.app.ui.onyx.TasksScreen(app, notify, m)
                Tab.MEMORY -> com.offlineagent.app.ui.onyx.MemoryScreen(app, launch, m)
                Tab.AGENTS -> com.offlineagent.app.ui.onyx.AgentsScreen(app, onOpenSettings = { showSettings = true }, notify = notify, modifier = m)
            }
        }
    }

    // Chat-agent approvals are cards in the chat thread; anywhere else they still need an answer.
    if (showSettings || tab != Tab.CHAT) pending?.let { ApprovalDialog(it, onAnswer = { allow, always -> app.agents.answerApproval(allow, always) }) }
    // Multi-agent (ONYX) confirmations: a bottom sheet over every screen.
    onyxPending?.let { p -> com.offlineagent.app.ui.onyx.ApprovalSheet(p) { allow, always -> app.onyx.answer(allow, always) } }
}

@Composable
private fun ApprovalDialog(p: PendingApproval, onAnswer: (Boolean, Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Allow “${p.spec.name}”?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(p.spec.description)
                Text("Effect: ${p.reason}", modifier = Modifier.padding(top = 8.dp))
                Text("Details:", modifier = Modifier.padding(top = 8.dp))
                Text(p.call.args.entries.joinToString("\n") { (k, v) -> "$k: ${v.toString().trim('"').take(400)}" }, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = { onAnswer(true, false) }) { Text("Allow once") }
                if (p.spec.permission.canAlwaysAllow && p.spec.permission != Permission.READ_ONLY) TextButton(onClick = { onAnswer(true, true) }) { Text("Always allow this tool") }
            }
        },
        dismissButton = { TextButton(onClick = { onAnswer(false, false) }) { Text("Deny") } },
    )
}

private val SKILL_TEMPLATE = """
---
id: my-skill
name: My skill
description: What this skill does in one sentence.
triggers: [example phrase, another phrase]
tools: [search_docs, calculator, render_ui]
output: [heading, table, callout]
max_steps: 4
---
1. Describe step one for the model (which tool to call and why).
2. Describe step two.
3. Finish with render_ui: a heading, a table of ..., and a callout with the key takeaway.
""".trimIndent() + "\n" // keeps SkillParser happy

