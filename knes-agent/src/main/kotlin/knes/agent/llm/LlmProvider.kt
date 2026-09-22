package knes.agent.llm

/**
 * Which models the agent plays with.
 *
 * Chosen from the environment rather than hardcoded, because the three roles the agent
 * needs — a quick model for scene description, a capable one for decisions, and a vision
 * model for planning — exist at every provider under different names.
 *
 * `KNES_LLM` picks explicitly; otherwise OpenAI wins when its key is present, then
 * Gemini. Each covers every role from a single key. The Anthropic pairing is the older
 * arrangement and needs an Anthropic key for chat alongside Gemini for vision.
 */
sealed interface LlmProvider {

    fun describe(): String

    /** The chat model behind the Haiku/Sonnet roles. */
    fun chat(): ChatLlm

    /** Vision for the Advisor and Cartographer — the long-horizon planning calls. */
    fun planningVision(chat: ChatLlm): VisionLlm

    /** Vision for the Executor, which may want a different (often faster) model. */
    fun executorVision(chat: ChatLlm): VisionLlm

    object OpenAi : LlmProvider {
        override fun describe() = "openai (one key covers chat and vision)"

        override fun chat(): ChatLlm = OpenAiHttp(key())

        override fun planningVision(chat: ChatLlm) = OpenAiVisionClient(chat as OpenAiHttp)

        override fun executorVision(chat: ChatLlm) = OpenAiVisionClient(
            chat as OpenAiHttp,
            modelOverride = System.getenv("OPENAI_EXECUTOR_MODEL")?.takeIf { it.isNotBlank() },
        )

        private fun key() = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: error("OPENAI_API_KEY not set")
    }

    /** Gemini for everything: one key, same as the OpenAI arrangement. */
    object Gemini : LlmProvider {
        override fun describe() = "gemini (one key covers chat and vision)"

        override fun chat(): ChatLlm = GeminiChat(key())

        override fun planningVision(chat: ChatLlm) = GeminiPro31Client(key())

        /**
         * Pro, not Flash-Lite. Flash-Lite mis-read the town viewport — it put the party
         * "near the Inn" while it stood on the centre path, walked Right off the south
         * edge, then confused Coneria Castle for Coneria Town.
         */
        override fun executorVision(chat: ChatLlm) = GeminiPro31Client(
            key(),
            modelOverride = System.getenv("GEMINI_EXECUTOR_MODEL")?.takeIf { it.isNotBlank() }
                ?: "gemini-3.1-pro-preview",
        )

        private fun key() = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: error("GEMINI_API_KEY not set")
    }

    object AnthropicAndGemini : LlmProvider {
        override fun describe() = "anthropic (chat) + gemini (vision)"

        override fun chat(): ChatLlm = AnthropicHttp(
            System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
                ?: error("ANTHROPIC_API_KEY not set"),
        )

        override fun planningVision(chat: ChatLlm) = GeminiPro31Client(geminiKey())

        /**
         * Pro, not Flash-Lite. Flash-Lite mis-read the town viewport — it put the party
         * "near the Inn" while it stood on the centre path, walked Right off the south
         * edge, then confused Coneria Castle for Coneria Town.
         */
        override fun executorVision(chat: ChatLlm) = GeminiPro31Client(
            geminiKey(),
            modelOverride = System.getenv("GEMINI_EXECUTOR_MODEL")?.takeIf { it.isNotBlank() }
                ?: "gemini-3.1-pro-preview",
        )

        private fun geminiKey() = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: error("GEMINI_API_KEY not set")
    }

    companion object {
        fun fromEnvironment(): LlmProvider = select(
            requested = System.getenv("KNES_LLM"),
            hasOpenAiKey = !System.getenv("OPENAI_API_KEY").isNullOrBlank(),
            hasGeminiKey = !System.getenv("GEMINI_API_KEY").isNullOrBlank(),
        )

        /** Split from the environment so the rule itself can be tested. */
        fun select(requested: String?, hasOpenAiKey: Boolean, hasGeminiKey: Boolean = false): LlmProvider =
            when (val name = requested?.takeIf { it.isNotBlank() }?.lowercase()?.trim()) {
                "openai" -> OpenAi
                "gemini" -> Gemini
                "anthropic", "anthropic+gemini" -> AnthropicAndGemini
                null -> when {
                    hasOpenAiKey -> OpenAi
                    hasGeminiKey -> Gemini
                    else -> AnthropicAndGemini
                }
                else -> error("KNES_LLM='$name' is not a provider; use 'openai', 'gemini' or 'anthropic+gemini'")
            }
    }
}
