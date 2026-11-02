package com.offlineagent.app.ui.onyx

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.onyx.Speaker
import com.offlineagent.app.onyx.VoiceInput
import com.offlineagent.core.agents.Thermal
import com.offlineagent.core.inference.EngineState
import java.time.LocalTime

private val SUGGESTIONS = listOf(
    "How's my battery and storage?",
    "Summarise my notifications",
    "What's on my calendar today?",
    "Turn on the flashlight",
    "Remind me in 30 minutes to stretch",
    "Remember that I prefer short answers",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(app: OfflineAgentApp, voice: VoiceInput, speaker: Speaker, onContinueInChat: (String) -> Unit, onOpenAgents: () -> Unit, notify: (String) -> Unit, modifier: Modifier = Modifier) {
    val onyx = app.onyx
    val board by onyx.current.collectAsState()
    val online by onyx.monitor.online.collectAsState()
    val network by onyx.monitor.network.collectAsState()
    val thermal by onyx.monitor.thermal.collectAsState()
    val engine by app.inference.state.collectAsState()
    val hasModel by onyx.hasChatModel.collectAsState()
    val plan by onyx.runtimePlan.collectAsState()
    val qstats by onyx.queue.stats.collectAsState()
    val vstate by voice.state.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme

    fun submit(q: String) {
        val t = q.trim(); if (t.isEmpty()) return
        onyx.run(t)?.let(notify) ?: run { text = "" }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) voice.start { submit(it) } else notify("Microphone permission is needed for voice input.")
    }
    fun onOrb() {
        when {
            vstate is VoiceInput.State.Listening -> voice.stop()
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> voice.start { submit(it) }
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(vstate) { (vstate as? VoiceInput.State.Error)?.let { notify(it.message); voice.clearError() } }
    LaunchedEffect(Unit) { onyx.refreshModelState(); onyx.refreshDevice() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ONYX", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = cs.primary)
            Spacer(Modifier.weight(1f))
            Pill(if (online) "Online · $network" else "Offline", if (online) NetworkAmber else OfflineGreen)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val modelLabel = when (val e = engine) {
                is EngineState.Ready -> e.model.path.substringAfterLast('/').removeSuffix(".gguf").take(22)
                is EngineState.Generating -> "Thinking · " + e.model.path.substringAfterLast('/').removeSuffix(".gguf").take(16)
                is EngineState.Loading -> "Loading model…"
                else -> if (hasModel) "Model sleeps until needed" else "No chat model"
            }
            Pill(modelLabel, if (hasModel) cs.primary else cs.error)
            plan?.let { Pill(it.tier.label, cs.secondary) }
            if (thermal >= Thermal.MODERATE) Pill("Phone ${thermal.name.lowercase()}", NetworkAmber)
            if (qstats.paused) Pill("Model paused: cooling", cs.error)
            Pill(if (voice.offlineGuaranteed) "On-device speech" else "Speech: offline pack not guaranteed", if (voice.offlineGuaranteed) OfflineGreen else NetworkAmber)
        }
        if (!hasModel) Surface(shape = MaterialTheme.shapes.medium, color = cs.errorContainer.copy(alpha = 0.4f)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("No chat model yet: ONYX uses simple rules only. Download one for planning and answers.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenAgents) { Text("Models") }
            }
        }

        val b = board
        AnimatedContent(b == null, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "home") { idle ->
            if (idle) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Text(greeting(), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                    Text("Private by design — everything runs on this phone.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                    val listening = vstate as? VoiceInput.State.Listening
                    VoiceOrb(listening != null, false, listening?.level ?: 0f, onClick = ::onOrb)
                    Text(listening?.partial?.ifBlank { "Listening…" } ?: "Tap the orb and speak", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            } else if (b != null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActivityTimeline(b, onContinueInChat = onContinueInChat, onSpeak = { speaker.speak(it) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onyx.isRunning) OutlinedButton(onClick = { onyx.cancel() }) { Text("Stop") }
                        else OutlinedButton(onClick = { onyx.dismissCurrent() }) { Text("New task") }
                    }
                }
            }
        }

        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            placeholder = { Text("Ask ONYX to do something…") }, maxLines = 4,
            trailingIcon = { FilledIconButton(onClick = { submit(text) }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Run") } })
        AnimatedVisibility(board == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SUGGESTIONS.forEach { s -> AssistChip(onClick = { submit(s) }, label = { Text(s) }) }
            }
        }
        Box(Modifier.height(24.dp))
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Hello, night owl"
}
