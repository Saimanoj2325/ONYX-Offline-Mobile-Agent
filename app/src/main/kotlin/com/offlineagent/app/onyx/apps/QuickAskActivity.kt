package com.offlineagent.app.onyx.apps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.offlineagent.app.MainActivity
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.onyx.VoiceInput
import com.offlineagent.core.apps.InboundContent
import com.offlineagent.core.apps.InboundFile
import com.offlineagent.core.apps.InboundKind
import com.offlineagent.core.apps.InboundPrompts
import com.offlineagent.core.apps.InboundRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Hand-off from the Quick-ask sheet to MainActivity (in memory; consumed once). */
object AppBridgeInbox {
    @Volatile private var pending: InboundRequest? = null
    const val OPEN = "onyx-inbound"
    fun put(r: InboundRequest) { pending = r }
    fun take(): InboundRequest? = pending.also { pending = null }
}

/**
 * Inbound entry points (all user-initiated, Play-safe):
 *  - ACTION_SEND / SEND_MULTIPLE (share sheet → "ONYX")
 *  - ACTION_PROCESS_TEXT ("Ask ONYX" in every app's text-selection menu)
 *  - ACTION_ASSIST (assistant gesture when ONYX is the default digital assistant) and the Quick Settings tile
 * Shows a small sheet with the content preview, an instruction field (prefilled chips, mic) and Send. Send opens a new
 * ONYX chat with the request (Agents mode decides chat vs multi-agent task). Nothing is sent before the user taps Send.
 */
class QuickAskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = kindOf(intent)
        val source = referrer?.takeIf { it.scheme == "android-app" }?.host ?: callingPackage
        val voice = VoiceInput(this)
        setContent {
            val prefs by (application as OfflineAgentApp).onyx.prefs.values.collectAsState()
            com.offlineagent.app.ui.theme.OnyxTheme(prefs.theme, prefs.dynamicColor) {
                var content by remember { mutableStateOf<InboundContent?>(null) }
                androidx.compose.runtime.LaunchedEffect(Unit) { content = withContext(Dispatchers.IO) { readContent(this@QuickAskActivity, intent, kind, source) } }
                content?.let { c -> QuickAskSheet(c, voice, onCancel = { finish() }, onSend = { instruction -> submit(instruction, c) }) }
            }
        }
    }

    private fun submit(instruction: String, c: InboundContent) {
        val r = InboundPrompts.build(instruction, c)
        if (r.prompt.isBlank()) { finish(); return }
        AppBridgeInbox.put(r)
        lifecycleScope.launch {
            runCatching { (application as OfflineAgentApp).onyx.audit("inbound", "AppBridge", "inbound ${c.kind.name.lowercase()}", "from ${c.sourcePkg ?: "?"}: ${c.text.length} chars, ${c.files.size} files${if (r.suspicious) ", instruction-like text flagged" else ""}", com.offlineagent.core.agents.Risk.NONE) }
        }
        startActivity(Intent(this, MainActivity::class.java).putExtra("open", AppBridgeInbox.OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        const val ACTION_QUICK_ASK = "com.offlineagent.action.QUICK_ASK"

        fun kindOf(i: Intent?): InboundKind = when (i?.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> InboundKind.SHARE
            Intent.ACTION_PROCESS_TEXT -> InboundKind.PROCESS_TEXT
            Intent.ACTION_ASSIST -> InboundKind.ASSIST
            else -> InboundKind.QUICK_ASK
        }

        private const val MAX_COPY = 20L * 1024 * 1024
        private const val MAX_TEXT_FILE = 64 * 1024

        @Suppress("DEPRECATION")
        fun readContent(ctx: Context, i: Intent, kind: InboundKind, sourcePkg: String?): InboundContent {
            val label = sourcePkg?.let { p -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() }
            return when (kind) {
                InboundKind.PROCESS_TEXT -> InboundContent(kind, text = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(), sourcePkg = sourcePkg, sourceLabel = label)
                InboundKind.SHARE -> {
                    val uris: List<Uri> = if (i.action == Intent.ACTION_SEND_MULTIPLE) i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                        else listOfNotNull(i.getParcelableExtra(Intent.EXTRA_STREAM))
                    InboundContent(kind, text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(), subject = i.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(),
                        files = uris.take(5).mapNotNull { copy(ctx, it, i.type) }, sourcePkg = sourcePkg, sourceLabel = label)
                }
                else -> InboundContent(kind, sourcePkg = sourcePkg, sourceLabel = label)
            }
        }

        /** Copies a shared stream into cache/inbound (the grant only lasts while this activity lives). */
        private fun copy(ctx: Context, uri: Uri, fallbackType: String?): InboundFile? = runCatching {
            if (uri.scheme != "content") return@runCatching null // never read file:// paths handed in by other apps
            val cr = ctx.contentResolver
            val mime = cr.getType(uri) ?: fallbackType ?: "application/octet-stream"
            var name = "shared"; var size = -1L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
            }
            if (size > MAX_COPY) return@runCatching InboundFile(name, mime, size, null)
            val dir = File(ctx.cacheDir, "inbound").apply { mkdirs() }
            val out = File(dir, UUID.randomUUID().toString().take(8) + "-" + name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60))
            cr.openInputStream(uri)?.use { inp -> out.outputStream().use { o -> inp.copyTo(o) } } ?: return@runCatching null
            val text = if (mime.startsWith("text/") || name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv"))
                out.inputStream().use { s -> String(s.readNBytesCompat(MAX_TEXT_FILE), Charsets.UTF_8) } else null
            InboundFile(name, mime, if (size >= 0) size else out.length(), out.absolutePath, text)
        }.getOrNull()

        private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
            val buf = ByteArray(n); var off = 0
            while (off < n) { val r = read(buf, off, n - off); if (r < 0) break; off += r }
            return buf.copyOf(off)
        }
    }
}

@Composable
private fun QuickAskSheet(c: InboundContent, voice: VoiceInput, onCancel: () -> Unit, onSend: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    val vs by voice.state.collectAsState()
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) voice.start { text = it } }
    val title = when (c.kind) {
        InboundKind.SHARE -> "Shared with ONYX" + (c.sourceLabel?.let { " from $it" } ?: "")
        InboundKind.PROCESS_TEXT -> "Ask ONYX about this text"
        else -> "Ask ONYX"
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!c.isEmpty) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(10.dp).heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                        if (c.text.isNotBlank()) Text(c.text.take(1200), style = MaterialTheme.typography.bodySmall)
                        c.files.forEach { Text("📎 ${it.name} (${it.mime})", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                val chips = InboundPrompts.suggestions(c)
                if (chips.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { s -> AssistChip(onClick = { onSend(s) }, label = { Text(s) }) }
                }
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                    placeholder = { Text(if (c.isEmpty) "What do you need?" else "What should ONYX do with it?") })
                when (val s = vs) {
                    is VoiceInput.State.Listening -> Text("Listening… ${s.partial}", style = MaterialTheme.typography.labelSmall)
                    is VoiceInput.State.Error -> Text(s.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    else -> if (voice.available && !voice.offlineGuaranteed) Text("Voice: offline speech not guaranteed on this phone", style = MaterialTheme.typography.labelSmall)
                }
                Text("Content from other apps is treated as data, never as instructions.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank() || !c.isEmpty) { Text("Send") } },
        dismissButton = {
            Row {
                if (voice.available) TextButton(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.start { text = it }
                    else mic.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(if (vs is VoiceInput.State.Listening) "Listening…" else "🎤 Speak") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

/** Quick Settings tile → Quick-ask sheet (voice or text). */
class QuickAskTileService : android.service.quicksettings.TileService() {
    override fun onStartListening() { qsTile?.apply { state = android.service.quicksettings.Tile.STATE_INACTIVE; updateTile() } }

    @Suppress("DEPRECATION")
    override fun onClick() {
        val i = Intent(this, QuickAskActivity::class.java).setAction(QuickAskActivity.ACTION_QUICK_ASK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 7, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        } else startActivityAndCollapse(i)
    }
}
