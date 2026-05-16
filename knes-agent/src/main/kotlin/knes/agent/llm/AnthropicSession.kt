package knes.agent.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor

/** Long-lived Anthropic/Koog client for one agent run. Kept alive across turns for prompt-cache hits. */
class AnthropicSession(apiKey: String) : AutoCloseable {
    val client: AnthropicLLMClient = AnthropicLLMClient(apiKey = apiKey)
    val executor: SingleLLMPromptExecutor = SingleLLMPromptExecutor(client)

    override fun close() {
        (client as? AutoCloseable)?.close()
    }
}
