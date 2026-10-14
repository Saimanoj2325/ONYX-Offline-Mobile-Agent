package com.offlineagent.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.offlineagent.core.inference.GgufFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A GGUF that has been copied into app-internal storage. */
data class StoredModel(val file: File, val displayName: String) {
    val sizeBytes: Long get() = file.length()
}

class ModelImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Model provisioning without any network: the user picks a .gguf via the Storage Access Framework,
 * and we copy it into app-internal storage (`filesDir/models`). Internal storage is required because
 * llama.cpp mmaps by file path, and SAF URIs are not stable paths.
 */
class ModelRepository(private val context: Context) {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)

    fun current(): StoredModel? {
        val name = prefs.getString(KEY_FILE, null) ?: return null
        val f = File(modelsDir, name)
        return if (f.isFile && f.length() > 0) StoredModel(f, prefs.getString(KEY_DISPLAY, name) ?: name) else null
    }

    /**
     * Copies [uri] into internal storage, reporting progress 0..1. Cancellable (partial file deleted).
     * Only one model is kept; the previous one is deleted after a successful import.
     */
    suspend fun import(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): StoredModel =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var displayName = "model.gguf"
            var total = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { displayName = it }
                    if (!c.isNull(1)) total = c.getLong(1)
                }
            }
            if (!GgufFile.looksLikeGgufName(displayName)) {
                throw ModelImportException("\"$displayName\" is not a .gguf file. Pick a GGUF model, e.g. Qwen3-0.6B-Q8_0.gguf.")
            }
            if (total > 0) {
                val free = modelsDir.usableSpace
                val reclaimable = current()?.sizeBytes ?: 0
                if (total + SPACE_MARGIN > free + reclaimable) {
                    throw ModelImportException(
                        "Not enough storage: model needs ${formatBytes(total)}, only ${formatBytes(free + reclaimable)} free.",
                    )
                }
            }

            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val part = File(modelsDir, "$safeName.part")
            val dest = File(modelsDir, safeName)
            try {
                val input = resolver.openInputStream(uri) ?: throw ModelImportException("Could not open the selected file")
                input.use { inp ->
                    part.outputStream().buffered(BUFFER).use { out ->
                        val buf = ByteArray(BUFFER)
                        var copied = 0L
                        var lastReport = 0L
                        val header = ByteArray(8)
                        var headerFilled = 0
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            if (headerFilled < 8) {
                                val k = minOf(8 - headerFilled, n)
                                System.arraycopy(buf, 0, header, headerFilled, k)
                                headerFilled += k
                                if (headerFilled == 8 && !GgufFile.hasValidHeader(header)) {
                                    throw ModelImportException("$displayName is not a valid GGUF file (bad header).")
                                }
                            }
                            out.write(buf, 0, n)
                            copied += n
                            if (copied - lastReport >= REPORT_EVERY) {
                                lastReport = copied
                                currentCoroutineContext().ensureActive()
                                onProgress(copied, total)
                            }
                        }
                        if (headerFilled < 8) throw ModelImportException("$displayName is too small to be a model.")
                        out.flush()
                        onProgress(copied, if (total > 0) total else copied)
                    }
                }
                if (total > 0 && part.length() != total) {
                    throw ModelImportException("Copy incomplete (${formatBytes(part.length())} of ${formatBytes(total)}).")
                }
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) throw ModelImportException("Could not finalise the model file")
            } catch (t: Throwable) {
                part.delete()
                throw t
            }
            // keep only the new model
            modelsDir.listFiles()?.filter { it != dest }?.forEach { it.delete() }
            prefs.edit().putString(KEY_FILE, dest.name).putString(KEY_DISPLAY, displayName).apply()
            StoredModel(dest, displayName)
        }

    suspend fun delete() = withContext(Dispatchers.IO) {
        modelsDir.listFiles()?.forEach { it.delete() }
        prefs.edit().clear().apply()
    }

    /** Removes half-copied files left by a crash or kill during import. */
    fun cleanupPartials() {
        modelsDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    companion object {
        private const val KEY_FILE = "file"
        private const val KEY_DISPLAY = "display"
        private const val BUFFER = 1 shl 20
        private const val REPORT_EVERY = 4L shl 20
        private const val SPACE_MARGIN = 64L shl 20
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
