package com.offlineagent.core.inference

/** Renders a conversation into the exact prompt string a model was trained on. */
interface PromptTemplate {
    val name: String
    fun render(messages: List<ChatMessage>, thinking: Boolean): String
}

/**
 * Qwen3 chat template (ChatML), mirroring the official Jinja template for plain chat (no tools):
 *
 * ```
 * <|im_start|>system\n{system}<|im_end|>\n
 * <|im_start|>user\n{user}<|im_end|>\n
 * <|im_start|>assistant\n{answer only — past reasoning is dropped}<|im_end|>\n
 * <|im_start|>assistant\n
 * ```
 *
 * Thinking off ("hard switch", same as `enable_thinking=false`): the generation prompt is prefilled
 * with an empty `<think>\n\n</think>\n\n` block, and the last user turn gets the documented
 * `/no_think` soft switch, so the model answers directly.
 */
object Qwen3ChatTemplate : PromptTemplate {
    override val name = "qwen3-chatml"

    const val IM_START = "<|im_start|>"
    const val IM_END = "<|im_end|>"
    const val NO_THINK = "/no_think"
    const val EMPTY_THINK = "<think>\n\n</think>\n\n"

    override fun render(messages: List<ChatMessage>, thinking: Boolean): String {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        require(messages.last().role == Role.USER) { "last message must be from the user" }
        val lastUser = messages.indexOfLast { it.role == Role.USER }
        val sb = StringBuilder()
        messages.forEachIndexed { i, m ->
            when (m.role) {
                Role.SYSTEM -> {
                    require(i == 0) { "system message must be first" }
                    sb.append(IM_START).append("system\n").append(m.content.trim()).append(IM_END).append('\n')
                }
                Role.USER -> {
                    var text = m.content.trim()
                    if (!thinking && i == lastUser && !text.endsWith(NO_THINK)) text = "$text $NO_THINK"
                    sb.append(IM_START).append("user\n").append(text).append(IM_END).append('\n')
                }
                Role.ASSISTANT -> {
                    sb.append(IM_START).append("assistant\n")
                        .append(ThinkSplitter.stripThinking(m.content).trim())
                        .append(IM_END).append('\n')
                }
            }
        }
        sb.append(IM_START).append("assistant\n")
        if (!thinking) sb.append(EMPTY_THINK)
        return sb.toString()
    }

    /** True when a GGUF's embedded chat template is ChatML-style (Qwen family and many others). */
    fun matches(chatTemplate: String?): Boolean = chatTemplate?.contains(IM_START) == true
}

object PromptTemplates {
    /** Phase 1 ships only ChatML/Qwen3; other families get it as a best-effort fallback. */
    fun forModel(info: ModelInfo?): PromptTemplate = Qwen3ChatTemplate
}
