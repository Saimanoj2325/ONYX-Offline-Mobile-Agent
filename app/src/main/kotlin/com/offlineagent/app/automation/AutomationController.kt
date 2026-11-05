package com.offlineagent.app.automation

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.data.ModelRole
import com.offlineagent.app.phone.Notifications
import com.offlineagent.app.work.LongTask
import com.offlineagent.app.work.LongTasks
import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationRequest
import com.offlineagent.core.inference.ImageData
import com.offlineagent.core.inference.Role
import com.offlineagent.core.runtime.agent.InferenceAgentLlm
import com.offlineagent.core.runtime.phone.AutofillMapper
import com.offlineagent.core.runtime.phone.AutomationHost
import com.offlineagent.core.runtime.phone.AutomationLog
import com.offlineagent.core.runtime.phone.AutomationLogEntry
import com.offlineagent.core.runtime.phone.AutomationPolicy
import com.offlineagent.core.runtime.phone.FormField
import com.offlineagent.core.runtime.phone.Macro
import com.offlineagent.core.runtime.phone.MacroCodec
import com.offlineagent.core.runtime.phone.MacroPlayer
import com.offlineagent.core.runtime.phone.MacroRecorder
import com.offlineagent.core.runtime.phone.MacroSchedule
import com.offlineagent.core.runtime.phone.PhoneGroup
import com.offlineagent.core.runtime.phone.ScreenAction
import com.offlineagent.core.runtime.phone.ScreenAgent
import com.offlineagent.core.runtime.phone.ScreenAgentLimits
import com.offlineagent.core.runtime.phone.ScreenPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Append-only action log (JSON lines in app storage, last 2000 entries kept). Shown in Settings → Automation log. */
class FileAutomationLog(ctx: Context) : AutomationLog {
    private val file = File(ctx.filesDir, "automation-log.jsonl")
    private val _recent = MutableStateFlow(load())
    val recent: StateFlow<List<AutomationLogEntry>> = _recent.asStateFlow()

    private fun load(): List<AutomationLogEntry> = runCatching {
        file.readLines().takeLast(300).mapNotNull { l ->
            runCatching {
                val o = Json.parseToJsonElement(l) as kotlinx.serialization.json.JsonObject
                fun s(k: String) = o[k]?.toString()?.trim('"') ?: ""
                AutomationLogEntry(s("t").toLong(), s("step").toInt(), s("app"), s("action"), s("outcome"))
            }.getOrNull()
        }.reversed()
    }.getOrDefault(emptyList())

    @Synchronized override fun add(e: AutomationLogEntry) {
        val line = buildJsonObject { put("t", e.time); put("step", e.step); put("app", e.app); put("action", e.action); put("outcome", e.outcome) }.toString()
        file.appendText(line + "\n")
        if (file.length() > 600_000) file.writeText(file.readLines().takeLast(2000).joinToString("\n", postfix = "\n"))
        _recent.value = (listOf(e) + _recent.value).take(300)
    }

    @Synchronized fun clear() { file.delete(); _recent.value = emptyList() }
}

class MacroStore(ctx: Context, private val migrate: (Macro) -> Macro = { it }) {
    private val dir = File(ctx.filesDir, "macros").apply { mkdirs() }
    private val _macros = MutableStateFlow(load())
    val macros: StateFlow<List<Macro>> = _macros.asStateFlow()
    /** Phase 6.1: macros recorded on the home screen (first step "open <launcher>") are fixed on load and saved back. */
    private fun load() = dir.listFiles { f -> f.extension == "json" }?.mapNotNull { f ->
        runCatching {
            val m = MacroCodec.decode(f.readText())
            val fixed = runCatching { migrate(m) }.getOrDefault(m)
            if (fixed !== m) runCatching { f.writeText(fixed.toJson()) }
            fixed
        }.getOrNull()
    }?.sortedBy { it.name } ?: emptyList()
    fun save(m: Macro) { File(dir, "${m.id}.json").writeText(m.toJson()); _macros.value = load() }
    fun delete(id: String) { File(dir, "$id.json").delete(); _macros.value = load() }
    fun find(nameOrId: String): Macro? = _macros.value.firstOrNull { it.id == nameOrId } ?: _macros.value.firstOrNull { it.name.equals(nameOrId, true) }
        ?: _macros.value.firstOrNull { it.name.contains(nameOrId, true) }
}

/**
 * Runs screen automation: the LLM screen agent, form filling from the profile vault, and macro record/replay. Every
 * run goes through [AutomationPolicy] (blocked banking/payment apps, CAPTCHA hand-off, OTP rule, approval before
 * irreversible steps), is visible in the overlay with Pause/Stop, is logged, and is kept alive by the foreground service.
 */
class AutomationController(private val app: OfflineAgentApp) : ScreenPort {
    val launcher = LauncherInfo(app)
    val macros = MacroStore(app) { m -> com.offlineagent.core.runtime.phone.MacroApps.migrate(m, app.packageName, launcher::isHome, launcher::appForLabel) }
    private var running: ScreenAgent? = null
    private var job: Job? = null
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private fun service(): OfflineAccessibilityService {
        val s = OfflineAccessibilityService.instance
        if (s == null) throw com.offlineagent.core.runtime.phone.PhoneActionException(
            "Screen automation is off. Turn it on: Settings → Phone actions → Screen automation (opens Android accessibility settings).", PhoneGroup.SCREEN_AUTOMATION)
        return s
    }

    private suspend fun checkEnabled() {
        val st = app.settings.current()
        if (!st.automationEnabled) throw com.offlineagent.core.runtime.phone.PhoneActionException("Automation is switched off (kill switch in Settings → Phone actions).", PhoneGroup.SCREEN_AUTOMATION)
        if (PhoneGroup.SCREEN_AUTOMATION.name !in st.phoneGroups) throw com.offlineagent.core.runtime.phone.PhoneActionException("Screen automation is disabled in Settings → Phone actions.", PhoneGroup.SCREEN_AUTOMATION)
    }

    fun onServiceGone() { running?.stop(); job?.cancel() }

    /** Kill switch: stops whatever is running right now. */
    fun stopAll() { running?.stop(); job?.cancel(); OfflineAccessibilityService.instance?.overlay()?.hide() }

    private fun host(s: OfflineAccessibilityService, header: String, agent: () -> ScreenAgent?, report: () -> String? = { null }): AutomationHost {
        val ov = s.overlay()
        ov.onPauseToggle = {
            agent()?.let { a -> if (a.state.value == com.offlineagent.core.runtime.phone.AutomationState.PAUSED) a.resume() else a.pause() }
        }
        ov.onStop = { agent()?.stop(); job?.cancel() }
        val source = if (header.startsWith("Macro")) "Macro" else "Screen agent"
        fun appLabel(): String = OfflineAccessibilityService.lastForegroundPackage?.let { launcher.label(it) }.orEmpty()
        return object : AutomationHost {
            override fun preview(step: Int, text: String) = ov.showStep("$header · step $step", text, agent()?.state?.value == com.offlineagent.core.runtime.phone.AutomationState.PAUSED)
            // Phase 6.2: approvals and questions appear on top of the current app (not by bringing ONYX to the front).
            override suspend fun approve(step: Int, action: String, reason: String): Boolean {
                ov.hide()
                val irreversible = Regex("(?i)send|submit|pay|delete|enter").containsMatchIn(reason)
                val r = PromptCenter.ask(app, com.offlineagent.core.agents.UserPrompt(PromptCenter.newId("macro"), com.offlineagent.core.agents.PromptKind.APPROVE, source,
                    "Step $step: $action", appLabel(), if (irreversible) com.offlineagent.core.agents.Risk.CRITICAL else com.offlineagent.core.agents.Risk.HIGH, reason,
                    allowTaskScope = false, irreversible = irreversible))
                if (r.stop) { agent()?.stop(); job?.cancel() }
                return r.allowed
            }
            override suspend fun ask(question: String): String? {
                ov.hide()
                val r = PromptCenter.ask(app, com.offlineagent.core.agents.UserPrompt(PromptCenter.newId("ask"), com.offlineagent.core.agents.PromptKind.ASK, source, question, appLabel()))
                if (r.stop) { agent()?.stop(); job?.cancel() }
                return if (r.allowed) r.text else null
            }
            override fun finished(summary: String) = ov.showDone(summary, report())
            override suspend fun highlight(step: Int, bounds: com.offlineagent.core.runtime.phone.Bounds, text: String) {
                ov.showStep("$header · test run · step $step", text, false)
                HighlightOverlay.show(s, bounds, 900)
            }
        }
    }

    /** Phase 6.2 "Copy debug report": the last macro run (null until one ran). */
    val lastMacroReport = MutableStateFlow<String?>(null)
    val lastMacroFailed = MutableStateFlow(false)

    /** Opens a deep link in [pkg] (e.g. https://wa.me/<number> in WhatsApp). */
    private fun openLink(uri: String, pkg: String): Boolean = runCatching {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg.isNotBlank()) i.setPackage(pkg)
        app.startActivity(i); true
    }.getOrDefault(false)

    override suspend fun describeScreen(): String {
        checkEnabled()
        val snap = service().snapshot() ?: return "Cannot read the screen right now."
        if (app.automationPolicy().isBlockedApp(snap.packageName)) return "${snap.appLabel} is a banking/payment app; I don't read it."
        return snap.describe(4000)
    }

    override suspend fun runTask(goal: String, app: String): String {
        checkEnabled()
        val s = service()
        val taskId = "screen-" + System.currentTimeMillis()
        LongTasks.start(this.app, LongTask(taskId, "Doing a task on your screen", goal.take(80)))
        job = currentCoroutineContext().job
        try {
            if (app.isNotBlank()) { s.perform(ScreenAction.OpenApp(app), com.offlineagent.core.runtime.phone.ScreenSnapshot("", com.offlineagent.core.runtime.phone.ScreenNode())); kotlinx.coroutines.delay(1500) }
            else { goHomeFromOurApp(s) }
            val st = this.app.settings.current()
            val role = if (st.screenVision && this.app.models.active(ModelRole.VISION) != null) ModelRole.VISION else ModelRole.CHAT
            this.app.engineController.ensure(role)
            val profileNotes = this.app.vault.current()?.let { p -> com.offlineagent.core.runtime.phone.ProfileKey.entries.mapNotNull { k -> p[k]?.let { "${k.label}: $it" } }.joinToString("; ") } ?: ""
            var agentRef: ScreenAgent? = null
            var lastPartial = 0L
            val llm = InferenceAgentLlm(this.app.inference,
                onPartial = { t -> val now = System.currentTimeMillis(); if (now - lastPartial > 150) { lastPartial = now; this.app.automationPartial.value = t } },
                onPrompt = { d, n -> this.app.automationPartial.value = com.offlineagent.core.runtime.chat.StreamingTurn.readingStatus(d, n) })
            val agent = ScreenAgent(llm, s, host(s, "Offline Agent", { agentRef }), this.app.automationPolicy(), this.app.automationLog,
                ScreenAgentLimits(maxSteps = 25))
            agentRef = agent; running = agent
            return agent.run(goal, if (profileNotes.isNotBlank()) "Your saved profile (use only if the task needs it): $profileNotes" else "")
        } finally {
            running = null
            this.app.automationPartial.value = ""
            LongTasks.finish(this.app, taskId)
        }
    }

    /** If the user asked from our chat screen, the task is about the previous app: go back to it. */
    private suspend fun goHomeFromOurApp(s: OfflineAccessibilityService) {
        val snap = s.snapshot() ?: return
        if (snap.packageName == this.app.packageName) { s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS); kotlinx.coroutines.delay(600)
            s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS); kotlinx.coroutines.delay(900) }
    }

    override suspend fun fillForm(): String {
        checkEnabled()
        val s = service()
        val profile = app.vault.current() ?: return "Your profile is locked. Unlock it in Settings → Profile & autofill (fingerprint/screen lock), then ask again."
        goHomeFromOurApp(s)
        val snap = s.snapshot() ?: return "Cannot read the screen."
        val policy = app.automationPolicy()
        if (policy.isBlockedApp(snap.packageName)) return "${snap.appLabel} is a banking/payment app; autofill is blocked there."
        if (policy.hasCaptcha(snap)) return "This screen has a CAPTCHA — please solve it yourself first."
        val fields = snap.elements.filter { it.node.editable && it.node.enabled }
        if (fields.isEmpty()) return "I don't see any text fields on this screen."
        val ff = fields.mapIndexed { i, e -> FormField(i, e.node.autofillHints, e.node.hint, e.nearbyLabel.ifBlank { e.node.contentDescription }, e.node.viewId, e.node.inputType, currentValue = e.node.text, isPassword = e.node.password) }
        val llm = app.readyLlmOrNull()
        val matches = AutofillMapper(llm).map(ff, profile, "${snap.appLabel}: " + snap.elements.take(8).joinToString(" ") { it.node.label }.take(200))
        var filled = 0
        var agentRef: ScreenAgent? = null
        val h = host(s, "Filling form", { agentRef })
        for ((i, m) in matches.withIndex()) {
            val v = m.value(profile) ?: continue
            if (ff[i].currentValue.isNotBlank()) continue // never overwrite what's there
            val action = ScreenAction.Type(fields[i].id, v)
            val now = s.snapshot() ?: break
            if (policy.check(action, now, emptySet()) !is AutomationPolicy.Verdict.Allow) continue
            h.preview(filled + 1, action.describe(now))
            if (s.perform(action, now)) { filled++; app.automationLog.add(AutomationLogEntry(System.currentTimeMillis(), filled, snap.packageName, "filled ${m.key?.label ?: m.custom?.label}", "ok")) }
            kotlinx.coroutines.delay(250)
        }
        val msg = if (filled == 0) "Nothing to fill: no fields matched your profile." else "Filled $filled field(s) from your profile. Review them and press Submit yourself."
        h.finished(msg)
        return msg
    }

    override suspend fun listMacros(): List<Pair<String, String>> = macros.macros.value.map { it.id to it.name }

    override suspend fun runMacro(name: String, params: Map<String, String>): String {
        checkEnabled()
        val m = macros.find(name) ?: return "No macro named \"$name\". Saved macros: " + macros.macros.value.joinToString { it.name }.ifBlank { "none" }
        return play(m, params.filterKeys { !it.startsWith("_") }.values.toList(), unattended = false)
    }

    suspend fun play(m: Macro, positional: List<String>, unattended: Boolean, testRun: Boolean = false, useDeepLink: Boolean = true): String {
        val s = service()
        val values = m.params.mapIndexed { i, p -> p to (positional.getOrNull(i) ?: "") }.toMap()
        val taskId = "macro-" + m.id
        LongTasks.start(app, LongTask(taskId, if (testRun) "Test-running macro" else "Running macro", m.name))
        job = currentCoroutineContext().job
        try {
            val policy = app.automationPolicy()
            var player: MacroPlayer? = null
            val failedReport: () -> String? = { player?.lastReport?.takeIf { !it.ok }?.render(com.offlineagent.app.BuildConfig.VERSION_NAME) }
            player = MacroPlayer(s, host(s, "Macro: ${m.name}", { null }, failedReport), policy, app.automationLog,
                isHome = launcher::isHome, openProblem = { pkg -> launcher.openProblem(pkg, policy::isBlockedApp) }, appLabel = launcher::label,
                openLink = { uri, pkg -> openLink(uri, pkg) })
            val out = player.play(m, values, unattended, testRun = testRun, useDeepLink = useDeepLink)
            player.lastReport?.let { r -> lastMacroReport.value = r.render(com.offlineagent.app.BuildConfig.VERSION_NAME); lastMacroFailed.value = !r.ok }
            return out
        } finally { LongTasks.finish(app, taskId) }
    }

    /** Phase 6.2: the last screen_task run's debug report (screen agent). */
    fun screenTaskReport(): String? = runCatching { app.onyx.screen.toolkit.session.lastTaskReport?.render(com.offlineagent.app.BuildConfig.VERSION_NAME) }.getOrNull()

    // ------------------------------------------------------------ teach by demonstration

    fun startRecording(onSaved: (Macro?) -> Unit) {
        val s = service()
        val rec = MacroRecorder(app.packageName, app.automationPolicy(), isHome = launcher::isHome, appForLabel = launcher::appForLabel)
        s.recorder = rec
        s.setKeyFiltering(true) // Back / Enter keys become steps (only while recording)
        _recording.value = true
        s.overlay().showRecording(onSave = {
            s.recorder = null; s.setKeyFiltering(false); _recording.value = false; s.overlay().hide()
            onSaved(rec.build("m" + System.currentTimeMillis().toString(36), "New macro", "Recorded on " + java.time.LocalDate.now()))
        }, onCancel = { s.recorder = null; s.setKeyFiltering(false); _recording.value = false; s.overlay().hide(); onSaved(null) })
        // Leave our app so the user can demonstrate in the target app.
        s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
    }

    suspend fun saveMacro(m: Macro) {
        macros.save(m)
        app.skills.save(m.toSkillMarkdown(), null)
        schedule(m)
    }

    suspend fun deleteMacro(m: Macro) {
        macros.delete(m.id)
        runCatching { app.skills.delete("macro-${m.id.take(40)}") }
        WorkManager.getInstance(app).cancelUniqueWork("macro-${m.id}")
    }

    fun schedule(m: Macro) {
        val wm = WorkManager.getInstance(app)
        val sch = m.schedule
        if (sch.kind == "none") { wm.cancelUniqueWork("macro-${m.id}"); return }
        val now = java.time.LocalDateTime.now()
        var first = now.withHour(sch.hour).withMinute(sch.minute).withSecond(0)
        if (sch.kind == "weekly") while (first.dayOfWeek.value != sch.dayOfWeek || !first.isAfter(now)) first = first.plusDays(1)
        else if (!first.isAfter(now)) first = first.plusDays(1)
        val delay = java.time.Duration.between(now, first).toMillis()
        val req = when (sch.kind) {
            "interval" -> PeriodicWorkRequestBuilder<MacroWorker>(sch.intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            "weekly" -> PeriodicWorkRequestBuilder<MacroWorker>(7, TimeUnit.DAYS).setInitialDelay(delay, TimeUnit.MILLISECONDS)
            else -> PeriodicWorkRequestBuilder<MacroWorker>(1, TimeUnit.DAYS).setInitialDelay(delay, TimeUnit.MILLISECONDS)
        }.setInputData(workDataOf("id" to m.id)).setConstraints(Constraints.Builder().setRequiresCharging(sch.whenCharging).build()).build()
        wm.enqueueUniquePeriodicWork("macro-${m.id}", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    // ------------------------------------------------------------ optional vision

    suspend fun describeScreenshot(s: OfflineAccessibilityService, goal: String): String? {
        val st = app.settings.current()
        if (!st.screenVision || Build.VERSION.SDK_INT < 30) return null
        val loaded = app.engineController.loaded.value ?: return null
        if (loaded.role != ModelRole.VISION.name) return null
        val bmp: Bitmap = suspendCancellableCoroutine { cont ->
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    r.hardwareBuffer.close()
                    if (cont.isActive) cont.resume(hw?.copy(Bitmap.Config.ARGB_8888, false))
                }
                override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
            })
        } ?: return null
        val scaled = Bitmap.createScaledBitmap(bmp, 448, (448f * bmp.height / bmp.width).toInt(), true)
        val px = IntArray(scaled.width * scaled.height).also { scaled.getPixels(it, 0, scaled.width, 0, 0, scaled.width, scaled.height) }
        val rgb = ByteArray(px.size * 3)
        for (i in px.indices) { val p = px[i]; rgb[i * 3] = (p shr 16 and 0xFF).toByte(); rgb[i * 3 + 1] = (p shr 8 and 0xFF).toByte(); rgb[i * 3 + 2] = (p and 0xFF).toByte() }
        val sb = StringBuilder()
        app.inference.generate(GenerationRequest(listOf(ChatMessage(Role.USER, "Describe the important controls and text on this phone screen for the task: $goal. Be brief.",
            images = listOf(ImageData(rgb, scaled.width, scaled.height)))), maxNewTokens = 120, thinking = false)).collect { if (it is GenerationEvent.Token) sb.append(it.text) }
        return sb.toString().trim().take(800)
    }

    companion object {
        fun openAccessibilitySettings(ctx: Context) = ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Scheduled macro runs. Unattended runs stop before any irreversible step and notify the user instead. */
class MacroWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as OfflineAgentApp
        val m = app.automation.macros.find(inputData.getString("id") ?: return Result.success()) ?: return Result.success()
        Notifications.ensureChannels(app)
        if (OfflineAccessibilityService.instance == null || !app.settings.current().automationEnabled) {
            Notifications.post(app, Notifications.CH_AUTOMATION, m.id.hashCode(), "Macro \"${m.name}\" did not run", "Screen automation is off. Open the app to run it.", "macros")
            return Result.success()
        }
        if (m.params.isNotEmpty()) {
            Notifications.post(app, Notifications.CH_AUTOMATION, m.id.hashCode(), "Macro \"${m.name}\" needs values", "Open the app to run it with ${m.params.joinToString()}.", "macros")
            return Result.success()
        }
        val r = runCatching { app.automation.play(m, emptyList(), unattended = true) }.getOrElse { "failed: ${it.message}" }
        Notifications.post(app, Notifications.CH_AUTOMATION, m.id.hashCode(), "Macro \"${m.name}\"", r, "macros")
        return Result.success()
    }
}
