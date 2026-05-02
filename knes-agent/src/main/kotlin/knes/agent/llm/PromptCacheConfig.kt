package knes.agent.llm

import ai.koog.prompt.dsl.Prompt

/**
 * Path B per Task 1.1 probe: Koog 0.5.1 / 0.6.1 does not expose Anthropic cache_control
 * breakpoints. We still get partial caching benefit from a long-lived AnthropicLLMClient
 * (fewer cold connections, plus internal client-side prompt comparison). Full cache_control
 * wiring is deferred to V2.1, where we either swap in a custom HttpClient or upgrade Koog.
 *
 * This object is intentionally a no-op so callers can write the same code under both paths
 * without conditionals scattered around.
 */
object PromptCacheConfig {
    fun cacheSystem(prompt: Prompt): Prompt = prompt
    fun cachePreamble(prompt: Prompt, preambleEndIndex: Int): Prompt = prompt
}
