package knes.agent.v2.llm

import knes.agent.llm.AnthropicSession

class SonnetClient(private val anthropic: AnthropicSession) {
    val modelId = "claude-sonnet-4-6"
    /**
     * Thin call site. The real agent loop in ExecutorAgent will use Koog's
     * tool calling on this model. For simple ask/answer fall through to
     * anthropic.chat() (whatever the v1 API exposes) — wired in Task C3.
     */
}
