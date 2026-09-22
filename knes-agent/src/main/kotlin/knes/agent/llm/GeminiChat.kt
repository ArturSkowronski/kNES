package knes.agent.llm

/**
 * The chat roles over Gemini, so one `GEMINI_API_KEY` can run the whole agent.
 *
 * Gemini has no system-message slot in the shape this project uses, so the system
 * prompt is prepended to the user text — which is what the vision path already does.
 *
 * Exists because the provider pairing otherwise needed two keys: Gemini could see but
 * not answer the Haiku/Sonnet roles, so a missing Anthropic key blocked a run even
 * though a perfectly good vision model was configured.
 */
class GeminiChat(
    private val apiKey: String,
    override val fastModel: String =
        System.getenv("GEMINI_FAST_MODEL")?.takeIf { it.isNotBlank() } ?: "gemini-3.1-flash-lite",
    override val strongModel: String =
        System.getenv("GEMINI_MODEL")?.takeIf { it.isNotBlank() } ?: "gemini-3.1-pro-preview",
) : ChatLlm {

    override val providerName: String get() = "gemini"

    private val clients = mutableMapOf<String, GeminiPro31Client>()

    override suspend fun generate(
        model: String,
        systemPrompt: String,
        userText: String,
        imageB64: String?,
        maxTokens: Int,
    ): String {
        val client = clients.getOrPut(model) { GeminiPro31Client(apiKey, modelOverride = model) }
        return client.generate("$systemPrompt\n\n$userText", imageB64)
    }

    override fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }
}
