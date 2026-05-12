package knes.agent.v2.llm

import knes.agent.llm.AnthropicSession

class HaikuClient(private val anthropic: AnthropicSession) {
    val modelId = "claude-haiku-4-5-20251001"
    // ReviewerAgent uses a placeholder LLM invocation for first cut; this
    // holder pins the model id for the future wire-up.
}
