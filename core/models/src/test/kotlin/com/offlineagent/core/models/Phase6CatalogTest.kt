package com.offlineagent.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase6CatalogTest {
    private val gb = 1024L * 1024 * 1024

    @Test fun storageCheckKeepsReserve() {
        val m = ModelCatalog.QWEN3_17B // 1.03 GiB
        assertTrue(StorageCheck.canDownload(m, freeBytes = 4 * gb).ok)
        val r = StorageCheck.canDownload(m, freeBytes = 2 * gb) // would leave < 1.5 GB
        assertFalse(r.ok)
        assertTrue(r.message.startsWith("Not enough storage"))
    }

    @Test fun storageCheckCountsResumedPartialDownload() {
        val m = ModelCatalog.QWEN3_17B
        assertFalse(StorageCheck.canDownload(m, freeBytes = 2 * gb).ok)
        assertTrue(StorageCheck.canDownload(m, freeBytes = 2 * gb, alreadyDownloaded = 1 * gb).ok)
    }

    @Test fun newEntriesHaveExactVerifiedSizesAndRepos() {
        assertEquals(507_154_688L, ModelCatalog.QWEN35_08B.main.approxBytes)
        assertEquals(695_751_488L, ModelCatalog.LFM25_12B.main.approxBytes)
        assertEquals(1_214_873_856L, ModelCatalog.QWEN35_2B.main.approxBytes)
        assertEquals(2_375_773_280L, ModelCatalog.QWEN3_4B_2507.main.approxBytes)
        assertEquals(2_583_221_408L, ModelCatalog.QWEN35_4B.main.approxBytes)
        assertEquals(2_841_481_184L, ModelCatalog.GEMMA4_E2B.main.approxBytes)
        assertEquals(4_844_678_368L, ModelCatalog.LFM25_8B_A1B.main.approxBytes)
        assertEquals("https://huggingface.co/LiquidAI/LFM2.5-8B-A1B-GGUF/resolve/main/LFM2.5-8B-A1B-Q4_0.gguf", ModelCatalog.LFM25_8B_A1B.main.url)
        assertTrue(ModelCatalog.all.map { it.id }.toSet().size == ModelCatalog.all.size)
        assertTrue(ModelCatalog.LFM25_8B_A1B.moe && ModelCatalog.LFM25_8B_A1B.hybrid)
    }

    @Test fun defaultModelFitsEightGbBudget() {
        val r = ModelFit.residentMb(ModelCatalog.QWEN3_17B, 8192)
        assertTrue("$r MB", r <= ModelFit.DEFAULT_BUDGET_MB)
        // hybrid models have a much smaller KV cache at the same context
        assertTrue(ModelFit.kvMb(ModelCatalog.QWEN35_2B, 8192) * 5 < ModelFit.kvMb(ModelCatalog.QWEN3_17B, 8192))
    }
}
