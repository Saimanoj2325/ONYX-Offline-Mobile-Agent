package com.offlineagent.core.models

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Fake Hugging Face: /resolve → 302 → /cdn (Range support), HEAD with X-Linked-Size/Etag, flaky + broken modes. */
class FakeHf(val payload: ByteArray) {
    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val gets = AtomicInteger()
    val ranges = mutableListOf<String?>()
    /** Close the connection after this many body bytes (once per value set), to simulate a dropped download. */
    @Volatile var dropAfter: Long = -1
    @Volatile var ignoreRange = false
    @Volatile var advertiseSha: String? = sha(payload)
    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/org/model/resolve/main/") { ex ->
            val file = ex.requestURI.path.substringAfterLast('/')
            if (file == "missing.gguf") { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            ex.responseHeaders.add("Location", "/cdn/$file?sig=abc")
            ex.responseHeaders.add("X-Linked-Size", payload.size.toString())
            advertiseSha?.let { ex.responseHeaders.add("X-Linked-Etag", "\"$it\"") }
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/cdn/") { ex -> serve(ex) }
        server.createContext("/evil") { ex -> ex.responseHeaders.add("Location", "https://evil.example.com/x.gguf"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.start()
    }

    private fun serve(ex: HttpExchange) {
        if (ex.requestMethod == "HEAD") { ex.responseHeaders.add("Content-Length", payload.size.toString()); ex.sendResponseHeaders(200, -1); ex.close(); return }
        gets.incrementAndGet()
        val range = ex.requestHeaders.getFirst("Range")
        synchronized(ranges) { ranges += range }
        var start = 0
        if (range != null && !ignoreRange) {
            start = range.removePrefix("bytes=").substringBefore('-').toInt()
            if (start >= payload.size) {
                ex.responseHeaders.add("Content-Range", "bytes */${payload.size}")
                ex.sendResponseHeaders(416, -1); ex.close(); return
            }
            ex.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
            ex.sendResponseHeaders(206, (payload.size - start).toLong())
        } else ex.sendResponseHeaders(200, payload.size.toLong())
        val out = ex.responseBody
        val drop = dropAfter
        try {
            if (drop in 1 until (payload.size - start)) {
                dropAfter = -1
                out.write(payload, start, drop.toInt()); out.flush()
                ex.close() // abrupt end before Content-Length is reached
                return
            }
            out.write(payload, start, payload.size - start)
        } catch (_: Exception) { }
        ex.close()
    }

    fun stop() = server.stop(0)

    companion object {
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        fun gguf(size: Int): ByteArray = ByteArray(size) { i -> ((i * 31 + 7) % 251).toByte() }.also {
            "GGUF".toByteArray().copyInto(it); it[4] = 3; it[5] = 0; it[6] = 0; it[7] = 0
        }
    }
}

class DownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val payload = FakeHf.gguf(3_000_000)
    private lateinit var hf: FakeHf
    private val dl get() = Downloader(UrlPolicy.ANY, retryDelayMs = { 10 })

    @Before fun up() { hf = FakeHf(payload) }
    @After fun down() { hf.stop() }

    private fun url(file: String = "model.gguf") = "${hf.base}/org/model/resolve/main/$file"

    @Test fun headReadsLinkedSizeAndSha() {
        val m = dl.head(url())
        assertEquals(payload.size.toLong(), m.size)
        assertEquals(FakeHf.sha(payload), m.sha256)
    }

    @Test fun followsRedirectAndVerifies() {
        val dest = File(tmp.root, "m.gguf")
        val progress = mutableListOf<DownloadProgress>()
        dl.download(url(), dest, dl.head(url()), onProgress = { progress += it })
        assertArrayEquals(payload, dest.readBytes())
        assertFalse(File(dest.path + ".part").exists())
        assertEquals(payload.size.toLong(), progress.last().downloaded)
        assertEquals(1f, progress.last().fraction)
    }

    @Test fun resumesAfterDroppedConnectionWithRange() {
        hf.dropAfter = 1_000_000
        val dest = File(tmp.root, "m.gguf")
        dl.download(url(), dest, dl.head(url()))
        assertArrayEquals(payload, dest.readBytes())
        assertEquals(2, hf.gets.get())
        assertNull(hf.ranges[0])
        assertEquals("bytes=1000000-", hf.ranges[1])
    }

    @Test fun resumesExistingPartFile() {
        val dest = File(tmp.root, "m.gguf")
        File(dest.path + ".part").writeBytes(payload.copyOfRange(0, 1234567))
        dl.download(url(), dest, dl.head(url()))
        assertArrayEquals(payload, dest.readBytes())
        assertEquals("bytes=1234567-", hf.ranges.single())
    }

    @Test fun serverIgnoringRangeRestartsCleanly() {
        hf.ignoreRange = true
        val dest = File(tmp.root, "m.gguf")
        File(dest.path + ".part").writeBytes(ByteArray(5000) { 1 }) // garbage prefix must be discarded on 200
        dl.download(url(), dest, dl.head(url()))
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test fun completePartFileGets416AndFinishes() {
        val dest = File(tmp.root, "m.gguf")
        File(dest.path + ".part").writeBytes(payload)
        dl.download(url(), dest, RemoteMeta(null, FakeHf.sha(payload)))
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test fun cancelKeepsPartForResume() {
        val dest = File(tmp.root, "m.gguf")
        var seen = 0L
        try {
            dl.download(url(), dest, dl.head(url()), onProgress = { seen = it.downloaded }, isCancelled = { seen > 600_000 })
            throw AssertionError("not cancelled")
        } catch (e: DownloadCancelledException) { }
        val part = File(dest.path + ".part")
        assertTrue(part.length() in 600_000 until payload.size.toLong())
        dl.download(url(), dest, dl.head(url()))
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test fun shaMismatchDeletesAndFails() {
        hf.advertiseSha = "0".repeat(64)
        val dest = File(tmp.root, "m.gguf")
        try { dl.download(url(), dest, dl.head(url())); throw AssertionError("accepted") } catch (e: DownloadException) { assertTrue(e.message!!.contains("Checksum")) }
        assertFalse(dest.exists()); assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun nonGgufIsRejected() {
        val html = "<!doctype html><html>not a model</html>".toByteArray()
        val other = FakeHf(html.copyOf(4096))
        try {
            val d = Downloader(UrlPolicy.ANY, retryDelayMs = { 10 })
            val dest = File(tmp.root, "x.gguf")
            try { d.download("${other.base}/org/model/resolve/main/x.gguf", dest, null); throw AssertionError("accepted") } catch (e: DownloadException) { assertTrue(e.message!!.contains("GGUF")) }
            assertFalse(dest.exists())
        } finally { other.stop() }
    }

    @Test fun notFoundIsAClearError() {
        try { dl.download(url("missing.gguf"), File(tmp.root, "x.gguf")); throw AssertionError() } catch (e: DownloadException) { assertTrue(e.message!!.contains("404")) }
    }

    @Test fun notEnoughSpaceFailsBeforeDownloading() {
        val d = Downloader(UrlPolicy.ANY, freeSpace = { 1_000_000 }, retryDelayMs = { 10 })
        try { d.download(url(), File(tmp.root, "m.gguf"), d.head(url())); throw AssertionError() } catch (e: DownloadException) { assertTrue(e.message!!.contains("storage")) }
        assertEquals(0, hf.gets.get())
    }

    @Test fun productionPolicyBlocksNonHuggingFaceHostsAndPlainHttp() {
        val d = Downloader(UrlPolicy.HUGGING_FACE)
        try { d.download(url(), File(tmp.root, "m.gguf")); throw AssertionError() } catch (e: DownloadException) { assertTrue(e.message!!.contains("Blocked")) }
        assertTrue(UrlPolicy.HUGGING_FACE.allowed(URL("https://huggingface.co/a/b/resolve/main/c.gguf")))
        assertTrue(UrlPolicy.HUGGING_FACE.allowed(URL("https://us.aws.cdn.hf.co/xet-bridge-us/abc")))
        assertTrue(UrlPolicy.HUGGING_FACE.allowed(URL("https://cdn-lfs.huggingface.co/x")))
        assertFalse(UrlPolicy.HUGGING_FACE.allowed(URL("http://huggingface.co/a")))
        assertFalse(UrlPolicy.HUGGING_FACE.allowed(URL("https://huggingface.co.evil.com/a")))
        assertFalse(UrlPolicy.HUGGING_FACE.allowed(URL("https://evilhf.co/a")))
        // A redirect to another host is refused hop by hop.
        val strictButLocal = Downloader(UrlPolicy { it.host == "127.0.0.1" }, retryDelayMs = { 10 })
        try { strictButLocal.download("${hf.base}/evil", File(tmp.root, "e.gguf")); throw AssertionError() } catch (e: DownloadException) { assertTrue(e.message!!.contains("evil.example.com")) }
    }

    @Test fun ggufMagic() {
        assertTrue(Gguf.hasMagic(FakeHf.gguf(16)))
        assertFalse(Gguf.hasMagic("GGUF\u0000\u0000\u0000\u0000".toByteArray()))
        assertFalse(Gguf.hasMagic("PK\u0003\u0004abcd".toByteArray()))
        assertFalse(Gguf.hasMagic(ByteArray(3)))
    }
}

class CatalogTest {
    @Test fun resolveUrlsAndCatalogue() {
        assertEquals("https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf", ModelCatalog.QWEN3_17B.main.url)
        assertEquals("https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF/resolve/main/mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf", ModelCatalog.QWEN3_VL_2B.mmproj!!.url)
        assertEquals(15, ModelCatalog.all.size) // Phase 6: +7 verified chat models
        assertTrue(ModelCatalog.all.all { m -> m.files.all { it.file.endsWith(".gguf") } })
        assertEquals(2, ModelCatalog.SMOLVLM.files.size)
        assertEquals(ModelCatalog.QWEN3_17B, ModelCatalog.byId("qwen3-1.7b"))
    }

    @Test fun recommendationByRam() {
        val gb = 1024L * 1024 * 1024
        assertEquals("qwen3-0.6b", ModelCatalog.recommendChat(3 * gb + gb / 2).id)
        assertEquals("qwen3-1.7b", ModelCatalog.recommendChat((7.3 * gb).toLong()).id)
        assertEquals("qwen3-1.7b", ModelCatalog.recommendChat(11 * gb).id)
    }

    @Test fun parsePastedLinks() {
        val r = HfUrls.parse("https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/blob/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf?download=true")!!
        assertEquals("bartowski/Llama-3.2-1B-Instruct-GGUF", r.repo)
        assertEquals("main", r.revision)
        assertEquals("Llama-3.2-1B-Instruct-Q4_K_M.gguf", r.fileName)
        assertEquals("https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf", r.url)
        val sub = HfUrls.parse("https://hf.co/org/repo/resolve/abc123/sub%20dir/model q4.gguf".replace(" ", "%20"))!!
        assertEquals("sub dir/model q4.gguf", sub.path)
        assertEquals("https://huggingface.co/org/repo/resolve/abc123/sub%20dir/model%20q4.gguf", sub.url)
        assertNull(HfUrls.parse("https://huggingface.co/org/repo"))
        assertNull(HfUrls.parse("https://huggingface.co/org/repo/resolve/main/model.safetensors"))
        assertNull(HfUrls.parse("http://huggingface.co/org/repo/resolve/main/m.gguf"))
        assertNull(HfUrls.parse("https://example.com/org/repo/resolve/main/m.gguf"))
        assertNull(HfUrls.parse("not a url"))
        assertNotNull(HfUrls.parse("  https://huggingface.co/a/b/resolve/main/c.GGUF  "))
    }
}
