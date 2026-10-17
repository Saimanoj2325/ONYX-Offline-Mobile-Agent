package com.offlineagent.app.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.data.ModelRole
import com.offlineagent.app.diag.Diagnostics
import com.offlineagent.core.data.ModelEntity
import com.offlineagent.core.models.CatalogFile
import com.offlineagent.core.models.CatalogModel
import com.offlineagent.core.models.DownloadCancelledException
import com.offlineagent.core.models.Downloader
import com.offlineagent.core.models.HfRef
import com.offlineagent.core.models.ModelCatalog
import com.offlineagent.core.models.ModelKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/** Live state of one model download (shown in the chat's model sheet and in a notification). */
data class DownloadState(
    val id: String,
    val name: String,
    val phase: Phase,
    val downloaded: Long = 0,
    val total: Long? = null,
    val bytesPerSecond: Double = 0.0,
    val fileIndex: Int = 0,
    val fileCount: Int = 1,
    val error: String? = null,
) {
    enum class Phase { QUEUED, DOWNLOADING, VERIFYING, DONE, FAILED, CANCELLED }
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (downloaded.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    val active: Boolean get() = phase == Phase.QUEUED || phase == Phase.DOWNLOADING || phase == Phase.VERIFYING
}

/**
 * In-app model downloader (Phase 4). The ONLY network use of the offline build: HTTPS GETs to huggingface.co and its
 * download CDN ([com.offlineagent.core.models.UrlPolicy.HUGGING_FACE]). Runs as a WorkManager foreground job
 * (notification with progress + Cancel), resumes partial files, verifies GGUF header, size and SHA-256, then registers
 * the model (and the vision projector) and makes it active.
 */
class ModelDownloads(private val app: OfflineAgentApp) {
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    fun update(s: DownloadState) = _states.update { it + (s.id to s) }
    fun clear(id: String) = _states.update { it - id }

    fun dirFor(kind: ModelKind): File = File(app.legacyModels.root, when (kind) { ModelKind.CHAT -> "chat"; ModelKind.EMBEDDING -> "embedding"; ModelKind.VISION -> "vision" }).apply { mkdirs() }

    fun start(model: CatalogModel) = enqueue(model.id, model.name, model.kind, model.files)

    /** A pasted Hugging Face link (role chosen by the user). */
    fun startCustom(ref: HfRef, kind: ModelKind): String {
        val id = "custom-" + ref.url.hashCode().toUInt().toString(16)
        enqueue(id, ref.fileName.removeSuffix(".gguf"), kind, listOf(CatalogFile(ref.repo, ref.path, 0, ref.revision)))
        return id
    }

    private fun enqueue(id: String, name: String, kind: ModelKind, files: List<CatalogFile>) {
        update(DownloadState(id, name, DownloadState.Phase.QUEUED, fileCount = files.size))
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(
                "id" to id, "name" to name, "kind" to kind.name,
                "urls" to files.map { it.url }.toTypedArray(), "files" to files.map { it.file.substringAfterLast('/') }.toTypedArray(),
            ))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(work(id), ExistingWorkPolicy.KEEP, req)
        Diagnostics.log("download queued: $name (${files.size} file(s))")
    }

    /** Cancels; the partial file is kept so a new download of the same model resumes. [deletePartial] removes it. */
    fun cancel(id: String, deletePartial: Boolean = false) {
        WorkManager.getInstance(app).cancelUniqueWork(work(id))
        _states.value[id]?.let { update(it.copy(phase = DownloadState.Phase.CANCELLED)) }
        if (deletePartial) ModelKind.entries.forEach { k -> dirFor(k).listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() } }
    }

    /** Is this catalogue model already installed (all files present)? */
    suspend fun installed(model: CatalogModel): ModelEntity? = withContext(Dispatchers.IO) {
        val role = roleOf(model.kind)
        app.models.all().firstOrNull { it.role == role.name && File(it.path).name == model.main.file && File(it.path).isFile }
    }

    companion object {
        const val TAG = "model-download"
        fun work(id: String) = "download-$id"
        fun roleOf(kind: ModelKind) = when (kind) { ModelKind.CHAT -> ModelRole.CHAT; ModelKind.EMBEDDING -> ModelRole.EMBEDDING; ModelKind.VISION -> ModelRole.VISION }
        const val CHANNEL = "model_downloads"
    }
}

class ModelDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app = ctx.applicationContext as OfflineAgentApp
    private val id = inputData.getString("id") ?: "unknown"
    private val name = inputData.getString("name") ?: "model"
    private val notifId = 7100 + (id.hashCode() and 0xFF)

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(notification(null, "Starting…"))

    private fun foreground(n: android.app.Notification) =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(notifId, n)

    private fun notification(fraction: Float?, text: String): android.app.Notification {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ModelDownloads.CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(getId())
        return NotificationCompat.Builder(applicationContext, ModelDownloads.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $name")
            .setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val kind = runCatching { ModelKind.valueOf(inputData.getString("kind") ?: "CHAT") }.getOrDefault(ModelKind.CHAT)
        val urls = inputData.getStringArray("urls") ?: return@withContext Result.failure()
        val files = inputData.getStringArray("files") ?: return@withContext Result.failure()
        val dl = app.modelDownloads
        val dir = dl.dirFor(kind)
        runCatching { setForeground(foreground(notification(null, "Starting…"))) }
        val downloader = Downloader()
        val done = mutableListOf<File>()
        try {
            for ((i, url) in urls.withIndex()) {
                val dest = File(dir, files[i].replace(Regex("[^A-Za-z0-9._-]"), "_"))
                dl.update(DownloadState(id, name, DownloadState.Phase.DOWNLOADING, fileIndex = i, fileCount = urls.size))
                val meta = runCatching { downloader.head(url) }.getOrElse { e ->
                    if (e is com.offlineagent.core.models.DownloadException && !e.retryable) throw e else null
                }
                if (dest.isFile && meta?.size == dest.length() && com.offlineagent.core.models.Gguf.hasMagic(dest)) { done += dest; continue }
                var lastNotif = 0L
                downloader.download(url, dest, meta, onProgress = { p ->
                    val st = DownloadState(id, name, DownloadState.Phase.DOWNLOADING, p.downloaded, p.total ?: meta?.size, p.bytesPerSecond, i, urls.size)
                    dl.update(st)
                    val now = System.currentTimeMillis()
                    if (now - lastNotif > 1000) {
                        lastNotif = now
                        val txt = "${if (urls.size > 1) "File ${i + 1}/${urls.size} · " else ""}${Downloader.fmt(p.downloaded)}${st.total?.let { " / ${Downloader.fmt(it)}" } ?: ""}" +
                            if (p.bytesPerSecond > 0) " · ${Downloader.fmt(p.bytesPerSecond.toLong())}/s" else ""
                        runCatching { setForegroundAsync(foreground(notification(st.fraction, txt))) }
                        setProgressAsync(workDataOf("fraction" to (st.fraction ?: -1f)))
                    }
                }, isCancelled = { isStopped })
                done += dest
            }
            dl.update(DownloadState(id, name, DownloadState.Phase.VERIFYING, fileCount = urls.size))
            register(kind, done)
            dl.update(DownloadState(id, name, DownloadState.Phase.DONE, fileCount = urls.size))
            Diagnostics.log("download finished: $name")
            Result.success()
        } catch (e: DownloadCancelledException) {
            dl.update(DownloadState(id, name, DownloadState.Phase.CANCELLED))
            Result.failure()
        } catch (e: Exception) {
            if (isStopped) { dl.update(DownloadState(id, name, DownloadState.Phase.CANCELLED)); return@withContext Result.failure() }
            Diagnostics.log("download failed: $name: ${e.message}")
            dl.update(DownloadState(id, name, DownloadState.Phase.FAILED, error = e.message ?: e.toString()))
            Result.failure(workDataOf("error" to (e.message ?: "failed")))
        }
    }

    /** Adds the model to the model table (replacing an older entry for the same file) and makes it active. */
    private suspend fun register(kind: ModelKind, files: List<File>) {
        val role = ModelDownloads.roleOf(kind)
        val dao = app.db.misc()
        val main = files.first()
        val mmproj = files.getOrNull(1)
        dao.models().filter { it.path == main.absolutePath }.forEach { dao.deleteModel(it.id) }
        val newId = dao.insertModel(ModelEntity(role = role.name, name = name, path = main.absolutePath, mmprojPath = mmproj?.absolutePath,
            sizeBytes = files.sumOf { it.length() }, active = false, addedAt = System.currentTimeMillis()))
        dao.activate(role.name, newId)
    }
}
