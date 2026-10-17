package com.offlineagent.core.inference

import kotlinx.coroutines.flow.collect

/**
 * Post-load self-test (Phase 4, P0): one short *greedy* prompt with a known answer. Catches broken native kernels,
 * corrupted KV/cache state or a wrong template before the user sees garbage such as "oughtought…", "THTHTH…" or
 * "ações ités ações…". Takes ~1-3 s on a phone (≈ 30 prompt tokens + ≤ 12 generated).
 */
object SelfTest {
    enum class Verdict { PASS, WRONG_ANSWER, DEGENERATE }

    data class Result(val verdict: Verdict, val output: String, val reason: String, val millis: Long) {
        val ok: Boolean get() = verdict != Verdict.DEGENERATE
    }

    val REQUEST = GenerationRequest(
        messages = listOf(ChatMessage(Role.USER, "What is 2 + 3? Reply with only the number.")),
        thinking = false,
        maxNewTokens = 12,
        sampling = SamplingParams(temperature = 0f, topK = 1, topP = 1f, minP = 0f, repeatPenalty = 1f, seed = 1),
        cachePrefix = false,
    )

    suspend fun run(adapter: InferenceAdapter): Result {
        val t0 = System.currentTimeMillis()
        val sb = StringBuilder()
        adapter.generate(REQUEST).collect { ev -> if (ev is GenerationEvent.Token) sb.append(ev.text) }
        return judge(ThinkSplitter().append(sb.toString()).answer, System.currentTimeMillis() - t0)
    }

    fun judge(output: String, millis: Long = 0): Result {
        degenerateReason(output)?.let { return Result(Verdict.DEGENERATE, output, it, millis) }
        val ok = Regex("(^|\\D)5(\\D|$)").containsMatchIn(output) || output.contains("five", ignoreCase = true)
        return if (ok) Result(Verdict.PASS, output, "answered 5", millis) else Result(Verdict.WRONG_ANSWER, output, "expected 5", millis)
    }

    /** Non-null = the text looks like broken inference rather than a (possibly wrong) answer. */
    fun degenerateReason(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return "empty output"
        val letters = t.filter { it.isLetterOrDigit() }
        if (letters.isNotEmpty()) {
            val nonAscii = letters.count { it.code > 127 }
            if (t.length >= 6 && nonAscii * 10 > letters.length * 3) return "mostly non-ASCII junk"
        }
        if (t.length >= 12) {
            for (p in 1..10) {
                if (t.length < p * 3) break
                var same = 0
                for (i in p until t.length) if (t[i] == t[i - p]) same++
                if (same * 10 >= (t.length - p) * 8) return "repeats a ${p}-character pattern"
            }
            val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size >= 6 && words.toSet().size * 4 <= words.size) return "repeats the same words"
        }
        if (t.count { it == '\uFFFD' } > 2) return "invalid UTF-8"
        return null
    }
}
