package knes.agent.advisor

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import knes.agent.llm.AgentRole
import knes.agent.llm.AnthropicSession
import knes.agent.llm.ModelRouter
import knes.agent.perception.AsciiMapRenderer
import knes.agent.perception.FfPhase
import knes.agent.perception.FogOfWar
import knes.agent.perception.MapSession
import knes.agent.perception.ViewportSource
import knes.agent.tools.EmulatorToolset

/**
 * Single-shot planner. Each plan() call does ONE Koog AIAgent.run (singleRunStrategy):
 * the LLM either returns plain text or invokes one read-only tool (getState/getScreen).
 * Read-only access — advisor must never mutate emulator state.
 *
 * V2.3: when called with an Overworld phase and a configured ViewportSource + FogOfWar,
 * the user-facing observation is augmented with an ASCII map (terrain + fog stats +
 * blocked tiles) — Gemini-PP finding: textual tile grids match raw screenshots for
 * spatial reasoning at much lower cost.
 *
 * V2.4: extended to also render ASCII map for Indoors phase when interiorSource is provided.
 */
class AdvisorAgent(
    private val anthropic: AnthropicSession,
    private val modelRouter: ModelRouter,
    private val toolset: EmulatorToolset,
    private val viewportSource: ViewportSource? = null,  // overworld source, kept for backward-compat
    private val interiorSource: ViewportSource? = null,
    private val fog: FogOfWar? = null,
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

    suspend fun plan(phase: FfPhase, observation: String): String {
        val augmented = augmentMapView(phase, observation)
        return try {
            newAgent(phase).run(augmented)
        } catch (e: Exception) {
            if (e::class.simpleName == "AIAgentMaxNumberOfIterationsReachedException") {
                "ADVISOR_ITERATION_CAP: stay the course with previous plan"
            } else throw e
        }
    }

    private fun augmentMapView(phase: FfPhase, observation: String): String {
        val (src, partyXY) = when (phase) {
            is FfPhase.Overworld -> viewportSource to (phase.x to phase.y)
            is FfPhase.Indoors -> {
                if (phase.mapId >= 0 && interiorSource is MapSession) {
                    interiorSource.ensureCurrent(phase.mapId)
                }
                interiorSource to (phase.localX to phase.localY)
            }
            else -> null to null
        }
        if (src == null || partyXY == null) return observation
        val f = fog ?: return observation
        val viewport = src.readViewport(partyXY)
        f.merge(viewport)
        val mapBlock = AsciiMapRenderer.render(viewport, f)
        return buildString {
            append(observation)
            if (!observation.endsWith('\n')) append('\n')
            append('\n')
            append(mapBlock)
        }
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
              - V2.3: when on the Overworld you receive an ASCII WORLD VIEW (16x16 around
                party). Glyphs: @=party, .=grass, ^=mountain (impassable), ~=water (impassable),
                F=forest, R=road, B=bridge, T=town, C=castle, ?=unseen, X=blocked-confirmed.
                Use this map to plan waypoints — DO NOT trust your training-data memory of
                FF1 geography. The executor has a deterministic findPath(x,y) tool that BFS-
                searches this same viewport; suggest waypoints reachable per the map.
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
