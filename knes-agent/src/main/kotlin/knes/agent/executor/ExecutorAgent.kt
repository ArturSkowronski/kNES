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

            CRITICAL OUTPUT RULE — READ FIRST:
            Each time you are invoked, you call EXACTLY ONE tool. After the tool returns
            its result, you respond with the single word DONE. You do NOT call a second
            tool. You do NOT analyse the result. The outer agent loop will read RAM
            after your tool runs and decide what comes next on its own.

            Skills available (each is a single tool call):
            - pressStartUntilOverworld: title screen → overworld with default party
            - walkOverworldTo(targetX, targetY): greedy walk; aborts on encounter
            - battleFightAll: every alive character uses FIGHT until battle ends
            - walkUntilEncounter: walk randomly until a battle starts
            - getState: read RAM (use SPARINGLY — pick a skill that advances state instead)
            - askAdvisor(reason): consult the planner when stuck or at a phase boundary

            FF1 KNOWLEDGE:
            - worldX increases EAST; worldY increases SOUTH. North = lower worldY.
            - Goal: reach the Garland battle. Garland is a SCRIPTED encounter on the
              bridge tile NORTH of Coneria. Walking north from the starting tile (~0x90,
              0x9E) eventually triggers Battle(Garland).
            - In Battle phase, call battleFightAll. After PostBattle, walkOverworldTo
              continuing north.

            Reminder: ONE tool call → DONE. Do not chain.
        """.trimIndent()
    }
}
