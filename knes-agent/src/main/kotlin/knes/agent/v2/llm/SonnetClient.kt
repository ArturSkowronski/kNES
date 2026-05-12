package knes.agent.v2.llm

import knes.agent.llm.AnthropicSession

class SonnetClient(private val anthropic: AnthropicSession) {
    val modelId = "claude-sonnet-4-6"
    // Real Koog tool-calling integration arrives in a follow-up task. For first
    // cut ExecutorAgent uses plan-hint dispatch only; this holder reserves the
    // model id so TurnLog records correctly attribute decisions.
}
