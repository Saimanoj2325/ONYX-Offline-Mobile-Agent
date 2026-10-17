package com.offlineagent.core.models

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

class DownloadException(message: String, val retryable: Boolean = false, cause: Throwable? = null) : IOException(message, cause)
class DownloadCancelledException : IOException("cancelled")

/** What Hugging Face says about a file before downloading (`HEAD` on the resolve URL, redirects not followed). */
data class RemoteMeta(val size: Long?, val sha256: String?)

data class DownloadProgress(val downloaded: Long, val total: Long?, val bytesPerSecond: Double) {
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (downloaded.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

/** Which URLs may be contacted. Production: HTTPS to Hugging Face hosts only. */
fun interface UrlPolicy {
    fun allowed(url: URL): Boolean

    companion object {
        val HUGGING_FACE = UrlPolicy { u -> u.protocol == "https" && HfUrls.isAllowedHost(u.host) }
        /** Unit tests (local fake server). */
        val ANY = UrlPolicy { true }
    }
}

/**
 * Resumable model downloader (plain `HttpURLConnection`, no extra libraries):
 *  - follows redirects itself (HF → CDN/Xet storage), checking every hop against [policy] (HTTPS + HF hosts only);
 *  - resumes from `<dest>.part` with `Range: bytes=N-` (206 appends, 200 restarts, 416 = already complete);
 *  - retries dropped connections ([maxRetries]) and resumes where it stopped;
 *  - checks free space first (needs the remaining bytes + [spaceMargin]);
 *  - verifies the result: GGUF magic, exact size (from HEAD / Content-Range), and SHA-256 when Hugging Face reports
 *    it (`X-Linked-Etag` of LFS/Xet files);
 *  - cancellation keeps the `.part` file so the next attempt resumes.
 */
class Downloader(
    private val policy: UrlPolicy = UrlPolicy.HUGGING_FACE,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val userAgent: String = "OfflineAgent-ModelDownloader/1.0",
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 60_000,
    private val maxRetries: Int = 4,
    private val spaceMargin: Long = 64L shl 20,
    private val retryDelayMs: (Int) -> Long = { attempt -> 1000L * attempt },
) {
    /** `HEAD` without following redirects: HF answers 302 with `X-Linked-Size` / `X-Linked-Etag` (sha256). */
    fun head(url: String): RemoteMeta {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            check(current)
            val c = open(current, "HEAD", null)
            try {
                val code = c.responseCode
                val linkedSize = c.getHeaderField("X-Linked-Size")?.toLongOrNull()
                val linkedEtag = c.getHeaderField("X-Linked-Etag")?.trim('"', ' ')?.takeIf { SHA256.matches(it) }
                if (linkedSize != null || linkedEtag != null) return RemoteMeta(linkedSize, linkedEtag)
                if (code in 300..399) { current = redirect(current, c); return@repeat }
                if (code == 404) throw DownloadException("File not found on Hugging Face (404). Check the link.")
                if (code == 401 || code == 403) throw DownloadException("This file needs a Hugging Face login or licence acceptance ($code). Pick an open model.")
                if (code !in 200..299) throw DownloadException("Server answered $code", retryable = code >= 500)
                val len = c.getHeaderField("Content-Length")?.toLongOrNull()
                val etag = c.getHeaderField("ETag")?.removePrefix("W/")?.trim('"', ' ')?.takeIf { SHA256.matches(it) }
                return RemoteMeta(len, etag)
            } finally { c.disconnect() }
        }
        throw DownloadException("Too many redirects")
    }

    /**
     * Downloads [url] to [dest] (atomically renamed from `<dest>.part` after verification). [expected] (from [head])
     * enables size/sha checks. [onProgress] is called about every 512 KB. Throws [DownloadCancelledException] when
     * [isCancelled] turns true (the part file is kept for resuming).
     */
    fun download(
        url: String,
        dest: File,
        expected: RemoteMeta? = null,
        verifySha256: Boolean = true,
        onProgress: (DownloadProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): File {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var attempt = 0
        var total: Long? = expected?.size
        while (true) {
            if (isCancelled()) throw DownloadCancelledException()
            try {
                total = fetch(url, part, total, onProgress, isCancelled)
                break
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: DownloadException) {
                if (!e.retryable || ++attempt > maxRetries) throw e
            } catch (e: IOException) { // dropped connection, timeout: resume
                if (++attempt > maxRetries) throw DownloadException("Download failed after $maxRetries retries: ${e.message}", cause = e)
            }
            sleepCancellable(retryDelayMs(attempt), isCancelled)
        }
        verify(part, total, if (verifySha256) expected?.sha256 else null, isCancelled)
        if (dest.exists() && !dest.delete()) throw DownloadException("Could not replace ${dest.name}")
        if (!part.renameTo(dest)) throw DownloadException("Could not finalise ${dest.name}")
        return dest
    }

    /** One connection: resumes [part], returns the total size. */
    private fun fetch(url: String, part: File, knownTotal: Long?, onProgress: (DownloadProgress) -> Unit, isCancelled: () -> Boolean): Long? {
        var have = if (part.isFile) part.length() else 0L
        if (knownTotal != null) {
            if (have > knownTotal) { part.delete(); have = 0 }
            if (have == knownTotal && have > 0) return knownTotal // already complete
            val need = knownTotal - have + spaceMargin
            val free = freeSpace(part.absoluteFile.parentFile ?: part.absoluteFile)
            if (free < need) throw DownloadException("Not enough storage: need ${fmt(need)} more, ${fmt(free)} free.")
        }
        var current = URL(url)
        var redirects = 0
        while (true) {
            check(current)
            val c = open(current, "GET", if (have > 0) "bytes=$have-" else null)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    if (++redirects > MAX_REDIRECTS) throw DownloadException("Too many redirects")
                    current = redirect(current, c)
                    continue
                }
                when (code) {
                    416 -> { // Range not satisfiable: either complete already or a stale/larger part file
                        val t = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: knownTotal
                        if (t != null && have == t) return t
                        part.delete()
                        throw DownloadException("Restarting download (stale partial file)", retryable = true)
                    }
                    404 -> throw DownloadException("File not found on Hugging Face (404).")
                    401, 403 -> throw DownloadException("This file needs a Hugging Face login or licence acceptance ($code).")
                }
                if (code !in 200..299) throw DownloadException("Server answered $code", retryable = code >= 500 || code == 429)
                val append = code == 206 && have > 0
                if (!append) have = 0
                val total = when {
                    code == 206 -> c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    else -> c.getHeaderField("Content-Length")?.toLongOrNull()
                } ?: knownTotal
                if (knownTotal != null && total != null && total != knownTotal) throw DownloadException("Server size ${fmt(total)} differs from expected ${fmt(knownTotal)}")
                if (total != null && !append) {
                    val free = freeSpace(part.absoluteFile.parentFile ?: part.absoluteFile)
                    if (free < total + spaceMargin) throw DownloadException("Not enough storage: need ${fmt(total + spaceMargin)}, ${fmt(free)} free.")
                }
                copy(c.inputStream, part, append, have, total, onProgress, isCancelled)
                return total ?: part.length()
            } finally { c.disconnect() }
        }
    }

    private fun copy(input: InputStream, part: File, append: Boolean, start: Long, total: Long?, onProgress: (DownloadProgress) -> Unit, isCancelled: () -> Boolean) {
        RandomAccessFile(part, "rw").use { raf ->
            if (!append) raf.setLength(0)
            raf.seek(if (append) start else 0)
            val buf = ByteArray(BUFFER)
            var done = if (append) start else 0
            var lastReport = 0L
            val t0 = System.nanoTime()
            val base = done
            input.use { inp ->
                while (true) {
                    if (isCancelled()) throw DownloadCancelledException()
                    val n = inp.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    done += n
                    if (done - lastReport >= REPORT_EVERY) {
                        lastReport = done
                        val secs = (System.nanoTime() - t0) / 1e9
                        onProgress(DownloadProgress(done, total, if (secs > 0) (done - base) / secs else 0.0))
                    }
                }
            }
            onProgress(DownloadProgress(done, total, 0.0))
            if (total != null && done < total) throw IOException("connection closed at ${fmt(done)} of ${fmt(total)}")
        }
    }

    private fun verify(part: File, total: Long?, sha256: String?, isCancelled: () -> Boolean) {
        if (!part.isFile) throw DownloadException("Nothing was downloaded")
        if (total != null && part.length() != total) { throw DownloadException("Size mismatch: got ${fmt(part.length())}, expected ${fmt(total)}", retryable = false) }
        if (!Gguf.hasMagic(part)) { part.delete(); throw DownloadException("Not a GGUF model file (bad header). The link may point to a web page or another format.") }
        if (sha256 != null) {
            val got = sha256Of(part, isCancelled)
            if (!got.equals(sha256, ignoreCase = true)) { part.delete(); throw DownloadException("Checksum mismatch (file corrupted in transfer). Please download again.") }
        }
    }

    private fun open(u: URL, method: String, range: String?): HttpURLConnection {
        val c = u.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = method
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept-Encoding", "identity")
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    private fun redirect(from: URL, c: HttpURLConnection): URL {
        val loc = c.getHeaderField("Location") ?: throw DownloadException("Redirect without a location")
        return URI(from.toString()).resolve(URI(loc.replace(" ", "%20"))).toURL()
    }

    private fun check(u: URL) { if (!policy.allowed(u)) throw DownloadException("Blocked: ${u.host} is not a Hugging Face download host (only https://huggingface.co links are allowed).") }

    private fun sleepCancellable(ms: Long, isCancelled: () -> Boolean) {
        var left = ms
        while (left > 0) { if (isCancelled()) throw DownloadCancelledException(); Thread.sleep(minOf(left, 100)); left -= 100 }
    }

    companion object {
        const val MAX_REDIRECTS = 8
        private const val BUFFER = 256 * 1024
        private const val REPORT_EVERY = 512L * 1024
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")

        fun sha256Of(f: File, isCancelled: () -> Boolean = { false }): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered(1 shl 20).use { inp ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    if (isCancelled()) throw DownloadCancelledException()
                    val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun fmt(b: Long): String = when {
            b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
            else -> "${b / 1024} KB"
        }
    }
}

object Gguf {
    /** GGUF files start with the ASCII magic "GGUF" followed by a little-endian uint32 version (1..3). */
    fun hasMagic(f: File): Boolean = runCatching {
        f.inputStream().use { s ->
            val h = ByteArray(8)
            var r = 0
            while (r < 8) { val n = s.read(h, r, 8 - r); if (n < 0) break; r += n }
            r == 8 && hasMagic(h)
        }
    }.getOrDefault(false)

    fun hasMagic(h: ByteArray): Boolean {
        if (h.size < 8 || h[0] != 'G'.code.toByte() || h[1] != 'G'.code.toByte() || h[2] != 'U'.code.toByte() || h[3] != 'F'.code.toByte()) return false
        val v = (h[4].toInt() and 0xFF) or ((h[5].toInt() and 0xFF) shl 8) or ((h[6].toInt() and 0xFF) shl 16) or ((h[7].toInt() and 0xFF) shl 24)
        return v in 1..3
    }
}
