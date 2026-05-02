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
              - exitBuilding: walk SOUTH out of any town/castle interior (use when Indoors)
              - walkOverworldTo(x, y): greedy walk to coords on the OVERWORLD; aborts on encounter
              - walkUntilEncounter: walk randomly until a battle starts
              - battleFightAll: every alive character uses FIGHT until battle ends

            FF1 KNOWLEDGE — use this to plan, not your training-data memory of the game:
              - Phases you may see: TitleOrMenu, Overworld(x, y), Indoors(localX, localY),
                Battle(...), PostBattle, PartyDefeated.
              - Indoors = inside a town/castle; uses LOCAL coords. walkOverworldTo will not
                work indoors. **First call exitBuilding to reach the world map.** After
                exiting, phase becomes Overworld with the world coords showing where you
                emerged.
              - Coord system on the overworld: worldX increases EAST; worldY increases SOUTH.
                Lower worldY = north, higher worldY = south.
              - **CRITICAL: After party creation in V2, the party usually starts INSIDE
                Coneria castle (Indoors), not on the overworld.** First action when you see
                Indoors should be exitBuilding. Then navigate north on the overworld.
              - Goal: Garland is a SCRIPTED encounter on the bridge tile NORTH of Coneria
                castle. After exiting the castle to overworld, walk NORTH (decreasing worldY)
                toward the bridge. Random encounters along the way are normal — handle them
                with battleFightAll, then resume walking north.
              - The bridge is roughly 15-30 tiles north of where you exit the castle.

            Output: a numbered plan with concrete coords. Do NOT execute the plan;
            only describe it.
        """.trimIndent()
    }
}
