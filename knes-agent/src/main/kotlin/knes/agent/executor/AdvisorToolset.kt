package knes.agent.executor

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import knes.agent.advisor.AdvisorAgent
import knes.agent.perception.FfPhase

@LLMDescription("Advisor consultation tool.")
class AdvisorToolset(private val advisor: AdvisorAgent) : ToolSet {
    @Tool
    @LLMDescription("Consult the planner when stuck or at a phase boundary. Provide a short reason. Returns a numbered plan.")
    suspend fun askAdvisor(reason: String): String =
        // Phase is unknown from inside the tool path; advisor itself observes via its read-only tools.
        // TitleOrMenu is the broadest assumption (Opus model, most capable).
        advisor.plan(FfPhase.TitleOrMenu, reason)
}
