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
              - exitInterior: PRIMARY in Indoors. Decoder-based BFS exit walker.
                Reliable on castles/dungeons, ~13% step success on town overlays.
                Suggest this first for any Indoors phase.
              - walkInteriorVision: ESCALATION. Single-frame vision navigator. Only
                suggest after exitInterior failed twice on the same mapId AND the
                screenshot reveals a clearly walkable direction the decoder missed.
              - walkOverworldTo(x, y): deterministic BFS walk to coords on the OVERWORLD;
                aborts on encounter
              - walkUntilEncounter: walk randomly until a battle starts
              - battleFightAll: every alive character uses FIGHT until battle ends

            FF1 KNOWLEDGE — use this to plan, not your training-data memory of the game:
              - Phases you may see: TitleOrMenu, Overworld(x, y),
                Indoors(mapId, localX, localY), Battle(...), PostBattle, PartyDefeated.
              - Indoors = inside a town/castle/dungeon; uses LOCAL coords. walkOverworldTo
                will not work indoors. Call exitInterior to reach the world map. The skill
                drives sub-map transitions (stairs/warps) on its own; keep calling it until
                the phase becomes Overworld.
              - Coord system on the overworld: worldX increases EAST; worldY increases SOUTH.
                Lower worldY = north, higher worldY = south.
              - V2.3+: when on the Overworld you receive an ASCII WORLD VIEW (16x16 around
                party). Glyphs: @=party, .=grass, ^=mountain (impassable), ~=water (impassable),
                F=forest, R=road, B=bridge, T=town, C=castle, ?=unseen, X=blocked-confirmed.
                Use this map to plan waypoints — DO NOT trust your training-data memory of
                FF1 geography. The executor has a deterministic findPath(x,y) tool that BFS-
                searches this same viewport; suggest waypoints reachable per the map.
              - V4 hybrid: interior navigation defaults to the DECODER (exitInterior).
                When called with reason "stuck in interior" you have a screenshot
                available via getScreen — INSPECT IT and give the executor a single
                clear cardinal hint: "walk SOUTH from here", "try EAST 3 tiles",
                etc. Phrase as a numbered plan step the executor will follow with
                walkUntilEncounter or by tapping cardinal buttons via the existing
                walk skills. Only after two such hints fail should you escalate to
                walkInteriorVision.
              - For the overworld you may continue to propose (worldX, worldY)
                waypoints; that pathfinder is solid.
              - You may also receive an ASCII map of the interior; cross-reference
                it with the screenshot — they should agree on walls but the
                screenshot is ground truth for NPCs and dynamic obstacles.
              - V2.5: after pressStartUntilOverworld the party normally appears on the
                overworld at world (146, 158) — that is INSIDE the Coneria peninsula but
                NOT in any interior map (locationType=0, localX=0, localY=0). RAM-override
                in the phase classifier recognises this as Overworld(146,158) directly;
                you should NOT see Indoors here.
              - Real Indoors states arise after the party walks INTO an entrance: Coneria
                Castle entry tile (152, 159), Coneria Town entries around (151, 162), and
                the Chaos Shrine (Temple of Fiends) entry north of the peninsula.
              - Goal: AtGarlandBattle = Battle.enemyId == 0x7C. Garland is the BOSS of the
                Chaos Shrine (Temple of Fiends), an interior dungeon. He is NOT a scripted
                bridge encounter. To reach him you must (a) walk north on the overworld
                from spawn to the Chaos Shrine entrance, (b) enter the shrine, (c) navigate
                its dungeon, (d) defeat the shrine miniboss room.
              - V2.5.4 hard-impassable rule: TOWN and CASTLE tiles are IMPASSABLE for
                walkOverworldTo when they are NOT the destination. To enter Coneria Castle
                set walkOverworldTo(targetX=152, targetY=159) — that exact tile becomes
                walkable as the goal. Same for Chaos Shrine: pick the shrine's entry tile
                as the explicit target.
              - From spawn (146, 158) the path north on the overworld goes WEST first
                (around mountains/water near (146, 150) which are impassable), then up the
                grass corridor at x≈140, eventually east toward shrine area. The pathfinder
                handles this routing automatically over the full 256x256 map; trust its
                output. If findPath returns BLOCKED for a target, the target tile itself
                may be unreachable (isolated pocket) — pick a different waypoint.
              - Random encounters along the way are normal — handle them with battleFightAll,
                then resume walking. battleFightAll also dismisses the PostBattle modal.

            Output: a numbered plan with concrete coords. Do NOT execute the plan;
            only describe it.
        """.trimIndent()
    }
}
