package com.offlineagent.app.ui.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlineagent.core.data.CanvasVersionEntity
import com.offlineagent.core.runtime.ui.UiDoc
import com.offlineagent.core.runtime.ui.UiMarkdown
import java.text.DateFormat
import java.util.Date

/**
 * Full-screen Canvas (artifact panel), one per chat. Shows the current version; follow-up messages in the chat update it
 * in place (new version). Undo/redo move between versions; the history menu jumps to any version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasPanel(
    doc: UiDoc?,
    streamingDoc: UiDoc?,
    streamingLabel: String?,
    versions: List<CanvasVersionEntity>,
    currentVersion: Int,
    actions: UiActions,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelectVersion: (Int) -> Unit,
    onShare: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val shown = streamingDoc ?: doc
    val maxV = versions.maxOfOrNull { it.version } ?: 0
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text(shown?.title?.ifBlank { "Canvas" } ?: "Canvas", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (streamingLabel != null) streamingLabel else if (maxV > 0) "Version $currentVersion of $maxV" else "Empty",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat") } },
                actions = {
                    IconButton(onClick = onUndo, enabled = streamingLabel == null && currentVersion > 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Undo (previous version)") }
                    IconButton(onClick = onRedo, enabled = streamingLabel == null && currentVersion < maxV) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Redo (next version)") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (doc != null) {
                            DropdownMenuItem(text = { Text("Copy as Markdown") }, onClick = { clipboard.setText(AnnotatedString(UiMarkdown.render(doc))); menu = false })
                            DropdownMenuItem(text = { Text("Share as text…") }, onClick = { onShare(UiMarkdown.render(doc)); menu = false })
                            HorizontalDivider()
                        }
                        versions.sortedByDescending { it.version }.forEach { v ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text((if (v.version == currentVersion) "● " else "") + "v${v.version} · ${v.note}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(v.createdAt)), style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                onClick = { onSelectVersion(v.version); menu = false },
                            )
                        }
                    }
                },
            )
            if (streamingLabel != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (shown == null) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (streamingLabel != null) "Building the canvas… finished components appear here as they are generated."
                        else "No canvas yet. Ask for a table, chart, plan, checklist, quiz or comparison.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(shown.blocks, key = { i, b -> "$currentVersion-$i-${b.type}" }) { i, b ->
                        UiBlockView(b, actions, key = "$currentVersion-$i")
                    }
                }
            }
        }
    }
}

/** Compact inline preview of a canvas inside the chat. */
@Composable
fun CanvasPreviewCard(doc: UiDoc, version: Int?, onOpen: () -> Unit, actions: UiActions) {
    androidx.compose.material3.OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("▦ ", color = MaterialTheme.colorScheme.primary)
                Text(doc.title.ifBlank { "Canvas" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (version != null) Text("v$version", style = MaterialTheme.typography.labelSmall)
            }
            // first component as a live preview (cheap), the rest summarised
            doc.blocks.firstOrNull()?.let { UiBlockView(it, actions, key = "preview-$version") }
            if (doc.blocks.size > 1) Text(
                "+ " + doc.blocks.drop(1).joinToString(" · ") { it.type } + "  — tap to open",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
