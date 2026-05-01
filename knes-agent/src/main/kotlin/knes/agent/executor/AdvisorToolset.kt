package knes.agent.executor

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import knes.agent.advisor.AdvisorAgent

/**
 * Wraps AdvisorAgent as a Koog ToolSet so the ExecutorAgent can call it via the
 * standard reflect-based tool registration.
 *
 * Note: Koog 0.5.1 exposes `AIAgent.asTool(...)` (via AIAgentToolKt) but that
 * path requires explicit KSerializer wiring and returns an opaque AgentToolResult.
 * The wrapper approach is simpler and returns a plain String the executor can act on
 * directly.
 */
@LLMDescription("Advisor consultation tools. Call askAdvisor with a short reason when stuck.")
class AdvisorToolset(private val advisor: AdvisorAgent) : ToolSet {
    @Tool
    @LLMDescription("Consult the planner when stuck or at a phase boundary. Provide a short reason. Returns a numbered plan.")
    suspend fun askAdvisor(reason: String): String = advisor.plan(reason)
}
