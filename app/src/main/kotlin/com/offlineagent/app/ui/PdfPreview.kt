package com.offlineagent.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.pdf.PdfExporter
import kotlinx.coroutines.launch

/** In-app PDF preview (PdfRenderer) with Share, Open in another app and "Save as…" (SAF). */
@Composable
fun PdfPreviewDialog(pdf: PdfExporter.ExportedPdf, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as OfflineAgentApp
    val scope = rememberCoroutineScope()
    var pages by remember { mutableStateOf<List<Bitmap>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pdf.file) { pages = runCatching { app.pdf.preview(pdf.file, maxPages = 12, width = 900) }.getOrDefault(emptyList()) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch { app.pdf.saveTo(uri, pdf.file); msg = "Saved." }
    }
    fun start(i: Intent) = try { ctx.startActivity(i) } catch (e: ActivityNotFoundException) { msg = "No app can open PDFs on this phone." }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pdf.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${pdf.pages} pages · saved to Downloads/OfflineAgent" + (if (pdf.downloadsUri == null) " (failed; use Save as…)" else ""), style = MaterialTheme.typography.bodySmall)
                msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                val p = pages
                if (p == null) CircularProgressIndicator() else LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(p) { b -> Image(b.asImageBitmap(), contentDescription = "PDF page", modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, pdf.shareUri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share PDF"))
                }) { Text("Share") }
                TextButton(onClick = { start(Intent(Intent.ACTION_VIEW).setDataAndType(pdf.shareUri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }) { Text("Open") }
                TextButton(onClick = { saveAs.launch(pdf.name) }) { Text("Save as…") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
