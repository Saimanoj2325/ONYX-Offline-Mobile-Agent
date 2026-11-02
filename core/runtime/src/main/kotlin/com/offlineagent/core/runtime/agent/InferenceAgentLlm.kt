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

/**
 * [AgentLlm] over the app's [InferenceAdapter]: one non-thinking completion per call, optional GBNF grammar.
 *
 * Phase 4b: [onPartial] receives the text generated so far (thinking stripped) after every token, and [onPrompt]
 * the prompt-reading progress, so agent steps / report sections / summaries stream into their cards instead of
 * appearing only at the end. Both are called from the generation coroutine; keep them cheap (post to a StateFlow).
 */
class InferenceAgentLlm(
    private val adapter: InferenceAdapter,
    private val onToken: (Int) -> Unit = {},
    private val onPartial: ((String) -> Unit)? = null,
    private val onPrompt: ((done: Int, total: Int) -> Unit)? = null,
) : AgentLlm {
    override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String = generate(system, user, grammar, maxTokens, null)

    override suspend fun stream(system: String, user: String, grammar: String?, maxTokens: Int, onPartial: (String) -> Unit): String =
        generate(system, user, grammar, maxTokens, onPartial)

    private suspend fun generate(system: String, user: String, grammar: String?, maxTokens: Int, extra: ((String) -> Unit)?): String {
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
        ).collect { ev ->
            when (ev) {
                is GenerationEvent.Token -> {
                    sb.append(ev.text); onToken(++n)
                    if (onPartial != null || extra != null) {
                        val visible = ThinkSplitter.split(sb.toString()).answer
                        onPartial?.invoke(visible); extra?.invoke(visible)
                    }
                }
                is GenerationEvent.PromptProgress -> onPrompt?.invoke(ev.done, ev.total)
                else -> Unit
            }
        }
        return ThinkSplitter.stripThinking(sb.toString()).trim()
    }
}
