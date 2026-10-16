package com.offlineagent.app.work

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.phone.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A long task the foreground service keeps alive. */
data class LongTask(val id: String, val title: String, val progress: String, val fraction: Float? = null)

/**
 * Keeps the process (and the loaded model) alive while a user-started long task runs: agent runs, report/PDF
 * generation, screen automation. Shows progress and a Cancel action. Started/stopped by [LongTasks].
 */
class AgentForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            (application as OfflineAgentApp).cancelAllLongTasks()
            stopSelf()
            return START_NOT_STICKY
        }
        val n = build(this, LongTasks.current.value)
        ServiceCompat.startForeground(this, ID, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    companion object {
        const val ID = 4242
        const val ACTION_CANCEL = "com.offlineagent.app.CANCEL_TASK"

        fun build(ctx: Context, t: LongTask?): Notification {
            Notifications.ensureChannels(ctx)
            val cancel = PendingIntent.getService(ctx, 1, Intent(ctx, AgentForegroundService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, Notifications.CH_AGENT)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(t?.title ?: "Offline Agent is working")
                .setContentText(t?.progress ?: "Running on this phone")
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setContentIntent(Notifications.openApp(ctx))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
                .apply { t?.fraction?.let { setProgress(100, (it * 100).toInt(), false) } ?: setProgress(0, 0, true) }
                .build()
        }
    }
}

/** Process-wide registry of running long tasks; drives the foreground service. */
object LongTasks {
    private val tasks = LinkedHashMap<String, LongTask>()
    private val _current = MutableStateFlow<LongTask?>(null)
    val current: StateFlow<LongTask?> = _current.asStateFlow()

    @Synchronized fun start(ctx: Context, task: LongTask) {
        val first = tasks.isEmpty()
        tasks[task.id] = task
        _current.value = task
        if (first) runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, AgentForegroundService::class.java)) }
        else update(ctx, task)
    }

    @Synchronized fun update(ctx: Context, task: LongTask) {
        if (task.id !in tasks) return
        tasks[task.id] = task
        _current.value = task
        runCatching { NotificationManagerCompat.from(ctx).notify(AgentForegroundService.ID, AgentForegroundService.build(ctx, task)) }
    }

    @Synchronized fun finish(ctx: Context, id: String) {
        tasks.remove(id)
        _current.value = tasks.values.lastOrNull()
        if (tasks.isEmpty()) ctx.stopService(Intent(ctx, AgentForegroundService::class.java))
    }
}
