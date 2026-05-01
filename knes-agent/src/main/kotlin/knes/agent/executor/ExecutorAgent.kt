package knes.agent.executor

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.ext.agent.reActStrategy
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import knes.agent.advisor.AdvisorAgent
import knes.agent.tools.EmulatorToolset

class ExecutorAgent(
    private val apiKey: String,
    private val toolset: EmulatorToolset,
    private val advisor: AdvisorAgent,
    private val model: LLModel = AnthropicModels.Sonnet_4_5,
    private val reasoningInterval: Int = 1,
) {
    private val executor = SingleLLMPromptExecutor(AnthropicLLMClient(apiKey))

    private val advisorTool = AdvisorToolset(advisor)
    private val registry = ToolRegistry {
        tools(toolset)
        tools(advisorTool)
    }

    // Koog's AIAgent is single-use (StatefulSingleUseAIAgent). Build a fresh one per call.
    // maxIterations limits Koog's internal ReAct loop per outer turn (default 50 is too expensive).
    private fun newAgent(): AIAgent<String, String> = AIAgent(
        promptExecutor = executor,
        llmModel = model,
        toolRegistry = registry,
        strategy = reActStrategy(reasoningInterval = reasoningInterval, name = "ff1_executor"),
        systemPrompt = ff1ExecutorSystemPrompt,
        maxIterations = 10,
    )

    suspend fun run(input: String): String = try {
        newAgent().run(input)
    } catch (e: Exception) {
        // Koog's reActStrategy hits an internal iteration cap (default 50) and throws
        // AIAgentMaxNumberOfIterationsReachedException — but that class is `internal`.
        // Match by class name; let anything else propagate.
        if (e::class.simpleName == "AIAgentMaxNumberOfIterationsReachedException") {
            "ITERATION_CAP: ${e.message?.take(120)}"
        } else throw e
    }

    companion object {
        // Source of truth for the Claude Code MCP setup is docs/ff1-system-prompt.md.
        // This prompt is a smaller, agent-loop-focused variant: the broader "what is FF1"
        // context is delivered per-turn from the runtime (RAM diff + plan).
        val ff1ExecutorSystemPrompt: String = """
            You are an autonomous Final Fantasy (NES) executor. Use the kNES tools to advance
            the game toward defeating Garland (the bridge boss).

            Tool surface: load_rom / step / tap / sequence / get_state / get_screen /
            apply_profile / list_actions / execute_action / press / release / reset.

            Conventions: 60 frames = 1 second. Prefer tap/sequence over many steps.
            Set screenshot=true only when the visual context changed.

            When uncertain or stuck (no progress, unknown screen, battle starts/ends), call
            askAdvisor("...short reason..."). Otherwise, keep executing the current plan until
            the next phase boundary. Reply DONE when no further action is required this turn.
        """.trimIndent()
    }
}
