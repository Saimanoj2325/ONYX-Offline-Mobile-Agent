package com.offlineagent.app.onyx.apps

import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.core.agents.ApprovalRequest
import com.offlineagent.core.agents.Risk
import com.offlineagent.core.apps.AppBridge
import com.offlineagent.core.apps.AppSafetyPolicy
import com.offlineagent.core.apps.NotificationStore
import com.offlineagent.core.runtime.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Phase 6 AppBridge registration — the ONLY place :app wires :core:apps. OnyxRuntime calls:
 *  - [tools]          → joined into the shared tool registry (chat agent + multi-agent orchestrator)
 *  - [risk]           → per-call risk override in the ToolGateway (CRITICAL = per step, never scoped/always)
 *  - [preApproved]    → a scoped "allow <App> for this task" grant covers the request (audited)
 *  - [scopeLabel]     → label of the scope button on the approval sheet / inline card (null = allow once only)
 *  - [grant]          → user tapped the scope button
 *  - [endRun]         → drop the run's grants when the task ends
 */
class AppBridgeTools(private val app: OfflineAgentApp) {
    init { instance = this }

    companion object {
        @Volatile private var instance: AppBridgeTools? = null
        /** For the inline approval card (it only sees the ApprovalRequest). */
        fun scopeLabelOf(r: ApprovalRequest): String? = instance?.scopeLabel(r)
    }

    val policy: AppSafetyPolicy = AppSafetyPolicy.default(selfPackages = setOf(app.packageName, "com.offlineagent.app"))
    private val notificationStore = NotificationStore(policy).also { AppBridgeNotifications.store = it }

    val bridge: AppBridge by lazy {
        AppBridge(
            policy = policy,
            source = AndroidPackageSource(app),
            launcher = AndroidAppLauncher(app, app.launcher),
            clipboard = AndroidClipboard(app),
            actuator = AppBridgeNotifications.Actuator(app),
            files = OnyxFileResolver(app),
            notifications = notificationStore,
            audit = { action, detail, risk -> runCatching { app.onyx.audit("appbridge", "AppBridge", action, detail, risk) } },
        ).also { startWatchingPackages() }
    }

    val tools: List<Tool> get() = bridge.tools

    fun risk(name: String, args: JsonObject): Risk? = if (AppBridge.NAMES.contains(name)) bridge.risk(name, args) else null

    fun preApproved(r: ApprovalRequest): Boolean = AppBridge.NAMES.contains(r.tool) && bridge.preApproved(r.runId, r.tool, r.args, r.risk)

    fun scopeLabel(r: ApprovalRequest): String? = if (AppBridge.NAMES.contains(r.tool)) bridge.scopeLabel(r.tool, r.args, r.risk) else null

    fun grant(r: ApprovalRequest) { if (AppBridge.NAMES.contains(r.tool)) bridge.grantScope(r.runId, r.tool, r.args) }

    fun endRun(runId: String) { runCatching { bridge.scopes.clear(runId) } }

    private var refreshJob: Job? = null
    private fun startWatchingPackages() {
        PackageChangeReceiver { _ ->
            refreshJob?.cancel()
            refreshJob = app.appScope.launch(Dispatchers.Default) { delay(1500); runCatching { bridge.catalogue.refresh() } }
        }.register(app)
        app.appScope.launch(Dispatchers.Default) { runCatching { bridge.catalogue.refresh() } }
    }
}
