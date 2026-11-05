package com.offlineagent.app.automation

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.offlineagent.app.MainActivity
import com.offlineagent.core.agents.PromptAnswer
import com.offlineagent.core.agents.PromptKind
import com.offlineagent.core.agents.PromptPresenter
import com.offlineagent.core.agents.PromptReply
import com.offlineagent.core.agents.PromptRouter
import com.offlineagent.core.agents.PromptSurface
import com.offlineagent.core.agents.Risk
import com.offlineagent.core.agents.SurfaceState
import com.offlineagent.core.agents.UserPrompt
import com.offlineagent.core.runtime.phone.Bounds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Phase 6.2: is any ONYX activity resumed (then prompts use the in-app ApprovalSheet)? */
object ForegroundTracker : Application.ActivityLifecycleCallbacks {
    private val resumed = AtomicInteger(0)
    @Volatile private var installed = false
    val onyxInForeground: Boolean get() = resumed.get() > 0
    fun install(app: Application) { if (!installed) { installed = true; app.registerActivityLifecycleCallbacks(this) } }
    override fun onActivityResumed(a: Activity) { resumed.incrementAndGet() }
    override fun onActivityPaused(a: Activity) { if (resumed.decrementAndGet() < 0) resumed.set(0) }
    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}

/**
 * Phase 6.2: one place that shows approvals, questions and hand-offs from the screen agent, macros and AppBridge.
 * Routing ([PromptRouter], JVM-tested): ONYX in front → its ApprovalSheet; otherwise a card drawn ON TOP of the
 * current app with the accessibility overlay; without the service a "display over other apps" overlay; else a
 * heads-up notification with Allow / Deny. Secure / password screens are never covered.
 */
object PromptCenter {
    private val ids = AtomicInteger(1)

    fun state(ctx: Context): SurfaceState {
        val svc = OfflineAccessibilityService.instance
        val (sensitive, y) = svc?.let { runCatching { it.sensitiveScreen() }.getOrNull() } ?: (false to -1f)
        return SurfaceState(
            onyxInForeground = ForegroundTracker.onyxInForeground,
            accessibilityOverlay = svc != null,
            systemOverlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx),
            notifications = NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            sensitiveScreen = sensitive, sensitiveFieldY = y,
        )
    }

    fun newId(prefix: String) = "$prefix-${ids.getAndIncrement()}"

    /** Shows [p] on the best surface. [inApp] is the caller's own in-ONYX sheet (null = use the overlay chain anyway). */
    suspend fun ask(ctx: Context, p: UserPrompt, inApp: PromptPresenter? = null): PromptReply {
        val app = ctx.applicationContext
        val presenters = HashMap<PromptSurface, PromptPresenter>()
        presenters[PromptSurface.ACCESSIBILITY_OVERLAY] = PromptPresenter { q ->
            OfflineAccessibilityService.instance?.let { PromptCard(it, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).show(q) } }
        presenters[PromptSurface.SYSTEM_OVERLAY] = PromptPresenter { q ->
            if (Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(app)) PromptCard(app, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).show(q) else null }
        presenters[PromptSurface.NOTIFICATION] = PromptPresenter { q -> PromptNotifications.ask(app, q) }
        presenters[PromptSurface.IN_APP_SHEET] = inApp ?: PromptPresenter { q ->
            // No ONYX sheet for this source: the overlay is still the right place (or the notification).
            OfflineAccessibilityService.instance?.let { PromptCard(it, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).show(q) } ?: PromptNotifications.ask(app, q) }
        return PromptRouter({ state(app) }, presenters).ask(p)
    }

    fun riskLabel(r: Risk) = when (r) { Risk.NONE, Risk.LOW -> "low risk"; Risk.MEDIUM -> "medium risk"; Risk.HIGH -> "high risk"; Risk.CRITICAL -> "asks every time" }
}

/**
 * The floating card (accessibility overlay or system overlay). Plain views so it stays responsive over any app.
 * Draggable by its header; the compact "Your turn" pill docks away from a sensitive field.
 */
class PromptCard(private val ctx: Context, private val type: Int) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = ctx.resources.displayMetrics.density

    private fun btn(text: String, primary: Boolean = false, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; isAllCaps = false; textSize = 13f
        setTextColor(if (primary) 0xFF0B1220.toInt() else 0xFFFFFFFF.toInt())
        background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(if (primary) 0xFF8AB4F8.toInt() else 0x33FFFFFF) }
        setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun draggable(handle: View, root: View, lp: WindowManager.LayoutParams) {
        var startY = 0f; var startX = 0f; var y0 = 0; var x0 = 0
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = ev.rawY; startX = ev.rawX; y0 = lp.y; x0 = lp.x; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.y = (y0 + (ev.rawY - startY).toInt() * if (lp.gravity and Gravity.BOTTOM == Gravity.BOTTOM) -1 else 1).coerceAtLeast(0)
                    lp.x = x0 + (ev.rawX - startX).toInt()
                    runCatching { wm.updateViewLayout(root, lp) }; true
                }
                else -> false
            }
        }
    }

    suspend fun show(p: UserPrompt): PromptReply? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var root: View? = null
            fun done(r: PromptReply) { root?.let { v -> runCatching { wm.removeView(v) } }; root = null; if (cont.isActive) cont.resume(r) }
            val focusable = p.kind == PromptKind.ASK
            val lp = WindowManager.LayoutParams(
                if (p.compact) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                // The pill docks on the side away from the password / code field; the card sits at the top.
                gravity = if (p.compact && p.sensitiveFieldY in 0f..0.5f) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (if (p.compact) 24 else 36) * dp.toInt()
            }
            val bg = GradientDrawable().apply { cornerRadius = 20 * dp; setColor(0xF01B1F24.toInt()); setStroke((1 * dp).toInt(), riskColor(p)) }
            val view: View = if (p.compact) {
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg
                    setPadding((12 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
                    val t = TextView(ctx).apply { text = "Your turn"; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; setPadding(0, 0, (8 * dp).toInt(), 0) }
                    addView(t)
                    addView(btn("Resume", true) { done(PromptReply(PromptAnswer.RESUME)) })
                    addView(btn("Stop") { done(PromptReply(PromptAnswer.STOP)) })
                    draggable(t, this, lp)
                }
            } else {
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; background = bg
                    val pad = (14 * dp).toInt(); setPadding(pad, pad, pad, pad)
                    val headerText = when (p.kind) {
                        PromptKind.HANDOFF -> "Your turn"
                        PromptKind.ASK -> "${p.source} has a question"
                        PromptKind.APPROVE -> "${p.source} needs your OK"
                    }
                    val header = TextView(ctx).apply {
                        text = "$headerText  ·  ${p.appLabel.ifBlank { "this app" }}  ·  ${PromptCenter.riskLabel(p.risk)}   ⠿"
                        setTextColor(riskColor(p)); textSize = 12f
                    }
                    addView(header)
                    addView(TextView(ctx).apply { text = p.action; setTextColor(0xFFFFFFFF.toInt()); textSize = 16f; maxLines = 5; setPadding(0, (6 * dp).toInt(), 0, (4 * dp).toInt()) })
                    if (p.reason.isNotBlank()) addView(TextView(ctx).apply { text = p.reason; setTextColor(0xFFC8CDD3.toInt()); textSize = 13f; maxLines = 4 })
                    if (p.irreversible) addView(TextView(ctx).apply { text = "This may send, pay, submit or delete something — ONYX asks every time."; setTextColor(0xFFFFB4A9.toInt()); textSize = 12f })
                    val input = if (p.kind == PromptKind.ASK) EditText(ctx).apply {
                        setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFFAAAAAA.toInt()); hint = "Type your answer"; inputType = InputType.TYPE_CLASS_TEXT
                    }.also { addView(it) } else null
                    val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (8 * dp).toInt(), 0, 0) }
                    val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    fun gap() = View(ctx).apply { layoutParams = LinearLayout.LayoutParams((6 * dp).toInt(), 1) }
                    when (p.kind) {
                        PromptKind.APPROVE -> {
                            row1.addView(btn("Allow once", true) { done(PromptReply(PromptAnswer.ALLOW_ONCE)) }); row1.addView(gap())
                            if (p.allowTaskScope && !p.irreversible) { row1.addView(btn("Allow for this task") { done(PromptReply(PromptAnswer.ALLOW_TASK)) }) }
                            row2.addView(btn("Deny") { done(PromptReply(PromptAnswer.DENY)) }); row2.addView(gap())
                            row2.addView(btn("Stop") { done(PromptReply(PromptAnswer.STOP)) })
                        }
                        PromptKind.ASK -> {
                            row1.addView(btn("Send", true) { done(PromptReply(PromptAnswer.ALLOW_ONCE, input?.text?.toString().orEmpty())) }); row1.addView(gap())
                            row1.addView(btn("Skip") { done(PromptReply(PromptAnswer.DENY)) }); row1.addView(gap())
                            row1.addView(btn("Stop") { done(PromptReply(PromptAnswer.STOP)) })
                        }
                        PromptKind.HANDOFF -> {
                            row1.addView(btn("Resume", true) { done(PromptReply(PromptAnswer.RESUME)) }); row1.addView(gap())
                            row1.addView(btn("Stop") { done(PromptReply(PromptAnswer.STOP)) })
                        }
                    }
                    addView(row1); if (row2.childCount > 0) { row2.setPadding(0, (6 * dp).toInt(), 0, 0); addView(row2) }
                    draggable(header, this, lp)
                }
            }
            lp.width = if (p.compact) WindowManager.LayoutParams.WRAP_CONTENT else (ctx.resources.displayMetrics.widthPixels - (24 * dp).toInt())
            val added = runCatching { wm.addView(view, lp); true }.getOrDefault(false)
            if (!added) { if (cont.isActive) cont.resume(null) } else root = view
            cont.invokeOnCancellation { view.post { runCatching { wm.removeView(view) } } }
        }
    }

    private fun riskColor(p: UserPrompt): Int = when {
        p.kind == PromptKind.HANDOFF -> 0xFF8AB4F8.toInt()
        p.irreversible || p.risk == Risk.CRITICAL -> 0xFFFF8A80.toInt()
        p.risk == Risk.HIGH -> 0xFFFFCC80.toInt()
        else -> 0xFFA5D6A7.toInt()
    }
}

/** Phase 6.2 macro "Test run": a frame around the matched element (touches pass through it). */
object HighlightOverlay {
    suspend fun show(svc: OfflineAccessibilityService, b: Bounds, ms: Long) {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = svc.resources.displayMetrics.density
        val v = object : View(svc) {
            val paint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4 * dp; color = 0xFF8AB4F8.toInt(); isAntiAlias = true }
            override fun onDraw(c: Canvas) { c.drawRoundRect(2 * dp, 2 * dp, width - 2 * dp, height - 2 * dp, 8 * dp, 8 * dp, paint) }
        }
        val lp = WindowManager.LayoutParams((b.width + 12 * dp).toInt().coerceAtLeast(24), (b.height + 12 * dp).toInt().coerceAtLeast(24),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = b.left - (6 * dp).toInt(); y = b.top - (6 * dp).toInt() }
        withContext(Dispatchers.Main) { runCatching { wm.addView(v, lp) } }
        try { delay(ms) } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { runCatching { wm.removeView(v) } } }
    }
}

/** Heads-up notification fallback with Allow / Deny (and Allow for this task, Resume, or a reply box). */
object PromptNotifications {
    const val CHANNEL = "approvals"
    const val ACTION = "com.offlineagent.app.PROMPT_ANSWER"
    private const val KEY_TEXT = "answer_text"
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<PromptReply>>()
    private val nextId = AtomicInteger(7100)

    private fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Approvals & your turn", NotificationManager.IMPORTANCE_HIGH).apply { description = "ONYX asks before risky steps" })
    }

    private fun action(ctx: Context, id: Int, answer: PromptAnswer): PendingIntent = PendingIntent.getBroadcast(ctx, id * 10 + answer.ordinal,
        Intent(ctx, PromptActionReceiver::class.java).setAction(ACTION).putExtra("id", id).putExtra("answer", answer.name),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))

    @SuppressLint("MissingPermission")
    suspend fun ask(ctx: Context, p: UserPrompt): PromptReply? {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return null
        ensure(ctx)
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<PromptReply>()
        pending[id] = d
        val title = when (p.kind) { PromptKind.HANDOFF -> "Your turn — ${p.appLabel}"; PromptKind.ASK -> "${p.source} has a question"; else -> "${p.source} needs your OK · ${PromptCenter.riskLabel(p.risk)}" }
        val b = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title)
            .setContentText(p.action).setStyle(NotificationCompat.BigTextStyle().bigText(p.action + if (p.reason.isNotBlank()) "\n" + p.reason else ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_CALL).setAutoCancel(true).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
        when (p.kind) {
            PromptKind.APPROVE -> {
                b.addAction(0, "Allow once", action(ctx, id, PromptAnswer.ALLOW_ONCE))
                if (p.allowTaskScope && !p.irreversible) b.addAction(0, "Allow for task", action(ctx, id, PromptAnswer.ALLOW_TASK))
                b.addAction(0, "Deny", action(ctx, id, PromptAnswer.DENY))
            }
            PromptKind.HANDOFF -> { b.addAction(0, "Resume", action(ctx, id, PromptAnswer.RESUME)); b.addAction(0, "Stop", action(ctx, id, PromptAnswer.STOP)) }
            PromptKind.ASK -> {
                val ri = RemoteInput.Builder(KEY_TEXT).setLabel("Your answer").build()
                b.addAction(NotificationCompat.Action.Builder(0, "Reply", action(ctx, id, PromptAnswer.ALLOW_ONCE)).addRemoteInput(ri).build())
                b.addAction(0, "Skip", action(ctx, id, PromptAnswer.DENY))
            }
        }
        if (runCatching { nm.notify(id, b.build()); true }.getOrDefault(false).not()) { pending.remove(id); return null }
        return try { d.await() } finally { pending.remove(id); nm.cancel(id) }
    }

    fun answer(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val a = runCatching { PromptAnswer.valueOf(intent.getStringExtra("answer") ?: "") }.getOrNull() ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()
        pending[id]?.complete(PromptReply(a, text))
        NotificationManagerCompat.from(ctx).cancel(id)
    }
}

class PromptActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { if (intent.action == PromptNotifications.ACTION) PromptNotifications.answer(context, intent) }
}
