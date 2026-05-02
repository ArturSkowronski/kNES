package knes.agent.executor

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import knes.agent.advisor.AdvisorAgent
import knes.agent.llm.AgentRole
import knes.agent.llm.AnthropicSession
import knes.agent.llm.ModelRouter
import knes.agent.perception.FfPhase
import knes.agent.skills.SkillRegistry
import knes.agent.tools.EmulatorToolset

class ExecutorAgent(
    private val anthropic: AnthropicSession,
    private val modelRouter: ModelRouter,
    private val toolset: EmulatorToolset,
    private val advisor: AdvisorAgent,
) {
    private val skillRegistry = SkillRegistry(toolset)
    private val advisorTool = AdvisorToolset(advisor)
    private val registry = ToolRegistry {
        tools(skillRegistry)
        tools(advisorTool)
    }

    private fun newAgent(phase: FfPhase): AIAgent<String, String> = AIAgent(
        promptExecutor = anthropic.executor,
        llmModel = modelRouter.modelFor(phase, AgentRole.EXECUTOR),
        toolRegistry = registry,
        strategy = singleRunStrategy(),
        systemPrompt = ff1ExecutorSystemPrompt,
        maxIterations = 10,   // Koog counts node executions, not LLM calls. 10 allows 1-2 tool calls + final response without runaway.
    )

    suspend fun run(phase: FfPhase, input: String): String = try {
        newAgent(phase).run(input)
    } catch (e: Exception) {
        // singleRunStrategy + maxIterations=2 should rarely cap, but if the model keeps
        // calling tools the cap will fire. Treat as a normal turn outcome; outer loop
        // observes RAM and decides next steps.
        if (e::class.simpleName == "AIAgentMaxNumberOfIterationsReachedException") {
            "ITERATION_CAP: ${e.message?.take(120)?.trim()}"
        } else throw e
    }

    companion object {
        val ff1ExecutorSystemPrompt: String = """
            You are an autonomous Final Fantasy (NES) executor.

            BEHAVIOR: Each time you are invoked, you MUST call exactly one skill (tool).
            After the tool returns its result, briefly state what you did and stop. The
            runtime calls you again with refreshed RAM state for the next decision —
            you do not need to chain tools yourself. Never respond without first invoking
            a tool.

            Skills available (each is a single tool call):
            - pressStartUntilOverworld: title screen → overworld with default party
            - walkOverworldTo(targetX, targetY): greedy walk; aborts on encounter
            - battleFightAll: every alive character uses FIGHT until battle ends
            - walkUntilEncounter: walk randomly until a battle starts (good when blocked
              from a target tile by terrain)
            - askAdvisor(reason): consult the planner when stuck or at a phase boundary

            FF1 KNOWLEDGE:
            - worldX increases EAST; worldY increases SOUTH. North = lower worldY.
            - Goal: reach the Garland battle. Garland is a SCRIPTED encounter on the
              bridge tile NORTH of Coneria. Walking north from the starting tile
              (worldX≈0x92, worldY≈0x9E) eventually triggers Battle(Garland).
            - When walkOverworldTo doesn't make progress (RAM coords unchanged after
              calling), the path is blocked. Try walkUntilEncounter (random walk often
              gets unstuck) or askAdvisor for a different target.
            - In Battle phase, call battleFightAll. After PostBattle, resume walking north.
        """.trimIndent()
    }
}
