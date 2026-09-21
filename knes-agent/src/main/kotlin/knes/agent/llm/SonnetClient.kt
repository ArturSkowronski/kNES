package knes.agent.llm

class SonnetClient(private val http: ChatLlm) {
    /** The provider's capable model: the turn's decision rests on this. */
    val modelId: String get() = http.strongModel

    /**
     * Per-turn tool decision. Sonnet receives a scene digest (from Haiku), the
     * RAM digest, the current plan summary, and recent outcomes — then picks
     * exactly one tool to execute. Returns the raw response text (JSON object
     * with tool/args/reasoning); ExecutorAgent parses it.
     */
    suspend fun decideTool(
        systemPrompt: String,
        userText: String,
        imageB64: String? = null,
    ): String {
        return http.generate(
            model = modelId,
            systemPrompt = systemPrompt,
            userText = userText,
            imageB64 = imageB64,
            maxTokens = 400,
        ).trim()
    }
}
