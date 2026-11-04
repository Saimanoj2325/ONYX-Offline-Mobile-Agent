package com.offlineagent.app.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.core.runtime.phone.Bounds
import com.offlineagent.core.runtime.phone.MacroRecorder
import com.offlineagent.core.runtime.phone.RecordedEvent
import com.offlineagent.core.runtime.phone.ScreenAction
import com.offlineagent.core.runtime.phone.ScreenDriver
import com.offlineagent.core.runtime.phone.ScreenNode
import com.offlineagent.core.runtime.phone.ScreenSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Opt-in accessibility service: reads the active window's view tree, performs taps / typing / scrolls / back for the
 * screen agent and macros, records demonstrations, and shows the floating control panel (step preview, Pause/Resume,
 * Stop, Allow/Deny, answer box). It does nothing on its own: every action comes from a task the user started.
 */
class OfflineAccessibilityService : AccessibilityService(), ScreenDriver {
    private lateinit var overlay: AutomationOverlay
    @Volatile var recorder: MacroRecorder? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = AutomationOverlay(this)
        ref = WeakReference(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ref = WeakReference(null)
        runCatching { overlay.hide() }
        (application as? OfflineAgentApp)?.automation?.onServiceGone()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 5: the Context agent's "foreground app" (only while the user has this service enabled).
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) event.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { lastForegroundPackage = it }
        val rec = recorder ?: return
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: ""
        val t = System.currentTimeMillis()
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> rec.add(RecordedEvent.WindowChanged(pkg, t))
            AccessibilityEvent.TYPE_VIEW_CLICKED -> e.source?.let { rec.add(RecordedEvent.Clicked(convert(it, 0, emptyList(), shallow = true), pkg, t)) }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> e.source?.let { src ->
                if (!src.isPassword) rec.add(RecordedEvent.TextChanged(convert(src, 0, emptyList(), shallow = true), pkg, src.text?.toString() ?: "", t))
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> rec.add(RecordedEvent.Scrolled(pkg, if (e.scrollDeltaY < 0) "up" else "down", t))
        }
    }

    // ------------------------------------------------------------ reading the screen

    private fun convert(n: AccessibilityNodeInfo, depth: Int, path: List<Int>, shallow: Boolean = false): ScreenNode {
        val r = Rect().also { n.getBoundsInScreen(it) }
        val kids = if (shallow || depth > MAX_DEPTH) emptyList() else (0 until n.childCount).mapNotNull { i ->
            n.getChild(i)?.let { c -> if (c.isVisibleToUser || c.childCount > 0) convert(c, depth + 1, path + i) else null }
        }
        return ScreenNode(
            className = n.className?.toString() ?: "", text = if (n.isPassword) (if ((n.text?.length ?: 0) > 0) "••••" else "") else n.text?.toString() ?: "",
            contentDescription = n.contentDescription?.toString() ?: "", hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() ?: "" else "",
            viewId = n.viewIdResourceName ?: "", bounds = Bounds(r.left, r.top, r.right, r.bottom),
            clickable = n.isClickable, longClickable = n.isLongClickable, editable = n.isEditable, scrollable = n.isScrollable,
            checkable = n.isCheckable, checked = n.isChecked, focused = n.isFocused, password = n.isPassword, enabled = n.isEnabled,
            selected = n.isSelected, autofillHints = emptyList(), inputType = n.inputType, children = kids, path = path,
        )
    }

    override suspend fun snapshot(): ScreenSnapshot? = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext null
        val pkg = root.packageName?.toString() ?: ""
        if (pkg == packageName) {
            // Our own overlay/app is in front: nothing to automate.
            return@withContext ScreenSnapshot(pkg, ScreenNode(), "Offline Agent")
        }
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        ScreenSnapshot(pkg, convert(root, 0, emptyList()), label)
    }

    private fun resolve(path: List<Int>): AccessibilityNodeInfo? {
        var n = rootInActiveWindow ?: return null
        for (i in path) n = (if (i < n.childCount) n.getChild(i) else null) ?: return null
        return n
    }

    // ------------------------------------------------------------ acting

    override suspend fun perform(a: ScreenAction, s: ScreenSnapshot): Boolean = withContext(Dispatchers.Main) {
        when (a) {
            is ScreenAction.Tap -> {
                val el = s.element(a.element) ?: return@withContext false
                val node = resolve(el.node.path)
                var target = node
                while (target != null && !target.isClickable) target = target.parent
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) true
                else tapAt(el.node.bounds.centerX.toFloat(), el.node.bounds.centerY.toFloat())
            }
            is ScreenAction.Type -> {
                val el = s.element(a.element) ?: return@withContext false
                val node = resolve(el.node.path) ?: return@withContext false
                if (node.isPassword) return@withContext false
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.text)
                })
                if (ok && a.submit && Build.VERSION.SDK_INT >= 30) node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                ok
            }
            is ScreenAction.Scroll -> {
                val scrollable = s.elements.firstOrNull { it.node.scrollable }?.let { resolve(it.node.path) }
                val act = if (a.direction == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                if (scrollable != null && scrollable.performAction(act)) true else swipe(a.direction == "up")
            }
            ScreenAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            ScreenAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            is ScreenAction.OpenApp -> openApp(a.app)
            is ScreenAction.Wait -> { delay(a.millis.coerceAtMost(5000)); true }
            else -> true
        }
    }

    private fun openApp(app: String): Boolean {
        // Phase 6.1: the launcher has no launch intent — "open the launcher" means go home.
        if ((application as? OfflineAgentApp)?.automation?.launcher?.isHome(app) == true) return performGlobalAction(GLOBAL_ACTION_HOME)
        val pm = packageManager
        val launch = pm.getLaunchIntentForPackage(app) ?: run {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(i, 0).firstOrNull { it.loadLabel(pm).toString().contains(app, true) }?.activityInfo?.packageName?.let { pm.getLaunchIntentForPackage(it) }
        } ?: return false
        return runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    private suspend fun gesture(path: Path, duration: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    private suspend fun tapAt(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 60)

    private suspend fun swipe(up: Boolean): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (y1, y2) = if (up) dm.heightPixels * 0.3f to dm.heightPixels * 0.75f else dm.heightPixels * 0.75f to dm.heightPixels * 0.3f
        return gesture(Path().apply { moveTo(x, y1); lineTo(x, y2) }, 350)
    }

    override suspend fun describeScreenshot(goal: String): String? = (application as OfflineAgentApp).automation.describeScreenshot(this, goal)

    fun overlay(): AutomationOverlay = overlay

    companion object {
        const val MAX_DEPTH = 40
        @Volatile private var ref = WeakReference<OfflineAccessibilityService>(null)
        val instance: OfflineAccessibilityService? get() = ref.get()
        /** Package of the last window that came to the front (null until the service sees one). */
        @Volatile var lastForegroundPackage: String? = null

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return flat.split(':').any { it.contains(ctx.packageName + "/") && it.contains("OfflineAccessibilityService") }
        }
    }
}

/**
 * Floating control panel drawn with TYPE_ACCESSIBILITY_OVERLAY (no SYSTEM_ALERT_WINDOW permission needed). Plain
 * views on purpose: it must stay responsive while another app is in front.
 */
class AutomationOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = androidx.core.content.ContextCompat.getMainExecutor(service)
    private var root: LinearLayout? = null
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var input: EditText
    private lateinit var b1: Button
    private lateinit var b2: Button
    private lateinit var b3: Button
    private var params: WindowManager.LayoutParams? = null
    var onPauseToggle: () -> Unit = {}
    var onStop: () -> Unit = {}

    private fun ensure() {
        if (root != null) return
        val dp = service.resources.displayMetrics.density
        val pad = (12 * dp).toInt()
        title = TextView(service).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; text = "Offline Agent" }
        body = TextView(service).apply { setTextColor(0xFFE0E0E0.toInt()); textSize = 15f; maxLines = 6 }
        input = EditText(service).apply { setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFFAAAAAA.toInt()); visibility = View.GONE; inputType = InputType.TYPE_CLASS_TEXT }
        b1 = Button(service); b2 = Button(service); b3 = Button(service)
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; addView(b1); addView(b2); addView(b3) }
        root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xEE1B1F24.toInt())
            addView(title); addView(body); addView(input); addView(row)
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }
        wm.addView(root, params)
    }

    private fun focusable(f: Boolean) {
        val p = params ?: return
        p.flags = if (f) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(root, p) }
    }

    private fun buttons(vararg specs: Pair<String, () -> Unit>) {
        listOf(b1, b2, b3).forEachIndexed { i, b ->
            val s = specs.getOrNull(i)
            b.visibility = if (s == null) View.GONE else View.VISIBLE
            if (s != null) { b.text = s.first; b.setOnClickListener { s.second() } }
        }
    }

    /** Running state: step preview with Pause/Resume and Stop. */
    fun showStep(header: String, text: String, paused: Boolean) = ui.execute {
        ensure(); focusable(false)
        title.text = header; body.text = text; input.visibility = View.GONE
        buttons((if (paused) "Resume" else "Pause") to { onPauseToggle() }, "Stop" to { onStop() })
    }

    suspend fun approve(header: String, action: String, reason: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        ui.execute {
            ensure(); focusable(false)
            title.text = "$header — needs your OK"; body.text = "$action\n($reason)"; input.visibility = View.GONE
            buttons("Allow" to { d.complete(true) }, "Deny" to { d.complete(false) }, "Stop" to { d.complete(false); onStop() })
        }
        return d.await()
    }

    suspend fun ask(header: String, question: String): String? {
        val d = CompletableDeferred<String?>()
        ui.execute {
            ensure(); focusable(true)
            title.text = header; body.text = question; input.setText(""); input.hint = "Type your answer"; input.visibility = View.VISIBLE
            buttons("Send" to { d.complete(input.text.toString()) }, "Cancel" to { d.complete(null) })
        }
        return d.await().also { ui.execute { focusable(false); input.visibility = View.GONE } }
    }

    fun showRecording(onSave: () -> Unit, onCancel: () -> Unit) = ui.execute {
        ensure(); focusable(false)
        title.text = "Recording a macro"; body.text = "Do the task normally. Passwords are never recorded."; input.visibility = View.GONE
        buttons("Stop & save" to onSave, "Cancel" to onCancel)
    }

    fun showDone(text: String) = ui.execute {
        ensure(); focusable(false)
        title.text = "Offline Agent — finished"; body.text = text; input.visibility = View.GONE
        buttons("Close" to { hide() })
    }

    fun hide() = ui.execute { root?.let { runCatching { wm.removeView(it) } }; root = null }

}
