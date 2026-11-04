package com.offlineagent.app.onyx.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.offlineagent.app.BuildConfig
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.automation.OfflineAccessibilityService
import com.offlineagent.app.onyx.OnyxRuntime
import com.offlineagent.core.agents.ApprovalGate
import com.offlineagent.core.agents.InferenceQueue
import com.offlineagent.core.agents.Risk
import com.offlineagent.core.runtime.phone.AutomationLogEntry
import com.offlineagent.core.runtime.phone.PhoneGroup
import com.offlineagent.core.runtime.phone.ScreenNode
import com.offlineagent.core.runtime.tools.Tool
import com.offlineagent.core.screen.DefaultScreenSafetyPolicy
import com.offlineagent.core.screen.FileAppCardStore
import com.offlineagent.core.screen.GlobalOp
import com.offlineagent.core.screen.KillSwitch
import com.offlineagent.core.screen.NodeOp
import com.offlineagent.core.screen.ScreenAudit
import com.offlineagent.core.screen.ScreenConfig
import com.offlineagent.core.screen.ScreenFrame
import com.offlineagent.core.screen.ScreenHost
import com.offlineagent.core.screen.ScreenLlm
import com.offlineagent.core.screen.ScreenPort
import com.offlineagent.core.screen.ScreenToolkit
import com.offlineagent.core.screen.ScreenView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.coroutines.resume

/**
 * Phase 6 "Screen tools v2" wiring: the pure-Kotlin [ScreenToolkit] (`:core:screen`) on Android ports. The screen_*
 * tools join the shared registry through [OnyxRuntime.extraTools]; [wrap] adds per-app scoped approvals in front of
 * the ApprovalSheet; [visible] hides them (and shows the legacy `run_phone_task` loop instead) while the setting is off.
 *
 * SIDELOAD ONLY: Google Play's Accessibility policy forbids apps that autonomously plan and execute actions through
 * the AccessibilityService. The feature is compiled in only when BuildConfig.SCREEN_AGENT_V2 is true (`-PscreenAgent=false`
 * removes it) and is OFF until the user turns it on in Agents → Screen agent.
 */
class ScreenTools(private val app: OfflineAgentApp, private val onyx: OnyxRuntime) {
    val available: Boolean = BuildConfig.SCREEN_AGENT_V2
    val prefs = ScreenPrefs(app)
    @Volatile private var stoppedRun: String? = null
    private fun runId(): String? = onyx.current.value?.runId?.takeIf { onyx.isActive(it) }

    val cards by lazy { FileAppCardStore(File(app.filesDir, "app-cards")) }

    private val audit = ScreenAudit { action, detail, risk ->
        runCatching { onyx.audit(runId() ?: "screen", "Screen", action, detail, risk) }
        runCatching { app.automationLog.add(AutomationLogEntry(System.currentTimeMillis(), 0, detail.substringAfter("app=", "").substringBefore(' '), action, detail.take(200))) }
    }

    /** Settings kill switch, the Screen-automation group, or the overlay's Stop for the current task. */
    private val kill = object : KillSwitch {
        override fun engaged(): Boolean {
            val s = app.cachedSettings
            return !s.automationEnabled || PhoneGroup.SCREEN_AUTOMATION.name !in s.phoneGroups || (stoppedRun != null && stoppedRun == runId())
        }
        override fun reason(): String {
            val s = app.cachedSettings
            return when {
                !s.automationEnabled -> "Phone automation is switched off (kill switch in Settings → Phone actions). Turn it back on to continue."
                PhoneGroup.SCREEN_AUTOMATION.name !in s.phoneGroups -> "Screen automation is not allowed in Settings → Phone actions. Turn it on (or switch Screen agent off and on in the Agents tab)."
                else -> "you stopped this task from the overlay."
            }
        }
    }

    private val llm: () -> ScreenLlm? = {
        if (!onyx.hasChatModel.value) null else object : ScreenLlm {
            override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int) =
                onyx.queue.complete(InferenceQueue.Priority.INTERACTIVE, "screen", system, user, grammar, maxTokens)
            override suspend fun stream(system: String, user: String, maxTokens: Int, onPartial: (String) -> Boolean): String {
                var last = ""
                return try {
                    onyx.queue.stream(InferenceQueue.Priority.INTERACTIVE, "screen", system, user, null, maxTokens) { p -> last = p; if (onPartial(p)) throw EarlyExit() }
                } catch (e: EarlyExit) { last } // grounded early exit: decoding stopped on purpose
            }
        }
    }

    val toolkit: ScreenToolkit by lazy {
        val port = AndroidScreenPort(app)
        ScreenToolkit(
            port = port,
            host = OverlayScreenHost { stop() },
            // One shared blocklist: AppBridge's AppSafetyPolicy (:core:apps) is consulted with Use.SCREEN, then the screen rules.
            policy = DefaultScreenSafetyPolicy(app.packageName, { app.cachedSettings.automationAllowedApps }, { app.cachedSettings.automationBlockedApps },
                shared = onyx.appBridge.policy, labelOf = { pkg -> port.appLabel(pkg).takeIf { it != pkg } }),
            audit = audit, kill = kill, cards = cards, llm = llm,
            config = { ScreenConfig(enabled = enabled(), stepsPerScope = prefs.stepsPerScope) },
        )
    }

    fun enabled() = available && prefs.enabled.value

    /**
     * Phase 6.1: the Agents toggle is the user's opt-in, so turning it on also allows the Screen-automation phone group
     * (without it the kill switch refused every screen step and only app_open was left). Turning the group off in
     * Settings stays the kill switch.
     */
    fun setEnabled(on: Boolean) {
        prefs.setEnabled(on)
        if (on) app.appScope.launch {
            runCatching { app.settings.update { st -> if (PhoneGroup.SCREEN_AUTOMATION.name in st.phoneGroups) st else st.copy(phoneGroups = st.phoneGroups + PhoneGroup.SCREEN_AUTOMATION.name) } }
        }
    }

    /** Status for the banner (Chat + Agents tab). */
    fun status(): com.offlineagent.core.screen.ScreenAgentStatus.Status {
        val st = app.cachedSettings
        return com.offlineagent.core.screen.ScreenAgentStatus.of(
            available = available, agentOn = enabled(),
            serviceEnabledInSettings = runCatching { OfflineAccessibilityService.isEnabled(app) }.getOrDefault(false),
            serviceConnected = OfflineAccessibilityService.instance != null,
            automationOn = st.automationEnabled, screenGroupOn = PhoneGroup.SCREEN_AUTOMATION.name in st.phoneGroups,
        )
    }

    fun tools(): List<Tool> = if (available) toolkit.tools() else emptyList()

    /** Tool visibility for the orchestrator's planner. */
    fun visible(name: String): Boolean = when (name) {
        in ScreenToolkit.NAMES -> enabled()
        in ScreenToolkit.LEGACY -> !enabled()
        else -> true
    }

    fun risk(name: String, args: JsonObject): Risk? = if (available && name in ScreenToolkit.NAMES) toolkit.risk(name, args) else null
    fun usesModel(name: String) = name in ScreenToolkit.MODEL_TOOLS
    fun wrap(gate: ApprovalGate): ApprovalGate = if (available) toolkit.approvals.wrap(gate) else gate

    /** Overlay Stop: kills the running task's screen steps (kill switch for this run) and cancels the task. */
    fun stop() {
        stoppedRun = runId()
        onyx.cancel()
        OfflineAccessibilityService.instance?.overlay()?.hide()
    }

    private class EarlyExit : RuntimeException("grounded early exit")
}

/** User setting for the autonomous screen agent (off by default). */
class ScreenPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("onyx_screen", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(sp.getBoolean("enabled", false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val stepsPerScope: Int get() = sp.getInt("steps_per_scope", 30)
    fun setEnabled(v: Boolean) { _enabled.value = v; sp.edit().putBoolean("enabled", v).apply() }
}

/** Overlay side: previews, per-step Allow/Deny, "take over" hand-off (Allow = continue after the user did it). */
class OverlayScreenHost(private val onStop: () -> Unit) : ScreenHost {
    private fun ov() = OfflineAccessibilityService.instance?.overlay()?.also { o -> o.onStop = onStop; o.onPauseToggle = {} }
    override fun preview(text: String) { ov()?.showStep("ONYX · screen", text, false) }
    override suspend fun confirm(action: String, reason: String): Boolean = ov()?.approve("ONYX", action, reason) ?: false
    override suspend fun takeOver(reason: String): Boolean =
        ov()?.approve("ONYX — your turn", "Please do this part yourself: $reason", "tap Allow when you are done to let ONYX continue, or Stop") ?: false
}

/** AccessibilityService-backed port: node actions by path, launch intents, clipboard, share sheet, secure-window probe. */
class AndroidScreenPort(private val app: OfflineAgentApp) : ScreenPort {
    private fun svc(): OfflineAccessibilityService? = OfflineAccessibilityService.instance
    @Volatile private var secureCache: Triple<String, Long, Boolean>? = null

    override suspend fun capture(): ScreenFrame? {
        val s = svc() ?: return null
        val snap = s.snapshot() ?: return null
        return ScreenFrame(snap, secure = isSecure(s, snap.packageName), capturedAt = System.currentTimeMillis())
    }

    /** FLAG_SECURE windows come back black (or refused) from takeScreenshot; probed at most every 15 s per app. */
    private suspend fun isSecure(s: OfflineAccessibilityService, pkg: String): Boolean {
        if (Build.VERSION.SDK_INT < 30 || pkg == app.packageName || pkg.isBlank()) return false
        val now = System.currentTimeMillis()
        secureCache?.let { (p, at, v) -> if (p == pkg && now - at < 15_000) return v }
        val v = withTimeoutOrNull(1_500) { probe(s) } ?: return false // unknown (timeout / rate limit): not cached
        secureCache = Triple(pkg, now, v)
        return v
    }

    @RequiresApi(30)
    private suspend fun probe(s: OfflineAccessibilityService): Boolean? = suspendCancellableCoroutine { cont ->
        s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                r.hardwareBuffer.close()
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                val black = bmp?.let { b ->
                    // middle band only: our own overlay sits at the top and is never black
                    val band = Bitmap.createBitmap(b, 0, b.height * 3 / 10, b.width, b.height * 6 / 10)
                    val sm = Bitmap.createScaledBitmap(band, 24, 36, false)
                    var max = 0
                    for (x in 0 until sm.width) for (y in 0 until sm.height) { val p = sm.getPixel(x, y); max = maxOf(max, (p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) }
                    max < 24
                }
                if (cont.isActive) cont.resume(black)
            }
            // 5 = ERROR_TAKE_SCREENSHOT_SECURE_WINDOW. Others (rate limit, internal error): unknown → not cached, not secure.
            override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(if (errorCode == 5) true else null) }
        })
    }

    private fun resolve(s: OfflineAccessibilityService, path: List<Int>): AccessibilityNodeInfo? {
        var n = s.rootInActiveWindow ?: return null
        for (i in path) n = (if (i < n.childCount) n.getChild(i) else null) ?: return null
        return n
    }

    private fun up(n: AccessibilityNodeInfo, ok: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var t: AccessibilityNodeInfo? = n
        while (t != null && !ok(t)) t = t.parent
        return t
    }

    override suspend fun perform(node: ScreenNode, op: NodeOp, text: String): Boolean {
        val s = svc() ?: return false
        val ok = withContext(Dispatchers.Main) {
            val live = resolve(s, node.path)
            if (live == null || (live.className?.toString() ?: "") != node.className) false // gone / tree moved: caller re-snapshots
            else when (op) {
                NodeOp.CLICK -> up(live) { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                NodeOp.LONG_CLICK -> up(live) { it.isLongClickable }?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
                NodeOp.SET_TEXT -> !live.isPassword && live.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
                NodeOp.PASTE -> !live.isPassword && live.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                NodeOp.FOCUS -> live.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || live.isFocused
                NodeOp.SCROLL_FORWARD -> up(live) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
                NodeOp.SCROLL_BACKWARD -> up(live) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
                NodeOp.IME_ENTER -> Build.VERSION.SDK_INT >= 30 && live.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
        if (ok || op != NodeOp.CLICK || node.bounds.isEmpty) return ok
        return tap(s, node.bounds.centerX.toFloat(), node.bounds.centerY.toFloat()) // icon views without a clickable ancestor
    }

    private suspend fun tap(s: OfflineAccessibilityService, x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 60)).build()
            val sent = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!sent && cont.isActive) cont.resume(false)
        }
    }

    override suspend fun global(op: GlobalOp): Boolean = withContext(Dispatchers.Main) {
        svc()?.performGlobalAction(if (op == GlobalOp.BACK) AccessibilityService.GLOBAL_ACTION_BACK else AccessibilityService.GLOBAL_ACTION_HOME) ?: false
    }

    override suspend fun resolveApp(app: String): String? = withContext(Dispatchers.IO) {
        val pm = this@AndroidScreenPort.app.packageManager
        val a = app.trim()
        if (a.contains('.') && pm.getLaunchIntentForPackage(a) != null) return@withContext a
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val labelled = list.map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        (labelled.firstOrNull { it.first.equals(a, true) } ?: labelled.firstOrNull { it.first.contains(a, true) } ?: labelled.firstOrNull { a.contains(it.first, true) && it.first.length >= 3 })?.second
    }

    override suspend fun launch(pkg: String): Boolean = withContext(Dispatchers.Main) {
        val i = app.packageManager.getLaunchIntentForPackage(pkg) ?: return@withContext false
        // From the accessibility service context: exempt from background-activity-start limits.
        runCatching { ((svc() as Context?) ?: app).startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    override suspend fun setClipboard(text: String): Boolean = withContext(Dispatchers.Main) {
        runCatching { app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ONYX", text)); true }.getOrDefault(false)
    }

    override suspend fun share(text: String, pkg: String): Boolean = withContext(Dispatchers.Main) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(app.packageManager) == null) return@withContext false
        runCatching { ((svc() as Context?) ?: app).startActivity(i); true }.getOrDefault(false)
    }

    override suspend fun awaitIdle(timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        var prev: String? = null
        while (System.currentTimeMillis() < end) {
            val snap = svc()?.snapshot() ?: return
            val sig = ScreenView.signatureOf(ScreenFrame(snap))
            if (sig == prev) return
            prev = sig
            delay(120)
        }
    }

    override suspend fun openSettings(actions: List<String>): Boolean = withContext(Dispatchers.Main) {
        val ctx: Context = (svc() as Context?) ?: app
        for (a in actions) {
            val i = Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (runCatching { ctx.startActivity(i); true }.getOrDefault(false)) return@withContext true
        }
        false
    }

    override fun settingsPackage(): String = runCatching {
        @Suppress("DEPRECATION")
        app.packageManager.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
    }.getOrNull() ?: "com.android.settings"

    override fun appLabel(pkg: String): String = runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
}
