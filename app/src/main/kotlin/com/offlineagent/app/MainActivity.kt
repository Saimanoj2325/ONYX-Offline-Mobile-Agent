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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
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
import com.offlineagent.app.ui.library.ModelsScreen
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

class MainActivity : ComponentActivity() {
    private val chatVm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OfflineAgentApp
        setContent { AppTheme { AppRoot(app, chatVm, ::launchIntent) } }
    }

    /** Only two kinds of outgoing intents exist (share sheet, calendar insert); both were approved by the user. Never ACTION_VIEW URLs. */
    private fun launchIntent(r: IntentRequest) {
        val intent = when (r) {
            is IntentRequest.Share -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.text), "Share")
            is IntentRequest.Calendar -> Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, r.title).putExtra(CalendarContract.Events.DESCRIPTION, r.description)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, r.beginMillis).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, r.endMillis)
        }
        try { startActivity(intent) } catch (e: ActivityNotFoundException) { chatVm.notice("No app on this phone can handle that.") }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun AppRoot(app: OfflineAgentApp, vm: ChatViewModel, launch: (IntentRequest) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pending by app.agents.pending.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    LaunchedEffect(ui.notice) { ui.notice?.let { snackbar.showSnackbar(it); vm.notice(null) } }
    LaunchedEffect(Unit) { app.intents.requests.collect { launch(it) } }

    val skillsHost = remember {
        object : SkillsHost {
            override val skills: Flow<List<SkillEntity>> = app.skills.skills
            override suspend fun save(markdown: String, replacingId: String?) = app.skills.save(markdown, replacingId)
            override suspend fun delete(id: String) = app.skills.delete(id)
            override suspend fun setEnabled(id: String, enabled: Boolean) = app.skills.setEnabled(id, enabled)
            override fun run(skillId: String) {
                scope.launch {
                    val s = app.skills.parsed().firstOrNull { it.id == skillId } ?: return@launch
                    tab = 0
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple("Chat", Icons.Filled.Email, 0), Triple("Docs", Icons.AutoMirrored.Filled.List, 1),
                    Triple("Skills", Icons.Filled.Build, 2), Triple("Runs", Icons.Filled.DateRange, 3), Triple("Models", Icons.Filled.Settings, 4),
                ).forEach { (label, icon, i) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            0 -> ChatScreen(vm, onShare = { text -> launch(IntentRequest.Share(text)) }, onOpenModels = { tab = 4 }, modifier = m)
            1 -> DocumentsScreen(app, notify, m)
            2 -> SkillsScreen(skillsHost, notify, m)
            3 -> RunsScreen(runsHost, notify, m)
            else -> ModelsScreen(app, notify, m)
        }
    }

    pending?.let { ApprovalDialog(it, onAnswer = { allow, always -> app.agents.answerApproval(allow, always) }) }
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
                if (p.spec.permission == Permission.WRITE_LOCAL) TextButton(onClick = { onAnswer(true, true) }) { Text("Always allow this tool") }
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

