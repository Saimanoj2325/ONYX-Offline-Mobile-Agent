package com.offlineagent.core.apps

/**
 * Inbound connection: other apps hand content to ONYX (share sheet ACTION_SEND/SEND_MULTIPLE, the text-selection
 * menu ACTION_PROCESS_TEXT "Ask ONYX", the Quick Settings tile, the assistant gesture). The user always confirms in the
 * Quick-ask sheet; the content is wrapped as untrusted data before it becomes part of the chat/agent prompt.
 */
enum class InboundKind { SHARE, PROCESS_TEXT, QUICK_ASK, ASSIST }

data class InboundFile(val name: String, val mime: String, val sizeBytes: Long, /** Local copy in ONYX's cache (file path), if copied. */ val localPath: String?, val textContent: String? = null)

data class InboundContent(
    val kind: InboundKind,
    val text: String = "",
    val subject: String = "",
    val files: List<InboundFile> = emptyList(),
    /** Package of the app that sent it (referrer), when Android tells us. */
    val sourcePkg: String? = null,
    val sourceLabel: String? = null,
) {
    val isEmpty: Boolean get() = text.isBlank() && files.isEmpty()
    val images: List<InboundFile> get() = files.filter { it.mime.startsWith("image/") && it.localPath != null }
}

/** Ready-to-send chat request built from the user's instruction + the inbound content. */
data class InboundRequest(val prompt: String, val imagePaths: List<String>, val kind: InboundKind, val suspicious: Boolean)

object InboundPrompts {
    /** Suggestion chips shown in the Quick-ask sheet. */
    fun suggestions(c: InboundContent): List<String> = when {
        c.kind == InboundKind.QUICK_ASK || c.kind == InboundKind.ASSIST -> emptyList()
        c.images.isNotEmpty() && c.text.isBlank() -> listOf("Describe this", "Extract the text", "What should I do with this?")
        c.text.length in 1..120 && c.files.isEmpty() -> listOf("Explain", "Translate to English", "Reply ideas", "Search this")
        c.files.isNotEmpty() && c.text.isBlank() -> listOf("Summarise", "Key points", "What is this?")
        else -> listOf("Summarise", "Explain", "Key points", "Translate to English", "Reply ideas")
    }

    const val MAX_TEXT = 6000
    const val MAX_FILE_TEXT = 6000

    fun build(instruction: String, c: InboundContent): InboundRequest {
        val ask = instruction.trim().ifBlank { if (c.isEmpty) "" else "Summarise this" }
        val src = c.sourceLabel ?: c.sourcePkg
        val how = when (c.kind) {
            InboundKind.SHARE -> "shared from ${src ?: "another app"}"
            InboundKind.PROCESS_TEXT -> "text selected in ${src ?: "another app"}"
            InboundKind.ASSIST -> "from the assistant gesture"
            InboundKind.QUICK_ASK -> "quick ask"
        }
        var suspicious = false
        val parts = mutableListOf<String>()
        if (c.text.isNotBlank() || c.subject.isNotBlank()) {
            val body = listOf(c.subject.takeIf { it.isNotBlank() }?.let { "Subject: $it" }, c.text.takeIf { it.isNotBlank() }).filterNotNull().joinToString("\n")
            val w = UntrustedContent.wrap(how, body, MAX_TEXT); suspicious = suspicious || w.suspicious; parts += w.text
        }
        for (f in c.files) {
            val line = "File: ${UntrustedContent.sanitize(f.name).take(120)} (${f.mime}${if (f.sizeBytes >= 0) ", ${f.sizeBytes / 1024} KB" else ""})"
            if (f.textContent != null) {
                val w = UntrustedContent.wrap("file ${f.name} $how", f.textContent, MAX_FILE_TEXT); suspicious = suspicious || w.suspicious
                parts += "$line\n${w.text}"
            } else parts += line + if (f.mime.startsWith("image/")) " — attached image" else " — ONYX cannot read this file type yet"
        }
        val prompt = if (parts.isEmpty()) ask else buildString {
            append(ask)
            append("\n\n")
            append(UntrustedContent.NOTE)
            append("\n")
            append(parts.joinToString("\n\n"))
        }
        return InboundRequest(prompt.trim(), c.images.mapNotNull { it.localPath }.take(2), c.kind, suspicious)
    }
}
