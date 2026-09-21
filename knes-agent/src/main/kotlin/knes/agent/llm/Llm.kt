package knes.agent.llm

/**
 * A chat model that can look at a screenshot.
 *
 * Providers name their models differently, so callers ask for a **role** rather than a
 * name: [fastModel] for scene description and short checks that happen many times a
 * turn, [strongModel] for the decision the turn actually rests on.
 */
interface ChatLlm : AutoCloseable {
    val providerName: String
    val fastModel: String
    val strongModel: String

    suspend fun generate(
        model: String,
        systemPrompt: String,
        userText: String,
        imageB64: String? = null,
        maxTokens: Int = 800,
    ): String
}

/**
 * A single-prompt vision model, the shape the planning agents use.
 *
 * No system prompt: the Advisor, Executor and Cartographer each build one long prompt
 * and hand it over with the current frame.
 */
interface VisionLlm : AutoCloseable {
    val model: String

    suspend fun generate(prompt: String, imageB64: String? = null): String
}
