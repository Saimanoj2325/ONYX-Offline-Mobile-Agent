package com.offlineagent.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.vault.VaultUnlockActivity
import com.offlineagent.core.runtime.phone.AutofillMapper
import com.offlineagent.core.runtime.phone.FieldMatch
import com.offlineagent.core.runtime.phone.FormField
import com.offlineagent.core.runtime.phone.PhoneGroup
import com.offlineagent.core.runtime.phone.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * System autofill from the encrypted on-device profile, in any app or browser (Chrome passes web form fields with
 * HTML name/type/label). Fields are mapped by autofill hints + HTML autocomplete + keyword rules; unmapped fields go to
 * the on-device LLM when a chat model is already loaded (2 s budget, never loads one). Password, OTP, card and ID
 * fields are never filled. The vault must be unlocked (biometric/screen lock) — otherwise the suggestion asks to unlock.
 * Nothing is ever saved from other apps (no onSaveRequest storage).
 */
class OfflineAutofillService : AutofillService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val app = application as OfflineAgentApp
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        if (structure.activityComponent.packageName == packageName) return callback.onSuccess(null)
        val job = scope.launch {
            val enabled = app.settings.current().phoneGroups.contains(PhoneGroup.AUTOFILL.name)
            if (!enabled) { callback.onSuccess(null); return@launch }
            val parsed = parse(structure)
            if (parsed.isEmpty()) { callback.onSuccess(null); return@launch }
            val pkg = structure.activityComponent.packageName
            if (app.automationPolicy().isBlockedApp(pkg)) { callback.onSuccess(null); return@launch }
            val reqId = System.nanoTime().toString()
            pending[reqId] = parsed
            val profile = app.vault.current()
            val response = FillResponse.Builder()
            if (profile == null) {
                val i = Intent(this@OfflineAutofillService, VaultUnlockActivity::class.java).putExtra(VaultUnlockActivity.EXTRA_AUTOFILL, true).putExtra(EXTRA_REQ, reqId)
                val sender = PendingIntent.getActivity(this@OfflineAutofillService, reqId.hashCode(), i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT).intentSender
                response.setAuthentication(parsed.map { it.first }.toTypedArray(), sender, presentation("Unlock to fill from your Offline Agent profile"))
            } else {
                val ds = buildDataset(this@OfflineAutofillService, parsed, profile, app) ?: run { callback.onSuccess(null); return@launch }
                response.addDataset(ds)
            }
            runCatching { callback.onSuccess(response.build()) }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()

    companion object {
        const val EXTRA_REQ = "req"
        private val pending = ConcurrentHashMap<String, List<Pair<AutofillId, FormField>>>()

        fun presentation(text: String) = RemoteViews("android", android.R.layout.simple_list_item_1).apply { setTextViewText(android.R.id.text1, text) }

        /** Called by VaultUnlockActivity after unlocking. */
        fun datasetFor(ctx: Context, intent: Intent): Dataset? {
            val app = ctx.applicationContext as OfflineAgentApp
            val parsed = pending.remove(intent.getStringExtra(EXTRA_REQ) ?: return null) ?: return null
            val profile = app.vault.current() ?: return null
            return kotlinx.coroutines.runBlocking { buildDataset(ctx, parsed, profile, app) }
        }

        suspend fun buildDataset(ctx: Context, parsed: List<Pair<AutofillId, FormField>>, profile: Profile, app: OfflineAgentApp): Dataset? {
            val llm = app.readyLlmOrNull()
            val matches: List<FieldMatch> = withTimeoutOrNull(2_000) { AutofillMapper(llm).map(parsed.map { it.second }, profile) }
                ?: AutofillMapper(null).map(parsed.map { it.second }, profile)
            val b = Dataset.Builder()
            var n = 0
            matches.forEachIndexed { i, m ->
                val v = m.value(profile) ?: return@forEachIndexed
                @Suppress("DEPRECATION")
                b.setValue(parsed[i].first, AutofillValue.forText(v), presentation("${m.key?.label ?: m.custom?.label}: ${v.take(30)}"))
                n++
            }
            if (n == 0) return null
            app.automationLog.add(com.offlineagent.core.runtime.phone.AutomationLogEntry(System.currentTimeMillis(), 0, "autofill", "offered $n profile fields", "suggested"))
            return b.build()
        }

        /** Walks the view tree and returns fillable text fields with everything that describes them. */
        fun parse(structure: AssistStructure): List<Pair<AutofillId, FormField>> {
            val out = ArrayList<Pair<AutofillId, FormField>>()
            var lastLabel = ""
            fun walk(n: AssistStructure.ViewNode) {
                val text = n.text?.toString().orEmpty()
                if (n.autofillType != View.AUTOFILL_TYPE_TEXT && text.isNotBlank() && text.length < 80) lastLabel = text
                val id = n.autofillId
                if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT && n.visibility == View.VISIBLE) {
                    val html = n.htmlInfo
                    val attrs = html?.attributes?.associate { it.first.lowercase() to (it.second ?: "") } ?: emptyMap()
                    val pwd = (n.inputType and InputType.TYPE_MASK_VARIATION).let { it == InputType.TYPE_TEXT_VARIATION_PASSWORD || it == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || it == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD } ||
                        attrs["type"] == "password"
                    out += id to FormField(
                        index = out.size, autofillHints = n.autofillHints?.toList() ?: emptyList(), hint = n.hint ?: attrs["placeholder"] ?: "",
                        label = attrs["label"] ?: attrs["aria-label"] ?: n.contentDescription?.toString() ?: lastLabel,
                        viewId = n.idEntry ?: "", inputType = n.inputType, htmlName = attrs["autocomplete"] ?: attrs["name"] ?: "", htmlType = attrs["type"] ?: "",
                        currentValue = n.autofillValue?.takeIf { it.isText }?.textValue?.toString() ?: "", isPassword = pwd,
                    )
                }
                for (i in 0 until n.childCount) walk(n.getChildAt(i))
            }
            for (w in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(w).rootViewNode)
            return out
        }
    }
}
