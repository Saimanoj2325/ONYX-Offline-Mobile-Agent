package com.offlineagent.app.pdf

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.offlineagent.core.data.ChatDao
import com.offlineagent.core.data.MessageEntity
import com.offlineagent.core.runtime.pdf.PdfBlock
import com.offlineagent.core.runtime.pdf.PdfContent
import com.offlineagent.core.runtime.pdf.PdfDocModel
import com.offlineagent.core.runtime.pdf.PdfImage
import com.offlineagent.core.runtime.pdf.PdfOutput
import com.offlineagent.core.runtime.pdf.PdfPort
import com.offlineagent.core.runtime.pdf.PdfWriter
import com.offlineagent.core.runtime.ui.UiParseResult
import com.offlineagent.core.runtime.ui.UiParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Renders documents with the pure-Kotlin PDF writer (same code the JVM tests and the host harness exercise) and saves
 * them to Downloads/OfflineAgent via MediaStore (no storage permission on Android 10+). A copy stays in cache/exports
 * for the in-app preview (PdfRenderer) and the share sheet (FileProvider).
 */
class PdfExporter(private val ctx: Context, private val chats: ChatDao) : PdfPort {
    private val exportDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    @Volatile var last: ExportedPdf? = null
        private set

    data class ExportedPdf(val downloadsUri: Uri?, val shareUri: Uri, val file: File, val pages: Int, val name: String)

    /** Markdown image references -> local images (attachments/photos only; never network). */
    fun imageResolver(ref: String): PdfImage? = runCatching {
        val f = if (ref.startsWith("/")) File(ref) else File(ctx.filesDir, ref.removePrefix("file://"))
        if (!f.isFile || !f.canonicalPath.startsWith(ctx.filesDir.canonicalPath)) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        val sample = generateSequence(1) { it * 2 }.first { opts.outWidth / it <= 1600 }
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        PdfImage(bytes, bmp.width, bmp.height, isJpeg = true)
    }.getOrNull()

    override suspend fun createPdf(doc: PdfDocModel, fileName: String): PdfOutput = withContext(Dispatchers.IO) {
        val (bytes, layout) = PdfWriter.render(doc.copy(date = doc.date.ifBlank { LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy")) }))
        val name = fileName.ifBlank { "document.pdf" }.let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        val local = File(exportDir, name).apply { writeBytes(bytes) }
        exportDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        val downloads = saveToDownloads(name, bytes)
        val share = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", local)
        last = ExportedPdf(downloads, share, local, layout.pages.size, name)
        PdfOutput((downloads ?: share).toString(), name, layout.pages.size, bytes.size.toLong())
    }

    private fun saveToDownloads(name: String, bytes: ByteArray): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OfflineAgent")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            uri
        } else {
            // Android 9: app-specific external Downloads folder (no permission needed).
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "OfflineAgent").apply { mkdirs() }
            File(dir, name).apply { writeBytes(bytes) }.let { Uri.fromFile(it) }
        }
    }.getOrNull()

    /** Saves to a location the user picked with the system "Create document" picker (SAF). */
    suspend fun saveTo(target: Uri, source: File) = withContext(Dispatchers.IO) {
        ctx.contentResolver.openOutputStream(target)?.use { o -> source.inputStream().use { it.copyTo(o) } }
    }

    override suspend fun canvasMarkdown(chatId: Long?): Pair<String, List<PdfBlock>>? {
        val id = chatId ?: return null
        val chat = chats.chat(id) ?: return null
        val json = chats.canvasVersion(id, chat.canvasVersion)?.docJson ?: return null
        val doc = (UiParser.parse(json) as? UiParseResult.Ok)?.doc ?: return null
        return doc.title to PdfContent.fromCanvas(doc, ::imageResolver)
    }

    /** "Export as PDF" for a whole chat: user/assistant turns as sections, canvases appended. */
    suspend fun exportChat(chatId: Long): PdfOutput {
        val chat = chats.chat(chatId) ?: throw IllegalStateException("chat not found")
        val msgs: List<MessageEntity> = chats.messages(chatId).filter { (it.role == "user" || it.role == "assistant") && it.kind != "summary" }
        val blocks = ArrayList<PdfBlock>()
        for (m in msgs) {
            if (m.text.isBlank()) continue
            blocks += PdfBlock.Heading(2, if (m.role == "user") "You" else "Assistant")
            blocks += PdfContent.fromMarkdown(m.text, ::imageResolver)
        }
        canvasMarkdown(chatId)?.let { (title, b) -> blocks += PdfBlock.Heading(1, "Canvas: $title"); blocks += b }
        return createPdf(PdfDocModel(chat.title.ifBlank { "Chat" }, blocks, subtitle = "Conversation exported from Offline Agent", tableOfContents = false),
            safeName(chat.title.ifBlank { "chat" }) + ".pdf")
    }

    suspend fun exportCanvas(chatId: Long): PdfOutput {
        val (title, blocks) = canvasMarkdown(chatId) ?: throw IllegalStateException("This chat has no canvas")
        return createPdf(PdfDocModel(title.ifBlank { "Canvas" }, blocks, titlePage = false), safeName(title.ifBlank { "canvas" }) + ".pdf")
    }

    /** Renders [pages] pages of a PDF into bitmaps for the in-app preview. */
    suspend fun preview(file: File, maxPages: Int = 30, width: Int = 1080): List<Bitmap> = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                (0 until minOf(r.pageCount, maxPages)).map { i ->
                    r.openPage(i).use { p ->
                        val h = (width.toFloat() * p.height / p.width).toInt()
                        Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { b ->
                            b.eraseColor(android.graphics.Color.WHITE)
                            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').take(60).ifBlank { "document" }
    }
}
