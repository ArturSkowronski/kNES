package knes.agent.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor

/**
 * Long-lived Anthropic client + Koog single-LLM executor for one agent run.
 *
 * V1 built a fresh AnthropicLLMClient per turn (defeating prompt caching). V2 keeps one
 * instance for the lifetime of an AgentSession so static prefixes (system prompt, tool
 * descriptions) hit the cache across turns. See spec §6.
 *
 * Cache markers are configured per-prompt in PromptCacheConfig (Task 1.4) — this class
 * just owns the connection.
 */
class AnthropicSession(apiKey: String) : AutoCloseable {
    val client: AnthropicLLMClient = AnthropicLLMClient(apiKey = apiKey)
    val executor: SingleLLMPromptExecutor = SingleLLMPromptExecutor(client)

    override fun close() {
        // Koog uses Ktor's CIO under the hood. Closing the client releases its coroutine
        // resources. Required because long-lived sessions must clean up on JVM exit.
        // If AnthropicLLMClient does not implement Closeable in 0.5.1, this is a no-op
        // and the GC will reclaim resources.
        (client as? AutoCloseable)?.close()
    }
}
