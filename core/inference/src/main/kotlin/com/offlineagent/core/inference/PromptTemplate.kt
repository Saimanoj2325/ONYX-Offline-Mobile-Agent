package com.offlineagent.core.inference

/** Renders a conversation into the exact prompt string a model was trained on. */
interface PromptTemplate {
    val name: String
    fun render(messages: List<ChatMessage>, thinking: Boolean): String

    /**
     * The exact text the rendered prompt starts with for the leading system message (identical for every request that
     * shares this system prompt), or null if the template cannot say. Used for prompt-prefix KV caching and as the
     * part context shifting must keep.
     */
    fun systemPrefix(messages: List<ChatMessage>): String? = null
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
open class ChatMlTemplate(
    /** Qwen3-style hybrid thinking: adds /no_think + empty think block when thinking is off. */
    private val hybridThinking: Boolean,
    /** Text placed before a user turn's content for each attached image (engine media marker). */
    private val mediaMarker: String = "<__media__>",
) : PromptTemplate {
    override val name = if (hybridThinking) "qwen3-chatml" else "chatml"

    override fun systemPrefix(messages: List<ChatMessage>): String? {
        val sys = messages.firstOrNull()?.takeIf { it.role == Role.SYSTEM } ?: return null
        return IM_START + "system\n" + sys.content.trim() + IM_END + "\n"
    }

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
                    if (hybridThinking && !thinking && i == lastUser && !text.endsWith(NO_THINK)) text = "$text $NO_THINK"
                    if (m.images.isNotEmpty()) text = mediaMarker.repeat(m.images.size) + "\n" + text
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
        if (hybridThinking && !thinking) sb.append(EMPTY_THINK)
        return sb.toString()
    }

    companion object {
        const val IM_START = "<|im_start|>"
        const val IM_END = "<|im_end|>"
        const val NO_THINK = "/no_think"
        const val EMPTY_THINK = "<think>\n\n</think>\n\n"

        /** True when a GGUF's embedded chat template is ChatML (Qwen family and many others). */
        fun matches(chatTemplate: String?): Boolean =
            chatTemplate != null && chatTemplate.contains(IM_START) && chatTemplate.contains(IM_END)

        /** Qwen3 hybrid-thinking models mention <think> in their template; Qwen2.5 / Qwen-VL instruct models do not. */
        fun hasHybridThinking(chatTemplate: String?): Boolean = chatTemplate == null || chatTemplate.contains("<think>")
    }
}

/** The Phase 1 default (Qwen3 hybrid thinking, ChatML). */
object Qwen3ChatTemplate : ChatMlTemplate(hybridThinking = true) {
    const val IM_START = ChatMlTemplate.IM_START
    const val IM_END = ChatMlTemplate.IM_END
    const val NO_THINK = ChatMlTemplate.NO_THINK
    const val EMPTY_THINK = ChatMlTemplate.EMPTY_THINK
    fun matches(chatTemplate: String?): Boolean = ChatMlTemplate.matches(chatTemplate)
}

/** Uses the engine's built-in template matcher (llama.cpp llama_chat_apply_template) for non-ChatML models. */
class EngineChatTemplate(private val engine: TokenEngine) : PromptTemplate {
    override val name = "engine-builtin"
    override fun render(messages: List<ChatMessage>, thinking: Boolean): String {
        val withMarkers = messages.map { m ->
            if (m.images.isEmpty()) m else m.copy(content = engine.mediaMarker.repeat(m.images.size) + "\n" + m.content)
        }
        return engine.formatChat(withMarkers) ?: ChatMlTemplate(false, engine.mediaMarker).render(messages, thinking)
    }
}

object PromptTemplates {
    /** ChatML models (Qwen etc.) use the Kotlin template; others use llama.cpp's built-in templates. */
    fun forModel(info: ModelInfo?, engine: TokenEngine? = null): PromptTemplate {
        val t = info?.chatTemplate
        return when {
            t == null -> Qwen3ChatTemplate
            ChatMlTemplate.matches(t) && ChatMlTemplate.hasHybridThinking(t) -> Qwen3ChatTemplate
            ChatMlTemplate.matches(t) -> ChatMlTemplate(hybridThinking = false, mediaMarker = engine?.mediaMarker ?: "<__media__>")
            engine != null -> EngineChatTemplate(engine)
            else -> Qwen3ChatTemplate
        }
    }
}
