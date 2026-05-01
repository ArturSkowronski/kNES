package knes.agent.advisor

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.ext.agent.reActStrategy
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import knes.agent.tools.EmulatorToolset

/**
 * Single-shot planner. Given the current observation (text + optional screenshot path),
 * returns a short numbered plan-of-attack the executor will follow until the next phase change.
 *
 * Read-only access: only `getState` and `getScreen` are exposed via a wrapper toolset
 * (the advisor must not change game state). It returns plan text; the executor consumes it.
 */
class AdvisorAgent(
    private val apiKey: String,
    private val toolset: EmulatorToolset,
    private val model: LLModel = AnthropicModels.Opus_4,   // confirmed: Opus_4 = claude-opus-4-0
) {
    private val executor = SingleLLMPromptExecutor(AnthropicLLMClient(apiKey))

    private val readOnlyTools = ReadOnlyToolset(toolset)
    private val registry = ToolRegistry { tools(readOnlyTools) }

    private val agent: AIAgent<String, String> = AIAgent(
        promptExecutor = executor,
        llmModel = model,
        toolRegistry = registry,
        strategy = reActStrategy(reasoningInterval = 1, name = "ff1_advisor"),
        systemPrompt = """
            You are the planner for an autonomous Final Fantasy (NES) agent.
            Given the current emulator state, output a short numbered plan (1–6 steps) the
            executor will follow until the next phase change. Each step must be actionable
            using the kNES tool surface (step / tap / sequence / execute_action).
            Do NOT execute the plan yourself; only describe it as text.
        """.trimIndent(),
    )

    suspend fun plan(observation: String): String = agent.run(observation)

    fun underlying(): AIAgent<String, String> = agent
}
