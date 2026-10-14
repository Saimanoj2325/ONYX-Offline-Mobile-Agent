package com.offlineagent.core.runtime.agent

import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationRequest
import com.offlineagent.core.inference.InferenceAdapter
import com.offlineagent.core.inference.Role
import com.offlineagent.core.inference.SamplingParams
import com.offlineagent.core.inference.ThinkSplitter

/** Sampling for structured (grammar-constrained) output: low temperature, NO repeat penalty (JSON repeats braces/quotes). */
val STRUCTURED_SAMPLING = SamplingParams(temperature = 0.3f, topK = 20, topP = 0.9f, minP = 0.05f, repeatPenalty = 1.0f)

/** [AgentLlm] over the app's [InferenceAdapter]: one non-thinking completion per call, optional GBNF grammar. */
class InferenceAgentLlm(
    private val adapter: InferenceAdapter,
    private val onToken: (Int) -> Unit = {},
) : AgentLlm {
    override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
        val sb = StringBuilder()
        var n = 0
        adapter.generate(
            GenerationRequest(
                messages = listOf(ChatMessage(Role.SYSTEM, system), ChatMessage(Role.USER, user)),
                thinking = false,
                maxNewTokens = maxTokens,
                sampling = if (grammar != null) STRUCTURED_SAMPLING else SamplingParams.QWEN3_NON_THINKING,
                grammar = grammar,
            ),
        ).collect { ev -> if (ev is GenerationEvent.Token) { sb.append(ev.text); onToken(++n) } }
        return ThinkSplitter.stripThinking(sb.toString()).trim()
    }
}
