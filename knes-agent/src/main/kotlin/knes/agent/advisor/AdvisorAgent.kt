package knes.agent.advisor

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import knes.agent.llm.AgentRole
import knes.agent.llm.AnthropicSession
import knes.agent.llm.ModelRouter
import knes.agent.perception.FfPhase
import knes.agent.tools.EmulatorToolset

/**
 * Single-shot planner. Each plan() call does ONE Koog AIAgent.run (singleRunStrategy):
 * the LLM either returns plain text or invokes one read-only tool (getState/getScreen).
 * Read-only access — advisor must never mutate emulator state.
 */
class AdvisorAgent(
    private val anthropic: AnthropicSession,
    private val modelRouter: ModelRouter,
    private val toolset: EmulatorToolset,
) {
    private val readOnlyTools = ReadOnlyToolset(toolset)
    private val registry = ToolRegistry { tools(readOnlyTools) }

    private fun newAgent(phase: FfPhase): AIAgent<String, String> = AIAgent(
        promptExecutor = anthropic.executor,
        llmModel = modelRouter.modelFor(phase, AgentRole.ADVISOR),
        toolRegistry = registry,
        strategy = singleRunStrategy(),
        systemPrompt = systemPrompt,
        maxIterations = 8,   // Koog counts node executions; advisor may inspect state once + produce plan
    )

    suspend fun plan(phase: FfPhase, observation: String): String = try {
        newAgent(phase).run(observation)
    } catch (e: Exception) {
        if (e::class.simpleName == "AIAgentMaxNumberOfIterationsReachedException") {
            "ADVISOR_ITERATION_CAP: stay the course with previous plan"
        } else throw e
    }

    companion object {
        val systemPrompt: String = """
            You are the planner for an autonomous Final Fantasy (NES) agent.
            Given the current emulator state, output a short numbered plan (1–6 steps) the
            executor will follow until the next phase change. Each step must be actionable
            using the available kNES skills:
              - pressStartUntilOverworld: title screen → overworld with default party
              - walkOverworldTo(x, y): greedy walk to coords; aborts on encounter
              - walkUntilEncounter: walk randomly until a battle starts
              - battleFightAll: every alive character uses FIGHT until battle ends

            FF1 KNOWLEDGE — use this to plan, not your training-data memory of the game:
              - Coordinate system: worldX increases EAST; worldY increases SOUTH.
                So lower worldY = north, higher worldY = south.
              - Goal: reach the Garland boss battle. Garland is a SCRIPTED encounter
                on the bridge tile NORTH of Coneria castle. Stepping onto the bridge
                tile triggers Battle(Garland) automatically — no random rolls needed.
              - From the post-party-creation starting position (typically
                worldX≈0x90, worldY≈0x9E), walk NORTH (decreasing worldY) toward the
                bridge. Random encounters along the way are normal — handle them with
                battleFightAll, then continue walking.
              - The bridge is roughly 15-30 tiles north of the start.

            Output: a numbered plan with concrete coords. Do NOT execute the plan;
            only describe it.
        """.trimIndent()
    }
}
